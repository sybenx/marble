// When Create account can't reach enough servers: the key is made here, kept wrapped,
// and used to sign right away ("not protected yet"); as soon as servers answer it is
// dealt to them (this browser 3 shares, each server 1, 4 to sign), and the whole key is
// wiped. Dealing is Cosign's 'dealt' enrollment (~/cosign/server/core/enroll.ts), split
// the way ~/cosign/site/src/import.ts splits a key.

import { secp256k1, schnorr } from '@noble/curves/secp256k1.js';
import { bytesToHex, hexToBytes, randomBytes } from '@noble/hashes/utils.js';
import { nip19 } from 'nostr-tools';
import { Mailbox, pubOf } from './net.ts';
import type { MemberState } from './device.ts';
import type { Server } from '../servers.ts';

export interface Pending {
  /** The key, encrypted with a browser key that can't be read out (see wrap). */
  wrapped: string;
  iv: string;
  pubkey: string;
  name: string;
  since: number;
}

const N = secp256k1.Point.Fn.ORDER;
const mod = (a: bigint) => ((a % N) + N) % N;
const hex32 = (x: bigint) => x.toString(16).padStart(64, '0');

// --- Wrapping: AES-GCM under a non-extractable key kept in IndexedDB ----------------------

function db(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const r = indexedDB.open('cosign', 1);
    r.onupgradeneeded = () => r.result.createObjectStore('keys');
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}
async function wrapKey(): Promise<CryptoKey> {
  const d = await db();
  const get = () => new Promise<CryptoKey | undefined>((res, rej) => { const q = d.transaction('keys').objectStore('keys').get('wrap'); q.onsuccess = () => res(q.result); q.onerror = () => rej(q.error); });
  const have = await get();
  if (have) return have;
  const k = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
  await new Promise<void>((res, rej) => { const t = d.transaction('keys', 'readwrite'); t.objectStore('keys').put(k, 'wrap'); t.oncomplete = () => res(); t.onerror = () => rej(t.error); });
  return k;
}

export async function makePending(name: string): Promise<Pending> {
  let s = mod(BigInt('0x' + bytesToHex(randomBytes(32)))) || 1n;
  // BIP-340: keep the even-y form, so the npub and every later share agree.
  if (secp256k1.Point.BASE.multiply(s).toBytes(true)[0] === 3) s = N - s;
  const secret = hexToBytes(hex32(s));
  const iv = randomBytes(12);
  const wrapped = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv: iv as BufferSource }, await wrapKey(), secret as BufferSource));
  return { wrapped: bytesToHex(wrapped), iv: bytesToHex(iv), pubkey: bytesToHex(schnorr.getPublicKey(secret)), name, since: Date.now() };
}

/** The key, for signing while not protected yet. Wiped from memory by the caller after use. */
export async function unwrap(p: Pending): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: hexToBytes(p.iv) as BufferSource }, await wrapKey(), hexToBytes(p.wrapped) as BufferSource));
}

/** Split so any `t` of `n` shares sign as the key (as site/src/import.ts deal does). */
function deal(secret: Uint8Array, n: number, t: number) {
  const a0 = mod(BigInt('0x' + bytesToHex(secret)));
  const coeffs = [a0, ...Array.from({ length: t - 1 }, () => mod(BigInt('0x' + bytesToHex(randomBytes(32)))))];
  const f = (x: bigint) => coeffs.reduceRight((acc, c) => mod(acc * x + c), 0n);
  return Array.from({ length: n }, (_, i) => { const v = f(BigInt(i + 1)); return { idx: i + 1, secret: hex32(v), sharePub: bytesToHex(secp256k1.Point.BASE.multiply(v).toBytes(true)) }; });
}

/**
 * Deal the key to these servers (at least two: Cosign's rule for a new account) and
 * become a device of it. Resolves with the member state; the caller then deletes the
 * pending key.
 */
export async function dealTo(p: Pending, servers: Server[], onStatus: (s: string) => void = () => {}): Promise<MemberState> {
  if (servers.length < 2) throw new Error('needs two servers');
  const sk = bytesToHex(randomBytes(32)), me = pubOf(sk);
  const relays = [...new Set(servers.flatMap(s => s.relays))].slice(0, 6);
  const replies = new Map<string, any>();
  const box = new Mailbox(sk, relays, m => { if (m.id && String(m.type).startsWith('enroll')) replies.set(m.id, m); });
  await box.ready(6000);
  const ask = async (to: string, msg: any) => {
    msg.id = bytesToHex(randomBytes(8));
    const until = Date.now() + 20_000;
    while (!replies.has(msg.id) && Date.now() < until) {
      box.send(to, msg);
      for (let i = 0; i < 20 && !replies.has(msg.id); i++) await new Promise(r => setTimeout(r, 200));
    }
    return replies.get(msg.id) ?? { type: 'no answer' };
  };
  try {
    onStatus('Protecting your account with your servers…');
    const keys = await Promise.all(servers.map(s => ask(s.pk, { type: 'enroll_key' })));
    const ok = servers.map((s, i) => ({ s, key: keys[i] })).filter(x => x.key.type === 'enroll_key');
    if (ok.length < 2) throw new Error('not enough servers answered');
    const secret = await unwrap(p);
    const shares = deal(secret, 3 + ok.length, 4);
    secret.fill(0);
    const mine = shares.slice(0, 3);
    const group = {
      groupX: p.pubkey, npub: nip19.npubEncode(p.pubkey), threshold: 4, relays,
      members: [
        { idx: 1, name: 'This browser', role: 'phone', pk: me, sharePub: mine[0].sharePub, shares: mine.map(s => ({ idx: s.idx, sharePub: s.sharePub })) },
        ...ok.map((x, i) => ({ idx: 4 + i, name: x.s.nip05 ?? `Server ${i + 1}`, role: 'server', pk: x.key.pk, sharePub: shares[3 + i].sharePub })),
      ],
    };
    const done = await Promise.all(ok.map((x, i) => ask(x.s.pk, { type: 'enroll', how: 'dealt', key: x.key.key, group, share: shares[3 + i].secret })));
    const bad = done.find(d => d.type !== 'enrolled');
    if (bad) throw new Error(bad.reason ?? 'a server didn’t take it');
    return { sk, shares: mine.map(s => ({ idx: s.idx, secret: s.secret })), group, epoch: 0, profile: { name: p.name } } as MemberState;
  } finally {
    setTimeout(() => box.close(), 5000);
  }
}
