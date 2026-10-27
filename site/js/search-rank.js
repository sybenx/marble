// Copied unchanged from astral-nostr src/nostr/searchRank.js (b8eac24), the ranking Astral, Wisp and discover.syb.lol share.
// Keep them in step: edit there, run `npm run search-check`, copy here.

// Ranking for profile search. Kept free of NDK and Vue so scripts/search-check.mjs
// can run the same code against live relays.
//
// Search relays (Ditto, syb.lol) return every account with a matching name, and
// impostors copy names freely, so the order is ours:
//   - people you follow first;
//   - then a weighted score: exact name +100 (prefix +60, contains +30),
//     NIP-05 checked against its .well-known +30, 15 × log10(1 + followers),
//     and a small hint from Primal's own ranking.
// Weighted rather than strict tiers on purpose: anyone can verify a NIP-05 on a
// domain they own, and a real person's domain can be down (gleasonator.com was
// on 2026-10-24), so a verified copy with no followers mustn't outrank them.

export function normalizeName(s) {
  return (s || '')
    .normalize('NFKC')
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, ' ')
    .trim()
}

function nameMatch(profile, q) {
  let best = 0
  for (const n of [profile.display_name, profile.name]) {
    const name = normalizeName(n)
    if (!name) continue
    if (name === q) return 100
    if (name.startsWith(q)) best = Math.max(best, 60)
    else if (name.includes(q)) best = Math.max(best, 30)
  }
  return best
}

// the name, or the NIP-05's name part, contains the query
export function matchesQuery(profile, query) {
  const q = normalizeName(query)
  if (!q) return false
  if (nameMatch(profile, q) > 0) return true
  return normalizeName((profile.nip05 || '').split('@')[0]).includes(q)
}

/**
 * @param {object[]} profiles  parsed profiles ({pubkey, name, display_name, nip05, …})
 * @param {string} query
 * @param {object} signals
 * @param {Set<string>} [signals.follows]        pubkeys the viewer follows
 * @param {Map<string,boolean>} [signals.verified] pubkey → NIP-05 checked and valid
 * @param {Map<string,number>} [signals.followers] pubkey → follower count
 * @param {Map<string,number>} [signals.primalRank] pubkey → position in Primal's results
 */
export function scoreProfile(p, query, { follows, verified, followers, primalRank } = {}) {
  const q = normalizeName(query)
  let score = nameMatch(p, q)
  if (follows?.has(p.pubkey)) score += 1000
  if (verified?.get(p.pubkey)) score += 30
  score += 15 * Math.log10(1 + (followers?.get(p.pubkey) || 0))
  const rank = primalRank?.get(p.pubkey)
  if (rank !== undefined) score += Math.max(0, 10 - rank)
  return score
}

export function rankProfiles(profiles, query, signals = {}) {
  return profiles
    .map((p, i) => ({ p, i, s: scoreProfile(p, query, signals) }))
    .sort((a, b) => b.s - a.s || a.i - b.i)
    .map((x) => x.p)
}

// ── NIP-05 check ────────────────────────────────────────────────────────────
const nip05Cache = new Map() // `${nip05}|${pubkey}` → Promise<boolean>

export function verifyNip05(nip05, pubkey, timeout = 2500) {
  const m = /^(?:([\w.+-]*)@)?([\w-]+(?:\.[\w-]+)+)$/.exec(String(nip05 || '').trim())
  if (!m || !pubkey) return Promise.resolve(false)
  const name = (m[1] || '_').toLowerCase()
  const domain = m[2].toLowerCase()
  const key = `${name}@${domain}|${pubkey}`
  if (!nip05Cache.has(key)) {
    const ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null
    const timer = setTimeout(() => ctrl?.abort(), timeout)
    nip05Cache.set(
      key,
      fetch(`https://${domain}/.well-known/nostr.json?name=${encodeURIComponent(name)}`, { signal: ctrl?.signal, redirect: 'error' })
        .then((r) => (r.ok ? r.json() : null))
        .then((data) => {
          const names = data?.names || {}
          const found = names[name] ?? Object.entries(names).find(([k]) => k.toLowerCase() === name)?.[1]
          return found === pubkey
        })
        .catch(() => false)
        .finally(() => clearTimeout(timer))
    )
  }
  return nip05Cache.get(key)
}

export function parseProfile(event) {
  try {
    const c = JSON.parse(event.content)
    const s = (x) => (typeof x === 'string' ? x : '') // a profile can hold anything: only text is kept
    return {
      pubkey: event.pubkey,
      created_at: event.created_at,
      name: s(c.name) || s(c.display_name),
      display_name: s(c.display_name) || s(c.name),
      picture: s(c.picture),
      nip05: s(c.nip05),
      about: s(c.about),
      lud16: s(c.lud16),
      lud06: s(c.lud06),
    }
  } catch (_) {
    return null
  }
}

// Bridged copies (mostr, momostr, Bridgy Fed) duplicate real accounts with
// their followers attached.
const BRIDGES = /(^|[.@])(mostr\.pub|momostr\.pink|brid\.gy)$/i
export function isBridged(p) {
  const nip05 = String(p?.nip05 || '').trim() // (a profile can hold anything)
  return !!nip05 && (BRIDGES.test(nip05) || /_at_/i.test(nip05.split('@')[0]))
}
