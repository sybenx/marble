// Writes android/app/src/test/resources/apps/vectors.json: a small index and what site/js/apps.js returns for each
// query. AppIndexVectorsTest.kt checks the phone gives the same. Run after changing the rules: bun apps-index/vectors.ts
import { searchApps, textScore, appLine } from "../site/js/apps.js";
const pk = (c: string) => c.repeat(64);
const A = (o: any) => ({ a: "", kind: "web", about: "", icon: null, domain: null, site: null, by: null, official: false, zapstore: null, score: 50, ...o });
const apps = [
  A({ a: "31990:1:jumble", name: "Jumble", domain: "jumble.social", url: "https://jumble.social/", pk: pk("1"), official: true, score: 92, about: "A user-friendly Nostr client for exploring relay feeds" }),
  A({ a: "35128:2:jumble", kind: "nsite", name: "Jumble", url: "https://x.nsite.lol/", pk: pk("2"), by: "44 Mirror", score: 20 }),
  A({ a: "35128:3:rj", kind: "nsite", name: "RogueJumble", url: "https://y.nsite.lol/", pk: pk("3"), score: 30 }),
  A({ a: "31990:4:nostrudel", name: "noStrudel", domain: "nostrudel.ninja", url: "https://nostrudel.ninja/", pk: pk("4"), official: true, score: 86, about: "A sandbox for exploring nostr" }),
  A({ a: "31990:5:coracle", name: "Coracle", domain: "coracle.social", url: "https://coracle.social/", pk: pk("5"), official: true, zapstore: "social.coracle", score: 133.7, about: "Nostr, your way" }),
  A({ a: "32267:6:amber", kind: "android", name: "Amber", url: "https://zapstore.dev/apps/com.greenart7c3.nostrsigner", pk: pk("6"), score: 67, about: "Nostr event signer for Android", by: "greenart7c3" }),
  A({ a: "15128:7", kind: "nsite", name: "Nostr Wallet Connect Tester", url: "https://z.nsite.lol/", pk: pk("7"), by: "hzrd149", score: 40 }),
  A({ a: "15128:8", kind: "nsite", name: "Café Notes", url: "https://c.nsite.lol/", pk: pk("8"), score: 25, about: "Write notes over coffee" }),
  ...["a", "b", "c", "d"].map((c, i) => A({ a: `35128:9:${c}`, kind: "nsite", name: `Shakespeare demo ${c}`, url: `https://${c}.nsite.lol/`, pk: pk("9"), by: "Derek Ross", score: 30 - i })),
];
const follows = [pk("4"), pk("8")];
const queries = ["jumble", "Jum", "jumble.social", "nostr", "strudel", "coracle", "nostrudel", "wallet tester", "cafe", "café", "coffee", "shakespeare", "shakespeare demo", "j", "zz", "derek", "signer", "NOSTR wallet", "ross"];
const fset = new Set(follows);
const cases = queries.map((q) => ({ q, top: searchApps(apps, q, { follows: fset, limit: 8 }).map((a) => a.a), scores: apps.map((a) => textScore(a, q)) }));
const lines = apps.map((a) => appLine(a, fset));
await Bun.write(new URL("../android/app/src/test/resources/apps/vectors.json", import.meta.url), JSON.stringify({ v: 1, apps, follows, cases, lines }, null, 1));
for (const c of cases) console.log(c.q.padEnd(18), c.top.join(" "));
