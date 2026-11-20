// Web's vault session against an in-memory relay and Blossom server: open a vault an owner app made, write notes,
// two devices in conflict, a reader added (rekey and wrap), a new generation for a revoked root, segments past
// 8 KiB, a checkpoint, and a fresh device loading from it.   node tools/vaults-sync.test.mjs
import * as C from "../site/js/vault/crypto.js";
import * as V from "../site/js/vault/core.js";
import { Session, readKeyrings, notesVault } from "../site/js/vault/sync.js";
import * as N from "../site/js/vault/notes.js";
import { memoryNet, localSigner, memoryStore, makeVault } from "./vault-fixtures.mjs";

let n = 0;
const ok = (c, m) => { if (!c) throw new Error("FAIL: " + m); n++; };
const net = memoryNet();
const ownerSec = C.randomKey(), signer = localSigner(ownerSec), me = signer.pubkey;
const firstNote = N.notePut({ id: "0123456789abcdef", text: "Buy milk", created: 1793750400, updated: 1793750400 }, []);
const made = await makeVault(net, ownerSec, { puts: [firstNote] });
// a second, unrelated owner vault (photos) and a notes vault with a higher genesis are not the notes vault
const relays = made.payload.relays;

async function session(store = memoryStore(), name = "Marble Web") {
  const entries = notesVault(await readKeyrings({ me, relays, net, signer }));
  return new Session({ me, entries, net, signer, store, deviceName: name }).open();
}
const notes = (s) => N.notesOf(s.vault.state(), s.vault.roster());

// opening
const a = await session();
ok(a.genesis === made.genesis && a.current.id === made.gen.id, "opened, first generation anchored");
ok(notes(a).length === 1 && notes(a)[0].note.text === "Buy milk" && notes(a)[0].note.id === "0123456789abcdef", "Android's note shows");
ok(N.entryOf("0123456789abcdef") === firstNote.entry, "entry from the note id");

// an edit, from web
const v1 = notes(a)[0].versions[0];
await a.commit([N.notePut({ ...v1, text: "Buy oat milk", updated: v1.updated + 5 }, [v1.ver], v1.meta)]);
const b = await session(memoryStore(), "Other browser");
ok(notes(b)[0].note.text === "Buy oat milk" && !notes(b)[0].conflict, "a second browser sees the edit");
ok(b.vault.roster().dvks.size === 2, "two owner dvks, both live");

// a conflict: both edit the same version
const base = notes(b)[0].versions[0];
await a.commit([N.notePut({ ...base, text: "Buy oat milk and bread", updated: base.updated + 1 }, [base.ver], base.meta)]);
await b.commit([N.notePut({ ...base, text: "Buy soy milk", updated: base.updated + 2 }, [base.ver], base.meta)]);
await a.catchUp();
const cf = notes(a)[0];
ok(cf.conflict && cf.versions.length === 2 && cf.versions[0].ver < cf.versions[1].ver, "conflict: two versions, lowest ver first");
ok(new Set(cf.versions.map((x) => x.device)).size === 2, "conflict: names each device");
// keep this: one put superseding every current version
await a.commit([N.notePut({ ...cf.versions[1], updated: base.updated + 3 }, cf.current, cf.versions[1].meta)]);
await b.catchUp();
ok(!notes(b)[0].conflict && notes(b)[0].note.text === cf.versions[1].text, "the pick resolves it everywhere");

// delete beside an edit
const cur = notes(a)[0].versions[0];
await a.commit([N.deletePut(cur.entry, [cur.ver])]);
await b.catchUp();
ok(notes(b).length === 0, "deleted");
// unknown members kept on rewrite
const odd = N.notePut({ id: "fedcba9876543210", text: "x", created: 1, updated: 1 }, [], { colour: "blue" });
await b.commit([odd]);
await a.catchUp();
const oddN = notes(a).find((x) => x.note.id === "fedcba9876543210");
ok(oddN && N.notePut({ ...oddN.note, text: "y", updated: 2 }, [oddN.note.ver], oddN.note.meta).meta.colour === "blue", "unknown meta members kept");

// a picture kept in a note (a screenshot dropped on Marble Web): its own blob, named in meta; kept through edits
{
  const png = new Uint8Array(70000).map((_, i) => i * 7 % 251); // (past one 64 KiB chunk)
  const ref = await b.upload(await b.device(), png, 2);
  const pic = { ref, mime: "image/png", w: 1440, h: 900 };
  await b.commit([N.notePut({ id: "9900000000000000", text: "Screenshot 2026-11-20 at 10.42.13", created: 8, updated: 8, pictures: [pic] }, [])]);
  await a.catchUp();
  const got = notes(a).find((x) => x.note.id === "9900000000000000").note;
  ok(got.pictures.length === 1 && got.pictures[0].w === 1440 && got.pictures[0].mime === "image/png", "the picture is named in the note");
  const back = await a.blob(got.pictures[0].ref);
  ok(back.length === png.length && back.every((x, i) => x === png[i]), "and opens to the same bytes on another device");
  const ticked = N.notePut({ ...got, pictures: undefined, done: 9, updated: 9 }, [got.ver], got.meta);
  ok(ticked.meta.pictures?.length === 1, "ticking it done keeps the picture");
  ok(N.picturesOf({ pictures: [{ ref: { alg: "nv1" }, mime: "image/png" }, { ...pic, mime: "text/html" }] }).length === 0, "a bad ref or a type that isn't a picture is never shown");
}

// many edits: segments past 8 KiB, and a fresh device still sees everything
for (let i = 0; i < 40; i++) await a.commit([N.notePut({ id: "aa" + String(i).padStart(14, "0"), text: "note " + i + " ".repeat(200), created: 2, updated: 2 }, [])], { catchUp: false });
const head = [...net.events.values()].find((e) => e.kind === V.KIND.HEAD && a.heads.get(e.pubkey + "/" + e.tags[0][1])?.id === e.id && e.pubkey === [...a.vault.roster().dvks.values()].find((d) => d.name === "Marble Web").dvk);
const body = JSON.parse(C.nip44Decrypt(a.epochs.get(head.tags[0][1]).e.opsKey, head.content));
ok(body.segment && body.commits.length < 40, "older commits moved to a segment");
const fresh = await session();
ok(notes(fresh).length === 42, "a fresh device reaches every commit through the segment chain");

// a reader added (the owner's AUTH, through the app): ρ moves; the next write rekeys with a wrap
const readerSec = C.randomKey(), reader = C.pubHex(readerSec);
const add = C.signEvent(ownerSec, V.authTemplate(a.aKey, { vault: a.vid, type: "change", add: [{ reader, after: [] }] }, 1793750500));
await a.commit([{ op: "auth", event: add }]);
const before = [...a.vault.byId.values()].filter((c) => c.ops.some((o) => o.op === "rekey")).length;
await a.commit([N.notePut({ id: "bb00000000000000", text: "after the reader", created: 3, updated: 3 }, [])]);
const rk = [...a.vault.byId.values()].find((c) => c.ops.some((o) => o.op === "rekey"));
ok(before === 0 && rk, "a rekey before the first write under the new epoch");
const op = rk.ops.find((o) => o.op === "rekey");
const opened = await V.openKeyWrap(op.wraps[0], a.vid, localSigner(readerSec).decrypt);
ok(op.wraps.length === 1 && opened && opened.keys[op.to], "the reader's wrap opens to the new key");
const r2 = await session();
ok(notes(r2).some((x) => x.note.text === "after the reader") && r2.epochs.size >= 2, "a fresh device follows the rekey");

// a revoked owner root: a new generation is needed; the page knows the trigger through the revocation
const rev = C.signEvent(ownerSec, { kind: V.KIND.CERT, tags: [], content: C.canon({ type: "revoke", root: made.device.root, keep: [] }), created_at: 1793750600 });
await a.commit([{ op: "auth", event: rev }]);
a.vetting();
ok(a.uncovered.includes(made.device.root), "a revoked root is an uncovered trigger");
ok(!a.vault.roster().isLive(made.device.dvk), "the revoked device's dvk is no longer live");
ok(notes(a).find((x) => x.note.id === "0123456789abcdef") === undefined, "its deletion stays (the delete was web's)");
const oldGen = a.current.id;
await a.commit([N.notePut({ id: "dd00000000000000", text: "under the new generation", created: 5, updated: 5 }, [])]);
ok(a.current.prev === oldGen && a.current.because.roots[0] === made.device.root && !a.uncovered.length, "a new generation started on top, covering the root");
const last = a.vault.chain([...a.vault.roster().dvks.values()].find((d) => d.name === "Marble Web").dvk).at(-1);
ok(a.epochs.get(last.kid).gen === a.current.id, "the next write is under the new generation's epoch");
const r4 = await session();
ok(r4.gens.some((g) => g.id === a.current.id) && r4.current.id === a.current.id && notes(r4).some((x) => x.note.text === "under the new generation"), "another device vets it through the rekey and reads it");

// a checkpoint, and a fresh device loading through it
const cp = await a.checkpoint();
ok(cp && a.vault.countingCheckpoints().some((c) => c.id === cp.id), "checkpoint written and counting");
const r3 = await session();
ok(notes(r3).length === notes(a).length, "a fresh device after the checkpoint shows the same notes");

// this browser's root revoked: it stops writing
const myRoot = C.pubHex(C.unhex(a.store.get("root")));
await b.commit([{ op: "auth", event: C.signEvent(ownerSec, { kind: V.KIND.CERT, tags: [], content: C.canon({ type: "revoke", root: myRoot, keep: [] }), created_at: 1793750700 }) }]);
await a.catchUp();
let refused = false; try { await a.commit([N.notePut({ id: "cc00000000000000", text: "no", created: 4, updated: 4 }, [])]); } catch (e) { refused = /removed/.test(e.message); }
ok(refused && a.removed(), "a removed browser refuses to write");

// a batch import (as Android's import is one batch blob), and a note over 4 KiB in a blob
{
  const imp = [];
  for (let i = 0; i < 30; i++) imp.push(N.notePut({ id: "ee" + String(i).padStart(14, "0"), text: "imported " + i, created: 6, updated: 6 }, []));
  imp.push(N.deletePut(N.entryOf("ee99999999999999"), []));
  const s2 = await session();
  const dev = await s2.device();
  const ref = await s2.upload(dev, C.utf8(C.canon(imp)), 2);
  const long = "x".repeat(5000);
  const bref = await s2.upload(dev, C.utf8(long), 2);
  await s2.commit([{ op: "batch", ref }, N.notePut({ id: "ff00000000000000", text: long, created: 7, updated: 7 }, [], {}, { blob: bref })]);
  const s3 = await session();
  const all = notes(s3);
  ok(all.filter((x) => x.note.text?.startsWith("imported ")).length === 30, "a batch's puts count");
  ok(s3.vault.state().entries.has(N.entryOf("ee99999999999999")) && !all.some((x) => x.entry === N.entryOf("ee99999999999999")), "an imported removal: the vault has it, nothing shows");
  const big = all.find((x) => x.note.id === "ff00000000000000");
  ok(big && big.note.text === null && big.note.blob && C.unutf8(await s3.blob(big.note.blob)) === long, "a note over 4 KiB reads from its blob");
}

// Blossom (the person's decision, 2026-11-18): the account's own list, in order; a refusing server is skipped
{
  const { normalizeServers, serverList } = await import("../site/js/vault/sync.js");
  ok(normalizeServers([" https://A.test/ ", "https://a.test", "ftp://x.test", "http://b.test//", "https://c.test"]).join() === "https://A.test,http://b.test,https://c.test", "server list normalized");
  const list = C.signEvent(ownerSec, { kind: 10063, tags: [["server", "https://refuses.test"], ["server", "https://first.test/"], ["server", "https://FIRST.test"], ["server", "https://second.test"], ["server", "https://third.test"]], content: "", created_at: 1793750000 });
  const bl = serverList([list], me);
  ok(bl.join() === "https://refuses.test,https://first.test,https://second.test,https://third.test", "the account's kind 10063, in its order");
  net.refuse.add("https://refuses.test");
  const s4 = new Session({ me, entries: notesVault(await readKeyrings({ me, relays, net, signer })), net, signer, store: memoryStore(), blossom: [list.tags[0][1], ...bl] });
  await s4.open();
  net.auths.length = 0;
  const big = new Uint8Array(9 * 1024 * 1024); big.fill(7);
  const ref = await s4.upload(null, big, 2);
  const servers = [...new Set(net.auths.map((x) => x.server))];
  ok(servers.join() === "https://first.test,https://second.test", "uploaded to the first two that take it");
  ok(s4.uploadLog.some((x) => x.server === "https://refuses.test" && /401/.test(x.why)), "the refusal recorded");
  const a0 = net.auths.find((x) => x.server === "https://first.test").auth;
  ok(a0.pubkey === me && ref.parts.length === 2 && ref.parts.every((p) => a0.tags.some((t) => t[0] === "x" && t[1] === p)) && a0.tags.find((t) => t[0] === "server")[1] === "first.test",
    "one auth per server, signed by the account, an x per part, the server's domain");
  ok(net.auths.filter((x) => x.server === "https://first.test").every((x) => x.auth.id === a0.id), "the same auth for every part");
  ok(s4.servers[0] === "https://refuses.test" && s4.servers.includes("https://s1.test"), "read from the list, then the vault's own");
  net.refuse.clear();
}

// a signer that won't decrypt for this site (Marble 0.15.1's site scopes: a peer other than you counts as DMs)
{
  const refusing = { ...signer, decrypt: async () => { throw new Error("not_allowed: DMs are off for this site"); } };
  const all = await readKeyrings({ me, relays, net, signer: refusing });
  ok(all.length === 0 && all.refused.length >= 1 && /not_allowed/.test(all.refused[0].message) && all.found >= 1, "a refused decrypt is reported, not taken for no vault");
  const again = await readKeyrings({ me, relays, net, signer });
  ok(notesVault(again).length >= 1, "and the same entries open once the signer answers");
}

console.log(`all ${n} sync checks pass`);
