// Phase 2 groundwork, run inside the extension: the weighted design of COSIGN.md 0.2
// (4 to sign; this browser holds 3 shares, each of three servers 1) made by DKG and used
// to sign, all locally. The servers are simulated in-page; nothing leaves the browser.
// Opened by test/weighted.ts; results land in window.result.

import { schnorr } from '@noble/curves/secp256k1.js';
import { bytesToHex, hexToBytes, randomBytes } from '@noble/hashes/utils.js';
import * as F from '../frost.ts';

const BROWSER = [1, 2, 3], SERVERS = [4, 5, 6], ALL = [...BROWSER, ...SERVERS];
const T = 4;

async function run() {
  const out: Record<string, unknown> = {};
  let t = performance.now();
  await F.load();
  out.loadMs = Math.round(performance.now() - t);

  // DKG: every share is a participant; the browser simply runs three of them.
  t = performance.now();
  const r1 = new Map(ALL.map(i => [i, F.dkg.part1(i, ALL.length, T)]));
  const others1 = (i: number) => ALL.filter(k => k !== i).map(k => [k, r1.get(k)!.package] as [number, string]);
  const r2 = new Map(ALL.map(i => [i, F.dkg.part2(r1.get(i)!.secret, others1(i))]));
  const to = (i: number) => ALL.filter(k => k !== i).map(k => [k, r2.get(k)!.packages.find(([d]) => d === i)![1]] as [number, string]);
  const done = new Map(ALL.map(i => [i, F.dkg.part3(i, r2.get(i)!.secret, others1(i), to(i))]));
  const groupX = done.get(1)!.groupX;
  out.dkgMs = Math.round(performance.now() - t);
  out.sameKeyEverywhere = ALL.every(i => done.get(i)!.groupX === groupX);
  const group: F.Group = { groupX, threshold: T, members: done.get(1)!.members };

  const trySign = (signers: number[]) => {
    const msg = bytesToHex(randomBytes(32));
    const c = signers.map(i => F.commit(group, i, done.get(i)!.share));
    const commits = c.map(x => x.commit);
    const partials = signers.map((i, n) => [i, F.sign(group, i, done.get(i)!.share, c[n].nonces, msg, commits)] as [number, string]);
    const sig = F.aggregate(group, msg, commits, partials);
    return schnorr.verify(hexToBytes(sig), hexToBytes(msg), hexToBytes(groupX));
  };
  const attempt = (signers: number[]) => { try { return trySign(signers) ? 'signs' : 'invalid'; } catch (e) { return `refused (${String((e as Error).message ?? e).slice(0, 60)})`; } };

  t = performance.now();
  out.browserPlusServer4 = attempt([...BROWSER, 4]);
  out.signMs = Math.round(performance.now() - t);
  out.browserPlusServer6 = attempt([...BROWSER, 6]);
  out.allThreeServers = attempt(SERVERS);
  out.browserAlone = attempt(BROWSER);
  return out;
}

run().then(r => { (window as any).result = r; }, e => { (window as any).result = { error: String(e?.stack ?? e) }; });
