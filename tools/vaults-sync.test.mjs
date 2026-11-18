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

// many edits: segments past 8 KiB, and a fresh device still sees everything
for (let i = 0; i < 40; i++) await a.commit([N.notePut({ id: "aa" + String(i).padStart(14, "0"), text: "note " + i + " ".repeat(200), created: 2, updated: 2 }, [])], { catchUp: false });
const head = [...net.events.values()].find((e) => e.kind === V.KIND.HEAD && a.heads.get(e.pubkey + "/" + e.tags[0][1])?.id === e.id && e.pubkey === [...a.vault.roster().dvks.values()].find((d) => d.name === "Marble Web").dvk);
const body = JSON.parse(C.nip44Decrypt(a.epochs.get(head.tags[0][1]).e.opsKey, head.content));
ok(body.segment && body.commits.length < 40, "older commits moved to a segment");
const fresh = await session();
ok(notes(fresh).length === 41, "a fresh device reaches every commit through the segment chain");

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

console.log(`all ${n} sync checks pass`);
