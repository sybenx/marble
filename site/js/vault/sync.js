// Vaults (vaults.md 7.14): one owner device's session with one vault. Reads (§7.2, §7.3), catches up (§8.3), walks
// epochs (§3.1), loads checkpoints (§9.2), and writes (§7.1): commits numbered and signed only when published, a
// `rekey` under the old epoch before the first write under a new one (§4.1, §8.3), segments on Blossom (§4.1).
// The network and the signer are passed in, so this runs the same in a page and in a node test.
//   net: {req(urls, filter) → events (.answered), publish(urls, event) → {ok, failed}, get(servers, sha) → bytes|null,
//         put(server, bytes, auth) → bool, mirror(server, fromUrl, auth) → bool}
//   signer: {pubkey, sign(template) → signed event, decrypt(eph, ct) → text}  (the account; nip44_decrypt as it)
//   store: {get(key), set(key, value)} (this browser's storage: device root, certs, own commits; never owner secrets)
import { hex, unhex, isHex, canon, parse, utf8, unutf8, signEvent, pubHex, randomKey, random, sha256 } from "./crypto.js";
import * as V from "./core.js";
import { Vault } from "./state.js";

const isObj = (o) => !!o && typeof o === "object" && !Array.isArray(o);
const now = () => Math.floor(Date.now() / 1000);
const HEAD_COMMITS = 8 * 1024, SEGMENT_MAX = 64 * 1024;
const RHO0 = () => V.rhoOf([], []);

/** Every keyring entry the account has (§3.6), opened: [{event, payload}]. One decrypt per entry not opened before. */
export async function readKeyrings({ me, relays, net, signer, opened = new Map() }) {
  const got = await net.req(relays, { kinds: [V.KIND.KEYRING], authors: [me] });
  const newest = new Map(); // d → newest event (a copy dated more than a day ahead is treated as missing, §3.6)
  for (const e of got) {
    const d = e.tags.find((t) => t[0] === "d")?.[1];
    if (!d || e.pubkey !== me || e.created_at > now() + 86400) continue;
    const o = newest.get(d);
    if (!o || e.created_at > o.created_at) newest.set(d, e);
  }
  const out = [], refused = [];
  for (const e of newest.values()) {
    if (!opened.has(e.id)) {
      // (a signer that won't decrypt is told apart from an entry that isn't for us: it isn't remembered, and it's said)
      let err = null;
      const p = await V.openKeyring(e, me, (eph, ct) => signer.decrypt(eph, ct).catch((x) => { err = x; throw x; }));
      if (err) { refused.push(err); continue; }
      opened.set(e.id, p);
    }
    const p = opened.get(e.id);
    if (p) out.push({ event: e, payload: p });
  }
  return Object.assign(out, { answered: got.answered, refused, found: newest.size });
}
/** The account's notes vault (Android's rule): among owner entries of profile "notes", the lowest genesis id. */
export function notesVault(entries) {
  const own = entries.filter((x) => x.payload.role === "owner" && x.payload.profile === "notes");
  const genesis = own.map((x) => x.payload.genesis).sort()[0];
  return genesis ? own.filter((x) => x.payload.genesis === genesis) : [];
}

export class Session {
  constructor({ me, entries, net, signer, store, deviceName = "Marble Web", accountRelays = [], blossom = null, log = () => {} }) {
    Object.assign(this, { me, net, signer, store, deviceName, accountRelays, log, blossom: blossom && normalizeServers(blossom) });
    this.uploadLog = []; // [{server, why}] for servers skipped
    const p = entries[0].payload;
    this.vid = p.vault; this.genesis = p.genesis;
    this.entries = entries; // keyring entries for this vault (every d)
    this.gens = V.mergeGens(...entries.map((x) => x.payload.gens));
    this.epochs = new Map(); // kid → {e (epoch), gen (id), rho (hex), from (rekey dvk | "genesis")}
    this.heads = new Map(); // `${dvk}/${kid}` → newest head event seen
    this.loadedBlobs = new Set();
    this.rekeys = []; // [{dvk, commit, op}]
    this.missingGen = new Set();
    this.gaps = new Set(); // blobs a live commit or checkpoint names that won't load yet (§7.4): "syncing…", never deletion
    this.ignoredGens = new Set(); // named by a rekey, missing from every keyring entry after a complete re-read
    this.suspect = new Set(); // dvks whose rekey named such a generation
    this.vault = null;
    this.firstLoadDone = false;
  }
  get relays() {
    const c = this.vault?.config();
    return [...new Set([...(c?.relays || []), ...this.entries.flatMap((x) => x.payload.relays || [])])];
  }
  /** The vault's own servers: its config's, then its keyring entries'. */
  get configServers() {
    const c = this.vault?.config();
    return normalizeServers([...(c?.servers || []), ...this.entries.flatMap((x) => x.payload.servers || [])]);
  }
  /**
   * Where blobs go (the person's decision, 2026-11-18): the account's own kind 10063 list (BUD-03) in its order, else
   * the vault's servers. Where they're read from: that list, then the vault's.
   */
  get writeServers() { return this.blossom?.length ? this.blossom : this.configServers; }
  get servers() { return normalizeServers([...(this.blossom || []), ...this.configServers]); }
  gen(id) { return this.gens.find((g) => g.id === id); }

  // ── reading ──

  /** Finds the first generation (§3.1: the one whose genesis-epoch key opens the genesis), then walks every epoch. */
  async open() {
    for (const g of this.gens.filter((x) => x.prev === null)) {
      const e = V.epoch(unhex(g.secret), unhex(this.vid), RHO0());
      const aKey = V.authKey(unhex(e.K), unhex(this.vid));
      const heads = await this.net.req(this.relays, { kinds: [V.KIND.HEAD], "#v": [e.locator] });
      const v = new Vault({ vid: this.vid, genesis: this.genesis, owner: this.me, aKey });
      const tmp = new Map([[e.kid, e]]);
      let anchored = false;
      for (const h of heads) {
        const b = V.openHead(h, tmp, this.vid, this.genesis);
        if (!b) continue;
        for (const x of b.commits) v.addCommit(x);
        if (v.events.has(this.genesis) && V.readAuth(v.events.get(this.genesis), aKey, this.vid)?.type === "genesis") anchored = true;
      }
      if (!anchored) continue;
      this.first = g; this.K0 = unhex(e.K); this.aKey = aKey; this.dKey = V.dedupKey(this.K0, unhex(this.vid));
      this.vault = v;
      this.epochs.set(e.kid, { e, gen: g.id, rho: e.rho, from: "genesis" });
      break;
    }
    if (!this.vault) throw new Error("vault not found: no head opens the genesis");
    this.depths = V.chainDepths(this.gens, this.first.id);
    await this.catchUp();
    this.firstLoadDone = true;
    return this;
  }

  /** Fetches heads under every epoch it can open until nothing new turns up; segments, checkpoints and batches too. */
  async catchUp() {
    for (let round = 0; round < 50; round++) {
      const before = this.epochs.size, ver = this.vault.version;
      const byLoc = new Map([...this.epochs.values()].map((x) => [x.e.locator, x]));
      const got = await this.net.req(this.relays, { kinds: [V.KIND.HEAD], "#v": [...byLoc.keys()] });
      this.answered = got.answered;
      const byKid = new Map([...this.epochs].map(([k, x]) => [k, x.e]));
      for (const h of got) await this.takeHead(h, byKid);
      this.followRekeys();
      if (this.epochs.size === before) this.deadEnd();
      await this.loadCheckpoints();
      await this.loadBatches();
      if (this.missingGen.size && !(await this.rereadKeyring())) break;
      if (this.epochs.size === before && this.vault.version === ver && !this.missingGen.size) break;
    }
    this.vetting();
  }
  async takeHead(h, byKid) {
    const b = V.openHead(h, byKid, this.vid, this.genesis);
    if (!b) return;
    const key = h.pubkey + "/" + b.kid, o = this.heads.get(key);
    if (o && o.created_at >= h.created_at) return; // never backwards (§10)
    this.heads.set(key, h);
    for (const x of b.commits) {
      const c = V.readCommit(x, this.genesis);
      if (c && c.kid === b.kid && c.dvk === h.pubkey) this.vault.addCommit(x); // §10.1: the commit's kid is the head's epoch
    }
    if (isObj(b.checkpoint)) this.vault.addCommit(b.checkpoint);
    // the segment chain, back until it reaches commits already held
    let ref = b.segment, n = 0;
    while (V.refOK(ref) && !this.loadedBlobs.has(ref.parts[0]) && n++ < 1000) {
      const seg = await this.blobJSON(ref);
      if (!seg || seg.genesis !== this.genesis || seg.dvk !== h.pubkey) break;
      this.loadedBlobs.add(ref.parts[0]);
      let fresh = 0;
      for (const x of seg.commits || []) { const had = this.vault.byId.size; const c = this.vault.addCommit(x); if (c && this.vault.byId.size > had) fresh++; }
      if (!fresh && (seg.commits || []).length) break;
      ref = seg.prev;
    }
  }
  /** New epochs from `rekey` ops of owner dvks (§3.1): K(V(state), rho), checked against `to`. */
  followRekeys() {
    const ro = this.vault.roster();
    for (const c of this.vault.byId.values()) {
      const d = ro.dvks.get(c.dvk);
      if (!d?.ownerRoot) continue;
      for (const op of c.ops) {
        if (op?.op !== "rekey" || !isHex(op.to, 16) || !Array.isArray(op.state) || !isHex(op.rho, 32)) continue;
        if (!this.rekeys.some((r) => r.commit === c.id)) this.rekeys.push({ dvk: c.dvk, commit: c.id, op, kid: c.kid });
        if (this.epochs.has(op.to)) continue;
        const g = this.gen(op.state[0]);
        if (!g) { if (!this.ignoredGens.has(op.state[0])) this.missingGen.add(op.state[0]); continue; }
        const e = V.epoch(unhex(g.secret), unhex(this.vid), unhex(op.rho));
        if (e.kid === op.to) this.epochs.set(e.kid, { e, gen: g.id, rho: op.rho, from: c.dvk });
      }
    }
  }
  /** Where the walk dead-ends: every generation held × every ρ a rekey named, and the current ρ (§3.1). */
  deadEnd() {
    const rhos = new Set([...this.rekeys.map((r) => r.op.rho), this.vault.readers().rho]);
    for (const g of this.gens) if (this.depths.has(g.id)) for (const rho of rhos) {
      const e = V.epoch(unhex(g.secret), unhex(this.vid), unhex(rho));
      if (!this.epochs.has(e.kid)) this.epochs.set(e.kid, { e, gen: g.id, rho, from: "tried", unproven: true });
    }
  }
  async loadCheckpoints() {
    for (const cp of this.vault.countingCheckpoints()) {
      if (this.vault.checkpoints.has(cp.id)) continue;
      const op = cp.ops.find((o) => o?.op === "checkpoint" && V.refOK(o.ref));
      const root = await this.blobJSON(op.ref);
      if (!isObj(root) || root.genesis !== this.genesis) continue;
      for (const k of ["covers", "lost", "authority", "kept", "decided", "unknown_ops", "shards"]) if (isObj(root[k]) && V.refOK(root[k].ref)) root[k] = await this.blobJSON(root[k].ref);
      const shards = [];
      let complete = true;
      for (const ref of Array.isArray(root.shards) ? root.shards : []) { const s = await this.blobJSON(ref); if (s) shards.push(s); else complete = false; }
      this.vault.addCheckpoint(cp.id, root, shards, complete);
    }
  }
  async loadBatches() {
    for (const c of this.vault.byId.values()) for (const op of c.ops) {
      if (op?.op !== "batch" || !V.refOK(op.ref) || this.vault.batches.has(op.ref.parts[0])) continue;
      const ops = await this.blobJSON(op.ref);
      if (Array.isArray(ops)) this.vault.addBatch(op.ref, ops);
    }
  }
  async blobJSON(ref) {
    try { const v = parse(unutf8(await this.blob(ref))); this.gaps.delete(ref.parts[0]); return v; }
    catch { if (V.refOK(ref)) this.gaps.add(ref.parts[0]); return null; }
  }
  /** A blob's plaintext: each part from any vault server, checked (§6.2). */
  async blob(ref) {
    if (!V.refOK(ref)) throw new Error("bad ref");
    const parts = [];
    for (const sha of ref.parts) { const p = await this.net.get(this.servers, sha); if (!p) throw new Error("missing"); parts.push(p); }
    return V.nv1Open(ref, parts);
  }

  // ── generations (§3.1) ──

  /** Which generations this device may write under: vetted by a trusted owner dvk's rekey, or started here. */
  vetting() {
    const ro = this.vault.roster(), known = this.knownTriggers();
    const labels = new Map(); // keykin label → live roots carrying it
    for (const [root, list] of ro.deviceCerts) if (ro.liveRoot(root)) for (const c of list) if (c.keykin) labels.set(c.keykin, new Set([...(labels.get(c.keykin) || []), root]));
    const self = this.store.get("root") && pubHex(unhex(this.store.get("root")));
    const trusted = (dvk) => {
      const d = ro.dvks.get(dvk);
      if (!d?.live || !d.ownerRoot) return false;
      return (ro.deviceCerts.get(d.root) || []).every((c) => !c.keykin || [...labels.get(c.keykin)].every((r) => r === d.root || r === self));
    };
    const knows = (g) => ["roots", "members", "grants"].every((k) => (g.because?.[k] || []).every((x) => known[k].has(x)));
    const vetted = new Set([this.first.id, ...(this.store.get("started:" + this.genesis) || [])]);
    for (const r of this.rekeys) {
      if (!trusted(r.dvk)) continue;
      // the generation it names and every ancestor, provided each names only triggers this device knows
      for (let g = this.gen(r.op.state[0]); g && !vetted.has(g.id); g = this.gen(g.prev)) { if (!knows(g)) break; vetted.add(g.id); }
    }
    this.vetted = vetted;
    this.current = V.currentGen(this.gens, this.depths, vetted);
    this.uncovered = this.uncoveredTriggers(known);
    return this.current;
  }
  /** Triggers a page knows: owner roots with a counting revocation, and through their labels members and grants. */
  knownTriggers() {
    const ro = this.vault.roster(), out = { roots: new Set(), members: new Set(), grants: new Set() };
    for (const root of ro.revoked.keys()) if (ro.deviceCerts.has(root)) {
      out.roots.add(root);
      for (const c of ro.deviceCerts.get(root)) if (c.keykin) (c.keykin.startsWith("grant:") ? out.grants.add(c.keykin.slice(6)) : out.members.add(c.keykin));
    }
    return out;
  }
  uncoveredTriggers(known) {
    const covered = new Set();
    for (let g = this.current; g; g = this.gen(g.prev)) if (this.vetted.has(g.id)) for (const r of g.because?.roots || []) covered.add(r);
    return [...known.roots].filter((r) => !covered.has(r));
  }

  /**
   * A new generation on top of the current one for every trigger this device knows is uncovered (§3.1): added to the
   * keyring entry, re-sealed with a fresh ephemeral key and published (one npub signature), then vetted here. The next
   * write announces it with a rekey. Returns the generation, or null when none was needed.
   */
  async startGeneration() {
    if (!this.uncovered?.length || !this.current) return null;
    const fresh = await readKeyrings({ me: this.me, relays: this.keyringRelays(), net: this.net, signer: this.signer, opened: this.opened ||= new Map() });
    const mine = fresh.filter((x) => x.payload.vault === this.vid && x.payload.genesis === this.genesis);
    if (mine.length) { this.entries = mine; this.gens = V.mergeGens(this.gens, ...mine.map((x) => x.payload.gens)); this.depths = V.chainDepths(this.gens, this.first.id); this.vetting(); }
    if (!this.uncovered.length) return null; // (another device covered it meanwhile)
    const g = V.newGeneration(this.current.id, { roots: this.uncovered });
    const gens = V.mergeGens(this.gens, [g]);
    // re-seal the entry holding the most generations (every copy is merged into it), under its own d
    const base = [...this.entries].sort((x, y) => y.payload.gens.length - x.payload.gens.length)[0].payload;
    const payload = { ...base, gens };
    const ev = await this.signer.sign(V.keyringTemplate(payload, this.me, Math.max(now(), ...this.entries.map((x) => x.event.created_at + 1))));
    const res = await this.net.publish(this.keyringRelays(), ev);
    if (!res.ok.length) throw new Error("keyring not published");
    this.entries = [...this.entries.filter((x) => x.payload.d !== payload.d), { event: ev, payload }];
    this.gens = gens; this.depths = V.chainDepths(this.gens, this.first.id);
    this.store.set("started:" + this.genesis, [...(this.store.get("started:" + this.genesis) || []), g.id]);
    this.vetting();
    return g;
  }
  /** A rekey named a generation not held: every keyring entry read again (§3.1). True if that found something new. */
  async rereadKeyring() {
    const fresh = await readKeyrings({ me: this.me, relays: this.keyringRelays(), net: this.net, signer: this.signer, opened: this.opened ||= new Map() });
    const mine = fresh.filter((x) => x.payload.vault === this.vid && x.payload.genesis === this.genesis);
    const had = this.gens.length;
    if (mine.length) { this.entries = mine; this.gens = V.mergeGens(this.gens, ...mine.map((x) => x.payload.gens)); this.depths = V.chainDepths(this.gens, this.first.id); }
    for (const id of [...this.missingGen]) if (this.gen(id)) this.missingGen.delete(id);
    // a complete re-read still without it: that rekey is ignored and its device shown as suspect
    if (fresh.answered > 0) for (const id of this.missingGen) {
      this.ignoredGens.add(id);
      for (const r of this.rekeys) if (r.op.state[0] === id) this.suspect.add(r.dvk);
    }
    if (fresh.answered > 0) this.missingGen.clear();
    return this.gens.length > had;
  }
  /** Where the account's keyring entries go: its own write relays (§3.6), else the vault's. */
  keyringRelays() { return this.accountRelays?.length ? this.accountRelays : this.relays; }

  /** The epoch to write under: the current generation and the current ρ (§3.1). */
  writeEpoch() {
    const g = this.current, rho = this.vault.readers().rho;
    const e = V.epoch(unhex(g.secret), unhex(this.vid), unhex(rho));
    if (!this.epochs.has(e.kid)) this.epochs.set(e.kid, { e, gen: g.id, rho, from: "self" });
    return this.epochs.get(e.kid);
  }

  // ── this device (§3.3, §3.4) ──

  /** This browser's root, device cert (one npub signature, once) and this vault's dvk and sub-cert. */
  async device() {
    let rootH = this.store.get("root");
    if (!isHex(rootH, 32)) { rootH = hex(randomKey()); this.store.set("root", rootH); }
    const rootSec = unhex(rootH), root = pubHex(rootSec);
    let cert = this.store.get("deviceCert");
    if (!cert || V.readCert(cert)?.root !== root || cert.pubkey !== this.me) {
      // a page writes no keykin label; a Keykin device answering for it adds its grant's (§3.4)
      cert = await this.signer.sign(V.deviceCertTemplate(root, null, now()));
      const c = V.readCert(cert);
      if (!c || c.type !== "device" || c.root !== root || cert.pubkey !== this.me) throw new Error("device cert refused");
      this.store.set("deviceCert", cert);
    }
    const nKey = "n:" + this.genesis;
    let n = this.store.get(nKey);
    if (!Number.isSafeInteger(n)) { n = 0; this.store.set(nKey, n); }
    const dvkSec = V.dvkFor(rootSec, this.genesis, n), dvk = pubHex(dvkSec);
    let sub = this.store.get("sub:" + this.genesis);
    if (!sub || V.readCert(sub)?.dvk !== dvk) { sub = V.subCert(rootSec, this.genesis, n, dvkSec, this.deviceName, now()); this.store.set("sub:" + this.genesis, sub); }
    return { rootSec, root, cert, dvkSec, dvk, sub, n, storageSec: V.storageFor(dvkSec) };
  }
  /** Whether this browser's root was revoked by the account (§8.5: "This device was removed"). */
  removed() {
    const r = this.store.get("root");
    return !!r && this.vault.roster().revoked.has(pubHex(unhex(r)));
  }

  // ── writing (§7.1) ──

  /**
   * Publishes ops as one commit (after a catch-up). Moves epochs first if needed, with a rekey under the old one.
   * Returns the commit, or throws (nothing published) when it may not write.
   */
  async commit(ops, { catchUp = true } = {}) {
    if (!this.firstLoadDone) throw new Error("first load not done");
    if (catchUp) await this.catchUp();
    if (this.removed()) throw new Error("this device was removed");
    if (catchUp && this.uncovered?.length) await this.startGeneration();
    if (this.missingGen.size) throw new Error("waiting for the keyring"); // a rekey names a generation not held (§3.1)
    if (!this.current) throw new Error("no generation to write under");
    const dev = await this.device();
    const lock = (fn) => (globalThis.navigator?.locks ? navigator.locks.request("marble-vault-" + this.genesis, fn) : fn());
    return lock(async () => {
      const mine = this.vault.chain(dev.dvk);
      const tip = mine.at(-1) || null;
      const target = this.writeEpoch();
      const certOps = [{ op: "auth", event: dev.cert }, { op: "auth", event: dev.sub }];
      let prev = tip, seq = tip ? tip.seq + 1 : 0;
      const sign = (kid, body) => {
        const c = V.makeCommit({ genesis: this.genesis, dvk: dev.dvk, seq, prev: prev ? prev.id : null, kid, time: now(), ops: body }, dev.dvkSec);
        const got = this.vault.addCommit({ c: c.c, sig: c.sig });
        prev = got; seq++;
        return got;
      };
      if (tip && tip.kid !== target.e.kid) {
        // §4.1: the last commit under the epoch being left: the rekey, the authority events that moved ρ, every
        // owner-root revocation held, and this device's certs; then everything moves to the segment
        const readers = this.vault.readers().readers;
        const moved = [...this.vault.events.values()].filter((e) => e.kind === 4810 || V.readCert(e)?.type === "revoke");
        const rk = { op: "rekey", to: target.e.kid, state: [target.gen], rho: target.rho, wraps: readers.map((r) => V.keyWrap(r, this.vid, [target.e])) };
        const old = this.epochs.get(tip.kid);
        if (!old) throw new Error("can't open this device's previous epoch");
        sign(tip.kid, [...moved.map((event) => ({ op: "auth", event })), ...certOps, rk]);
        await this.publishHead(dev, old.e, { segmentAll: true });
      }
      const first = !this.vault.chain(dev.dvk).some((c) => c.ops.some((o) => o?.op === "auth" && o.event?.id === dev.sub.id));
      const c = sign(target.e.kid, [...(first ? certOps : []), ...ops]);
      await this.publishHead(dev, target.e);
      return c;
    });
  }

  /** This dvk's head for an epoch: its commits under it, the older ones in segments once past 8 KiB (§4.1). */
  async publishHead(dev, e, { segmentAll = false } = {}) {
    const under = this.vault.chain(dev.dvk).filter((c) => c.kid === e.kid);
    const segKey = "seg:" + this.genesis + ":" + dev.dvk; // one chain of segments per dvk, across epochs (§4.1)
    let seg = this.store.get(segKey) || { ref: null, through: -1, size: 0 }; // the newest segment: its ref, the last seq in it
    let inHead = under.filter((c) => c.seq > seg.through);
    const size = (cs) => cs.reduce((n, c) => n + c.c.length + 140, 0);
    if (inHead.length && (segmentAll || size(inHead) > HEAD_COMMITS)) {
      // the oldest move to the segment: all of them on leaving an epoch, else all but the newest
      const move = segmentAll ? inHead : inHead.slice(0, -1);
      if (move.length) {
        seg = await this.writeSegment(dev, seg, move);
        this.store.set(segKey, seg);
        inHead = under.filter((c) => c.seq > seg.through);
      }
    }
    const key = dev.dvk + "/" + e.kid, prevHead = this.heads.get(key);
    const body = { v: 1, vault: this.vid, genesis: this.genesis, kid: e.kid, commits: inHead.map((c) => ({ c: c.c, sig: c.sig })),
      segment: seg.ref, checkpoint: this.ownCheckpoint(dev.dvk), seen: this.vault.seen(dev.dvk) };
    const ev = signEvent(dev.dvkSec, V.headTemplate(e, body, Math.max(now(), (prevHead?.created_at || 0) + 1)));
    const res = await this.net.publish(this.relays, ev);
    if (res.ok.length < Math.min(2, this.relays.length)) throw new Error("head not taken by two relays");
    this.heads.set(key, ev);
    return ev;
  }
  ownCheckpoint(dvk) {
    const c = this.vault.chain(dvk).filter((x) => x.ops.some((o) => o?.op === "checkpoint")).at(-1);
    return c ? { c: c.c, sig: c.sig } : null;
  }
  /** Appends commits to this dvk's newest segment (a new blob each time; a new segment past 64 KiB). On two servers. */
  async writeSegment(dev, seg, commits) {
    const base = seg.ref && seg.size < SEGMENT_MAX ? (await this.blobJSON(seg.ref)) : null;
    const prior = base ? base.commits : [];
    const prevRef = base ? base.prev : seg.ref;
    const through = commits.at(-1).seq;
    const plain = { genesis: this.genesis, dvk: dev.dvk, commits: [...prior, ...commits.map((c) => ({ c: c.c, sig: c.sig }))], prev: prevRef ?? null };
    const bytes = utf8(canon(plain));
    const ref = await this.upload(dev, bytes, 2);
    return { ref, through, size: bytes.length };
  }
  /** Uploads a blob (nv1) to the vault servers: true once on `need` of them (§6.3). Returns its ref. */
  /**
   * Uploads a blob (nv1) to the first two write servers that take every part (one if the list has one), with one
   * upload auth per server signed by the account (the person's decision, 2026-11-18). Returns its ref.
   */
  async upload(_dev, plain, need = 2, key) {
    const { ref, parts } = await V.nv1Seal(plain, key);
    const servers = this.writeServers, want = Math.min(need, servers.length);
    let on = 0;
    for (const server of servers) {
      if (on >= want) break;
      let why = null;
      try {
        const auth = await this.signer.sign(blossomAuth("upload", ref.parts, server));
        for (let i = 0; i < parts.length && !why; i++) {
          const r = await this.net.put(server, parts[i], auth);
          if (r !== true && !(r && r.ok && (!r.sha256 || r.sha256 === ref.parts[i]))) why = (r && r.why) || "refused";
        }
      } catch (err) { why = String(err?.message || err); }
      if (why) this.uploadLog.push({ server, why, at: now() }); else on++;
    }
    if (!want || on < want) throw new Error("storage unavailable");
    return ref;
  }

  /**
   * Writes a checkpoint (§9.1) of what this device holds, when it holds every counting checkpoint whole: shards by
   * entry prefix, keyed from their content, then a commit holding only the checkpoint op and this device's certs.
   */
  async checkpoint() {
    if ([...this.vault.countingCheckpoints()].some((c) => !this.vault.checkpoints.get(c.id)?.complete)) return null;
    const dev = await this.device();
    const content = this.vault.checkpointContent();
    const total = Object.keys(content.entries).length;
    let p = 0; while (p < 4 && total >= 2000 * 16 ** p) p++;
    const buckets = new Map();
    for (const [entry, e] of Object.entries(content.entries)) { const k = p ? entry.slice(0, p) : ""; if (!buckets.has(k)) buckets.set(k, {}); buckets.get(k)[entry] = e; }
    const shards = [];
    for (let i = 0; i < 16 ** p; i++) {
      const k = p ? i.toString(16).padStart(p, "0") : "";
      const plain = utf8(canon({ entries: buckets.get(k) || {} }));
      shards.push(await this.upload(dev, plain, 2, V.shardKey(this.dKey, plain)));
    }
    const prevCp = this.ownCheckpoint(dev.dvk);
    const root = { genesis: this.genesis, covers: content.covers, lost: {}, authority: content.authority, kept: content.kept, decided: content.decided,
      unknown_ops: content.unknown_ops, depth: p, shards, prev: prevCp ? this.cpRef(prevCp) : null };
    const ref = await this.upload(dev, utf8(canon(root)), 2);
    // (the checkpoint's commit holds nothing else but the certs, §5.2)
    const c = await this.commit([{ op: "checkpoint", ref }, { op: "auth", event: dev.cert }, { op: "auth", event: dev.sub }], { catchUp: false });
    this.vault.addCheckpoint(c.id, root, shards.map((_, i) => ({ entries: buckets.get(p ? i.toString(16).padStart(p, "0") : "") || {} })));
    return c;
  }
  cpRef(x) { try { return parse(x.c).ops.find((o) => o.op === "checkpoint")?.ref ?? null; } catch { return null; } }
}

/** BUD-11 auth for a vault blob (Android's contract): kind 24242, content "nostr-vault", server tag the domain. */
/** BUD-11 auth for a vault blob: kind 24242, content "nostr-vault", one x per part, server the domain[:port]. */
export function blossomAuth(verb, shas, server) {
  return { kind: 24242, content: "nostr-vault", created_at: now(),
    tags: [["t", verb], ...[].concat(shas).map((x) => ["x", x]), ["expiration", String(now() + 300)], ["server", new URL(server).host.toLowerCase()]] };
}
/** A server list as both apps read it: trimmed, no trailing "/", http(s) only, no duplicates (ignoring case), in order. */
export function normalizeServers(list) {
  const out = [], seen = new Set();
  for (const raw of list || []) {
    if (typeof raw !== "string") continue;
    const u = raw.trim().replace(/\/+$/, "");
    if (!/^https?:\/\/[^\s/]/i.test(u)) continue;
    const k = u.toLowerCase();
    if (!seen.has(k)) { seen.add(k); out.push(u); }
  }
  return out;
}
/** The account's server list (kind 10063, BUD-03): its `server` tags, newest event, normalized; null when there's none. */
export function serverList(events, me) {
  const e = events.filter((x) => x.kind === 10063 && x.pubkey === me).sort((a, b) => b.created_at - a.created_at)[0];
  return e ? normalizeServers(e.tags.filter((t) => t[0] === "server").map((t) => t[1])) : null;
}
export { random, sha256 };
