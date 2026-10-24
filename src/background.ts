// The extension's brain. It holds this browser's pairing with your servers, a grant per
// website, and the pairing code while one is showing.
//
// Phase 1: no piece of your key lives here. This browser pairs like a site would (your
// phone scans its code), and every website then gets a grant of its own under it, so
// your phone lists "Firefox on Mac" inside each site and can remove one or all.
// If your server is older and can't add grants under the extension, every site shares
// the extension's own grant instead.

import { Store, Transfer, browserEnv } from '../vendor/qrst-web/index.ts';
import { verifyEvent, type Event } from 'nostr-tools/pure';
import { SimplePool } from 'nostr-tools/pool';
import { signerTicket, type Ticket } from './ticket.ts';
import { Link, newKey } from './link.ts';
import { api, deviceName } from './env.ts';

const DEFAULT_RELAYS = ['wss://syb.lol', 'wss://nos.lol', 'wss://relay.primal.net'];
const BASE_URL = 'https://cosign.aaro.cc/';

interface Pairing {
  sk: string;
  signer: string;
  relays: string[];
  pubkey: string;
  /** What your phone calls this browser. */
  name: string;
  /** The origin this browser claimed in its code, which older servers list it by. */
  claim?: string;
  /** Whether your server can hold a grant per website under this one. */
  perSite?: boolean;
  profile?: { name?: string; picture?: string };
}
interface Site { sk?: string; added: number; lastUsed: number }
type Sites = Record<string, Site>;

// --- Storage --------------------------------------------------------------------

async function load(): Promise<{ pairing?: Pairing; sites: Sites }> {
  const r = await api.storage.local.get(['pairing', 'sites']) as { pairing?: Pairing; sites?: Sites };
  return { pairing: r.pairing, sites: r.sites ?? {} };
}
const savePairing = (p: Pairing | undefined) => p ? api.storage.local.set({ pairing: p }) : api.storage.local.remove('pairing');
const saveSites = (s: Sites) => api.storage.local.set({ sites: s });

/** Sites you said "Not now" to, until the browser closes. */
async function notNow(): Promise<string[]> {
  try { return ((await api.storage.session.get('notNow')) as { notNow?: string[] }).notNow ?? []; } catch { return memNotNow; }
}
let memNotNow: string[] = [];
async function setNotNow(list: string[]) {
  memNotNow = list;
  try { await api.storage.session.set({ notNow: list }); } catch { /* older browser */ }
}

// --- The connection to your servers -------------------------------------------

let link: Link | undefined;
function linkFor(p: Pairing): Link {
  if (!link || link.signer !== p.signer) { link?.close(); link = new Link(p.signer, p.relays); }
  link.add(p.sk);
  return link;
}

// Servers since Cosign 0.5.0 start every error with a code; older ones only had English.
const removedErr = (e: unknown) => /^(revoked|not-connected):|removed|not connected/i.test(String((e as Error)?.message ?? e));

async function signOutLocally() {
  link?.close(); link = undefined;
  await savePairing(undefined);
  await saveSites({});
  changed();
}

/** Make sure `origin` has a grant. Returns the key it signs with. */
async function grantFor(p: Pairing, sites: Sites, origin: string): Promise<string> {
  const l = linkFor(p);
  const have = sites[origin];
  if (have) return have.sk ?? p.sk;
  let sk: string | undefined;
  if (p.perSite !== false) {
    const child = newKey();
    const pk = l.add(child);
    try {
      await l.call(l.add(p.sk), 'cosign_add_site', [pk, origin, p.name], 30_000);
      sk = child;
      if (!p.perSite) { p.perSite = true; await savePairing(p); }
    } catch (e) {
      if (/unsupported method/i.test(String((e as Error).message))) { p.perSite = false; await savePairing(p); }
      else if (removedErr(e)) { await signOutLocally(); throw new Error('This browser was removed on your phone. Sign in again.'); }
      else throw e;
    }
  }
  sites[origin] = { sk, added: Date.now(), lastUsed: Date.now() };
  await saveSites(sites);
  changed();
  return sk ?? p.sk;
}

async function forgetSite(origin: string, tellServer: boolean) {
  const { pairing, sites } = await load();
  const s = sites[origin];
  if (!s) return;
  delete sites[origin];
  await saveSites(sites);
  changed();
  if (tellServer && pairing && s.sk) {
    const l = linkFor(pairing);
    l.call(l.add(s.sk), 'logout', [], 20_000).catch(() => {});
  }
}

async function signOut() {
  const { pairing, sites } = await load();
  if (pairing) {
    const l = linkFor(pairing);
    const keys = [...Object.values(sites).flatMap(s => s.sk ? [s.sk] : []), pairing.sk];
    await Promise.allSettled(keys.map(k => l.call(l.add(k), 'logout', [], 15_000)));
  }
  await signOutLocally();
}

// --- NIP-07 ---------------------------------------------------------------------

let busy = 0;
function setBusy(d: number) {
  busy += d;
  api.action.setBadgeText({ text: busy > 0 ? '…' : '' }).catch?.(() => {});
}

async function nip07(origin: string, tabId: number | undefined, method: string, params: any): Promise<unknown> {
  let { pairing, sites } = await load();

  if (!pairing) {
    if ((await notNow()).includes(origin)) throw new Error('Not signed in.');
    await ask(tabId, origin, 'pair');
    ({ pairing, sites } = await load());
    if (!pairing) throw new Error('Not signed in.');
  } else if (!sites[origin]) {
    if ((await notNow()).includes(origin)) throw new Error('You said not now to this site.');
    await ask(tabId, origin, 'allow');
  }

  if (method === 'getPublicKey') {
    if (!sites[origin]) await grantFor(pairing, sites, origin).catch(() => {});
    return pairing.pubkey;
  }
  if (method === 'getRelays') return {};

  const sk = await grantFor(pairing, sites, origin);
  const l = linkFor(pairing);
  const pk = l.add(sk);
  setBusy(1);
  try {
    const call = (m: string, p: string[]) => l.call(pk, m, p);
    let result: unknown;
    switch (method) {
      case 'signEvent': {
        const t = params.event ?? params;
        const ev = JSON.parse(await call('sign_event', [JSON.stringify({ created_at: Math.floor(Date.now() / 1000), tags: [], content: '', ...t })])) as Event;
        if (!verifyEvent(ev) || ev.pubkey !== pairing.pubkey || ev.kind !== t.kind || ev.content !== (t.content ?? ''))
          throw new Error('Your signer returned something other than what was asked.');
        result = ev;
        break;
      }
      case 'nip04.encrypt': result = await call('nip04_encrypt', [params.peer, params.plaintext]); break;
      case 'nip04.decrypt': result = await call('nip04_decrypt', [params.peer, params.ciphertext]); break;
      case 'nip44.encrypt': result = await call('nip44_encrypt', [params.peer, params.plaintext]); break;
      case 'nip44.decrypt': result = await call('nip44_decrypt', [params.peer, params.ciphertext]); break;
      default: throw new Error(`Not supported: ${method}`);
    }
    const s = (await load()).sites[origin];
    if (s) { s.lastUsed = Date.now(); sites[origin] = s; await saveSites({ ...(await load()).sites, [origin]: s }); }
    return result;
  } catch (e) {
    if (removedErr(e)) {
      if (sites[origin]?.sk) {
        await forgetSite(origin, false);
        // Was it just this site, or this whole browser?
        const alive = await l.call(l.add(pairing.sk), 'get_public_key', [], 20_000).then(() => true, err => !removedErr(err));
        if (alive) throw new Error(`${host(origin)} was removed on your phone.`);
      }
      await signOutLocally();
      throw new Error('This browser was removed on your phone. Sign in again.');
    }
    throw e;
  } finally { setBusy(-1); }
}

const host = (origin: string) => { try { return new URL(origin).host; } catch { return origin; } };

// --- Asking you, in the page ----------------------------------------------------
//
// A small card drawn over the page (an extension page in a frame the site can't
// reach). One card per tab and site; every request waiting on it shares the answer.

interface Ask { id: string; tabId?: number; origin: string; kind: 'pair' | 'allow'; done: Promise<void>; resolve(): void; reject(e: Error): void }
const asks = new Map<string, Ask>();

function ask(tabId: number | undefined, origin: string, kind: 'pair' | 'allow'): Promise<void> {
  const existing = [...asks.values()].find(a => a.tabId === tabId && a.origin === origin && a.kind === kind);
  if (existing) return existing.done;
  if (tabId === undefined) return Promise.reject(new Error('Not signed in.'));
  const id = newKey().slice(0, 32);
  let resolve!: () => void, reject!: (e: Error) => void;
  const done = new Promise<void>((y, n) => { resolve = y; reject = n; });
  const a: Ask = { id, tabId, origin, kind, done, resolve, reject };
  asks.set(id, a);
  const close = () => { asks.delete(id); api.tabs.sendMessage(tabId, { type: 'card', url: null, id }).catch(() => {}); };
  done.then(close, close);
  api.tabs.sendMessage(tabId, { type: 'card', id, url: api.runtime.getURL(`card.html#${id}`) }).catch(() => reject(new Error('Couldn’t show the sign-in card on this page.')));
  return done;
}

async function answer(id: string, yes: boolean) {
  const a = asks.get(id);
  if (!a) return;
  if (yes) {
    if (a.kind === 'allow') {
      const { pairing, sites } = await load();
      if (pairing) await grantFor(pairing, sites, a.origin).catch(e => a.reject(e));
    }
    a.resolve();
  } else {
    await setNotNow([...new Set([...(await notNow()), a.origin])]);
    a.reject(new Error('You said not now.'));
  }
}

// --- Pairing ----------------------------------------------------------------------
//
// One pairing code at a time, shared by every card and the toolbar popup showing it.

interface PairView { uri?: string; status: string; confirm?: string; error?: string; done?: boolean }
let pairing: { t: Transfer<Ticket>; view: PairView } | undefined;
const pairPorts = new Set<chrome.runtime.Port>();

function pairEmit() { for (const p of pairPorts) p.postMessage({ type: 'pair', view: pairing?.view ?? { status: '' } }); }

function startPairing() {
  if (pairing && !pairing.view.error && !pairing.view.done) return pairEmit();
  const store = new Store(undefined, () => Math.floor(Date.now() / 1000), [], DEFAULT_RELAYS);
  const t = Transfer.show({ profile: signerTicket, store, env: browserEnv(), baseUrl: BASE_URL, origin: originClaim(), check: 'none' }, 'receiver');
  const view: PairView = { status: 'Getting a code…' };
  pairing = { t, view };
  let finished = false;
  t.onChange = () => {
    const v = t.view();
    if (v.uri && v.uri !== view.uri) { view.uri = v.uri; view.status = ''; }
    view.confirm = v.session.phase === 'accept' && v.session.rendering ? v.session.rendering : undefined;
    if (v.stage === 'ended' && !finished) {
      finished = true;
      if (v.value) void finish(v.value);
      else { view.error = v.session.outcome === 'second-responder' ? 'Two phones answered that code, so nothing was sent. Try again.' : 'Not signed in. Try again.'; view.uri = undefined; }
    }
    pairEmit();
  };
  pairEmit();
}

async function finish(tk: Ticket) {
  const view = pairing!.view;
  view.status = 'Signing in through your server…'; view.confirm = undefined; pairEmit();
  try {
    const p: Pairing = { sk: newKey(), signer: tk.signer, relays: tk.relays, pubkey: tk.pubkey, name: deviceName(), claim: originClaim() };
    const l = linkFor(p);
    const pk = l.add(p.sk);
    await l.call(pk, 'connect', [p.signer, tk.t], 30_000);
    p.pubkey = await l.call(pk, 'get_public_key', []);
    await savePairing(p);
    await setNotNow([]);
    view.done = true; view.status = 'Signed in.';
    pairEmit();
    changed();
    void fetchProfile(p);
    // Every site that was waiting on this sign-in goes ahead: you signed in from it.
    for (const a of asks.values()) if (a.kind === 'pair') { await grantFor(p, (await load()).sites, a.origin).catch(() => {}); a.resolve(); }
  } catch (e) {
    view.error = (e as Error).message; view.uri = undefined; pairEmit();
  }
}

/** QRST wants an http(s) origin; this names the browser so your phone can show it. */
function originClaim() {
  return `https://${deviceName().toLowerCase().replace(/[^a-z0-9]+/g, '-')}.extension`;
}

async function fetchProfile(p: Pairing) {
  const pool = new SimplePool();
  try {
    const ev = await pool.get([...new Set([...p.relays, ...DEFAULT_RELAYS, 'wss://purplepag.es'])], { kinds: [0], authors: [p.pubkey] }, { maxWait: 4000 });
    if (!ev) return;
    const m = JSON.parse(ev.content);
    const cur = (await load()).pairing;
    if (cur?.pubkey === p.pubkey) { cur.profile = { name: m.display_name || m.name, picture: m.picture }; await savePairing(cur); changed(); }
  } catch { /* no profile is fine */ } finally { pool.destroy(); }
}

// --- The popup ------------------------------------------------------------------

const popupPorts = new Set<chrome.runtime.Port>();
async function state() {
  const { pairing: p, sites } = await load();
  return {
    type: 'state',
    signedIn: !!p,
    name: p?.perSite === false && p.claim ? new URL(p.claim).host : p?.name,
    pubkey: p?.pubkey,
    profile: p?.profile,
    perSite: p?.perSite,
    sites: Object.entries(sites).map(([origin, s]) => ({ origin, lastUsed: s.lastUsed })).sort((a, b) => b.lastUsed - a.lastUsed),
    notNow: await notNow(),
  };
}
function changed() { void state().then(s => { for (const p of popupPorts) p.postMessage(s); }); }

// --- Wiring -----------------------------------------------------------------------

const isOurs = (sender: chrome.runtime.MessageSender) => sender.id === api.runtime.id && (sender.url ?? '').startsWith(api.runtime.getURL(''));

api.runtime.onMessage.addListener((msg: any, sender: chrome.runtime.MessageSender, reply: (r: unknown) => void) => {
  if (msg?.type === 'nip07') {
    if (isOurs(sender)) return false;
    const origin = sender.origin ?? (sender.url ? new URL(sender.url).origin : '');
    if (!/^https?:\/\//.test(origin)) { reply({ error: 'This page can’t sign.' }); return false; }
    nip07(origin, sender.tab?.id, String(msg.method), msg.params ?? {}).then(result => reply({ result }), e => reply({ error: (e as Error).message ?? String(e) }));
    return true;
  }
  if (!isOurs(sender)) return false;
  switch (msg?.type) {
    case 'card-info': { const a = asks.get(msg.id); reply(a ? { kind: a.kind, origin: a.origin } : null); return false; }
    case 'card-answer': void answer(msg.id, !!msg.yes); return false;
    case 'remove-site': void forgetSite(msg.origin, true); return false;
    case 'allow-site': void (async () => { await setNotNow((await notNow()).filter(o => o !== msg.origin)); const { pairing: p, sites } = await load(); if (p) await grantFor(p, sites, msg.origin).catch(() => {}); changed(); })(); return false;
    case 'sign-out': void signOut(); return false;
  }
  return false;
});

api.runtime.onConnect.addListener((port: chrome.runtime.Port) => {
  if (!isOurs(port.sender!)) return port.disconnect();
  if (port.name === 'pair') {
    pairPorts.add(port);
    port.onDisconnect.addListener(() => pairPorts.delete(port));
    port.onMessage.addListener((m: any) => {
      if (m.type === 'start') startPairing();
      if (m.type === 'retry') { pairing?.t.dispose(); pairing = undefined; startPairing(); }
      if (m.type === 'accept') pairing?.t.accept();
      if (m.type === 'decline') pairing?.t.decline();
    });
    void load().then(({ pairing: p }) => p ? port.postMessage({ type: 'pair', view: { done: true, status: 'Signed in.' } }) : startPairing());
  }
  if (port.name === 'popup') {
    popupPorts.add(port);
    port.onDisconnect.addListener(() => popupPorts.delete(port));
    void state().then(s => port.postMessage(s));
  }
});
