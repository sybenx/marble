// Adding Marble on a Mac to your account (Keykin SPEC 11.6, `add=`). The Mac app holds no sign-in of its own: its
// Sign in opens this site at /#relay=…&secret=…&add=<its Keykin invite>. Marble finds your login page as its own
// sign-in does (your account if this browser is signed in, else your name) and sends you there with the same three;
// you press Add, a device of yours adds the Mac, and the login page sends the join code to the Mac's invite key on that
// relay, as a site hears a nostrconnect answer. With no login page, the invite is shown as a code for your phone's Add
// a device. The rules here are plain, for tools/ tests.

export const ADD = "marble.add"; // sessionStorage: the invite, until it is handed on

/** A Keykin invite: JSON, keykin 2, an invite with a 64-hex key. { text, key, name }, or null. */
export function readInvite(text) {
  const t = String(text ?? "").trim();
  if (!t.startsWith("{") || t.length > 4000) return null;
  let v;
  try { v = JSON.parse(t); } catch { return null; }
  const inv = v && v.keykin === 2 && v.invite && typeof v.invite === "object" ? v.invite : null;
  if (!inv || typeof inv.key !== "string" || !/^[0-9a-f]{64}$/i.test(inv.key)) return null;
  const name = (typeof inv.name === "string" ? inv.name.trim().slice(0, 60) : "") || "A new device";
  return { text: t, key: inv.key.toLowerCase(), name };
}

const relayOk = (r) => typeof r === "string" && r.length < 300 && /^wss?:\/\/[^\s]+$/.test(r) ? r : null;
const secretOk = (x) => typeof x === "string" && /^[0-9a-f]{16,64}$/i.test(x) ? x : null;

/**
 * `add=` in a fragment, by the same rules as the login page's `connect=`: the last parameter, everything after it
 * URL-decoded once; `relay` and `secret` from the parameters before it. Returns null when there is none, else
 * { invite (null when it is not one), relay, secret, rest }, where rest is the fragment without the three, to put back.
 */
export function addFromHash(hash) {
  const h = String(hash || "").replace(/^#/, "");
  const at = h.startsWith("add=") ? 0 : h.indexOf("&add=") + 1;
  if (at === 0 && !h.startsWith("add=")) return null;
  const before = h.slice(0, Math.max(0, at - 1)).split("&").filter(Boolean);
  const param = (k) => { const p = before.find((x) => x.startsWith(k + "=")); try { return p ? decodeURIComponent(p.slice(k.length + 1)) : null; } catch { return null; } };
  let text = "";
  try { text = decodeURIComponent(h.slice(at + "add=".length)); } catch {}
  const relay = relayOk(param("relay")), secret = secretOk(param("secret"));
  return { invite: readInvite(text), relay: relay && secret ? relay : null, secret: relay && secret ? secret : null, rest: before.filter((x) => !/^(relay|secret)=/.test(x)).join("&") };
}

/** The login page's address for adding the Mac: its origin with the same three parameters, `add=` last. */
export function addLink(origin, { invite, relay, secret }) {
  const answer = relay && secret ? `relay=${encodeURIComponent(relay)}&secret=${secret}&` : "";
  return `${String(origin).replace(/\/$/, "")}/#${answer}add=${encodeURIComponent(invite)}`;
}
