// A search naming someone (site/js/author-query.js): "from:fiatjaf failed" when you don't follow fiatjaf, or you're
// signed out. It used to find nothing: the whole text went to the search servers (they ignore from: and send anyone's
// posts about "failed"), then the page kept only posts saying "from:fiatjaf failed" word for word: none.
//   node tools/author-query.test.mjs
import { split, explicit, anyone } from "../site/js/author-query.js";
import { queryRe } from "../site/js/find.js";

let n = 0;
const ok = (c, m) => { if (!c) throw new Error("FAIL: " + m); n++; };
const same = (a, b, m) => ok(JSON.stringify(a) === JSON.stringify(b), `${m}: ${JSON.stringify(a)} vs ${JSON.stringify(b)}`);

// ── from:name and @name, taken out of the words ──
same(explicit("from:fiatjaf failed"), { name: "fiatjaf", rest: "failed", strict: true }, "from: (strict: their posts alone)");
same(explicit("failed from:fiatjaf"), { name: "fiatjaf", rest: "failed", strict: true }, "from: last");
same(explicit("@fiatjaf bunker failed"), { name: "fiatjaf", rest: "bunker failed", strict: false }, "@name (the softer kind)");
same(explicit("FROM:fiatjaf failed"), { name: "fiatjaf", rest: "failed", strict: true }, "any case");
same(explicit("from:fiatjaf"), { name: "fiatjaf", rest: "", strict: true }, "a bare from:name: their latest posts");
ok(explicit("failed") === null && explicit("@fiatjaf") === null && explicit("") === null, "a bare @name is a person, not this");
ok(split("from:fiatjaf", new Set(["3b".repeat(32)]), new Map([["3b".repeat(32), { name: "fiatjaf" }]]))?.rest === "", "a bare from: someone you follow");
ok(explicit("from:x failed") === null, "a one-letter name");

// ── the words everything else looks for ──
const post = "the app didn't even know my public key as the bunker stuff had failed";
ok(!queryRe("from:fiatjaf failed").test(post), "the whole text matches no post (what happened)");
ok(queryRe(explicit("from:fiatjaf failed").rest).test(post), "the rest does");

// ── nobody you follow: the person people search found ──
const FIAT = "3b".repeat(32), FAKE = "aa".repeat(32), OTHER = "bb".repeat(32), PART = "cc".repeat(32);
const found = [ // people search's order
  { pubkey: PART, name: "fiatjafsfan" },
  { pubkey: FIAT, name: "fiatjaf", nip05: "_@fiatjaf.com" },
  { pubkey: FAKE, display_name: "fiatjaf" },
  { pubkey: OTHER, name: "someone" },
];
same(anyone(found, "fiatjaf"), { author: FIAT, others: [FAKE] }, "their name, in people search's order; a part of a name isn't them");
same(anyone(found, "Fiatjaf"), { author: FIAT, others: [FAKE] }, "any case");
ok(anyone(found, "fiat") === null, "a part of a name names nobody");
ok(anyone([], "fiatjaf") === null && anyone(null, "fiatjaf") === null, "nobody found");

// ── people you follow still come first (split), and signed out split has nobody ──
ok(split("from:fiatjaf failed", new Set(), new Map()) === null, "signed out: split has nobody (anyone() then looks)");
const known = new Map([[FIAT, { name: "fiatjaf" }]]);
same(split("from:fiatjaf failed", new Set([FIAT]), known), { author: FIAT, rest: "failed", others: [], loose: false }, "followed");

console.log(`author-query: ${n} checks passed`);
