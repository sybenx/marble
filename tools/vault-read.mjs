// Opens the test account's notes vault from node, as another owner device: prints its notes; with --edit "<id>" "<text>",
// writes an edit of that note's displayed version (to make a conflict with an edit elsewhere).
//   node tools/vault-read.mjs <nsec hex> [--edit <note id> <text>]      (local test servers only)
import * as C from "../site/js/vault/crypto.js";
import * as N from "../site/js/vault/notes.js";
import { Session, readKeyrings, notesVault } from "../site/js/vault/sync.js";
import { localSigner, memoryStore } from "./vault-fixtures.mjs";
import { realNet } from "./vault-net.mjs";
const [sec, flag, id, text] = process.argv.slice(2);
const signer = localSigner(C.unhex(sec)), me = signer.pubkey, relays = ["ws://127.0.0.1:7447"];
const s = await new Session({ me, entries: notesVault(await readKeyrings({ me, relays, net: realNet, signer })), net: realNet, signer, store: memoryStore(), deviceName: "Node phone" }).open();
const texts = new Map();
for (const n of N.notesOf(s.vault.state(), s.vault.roster())) for (const v of n.versions) if (v.text === null && v.blob) texts.set(v.ver, C.unutf8(await s.blob(v.blob)));
const textOf = (v) => v.text ?? texts.get(v.ver) ?? null;
const show = () => N.notesOf(s.vault.state(), s.vault.roster()).map((n) => ({ id: n.note.id, text: textOf(n.note), long: !!n.note.blob, done: !!n.note.done, conflict: n.conflict, versions: n.conflict ? n.versions.map((v) => v.device + ": " + textOf(v)) : undefined, deletedOn: n.deletedOn }));
const all = show();
const brief = process.argv.includes("--brief");
console.log(JSON.stringify({ dvks: [...s.vault.roster().dvks.values()].map((d) => d.name), count: all.length, checkpoints: s.vault.countingCheckpoints().length,
  notes: brief ? all.filter((x) => !/^a000/.test(x.id)).map((x) => ({ ...x, text: x.text && x.text.length > 80 ? x.text.slice(0, 40) + "… (" + x.text.length + " chars)" : x.text })) : all }, null, 1));
if (flag === "--edit") {
  const n = N.notesOf(s.vault.state(), s.vault.roster()).find((x) => x.note.id === id);
  await s.commit([N.notePut({ ...n.note, text, updated: n.note.updated + 1 }, [n.note.ver], n.note.meta)]);
  console.log("edited", id);
}
process.exit(0);
