// A real network for the vault code in node (tests and seeding): Nostr relays over WebSocket, Blossom over HTTP.
// Only for local test servers (Android's tools/vaults-test-server.ts).
import * as C from "../site/js/vault/crypto.js";

function talk(url, msgs, until, timeout = 5000) {
  return new Promise((resolve) => {
    const ws = new WebSocket(url), out = [];
    const done = () => { try { ws.close(); } catch {} resolve(out); };
    const t = setTimeout(done, timeout);
    ws.onopen = () => msgs.forEach((m) => ws.send(JSON.stringify(m)));
    ws.onmessage = (m) => { const x = JSON.parse(m.data); out.push(x); if (until(x, out)) { clearTimeout(t); done(); } };
    ws.onerror = () => { clearTimeout(t); done(); };
  });
}
export const realNet = {
  async req(urls, filter) {
    const evs = new Map(); let answered = 0;
    for (const u of urls) {
      const got = await talk(u, [["REQ", "q", ...(Array.isArray(filter) ? filter : [filter])]], (x) => x[0] === "EOSE");
      if (got.some((x) => x[0] === "EOSE")) answered++;
      for (const x of got) if (x[0] === "EVENT" && C.eventOK(x[2])) evs.set(x[2].id, x[2]);
    }
    return Object.assign([...evs.values()], { answered, answeredBy: new Set() });
  },
  async publish(urls, e) {
    const ok = [], failed = [];
    for (const u of urls) { const got = await talk(u, [["EVENT", e]], (x) => x[0] === "OK"); (got.find((x) => x[0] === "OK")?.[2] ? ok.push(u) : failed.push([u, "no"])); }
    return { ok, failed };
  },
  async get(servers, sha) {
    for (const s of servers) { try { const r = await fetch(`${s}/${sha}`); if (r.ok) return new Uint8Array(await r.arrayBuffer()); } catch {} }
    return null;
  },
  async put(server, bytes, auth) {
    try { const r = await fetch(`${server}/upload`, { method: "PUT", body: bytes, headers: { "Content-Type": "application/octet-stream", Authorization: "Nostr " + btoa(JSON.stringify(auth)) } }); return r.ok; } catch { return false; }
  },
};
