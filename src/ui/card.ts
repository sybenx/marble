// The card drawn over a page: "Sign in to this site with your phone", or, once
// signed in, "Let this site sign in as you?". Tapping Allow here is this device saying yes.

import { api, isNarrow } from '../env.ts';
import { mountSignIn } from './pair.ts';

const id = location.hash.slice(1);
// Safari on iPhone floats its toolbar over the bottom of the page, where this card sits.
if (isNarrow() && (/iPhone|iPad/.test(navigator.userAgent) || (/Macintosh/.test(navigator.userAgent) && navigator.maxTouchPoints > 1))) document.body.classList.add('ios');
const $ = (s: string) => document.getElementById(s)!;
const host = (o: string) => { try { return new URL(o).host; } catch { return o; } };

function resize() {
  requestAnimationFrame(() => parent.postMessage({ cosignCardHeight: Math.ceil(document.body.getBoundingClientRect().height) }, '*'));
}
new ResizeObserver(resize).observe(document.body);

const answer = (yes: boolean) => api.runtime.sendMessage({ type: 'card-answer', id, yes });

/** Buttons wait a moment before they work, so a click meant for the page can't land on them. */
function arm(...buttons: HTMLButtonElement[]) {
  buttons.forEach(b => b.disabled = true);
  setTimeout(() => buttons.forEach(b => b.disabled = false), 600);
}

async function main() {
  const info = await api.runtime.sendMessage({ type: 'card-info', id }) as { kind: 'pair' | 'allow' | 'approve' | 'notice'; origin: string; detail?: string } | null;
  if (!info) { $('title').textContent = 'This request has ended.'; resize(); return; }
  const site = host(info.origin);

  if (info.kind === 'notice') {
    $('title').textContent = `Signed in as ${info.detail ?? 'you'}`;
    $('body').innerHTML = `<p class="muted" style="margin:4px 0 0"><button class="link" id="out" style="padding:0">Not you? Sign out</button></p>`;
    ($('out') as HTMLButtonElement).onclick = () => answer(false);
    setTimeout(() => answer(true), 6000);
  } else if (info.kind === 'approve') {
    const what = (info.detail ?? 'make a big change').replace(/^\w/, c => c.toUpperCase());
    $('title').textContent = `${site} wants to make a big change`;
    $('body').innerHTML = `<p class="who"></p><p class="muted" id="lockNote">You’ll confirm with your device lock.</p>
      <div class="row" style="margin-top:12px"><button class="btn" id="no">Not now</button><button class="btn primary" id="yes">Approve</button></div>`;
    document.querySelector('.who')!.textContent = what;
    const yes = $('yes') as HTMLButtonElement, no = $('no') as HTMLButtonElement;
    arm(yes, no);
    no.onclick = () => answer(false);
    yes.onclick = async () => {
      yes.disabled = true;
      const ok = await deviceLock().catch(e => { $('lockNote').textContent = (e as Error).message; return false; });
      if (ok) answer(true); else yes.disabled = false;
    };
  } else if (info.kind === 'pair') {
    $('title').textContent = `Sign in to ${site}`;
    $('body').innerHTML = `<div id="pair" style="margin-top:12px"></div><div class="foot"><button class="link" id="later">Not now</button></div>`;
    mountSignIn($('pair'), resize);
    ($('later') as HTMLButtonElement).onclick = () => answer(false);
  } else {
    const { pairing } = await api.storage.local.get('pairing') as { pairing?: { profile?: { name?: string } } };
    const who = pairing?.profile?.name ?? 'you';
    $('title').textContent = `Let ${site} sign in as ${who}?`;
    $('body').innerHTML = `
      <div class="row" style="margin-top:12px"><button class="btn" id="no">Not now</button><button class="btn primary" id="yes">Allow</button></div>`;
    const yes = $('yes') as HTMLButtonElement, no = $('no') as HTMLButtonElement;
    arm(yes, no);
    yes.onclick = () => { yes.disabled = no.disabled = true; answer(true); };
    no.onclick = () => answer(false);
  }
  resize();
}

/**
 * Ask for this device's lock (Touch ID, Windows Hello, a passcode) with a passkey that
 * belongs to this extension, made the first time. True only when the lock was used.
 */
async function deviceLock(): Promise<boolean> {
  if (!window.PublicKeyCredential) throw new Error('This browser can’t ask for your device lock, so it can’t approve this here.');
  const b64 = (u: ArrayBuffer) => btoa(String.fromCharCode(...new Uint8Array(u)));
  const unb64 = (s: string) => Uint8Array.from(atob(s), c => c.charCodeAt(0));
  const challenge = crypto.getRandomValues(new Uint8Array(32));
  const { lock } = await api.storage.local.get('lock') as { lock?: { id: string } };
  let data: ArrayBuffer;
  try {
    if (!lock) {
      const c = await navigator.credentials.create({ publicKey: {
        rp: { name: 'Cosign' }, challenge, pubKeyCredParams: [{ type: 'public-key', alg: -7 }, { type: 'public-key', alg: -257 }],
        user: { id: crypto.getRandomValues(new Uint8Array(16)), name: 'Cosign', displayName: 'Cosign on this device' },
        authenticatorSelection: { authenticatorAttachment: 'platform', userVerification: 'required', residentKey: 'discouraged' },
      } }) as PublicKeyCredential;
      await api.storage.local.set({ lock: { id: b64(c.rawId) } });
      data = (c.response as AuthenticatorAttestationResponse).getAuthenticatorData();
    } else {
      const a = await navigator.credentials.get({ publicKey: { challenge, allowCredentials: [{ type: 'public-key', id: unb64(lock.id) }], userVerification: 'required' } }) as PublicKeyCredential;
      data = (a.response as AuthenticatorAssertionResponse).authenticatorData;
    }
  } catch (e) {
    if ((e as Error).name === 'NotAllowedError') return false; // cancelled
    throw new Error('Your device lock isn’t available in this browser, so it can’t approve this here.');
  }
  return (new Uint8Array(data)[32] & 0x04) !== 0; // UV: the lock itself was used
}

void main();
