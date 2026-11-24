// Release smoke test for notes sync, web engine (site/js/vault/): a note written on one device is read on another and on
// a fresh browser, across a new key generation, while your signer refuses once as the new generation's keyring entry is
// re-read (a busy box answering slow_down, a phone out of reach). Before the fix the generation was dropped for the
// page's whole visit and the phone shown as suspect, so this browser stopped reading the phone's notes.
//   node tools/notes-sync-smoke.test.mjs
import * as C from "../site/js/vault/crypto.js";
import * as V from "../site/js/vault/core.js";
import * as N from "../site/js/vault/notes.js";
import { Session, readKeyrings, notesVault } from "../site/js/vault/sync.js";
import { memoryNet, localSigner, memoryStore, makeVault } from "./vault-fixtures.mjs";

let n = 0;
const ok = (c, m) => { if (!c) throw new Error("FAIL: " + m); n++; };
const net = memoryNet(), sec = C.randomKey(), base = localSigner(sec), me = base.pubkey;
let refuse = 0; // the next this many decrypts are refused
const signer = { ...base, decrypt: (eph, ct) => (refuse > 0 ? (refuse--, Promise.reject(new Error("slow_down"))) : base.decrypt(eph, ct)) };
const made = await makeVault(net, sec, {});
const relays = made.payload.relays;
async function session(name) {
  const entries = notesVault(await readKeyrings({ me, relays, net, signer }));
  return new Session({ me, entries, net, signer, store: memoryStore(), deviceName: name }).open();
}
const texts = (s) => N.notesOf(s.vault.state(), s.vault.roster()).map((x) => x.note.text).sort().join(", ");
const note = (id, text, t) => N.notePut({ id, text, created: t, updated: t }, []);

const phone = await session("Phone"), web = await session("Marble Web");
await phone.commit([note("1000000000000000", "phone 1", 1)]);
await web.commit([note("2000000000000000", "web 1", 1)]);
await phone.catchUp(); await web.catchUp();
ok(texts(phone) === "phone 1, web 1" && texts(web) === "phone 1, web 1", "two devices read each other");

// the phone starts a new generation (its keyring entry first, then a rekey) and writes under it
const g = V.newGeneration(phone.current.id, {});
const gens = V.mergeGens(phone.gens, [g]);
const payload = { ...phone.entries[0].payload, gens };
const ev = await base.sign(V.keyringTemplate(payload, me, Math.floor(Date.now() / 1000) + 1));
await net.publish(relays, ev);
phone.entries = [{ event: ev, payload }]; phone.gens = gens; phone.depths = V.chainDepths(gens, phone.first.id);
phone.store.set("started:" + phone.genesis, [g.id]); phone.vetting();
await phone.commit([note("3000000000000000", "phone 2", 2)]);

// the browser catches up while the signer refuses once, then again once it answers
refuse = 1;
await web.catchUp().catch(() => {});
ok(web.suspect.size === 0 && web.ignoredGens.size === 0, "a refusal makes no device suspect and drops no generation");
await web.catchUp();
ok(texts(web) === "phone 1, phone 2, web 1", "the browser reads the phone's note under the new generation: " + texts(web));
ok(web.current.id === g.id, "and writes under it");
await web.commit([note("4000000000000000", "web 2", 3)]);
await phone.catchUp();
ok(texts(phone) === "phone 1, phone 2, web 1, web 2", "the phone reads the browser's note");

const fresh = await session("Fresh browser");
ok(texts(fresh) === "phone 1, phone 2, web 1, web 2", "a fresh browser reads everything");
console.log(`notes sync smoke (web engine): ${n} checks pass`);
