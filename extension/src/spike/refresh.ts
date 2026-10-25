// Refresh, run inside the extension: a weighted group (this browser 1–3, a phone 4–6,
// servers 7 and 8; 4 to sign) made by DKG; the phone reports server 8 lost and refreshes
// with server 7 as the other dealer. Then: new shares sign under the same key; old ones,
// or the removed server's, don't mix with them. All local; results land in window.result.

import { schnorr } from '@noble/curves/secp256k1.js';
import { bytesToHex, hexToBytes, randomBytes } from '@noble/hashes/utils.js';
import * as F from '../frost.ts';
import { RefreshRun, type RefreshPlan } from '../member/refresh.ts';

const T = 4, ALL = [1, 2, 3, 4, 5, 6, 7, 8];
const holderOf = (i: number) => (i <= 3 ? 'ext' : i <= 6 ? 'phone' : i === 7 ? 's7' : 's8');

async function run() {
  await F.load();
  const out: Record<string, unknown> = {};
  const r1 = new Map(ALL.map(i => [i, F.dkg.part1(i, ALL.length, T)]));
  const others1 = (i: number) => ALL.filter(k => k !== i).map(k => [k, r1.get(k)!.package] as [number, string]);
  const r2 = new Map(ALL.map(i => [i, F.dkg.part2(r1.get(i)!.secret, others1(i))]));
  const to = (i: number) => ALL.filter(k => k !== i).map(k => [k, r2.get(k)!.packages.find(([d]) => d === i)![1]] as [number, string]);
  const done = new Map(ALL.map(i => [i, F.dkg.part3(i, r2.get(i)!.secret, others1(i), to(i))]));
  const groupX = done.get(1)!.groupX;
  const oldPoints = done.get(1)!.members;
  const share = (i: number) => ({ idx: i, secret: done.get(i)!.share });

  // The phone reports server 8 lost: everyone else moves to epoch 1.
  const left = ALL.filter(i => i !== 8);
  const plan: RefreshPlan = { epoch: 1, threshold: T, dealers: ['phone', 's7'], holders: left.map(i => ({ idx: i, pk: holderOf(i) })), points: left.map(i => [i, oldPoints.find(m => m.idx === i)!.sharePub]) };
  const runs = new Map<string, RefreshRun>();
  const results = new Map<string, { shares: { idx: number; secret: string }[]; points: [number, string][] }>();
  const send = (from: string) => (toPk: string, msg: object) => queueMicrotask(() => runs.get(toPk)?.take(msg, from));
  for (const who of ['ext', 'phone', 's7']) runs.set(who, new RefreshRun(plan, who, left.filter(i => holderOf(i) === who).map(share), send(who), r => results.set(who, r)));
  for (const r of runs.values()) r.start();
  await new Promise(r => setTimeout(r, 200));
  for (const r of runs.values()) r.stop();
  out.everyoneRefreshed = results.size === 3;
  const pts = results.get('ext')!.points;
  out.samePointsEverywhere = [...results.values()].every(r => JSON.stringify(r.points) === JSON.stringify(pts));
  const newGroup: F.Group = { groupX, threshold: T, members: pts.map(([idx, sharePub]) => ({ idx, sharePub })) };
  const newShare = (i: number) => results.get(holderOf(i))!.shares.find(s => s.idx === i)!;

  const attempt = (g: F.Group, shares: { idx: number; secret: string }[]) => {
    try {
      const msg = bytesToHex(randomBytes(32));
      const c = shares.map(s => F.commit(g, s.idx, s.secret));
      const commits = c.map(x => x.commit);
      const zs = shares.map((s, n) => [s.idx, F.sign(g, s.idx, s.secret, c[n].nonces, msg, commits)] as [number, string]);
      return schnorr.verify(hexToBytes(F.aggregate(g, msg, commits, zs)), hexToBytes(msg), hexToBytes(groupX)) ? 'signs' : 'invalid';
    } catch (e) { return `refused (${String((e as Error).message ?? e).slice(0, 50)})`; }
  };
  out.browserPlusServer7_new = attempt(newGroup, [1, 2, 3, 7].map(newShare));
  out.browserOld_plusServer7New = attempt(newGroup, [...[1, 2, 3].map(share), newShare(7)]);
  // The removed server's old share against the browser's new ones (as if it tried to rejoin a round).
  const withOld8: F.Group = { ...newGroup, members: [...newGroup.members, { idx: 8, sharePub: oldPoints.find(m => m.idx === 8)!.sharePub }] };
  out.browserNew_plusRemovedServer8 = attempt(withOld8, [...[1, 2, 3].map(newShare), share(8)]);
  return out;
}
run().then(r => { (window as any).result = r; }, e => { (window as any).result = { error: String(e?.stack ?? e) }; });
