// Marble Web's search in the page (headless Chromium driving site/ as it is), against fake relays that answer the way
// the real ones do at their worst (tools/search-fake-relay.mjs): search servers that take a post with ANY of the words,
// one that ignores the search and sends everything, an archive that matches any word, and copycats of fiatjaf.
// Nothing outside is reached: the site, every relay, Primal and the NIP-05 lookups are answered here (page.route /
// routeWebSocket), with no ports opened.
//   node tools/search-page.test.mjs                  (SEARCH_SITE=<dir> runs another copy of site/, e.g. an old one)
// Needs ~/keykin/node_modules/playwright-core and a Playwright Chromium in ~/Library/Caches/ms-playwright; skipped
// without them. Every check runs; the failures are listed at the end (exit 1).
import { execFileSync } from "node:child_process";
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { fixtures, posts, archive, pk } from "./search-fixtures.mjs";
import { relaySocket } from "./search-fake-relay.mjs";

const pwPath = path.join(os.homedir(), "keykin/node_modules/playwright-core/index.mjs");
function chromium() {
  const cache = path.join(os.homedir(), "Library/Caches/ms-playwright");
  if (!existsSync(cache)) return null;
  const dirs = readdirSync(cache).sort().reverse();
  for (const [pre, name] of [["chromium_headless_shell-", "chrome-headless-shell"], ["chromium-", "Chromium"]]) {
    for (const d of dirs.filter((n) => n.startsWith(pre) && /\d$/.test(n))) {
      try { const p = execFileSync("find", [path.join(cache, d), "-maxdepth", "6", "-type", "f", "-perm", "+111", "-name", name], { encoding: "utf8" }).split("\n")[0]; if (p) return p; } catch {}
    }
  }
  return null;
}
const exe = chromium();
if (!existsSync(pwPath) || !exe) { console.log(`search page: SKIPPED (needs ${pwPath} and a Playwright Chromium)`); process.exit(0); }
const { chromium: pw } = await import(pwPath);

// (Android reads the same world from the JSON: it must be this one)
const json = readFileSync(new URL("./search-fixtures.json", import.meta.url), "utf8");
if (json !== JSON.stringify(fixtures, null, 1) + "\n") { console.log("search page: tools/search-fixtures.json is out of date (node tools/search-fixtures.mjs --write)"); process.exit(1); }

const SITE = path.resolve(process.env.SEARCH_SITE || new URL("../site/", import.meta.url).pathname) + "/";
const BASE = "http://localhost:7801";
const ARCHIVE = "wss://archive.test";
const types = { ".html": "text/html", ".js": "text/javascript", ".json": "application/json", ".svg": "image/svg+xml", ".png": "image/png", ".css": "text/css" };
// How each relay answers. Search servers match any word; search.nos.today and the plain relays ignore `search`.
const HOW = {
  "wss://relay.ditto.pub": "loose", "wss://syb.lol": "loose", "wss://search.nostrarchives.com": "loose", "wss://search.nos.today": "ignore",
  "wss://relay.damus.io": "ignore", "wss://nos.lol": "ignore", "wss://relay.primal.net": "ignore", "wss://purplepag.es": "ignore", "wss://user.kindpag.es": "ignore",
  "wss://cache1.primal.net/v1": "primal", "wss://cache2.primal.net/v1": "primal", [ARCHIVE]: "loose",
};

const failures = [];
let n = 0;
const ok = (c, m) => { n++; if (!c) { failures.push(m); console.log("  FAIL " + m); } };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const byId = new Map([...fixtures.publicEvents, ...fixtures.archiveEvents].map((e) => [e.id, e]));
const name = Object.fromEntries([...Object.entries(posts), ...Object.entries(archive)].map(([k, e]) => [e.id, k]));
const all = [...fixtures.publicEvents, ...fixtures.archiveEvents].filter((e) => e.kind === 1);
// A result on screen, as the fixture post it shows (its text holds the post's first 40 characters).
const which = (r) => all.find((e) => r.text.includes(e.content.slice(0, 40)));
const label = (e) => e ? (name[e.id] || e.content.slice(0, 24)) : "?";
const whoIs = (h) => Object.keys(pk).find((k) => pk[k] === h) || (h ? h.slice(0, 8) : "nobody");
const hasAll = (e, ws) => !!e && ws.every((w) => e.content.toLowerCase().includes(w));

const browser = await pw.launch({ executablePath: exe });
try {
  /** The page searching [q]; signedIn: as the test account (?as=, read-only), with the archive on its list. */
  async function search(q, { signedIn = false } = {}) {
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const page = await context.newPage();
    const errors = [], log = [];
    page.on("pageerror", (e) => errors.push(String(e)));
    await page.routeWebSocket(/.*/, (ws) => {
      const url = ws.url().replace(/\/$/, ""), how = HOW[url];
      if (!how) return ws.close();
      const events = url === ARCHIVE ? [...fixtures.archiveEvents, ...fixtures.publicEvents.filter((e) => e.kind === 0)] : fixtures.publicEvents;
      ws.onMessage(relaySocket(url, how, events, fixtures, (m) => ws.send(m), log));
    });
    await page.route(() => true, (route) => {
      const u = new URL(route.request().url());
      if (u.pathname === "/.well-known/nostr.json" && fixtures.nip05[u.hostname])
        return route.fulfill({ status: 200, contentType: "application/json", headers: { "access-control-allow-origin": "*" }, body: JSON.stringify({ names: fixtures.nip05[u.hostname] }) });
      if (u.origin !== BASE) return route.fulfill({ status: 404, contentType: "text/plain", headers: { "access-control-allow-origin": "*" }, body: "" }); // outside: not reached
      let f = path.join(SITE, decodeURIComponent(u.pathname));
      if (!f.startsWith(SITE) || !existsSync(f) || statSync(f).isDirectory()) f = path.join(SITE, "index.html");
      return route.fulfill({ status: 200, contentType: types[path.extname(f)] || "application/octet-stream", body: readFileSync(f) });
    });
    if (signedIn) await context.addInitScript(({ me, url }) => {
      localStorage.setItem(`marble.private-relays.${me}`, JSON.stringify({ v: 1, relays: [{ url, at: 1, gone: false }] }));
    }, { me: pk.ME, url: ARCHIVE });
    await page.goto(`${BASE}/?q=${encodeURIComponent(q)}${signedIn ? `&as=${pk.ME}` : ""}`);
    // Until nothing says "Searching…" and what's shown has stayed the same for a moment (20 s at most).
    let last = "", same = 0, shot;
    for (let i = 0; i < 80; i++) {
      await sleep(250);
      shot = await page.evaluate(() => {
        const main = document.querySelector("#main");
        if (!main) return null;
        const of = (el) => ({ pk: el.querySelector(".res-who [data-name]")?.dataset.name || "", text: el.querySelector(".text")?.textContent || "" });
        const sections = [...main.querySelectorAll(":scope > section")];
        const arch = sections.find((s) => s.querySelector("h2")?.textContent === "From your archive");
        const by = main.querySelector(":scope > section.by");
        return {
          loading: !!main.querySelector(".loading-text"),
          archive: arch ? [...arch.querySelectorAll(".res")].map(of) : [],
          archiveAll: arch?.querySelector("[data-open]")?.textContent || "",
          by: by ? { who: by.querySelector(".by-head [data-name]")?.dataset.name || "", list: [...by.querySelectorAll(".res")].map(of), said: by.querySelector(".muted")?.textContent || "", all: by.querySelector("[data-open]")?.textContent || "" } : null,
          general: [...main.querySelectorAll(":scope > article.res")].map(of),
          nothing: /Nothing found/.test(main.textContent),
        };
      });
      const s = JSON.stringify(shot);
      if (shot && !shot.loading && s === last) { if (++same >= 6) break; } else same = 0;
      last = s;
    }
    await context.close();
    const relaysAsked = new Set(log.map((x) => x.relay));
    return { ...shot, errors, log, relaysAsked };
  }
  const show = (l) => l.map((r) => label(which(r))).join(", ");

  console.log("1. a plain word: \"failed\", signed out");
  {
    const r = await search("failed");
    const shown = r.general.map(which);
    ok(r.general.length > 0, `"failed" shows posts, not nothing (${r.general.length}${r.nothing ? ', "Nothing found"' : ""})`);
    ok(shown.every((e) => hasAll(e, ["fail"])), `every post shown has the word: ${show(r.general)}`);
    ok(!shown.some((e) => e?.pubkey === pk.JUNK), `nothing from the relay that ignores the search leaks in: ${show(r.general)}`);
    ok(shown.some((e) => e?.id === posts.a1.id || e?.id === posts.b1.id), `posts by known people with the word are there: ${show(r.general)}`);
    ok(r.errors.length === 0, `no page errors: ${r.errors.join(" | ")}`);
  }

  console.log("2. from:fiatjaf failed, signed out (fiatjaf is a stranger, with copycats)");
  {
    const r = await search("from:fiatjaf failed");
    ok(r.by?.who === pk.FIAT, `from: names the real fiatjaf, not a copycat (${whoIs(r.by?.who)})`);
    const theirs = (r.by?.list || []).map(which);
    ok(theirs.length >= 2 && theirs.every((e) => e?.pubkey === pk.FIAT && hasAll(e, ["failed"])), `their posts with "failed", only: ${show(r.by?.list || [])}`);
    ok(theirs.some((e) => e?.id === posts.f1.id) && theirs.some((e) => e?.id === posts.f3.id), `both of them (f1, f3): ${show(r.by?.list || [])}`);
    const others = [...r.general, ...r.archive].map(which);
    ok(others.length === 0, `nothing else under from: (no general posts): ${show([...r.general, ...r.archive])}`);
    ok(!r.log.some((x) => /from:/i.test(x.filter.search || "")), `"from:" never goes to a relay as words: ${r.log.filter((x) => /from:/i.test(x.filter.search || "")).map((x) => x.relay).join(", ")}`);
    ok(!r.nothing, `no "Nothing found" under their posts`);
    ok(r.errors.length === 0, `no page errors: ${r.errors.join(" | ")}`);
  }

  console.log("3. from:fiatjaf, signed out: their latest posts");
  {
    const r = await search("from:fiatjaf");
    const theirs = (r.by?.list || []).map(which);
    ok(r.by?.who === pk.FIAT, `a bare from: names the real fiatjaf (${whoIs(r.by?.who)})`);
    ok(theirs.length >= 3 && theirs.every((e) => e?.pubkey === pk.FIAT), `their latest posts: ${show(r.by?.list || [])} ${r.by?.all || ""}`);
    ok(r.general.length === 0, `nothing else: ${show(r.general)}`);
    ok(r.errors.length === 0, `no page errors: ${r.errors.join(" | ")}`);
  }

  console.log("4. @fiatjaf failed: the softer kind (their posts, and the usual results)");
  {
    const r = await search("@fiatjaf failed");
    const theirs = (r.by?.list || []).map(which);
    ok(r.by?.who === pk.FIAT && theirs.length > 0 && theirs.every((e) => e?.pubkey === pk.FIAT), `their posts first: ${show(r.by?.list || [])}`);
    ok(r.general.length > 0 && r.general.map(which).every((e) => hasAll(e, ["fail"])), `and the usual results for "failed": ${show(r.general)}`);
  }

  console.log("5. relay failed, signed in with an archive that matches any word");
  {
    const r = await search("relay failed", { signedIn: true });
    const mine = r.archive.map(which);
    ok(r.relaysAsked.has(ARCHIVE), "the archive is asked");
    ok(mine.length > 0, `the archive's posts with both words show (${mine.length})`);
    ok(mine.every((e) => hasAll(e, ["relay", "failed"])), `the archive shows only posts with every word: ${show(r.archive)} ${r.archiveAll}`);
    ok(!/See all/.test(r.archiveAll), `no flood of archive posts ("${r.archiveAll}")`);
    ok(r.general.length > 0, `and the usual results (signed in, search isn't empty): ${show(r.general)}`);
    ok(r.general.map(which).every((e) => hasAll(e, ["relay", "fail"])), `every usual result has the words: ${show(r.general)}`);
    ok(r.errors.length === 0, `no page errors: ${r.errors.join(" | ")}`);
  }

  console.log("6. failed, signed in: a plain search isn't empty");
  {
    const r = await search("failed", { signedIn: true });
    ok(r.general.length > 0, `"failed" signed in shows posts (${r.general.length}${r.nothing ? ', "Nothing found"' : ""})`);
    ok([...r.general, ...r.archive].map(which).every((e) => hasAll(e, ["fail"])), `every post shown has the word: ${show([...r.archive, ...r.general])}`);
  }

  console.log("7. from:fiatjaf relay failed, signed in with the archive: only theirs, from the archive too");
  {
    const r = await search("from:fiatjaf relay failed", { signedIn: true });
    const everything = [...r.archive, ...(r.by?.list || []), ...r.general].map(which);
    ok(everything.length > 0, `something shows (${everything.length})`);
    ok(everything.every((e) => e?.pubkey === pk.FIAT && hasAll(e, ["relay", "failed"])), `only fiatjaf's posts with both words: archive [${show(r.archive)}], theirs [${show(r.by?.list || [])}], other [${show(r.general)}]`);
    ok(everything.some((e) => e?.id === archive.fa1.id), `their archive post is there: ${show(r.archive)}`);
  }

  console.log("8. from:_@fiatjaf.test failed: an address names whoever it names");
  {
    const r = await search("from:_@fiatjaf.test failed");
    const theirs = (r.by?.list || []).map(which);
    ok(r.by?.who === pk.FIAT, `the address's key (${whoIs(r.by?.who)})`);
    ok(theirs.length >= 2 && theirs.every((e) => e?.pubkey === pk.FIAT && hasAll(e, ["failed"])) && r.general.length === 0, `their posts with "failed", only: ${show(r.by?.list || [])} / ${show(r.general)}`);
  }

  console.log("9. from:nobodyatall failed: no one by that name says so, and stops searching");
  {
    const r = await search("from:nobodyatall failed");
    ok(!r.loading && r.nothing && !r.by && r.general.length === 0, `"Nothing found", nothing shown (loading ${r.loading}, by ${!!r.by}, ${r.general.length} posts)`);
  }
} finally {
  await browser.close();
}
if (failures.length) { console.log(`search page: ${failures.length} of ${n} checks FAILED`); process.exit(1); }
console.log(`search page: ${n} checks passed`);
