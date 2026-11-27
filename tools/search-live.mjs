// Marble Web's search against the real, public relays (read only: it searches, nothing is sent or signed). Headless
// Chromium on site/ as it is here (answered from disk; everything else, relays included, is the real thing):
//   "failed"               posts, each with the word
//   "from:fiatjaf failed"  only fiatjaf's (3bf0c63f…) posts, each with "failed", and nothing else
// A failure says which step, and what each relay did (answered, how many events, or never connected).
//   node tools/search-live.mjs        (SEARCH_OFFLINE=1 skips it; tools/search-smoke.sh runs it)
// Needs ~/keykin/node_modules/playwright-core and a Playwright Chromium; skipped without them.
import { execFileSync } from "node:child_process";
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import os from "node:os";
import path from "node:path";

if (process.env.SEARCH_OFFLINE) { console.log("search live (web): SKIPPED (SEARCH_OFFLINE)"); process.exit(0); }
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
if (!existsSync(pwPath) || !exe) { console.log(`search live (web): SKIPPED (needs ${pwPath} and a Playwright Chromium)`); process.exit(0); }
const { chromium: pw } = await import(pwPath);

const FIATJAF = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d";
const SITE = path.resolve(new URL("../site/", import.meta.url).pathname) + "/";
const BASE = "http://localhost:7802";
const types = { ".html": "text/html", ".js": "text/javascript", ".json": "application/json", ".svg": "image/svg+xml", ".png": "image/png", ".css": "text/css" };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const failures = [];
const ok = (c, m, why = "") => { if (!c) { failures.push(m); console.log(`  FAIL ${m}${why ? "\n       " + why : ""}`); } else console.log(`  ok   ${m}`); };

const browser = await pw.launch({ executablePath: exe });
try {
  async function search(q) {
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const page = await context.newPage();
    const relays = new Map(); // url → {events, eose, error, closed}
    page.on("websocket", (ws) => {
      const r = { events: 0, eose: 0, notices: [], error: "", opened: Date.now() };
      relays.set(ws.url(), r);
      ws.on("framereceived", (f) => { const s = String(f.payload); if (s.startsWith('["EVENT"')) r.events++; else if (s.startsWith('["EOSE"')) r.eose++; else if (/^\["(NOTICE|CLOSED)"/.test(s)) r.notices.push(s.slice(0, 120)); });
      ws.on("socketerror", (e) => { r.error = String(e); });
    });
    await page.route(() => true, (route) => {
      const u = new URL(route.request().url());
      if (u.origin !== BASE) return route.continue();
      let f = path.join(SITE, decodeURIComponent(u.pathname));
      if (!f.startsWith(SITE) || !existsSync(f) || statSync(f).isDirectory()) f = path.join(SITE, "index.html");
      return route.fulfill({ status: 200, contentType: types[path.extname(f)] || "application/octet-stream", body: readFileSync(f) });
    });
    await page.goto(`${BASE}/?q=${encodeURIComponent(q)}`);
    let shot, last = "", same = 0;
    for (let i = 0; i < 120; i++) { // (30 s at most)
      await sleep(250);
      shot = await page.evaluate(() => {
        const main = document.querySelector("#main");
        if (!main) return null;
        const of = (el) => ({ pk: el.querySelector(".res-who [data-name]")?.dataset.name || "", text: el.querySelector(".text")?.textContent || "", title: el.querySelector(".res-title")?.textContent || "" });
        const by = main.querySelector(":scope > section.by");
        return { loading: !!main.querySelector(".loading-text"), general: [...main.querySelectorAll(":scope > article.res")].map(of),
          by: by ? { who: by.querySelector(".by-head [data-name]")?.dataset.name || "", list: [...by.querySelectorAll(".res")].map(of) } : null,
          nothing: /Nothing found/.test(main.textContent) };
      });
      const s = JSON.stringify(shot);
      if (shot && !shot.loading && s === last) { if (++same >= 8) break; } else same = 0;
      last = s;
    }
    await context.close();
    const report = [...relays].map(([u, r]) => `${u.replace(/^wss:\/\//, "")}: ${r.error ? "failed (" + r.error + ")" : `${r.events} events, ${r.eose ? "answered" : "never said it was done"}`}${r.notices.length ? " " + r.notices.join(" ") : ""}`).join("\n       ");
    return { ...shot, report };
  }

  console.log('1. "failed"');
  {
    const r = await search("failed");
    ok(r.general.length > 0, `"failed" shows posts (${r.general.length}${r.nothing ? ', "Nothing found"' : ""}${r.loading ? ", still searching after 30 s" : ""})`, `relays:\n       ${r.report}`);
    const bad = r.general.filter((x) => !/fail/i.test(x.text));
    ok(!bad.length, `every post shown has the word (${bad.length} without it)`, bad.map((x) => x.text.slice(0, 80)).join(" | "));
  }
  console.log('2. "from:fiatjaf failed"');
  {
    const r = await search("from:fiatjaf failed");
    ok(r.by?.who === FIATJAF, `from:fiatjaf names fiatjaf (3bf0c63f…), not a copycat (named ${r.by?.who?.slice(0, 8) || "nobody"})`, `people search (relays):\n       ${r.report}`);
    const list = r.by?.list || [];
    ok(list.length > 0, `his posts with "failed" show (${list.length})`, `relays:\n       ${r.report}`);
    // (an article shows its title and summary, not its text: the word may be only in the text, which the page checked)
    const bad = list.filter((x) => x.pk !== FIATJAF || !(/failed/i.test(x.text + " " + x.title) || x.title));
    ok(!bad.length, `only his, each with "failed" (${bad.length} not)`, bad.map((x) => `${x.pk.slice(0, 8)}: ${x.text.slice(0, 60)}`).join(" | "));
    ok(r.general.length === 0, `nothing else under from: (${r.general.length} other posts)`);
  }
} finally {
  await browser.close();
}
if (failures.length) { console.log(`search live (web): ${failures.length} FAILED (if this Mac is offline: SEARCH_OFFLINE=1)`); process.exit(1); }
console.log("search live (web): passed");
