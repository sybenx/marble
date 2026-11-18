// The shared vaults vectors (~/nostr-sync-nip/vectors/vaults.json) run against Marble Web's core (site/js/vault/).
// Every check of the spec's own verify.mjs, done with this code, plus byte-for-byte remakes of what the vectors made
// (signatures with BIP-340 aux zero, the keyring seal, nv1 blobs, the shard, accepted puts).
//   node tools/vaults-vectors.test.mjs [path to vaults.json]
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import * as C from "../site/js/vault/crypto.js";
import * as V from "../site/js/vault/core.js";

const file = process.argv[2] || [path.join(os.homedir(), "nostr-sync-nip/vectors/vaults.json"), new URL("./vaults-vectors.json", import.meta.url).pathname].find((f) => fs.existsSync(f));
const v = JSON.parse(fs.readFileSync(file, "utf8"));
const { hex, unhex, TH, thHex, sha256, utf8 } = C;
let n = 0;
const ok = (c, m) => { if (!c) throw new Error("FAIL: " + m); n++; };
const fails = async (f, m) => { let t; try { t = await f(); } catch { ok(true, m); return; } ok(t === false || t === null, m); };
const Z = new Uint8Array(32); // BIP-340 aux used by gen.mjs
const label = (s) => sha256(utf8("vaults-vector/" + s));

// ── keys and epochs ──
const vid = unhex(v.inputs.vault_id), Vs = unhex(v.owner_secret.V);
ok(V.genId(Vs, null) === v.owner_secret.id && v.inputs.sigma[0] === v.owner_secret.id, "generation id");
{ const g = v.owner_secret.second; ok(V.genOK(g) && V.genId(unhex(g.secret), null) !== g.id, "second generation id binds prev");
  const d = V.chainDepths([{ ...v.owner_secret, secret: v.owner_secret.V, prev: null }, g], v.owner_secret.id);
  ok(d.get(g.id) === 1 && d.get(v.owner_secret.id) === 0, "chain depths");
  ok(V.chainDepths([g], v.owner_secret.id).size === 0, "unanchored chain ignored"); }
for (const [name, e] of Object.entries(v.epochs)) {
  ok(hex(sha256(e.rho_preimage)) === e.rho, name + " rho");
  const p = JSON.parse(e.rho_preimage);
  ok(hex(V.rhoOf(p.readers, p.ended)) === e.rho, name + " rhoOf");
  const x = V.epoch(Vs, vid, unhex(e.rho));
  ok(x.K === e.K && x.kid === e.kid && hex(x.opsKey) === e.ops_key && x.locator === e.locator, name + " K, kid, ops, locator");
  ok(hex(x.inboxSecret) === e.inbox_secret && x.inbox === e.inbox && x.inboxC === e.inbox_c, name + " inbox");
  const r = V.epochFromK(unhex(e.K), vid);
  ok(r.kid === e.kid && r.locator === e.locator, name + " reader's view");
}
const K0 = unhex(v.epochs.genesis.K), aKey = V.authKey(K0, vid);
ok(hex(aKey) === v.per_vault.auth_key && hex(V.dedupKey(K0, vid)) === v.per_vault.dedup_key, "auth and dedup keys");

// ── AUTH events, and ρ from them ──
const auths = {};
for (const [k, a] of Object.entries(v.auth)) {
  const r = V.readAuth(a.event, aKey, v.inputs.vault_id);
  ok(r && a.event.pubkey === v.inputs.owner_npub, "auth read " + k);
  ok(C.nip44Decrypt(aKey, a.event.content) === a.plaintext, "auth dec " + k);
  ok(C.nip44Encrypt(aKey, a.plaintext, unhex(a.nonce)) === a.event.content, "auth remade " + k);
  ok(C.signEvent(unhex(v.inputs.owner_npub_secret), { ...a.event }, Z).sig === a.event.sig, "auth sig remade " + k);
  auths[k] = r;
}
ok(v.auth.genesis.event.id === v.inputs.genesis_id, "genesis id");
const own = v.inputs.owner_npub;
ok(V.readerSet([auths.genesis], own).rho === v.epochs.genesis.rho, "ρ genesis from events");
ok(V.readerSet([auths.genesis, auths.add], own).rho === v.epochs.with_reader.rho, "ρ with reader from events");
ok(V.readerSet([auths.genesis, auths.add, auths.remove], own).rho === v.epochs.reader_removed.rho, "ρ removed from events");
ok(V.readerSet([auths.genesis, auths.add, auths.remove, auths.rekey], own).rho === v.epochs.fresh_key.rho, "ρ fresh key from events");
{ const cfg = V.currentConfig([auths.genesis], own); ok(cfg.profile === "photos" && cfg.contrib.mode === "auto" && cfg.history_days === 30, "config defaults"); }

// ── certs ──
for (const [dev, sec] of [[v.owner_device, v.owner_device.npub_secret], [v.reader_device, v.inputs.reader_npub_secret]]) {
  const d = V.readCert(dev.device_cert), s = V.readCert(dev.sub_cert);
  ok(d?.type === "device" && d.root === dev.root && s?.type === "sub" && s.dvk === dev.dvk && s.root === dev.root, "certs read");
  const dvk = V.dvkFor(unhex(dev.root_secret), v.inputs.genesis_id, 0);
  ok(hex(dvk) === dev.dvk_secret, "dvk derived");
  if (dev.storage_secret) ok(hex(V.storageFor(dvk)) === dev.storage_secret && s.storage === dev.storage, "storage key");
  const sc = JSON.parse(dev.sub_cert.content);
  ok(V.subCert(unhex(dev.root_secret), sc.genesis, sc.n, dvk, sc.name, dev.sub_cert.created_at, Z).sig === dev.sub_cert.sig, "sub-cert remade");
  ok(C.signEvent(unhex(sec), V.deviceCertTemplate(dev.root, null, dev.device_cert.created_at), Z).id === dev.device_cert.id, "device cert remade");
}

// ── keyring ──
{
  const ownerSec = unhex(v.inputs.owner_npub_secret);
  const decrypt = async (eph, ct) => C.nip44Decrypt(C.nip44Conv(ownerSec, eph), ct);
  const kr = JSON.parse(v.keyring.event_content).self;
  ok(hex(C.nip44Conv(ownerSec, kr.eph)) === v.keyring.conversation_key, "keyring conversation key");
  const ev = C.signEvent(ownerSec, { kind: V.KIND.KEYRING, tags: [["d", v.keyring.d]], content: v.keyring.event_content, created_at: 1793750000 });
  const p = await V.openKeyring(ev, own, decrypt);
  ok(p && p.d === v.keyring.d && p.gens[0].secret === v.owner_secret.V && p.genesis === v.inputs.genesis_id, "keyring self seal opened");
  ok(V.sealKeyring(JSON.parse(v.keyring.payload), own, unhex(v.keyring.eph_secret), unhex(v.keyring.nonce)) === v.keyring.event_content, "keyring seal remade");
  const neg = v.negative.find((x) => x.name === "keyring payload d differs from the d tag").input;
  const bad = C.signEvent(ownerSec, { kind: V.KIND.KEYRING, tags: [["d", neg.tag_d]], content: neg.event_content, created_at: 1793750000 });
  ok((await V.openKeyring(bad, own, decrypt)) === null, "neg keyring d");
  const wrong = v.negative.find((x) => x.name.startsWith("keyring seal opened with the to-self")).input.wrong_key;
  await fails(() => C.nip44Decrypt(unhex(wrong), kr.ct), "neg seal");
  ok(hex(C.nip44Conv(ownerSec, own)) === wrong, "the to-self key is the wrong one");
}

// ── commits and heads ──
const odvk = unhex(v.owner_device.dvk_secret);
const epochs = new Map(Object.values(v.epochs).map((e) => [e.kid, V.epochFromK(unhex(e.K), vid)]));
let prev = null, seq = 0;
for (const h of Object.values(v.heads)) {
  const b = V.openHead(h.event, epochs, v.inputs.vault_id, v.inputs.genesis_id);
  ok(b && b.dvk === v.owner_device.dvk, "head opened");
  const ep = epochs.get(b.kid);
  ok(C.nip44Encrypt(ep.opsKey, h.plaintext, unhex(h.nonce)) === h.event.content, "head content remade");
  ok(C.signEvent(odvk, { ...h.event }, Z).sig === h.event.sig, "head sig remade");
  for (const x of b.commits) {
    const c = V.readCommit(x, v.inputs.genesis_id);
    ok(c && c.prev === prev && c.seq === seq && c.kid === b.kid && c.dvk === v.owner_device.dvk, "commit " + seq);
    const { id, sig, c: _, ...fields } = c;
    const re = V.makeCommit(fields, odvk, Z);
    ok(re.c === x.c && re.sig === x.sig && re.id === v.commits[seq].id, "commit remade " + seq);
    for (const op of c.ops) {
      if (op.op === "put") ok(V.putOK(op), "put in commit");
      if (op.op === "auth") ok(V.readCert(op.event) || V.readAuth(op.event, aKey, v.inputs.vault_id), "auth op read");
      if (op.op === "rekey") {
        ok(epochs.get(op.to) && Object.values(v.epochs).find((e) => e.kid === op.to).rho === op.rho, "rekey target");
        ok(V.epoch(Vs, vid, unhex(op.rho)).kid === op.to && op.state[0] === v.owner_secret.id, "rekey derives its target");
        for (const w of op.wraps) {
          const rsec = unhex(v.inputs.reader_npub_secret);
          const k = await V.openKeyWrap(w, v.inputs.vault_id, async (eph, ct) => C.nip44Decrypt(C.nip44Conv(rsec, eph), ct));
          ok(k && Object.keys(k.keys).includes(op.to), "wrap opened");
        }
      }
    }
    prev = id; seq++;
  }
}
ok(seq === 4, "four commits");
{
  const w = v.wrap_to_reader;
  ok(V.wrapTo(w.wrap.to, w.plaintext, unhex(w.eph_secret), unhex(w.nonce)).ct === w.wrap.ct, "wrap remade");
}

// ── versions, Padmé, nv1, shard ──
ok(V.putOK(v.ver.put) && V.putOK(v.contrib_drive.accepted.put) && V.putOK(v.contrib_photos.accepted.put) && V.putOK(v.contrib_photos.accepted.link), "positive vers");
{ const p = v.ver.put; ok(JSON.stringify(V.makePut(p.entry, p.supersedes, p.meta, p.data, p.deleted)) === JSON.stringify(p), "put remade"); }
for (const [len, L] of Object.entries(v.padme)) ok(V.padme(Number(len)) === L, "padme " + len);
{
  const r = await V.nv1Seal(utf8("hello vault"), label("blob key 1"));
  ok(JSON.stringify(r.ref) === JSON.stringify(v.nv1_small.ref) && C.b64(r.parts[0]) === v.nv1_small.part0_b64, "nv1 small remade");
  ok(C.unutf8(await V.nv1Open(v.nv1_small.ref, [C.unb64(v.nv1_small.part0_b64)])) === "hello vault", "nv1 opened");
  const big = new Uint8Array(70000); for (let i = 0; i < big.length; i++) big[i] = i % 251;
  const t = await V.nv1Seal(big, label("blob key 2"), 1);
  ok(JSON.stringify(t.ref) === JSON.stringify(v.nv1_two_parts.ref) && t.parts.map((p) => p.length).join() === v.nv1_two_parts.part_sizes.join(), "nv1 two parts remade");
  const back = await V.nv1Open(t.ref, t.parts);
  ok(back.length === 70000 && back.every((x, i) => x === i % 251), "nv1 two parts opened");
  const sp = utf8(v.shard.plaintext), sk = V.shardKey(V.dedupKey(K0, vid), sp);
  ok(hex(sk) === v.shard.key && JSON.stringify((await V.nv1Seal(sp, sk)).ref) === JSON.stringify(v.shard.ref), "shard remade");
}

// ── contributions ──
for (const [c, profile] of [[v.contrib_drive, "drive"], [v.contrib_photos, "photos"]]) {
  ok(C.eventOK(c.event) && c.event.pubkey === v.reader_device.dvk, "contrib sig");
  const plain = V.openContrib(c.event, unhex(v.epochs.with_reader.inbox_secret));
  ok(plain && C.canon(plain) === c.plaintext, "contrib dec");
  ok(V.contribEntry(c.event.id) === c.accepted.entry, "contrib entry");
  const puts = V.acceptedPuts(profile, c.event.id, plain);
  ok(C.canon(puts[0]) === C.canon(c.accepted.put), "accepted put byte-identical " + profile);
  if (c.accepted.link) ok(C.canon(puts[1]) === C.canon(c.accepted.link), "link put byte-identical");
}

// ── negatives ──
const neg = Object.fromEntries(v.negative.map((x) => [x.name, x]));
ok(!V.putOK(neg["put whose ver does not hash"].input), "neg ver");
await fails(() => C.parse(neg["put with a float"].input_json), "neg float");
for (const k of ["commit signed by a key other than its dvk", "commit signed over the bare id, not TH"])
  ok(V.readCommit(neg[k].input, v.inputs.genesis_id) === null, "neg " + k);
{ const c = V.readCommit(neg["commit whose prev breaks the chain"].input, v.inputs.genesis_id); ok(c && c.prev !== v.commits[1].id, "neg prev: a gap, not seq 2's successor"); }
{ const x = neg["commit kid differs from its head epoch"].input, c = V.readCommit(x, v.inputs.genesis_id); ok(c && c.kid !== x.head_kid, "neg kid"); }
ok(V.openHead(neg["head with content altered after signing"].input, epochs, v.inputs.vault_id, v.inputs.genesis_id) === null, "neg head content");
ok(V.openHead(neg["head signed correctly but sealed under another epoch"].input, epochs, v.inputs.vault_id, v.inputs.genesis_id) === null, "neg head epoch");
{ const rsec = unhex(v.inputs.reader_npub_secret), dec = async (eph, ct) => C.nip44Decrypt(C.nip44Conv(rsec, eph), ct);
  ok((await V.openKeyWrap(neg["wrap whose kid is not the hash of its key"].input.wrap, v.inputs.vault_id, dec)) === null, "neg wrap kid");
  ok((await V.openKeyWrap(neg["wrap NIP-44 MAC altered"].input, v.inputs.vault_id, dec)) === null, "neg wrap mac"); }
await fails(() => V.nv1Open(v.nv1_small.ref, [C.unb64(neg["nv1 part with a flipped byte"].input.part0_b64)]), "neg nv1 sha");
{ const t = neg["nv1 blob truncated: last chunk sealed with flag 0"].input;
  const big = new Uint8Array(70000); for (let i = 0; i < big.length; i++) big[i] = i % 251;
  const good = await V.nv1Seal(big, label("blob key 2"), 1);
  await fails(() => V.nv1Open(t.ref, [good.parts[0], C.unb64(t.part1_b64)]), "neg trunc gcm"); }
ok(V.readCert(neg["sub-cert whose storage_sig signs another dvk"].input) === null, "neg storage_sig");
{ const e = neg["CONTRIB signed by a key that is not the sub-cert dvk"].input, plain = V.openContrib(e, unhex(v.epochs.with_reader.inbox_secret));
  ok(plain && e.pubkey !== JSON.parse(plain.cert.sub.content).dvk, "neg contrib signer"); }
{ const a = V.readAuth(neg["AUTH remove naming the owner"].input.event, aKey, v.inputs.vault_id);
  ok(a && V.readerSet([auths.genesis, a], own).rho === v.epochs.genesis.rho, "neg owner remove: no effect on ρ"); }
{ // a removed reader repeating leave (prev names the remove, id lower than the remove's): ρ stays reader_removed
  const l = V.readAuth(neg["removed reader repeats leave"].input.event, aKey, v.inputs.vault_id);
  ok(l && l.id < v.auth.remove.event.id && V.readerSet([auths.genesis, auths.add, auths.remove, l], own).rho === v.epochs.reader_removed.rho, "neg repeated leave"); }

console.log(`all ${n} checks pass (${file})`);
