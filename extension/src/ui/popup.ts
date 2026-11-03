// The toolbar popup: who you're signed in as, the sites using this browser, and the
// way out.

import { bech32 } from '@scure/base';
import { hexToBytes } from '@noble/hashes/utils.js';
import { api, isNarrow } from '../env.ts';
import { mountSignIn, qrSvg } from './pair.ts';

if (isNarrow()) document.documentElement.classList.add('phone');
const $ = (s: string) => document.getElementById(s)!;
// Just the npub encoding, not all of nostr-tools: the popup should open at once.
const npubOf = (hex: string) => bech32.encode('npub', bech32.toWords(hexToBytes(hex)), 1000);
const host = (o: string) => { try { return new URL(o).host; } catch { return o; } };
const esc = (s: string) => s.replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]!));
const ago = (ms: number) => {
  const s = (Date.now() - ms) / 1000;
  return s < 60 ? 'just now' : s < 3600 ? `${Math.floor(s / 60)} min ago` : s < 86400 ? `${Math.floor(s / 3600)} h ago` : `${Math.floor(s / 86400)} d ago`;
};

let unmount: (() => void) | undefined;
let here: string | undefined;

api.tabs.query({ active: true, currentWindow: true }).then(([t]) => {
  try { const o = t?.url ? new URL(t.url).origin : ''; here = /^https?:/.test(o) ? o : undefined; } catch { here = undefined; }
}).catch(() => {});

const port = api.runtime.connect({ name: 'popup' });
// Keeps the background awake while the popup is open (a join can take a while).
setInterval(() => { try { port.postMessage({ type: 'ping' }); } catch { /* closed */ } }, 10_000);
let shownMark = false;
const mark = () => {
  if (shownMark) return;
  shownMark = true;
  requestAnimationFrame(() => {
    performance.mark('marble-content');
    // Timing runs only (a test page frames the popup with #measure).
    if (location.hash === '#measure' && parent !== window) parent.postMessage({ marbleContent: performance.now() }, '*');
  });
};
// Draw the last known state at once; the background's answer replaces it a moment later.
let live = false;
api.storage.local.get('popupCache').then((r: any) => { if (!live && r?.popupCache) { render(r.popupCache); mark(); } }).catch(() => {});
port.onMessage.addListener((s: any) => {
  if (s.type === 'state') { live = true; render(s); mark(); return; }
  render(s);
});
function render(s: any) {
  if (s.type === 'join' && s.code) { $('joinQr').innerHTML = qrSvg(s.code); $('joinDeviceCode').textContent = s.code; }
  if (s.type === 'join') {
    $('joinStatus').textContent = s.text;
    $('joinStatus').classList.toggle('error', !!s.error);
    ($('joinGo') as HTMLButtonElement).disabled = !s.error && !s.done;
    return;
  }
  if (s.type !== 'state') return;
  $('out').hidden = s.signedIn;
  $('in').hidden = !s.signedIn;
  if (!s.signedIn) { unmount ??= mountSignIn($('pair')); return; }
  unmount?.(); unmount = undefined;

  const npub = npubOf(s.pubkey);
  const short = `${npub.slice(0, 12)}…${npub.slice(-6)}`;
  // Where the npub shows (shortened), a click copies all of it.
  const at = s.profile?.name ? $('npub') : $('name');
  $('name').textContent = s.profile?.name ?? short;
  $('npub').textContent = s.profile?.name ? short : 'Signed in';
  for (const el of [$('name'), $('npub')]) { el.classList.remove('copy'); el.removeAttribute('title'); delete el.dataset.npub; }
  at.classList.add('copy'); at.title = 'Copy your npub'; at.dataset.npub = npub;
  const av = $('avatar') as HTMLImageElement;
  av.hidden = !s.profile?.picture;
  if (s.profile?.picture) av.src = s.profile.picture;

  const listed = new Set(s.sites.map((x: any) => x.origin));
  $('here').hidden = !here || listed.has(here);
  if (here) $('hereHost').textContent = host(here);

  $('sites').innerHTML = s.sites.map((x: any) =>
    `<div class="item"><div><div class="host">${esc(host(x.origin))}</div><div class="muted">used ${ago(x.lastUsed)}</div></div><button class="btn small" data-origin="${esc(x.origin)}">Remove</button></div>`).join('');
  $('sites').hidden = s.sites.length === 0;
  $('none').hidden = s.sites.length > 0;
  $('device').textContent = s.member || s.pending ? '' : `Your phone lists this browser as “${s.name}”.`;
  // Signing out would throw away this browser's part of your key; not offered until
  // adding your phone or printing a backup exists.
  $('signOut').hidden = !!s.member;
  $('memberNote').hidden = !s.member;
  $('safety').hidden = !s.unsafe;
  $('removed').hidden = !s.removed;
  $('pending').hidden = !s.pending;
  if (s.pending?.long) {
    $('pendingTitle').textContent = 'Still not protected';
    $('pendingText').textContent = 'It’s been over a day and no servers have answered. If this browser’s data is lost, your account is gone. Check your connection; Marble keeps trying.';
  }
  if (s.pending) { $('safety').hidden = true; $('signOut').hidden = true; $('memberNote').hidden = true; }
  if (s.removed) $('removedBy').textContent = `${s.removed.by} removed it. It can’t sign any more.`;
}

$('sites').addEventListener('click', e => {
  const b = (e.target as HTMLElement).closest('button[data-origin]') as HTMLButtonElement | null;
  if (b) { b.disabled = true; api.runtime.sendMessage({ type: 'remove-site', origin: b.dataset.origin }); }
});
$('allow').onclick = () => here && api.runtime.sendMessage({ type: 'allow-site', origin: here });
$('signOut').onclick = () => {
  if (confirm('Sign out of this browser? Every site here stops signing as you.')) api.runtime.sendMessage({ type: 'sign-out' });
};

// Joining a server's own group by its code is for testing and self-hosters for now.
api.storage.local.get('dev').then((r: any) => { if (r.dev) { $('joinOpen').hidden = false; $('joinDevice').hidden = false; } }).catch(() => {});
$('joinDevice').onclick = () => { $('joinDevice').hidden = true; $('joinDeviceView').hidden = false; port.postMessage({ type: 'join-existing' }); };
$('joinOpen').onclick = () => { $('joinForm').hidden = false; $('joinOpen').hidden = true; ($('joinCode') as HTMLTextAreaElement).focus(); };
$('joinGo').onclick = () => {
  const uri = ($('joinCode') as HTMLTextAreaElement).value.trim();
  if (!uri) return;
  ($('joinGo') as HTMLButtonElement).disabled = true;
  $('joinStatus').textContent = 'Connecting…';
  port.postMessage({ type: 'join', uri });
};

$('safePaper').onclick = () => { api.runtime.sendMessage({ type: 'open-safety', which: 'paper' }); window.close(); };
$('safePhone').onclick = () => { api.runtime.sendMessage({ type: 'open-safety', which: 'phone' }); window.close(); };

// Click your npub: the whole of it is copied; for a moment it reads "Copied".
for (const id of ['name', 'npub']) $(id).addEventListener('click', async () => {
  const el = $(id);
  const full = el.dataset.npub;
  if (!full || el.dataset.copied) return;
  try { await navigator.clipboard.writeText(full); } catch { return; }
  const was = el.textContent;
  el.dataset.copied = '1'; el.textContent = 'Copied';
  setTimeout(() => { el.textContent = was; delete el.dataset.copied; }, 1200);
});
