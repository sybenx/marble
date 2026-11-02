#!/usr/bin/env node
// Translation benchmark against your LibreTranslate, on the shared language fixtures (57 real posts).
//
//   node tools/translate-bench.mjs https://translate.your-tailnet.ts.net [api-key] [target=en]
//
// 1. Language detection (/detect) on each post, raw and cleaned (links, nostr: references, hashtags, mentions, emoji
//    and :shortcodes: out, as Marble's PostLanguage.clean does): right / wrong / unsure (under 80% confidence).
// 2. Translation (/translate) of every post not already in the target language, with links, mentions, hashtags and
//    shortcodes protected as placeholders (as PostLanguage.protect), written side by side with the original to
//    translate-bench.md for reading, and checked that every protected piece came back.
// ML Kit's side runs only on a phone, so it isn't here: compare by reading the same posts translated in Marble.
import fs from 'node:fs'

const [server, key = '', target = 'en'] = process.argv.slice(2)
if (!server) { console.error('usage: node tools/translate-bench.mjs <libretranslate-url> [api-key] [target]'); process.exit(1) }
const base = server.replace(/\/$/, '')
const fixtures = new URL('../android/app/src/test/resources/languages/posts.json', import.meta.url)
const posts = JSON.parse(fs.readFileSync(fixtures)).posts

const URL_ = /\b(?:https?:\/\/|www\.)\S+/gi
const REF = /\b(?:nostr:)?(?:npub|nprofile|note|nevent|naddr)1[02-9ac-hj-np-z]{8,}/gi
const HASHTAG = /(?<![\p{L}\p{N}_])#[\p{L}\p{N}_]+/gu
const MENTION = /(?<![\p{L}\p{N}_.])[@＠][\p{L}\p{N}_.-]+/gu
const SHORTCODE = /:[A-Za-z0-9_+-]{2,}:/g
const EMOJI = /[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{2B00}-\u{2BFF}\u{FE0F}\u{200D}\u{1F1E6}-\u{1F1FF}\u{E0020}-\u{E007F}]/gu
const clean = t => [URL_, REF, SHORTCODE, HASHTAG, MENTION, EMOJI].reduce((s, r) => s.replace(r, ' '), t).replace(/\s+/g, ' ').trim()
const protect = t => {
  const kept = []
  const all = new RegExp([URL_, REF, SHORTCODE, HASHTAG, MENTION].map(r => `(?:${r.source})`).join('|'), 'giu')
  return [t.replace(all, m => { kept.push(m); return `⟦${kept.length - 1}⟧` }), kept]
}
const restore = (t, kept) => {
  const used = new Set()
  let out = t.replace(/⟦\s*(\d+)\s*⟧/g, (_, i) => { used.add(+i); return kept[+i] ?? '' })
  const lost = kept.filter((_, i) => !used.has(i))
  return [lost.length ? out.trimEnd() + ' ' + lost.join(' ') : out, lost.length]
}
const post = async (path, body) => {
  const r = await fetch(base + path, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ ...body, ...(key ? { api_key: key } : {}) }) })
  if (!r.ok) throw new Error(`${path}: ${r.status}`)
  return r.json()
}

const score = { raw: { right: 0, wrong: 0, unsure: 0 }, cleaned: { right: 0, wrong: 0, unsure: 0 } }
let md = `# Translation benchmark: LibreTranslate (${base}) into ${target}\n\n`
let lostTotal = 0, translated = 0
for (const p of posts) {
  for (const [name, text] of [['raw', p.content], ['cleaned', clean(p.content)]]) {
    const d = (await post('/detect', { q: text }))[0]
    const s = score[name]
    if (!d || d.confidence < 80) s.unsure++; else if (d.language === p.lang) s.right++; else s.wrong++
  }
  if (p.lang === target) continue
  const [q, kept] = protect(p.content)
  const out = await post('/translate', { q, source: p.lang, target, format: 'text' })
  const [text, lost] = restore(out.translatedText ?? '', kept)
  lostTotal += lost; translated++
  md += `## ${p.id.slice(0, 8)} (${p.lang})\n\n${p.content}\n\n→ ${text}\n\n`
}
fs.writeFileSync('translate-bench.md', md)
console.log(`detect, raw:     ${JSON.stringify(score.raw)}`)
console.log(`detect, cleaned: ${JSON.stringify(score.cleaned)}`)
console.log(`translated ${translated}; links/mentions/hashtags a translator dropped (put back at the end): ${lostTotal}`)
console.log('side by side: translate-bench.md')
