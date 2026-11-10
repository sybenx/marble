/**
 * Front for nostr-diamonds behind diamonds.syb.lol (Took's Cloudflare tunnel -> 127.0.0.1:7451).
 * - /diamonds.json: the public picks file nostr-diamonds writes after each cycle, served straight from disk,
 *   so it always answers even while diamonds' cycle blocks its own thread.
 * - /apps.json: Marble's app-search index, written once a day by H:\Programs\marble-apps-index (same folder).
 * - everything else (the /api JSON, login/seen/replies/translate, /about, /go, the read-only relay websocket):
 *   proxied to diamonds on 127.0.0.1:7449. The Cloudflare Worker on discover.syb.lol sends those here with
 *   X-Forwarded-Host: discover.syb.lol, which becomes the Host diamonds sees (NIP-98 login URL, NIP-42 relay tag,
 *   links), so it behaves exactly as when discover.syb.lol pointed at it directly.
 */
import { resolve } from "node:path";
const PORT = Number(process.env.FEED_PORT ?? 7451);
const UPSTREAM = process.env.DIAMONDS_UPSTREAM ?? "127.0.0.1:7449";
const FILES: Record<string, { file: string; maxAge: number; empty: string }> = {
  "/diamonds.json": { file: resolve(import.meta.dir, "public", "diamonds.json"), maxAge: 60, empty: '{"notes":[],"profiles":[],"meta":{}}' },
  "/apps.json": { file: resolve(import.meta.dir, "public", "apps.json"), maxAge: 3600, empty: '{"v":1,"apps":[]}' },
};
const cors = { "access-control-allow-origin": "*", "access-control-allow-methods": "GET, HEAD, OPTIONS" };
const HOP = ["connection", "keep-alive", "transfer-encoding", "upgrade", "proxy-connection", "te", "trailer", "host", "x-forwarded-host", "content-length"];

function upstreamHeaders(req: Request): Headers {
  const h = new Headers();
  req.headers.forEach((v, k) => { if (!HOP.includes(k.toLowerCase()) && !k.toLowerCase().startsWith("sec-websocket")) h.set(k, v); });
  h.set("host", req.headers.get("x-forwarded-host") || req.headers.get("host") || "discover.syb.lol");
  return h;
}

type WsData = { url: string; headers: Record<string, string>; up?: WebSocket; queue: (string | ArrayBufferLike)[] };

Bun.serve<WsData, {}>({
  hostname: "127.0.0.1",
  port: PORT,
  idleTimeout: 120, // diamonds can take a while mid-cycle; Bun's default 10 s cut those requests off
  async fetch(req, server) {
    const url = new URL(req.url);
    const served = FILES[url.pathname];
    if (served) {
      if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
      const f = Bun.file(served.file);
      if (!(await f.exists())) return new Response(served.empty, { status: 503, headers: { ...cors, "content-type": "application/json" } });
      return new Response(f, { headers: { ...cors, "content-type": "application/json; charset=utf-8", "cache-control": `public, max-age=${served.maxAge}` } });
    }
    if ((req.headers.get("upgrade") ?? "").toLowerCase() === "websocket") {
      const headers: Record<string, string> = {};
      upstreamHeaders(req).forEach((v, k) => { headers[k] = v; });
      if (server.upgrade(req, { data: { url: `ws://${UPSTREAM}${url.pathname}${url.search}`, headers, queue: [] } })) return;
      return new Response("websocket upgrade failed", { status: 400 });
    }
    try {
      const r = await fetch(`http://${UPSTREAM}${url.pathname}${url.search}`, {
        method: req.method, headers: upstreamHeaders(req),
        body: req.method === "GET" || req.method === "HEAD" ? undefined : await req.arrayBuffer(),
        redirect: "manual", signal: AbortSignal.timeout(60_000),
      });
      const h = new Headers(r.headers);
      h.delete("content-encoding"); h.delete("content-length");
      return new Response(r.body, { status: r.status, headers: h });
    } catch (e) {
      return new Response("diamonds is busy or down, try again in a moment", { status: 502, headers: { "cache-control": "no-store" } });
    }
  },
  websocket: {
    open(ws) {
      const d = ws.data;
      const up = new WebSocket(d.url, { headers: d.headers } as any);
      d.up = up;
      up.onopen = () => { for (const m of d.queue.splice(0)) up.send(m); };
      up.onmessage = (m) => { try { ws.send(m.data as any); } catch {} };
      up.onclose = () => { try { ws.close(); } catch {} };
      up.onerror = () => { try { ws.close(); } catch {} };
    },
    message(ws, msg) {
      const up = ws.data.up;
      if (up && up.readyState === WebSocket.OPEN) up.send(msg as any); else ws.data.queue.push(msg as any);
    },
    close(ws) { try { ws.data.up?.close(); } catch {} },
  },
});
console.log(JSON.stringify({ t: new Date().toISOString(), msg: "serving", url: `http://127.0.0.1:${PORT}`, files: Object.values(FILES).map((f) => f.file), upstream: UPSTREAM }));
