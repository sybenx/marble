// Release smoke test for notes sync, web engine (site/js/vault/): a note written on one device is read on another and on
// a fresh browser, across a new key generation, while your signer refuses once as the new generation's keyring entry is
// re-read (a busy box answering slow_down, a phone out of reach). Before the fix the generation was dropped for the
// page's whole visit and the phone shown as suspect, so this browser stopped reading the phone's notes.
// Then two more, each of which failed before its fix (kist rekey-size-split):
//  - a device that rekeyed to a generation nobody holds doesn't keep a fresh browser short of the newest epoch (the
//    catch-up stopped at the keyring re-read that didn't find it, before fetching the heads of epochs found that round);
//  - a browser that removes many devices keeps every commit within 12 KiB (kist.md §5.1): moving to a new generation,
//    its rekey commit carried every revocation, past 12 KiB at about 16, which Android before 0.16.24 drops.
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
// ── a device that rekeyed to a generation nobody holds (a fork, a lost keyring copy) ──
{
  const net2 = memoryNet(), sec2 = C.randomKey(), sg = localSigner(sec2), me2 = sg.pubkey;
  const made2 = await makeVault(net2, sec2, {});
  const open2 = async (name, store = memoryStore()) => new Session({ me: me2, entries: notesVault(await readKeyrings({ me: me2, relays: made2.payload.relays, net: net2, signer: sg })), net: net2, signer: sg, store, deviceName: name }).open();
  const phone2 = await open2("Phone"), odd = await open2("Odd one");
  await phone2.commit([note("5000000000000000", "before", 1)]);
  await odd.commit([note("6000000000000000", "odd 1", 1)]);
  // the odd device starts a generation it never publishes, and rekeys to it
  const lost = V.newGeneration(odd.current.id, {});
  odd.gens = V.mergeGens(odd.gens, [lost]); odd.depths = V.chainDepths(odd.gens, odd.first.id);
  odd.store.set("started:" + odd.genesis, [lost.id]); odd.vetting();
  await odd.commit([note("7000000000000000", "odd 2", 2)], { catchUp: false });
  // the phone moves to a generation it does publish, and writes under it
  const g2 = V.newGeneration(phone2.current.id, {});
  const pl = { ...phone2.entries[0].payload, gens: V.mergeGens(phone2.gens, [g2]) };
  const ev2 = await sg.sign(V.keyringTemplate(pl, me2, Math.floor(Date.now() / 1000) + 1));
  await net2.publish(made2.payload.relays, ev2);
  phone2.entries = [{ event: ev2, payload: pl }]; phone2.gens = pl.gens; phone2.depths = V.chainDepths(pl.gens, phone2.first.id);
  phone2.store.set("started:" + phone2.genesis, [g2.id]); phone2.vetting();
  await phone2.commit([note("8000000000000000", "newest", 3)]);
  const fresh2 = await open2("Fresh browser");
  ok(texts(fresh2).includes("newest"), "a fresh browser reaches the newest epoch past a rekey to a generation nobody holds: " + texts(fresh2));
}

// ── a browser that removes many devices (each web sign-in that ends is one) ──
{
  const net3 = memoryNet(), sec3 = C.randomKey(), sg = localSigner(sec3), me3 = sg.pubkey;
  const made3 = await makeVault(net3, sec3, {});
  const open3 = async (name) => new Session({ me: me3, entries: notesVault(await readKeyrings({ me: me3, relays: made3.payload.relays, net: net3, signer: sg })), net: net3, signer: sg, store: memoryStore(), deviceName: name }).open();
  const web3 = await open3("Marble Web");
  await web3.commit([note("9000000000000000", "web first", 1)]);
  const REMOVE = 24;
  for (let i = 0; i < REMOVE; i++) {
    // (a device of yours that signed in once, its cert carried here, then removed: the next write starts a generation)
    const t = Math.floor(Date.now() / 1000), root = C.pubHex(C.randomKey());
    const cert = C.signEvent(sec3, V.deviceCertTemplate(root, null, t));
    const rev = C.signEvent(sec3, { kind: V.KIND.CERT, tags: [], content: C.canon({ type: "revoke", root, keep: [] }), created_at: t });
    await web3.commit([{ op: "auth", event: cert }, { op: "auth", event: rev }]);
    await web3.commit([note("b1" + String(i).padStart(14, "0"), "after removal " + i, 2)]);
  }
  const dvk3 = C.pubHex(V.dvkFor(C.unhex(web3.store.get("root")), web3.genesis, 0));
  const sizes = web3.vault.chain(dvk3).map((c) => C.utf8(c.c).length);
  ok(Math.max(...sizes) <= 12 * 1024, `every commit this browser made is within 12 KiB after ${REMOVE} removals (largest ${Math.max(...sizes)} bytes)`);
  ok(web3.vault.roster().revoked.size === REMOVE, "and every removal counts");
  const other3 = await open3("Fresh device");
  ok(texts(other3).includes("after removal " + (REMOVE - 1)) && other3.current.id === web3.current.id, "a fresh device reads the note written after the last removal, under the newest generation");
}
console.log(`notes sync smoke (web engine): ${n} checks pass`);
