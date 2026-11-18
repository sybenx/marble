// Test helpers for vaults: an in-memory relay and Blossom server, and a vault made the way an owner app (Android)
// makes one (§8.2): genesis, keyring entry, first head with its certs. Web never creates vaults; tests do.
import * as C from "../site/js/vault/crypto.js";
import * as V from "../site/js/vault/core.js";

export function memoryNet() {
  const events = new Map(), blobs = new Map();
  const addr = (e) => (e.kind >= 30000 && e.kind < 40000 ? `${e.kind}:${e.pubkey}:${e.tags.find((t) => t[0] === "d")?.[1] ?? ""}` : e.id);
  const match = (f, e) => (!f.kinds || f.kinds.includes(e.kind)) && (!f.authors || f.authors.includes(e.pubkey))
    && Object.entries(f).filter(([k]) => k.startsWith("#")).every(([k, vals]) => e.tags.some((t) => t[0] === k.slice(1) && vals.includes(t[1])));
  const net = {
    events, blobs, puts: 0, offline: false,
    async req(_urls, filter) {
      const fs = Array.isArray(filter) ? filter : [filter];
      const out = [...events.values()].filter((e) => fs.some((f) => match(f, e)));
      return Object.assign(out, { answered: net.offline ? 0 : 2, answeredBy: new Set() });
    },
    async publish(urls, e) {
      if (net.offline || !C.eventOK(e)) return { ok: [], failed: urls.map((u) => [u, "no"]) };
      const k = addr(e), o = events.get(k);
      if (!o || o.created_at < e.created_at || (o.created_at === e.created_at && e.id < o.id)) events.set(k, e);
      return { ok: urls.slice(), failed: [] };
    },
    async get(_servers, sha) { return blobs.get(sha) || null; },
    async put(_server, bytes, auth) {
      if (!C.eventOK(auth) || auth.kind !== 24242 || !auth.tags.some((t) => t[0] === "x" && t[1] === C.hex(C.sha256(bytes)))) return false;
      net.puts++; blobs.set(C.hex(C.sha256(bytes)), bytes); return true;
    },
  };
  return net;
}

/** A local signer for an nsec: {pubkey, sign, decrypt} as a page's signer would answer. */
export function localSigner(sec) {
  const pubkey = C.pubHex(sec);
  return {
    pubkey,
    async sign(t) { return C.signEvent(sec, { created_at: Math.floor(Date.now() / 1000), tags: [], content: "", ...t }); },
    async decrypt(eph, ct) { return C.nip44Decrypt(C.nip44Conv(sec, eph), ct); },
  };
}
export function memoryStore() { const m = new Map(); return { m, get: (k) => m.get(k), set: (k, v) => m.set(k, v) }; }

/** Makes a notes vault as an owner app does: returns {vid, genesis, gen, entryEvent, payload, device}. */
export async function makeVault(net, ownerSec, { relays = ["wss://a.test", "wss://b.test"], servers = ["https://s1.test", "https://s2.test"], name = "Notes", profile = "notes", puts = [] } = {}) {
  const owner = C.pubHex(ownerSec), vid = C.random(32), gen = V.newGeneration(null);
  const e0 = V.epoch(C.unhex(gen.secret), vid, V.rhoOf([], []));
  const aKey = V.authKey(C.unhex(e0.K), vid), t = Math.floor(Date.now() / 1000) - 100;
  const genesisEv = C.signEvent(ownerSec, V.authTemplate(aKey, { vault: C.hex(vid), type: "genesis", config: { prev: [], name, profile, relays, servers } }, t));
  const genesis = genesisEv.id;
  const rootSec = C.randomKey(), root = C.pubHex(rootSec);
  const cert = C.signEvent(ownerSec, V.deviceCertTemplate(root, null, t));
  const dvkSec = V.dvkFor(rootSec, genesis, 0), dvk = C.pubHex(dvkSec);
  const sub = V.subCert(rootSec, genesis, 0, dvkSec, "Pixel 8", t);
  const c0 = V.makeCommit({ genesis, dvk, seq: 0, prev: null, kid: e0.kid, time: t, ops: [{ op: "auth", event: genesisEv }, { op: "auth", event: cert }, { op: "auth", event: sub }, ...puts] }, dvkSec);
  const head = C.signEvent(dvkSec, V.headTemplate(e0, { v: 1, vault: C.hex(vid), genesis, kid: e0.kid, commits: [{ c: c0.c, sig: c0.sig }], segment: null, checkpoint: null, seen: {} }, t));
  await net.publish(relays, head);
  const payload = { vault: C.hex(vid), genesis, owner, name, profile, role: "owner", gens: [gen], relays, servers, d: C.hex(C.random(16)) };
  const entryEvent = C.signEvent(ownerSec, V.keyringTemplate(payload, owner, t));
  await net.publish(relays, entryEvent);
  return { vid: C.hex(vid), genesis, gen, e0, aKey, payload, entryEvent, device: { rootSec, root, dvkSec, dvk, sub, cert, commits: [c0], head } };
}
