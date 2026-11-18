// Vaults (vaults.md 7.14): what a device holds of one vault, and the state it shows: "a pure function of the authority
// events, the live owner dvks' commits and the counting checkpoints" (§5.3). No network here: sync.js feeds it.
import { readCert, readAuth, readerSet, currentConfig, putOK, readCommit, refOK } from "./core.js";
import { isHex } from "./crypto.js";

const isObj = (o) => !!o && typeof o === "object" && !Array.isArray(o);

export class Vault {
  /** vid, genesis (ids, hex), owner (npub hex), aKey (the auth key, bytes). */
  constructor({ vid, genesis, owner, aKey }) {
    Object.assign(this, { vid, genesis, owner, aKey });
    this.events = new Map(); // id → AUTH or CERT event, wherever found (they count on their signatures)
    this.commits = new Map(); // dvk → Map(seq → Map(id → commit))
    this.byId = new Map(); // commit id → commit
    this.checkpoints = new Map(); // checkpoint commit id → {root, shards: [shard objects], complete}
    this.batches = new Map(); // batch blob sha (first part) → ops, once loaded
    this.version = 0;
  }

  /** An AUTH or CERT event (from an `auth` op, a checkpoint's `authority`, a contribution's certs). */
  addEvent(e) {
    if (!isObj(e) || typeof e.id !== "string" || this.events.has(e.id)) return false;
    if (e.kind === 4811 ? !readCert(e) : !readAuth(e, this.aKey, this.vid)) return false;
    this.events.set(e.id, e);
    this.version++;
    return true;
  }
  /** A carried commit {c, sig}, checked on its own (§10.2). Returns the commit, or null. */
  addCommit(x) {
    const c = readCommit(x, this.genesis);
    if (!c) return null;
    if (this.byId.has(c.id)) return this.byId.get(c.id);
    let seqs = this.commits.get(c.dvk);
    if (!seqs) this.commits.set(c.dvk, (seqs = new Map()));
    if (!seqs.has(c.seq)) seqs.set(c.seq, new Map());
    seqs.get(c.seq).set(c.id, c);
    this.byId.set(c.id, c);
    for (const op of c.ops) {
      if (op?.op === "auth" && isObj(op.event)) this.addEvent(op.event);
      if (op?.op === "kept" && isObj(op.commit)) this.addCommit(op.commit);
      if (op?.op === "rekey" && Array.isArray(op.events)) op.events.forEach((e) => this.addEvent(e));
    }
    this.version++;
    return c;
  }
  /** A batch blob's ops, once fetched (keyed by the ref's first part). */
  addBatch(ref, ops) { if (Array.isArray(ops)) { this.batches.set(ref.parts[0], ops); this.version++; } }
  /** A checkpoint's root blob and its shards, once fetched (§9). */
  addCheckpoint(commitId, root, shards, complete = true) {
    if (!isObj(root)) return;
    this.checkpoints.set(commitId, { root, shards, complete });
    for (const e of listOf(root.authority)) this.addEvent(e);
    for (const k of listOf(root.kept)) this.addCommit(k);
    for (const u of listOf(root.unknown_ops)) if (isObj(u?.commit)) this.addCommit(u.commit);
    this.version++;
  }

  /** The ops a commit carries, batches expanded (those loaded): [{op, commit}]. */
  opsOf(c) {
    const out = [];
    for (const op of c.ops) {
      if (!isObj(op)) continue;
      if (op.op === "batch" && refOK(op.ref)) { for (const b of this.batches.get(op.ref.parts[0]) || []) if (isObj(b) && (b.op === "put" || b.op === "contrib")) out.push(b); }
      else out.push(op);
    }
    return out;
  }

  /** Device certs, sub-certs and revocations: who is live (§2). Memoised per version. */
  roster() {
    if (this._roster?.v === this.version) return this._roster.r;
    const certs = [...this.events.values()].filter((e) => e.kind === 4811).map((e) => ({ e, c: readCert(e) }));
    const deviceCerts = new Map(); // root → [{keykin, cert}]
    const revoked = new Map(); // root → [revocation content] (by the owner only)
    for (const { e, c } of certs) {
      if (c.type === "device" && c.by === this.owner) deviceCerts.set(c.root, [...(deviceCerts.get(c.root) || []), { keykin: c.keykin ?? null, at: e.created_at, id: e.id }]);
      if (c.type === "revoke" && c.by === this.owner) revoked.set(c.root, [...(revoked.get(c.root) || []), c]);
    }
    const liveRoot = (r) => deviceCerts.has(r) && !revoked.has(r);
    // sub-certs count only when carried by a commit signed by the dvk they name (§2)
    const subs = new Map(); // dvk → Map(content → sub)
    for (const [dvk, seqs] of this.commits) for (const m of seqs.values()) for (const c of m.values()) for (const op of c.ops) {
      if (op?.op !== "auth" || op.event?.kind !== 4811) continue;
      const s = readCert(op.event);
      if (s?.type !== "sub" || s.dvk !== dvk || s.genesis !== this.genesis) continue;
      if (!subs.has(dvk)) subs.set(dvk, new Map());
      subs.get(dvk).set(op.event.content, s);
    }
    const dvks = new Map(); // dvk → {root, n, name, live}
    for (const [dvk, m] of subs) {
      const s = [...m.values()][0];
      dvks.set(dvk, { dvk, root: s.root, n: s.n, name: s.name || "", storage: s.storage, live: m.size === 1 && liveRoot(s.root), ownerRoot: deviceCerts.has(s.root) });
    }
    const r = { deviceCerts, revoked, liveRoot, dvks, isLive: (dvk) => !!dvks.get(dvk)?.live };
    this._roster = { v: this.version, r };
    return r;
  }

  auths() { return [...this.events.values()].filter((e) => e.kind === 4810).map((e) => readAuth(e, this.aKey, this.vid)).filter(Boolean); }
  /** Readers, ended, ρ (§3.1). */
  readers() { return readerSet(this.auths(), this.owner); }
  config() { return currentConfig(this.auths(), this.owner); }

  /** Every commit held, per dvk, oldest first, and the tip of each dvk's chain (for numbering and `seen`). */
  chain(dvk) {
    const seqs = this.commits.get(dvk);
    if (!seqs) return [];
    return [...seqs.keys()].sort((a, b) => a - b).flatMap((s) => [...seqs.get(s).values()]);
  }
  /** Forks: dvks with two different commits at one seq (§7.4). */
  forks() { const out = []; for (const [dvk, seqs] of this.commits) for (const [seq, m] of seqs) if (m.size > 1) out.push({ dvk, seq, commits: [...m.values()] }); return out; }

  /** The counting checkpoints (§9.2): per live owner root, the highest (n, seq) among its live dvks' checkpoint commits; plus kept ones. */
  countingCheckpoints() {
    const ro = this.roster(), best = new Map(); // root → commit
    const isCp = (c) => c.ops.some((op) => op?.op === "checkpoint" && refOK(op.ref));
    for (const [dvk, d] of ro.dvks) {
      if (!d.live || !ro.dvks.get(dvk).ownerRoot) continue;
      for (const c of this.chain(dvk)) if (isCp(c)) {
        const o = best.get(d.root), od = o && ro.dvks.get(o.dvk);
        if (!o || d.n > od.n || (d.n === od.n && c.seq > o.seq)) best.set(d.root, c);
      }
    }
    const out = new Map([...best.values()].map((c) => [c.id, c]));
    // kept: in the keep of an owner-signed revocation of an owner root, the commit signed by one of that root's dvks
    const keeps = new Map(); // commit id → revoked root
    for (const [root, list] of ro.revoked) if (ro.deviceCerts.has(root)) for (const rv of list) for (const id of rv.keep) keeps.set(id, root);
    const keptCarried = new Set();
    for (const [dvk, d] of ro.dvks) if (d.live) for (const c of this.chain(dvk)) for (const op of c.ops) if (op?.op === "kept" && isObj(op.commit)) keptCarried.add(op.commit);
    for (const cp of out.values()) for (const k of listOf(this.checkpoints.get(cp.id)?.root?.kept)) keptCarried.add(k);
    for (const k of keptCarried) {
      const c = readCommit(k, this.genesis);
      if (c && keeps.has(c.id) && ro.dvks.get(c.dvk)?.root === keeps.get(c.id) && isCp(c)) out.set(c.id, c);
    }
    return [...out.values()];
  }

  /**
   * The state (§5.3): entries → {current: [put] (lowest ver first), counting: Map(ver → put)}, with conflicts where
   * current has several. Memoised per version.
   */
  state() {
    if (this._state?.v === this.version) return this._state.s;
    const ro = this.roster();
    const counting = new Map(); // entry → Map(ver → put)
    const held = new Map(); // `${entry}/${ver}` → put (counting or not), for supersession through non-counting puts
    const by = new Map(); // `${entry}/${ver}` → dvk that carried it (for "deleted on device")
    const count = (p, dvk) => {
      if (!putOK(p)) return;
      const k = p.entry + "/" + p.ver;
      if (!counting.has(p.entry)) counting.set(p.entry, new Map());
      const m = counting.get(p.entry), o = m.get(p.ver);
      // two different puts sharing a ver: the lower SHA-256 of canonical JSON wins (never in practice)
      if (!o) m.set(p.ver, p);
      held.set(k, p);
      if (dvk && !by.has(k)) by.set(k, dvk);
    };
    for (const [dvk, seqs] of this.commits) {
      const live = ro.isLive(dvk);
      for (const m of seqs.values()) for (const c of m.values()) for (const op of this.opsOf(c)) {
        if (op.op !== "put") continue;
        if (live) count(op, dvk); else if (putOK(op)) held.set(op.entry + "/" + op.ver, op);
      }
    }
    const deadEdges = []; // [superseding ver, dead ver, entry]
    const cps = this.countingCheckpoints();
    for (const cp of cps) {
      const got = this.checkpoints.get(cp.id);
      for (const sh of got?.shards || []) for (const [entry, e] of Object.entries(sh?.entries || {})) {
        for (const p of e.current || []) if (p?.entry === entry) count(p, null);
        for (const [dead, sups] of Object.entries(e.dead || {})) for (const s of sups || []) deadEdges.push([s, dead, entry]);
      }
    }
    // supersession (§5.3): edges from counting versions' supersedes, and from counting checkpoints' dead lists
    // (ignored where they lie on a cycle); through non-counting puts, their own supersedes in turn
    const out = new Map(); // `${entry}/${ver}` → [targets]
    const edge = (entry, from, to) => { const k = entry + "/" + from; out.set(k, [...(out.get(k) || []), entry + "/" + to]); };
    for (const p of held.values()) for (const s of p.supersedes) edge(p.entry, p.ver, s);
    const reach = (from, to, seen = new Set()) => from === to || (!seen.has(from) && (seen.add(from), (out.get(from) || []).some((n) => reach(n, to, seen))));
    const deadOK = deadEdges.filter(([s, d, entry]) => !reach(entry + "/" + d, entry + "/" + s));
    for (const [s, d, entry] of deadOK) edge(entry, s, d);
    const superseded = new Set(), countsK = (k) => { const [e, v] = k.split("/"); return !!counting.get(e)?.has(v); };
    const visit = (k) => { for (const t of out.get(k) || []) if (!superseded.has(t)) { superseded.add(t); if (!countsK(t)) visit(t); } };
    for (const [entry, m] of counting) for (const ver of m.keys()) visit(entry + "/" + ver);
    for (const [, d, entry] of deadOK) { const k = entry + "/" + d; if (!superseded.has(k)) { superseded.add(k); if (!countsK(k)) visit(k); } }
    const entries = new Map();
    for (const [entry, m] of counting) {
      const current = [...m.values()].filter((p) => !superseded.has(entry + "/" + p.ver)).sort((a, b) => (a.ver < b.ver ? -1 : 1));
      entries.set(entry, { entry, current, by: (ver) => by.get(entry + "/" + ver) || null });
    }
    const s = { entries, superseded, held };
    this._state = { v: this.version, s };
    return s;
  }

  /** The `seen` map for a head (§4.1): how far this device applied each other owner dvk, the 32 most recent. */
  seen(self) {
    const ro = this.roster(), out = [];
    for (const dvk of this.commits.keys()) {
      if (dvk === self || !ro.dvks.get(dvk)?.ownerRoot) continue;
      const tip = this.chain(dvk).at(-1);
      if (tip) out.push([dvk, tip]);
    }
    return Object.fromEntries(out.sort((a, b) => b[1].time - a[1].time).slice(0, 32).map(([d, c]) => [d, [c.seq, c.id]]));
  }

  /** What a checkpoint (§9.1) of this state holds: {covers, authority, kept, decided, unknown_ops, entries}. */
  checkpointContent() {
    const st = this.state(), ro = this.roster();
    const covers = {};
    for (const dvk of this.commits.keys()) {
      if (!ro.dvks.get(dvk)?.ownerRoot) continue;
      const ch = this.chain(dvk); let tip = null;
      for (const c of ch) { if (tip && (c.seq !== tip.seq + 1 || c.prev !== tip.id)) break; if (!tip && c.seq !== 0 && !this.coveredBefore(dvk, c)) break; tip = c; }
      if (tip) covers[dvk] = [[tip.seq, tip.id]];
    }
    const entries = {};
    const deadByEntry = new Map();
    for (const k of st.superseded) {
      const [entry, ver] = k.split("/");
      const sups = [...st.held.values()].filter((p) => p.entry === entry && p.supersedes.includes(ver)).map((p) => p.ver);
      if (!deadByEntry.has(entry)) deadByEntry.set(entry, {});
      if (sups.length) deadByEntry.get(entry)[ver] = [...new Set(sups)].sort();
    }
    // a checkpoint's own dead lists carry forward
    for (const cp of this.countingCheckpoints()) for (const sh of this.checkpoints.get(cp.id)?.shards || []) for (const [entry, e] of Object.entries(sh?.entries || {}))
      for (const [d, sups] of Object.entries(e.dead || {})) {
        if (!deadByEntry.has(entry)) deadByEntry.set(entry, {});
        const m = deadByEntry.get(entry); m[d] = [...new Set([...(m[d] || []), ...sups])].sort();
      }
    for (const [entry, e] of st.entries) entries[entry] = { current: e.current, dead: deadByEntry.get(entry) || {} };
    for (const [entry, dead] of deadByEntry) if (!entries[entry] && Object.keys(dead).length) entries[entry] = { current: [], dead };
    const unknown = [];
    for (const [dvk] of this.commits) if (ro.isLive(dvk)) for (const c of this.chain(dvk)) for (const op of c.ops) if (isObj(op) && !KNOWN_OPS.has(op.op)) unknown.push({ op, commit: { c: c.c, sig: c.sig } });
    const kept = this.countingCheckpoints().filter((c) => !ro.dvks.get(c.dvk)?.live).map((c) => ({ c: c.c, sig: c.sig }));
    return { covers, authority: [...this.events.values()], kept, decided: [], unknown_ops: unknown, entries };
  }
  coveredBefore(dvk, c) {
    return this.countingCheckpoints().some((cp) => {
      const cov = this.checkpoints.get(cp.id)?.root?.covers?.[dvk];
      return Array.isArray(cov) && cov.some(([s, id]) => s === c.seq - 1 && id === c.prev);
    });
  }
}
const KNOWN_OPS = new Set(["put", "batch", "checkpoint", "auth", "rekey", "contrib", "sync-request", "kept", "fork"]);
const listOf = (x) => (Array.isArray(x) ? x : []);
export { isHex };
