// Marble's app index: builds apps.json, the list of Nostr web apps and nsites that Marble searches on the phone.
//
// Runs once a day on Took next to Diamonds (see README.md). Plan and measurements: ~/igloo/marble-app-search-research.md.
//
// Sources: nsites (NIP-5A kinds 15128 root, 35128 named), NIP-89 handlers (31990) and recommendations (31989),
// Zapstore apps (32267, checked against relay.zapstore.dev). Everything is gated on trust: the author must be the
// seed account, someone it follows, someone *they* follow, a nostr-builders entry, or a Zapstore publisher. That gate
// alone removed all the casino-spam nsites in the research pass. "Official" comes from the app domain's
// /.well-known/nostr.json naming the author.
//
// Output: one JSON file written atomically. If the build looks gutted (relays down), the old file is kept and the
// process exits non-zero, like nostr-builders' collect.ts does with MIN_ENTRIES.
//
// Run: bun build.ts   (env: SEED, OUT, STATE, see below)

import { schnorr } from "@noble/curves/secp256k1";
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";

const SEED = process.env.SEED ?? "f5073ea24832d8e6246d22ec0b5a75bbf4a12558e3ac67cb07360b3474e5a40e"; // Aaron
const OUT = resolve(process.env.OUT ?? "public/apps.json");
const STATE = resolve(process.env.STATE ?? "state.json");
const BUILDERS_URL = "https://sybenx.github.io/nostr-builders/data.json";
const GATEWAY = "nsite.lol";

// Relays the research found useful, best first.
const APP_RELAYS = ["wss://relay.ditto.pub", "wss://relay.nsite.lol", "wss://purplepag.es", "wss://relay.primal.net", "wss://syb.lol"];
const GRAPH_RELAYS = ["wss://purplepag.es", "wss://relay.damus.io", "wss://relay.primal.net", "wss://relay.ditto.pub"];
const ZAPSTORE = "wss://relay.zapstore.dev";

const MIN_APPS = Number(process.env.MIN_APPS ?? 200); // fewer than this means relays were down
const MIN_RATIO = 0.6;                                 // or under 60% of the last good build
const PER_AUTHOR = 12;                                 // most nsites one author gets in the index
const DEAD_AFTER = 3;                                  // builds in a row an nsite's index.html can't be fetched
const NOW = Math.floor(Date.now() / 1000), DAY = 86400;
const log = (...a: unknown[]) => console.error(new Date().toISOString().slice(11, 19), ...a);

type Ev = { id: string; pubkey: string; created_at: number; kind: number; tags: string[][]; content: string; sig: string };

// ------------------------------------------------------------------ relay access

function query(url: string, filter: object, onEvent: (e: Ev) => void, ms = 25_000): Promise<number> {
  return new Promise((res) => {
    let n = 0, ws: WebSocket;
    const done = () => { clearTimeout(t); try { ws.close(); } catch {} res(n); };
    const t = setTimeout(done, ms);
    try { ws = new WebSocket(url); } catch { return done(); }
    ws.onopen = () => ws.send(JSON.stringify(["REQ", "q", filter]));
    ws.onmessage = (m) => {
      let d: any;
      try { d = JSON.parse(String(m.data)); } catch { return; }
      if (d[0] === "EVENT" && d[2] && typeof d[2] === "object") { n++; onEvent(d[2]); }
      if (d[0] === "EOSE" || d[0] === "CLOSED") done();
    };
    ws.onerror = () => done();
  });
}

/** Pages one filter backwards with `until` (relays cap each REQ). */
async function queryAll(url: string, filter: object, onEvent: (e: Ev) => void, maxPages = 80, limit = 500): Promise<number> {
  const seen = new Set<string>();
  let until: number | undefined, total = 0;
  for (let i = 0; i < maxPages; i++) {
    let oldest = Infinity, fresh = 0, got = 0;
    await query(url, { ...filter, limit, ...(until ? { until } : {}) }, (e) => {
      got++;
      if (typeof e.created_at === "number" && e.created_at < oldest) oldest = e.created_at;
      if (seen.has(e.id)) return;
      seen.add(e.id); fresh++; onEvent(e);
    });
    total += fresh;
    if (!fresh || got < Math.min(20, limit) || !isFinite(oldest)) break;
    until = oldest; // inclusive, so an equal timestamp repeats once; `seen` drops it
  }
  return total;
}

// ------------------------------------------------------------------ events

const tag = (e: Ev, n: string) => e.tags.find((t) => t[0] === n)?.[1];
const tags = (e: Ev, n: string) => e.tags.filter((t) => t[0] === n);
const isHex = (s: unknown, len = 64) => typeof s === "string" && s.length === len && /^[0-9a-f]+$/.test(s);

function wellFormed(e: any): e is Ev {
  return e && isHex(e.id) && isHex(e.pubkey) && isHex(e.sig, 128) && Number.isInteger(e.created_at) && Number.isInteger(e.kind)
    && typeof e.content === "string" && Array.isArray(e.tags) && e.tags.every((t: any) => Array.isArray(t) && t.every((x: any) => typeof x === "string"))
    && e.created_at < NOW + 600;
}

/** NIP-01 id and signature check. Only events we keep are checked, so a relay can't plant an "official" app. */
function verify(e: Ev): boolean {
  try {
    const body = JSON.stringify([0, e.pubkey, e.created_at, e.kind, e.tags, e.content]);
    const id = new Bun.CryptoHasher("sha256").update(body).digest("hex");
    return id === e.id && schnorr.verify(e.sig, e.id, e.pubkey);
  } catch { return false; }
}

/** Newest event per replaceable address (kind:pubkey:d). */
class Latest {
  m = new Map<string, Ev>();
  add(e: Ev) {
    const k = addr(e);
    const old = this.m.get(k);
    if (!old || e.created_at > old.created_at || (e.created_at === old.created_at && e.id < old.id)) this.m.set(k, e);
  }
  get values() { return [...this.m.values()]; }
}
const addr = (e: Ev) => (e.kind >= 10000 && e.kind < 20000) ? `${e.kind}:${e.pubkey}` : `${e.kind}:${e.pubkey}:${tag(e, "d") ?? ""}`;

// ------------------------------------------------------------------ encodings

const CH = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
function polymod(v: number[]) {
  const g = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3];
  let c = 1;
  for (const x of v) { const b = c >>> 25; c = ((c & 0x1ffffff) << 5) ^ x; for (let i = 0; i < 5; i++) if ((b >>> i) & 1) c ^= g[i]; }
  return c >>> 0;
}
function npubOf(hex: string) {
  const out: number[] = [];
  let acc = 0, bits = 0;
  for (const b of hex.match(/../g)!.map((h) => parseInt(h, 16))) { acc = (acc << 8) | b; bits += 8; while (bits >= 5) { bits -= 5; out.push((acc >>> bits) & 31); } acc &= 0xff; }
  if (bits) out.push((acc << (5 - bits)) & 31);
  const hrp = "npub", hx = [...hrp].map((c) => c.charCodeAt(0) >> 5).concat(0, [...hrp].map((c) => c.charCodeAt(0) & 31));
  const p = polymod(hx.concat(out, [0, 0, 0, 0, 0, 0])) ^ 1;
  return hrp + "1" + out.concat([0, 1, 2, 3, 4, 5].map((i) => (p >>> (5 * (5 - i))) & 31)).map((x) => CH[x]).join("");
}
const B36 = "0123456789abcdefghijklmnopqrstuvwxyz";
function base36(hex: string) {
  let n = BigInt("0x" + hex), s = "";
  while (n > 0n) { s = B36[Number(n % 36n)] + s; n /= 36n; }
  return s.padStart(50, "0");
}
/** NIP-5A canonical gateway URL, or null when a named site's d can't be a host label. */
function gatewayUrl(e: Ev): string | null {
  if (e.kind === 15128) return `https://${npubOf(e.pubkey)}.${GATEWAY}/`;
  const d = tag(e, "d") ?? "";
  if (!/^[a-z0-9-]{1,13}$/.test(d) || d.endsWith("-")) return null;
  return `https://${base36(e.pubkey)}${d}.${GATEWAY}/`;
}

// ------------------------------------------------------------------ text

/** Text from strangers: no control or bidi characters, collapsed spaces, capped. */
function clean(s: unknown, max: number): string {
  if (typeof s !== "string") return "";
  const t = s.replace(/[\u0000-\u001f\u007f-\u009f​-‏‪-‮⁦-⁩﻿]/g, " ").replace(/\s+/g, " ").trim();
  return t.length > max ? t.slice(0, max - 1).trimEnd() + "…" : t;
}
const norm = (s: string) => s.toLowerCase().normalize("NFKD").replace(/[^a-z0-9]/g, "");

/** Public https URL (no localhost, LAN or bare IPs), else null. */
function publicUrl(s: unknown): URL | null {
  if (typeof s !== "string") return null;
  let t = s.trim().replace(/<bech32>.*$/, "");
  if (/^[a-z0-9-]+(\.[a-z0-9-]+)+(\/|$)/i.test(t)) t = "https://" + t; // "nips.pollerama.fun"
  let u: URL;
  try { u = new URL(t); } catch { return null; }
  if (u.protocol !== "https:" || u.username || u.password || u.port) return null;
  const h = u.hostname.toLowerCase();
  if (!h.includes(".") || h === "localhost" || h.endsWith(".local") || h.endsWith(".onion") || /^[0-9.]+$/.test(h) || h.includes(":")) return null;
  return u;
}
const hostOf = (u: URL) => u.hostname.toLowerCase().replace(/^www\./, "");
const imageUrl = (s: unknown) => { const u = publicUrl(s); return u ? clean(u.href, 400) : null; };

// SEO spam backstop: "Ethan Cooper | Une note sur Celsius" (the trust gate already removes these).
const SPAM_TITLE = /^[A-Z][a-z]+ [A-Z][a-z]+ \| /;

// ------------------------------------------------------------------ concurrency

async function pool<T>(items: T[], n: number, fn: (x: T) => Promise<void>) {
  let i = 0;
  await Promise.all(Array.from({ length: Math.min(n, items.length) }, async () => { while (i < items.length) await fn(items[i++]); }));
}

// ================================================================== build

const started = Date.now();

// 1. Trust graph: seed, its follows, their follows.
log("follow graph");
const lists = new Latest();
const contacts = (authors: string[]) => Promise.all(GRAPH_RELAYS.map((r) => query(r, { kinds: [3], authors }, (e) => { if (wellFormed(e) && authors.includes(e.pubkey)) lists.add(e); })));
await contacts([SEED]);
const seedList = lists.m.get(`3:${SEED}:`);
if (!seedList || !verify(seedList)) { log("no follow list for the seed account, giving up"); process.exit(2); }
const follows = [...new Set(tags(seedList, "p").map((t) => t[1]).filter((p) => isHex(p)))];
for (let i = 0; i < follows.length; i += 100) await contacts(follows.slice(i, i + 100));
/** pubkey -> how many of the seed's follows follow it */
const followedBy = new Map<string, number>();
let listsRead = 0;
for (const f of follows) {
  const l = lists.m.get(`3:${f}:`);
  if (!l || !verify(l)) continue;
  listsRead++;
  for (const p of new Set(tags(l, "p").map((t) => t[1]))) if (isHex(p)) followedBy.set(p, (followedBy.get(p) ?? 0) + 1);
}
const direct = new Set(follows);
log(`follows ${follows.length}, lists read ${listsRead}, second hop ${followedBy.size}`);

// nostr-builders: people who build Nostr things (trust seed and a small boost).
const builders = new Set<string>();
try {
  const d: any = await (await fetch(BUILDERS_URL, { signal: AbortSignal.timeout(30_000) })).json();
  for (const e of d.entries ?? []) if (isHex(e.pk)) builders.add(e.pk);
} catch (err) { log("nostr-builders unavailable", String(err)); }
log(`builders ${builders.size}`);

const trusted = (pk: string) => pk === SEED || direct.has(pk) || followedBy.has(pk) || builders.has(pk);

// 2. App events. Untrusted authors are dropped as they arrive.
log("app events");
const sites = new Latest(), handlers = new Latest();
const recs = new Map<string, Set<string>>(); // "31990:pk:d" -> trusted recommenders
const recEvents = new Latest();
const counts: Record<string, number> = {};
await Promise.all(APP_RELAYS.map(async (r) => {
  for (const kind of [15128, 35128, 31990, 31989]) {
    const n = await queryAll(r, { kinds: [kind] }, (e) => {
      if (!wellFormed(e) || e.kind !== kind) return;
      if (!trusted(e.pubkey)) return;
      if (kind === 15128 || kind === 35128) sites.add(e);
      else if (kind === 31990) handlers.add(e);
      else recEvents.add(e);
    });
    counts[`${r} ${kind}`] = n;
  }
}));
log(JSON.stringify(counts));

// Zapstore: read from relay.zapstore.dev itself, which accepts only publishers Zapstore lets in. Copies of 32267 on
// open relays are ignored. It refuses filters over limit 50 ("filters are too vague"), so this pages in 50s.
const zapApps = new Latest();
counts[`${ZAPSTORE} 32267`] = await queryAll(ZAPSTORE, { kinds: [32267] }, (e) => { if (wellFormed(e) && e.kind === 32267) zapApps.add(e); }, 200, 50);
log(`zapstore ${zapApps.m.size} apps by ${new Set(zapApps.values.map((e) => e.pubkey)).size} publishers`);
if (zapApps.m.size === 0) log("WARNING: relay.zapstore.dev returned nothing");

for (const e of recEvents.values) {
  if (!verify(e)) continue;
  for (const t of tags(e, "a")) if (t[1]?.startsWith("31990:")) {
    const s = recs.get(t[1]) ?? new Set<string>();
    s.add(e.pubkey); recs.set(t[1], s);
  }
}

// 3. Domain apps from Zapstore listings and NIP-89 handlers.
type App = {
  addr: string; kind: "web" | "nsite" | "android"; name: string; about: string; icon: string | null; url: string;
  site: string | null; gw: string | null; domain: string | null; pk: string; official: boolean; zapstore: string | null;
  recs: number; updated: number; copies: number; live: boolean | null; paths: number; x: string | null; ev?: Ev;
};
const isDvm = (e: Ev) => { const ks = tags(e, "k").map((t) => Number(t[1])); return ks.length > 0 && ks.every((k) => k >= 5000 && k < 7000); };
function handlerMeta(e: Ev): any { try { const m = JSON.parse(e.content); return m && typeof m === "object" ? m : {}; } catch { return {}; } }
function handlerWeb(e: Ev): URL | null {
  for (const t of tags(e, "web")) { const u = publicUrl(t[1]); if (u) return new URL(u.origin + "/"); }
  return null;
}

type Listing = { e: Ev; name: string; about: string; icon: string | null; host: string; origin: string; zap: string | null; recs: Set<string> };
const byDomain = new Map<string, Listing[]>();
const androidOnly: Listing[] = [];
// A publisher with dozens of listings is a catalog ("Zapstore Catalog" republishes ~900 general FOSS apps), not a
// Nostr developer. Their listings stay out.
const perPublisher = new Map<string, number>();
for (const e of zapApps.values) perPublisher.set(e.pubkey, (perPublisher.get(e.pubkey) ?? 0) + 1);
const CATALOG = 40;
log(`zapstore catalogs left out: ${[...perPublisher].filter(([, n]) => n > CATALOG).map(([p, n]) => `${p.slice(0, 8)} (${n})`).join(", ") || "none"}`);
for (const e of zapApps.values) {
  if ((perPublisher.get(e.pubkey) ?? 0) > CATALOG || !verify(e)) continue;
  const name = clean(tag(e, "name"), 60);
  if (!name) continue;
  const l: Listing = { e, name, about: clean(tag(e, "summary") || e.content, 200), icon: imageUrl(tag(e, "icon")), host: "", origin: "", zap: clean(tag(e, "d"), 120), recs: new Set() };
  const u = publicUrl(tag(e, "url"));
  if (u && !/(^|\.)(github\.com|gitlab\.com|codeberg\.org|play\.google\.com|apps\.apple\.com|f-droid\.org|zapstore\.dev)$/.test(hostOf(u))) {
    l.host = hostOf(u); l.origin = u.origin + "/";
    (byDomain.get(l.host) ?? byDomain.set(l.host, []).get(l.host)!).push(l);
  } else androidOnly.push(l);
}
for (const e of handlers.values) {
  if (isDvm(e)) continue;
  const m = handlerMeta(e);
  const name = clean(m.name || m.display_name, 60);
  const u = handlerWeb(e);
  if (!name || !u || !verify(e)) continue;
  const l: Listing = { e, name, about: clean(m.about, 200), icon: imageUrl(m.picture), host: hostOf(u), origin: u.origin + "/", zap: null, recs: recs.get(addr(e)) ?? new Set() };
  (byDomain.get(l.host) ?? byDomain.set(l.host, []).get(l.host)!).push(l);
}
log(`domains ${byDomain.size}, android-only listings ${androidOnly.length}`);

// 4. Who is official for each domain: /.well-known/nostr.json names the author.
const officialKeys = new Map<string, Set<string>>();
await pool([...byDomain.keys()], 8, async (host) => {
  const keys = new Set<string>();
  for (const q of ["", "?name=_"]) {
    try {
      const r = await fetch(`https://${host}/.well-known/nostr.json${q}`, { signal: AbortSignal.timeout(8000), redirect: "error" });
      if (!r.ok) continue;
      const text = await r.text();
      if (text.length > 256_000) continue;
      const names = JSON.parse(text)?.names;
      if (!names || typeof names !== "object") continue;
      const entries = Object.entries(names).filter(([, v]) => isHex(v)) as [string, string][];
      // A NIP-05 provider lists everyone; then only the domain's own "_" says who runs it.
      for (const [k, v] of entries) if (k === "_" || entries.length <= 20) keys.add(v);
      if (keys.size) break;
    } catch {}
  }
  officialKeys.set(host, keys);
});
log(`nostr.json: ${[...officialKeys.values()].filter((s) => s.size).length} of ${byDomain.size} domains name a key`);

const apps: App[] = [];
const appByOfficialKey = new Map<string, App[]>();
for (const [host, ls] of byDomain) {
  const keys = officialKeys.get(host) ?? new Set();
  const isOff = (l: Listing) => keys.has(l.e.pubkey);
  const officials = ls.filter(isOff);
  // Without an official listing, a domain needs a Zapstore listing or a trusted author with recommendations to stay.
  const pickFrom = officials.length ? officials : ls;
  const best = pickFrom.slice().sort((a, b) => (Number(!!b.zap) - Number(!!a.zap)) || (b.recs.size - a.recs.size) || (b.e.created_at - a.e.created_at))[0];
  const allRecs = new Set<string>();
  for (const l of pickFrom) for (const p of l.recs) allRecs.add(p);
  const app: App = {
    addr: addr(best.e), kind: "web", name: best.name, about: best.about || pickFrom.find((l) => l.about)?.about || "",
    icon: best.icon || pickFrom.find((l) => l.icon)?.icon || null, url: best.origin, site: null, gw: null, domain: host,
    pk: best.e.pubkey, official: officials.length > 0, zapstore: ls.find((l) => l.zap)?.zap ?? null, recs: allRecs.size,
    updated: Math.max(...ls.map((l) => l.e.created_at)), copies: 0, live: null, paths: 0, x: null,
  };
  apps.push(app);
  for (const k of keys) (appByOfficialKey.get(k) ?? appByOfficialKey.set(k, []).get(k)!).push(app);
}
for (const l of androidOnly) {
  // "YakiHonne" on Zapstore by the same key as the yakihonne.com app is that app's Android build, not a second result.
  const web = apps.find((a) => a.kind === "web" && a.pk === l.e.pubkey && norm(a.name) === norm(l.name));
  if (web) { web.zapstore ??= l.zap; web.icon ??= l.icon; continue; }
  apps.push({
    addr: addr(l.e), kind: "android", name: l.name, about: l.about, icon: l.icon, url: `https://zapstore.dev/apps/${encodeURIComponent(l.zap!)}`,
    site: null, gw: null, domain: null, pk: l.e.pubkey, official: false, zapstore: l.zap, recs: 0, updated: l.e.created_at,
    copies: 0, live: null, paths: 0, x: null,
  });
}

// 5. nsites: attach official ones to their app, the rest stand alone.
let unaddressable = 0, noIndex = 0, spam = 0, badSig = 0;
const nsites: App[] = [];
for (const e of sites.values) {
  const paths = tags(e, "path").filter((t) => t[1] && isHex(t[2]));
  const index = paths.find((t) => t[1] === "/index.html");
  if (!index) { noIndex++; continue; }
  const gw = gatewayUrl(e);
  if (!gw) { unaddressable++; continue; }
  const d = tag(e, "d") ?? "";
  const title = clean(tag(e, "title"), 60);
  if (SPAM_TITLE.test(title)) { spam++; continue; }
  if (!verify(e)) { badSig++; continue; }
  const name = title || d || "";
  const site = addr(e);
  // Linked to an app when the domain names this author and the names agree (or it's the author's root site).
  const owners = appByOfficialKey.get(e.pubkey) ?? [];
  const owner = owners.find((a) => { const an = norm(a.name); const n = norm(name), dn = norm(d); return an && (n === an || dn === an || (n && (n.includes(an) || an.includes(n)))); })
    ?? (e.kind === 15128 && owners.length === 1 ? owners[0] : undefined);
  const fav = paths.find((t) => /^\/(favicon\.(svg|png|ico)|apple-touch-icon\.png|icon\.(svg|png))$/.test(t[1]));
  const x = e.tags.find((t) => t[0] === "x" && t[2] === "aggregate" && isHex(t[1]))?.[1] ?? null;
  const ns: App = {
    addr: site, kind: "nsite", name: name || "", about: clean(tag(e, "description"), 200), icon: fav ? gw + fav[1].slice(1) : null,
    url: gw, site, gw, domain: null, pk: e.pubkey, official: !!owner, zapstore: null, recs: 0, updated: e.created_at, copies: 0,
    live: null, paths: paths.length, x, ev: e,
  };
  if (owner) {
    if (!owner.site || (owner.ev && owner.ev.created_at < e.created_at)) { owner.site = site; owner.gw = gw; owner.ev = e; owner.paths = paths.length; owner.x = x; }
    owner.official = true; // the domain names the key that publishes this site
    continue;
  }
  nsites.push(ns);
}
log(`nsites trusted ${sites.m.size}: kept ${nsites.length} standalone, no index.html ${noIndex}, d not addressable ${unaddressable}, spam titles ${spam}, bad signatures ${badSig}`);

// 6. Liveness: fetch each nsite's index.html from its Blossom servers and check the hash.
const state: { fails: Record<string, number> } = existsSync(STATE) ? JSON.parse(readFileSync(STATE, "utf8")) : { fails: {} };
const toCheck = [...nsites, ...apps.filter((a) => a.ev)];
await pool(toCheck, 6, async (a) => {
  const e = a.ev!;
  const want = tags(e, "path").find((t) => t[1] === "/index.html")![2];
  let ok = false;
  for (const t of tags(e, "server").slice(0, 4)) {
    const s = publicUrl(t[1]);
    if (!s) continue;
    try {
      const r = await fetch(`${s.origin}/${want}`, { signal: AbortSignal.timeout(8000) });
      if (!r.ok) continue;
      const buf = await r.arrayBuffer();
      if (buf.byteLength > 5_000_000) continue;
      if (new Bun.CryptoHasher("sha256").update(buf).digest("hex") === want) { ok = true; break; }
    } catch {}
  }
  a.live = ok;
  state.fails[a.site!] = ok ? 0 : (state.fails[a.site!] ?? 0) + 1;
});
log(`liveness: ${toCheck.filter((a) => a.live).length} of ${toCheck.length} served a matching index.html`);

// 7. Score, drop dead and copies, cap per author.
const net = (pk: string) => (pk === SEED || direct.has(pk) ? 25 : 0) + Math.min(followedBy.get(pk) ?? 0, 40);
function score(a: App): number {
  let s = 0;
  if (a.official) s += 40;
  if (a.kind === "web") s += 10;
  if (a.kind === "android") s -= 15; // Marble opens web apps; an Android-only app is a link to Zapstore
  if (a.zapstore) s += 10;
  s += Math.log2(1 + a.recs) * 8;
  s += Math.min(net(a.pk), 40) * 0.6;
  if (builders.has(a.pk)) s += 5;
  if (a.live) s += 5;
  if (NOW - a.updated < 90 * DAY) s += 3;
  if (a.kind === "nsite") { if (a.paths >= 3) s += 3; else s -= 10; if (!a.about) s -= 3; if (!a.name) s -= 20; }
  return Math.round(s * 10) / 10;
}
const dead = (a: App) => a.site && (state.fails[a.site] ?? 0) >= DEAD_AFTER;
for (const a of apps) if (a.site && dead(a)) { a.site = null; a.gw = null; } // the app still opens on its domain
let all = [...apps, ...nsites.filter((a) => !dead(a) && a.name)];
const sc = new Map(all.map((a) => [a, score(a)]));
all.sort((a, b) => sc.get(b)! - sc.get(a)!);

// Copies: an nsite with the same aggregate hash or name as a better entry is folded into it (counted, not listed).
const keepByName = new Map<string, App>(), keepByX = new Map<string, App>(), perAuthor = new Map<string, number>();
const out: App[] = [];
let folded = 0, capped = 0;
for (const a of all) {
  const n = norm(a.name);
  if (a.kind === "nsite") {
    const twin = (a.x && keepByX.get(a.x)) || (n && keepByName.get(n));
    if (twin) { twin.copies++; folded++; continue; }
    const c = perAuthor.get(a.pk) ?? 0;
    if (c >= PER_AUTHOR) { capped++; continue; }
    perAuthor.set(a.pk, c + 1);
  }
  if (n && !keepByName.has(n)) keepByName.set(n, a);
  if (a.x && !keepByX.has(a.x)) keepByX.set(a.x, a);
  out.push(a);
}
log(`apps ${out.length} (web ${out.filter((a) => a.kind === "web").length}, nsite ${out.filter((a) => a.kind === "nsite").length}, android ${out.filter((a) => a.kind === "android").length}); copies folded ${folded}, over author cap ${capped}`);

// 8. Author names for "nsite by …".
const profiles = new Latest();
const authors = [...new Set(out.map((a) => a.pk))];
for (let i = 0; i < authors.length; i += 150) {
  const chunk = authors.slice(i, i + 150);
  await Promise.all(["wss://purplepag.es", "wss://relay.primal.net"].map((r) => query(r, { kinds: [0], authors: chunk }, (e) => { if (wellFormed(e) && e.kind === 0 && chunk.includes(e.pubkey)) profiles.add(e); })));
}
const authorName = new Map<string, string>();
for (const e of profiles.values) {
  try { const m = JSON.parse(e.content); const n = clean(m.display_name || m.name, 40); if (n && verify(e)) authorName.set(e.pubkey, n); } catch {}
}

// 9. Write, unless the build looks gutted.
let previous = 0;
try { previous = JSON.parse(readFileSync(OUT, "utf8")).apps?.length ?? 0; } catch {}
if (out.length < MIN_APPS || out.length < previous * MIN_RATIO) {
  log(`REFUSING to publish ${out.length} apps (minimum ${MIN_APPS}, last build ${previous}); keeping the old file`);
  process.exit(3);
}
const doc = {
  v: 1,
  generated: new Date().toISOString(),
  gateway: GATEWAY,
  apps: out.map((a) => ({
    a: a.addr, kind: a.kind, name: a.name, about: a.about, icon: a.icon, url: a.domain ? a.url : (a.gw ?? a.url),
    domain: a.domain, site: a.site, gw: a.gw, pk: a.pk, by: authorName.get(a.pk) ?? null, official: a.official,
    zapstore: a.zapstore, recs: a.recs, follows: followedBy.get(a.pk) ?? 0, copies: a.copies, live: a.live,
    updated: a.updated, score: sc.get(a)!,
  })),
};
mkdirSync(dirname(OUT), { recursive: true });
writeFileSync(OUT + ".tmp", JSON.stringify(doc));
renameSync(OUT + ".tmp", OUT);
for (const k of Object.keys(state.fails)) if (!state.fails[k]) delete state.fails[k];
writeFileSync(STATE, JSON.stringify(state));
log(`wrote ${OUT}: ${out.length} apps, ${(JSON.stringify(doc).length / 1024).toFixed(0)} KB, ${((Date.now() - started) / 1000).toFixed(0)} s`);
process.exit(0);
