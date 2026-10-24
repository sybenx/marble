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

export interface PairView { uri?: string; status: string; confirm?: string; error?: string; done?: boolean }

/** Draw the pairing into `el`. Calls `onDone` once signed in. */
export function mountPairing(el: HTMLElement, onDone: () => void, onResize: () => void = () => {}) {
  el.innerHTML = `
    <div class="qr" hidden></div>
    <a class="btn primary open" hidden target="_blank" rel="noopener">Open Cosign</a>
    <button class="link show-qr" hidden>Show a code instead</button>
    <p class="status muted"></p>
    <div class="confirm" hidden>
      <p class="who"></p>
      <div class="row"><button class="btn primary yes">Sign in</button><button class="btn no">Cancel</button></div>
    </div>
    <button class="btn retry" hidden>Try again</button>`;
  const $ = <T extends HTMLElement>(s: string) => el.querySelector(s) as T;
  const port = api.runtime.connect({ name: 'pair' });
  let preferQr = !isAndroid();
  let last: PairView = { status: '' };

  const draw = (v: PairView) => {
    last = v;
    const showing = !!v.uri && !v.confirm && !v.error && !v.done;
    $('.qr').hidden = !(showing && preferQr);
    if (showing && preferQr && $('.qr').dataset.uri !== v.uri) { $('.qr').innerHTML = qrSvg(v.uri!); $('.qr').dataset.uri = v.uri!; }
    $<HTMLAnchorElement>('.open').hidden = !(showing && isAndroid() && !preferQr);
    if (v.uri) $<HTMLAnchorElement>('.open').href = v.uri;
    $('.show-qr').hidden = !(showing && isAndroid());
    $('.show-qr').textContent = preferQr ? 'Cosign is on this phone' : 'Show a code instead';
    $('.status').textContent = v.error ?? (!showing ? v.status : preferQr ? 'Scan this with Cosign on your phone.' : 'Tap Open Cosign, then Connect.');
    $('.status').classList.toggle('error', !!v.error);
    $('.confirm').hidden = !v.confirm;
    if (v.confirm) $('.who').textContent = `Sign in as ${v.confirm}?`;
    $('.retry').hidden = !v.error;
    onResize();
    if (v.done) onDone();
  };

  port.onMessage.addListener((m: any) => { if (m.type === 'pair') draw(m.view); });
  port.postMessage({ type: 'start' });
  $('.yes').onclick = () => port.postMessage({ type: 'accept' });
  $('.no').onclick = () => port.postMessage({ type: 'decline' });
  $('.retry').onclick = () => port.postMessage({ type: 'retry' });
  $('.show-qr').onclick = () => { preferQr = !preferQr; draw(last); };
  return () => port.disconnect();
}
