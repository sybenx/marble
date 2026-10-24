// Phase 2: this browser as a member device of your Cosign key, holding 3 of the 4
// points it takes to sign (COSIGN.md 0.2, weighted). It runs each round itself and asks
// one server for the last point: its most reliable first, the next after 300 ms if
// that one is slow (§5.1). Nothing it signs needs your phone.
//
// Messages are Cosign's member protocol (~/cosign README, "Weighted keys"):
//   joining:  pair_hello → pair_offer {weighted, plan} → dkg1/dkg2 → pair_ack {groupX}
//   signing:  cosign_req {event, msg, participants, grant, commits} → cosign_res {commits, zs}
//   reading:  ecdh_req {peerX, participants, grant} → ecdh_res {points}
//   after:    phone_signed {event, grant, approved}, so the servers' history stays whole

import { getEventHash, type Event } from 'nostr-tools/pure';
import { nip19 } from 'nostr-tools';
import { schnorr } from '@noble/curves/secp256k1.js';
import { bytesToHex, hexToBytes, randomBytes } from '@noble/hashes/utils.js';
import * as F from '../frost.ts';
import { Mailbox, pubOf } from './net.ts';
import { DkgRun } from './dkg.ts';
import { allValid, combine, commitAll, partialAll, sharesOf, type Group, type Share } from './sign.ts';

export interface MemberState {
  sk: string;
  shares: Share[];
  group: Group & { relays: string[]; npub: string };
  signer?: string;
  /** Server member keys, fastest first, from real rounds. */
  order?: string[];
  /** Your name and picture, as last published or read. */
  profile?: { name?: string; picture?: string };
  /** The extension's own grant, for what it signs itself (your profile at Create account). */
  ownGrant?: string;
  /** The profile event last signed, as published. */
  profileEvent?: Event;
}

const HEDGE_MS = 300;
const rid = () => bytesToHex(randomBytes(8));

/** An answer that ends the round: the user must decide, or the server refuses outright. */
const FINAL = /^(needs-approval|refused|revoked):/;

export class Device {
  private box: Mailbox;
  private waiting = new Map<string, (r: any) => void>();
  private pinger: ReturnType<typeof setInterval>;

  constructor(public st: MemberState, private save: (st: MemberState) => void) {
    this.box = new Mailbox(st.sk, st.group.relays, (m, from, at) => this.handle(m, from, at));
    const ping = () => { for (const s of this.servers()) this.box.send(s.pk!, { type: 'ping', idx: st.shares[0].idx, ts: Date.now() }); };
    setTimeout(ping, 1500);
    this.pinger = setInterval(ping, 10_000);
  }

  close() { clearInterval(this.pinger); this.box.close(); }

  get pubkey() { return this.st.group.groupX; }
  private myIdxs() { return this.st.shares.map(s => s.idx); }
  servers() {
    const all = this.st.group.members.filter(m => m.role === 'server' && m.pk);
    const rank = (pk: string) => { const i = this.st.order?.indexOf(pk) ?? -1; return i < 0 ? 99 : i; };
    return all.sort((a, b) => rank(a.pk!) - rank(b.pk!));
  }

  private fastest(pk: string) {
    const order = [pk, ...(this.st.order ?? []).filter(x => x !== pk)];
    if (order.join() !== (this.st.order ?? []).join()) { this.st.order = order; this.save(this.st); }
  }

  /** Tell the servers about a website's grant. Its key only names the grant; it signs nothing. */
  grant(pk: string, origin: string, via: string) {
    for (const s of this.servers()) this.box.send(s.pk!, { type: 'app_grant', pk, origin, via });
  }

  forget(pk: string) {
    for (const s of this.servers()) this.box.send(s.pk!, { type: 'revoke', pk });
  }

  /** Send `make(id)` to `to` until an answer with that id arrives, or `ms` pass. */
  private ask(to: string, make: (id: string) => object, ms = 25_000): { answer: Promise<any>; cancel(): void } {
    const id = rid(), msg = make(id);
    let timer: ReturnType<typeof setTimeout>, gap = 1500, done = false;
    const answer = new Promise<any>(resolve => {
      const started = Date.now();
      const again = () => {
        if (done) return;
        if (Date.now() - started > ms) { done = true; this.waiting.delete(id); return resolve({ ok: false, reason: 'no answer' }); }
        this.box.send(to, msg);
        timer = setTimeout(again, gap); gap = Math.min(gap * 1.6, 6000);
      };
      this.waiting.set(id, r => { done = true; clearTimeout(timer); resolve(r); });
      void this.box.ready().then(again);
    });
    return { answer, cancel: () => { done = true; clearTimeout(timer); this.waiting.delete(id); } };
  }

  /**
   * Ask the servers in order, the next one HEDGE_MS after the last, for one round each;
   * `finish` turns a good answer into the result. The first result wins; an answer that
   * ends the round (approval needed, refused, removed) wins too, so a decline is final.
   */
  private hedge<T>(make: (server: Group['members'][number]) => { request: (id: string) => object; finish: (res: any) => T }): Promise<T> {
    const servers = this.servers();
    if (!servers.length) return Promise.reject(new Error('No server to sign with.'));
    return new Promise<T>((resolve, reject) => {
      const asks: { cancel(): void }[] = [], why: string[] = [];
      let settled = false, left = servers.length;
      const end = (f: () => void) => { if (settled) return; settled = true; asks.forEach(a => a.cancel()); f(); };
      servers.forEach((s, i) => setTimeout(() => {
        if (settled) return;
        const { request, finish } = make(s);
        const a = this.ask(s.pk!, request);
        asks.push(a);
        void a.answer.then(res => {
          if (settled) return;
          if (res.ok) {
            try { const out = finish(res); this.fastest(s.pk!); return end(() => resolve(out)); }
            catch (e) { why.push(`${s.name}: ${(e as Error).message}`); }
          } else if (FINAL.test(String(res.reason))) return end(() => reject(new Error(res.reason)));
          else why.push(`${s.name}: ${res.reason}`);
          if (--left === 0) end(() => reject(new Error(`Your servers didn’t sign (${why.join('; ')}).`)));
        });
      }, i * HEDGE_MS));
    });
  }

  /** Sign an event for a website's grant. `approved`: you confirmed it on this device. */
  async sign(t: { kind: number; content: string; tags: string[][]; created_at: number }, grant: string, approved = false): Promise<Event> {
    await F.load();
    const ev = { kind: t.kind, content: t.content, tags: t.tags, created_at: t.created_at, pubkey: this.pubkey };
    const msg = getEventHash(ev);
    const g = this.st.group, mine = this.st.shares;
    const sig = await this.hedge(s => {
      const round = commitAll(g, mine);
      const participants = [...this.myIdxs(), ...sharesOf(s)].sort((a, b) => a - b);
      return {
        request: id => ({ type: 'cosign_req', id, event: ev, msg, participants, grant, commits: round.commits, commit: round.commits[0], frost: 2, approved }),
        finish: res => {
          const theirs: [number, string][] = res.zs ?? [[sharesOf(s)[0], res.z]];
          const commits = [...round.commits, ...(res.commits ?? [res.commit])];
          if (!allValid(g, msg, commits, theirs)) throw new Error('a bad partial signature');
          const sig = combine(g, msg, commits, [...partialAll(g, mine, round.nonces, msg, commits), ...theirs]);
          if (!schnorr.verify(hexToBytes(sig), hexToBytes(msg), hexToBytes(this.pubkey))) throw new Error('the signature didn’t verify');
          return sig;
        },
      };
    });
    const signed = { ...ev, id: msg, sig } as Event;
    // History only: the servers keep it like anything they signed themselves.
    for (const s of this.servers()) this.box.send(s.pk!, { type: 'phone_signed', event: signed, grant, approved });
    return signed;
  }

  /** The shared secret with `peerX` (x-only hex), for NIP-04 and NIP-44. */
  async ecdh(peerX: string, grant: string): Promise<Uint8Array> {
    await F.load();
    const mine = this.st.shares;
    const x = await this.hedge(s => {
      const participants = [...this.myIdxs(), ...sharesOf(s)].sort((a, b) => a - b);
      return {
        request: id => ({ type: 'ecdh_req', id, peerX, participants, grant }),
        finish: res => F.ecdhCombine([...mine.map(m => F.ecdhPartial(m.idx, m.secret, participants, peerX)), ...(res.points ?? [res.point])]),
      };
    });
    return hexToBytes(x);
  }

  private handle(m: any, from: string, sentAt: number) {
    if ((m.type === 'cosign_res' || m.type === 'ecdh_res' || m.type === 'sign_res') && this.waiting.has(m.id)) { this.waiting.get(m.id)!(m); this.waiting.delete(m.id); return; }
    const server = this.st.group.members.find(x => x.role === 'server' && x.pk === from);
    if (!server) return;
    if (m.type === 'members' && Array.isArray(m.members)) {
      const me = this.st.shares[0].idx;
      this.st.group.members = m.members.map((x: any) => (sharesOf(x).includes(me) ? { ...x, pk: pubOf(this.st.sk) } : x));
      this.save(this.st);
    } else if (m.type === 'sign_req' && Date.now() / 1000 - sentAt < 30) {
      // A server running a round for a site that isn't this browser (a NIP-46 site, say).
      // Co-sign what it judged everyday; anything held is for a device you're looking at.
      const reply = (r: object) => this.box.send(from, { type: 'sign_res', id: m.id, ...r });
      if (m.hold || m.batch || m.event?.pubkey !== this.pubkey || getEventHash(m.event) !== m.msg) return reply({ ok: false, reason: 'not here' });
      void F.load().then(() => {
        const round = commitAll(this.st.group, this.st.shares);
        const zs = partialAll(this.st.group, this.st.shares, round.nonces, m.msg, [...(m.commits ?? [m.commit]), ...round.commits]);
        reply({ ok: true, commits: round.commits, zs, commit: round.commits[0], z: zs[0][1] });
      });
    } else if (m.type === 'ecdh_req' && Date.now() / 1000 - sentAt < 30) {
      void F.load().then(() => {
        const points = this.st.shares.map(s => F.ecdhPartial(s.idx, s.secret, m.participants, m.peerX));
        this.box.send(from, { type: 'ecdh_res', id: m.id, ok: true, points, point: points[0] });
      });
    }
  }

  // --- Joining ----------------------------------------------------------------------

  /**
   * Join a server's weighted key by its pairing code (`cosign:pair?pk=…&t=…&r=…`), making
   * the key with the servers so nobody ever holds it. Until public servers take new
   * accounts this is the way in; "Create account" will do the same without a code.
   */
  static join(uri: string, onStatus: (s: string) => void): Promise<MemberState> {
    const u = new URL(uri.trim().replace(/^cosign:/, 'cosign://'));
    const serverPk = u.searchParams.get('pk'), token = u.searchParams.get('t'), relays = u.searchParams.getAll('r');
    if (u.protocol !== 'cosign:' || u.host !== 'pair' || !serverPk || !token || !relays.length) return Promise.reject(new Error('That isn’t a server’s pairing code.'));
    const sk = bytesToHex(randomBytes(32)), pk = pubOf(sk);
    return new Promise((resolve, reject) => {
      let dkg: DkgRun | null = null, finished = false;
      const box: Mailbox = new Mailbox(sk, relays, async (m, from) => {
        if (m.type === 'dkg1' || m.type === 'dkg2') return dkg?.take(m, from);
        if (m.type !== 'pair_offer' || from !== serverPk || dkg || finished) return;
        if (!m.weighted) { finished = true; clearInterval(hello); box.close(); return reject(new Error('That server’s key isn’t a weighted one, so this browser can’t join it yet.')); }
        onStatus('Making your key with your servers…');
        await F.load();
        dkg = new DkgRun(m.plan, pk, (to, msg) => box.send(to, msg), r => {
          const group = { ...m.group, groupX: r.groupX, npub: nip19.npubEncode(r.groupX) };
          const mine = r.shares.map(s => s.idx);
          for (const x of group.members) {
            const pts = sharesOf(x).map((idx: number) => ({ idx, sharePub: r.members.find(y => y.idx === idx)!.sharePub }));
            x.sharePub = pts[0].sharePub; if (x.shares) x.shares = pts;
            if (sharesOf(x).includes(mine[0])) x.pk = pk;
          }
          const ack = () => box.send(from, { type: 'pair_ack', token, groupX: r.groupX });
          ack(); setTimeout(ack, 4000);
          finished = true; clearInterval(hello);
          // Stay a little longer: the servers may still want our part-2 packages.
          setTimeout(() => box.close(), 21_000);
          resolve({ sk, shares: r.shares, group, signer: m.signer });
        });
        dkg.start();
      });
      const hello = setInterval(() => box.send(serverPk, { type: 'pair_hello', token }), 6000);
      void box.ready().then(() => { box.send(serverPk, { type: 'pair_hello', token }); onStatus('Asking your server…'); });
      setTimeout(() => { if (!finished) { finished = true; clearInterval(hello); dkg?.stop(); box.close(); reject(new Error('Your server didn’t answer. Is it running, and is the code fresh?')); } }, 90_000);
    });
  }
}
