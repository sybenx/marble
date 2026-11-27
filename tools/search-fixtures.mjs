// The search smoke test's world (tools/search-smoke.sh): a few people, copycats of one of them, their posts, and an
// archive full of posts that share only one word with a search. Test keys only (secret = 0…0n), signed with a fixed aux,
// so the same file comes out every time.
//   node tools/search-fixtures.mjs --write     (writes tools/search-fixtures.json, which Android's tests read)
// Used by tools/search-fake-relay.mjs (the web page test) and android/app/src/test/.../SearchSmokeTest.kt (the JSON).
import { writeFileSync } from "node:fs";
import * as C from "../site/js/vault/crypto.js";

const key = (n) => { const k = new Uint8Array(32); k[31] = n; return k; };
const AUX = new Uint8Array(32);
const BASE = 1764000000; // (2025-11-24: fixed, so the file is the same every time)

export const secrets = { FIAT: key(1), COPY_BARE: key(2), COPY_FAKE: key(3), FAN: key(4), ALICE: key(5), BOB: key(6), ME: key(7), CAROL: key(8), JUNK: key(9) };
export const pk = Object.fromEntries(Object.entries(secrets).map(([k, s]) => [k, C.pubHex(s)]));

const sign = (who, kind, created_at, content, tags = []) => C.signEvent(secrets[who], { kind, created_at, tags, content }, AUX);
const profile = (who, at, p) => sign(who, 0, at, JSON.stringify(p));

// ── people: the real one, two copycats with the same name (newer, so they come first by date), a fan whose name
//    starts with it, and people who aren't named anything like it ──
// (as on the real relays: the real one's newest profile has no address, an older one has; and more copies than a
// search looks at closely, with keys that sort before his, each with an about of "~" so they don't look bare)
const copies = [];
for (let n = 20; copies.length < 16; n++) { const s = key(n); if (C.pubHex(s) < C.pubHex(secrets.FIAT)) copies.push(s); }
copies.forEach((s, i) => { secrets[`COPY${i + 1}`] = s; pk[`COPY${i + 1}`] = C.pubHex(s); });
const profiles = [
  profile("FIAT", BASE - 90000, { name: "fiatjaf", about: "building nostr things", nip05: "_@fiatjaf.test" }),
  profile("FIAT", BASE - 80000, { name: "fiatjaf", about: "building nostr things, and a new picture" }),
  ...copies.map((_, i) => profile(`COPY${i + 1}`, BASE - 1000 - i, { name: "fiatjaf", about: "~" })),
  profile("COPY_BARE", BASE - 100, { name: "fiatjaf", display_name: "fiatjaf" }),
  profile("COPY_FAKE", BASE - 200, { name: "fiatjaf", about: "the real fiatjaf, follow me here", nip05: "fiatjaf@copy.test" }),
  profile("FAN", BASE - 300, { name: "fiatjafsfan", about: "a fan" }),
  profile("ALICE", BASE - 400, { name: "alice", about: "bakes bread" }),
  profile("BOB", BASE - 500, { name: "bob", about: "runs servers" }),
  profile("ME", BASE - 600, { name: "tester", about: "the test account" }),
  profile("CAROL", BASE - 700, { name: "carol", about: "keeps an archive" }),
  profile("JUNK", BASE - 800, { name: "sunshine", about: "good mornings" }),
];

/** Followers per person (relay COUNT on kind 3 #p, and Primal's user_infos): the real one has many, copies a handful. */
export const followers = { [pk.FIAT]: 52000, [pk.COPY_BARE]: 2, [pk.COPY_FAKE]: 40, [pk.FAN]: 500, [pk.ALICE]: 3100, [pk.BOB]: 1200, [pk.ME]: 10, [pk.CAROL]: 800, [pk.JUNK]: 4000 };
/** NIP-05: domain → names → key (copy.test names someone else, so the copycat's address doesn't check out). */
export const nip05 = { "fiatjaf.test": { _: pk.FIAT }, "copy.test": { fiatjaf: pk.BOB } };

let t = BASE;
const post = (who, content) => sign(who, 1, (t -= 600), content);

// ── posts on the public relays ──
export const posts = {
  f1: post("FIAT", "The bunker failed to answer again, so signing in with the app failed for everyone this morning."),
  f2: post("FIAT", "Relays are a simple idea: anyone can run one and clients pick where to read and write."),
  f3: post("FIAT", "My deploy of the new relay build failed twice before the cache cleared, all good now."),
  f4: post("FIAT", "Writing a tutorial about how to build a small Nostr client from scratch this weekend."),
  c1: post("COPY_BARE", "Everything I tried today failed, the bunker failed and the relay failed too, what a day."),
  c2: post("COPY_FAKE", "Official update from the real fiatjaf: the login failed for some of you, it is fixed now."),
  fan1: post("FAN", "I love everything fiatjaf builds, even when the bunker failed it was fun to debug it."),
  a1: post("ALICE", "My first attempt at baking sourdough failed completely but the second loaf came out great."),
  a2: post("ALICE", "Spent the afternoon at the lake with friends, the weather was perfect for a long swim."),
  b1: post("BOB", "The upload failed because the storage server was busy, retrying later fixed it for me."),
  b3: post("BOB", "Last night the relay failed during the upgrade, so I rolled it back and all was fine."),
  b2: post("BOB", "Marble search works nicely when you type a couple of words and press enter on the keyboard."),
  j1: post("JUNK", "Good morning everyone, have a wonderful day and enjoy the sunshine outside today."),
  j2: post("JUNK", "Another lovely morning here, the coffee is hot and the birds are singing in the garden."),
};
// (ME follows Bob: a signed-in search has follows, as a real account does)
const follows = sign("ME", 3, BASE - 50, "", [["p", pk.BOB]]);

// ── your archive (owner-only in life; here it just answers): it matches ANY word, as Chronicle's search does, and
//    has far more posts with one of the words than with all of them, newer ones too ──
t = BASE + 100000;
const filler = [];
for (let i = 1; i <= 24; i++) filler.push(post("CAROL", `Relay update number ${i}: everything is running smoothly on the home server tonight.`));
for (let i = 1; i <= 6; i++) filler.push(post("CAROL", `Backup run ${i} failed halfway because the laptop went to sleep in the middle of it.`));
filler.push(post("COPY_BARE", "From my archive too: the relay failed and so did everything else, follow me for more."));
export const archive = {
  r3: post("CAROL", "The relay failed over to the backup machine at three in the morning without any trouble."),
  fa1: post("FIAT", "From the archive: the relay failed to sync my old notes, a restart fixed it right away."),
  fa2: post("FIAT", "Archive thought: nothing broke today, it was a quiet and pleasant day at the desk."),
};

/** Everything on the public relays, and everything in the archive (posts with one word, and the ones with all). */
export const publicEvents = [...profiles, follows, ...Object.values(posts)];
export const archiveEvents = [...filler, ...Object.values(archive)];

export const fixtures = { pk, followers, nip05, publicEvents, archiveEvents, posts: Object.fromEntries(Object.entries(posts).map(([k, e]) => [k, e.id])), archive: Object.fromEntries(Object.entries(archive).map(([k, e]) => [k, e.id])) };

if (process.argv.includes("--write")) {
  writeFileSync(new URL("./search-fixtures.json", import.meta.url), JSON.stringify(fixtures, null, 1) + "\n");
  console.log(`wrote tools/search-fixtures.json: ${publicEvents.length} public events, ${archiveEvents.length} archive events`);
}
