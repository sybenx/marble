// The bridge: window.nostr in the page talks to the extension through here, and the
// extension draws its sign-in card over the page from here.

import { api, isNarrow } from './env.ts';

const TAG = '__cosign';

// window.nostr has to exist before the site looks for it.
const s = document.createElement('script');
s.src = api.runtime.getURL('inpage.js');
s.onload = () => s.remove();
(document.head ?? document.documentElement).appendChild(s);

const target = () => (location.origin === 'null' ? '*' : location.origin);

window.addEventListener('message', e => {
  if (e.source !== window || e.data?.[TAG] !== 'req') return;
  const { id, method, params } = e.data;
  api.runtime.sendMessage({ type: 'nip07', method, params })
    .then((r: any) => window.postMessage({ [TAG]: 'res', id, ...(r ?? { error: 'No answer from Cosign.' }) }, target()))
    .catch((err: Error) => window.postMessage({ [TAG]: 'res', id, error: err?.message ?? 'Cosign isn’t answering. Reload the page.' }, target()));
});

// --- The card -------------------------------------------------------------------
//
// An extension page in a frame, inside a closed shadow root. The site can't read it or
// click in it; it could still try to hide or cover it, so its look is put back if
// anything changes it, and the card's buttons wait a moment before they work.

let host: HTMLElement | undefined;
let frame: HTMLIFrameElement | undefined;
let guard: MutationObserver | undefined;
let shownId: string | undefined;

const phone = isNarrow();
const HOST_STYLE = [
  'all: initial', 'position: fixed', 'z-index: 2147483647', 'display: block', 'opacity: 1', 'visibility: visible',
  'pointer-events: auto', 'transform: none', 'filter: none', 'clip-path: none',
  ...(phone ? ['left: 0', 'right: 0', 'bottom: 0', 'width: 100%'] : ['top: 12px', 'right: 12px', 'width: 360px', 'max-width: calc(100vw - 24px)']),
  'height: 0', 'border: 0', 'margin: 0', 'padding: 0',
].join(' !important; ') + ' !important';

function showCard(id: string, url: string) {
  hideCard();
  shownId = id;
  host = document.createElement('div');
  const root = host.attachShadow({ mode: 'closed' });
  frame = document.createElement('iframe');
  frame.src = url;
  // The device lock (WebAuthn) for approvals, delegated to the card; a page whose own
  // Permissions-Policy forbids it will need the approval elsewhere (phase 2).
  frame.setAttribute('allow', 'clipboard-write; publickey-credentials-get; publickey-credentials-create');
  frame.style.cssText = `border:0;width:100%;height:100%;display:block;color-scheme:normal;${phone ? 'border-radius:16px 16px 0 0;' : 'border-radius:14px;'}box-shadow:0 8px 40px rgba(0,0,0,.28);background:transparent`;
  root.appendChild(frame);
  host.setAttribute('style', HOST_STYLE);
  document.documentElement.appendChild(host);
  guard = new MutationObserver(() => {
    if (host && host.getAttribute('style') !== currentStyle()) host.setAttribute('style', currentStyle());
    if (host && !host.isConnected) document.documentElement.appendChild(host);
  });
  guard.observe(host, { attributes: true });
  guard.observe(document.documentElement, { childList: true });
}

let height = 0;
const currentStyle = () => HOST_STYLE.replace('height: 0 !important', `height: ${height}px !important`);

function hideCard() {
  guard?.disconnect(); guard = undefined;
  host?.remove(); host = undefined; frame = undefined; shownId = undefined; height = 0;
}

// The card says how tall it is.
window.addEventListener('message', e => {
  if (!frame || e.source !== frame.contentWindow || typeof e.data?.cosignCardHeight !== 'number') return;
  height = Math.min(Math.max(0, e.data.cosignCardHeight), window.innerHeight - 24);
  host?.setAttribute('style', currentStyle());
});

api.runtime.onMessage.addListener((msg: any) => {
  if (msg?.type !== 'card') return;
  if (msg.url) showCard(msg.id, msg.url);
  else if (msg.id === shownId) hideCard();
});
