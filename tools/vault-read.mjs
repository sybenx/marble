// Opens the test account's notes vault from node, as another owner device: prints its notes; with --edit "<id>" "<text>",
// writes an edit of that note's displayed version (to make a conflict with an edit elsewhere).
//   node tools/vault-read.mjs <nsec hex> [--edit <note id> <text> | --remove <n> <text>]   (local test servers only; VAULTS_RELAY moves the relay)
import * as C from "../site/js/vault/crypto.js";
import * as N from "../site/js/vault/notes.js";
import { Session, readKeyrings, notesVault } from "../site/js/vault/sync.js";
import { localSigner, memoryStore } from "./vault-fixtures.mjs";
import { realNet } from "./vault-net.mjs";
const [sec, flag, id, text] = process.argv.slice(2);
const signer = localSigner(C.unhex(sec)), me = signer.pubkey, relays = [process.env.VAULTS_RELAY || "ws://127.0.0.1:7447"];
const found = notesVault(await readKeyrings({ me, relays, net: realNet, signer }));
if (!found.length) { console.log(JSON.stringify({ error: "no notes vault for this account on " + relays.join(", ") })); process.exit(1); }
const s = await new Session({ me, entries: found, net: realNet, signer, store: memoryStore(), deviceName: "Node phone" }).open();
const texts = new Map();
for (const n of N.notesOf(s.vault.state(), s.vault.roster())) for (const v of n.versions) if (v.text === null && v.blob) texts.set(v.ver, C.unutf8(await s.blob(v.blob)));
const textOf = (v) => v.text ?? texts.get(v.ver) ?? null;
const show = () => N.notesOf(s.vault.state(), s.vault.roster()).map((n) => ({ id: n.note.id, text: textOf(n.note), long: !!n.note.blob, done: !!n.note.done, conflict: n.conflict, versions: n.conflict ? n.versions.map((v) => v.device + ": " + textOf(v)) : undefined, deletedOn: n.deletedOn }));
const all = show();
const brief = process.argv.includes("--brief");
console.log(JSON.stringify({ dvks: [...s.vault.roster().dvks.values()].map((d) => d.name), count: all.length, checkpoints: s.vault.countingCheckpoints().length,
  notes: brief ? all.filter((x) => !/^a000/.test(x.id)).map((x) => ({ ...x, text: x.text && x.text.length > 80 ? x.text.slice(0, 40) + "… (" + x.text.length + " chars)" : x.text })) : all }, null, 1));
// --remove <n> <text>: this device (a browser) removes n devices of yours, one after another, writing a note with each
// (the move to each new generation carries every revocation the vault holds), then <text>. Fails (exit 1) if any commit
// it made is over 12 KiB (kist.md §5.1; Android before 0.16.24 drops one).
if (flag === "--remove") {
  const V = await import("../site/js/vault/core.js");
  const n = Number(id), t0 = Math.floor(Date.now() / 1000);
  for (let i = 0; i <= n; i++) {
    const ops = [N.notePut({ id: "e" + String(i).padStart(15, "0"), text: i < n ? `Web removal ${i + 1}` : text, created: t0 + i, updated: t0 + i }, [])];
    if (i < n) {
      // (a device of yours that signed in once, its cert carried here, then removed: the next write starts a generation)
      const root = C.pubHex(C.randomKey()), sec = C.unhex(process.argv[2]);
      ops.push({ op: "auth", event: C.signEvent(sec, V.deviceCertTemplate(root, null, t0 + i)) },
        { op: "auth", event: C.signEvent(sec, { kind: V.KIND.CERT, tags: [], content: C.canon({ type: "revoke", root, keep: [] }), created_at: t0 + i }) });
    }
    await s.commit(ops);
  }
  const dvk = C.pubHex(V.dvkFor(C.unhex(s.store.get("root")), s.genesis, s.store.get("n:" + s.genesis) || 0));
  const big = Math.max(...s.vault.chain(dvk).map((c) => C.utf8(c.c).length));
  console.log(`removed ${n} devices; ${s.vault.roster().revoked.size} removed in all; largest commit ${big} bytes`);
  if (big > 12 * 1024) { console.log("a commit is over 12 KiB"); process.exit(1); }
}
if (flag === "--edit") {
  const n = N.notesOf(s.vault.state(), s.vault.roster()).find((x) => x.note.id === id);
  await s.commit([N.notePut({ ...n.note, text, updated: n.note.updated + 1 }, [n.note.ver], n.note.meta)]);
  console.log("edited", id);
}
process.exit(0);
