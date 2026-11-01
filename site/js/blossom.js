// Blossom links (BUD-10): "blossom:<sha256>.<ext>?xs=server&as=<pubkey>&sz=<bytes>", a file named by its hash, found on
// the servers it names (xs), then its authors' server lists (as: kind 10063, BUD-03), then a few well-known ones; a copy
// counts only if its size is sz, when given (content-rules.md §4). The blossom: text itself never shows.
import { req, relays, FAST, INDEXERS } from "./pool.js";

export const BLOSSOM = /blossom:[0-9a-f]{64}\.[a-z0-9]{1,8}(?:\?[^\s<>"']*[^\s<>"'.,;:!?)\]}])?/gi;
const WELL_KNOWN = ["https://blossom.primal.net", "https://cdn.satellite.earth", "https://blossom.band"];
const PICTURE = /^(jpe?g|png|gif|webp|avif)$/i, MOVIE = /^(mp4|webm|mov|m4v)$/i;

/** {hash, ext, xs: [server…], as: [pubkey…], sz, kind: "image" | "video" | "file"} for a blossom: link, or null. */
export function parseBlossom(uri) {
  const m = /^blossom:([0-9a-f]{64})\.([a-z0-9]{1,8})(?:\?(.*))?$/i.exec(uri || "");
  if (!m) return null;
  const q = new URLSearchParams(m[3] || "");
  const ext = m[2].toLowerCase();
  return { hash: m[1].toLowerCase(), ext, xs: q.getAll("xs").filter(Boolean), as: q.getAll("as").filter((p) => /^[0-9a-f]{64}$/i.test(p)),
    sz: /^\d+$/.test(q.get("sz") || "") ? +q.get("sz") : null, kind: PICTURE.test(ext) ? "image" : MOVIE.test(ext) ? "video" : "file" };
}

// a server as written: a full address as it is; a bare domain, https then http (never http from an https page)
const bases = (s) => /^https?:\/\//i.test(s) ? [s] : [`https://${s}`, ...(location.protocol === "http:" ? [`http://${s}`] : [])];

async function has(url, sz) {
  try {
    const r = await fetch(url, { method: "HEAD", signal: AbortSignal.timeout(5000) });
    return r.ok && (sz == null || +r.headers.get("content-length") === sz);
  } catch { return false; }
}
// the first of these that has it (all asked at once; the order decides)
async function first(urls, sz) {
  const answers = urls.map((u) => has(u, sz));
  for (let i = 0; i < urls.length; i++) if (await answers[i]) return urls[i];
  return null;
}

const found = new Map(); // link → Promise<url | null>, for the visit
/** Where a blossom: link's file is (a URL), or null if no server has it. */
export function findBlossom(uri) {
  if (!found.has(uri)) found.set(uri, (async () => {
    const b = parseBlossom(uri);
    if (!b) return null;
    const at = (servers) => servers.flatMap(bases).map((s) => `${s.replace(/\/+$/, "")}/${b.hash}.${b.ext}`);
    let url = b.xs.length ? await first(at(b.xs), b.sz) : null;
    if (!url && b.as.length) {
      const lists = await req(relays(FAST, INDEXERS), { kinds: [10063], authors: b.as }, { timeout: 3000, settle: 600 });
      const servers = b.as.flatMap((pk) => (lists.filter((e) => e.pubkey === pk).sort((x, y) => y.created_at - x.created_at)[0]?.tags || [])
        .filter((t) => t[0] === "server" && /^https?:\/\//.test(t[1] || "")).map((t) => t[1]));
      if (servers.length) url = await first(at(servers), b.sz);
    }
    return url || first(at(WELL_KNOWN), b.sz);
  })());
  return found.get(uri);
}

/** Fills in the blossom: pictures, videos and file links under root once they're found. */
export function fillBlossom(root) {
  for (const el of root.querySelectorAll("[data-blossom]:not([data-looked])")) {
    el.dataset.looked = "1";
    findBlossom(el.dataset.blossom).then((url) => {
      if (url && el.matches("a")) { el.href = url; el.target = "_blank"; el.rel = "noopener noreferrer nofollow"; el.classList.remove("finding"); return; }
      // found, but it doesn't show (not a picture after all): a quiet link to it instead of an empty box
      if (url) {
        el.onerror = () => {
          const a = Object.assign(document.createElement("a"), { className: "muted blossom-gone", href: url, target: "_blank", rel: "noopener noreferrer nofollow", textContent: "Open ›" });
          el.hidden = true;
          el.after(a);
        };
        el.src = url;
        return;
      }
      // not found anywhere: said quietly (never an empty box)
      if (el.matches("a")) { el.classList.remove("finding"); el.classList.add("gone"); el.removeAttribute("href"); el.textContent = "Couldn't find that file"; return; }
      el.hidden = true;
      el.insertAdjacentHTML("afterend", `<span class="muted blossom-gone">Couldn't find that ${el.matches("video") ? "video" : "picture"}</span>`);
    });
  }
}
