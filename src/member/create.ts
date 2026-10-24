// "Create account": a brand-new key made by this browser and up to three servers run by
// different people, with no code and no phone (COSIGN.md 0.2, §9.5; the protocol is
// ~/cosign/server/core/enroll.ts). Nobody ever holds the key: this browser runs 3 of
// its shares, each server 1, and it takes 4 to sign.
//
//   find servers (servers.ts) → enroll_key to each host key → enroll {how: 'dkg', group,
//   plan} to each → the DKG run with all of them → this browser's member state.
//
// Public servers get no NIP-46 signer key (§9.2): sites reach this account through the
// extension, which runs every round itself.

import { nip19 } from 'nostr-tools';
import { bytesToHex, randomBytes } from '@noble/hashes/utils.js';
import * as F from '../frost.ts';
import { findServers, pickServers, pingHost, relayQuery, type Seed, type Server } from '../servers.ts';
import { Mailbox, pubOf } from './net.ts';
import { DkgRun } from './dkg.ts';
import type { MemberState } from './device.ts';

const hex = (n = 32) => bytesToHex(randomBytes(n));

/** Up to three servers for a new account, fastest first, one per operator. */
export async function chooseServers(extraSeeds: Seed[] = []): Promise<Server[]> {
  const found = await findServers({ query: relayQuery(), seeds: extraSeeds.length ? extraSeeds : undefined });
  return pickServers(found, { ping: s => pingHost(s), n: 3 });
}

export async function createAccount(o: { name: string; servers: Server[]; onStatus: (s: string) => void; gaveUp?: () => boolean }): Promise<MemberState> {
  if (!o.servers.length) throw new Error('No servers are taking new accounts right now. Try again later.');
  const sk = hex(), me = pubOf(sk);
  const relays = [...new Set(o.servers.flatMap(s => s.relays))].slice(0, 6);
  const replies = new Map<string, any>();
  let dkg = null as DkgRun | null;
  const box = new Mailbox(sk, relays, (m, from) => {
    if (m.type === 'dkg1' || m.type === 'dkg2') return dkg?.take(m, from);
    if (m.id && String(m.type).startsWith('enroll')) replies.set(m.id, m);
  });
  await box.ready(6000);

  /** Send until answered (the server answers a repeat the same way), up to 25 s. */
  const ask = async (to: string, msg: any) => {
    msg.id = hex(8);
    const until = Date.now() + 25_000;
    while (!replies.has(msg.id) && Date.now() < until) {
      box.send(to, msg);
      for (let i = 0; i < 20 && !replies.has(msg.id); i++) await new Promise(r => setTimeout(r, 200));
    }
    return replies.get(msg.id) ?? { type: 'no answer' };
  };

  try {
    o.onStatus(o.servers.length === 1 ? 'Asking a server to hold part of your key…' : `Asking ${o.servers.length} servers to hold part of your key…`);
    const keys = await Promise.all(o.servers.map(s => ask(s.pk, { type: 'enroll_key' })));
    const ok = o.servers.map((s, i) => ({ s, key: keys[i] })).filter(x => x.key.type === 'enroll_key');
    if (!ok.length) throw new Error('No server answered. Try again in a moment.');

    const name = o.name.trim().slice(0, 40) || 'Me';
    const group: { threshold: number; relays: string[]; members: any[] } = {
      threshold: 4, relays,
      members: [
        { idx: 1, name: 'This browser', role: 'phone', pk: me, sharePub: '', shares: [1, 2, 3].map(idx => ({ idx, sharePub: '' })) },
        ...ok.map((x, i) => ({ idx: 4 + i, name: x.s.nip05 ?? `Server ${i + 1}`, role: 'server', pk: x.key.pk, sharePub: '' })),
      ],
    };
    const plan = { session: hex(16), threshold: 4, holders: [1, 2, 3].map(idx => ({ idx, pk: me })).concat(ok.map((x, i) => ({ idx: 4 + i, pk: x.key.pk }))) };

    // Past here a key gets made: don't start if the caller has already fallen back.
    if (o.gaveUp?.()) throw new Error('gave up');
    const enrolled = await Promise.all(ok.map(x => ask(x.s.pk, { type: 'enroll', how: 'dkg', key: x.key.key, group, plan })));
    const bad = enrolled.find(e => e.type !== 'enrolled');
    if (bad) throw new Error(`A server couldn’t take your account (${bad.reason ?? 'no answer'}). Try again.`);

    o.onStatus('Making your key…');
    await F.load();
    const made = await new Promise<{ groupX: string; shares: { idx: number; secret: string }[]; members: { idx: number; sharePub: string }[] }>((resolve, reject) => {
      dkg = new DkgRun(plan, me, (to, msg) => box.send(to, msg), resolve);
      dkg.start();
      setTimeout(() => reject(new Error('Making your key took too long. Try again.')), 60_000);
    });
    const points = new Map(made.members.map(m => [m.idx, m.sharePub]));
    const members = group.members.map(m => ({ ...m, sharePub: points.get(m.idx)!, ...(m.shares ? { shares: m.shares.map((s: { idx: number }) => ({ idx: s.idx, sharePub: points.get(s.idx)! })) } : {}) }));
    // Keep the mailbox open a little: a server may still want our part-2 packages.
    setTimeout(() => box.close(), 21_000);
    return { sk, shares: made.shares, group: { groupX: made.groupX, npub: nip19.npubEncode(made.groupX), threshold: 4, relays, members }, profile: { name } } as MemberState;
  } catch (e) {
    (dkg as DkgRun | null)?.stop(); box.close();
    throw e;
  }
}
