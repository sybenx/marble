// The card drawn over a page: "Sign in to this site with your phone", or, once
// signed in, "Let this site sign in as you?". Tapping Allow here is this device saying yes.

import { api } from '../env.ts';
import { mountPairing } from './pair.ts';

const id = location.hash.slice(1);
const $ = (s: string) => document.getElementById(s)!;
const host = (o: string) => { try { return new URL(o).host; } catch { return o; } };

function resize() {
  requestAnimationFrame(() => parent.postMessage({ cosignCardHeight: document.documentElement.scrollHeight }, '*'));
}
new ResizeObserver(resize).observe(document.body);

const answer = (yes: boolean) => api.runtime.sendMessage({ type: 'card-answer', id, yes });

/** Buttons wait a moment before they work, so a click meant for the page can't land on them. */
function arm(...buttons: HTMLButtonElement[]) {
  buttons.forEach(b => b.disabled = true);
  setTimeout(() => buttons.forEach(b => b.disabled = false), 600);
}

async function main() {
  const info = await api.runtime.sendMessage({ type: 'card-info', id }) as { kind: 'pair' | 'allow'; origin: string } | null;
  if (!info) { $('title').textContent = 'This request has ended.'; resize(); return; }
  const site = host(info.origin);

  if (info.kind === 'pair') {
    $('title').textContent = `Sign in to ${site}`;
    $('body').innerHTML = `<p class="muted">with your phone</p><div id="pair"></div><div class="foot"><button class="link" id="later">Not now</button></div>`;
    mountPairing($('pair'), () => {}, resize);
    ($('later') as HTMLButtonElement).onclick = () => answer(false);
  } else {
    const { pairing } = await api.storage.local.get('pairing') as { pairing?: { profile?: { name?: string } } };
    const who = pairing?.profile?.name ?? 'you';
    $('title').textContent = `Let ${site} sign in as ${who}?`;
    $('body').innerHTML = `
      <p class="muted">Anything destructive still asks your phone first. You can remove it any time.</p>
      <div class="row" style="margin-top:12px"><button class="btn" id="no">Not now</button><button class="btn primary" id="yes">Allow</button></div>`;
    const yes = $('yes') as HTMLButtonElement, no = $('no') as HTMLButtonElement;
    arm(yes, no);
    yes.onclick = () => { yes.disabled = no.disabled = true; answer(true); };
    no.onclick = () => answer(false);
  }
  resize();
}

void main();
