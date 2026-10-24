// The card drawn over a page: "Sign in to this site with your phone", or, once
// signed in, "Let this site sign in as you?". Tapping Allow here is this device saying yes.

import { api, isNarrow } from '../env.ts';
import { mountSignIn } from './pair.ts';
import { deviceLock } from './lock.ts';

// `#<id>`: drawn in the page. `&window`: in a window of the extension's own (approvals on
// a computer). `&pointer`: in the page, only pointing at that window.
const [id, ...flags] = location.hash.slice(1).split('&');
const inWindow = flags.includes('window'), pointer = flags.includes('pointer');
if (inWindow) document.body.classList.add('win'); else if (isNarrow()) document.body.classList.add('sheet');
// Safari on iPhone floats its toolbar over the bottom of the page, where this card sits.
if (isNarrow() && (/iPhone|iPad/.test(navigator.userAgent) || (/Macintosh/.test(navigator.userAgent) && navigator.maxTouchPoints > 1))) document.body.classList.add('ios');
const $ = (s: string) => document.getElementById(s)!;
const host = (o: string) => { try { return new URL(o).host; } catch { return o; } };

function resize() {
  if (inWindow) return;
  requestAnimationFrame(() => parent.postMessage({ cosignCardHeight: Math.ceil(document.body.getBoundingClientRect().height) }, '*'));
}
new ResizeObserver(resize).observe(document.body);

const answer = (yes: boolean) => { void api.runtime.sendMessage({ type: 'card-answer', id, yes }); if (inWindow) setTimeout(() => window.close(), 50); };

// --- Against clickjacking, for a card drawn in the page ---------------------------------
//
// The page can't read or click inside this frame, but it controls the frame element: it
// could fade it, cover it, or move it under your pointer to get a real click on Allow.
// So the buttons that grant or approve work only when: the card has been still and in
// view for a moment (re-armed whenever the page tampers, says the content script); the
// click is a real one; and, where the browser can tell (Chromium's IntersectionObserver
// v2), the button is fully visible and nothing covers it. In a window of our own none of
// this is needed, but it costs nothing.

const ARM_MS = 800;
let armedAt = Number.POSITIVE_INFINITY, visible = true;
const guarded: HTMLButtonElement[] = [];
const refresh = () => { const ok = Date.now() - armedAt >= ARM_MS && visible; for (const b of guarded) b.disabled = !ok; };
function rearm() { armedAt = Date.now(); refresh(); setTimeout(refresh, ARM_MS + 20); }
window.addEventListener('message', e => { if (e.source === parent && e.data?.cosignCardTamper) rearm(); });
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') rearm(); });

/** These buttons grant or approve: arm them, and let them act only on a real, settled click. */
function arm(...buttons: HTMLButtonElement[]) {
  guarded.push(...buttons);
  rearm();
  if ('IntersectionObserver' in window && !inWindow) {
    try {
      const io = new IntersectionObserver(es => {
        for (const e of es) visible = (e as any).isVisible === undefined ? true : (e as any).isVisible && e.intersectionRatio >= 0.99;
        if (!visible) rearm(); else refresh();
      }, { threshold: [1], trackVisibility: true, delay: 100 } as IntersectionObserverInit);
      // The row, not the button: a disabled button is drawn half-faded, which the check
      // would count as hidden, and it would never come back on.
      for (const b of buttons) io.observe(b.parentElement ?? b);
    } catch { /* no v2: the other checks still hold */ }
  }
}
/** A click handler that only acts on a real click on an armed button. */
const real = (b: HTMLButtonElement, f: () => void) => {
  b.onclick = (e: MouseEvent) => {
    if (!e.isTrusted || b.disabled || Date.now() - armedAt < ARM_MS || !visible) return;
    f();
  };
};

async function main() {
  const info = await api.runtime.sendMessage({ type: 'card-info', id }) as { kind: 'pair' | 'allow' | 'approve' | 'notice' | 'backup'; origin: string; detail?: string } | null;
  if (!info) { $('title').textContent = 'This request has ended.'; resize(); return; }
  const site = host(info.origin);

  if (info.kind === 'backup') {
    $('title').textContent = 'One more step: a paper backup';
    $('body').innerHTML = `<p class="muted">If this browser’s data is lost, it gets your account back.</p>
      <div class="row" style="margin-top:12px"><button class="btn" id="later">Later</button><button class="btn primary" id="make">Make it</button></div>
      <button class="link" id="phone" style="margin-top:4px">Add your phone instead</button>`;
    ($('later') as HTMLButtonElement).onclick = () => answer(true);
    ($('make') as HTMLButtonElement).onclick = () => { api.runtime.sendMessage({ type: 'open-safety', which: 'paper' }); answer(true); };
    ($('phone') as HTMLButtonElement).onclick = () => { api.runtime.sendMessage({ type: 'open-safety', which: 'phone' }); answer(true); };
  } else if (info.kind === 'notice') {
    $('title').textContent = `Signed in as ${info.detail ?? 'you'}`;
    $('body').innerHTML = `<p class="muted" style="margin:4px 0 0"><button class="link" id="out" style="padding:0">Not you? Sign out</button></p>`;
    ($('out') as HTMLButtonElement).onclick = () => answer(false);
    setTimeout(() => answer(true), 6000);
  } else if (pointer) {
    // The decision is in a window of the extension's own; here only a pointer to it.
    $('title').textContent = info.kind === 'approve' ? `${site} wants to make a big change` : `${site} wants to sign in`;
    $('body').innerHTML = `<p class="muted">Cosign is asking in its own window.</p><button class="btn primary" id="show" style="width:100%">Show it</button>`;
    ($('show') as HTMLButtonElement).onclick = () => api.runtime.sendMessage({ type: 'card-focus', id });
  } else if (info.kind === 'approve') {
    const what = (info.detail ?? 'make a big change').replace(/^\w/, c => c.toUpperCase());
    $('title').textContent = 'A big change';
    $('body').innerHTML = `<p class="site"></p><p class="who"></p><p class="muted" id="lockNote">You’ll confirm with your device lock.</p>
      <div class="row" style="margin-top:12px"><button class="btn" id="no">Not now</button><button class="btn primary" id="yes">Approve</button></div>`;
    document.querySelector('.site')!.textContent = site; // from the extension's record, never the page
    document.querySelector('.who')!.textContent = what;
    const yes = $('yes') as HTMLButtonElement, no = $('no') as HTMLButtonElement;
    arm(yes);
    no.onclick = () => answer(false);
    real(yes, async () => {
      yes.disabled = true;
      const ok = await deviceLock().catch(e => { $('lockNote').textContent = (e as Error).message; return false; });
      if (ok) answer(true); else refresh();
    });
  } else if (info.kind === 'pair') {
    $('title').textContent = `Sign in to ${site}`;
    $('body').innerHTML = `<div id="pair" style="margin-top:12px"></div><div class="foot"><button class="link" id="later">Not now</button></div>`;
    mountSignIn($('pair'), resize);
    ($('later') as HTMLButtonElement).onclick = () => answer(false);
  } else {
    const { pairing, member } = await api.storage.local.get(['pairing', 'member']) as { pairing?: { profile?: { name?: string } }; member?: { profile?: { name?: string } } };
    const who = member?.profile?.name ?? pairing?.profile?.name ?? 'you';
    $('title').textContent = `Let this site sign in as ${who}?`;
    $('body').innerHTML = `<p class="site"></p>
      <div class="row" style="margin-top:12px"><button class="btn" id="no">Not now</button><button class="btn primary" id="yes">Allow</button></div>`;
    document.querySelector('.site')!.textContent = site; // from the extension's record, never the page
    const yes = $('yes') as HTMLButtonElement, no = $('no') as HTMLButtonElement;
    arm(yes);
    real(yes, () => { yes.disabled = no.disabled = true; answer(true); });
    no.onclick = () => answer(false);
  }
  resize();
}

void main();
