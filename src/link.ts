// NIP-46 to your servers, over relays, for every key this browser holds: its own (the
// pairing) and one per website. One set of relay connections serves them all.
//
// Adapted from ~/cosign/site/src/client.ts. A request is published to every relay at
// once and published again, freshly wrapped, until the answer arrives. Your servers keep
// their answers and send them again to a repeat, so a dropped event in either direction
// costs a retry, not a timeout.

import { Relay } from 'nostr-tools/relay';
import { finalizeEvent, generateSecretKey, getPublicKey, verifyEvent, type Event, type EventTemplate } from 'nostr-tools/pure';
import * as nip44 from 'nostr-tools/nip44';
import { bytesToHex, hexToBytes } from '@noble/hashes/utils.js';

export const KIND_NIP46 = 24133;

const now = () => Math.floor(Date.now() / 1000);

interface Key { sk: Uint8Array; pk: string; ck: Uint8Array }

export class Link {
  private conns = new Map<string, Relay | null>();
  private subs = new Map<string, { close(): void }>();
  private keys = new Map<string, Key>();
  private waiting = new Map<string, (r: { result?: string; error?: string }) => void>();
  private seen = new Set<string>();
  private closed = false;
  /** Relay connection changes, for a status line. */
  onStatus: (up: number, of: number) => void = () => {};

  constructor(readonly signer: string, readonly relays: string[]) {
    for (const url of relays) void this.open(url);
  }

  /** Listen for answers to this key. Returns its public key. */
  add(skHex: string): string {
    const sk = hexToBytes(skHex);
    const pk = getPublicKey(sk);
    if (this.keys.has(pk)) return pk;
    this.keys.set(pk, { sk, pk, ck: nip44.v2.utils.getConversationKey(sk, this.signer) });
    for (const [url, r] of this.conns) if (r?.connected) this.subscribe(url, r);
    return pk;
  }

  private async open(url: string) {
    if (this.closed) return;
    this.conns.set(url, null);
    try {
      const r = await Relay.connect(url);
      r.onclose = () => { this.conns.set(url, null); this.status(); if (!this.closed) setTimeout(() => this.open(url), 3000); };
      this.conns.set(url, r);
      this.subscribe(url, r);
      this.status();
    } catch {
      if (!this.closed) setTimeout(() => this.open(url), 3000);
    }
  }

  private subscribe(url: string, r: Relay) {
    this.subs.get(url)?.close();
    if (this.keys.size === 0) return;
    this.subs.set(url, r.subscribe([{ kinds: [KIND_NIP46], '#p': [...this.keys.keys()], authors: [this.signer], since: now() - 30 }], {
      onevent: (e: Event) => this.receive(e),
    }));
  }

  private status() {
    this.onStatus([...this.conns.values()].filter(r => r?.connected).length, this.relays.length);
  }

  private receive(e: Event) {
    if (this.seen.has(e.id) || !verifyEvent(e)) return;
    this.seen.add(e.id);
    const to = e.tags.find(t => t[0] === 'p')?.[1];
    const key = to && this.keys.get(to);
    if (!key) return;
    try {
      const r = JSON.parse(nip44.v2.decrypt(e.content, key.ck));
      const done = this.waiting.get(`${key.pk}:${r.id}`);
      if (done) { this.waiting.delete(`${key.pk}:${r.id}`); done(r); }
    } catch { /* not for us */ }
  }

  private publish(key: Key, body: string) {
    // A fresh event every time: strfry refuses an id it has already seen.
    const e = finalizeEvent({ kind: KIND_NIP46, created_at: now(), tags: [['p', this.signer]], content: nip44.v2.encrypt(body, key.ck) } as EventTemplate, key.sk);
    for (const r of this.conns.values()) if (r?.connected) r.publish(e).catch(() => {});
  }

  /** One NIP-46 call as the key `pk` (added first). Re-sent until answered; gives up after `ms`. */
  call(pk: string, method: string, params: string[], ms = 75_000): Promise<string> {
    const key = this.keys.get(pk);
    if (!key) return Promise.reject(new Error('unknown key'));
    const id = bytesToHex(generateSecretKey().slice(0, 8));
    const body = JSON.stringify({ id, method, params });
    const slot = `${pk}:${id}`;
    return new Promise((resolve, reject) => {
      let gap = 1500, timer: ReturnType<typeof setTimeout>;
      const started = Date.now();
      const again = () => {
        if (Date.now() - started > ms) { this.waiting.delete(slot); return reject(new Error('Your signer didn’t answer. Is your server running?')); }
        this.publish(key, body);
        timer = setTimeout(again, gap);
        gap = Math.min(gap * 1.6, 8000);
      };
      this.waiting.set(slot, r => { clearTimeout(timer); r.error ? reject(new Error(r.error)) : resolve(r.result ?? ''); });
      // Give the sockets a moment on a cold start.
      setTimeout(again, [...this.conns.values()].some(r => r?.connected) ? 0 : 800);
    });
  }

  close() { this.closed = true; for (const r of this.conns.values()) r?.close(); }
}

export const newKey = () => bytesToHex(generateSecretKey());
