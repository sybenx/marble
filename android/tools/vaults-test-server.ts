// A Nostr relay and a Blossom server in one, for vaults tests (Android and Web): in memory, never for the internet.
//
//   bun android/tools/vaults-test-server.ts [port]        (default 7447: ws://127.0.0.1:7447 and http://127.0.0.1:7447)
//
// Relay: NIP-01 (EVENT, REQ, CLOSE, EOSE, OK), replaceable and addressable kinds keep only the newest event at each
// address (a tie: the lower id), ephemeral kinds are delivered and not kept. Event ids are checked; signatures are not
// (vault clients check them). Blossom: BUD-01 GET and HEAD /<sha256>, BUD-02 PUT /upload, BUD-04 PUT /mirror, BUD-12
// DELETE /<sha256>, BUD-02 GET /list/<pubkey>. It keeps a blob per owner (spec §6.4): a DELETE drops only the signer's
// claim, and the bytes go when nobody claims them. A second port can be started for a second server.
//
// State is kept in $TMPDIR/vaults-test-<port>.json (or the third argument), written a moment after each change, and read
// back at start, so a restart loses nothing; POST /_test {"reset":true} empties it. Every HTTP answer carries CORS
// (BUD-01), so pages in a browser can use it.
//
// Misbehaviour for tests, by HTTP: POST /_test {"alterBlobs": true} serves altered bytes; {"dropHalf": true} drops half
// of the blobs; {"reset": true} forgets everything.

const port = Number(process.argv[2] ?? 7447);
const enc = new TextEncoder();
const hex = (b: ArrayBuffer | Uint8Array) => [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, '0')).join('');
const sha256 = async (b: Uint8Array) => hex(await crypto.subtle.digest('SHA-256', b));

type Ev = { id: string; pubkey: string; created_at: number; kind: number; tags: string[][]; content: string; sig: string };
let events = new Map<string, Ev>(); // by id
let blobs = new Map<string, { bytes: Uint8Array; owners: Set<string>; uploaded: number }>();
let behaviour: Record<string, boolean> = {};
const statePath = process.argv[3] ?? `${require('node:os').tmpdir()}/vaults-test-${port}.json`;
const fs = require('node:fs');
try {
  const saved = JSON.parse(fs.readFileSync(statePath, 'utf8'));
  for (const e of saved.events ?? []) events.set(e.id, e);
  for (const [sha, b] of Object.entries<any>(saved.blobs ?? {})) blobs.set(sha, { bytes: Uint8Array.from(Buffer.from(b.b64, 'base64')), owners: new Set(b.owners), uploaded: b.uploaded });
} catch {}
let saving: any = null;
function changed() {
  if (saving) return;
  saving = setTimeout(() => {
    saving = null;
    const out = { events: [...events.values()], blobs: Object.fromEntries([...blobs].map(([s, b]) => [s, { b64: Buffer.from(b.bytes).toString('base64'), owners: [...b.owners], uploaded: b.uploaded }])) };
    fs.writeFileSync(statePath + '.tmp', JSON.stringify(out)); fs.renameSync(statePath + '.tmp', statePath);
  }, 300);
}
const CORS = { 'access-control-allow-origin': '*', 'access-control-allow-methods': 'GET, HEAD, PUT, DELETE', 'access-control-allow-headers': 'Authorization, Content-Type, X-SHA-256, X-Content-Length, X-Content-Type', 'access-control-max-age': '86400' };
const subs = new Map<any, Map<string, any[]>>();

const addressOf = (e: Ev) => {
  if (e.kind === 0 || e.kind === 3 || (e.kind >= 10000 && e.kind < 20000)) return `${e.kind}:${e.pubkey}`;
  if (e.kind >= 30000 && e.kind < 40000) return `${e.kind}:${e.pubkey}:${e.tags.find((t) => t[0] === 'd')?.[1] ?? ''}`;
  return null;
};
function matches(f: any, e: Ev) {
  if (f.ids && !f.ids.includes(e.id)) return false;
  if (f.authors && !f.authors.includes(e.pubkey)) return false;
  if (f.kinds && !f.kinds.includes(e.kind)) return false;
  if (f.since && e.created_at < f.since) return false;
  if (f.until && e.created_at > f.until) return false;
  for (const k of Object.keys(f)) if (k.startsWith('#')) { const vals = f[k]; if (!e.tags.some((t) => t[0] === k.slice(1) && vals.includes(t[1]))) return false; }
  return true;
}
async function idOK(e: Ev) {
  try { return (await sha256(enc.encode(JSON.stringify([0, e.pubkey, e.created_at, e.kind, e.tags, e.content])))) === e.id; } catch { return false; }
}
async function accept(e: Ev): Promise<[boolean, string]> {
  if (!(await idOK(e))) return [false, 'invalid: bad id'];
  if (events.has(e.id)) return [true, 'duplicate:'];
  const ephemeral = e.kind >= 20000 && e.kind < 30000;
  if (!ephemeral) {
    const a = addressOf(e);
    if (a) {
      for (const [id, o] of events) if (addressOf(o) === a) {
        if (o.created_at > e.created_at || (o.created_at === e.created_at && o.id < e.id)) return [true, 'duplicate: have newer'];
        events.delete(id);
      }
    }
    events.set(e.id, e);
    changed();
  }
  for (const [ws, m] of subs) for (const [sid, fs] of m) if (fs.some((f) => matches(f, e))) ws.send(JSON.stringify(['EVENT', sid, e]));
  return [true, ''];
}

function authOf(req: Request, verb: string): { pubkey: string; xs: string[] } | null {
  const h = req.headers.get('authorization') ?? '';
  if (!h.startsWith('Nostr ')) return null;
  try {
    const e = JSON.parse(atob(h.slice(6))) as Ev;
    if (e.kind !== 24242 || !e.tags.some((t) => t[0] === 't' && t[1] === verb)) return null;
    const exp = Number(e.tags.find((t) => t[0] === 'expiration')?.[1] ?? 0);
    if (exp < Date.now() / 1000) return null;
    return { pubkey: e.pubkey, xs: e.tags.filter((t) => t[0] === 'x').map((t) => t[1]) };
  } catch { return null; }
}
const descriptor = (sha: string, b: Uint8Array, at: number) => ({ url: `http://127.0.0.1:${port}/${sha}`, sha256: sha, size: b.length, type: 'application/octet-stream', uploaded: at });

async function answer(req: Request): Promise<Response> {
  {
    const url = new URL(req.url);
    const path = url.pathname.slice(1);
    if (req.method === 'POST' && path === '_test') {
      const b = await req.json();
      if (b.reset) { events = new Map(); blobs = new Map(); behaviour = {}; }
      if (b.dropHalf) { let i = 0; for (const k of [...blobs.keys()]) if (i++ % 2 === 0) blobs.delete(k); }
      for (const k of ['alterBlobs']) if (k in b) behaviour[k] = !!b[k];
      return Response.json({ ok: true, events: events.size, blobs: blobs.size });
    }
    if ((req.method === 'PUT') && (path === 'upload' || path === 'mirror')) {
      const a = authOf(req, 'upload');
      if (!a) return new Response('auth', { status: 401 });
      let bytes: Uint8Array;
      if (path === 'mirror') {
        const src = (await req.json()).url as string;
        const r = await fetch(src); if (!r.ok) return new Response('fetch', { status: 502 });
        bytes = new Uint8Array(await r.arrayBuffer());
      } else bytes = new Uint8Array(await req.arrayBuffer());
      const sha = await sha256(bytes);
      if (a.xs.length && !a.xs.includes(sha)) return new Response('x', { status: 403 });
      const have = blobs.get(sha) ?? { bytes, owners: new Set<string>(), uploaded: Math.floor(Date.now() / 1000) };
      have.owners.add(a.pubkey); blobs.set(sha, have);
      return Response.json(descriptor(sha, bytes, have.uploaded));
    }
    if (req.method === 'GET' && path.startsWith('list/')) {
      const pk = path.slice(5);
      return Response.json([...blobs].filter(([, b]) => b.owners.has(pk)).map(([s, b]) => descriptor(s, b.bytes, b.uploaded)));
    }
    const sha = path.split('.')[0];
    if (/^[0-9a-f]{64}$/.test(sha)) {
      const b = blobs.get(sha);
      if (req.method === 'DELETE') {
        const a = authOf(req, 'delete');
        if (!a || (a.xs.length && !a.xs.includes(sha))) return new Response('auth', { status: 401 });
        if (b) { b.owners.delete(a.pubkey); if (b.owners.size === 0) blobs.delete(sha); }
        return new Response('ok');
      }
      if (!b) return new Response('not found', { status: 404 });
      if (req.method === 'HEAD') return new Response(null, { headers: { 'content-length': String(b.bytes.length) } });
      let bytes = b.bytes;
      if (behaviour.alterBlobs) { bytes = bytes.slice(); bytes[0] ^= 1; }
      return new Response(bytes, { headers: { 'content-type': 'application/octet-stream' } });
    }
    return new Response('vaults test server', { status: 200 });
  }
}
const WS: any = {
    open(ws) { subs.set(ws, new Map()); },
    close(ws) { subs.delete(ws); },
    async message(ws, raw) {
      let m: any; try { m = JSON.parse(String(raw)); } catch { return; }
      if (m[0] === 'EVENT') { const [ok, msg] = await accept(m[1]); ws.send(JSON.stringify(['OK', m[1]?.id, ok, msg])); }
      else if (m[0] === 'REQ') {
        const [, sid, ...filters] = m;
        subs.get(ws)!.set(sid, filters);
        const out = new Map<string, Ev>();
        for (const f of filters) {
          const hit = [...events.values()].filter((e) => matches(f, e)).sort((a, b) => b.created_at - a.created_at || (a.id < b.id ? -1 : 1));
          for (const e of hit.slice(0, f.limit ?? 5000)) out.set(e.id, e);
        }
        for (const e of out.values()) ws.send(JSON.stringify(['EVENT', sid, e]));
        ws.send(JSON.stringify(['EOSE', sid]));
      } else if (m[0] === 'CLOSE') subs.get(ws)?.delete(m[1]);
    },
};
Bun.serve({
  port,
  async fetch(req, server) {
    if (server.upgrade(req)) return;
    if (req.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });
    const res = await answer(req);
    for (const [k, v] of Object.entries(CORS)) res.headers.set(k, v);
    if (req.method !== 'GET' && req.method !== 'HEAD') changed();
    return res;
  },
  websocket: WS,
});
console.log(`vaults test server on ws://127.0.0.1:${port} and http://127.0.0.1:${port}`);
