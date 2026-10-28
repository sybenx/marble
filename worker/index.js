// marble.syb.lol on Cloudflare: the site's files (site/), and a shared link to a post or a person
// (marble.syb.lol/nevent1…, /note1…, /naddr1…, /npub1…, /nprofile1…, and the older /e/…, /p/…, /a/…) answered with
// that post already in the page: drawn, so it shows before any script runs; with its title, text and picture for the
// previews chat apps show; and handed to the page's script (#seed), which takes over without asking again.
// Fetched from fast relays (and the ones the link names), checked, and kept at the edge for a while.
import { decode, npub, note, toHex, fromHex } from "../site/js/ids.js";
import { schnorr, sha256 } from "../site/js/vendor/noble.js";

const RELAYS = ["wss://relay.ditto.pub", "wss://syb.lol", "wss://relay.primal.net", "wss://relay.damus.io"];
const SHARED = /^\/(?:(e|p|a)\/)?((?:note|nevent|npub|nprofile|naddr)1[02-9ac-hj-np-z]{6,})\/?$/;
const BUDGET = 2000; // ms for everything asked of the relays; after that the page asks for itself
const SITE = "https://marble.syb.lol";

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (url.pathname === "/.well-known/assetlinks.json") return assetLinks(env);
    const m = SHARED.exec(url.pathname);
    const d = m && decode(m[2]);
    if (!d || !["GET", "HEAD"].includes(request.method)) return env.ASSETS.fetch(request);
    // the older forms go to the short one, the address to share
    if (m[1]) return Response.redirect(`${url.origin}/${m[2]}${url.search}`, 301);

    const [page, seed] = await Promise.all([
      env.ASSETS.fetch(new Request(new URL("/index.html", url))).then((r) => r.text()),
      seedFor(m[2], d, env, ctx),
    ]);
    return new Response(request.method === "HEAD" ? null : withPost(page, m[2], d, seed), {
      headers: { "content-type": "text/html; charset=utf-8", "cache-control": "public, max-age=60", "x-marble-seed": String(seed.events.length) },
    });
  },
};

// Android opens these links in Marble when it's installed (App Links): the app's signing certificates, from
// ANDROID_CERTS ("AA:BB:…", comma-separated).
function assetLinks(env) {
  const certs = String(env.ANDROID_CERTS || "").split(",").map((s) => s.trim()).filter(Boolean);
  if (!certs.length) return new Response("not set up", { status: 404 });
  const body = [{ relation: ["delegate_permission/common.handle_all_urls"], target: { namespace: "android_app", package_name: "cc.aaro.cosign", sha256_cert_fingerprints: certs } }];
  return new Response(JSON.stringify(body), { headers: { "content-type": "application/json", "cache-control": "public, max-age=3600" } });
}

// ── What the link names, from the relays (kept at the edge) ──────────────────────────────────────────

async function seedFor(entity, d, env, ctx) {
  const key = new Request(`${SITE}/__seed/${entity}`);
  const cache = caches.default;
  const hit = await cache.match(key).catch(() => null);
  if (hit) return hit.json();
  const seed = await fetchSeed(d, env).catch(() => ({ events: [] }));
  // a post never changes; a profile or an article can: kept less long; nothing found: asked again soon
  const main = seed.events.find((e) => e.id === seed.main);
  const ttl = !main ? 30 : d.type === "event" ? 86400 : 600;
  ctx.waitUntil(cache.put(key, new Response(JSON.stringify(seed), { headers: { "cache-control": `public, max-age=${ttl}` } })).catch(() => {}));
  return seed;
}

async function fetchSeed(d, env) {
  const urls = [...new Set([...(env.RELAYS ? env.RELAYS.split(",") : RELAYS), ...(d.relays || []).filter((u) => /^wss:\/\//.test(u)).slice(0, 3)])];
  const relays = new Relays(urls, Date.now() + BUDGET);
  try {
    let main = null;
    if (d.type === "event") {
      // the post, and its author's profile at the same time when the link says who wrote it
      const post = (evs) => evs.some((e) => e.id === d.id);
      const both = (evs) => post(evs) && evs.some((e) => e.kind === 0 && e.pubkey === d.pubkey);
      const got = await relays.ask([{ ids: [d.id] }, ...(d.pubkey ? [{ kinds: [0], authors: [d.pubkey] }] : [])], d.pubkey ? both : post, post, 250);
      main = got.find((e) => e.id === d.id) || null;
    } else if (d.type === "address") {
      const piece = (evs) => evs.some((e) => e.kind === d.kind);
      // (replaceable: a moment for a newer copy from another relay)
      const got = await relays.ask([{ kinds: [d.kind], authors: [d.pubkey], "#d": [d.identifier] }, { kinds: [0], authors: [d.pubkey] }], () => false, piece, 300);
      main = got.filter((e) => e.kind === d.kind).sort((a, b) => b.created_at - a.created_at)[0] || null;
    } else {
      const got = await relays.ask([{ kinds: [0], authors: [d.pubkey] }], () => false, (evs) => evs.length > 0, 300);
      main = newest(got.filter((e) => e.kind === 0 && e.pubkey === d.pubkey));
    }
    // then, together: the author's profile (if not here yet) and any posts this one quotes
    if (main && main.kind !== 0) {
      const quoted = quotesOf(main).slice(0, 2);
      const needProfile = !relays.got.some((e) => e.kind === 0 && e.pubkey === main.pubkey);
      const filters = [...(needProfile ? [{ kinds: [0], authors: [main.pubkey] }] : []), ...(quoted.length ? [{ ids: quoted }] : [])];
      const all = (evs) => (!needProfile || evs.some((e) => e.kind === 0 && e.pubkey === main.pubkey)) && quoted.every((id) => evs.some((e) => e.id === id));
      if (filters.length) await relays.ask(filters, all);
    }
    const profilesOf = new Map();
    for (const e of relays.got) if (e.kind === 0 && (!profilesOf.has(e.pubkey) || profilesOf.get(e.pubkey).created_at < e.created_at)) profilesOf.set(e.pubkey, e);
    const events = [...relays.got.filter((e) => e.kind !== 0), ...profilesOf.values()];
    return { main: main?.id || null, events };
  } finally { relays.close(); }
}

const newest = (evs) => evs.sort((a, b) => b.created_at - a.created_at)[0] || null;
function quotesOf(e) {
  const ids = (e.tags || []).filter((t) => t[0] === "q" && /^[0-9a-f]{64}$/.test(t[1])).map((t) => t[1]);
  for (const m of String(e.content || "").matchAll(/nostr:((?:note|nevent)1[02-9ac-hj-np-z]+)/gi)) {
    const d = decode(m[1]);
    if (d?.id) ids.push(d.id);
  }
  return [...new Set(ids)].filter((id) => id !== e.id);
}

// A few relays at once over WebSockets, for one link: ask() sends filters to all of them and resolves as soon as
// done(events) holds, `grace` ms after enough(events) first holds (time for a late profile, or a newer copy),
// once every relay has finished, or when time is up.
class Relays {
  constructor(urls, deadline) {
    this.deadline = deadline;
    this.got = [];
    this.seen = new Set();
    this.sockets = urls.map((u) => connect(u).catch(() => null));
    this.n = 0;
  }
  async ask(filters, done, enough = done, grace = 0) {
    const sockets = (await Promise.all(this.sockets)).filter(Boolean);
    const id = `m${this.n++}`;
    return new Promise((resolve) => {
      let open = sockets.length, timer = null;
      const finish = () => { clearTimeout(timer); clearTimeout(cap); for (const ws of sockets) { try { ws.send(JSON.stringify(["CLOSE", id])); } catch {} ws.removeEventListener("message", ws.__on); } resolve(this.got); };
      const cap = setTimeout(finish, Math.max(0, this.deadline - Date.now()));
      const check = () => { if (done(this.got)) finish(); else if (enough(this.got) && !timer) timer = setTimeout(finish, grace); };
      if (!open) return finish();
      for (const ws of sockets) {
        ws.__on = (m) => {
          let msg;
          try { msg = JSON.parse(m.data); } catch { return; }
          if (msg[1] !== id) return;
          if (msg[0] === "EVENT" && msg[2] && !this.seen.has(msg[2].id) && verify(msg[2])) { this.seen.add(msg[2].id); this.got.push(msg[2]); check(); }
          else if (msg[0] === "EOSE" || msg[0] === "CLOSED") { if (--open === 0) finish(); }
        };
        ws.addEventListener("message", ws.__on);
        ws.send(JSON.stringify(["REQ", id, ...filters]));
      }
      check();
    });
  }
  async close() { for (const ws of await Promise.all(this.sockets)) try { ws?.close(); } catch {} }
}

async function connect(url) {
  const r = await fetch(url.replace(/^ws/, "http"), { headers: { Upgrade: "websocket" }, signal: AbortSignal.timeout(BUDGET) });
  const ws = r.webSocket;
  if (!ws) throw new Error("no websocket");
  ws.accept();
  return ws;
}

const utf8 = new TextEncoder();
function verify(e) {
  try {
    if (typeof e.id !== "string" || typeof e.sig !== "string" || typeof e.content !== "string" || !Array.isArray(e.tags)) return false;
    const id = toHex(sha256(utf8.encode(JSON.stringify([0, e.pubkey, e.created_at, e.kind, e.tags, e.content]))));
    return id === e.id && schnorr.verify(fromHex(e.sig), fromHex(e.id), fromHex(e.pubkey));
  } catch { return false; }
}

// ── The page, with the post in it ────────────────────────────────────────────────────────────────────

const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
const text = (x) => (typeof x === "string" ? x : "");
const https = (u) => /^https:\/\/[^\s"'<>]+$/.test(text(u));
const IMAGE = /\.(?:jpe?g|png|gif|webp|avif)(?:\?\S*)?$/i;
const profileOf = (seed, pk) => {
  const e = seed.events.find((x) => x.kind === 0 && x.pubkey === pk);
  try { return e ? JSON.parse(e.content) : {}; } catch { return {}; }
};
const nameOf = (p, pk) => text(p.display_name).trim() || text(p.name).trim() || `${npub(pk).slice(0, 12)}…`;
// in a preview, someone without a name is just "Marble" (not their code)
const titleOf = (p) => text(p.display_name).trim() || text(p.name).trim() || "Marble";

function withPost(page, entity, d, seed) {
  const main = seed.events.find((e) => e.id === seed.main);
  const share = `${SITE}/${entity}`;
  let meta, body = "";
  if (main && main.kind === 0) {
    const p = profileOf(seed, main.pubkey), name = nameOf(p, main.pubkey);
    meta = { title: titleOf(p), description: plain(text(p.about)).slice(0, 200) || "On Marble", image: https(p.picture) ? p.picture : null, large: false };
    body = `<div class="ssr profile"><div class="who">${face(p, main.pubkey, 64)}<div class="who-text"><b class="who-name">${esc(name)}</b>${
      text(p.nip05) ? `<span class="who-sub">${esc(text(p.nip05).replace(/^_@/, ""))}</span>` : ""}</div></div>${text(p.about) ? `<p class="text">${esc(text(p.about).slice(0, 600))}</p>` : ""}</div>`;
  } else if (main) {
    const p = profileOf(seed, main.pubkey), name = nameOf(p, main.pubkey);
    const title = main.kind === 30023 ? text((main.tags.find((t) => t[0] === "title") || [])[1]) : "";
    const summary = main.kind === 30023 ? text((main.tags.find((t) => t[0] === "summary") || [])[1]) : "";
    const pics = imagesOf(main);
    const cover = main.kind === 30023 ? text((main.tags.find((t) => t[0] === "image") || [])[1]) : "";
    const still = previewOf(main);
    const image = https(cover) ? cover : still || (https(p.picture) ? p.picture : null);
    meta = { title: title || titleOf(p), description: (summary || plain(main.content)).slice(0, 200) || "A post on Marble", image, large: !!(https(cover) || still) };
    const quoted = quotesOf(main).map((id) => seed.events.find((e) => e.id === id)).filter(Boolean);
    body = `<div class="thread ssr"><article class="card main">
      <div class="who"><a href="/${npub(main.pubkey)}" class="who-av">${face(p, main.pubkey, 48)}</a><div class="who-text"><a href="/${npub(main.pubkey)}" class="who-name">${esc(name)}</a>
        <span class="who-sub"><time datetime="${new Date(main.created_at * 1000).toISOString()}">${esc(new Date(main.created_at * 1000).toUTCString().slice(5, 16))}</time></span></div></div>
      ${title ? `<h1>${esc(title)}</h1>${summary ? `<p class="text">${esc(summary)}</p>` : ""}` : `<div class="text big">${paragraphs(main)}</div>`}
      ${quoted.map((q) => { const qp = profileOf(seed, q.pubkey); return `<a class="quote" href="/${note(q.id)}"><b>${esc(nameOf(qp, q.pubkey))}</b> ${esc(plain(q.content).slice(0, 280))}</a>`; }).join("")}
    </article></div>`;
  } else {
    meta = null; // not found in time: the page as it is, which looks for itself
  }
  let html = page;
  if (meta) {
    const image = meta.image || `${SITE}/og.jpg`;
    const tags = [
      ["og:type", d.type === "profile" ? "profile" : "article"], ["og:site_name", "Marble"], ["og:title", meta.title],
      ["og:description", meta.description], ["og:url", share], ["og:image", image],
    ].map(([k, v]) => `<meta property="${k}" content="${esc(v)}">`).join("\n")
      + `\n<meta name="twitter:card" content="${meta.image && meta.large ? "summary_large_image" : "summary"}">`;
    html = html
      .replace(/<meta property="og:[^>]*>\n?/g, "").replace(/<meta name="twitter:[^>]*>\n?/g, "")
      .replace(/<title>[^<]*<\/title>/, () => `<title>${esc(meta.title)} · Marble</title>`)
      .replace(/<meta name="description"[^>]*>/, () => `<meta name="description" content="${esc(meta.description)}">\n${tags}`)
      .replace(/<link rel="canonical"[^>]*>/, () => `<link rel="canonical" href="${esc(share)}">`);
  }
  const json = JSON.stringify(seed.events).replace(/</g, "\\u003c").replace(/\u2028/g, "\\u2028").replace(/\u2029/g, "\\u2029");
  return html
    // the post's layout from the start, not only once the page's first script says so
    .replace('<body data-page="home">', '<body data-page="view">')
    // (functions, so a "$&" in someone's post is just text)
    .replace('<main id="view" aria-live="polite"></main>', () => `<main id="view" aria-live="polite">${body}</main>`)
    .replace('<script type="module" src="/js/app.js"></script>', () => `<script type="application/json" id="seed">${json}</script>\n<script type="module" src="/js/app.js"></script>`);
}

function face(p, pk, size) {
  const name = nameOf(p, pk), letter = esc([...name.replace(/^npub1/, "")][0]?.toUpperCase() || "?");
  return `<span class="av" style="--s:${size}px" aria-hidden="true">${https(p.picture) ? `<img src="${esc(p.picture)}" alt="" referrerpolicy="no-referrer" onerror="this.remove()">` : ""}<b>${letter}</b></span>`;
}
function imagesOf(e) {
  const urls = (e.tags || []).filter((t) => t[0] === "imeta").map((t) => (t.find((x) => /^url /.test(x)) || "").slice(4));
  for (const m of String(e.content || "").matchAll(/https:\/\/[^\s<>"']+/g)) if (IMAGE.test(m[0])) urls.push(m[0]);
  return [...new Set(urls.filter(https))];
}
// The picture for a preview: chat apps show a GIF's first frame, which is often blank (animations that draw as they
// go start white), so a post's still picture first, then a GIF's own still (its imeta "image"), and a GIF only last.
const GIF = /\.gif(?:\?\S*)?$/i;
function previewOf(e) {
  const metas = (e.tags || []).filter((t) => t[0] === "imeta").map((t) => Object.fromEntries(t.slice(1).map((x) => [x.split(" ")[0], x.slice(x.indexOf(" ") + 1)])));
  const gif = (m) => /gif/i.test(m.m || "") || GIF.test(m.url || "");
  const pics = imagesOf(e);
  return pics.find((u) => !GIF.test(u) && !metas.some((m) => m.url === u && gif(m)))
    || metas.filter(gif).map((m) => m.image).find(https)
    || pics[0] || null;
}
// The text for a preview: no links to other posts or people, no addresses.
const plain = (s) => text(s).replace(/nostr:\S+/g, "").replace(/https?:\/\/\S+/g, "").replace(/\s+/g, " ").trim();
// The post's text, as the page will show it (roughly): pictures as pictures, links as links, mentions of people and
// posts without the code they're written in.
function paragraphs(e) {
  const pics = new Set(imagesOf(e));
  const out = esc(text(e.content).replace(/nostr:(?:note|nevent)1[02-9ac-hj-np-z]+/gi, "").trim())
    .replace(/(?:nostr:)?((?:npub|nprofile)1[02-9ac-hj-np-z]{6,})/g, (_, ent) => { const x = decode(ent); return x?.pubkey ? `<a href="/${npub(x.pubkey)}">@${npub(x.pubkey).slice(5, 13)}</a>` : ""; })
    .replace(/https:\/\/[^\s<>"']+/g, (u) => {
      const raw = u.replace(/&amp;/g, "&");
      return pics.has(raw) ? `<img class="ssr-pic" src="${u}" alt="" loading="lazy" referrerpolicy="no-referrer">` : `<a href="${u}" rel="noopener nofollow">${esc(raw.replace(/^https:\/\/(www\.)?/, "").slice(0, 40))}</a>`;
    });
  return out.split(/\n{2,}/).map((p) => `<p>${p.replace(/\n/g, "<br>")}</p>`).join("");
}
