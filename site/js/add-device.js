// Adding Marble on a Mac to your account (Keykin SPEC 11.6, `add=`). The Mac app holds no sign-in of its own: its
// Sign in opens this site at /#challenge=…&return=http://127.0.0.1:<port>/done&state=…&add=<its Keykin invite>, as an
// app signs in with OAuth (RFC 8252 with PKCE). Marble finds your login page as its own sign-in does (your account if
// this browser is signed in, else your name) and sends you there with the same four; you press Add, a device of yours
// adds the Mac, and the login page hands the approval back to the Mac on this computer. With no login page, the invite
// is shown as a code for your phone's Add a device. The rules here are plain, for tools/ tests.

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

const challengeOk = (c) => typeof c === "string" && /^[A-Za-z0-9_-]{43}$/.test(c) ? c : null;
const stateOk = (x) => typeof x === "string" && /^[A-Za-z0-9_-]{8,128}$/.test(x) ? x : null;
/** The https addresses a phone app takes its approval back on (RFC 8252 7.2, a claimed App Link), exactly as written. */
export const APP_RETURNS = ["https://marble.syb.lol/joined"];
/**
 * Where the approval may go: a loopback address on this computer (http, 127.0.0.1 or localhost, a port, nothing else;
 * RFC 8252 7.3), or Marble on Android's own App Link above (its "Use my name" sign-in).
 */
export function returnOk(r) {
  if (typeof r === "string" && APP_RETURNS.includes(r)) return r;
  let u;
  try { u = new URL(r); } catch { return null; }
  return typeof r === "string" && r.length < 300 && u.protocol === "http:" && ["127.0.0.1", "localhost"].includes(u.hostname) && u.port && !u.username && !u.password && !u.hash ? u.href : null;
}

/**
 * `add=` in a fragment, by the same rules as the login page's `connect=`: the last parameter, everything after it
 * URL-decoded once; `challenge`, `return` and `state` from the parameters before it. Returns null when there is none,
 * else { invite (null when it is not one), app ({ challenge, ret, state }, or null when there is no return), bad (why
 * it is left alone, or null), rest }, where rest is the fragment without the four, to put back.
 */
export function addFromHash(hash) {
  const h = String(hash || "").replace(/^#/, "");
  const at = h.startsWith("add=") ? 0 : h.indexOf("&add=") + 1;
  if (at === 0 && !h.startsWith("add=")) return null;
  const before = h.slice(0, Math.max(0, at - 1)).split("&").filter(Boolean);
  const param = (k) => { const p = before.find((x) => x.startsWith(k + "=")); try { return p ? decodeURIComponent(p.slice(k.length + 1)) : null; } catch { return ""; } };
  let text = "";
  try { text = decodeURIComponent(h.slice(at + "add=".length)); } catch {}
  const rest = before.filter((x) => !/^(challenge|return|state)=/.test(x)).join("&");
  const invite = readInvite(text);
  const r = param("return"), c = param("challenge"), st = param("state");
  if (!invite) return { invite: null, app: null, bad: "invite", rest };
  if (r === null && c === null && st === null) return { invite, app: null, bad: null, rest };
  const ret = r !== null ? returnOk(r) : null;
  if (r !== null && !ret) return { invite, app: null, bad: "return", rest };
  if (!ret || !challengeOk(c) || !stateOk(st)) return { invite, app: null, bad: "incomplete", rest };
  return { invite, app: { challenge: c, ret, state: st }, bad: null, rest };
}

/** The login page's address for adding the device: its origin with the same four parameters, `add=` last. */
export function addLink(origin, invite, app) {
  const more = app ? `challenge=${app.challenge}&return=${encodeURIComponent(app.ret)}&state=${app.state}&` : "";
  return `${String(origin).replace(/\/$/, "")}/#${more}add=${encodeURIComponent(invite)}`;
}
