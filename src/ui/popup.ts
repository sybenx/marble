// The toolbar popup: who you're signed in as, the sites using this browser, and the
// way out.

import { nip19 } from 'nostr-tools';
import { api, isPhone } from '../env.ts';
import { mountPairing } from './pair.ts';

if (isPhone()) document.documentElement.classList.add('phone');
const $ = (s: string) => document.getElementById(s)!;
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
port.onMessage.addListener((s: any) => {
  if (s.type !== 'state') return;
  $('out').hidden = s.signedIn;
  $('in').hidden = !s.signedIn;
  if (!s.signedIn) { unmount ??= mountPairing($('pair'), () => {}); return; }
  unmount?.(); unmount = undefined;

  const npub = nip19.npubEncode(s.pubkey);
  $('name').textContent = s.profile?.name ?? `${npub.slice(0, 12)}…${npub.slice(-6)}`;
  $('npub').textContent = s.profile?.name ? `${npub.slice(0, 12)}…${npub.slice(-6)}` : 'Signed in';
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
  $('device').textContent = `Your phone lists this browser as “${s.name}”.`;
});

$('sites').addEventListener('click', e => {
  const b = (e.target as HTMLElement).closest('button[data-origin]') as HTMLButtonElement | null;
  if (b) { b.disabled = true; api.runtime.sendMessage({ type: 'remove-site', origin: b.dataset.origin }); }
});
$('allow').onclick = () => here && api.runtime.sendMessage({ type: 'allow-site', origin: here });
$('signOut').onclick = () => {
  if (confirm('Sign out of this browser? Every site here stops signing as you.')) api.runtime.sendMessage({ type: 'sign-out' });
};
