// A relay to read here: "wss://pyramid.fiatjaf.com/popular" typed as it is, or an address ("pyramid.fiatjaf.com/popular")
// that says it's a relay when asked (NIP-11: its information document, over https with Accept: application/nostr+json).
import { testRelay } from "./pool.js";

const local = (host) => /^(localhost|127\.0\.0\.1)(:\d+)?$/i.test(host);

/** {url: "wss://host/path", address: "host/path", sure}: sure when typed with wss://, else it has to say so. */
export function relayOf(typed) {
  const t = String(typed || "").trim();
  let m = /^(wss?):\/\/([a-z0-9.-]+(?::\d+)?)(\/\S*)?$/i.exec(t);
  if (m) return make(m[2], m[3], true, m[1].toLowerCase());
  m = /^([a-z0-9-]+(?:\.[a-z0-9-]+)*\.[a-z]{2,}|localhost:\d+)(\/[^\s?#]*)?$/i.exec(t);
  return m ? make(m[1], m[2], false) : null;
}
function make(host, path = "", sure, scheme) {
  host = host.toLowerCase();
  if (local(host) && !testRelay()) return null;
  const address = host + (path || "").replace(/\/+$/, "");
  return { url: `${scheme || (local(host) ? "ws" : "wss")}://${address}`, address, sure };
}

/** The relay page's path for a relay address. */
export const relayPath = (r) => `/relay/${r.address}`;

const infos = new Map(); // address → Promise<info | null>
/** What the relay says about itself (name, description, icon, pubkey…), or null if it isn't one (or doesn't answer). */
export function relayInfo(r) {
  if (!infos.has(r.address)) {
    const http = r.url.replace(/^ws/, "http");
    infos.set(r.address, fetch(http, { headers: { Accept: "application/nostr+json" }, signal: AbortSignal.timeout(3500) })
      .then((res) => (res.ok && /json/.test(res.headers.get("content-type") || "") ? res.json() : null))
      .then((j) => (j && typeof j === "object" && !Array.isArray(j) && (j.name || j.supported_nips || j.software) ? j : null))
      .catch(() => null)
      .then((info) => (answered.set(r.address, info), info)));
  }
  return infos.get(r.address);
}
const answered = new Map(); // address → info | null, once it has answered
/** What it said, if it has answered: its info, null (not a relay), or undefined (not asked yet, or still asking). */
export const relayAnswered = (r) => answered.get(r.address);
