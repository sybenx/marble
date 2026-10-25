// "Sign in with your phone": the code, drawn in a card or the toolbar popup. The code
// itself lives in the extension's background, so it survives this view closing (on a
// phone, opening Cosign closes the popup).

import encodeQR from 'qr';
import { api, isAndroid } from '../env.ts';

export function qrSvg(text: string): string {
  const m = encodeQR(text, 'raw', { ecc: 'medium', border: 3 });
  let d = '';
  for (let y = 0; y < m.length; y++) for (let x = 0; x < m.length; x++) {
    if (!m[y][x]) continue;
    let run = 1; while (x + run < m.length && m[y][x + run]) run++;
    d += `M${x} ${y}h${run}v1h-${run}z`; x += run - 1;
  }
  return `<svg viewBox="0 0 ${m.length} ${m.length}" shape-rendering="crispEdges" role="img" aria-label="Sign-in code"><rect width="${m.length}" height="${m.length}" fill="#fff"/><path d="${d}" fill="#000"/></svg>`;
}

export interface PairView { uri?: string; status: string; confirm?: string; error?: string; done?: boolean; busy?: boolean; note?: string }

/** Draw the pairing into `el`. Calls `onDone` once signed in. */
export function mountPairing(el: HTMLElement, onDone: () => void, onResize: () => void = () => {}) {
  el.innerHTML = `
    <div class="qr" hidden></div>
    <a class="btn primary open" hidden target="_blank" rel="noopener">Open Marble</a>
    <button class="link show-qr" hidden>Show a code instead</button>
    <p class="status muted"></p>
    <div class="confirm" hidden>
      <p class="who"></p>
      <div class="row"><button class="btn primary yes">Sign in</button><button class="btn no">Cancel</button></div>
    </div>
    <button class="btn retry" hidden>Try again</button>`;
  const $ = <T extends HTMLElement>(s: string) => el.querySelector(s) as T;
  let port = api.runtime.connect({ name: 'pair' });
  let gone = false;
  let preferQr = !isAndroid();
  let last: PairView = { status: '' };

  const draw = (v: PairView) => {
    last = v;
    const showing = !!v.uri && !v.confirm && !v.error && !v.done;
    const waiting = !v.uri && !v.confirm && !v.error && !v.done && !v.busy;
    // The code's square is there from the start, so nothing jumps when the code arrives.
    $('.qr').hidden = !((showing || waiting) && preferQr);
    if (waiting) { $('.qr').innerHTML = ''; delete $('.qr').dataset.uri; }
    if (showing && preferQr && $('.qr').dataset.uri !== v.uri) { $('.qr').innerHTML = qrSvg(v.uri!); $('.qr').dataset.uri = v.uri!; }
    $<HTMLAnchorElement>('.open').hidden = !(showing && isAndroid() && !preferQr);
    if (v.uri) $<HTMLAnchorElement>('.open').href = v.uri;
    $('.show-qr').hidden = !(showing && isAndroid());
    $('.show-qr').textContent = preferQr ? 'Marble is on this phone' : 'Show a code instead';
    $('.status').textContent = v.error ?? (!showing ? v.status : preferQr ? 'Scan this with Marble on your phone.' : (v.note ?? 'Tap Open Marble, then Connect.'));
    $('.status').classList.toggle('error', !!v.error);
    $('.confirm').hidden = !v.confirm;
    if (v.confirm) $('.who').textContent = `Sign in as ${v.confirm}?`;
    $('.retry').hidden = !v.error;
    onResize();
    if (v.done) onDone();
  };

  // If the background was restarted, the code it showed is dead: reconnect, which
  // shows a fresh one. While connected, a ping every 10 s keeps it awake.
  const wire = () => {
    port.onMessage.addListener((m: any) => { if (m.type === 'pair') draw(m.view); });
    port.onDisconnect.addListener(() => { if (!gone) setTimeout(() => { port = api.runtime.connect({ name: 'pair' }); wire(); port.postMessage({ type: 'start' }); }, 300); });
  };
  wire();
  port.postMessage({ type: 'start' });
  const ping = setInterval(() => { try { port.postMessage({ type: 'ping' }); } catch { /* reconnecting */ } }, 10_000);
  $('.yes').onclick = () => port.postMessage({ type: 'accept' });
  $('.no').onclick = () => port.postMessage({ type: 'decline' });
  $('.retry').onclick = () => port.postMessage({ type: 'retry' });
  $('.show-qr').onclick = () => { preferQr = !preferQr; draw(last); };
  let handedOff = false;
  $('.open').addEventListener('click', () => { handedOff = true; port.postMessage({ type: 'by-link' }); });
  document.addEventListener('visibilitychange', () => { if (handedOff && document.visibilityState === 'visible') port.postMessage({ type: 'returned' }); });
  return () => { gone = true; clearInterval(ping); port.disconnect(); };
}

/**
 * The way in, for a card or the popup: **Create account** first (a name, then this
 * browser and a few servers make your key), **Sign in with your phone** below it for
 * people who already use Cosign.
 */
export function mountSignIn(el: HTMLElement, onResize: () => void = () => {}) {
  el.innerHTML = `
    <div class="choose">
      <button class="btn primary create" style="width:100%">Create account</button>
      <button class="btn phone" style="width:100%;margin-top:8px">Sign in with Marble</button>
    </div>
    <form class="new" hidden>
      <input class="name" maxlength="40" autocomplete="name" placeholder="Your name" style="width:100%;font:inherit;padding:10px 12px;border:1px solid var(--line);border-radius:10px;background:var(--soft);color:var(--fg)">
      <button class="btn primary go" type="submit" style="width:100%;margin-top:8px">Create account</button>
      <p class="status muted"></p>
      <button class="link back" type="button">Back</button>
    </form>
    <div class="by-phone" hidden></div>`;
  const $ = <T extends HTMLElement>(s: string) => el.querySelector(s) as T;
  let unmount: (() => void) | undefined;
  const show = (which: 'choose' | 'new' | 'by-phone') => {
    for (const c of ['choose', 'new', 'by-phone']) $(`.${c}`).hidden = c !== which;
    onResize();
  };
  $('.phone').onclick = () => { show('by-phone'); unmount ??= mountPairing($('.by-phone'), () => {}, onResize); };
  $('.create').onclick = () => { show('new'); $<HTMLInputElement>('.name').focus(); };
  $('.back').onclick = () => show('choose');

  let port: chrome.runtime.Port | undefined;
  $<HTMLFormElement>('.new').onsubmit = e => {
    e.preventDefault();
    const name = $<HTMLInputElement>('.name').value.trim();
    if (!name) return $<HTMLInputElement>('.name').focus();
    $<HTMLButtonElement>('.go').disabled = true; $('.back').hidden = true;
    port ??= api.runtime.connect({ name: 'create' });
    port.onMessage.addListener((m: any) => {
      if (m.type !== 'create') return;
      $('.status').textContent = m.done ? 'You’re in.' : m.text;
      $('.status').classList.toggle('error', !!m.error);
      if (m.error) { $<HTMLButtonElement>('.go').disabled = false; $('.back').hidden = false; }
      onResize();
    });
    setInterval(() => { try { port?.postMessage({ type: 'ping' }); } catch { /* closed */ } }, 10_000);
    port.postMessage({ type: 'create', name });
  };
  return () => unmount?.();
}
