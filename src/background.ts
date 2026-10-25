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
import { nip19 } from 'nostr-tools';
import { SimplePool } from 'nostr-tools/pool';
import { signerTicket, type Ticket } from './ticket.ts';
import { Link, newKey } from './link.ts';
import { api, deviceName } from './env.ts';
import * as nip44 from 'nostr-tools/nip44';
import { getPublicKey } from 'nostr-tools/pure';
import { extract } from '@noble/hashes/hkdf.js';
import { sha256 } from '@noble/hashes/sha2.js';
import { hexToBytes, utf8ToBytes } from '@noble/hashes/utils.js';
import { Device, type MemberState } from './member/device.ts';
import { chooseServers, createAccount } from './member/create.ts';
import { makePending, unwrap, dealTo, type Pending } from './member/pending.ts';
import { finalizeEvent } from 'nostr-tools/pure';
import * as nip04lib from 'nostr-tools/nip04';
import type { Seed } from './servers.ts';

// nos.lol left out while it's down (2026-10-24); relays that fail are skipped anyway.
const DEFAULT_RELAYS = ['wss://syb.lol', 'wss://relay.primal.net'];
// Tried for the sign-in code when neither of those passes QRST's echo test; without them
// the code never appeared.
const FALLBACK_RELAYS = ['wss://nos.lol', 'wss://relay.damus.io'];
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

// --- Phase 2: this browser as a device holding part of your key -----------------
//
// When it is one, sites sign through the device (this browser + one server) instead of
// through the phase 1 pairing. Each website's grant key only names the grant.

let device: Device | undefined;
async function loadMember(): Promise<MemberState | undefined> {
  return ((await api.storage.local.get('member')) as { member?: MemberState }).member;
}
const saveMember = (m: MemberState) => api.storage.local.set({ member: m });
function deviceFor(m: MemberState): Device {
  if (!device || device.st.sk !== m.sk) {
    device?.close();
    device = new Device(m, st => { void saveMember(st).then(changed); });
    device.onJoinRequest = r => { for (const p of safetyPorts) p.postMessage({ type: 'join_request', ...r }); };
  }
  return device;
}
const safetyPorts = new Set<chrome.runtime.Port>();
const openSafety = (which: 'paper' | 'phone') => api.tabs.create({ url: api.runtime.getURL(`safety.html#${which}`) });

async function memberGrant(d: Device, origin: string): Promise<string> {
  const { sites } = await load();
  const have = sites[origin];
  if (have?.sk) return getPublicKey(hexToBytes(have.sk));
  const sk = newKey();
  sites[origin] = { sk, added: Date.now(), lastUsed: Date.now() };
  await saveSites(sites);
  const pk = getPublicKey(hexToBytes(sk));
  d.grant(pk, origin, deviceName());
  changed();
  return pk;
}

async function memberNip07(m: MemberState, origin: string, tabId: number | undefined, method: string, params: any): Promise<unknown> {
  if (m.removed) throw new Error('This browser was removed from your key, so it can’t sign any more.');
  const d = deviceFor(m);
  const { sites } = await load();
  if (!sites[origin]) {
    if ((await notNow()).includes(origin)) throw new Error('You said not now to this site.');
    await ask(tabId, origin, 'allow');
  }
  const grant = await memberGrant(d, origin);
  if (method === 'getPublicKey') return d.pubkey;
  if (method === 'getRelays') return {};
  // A grant just sent may not have reached the servers yet: try again for a few seconds.
  const fresh = Date.now() - ((await load()).sites[origin]?.added ?? 0) < 15_000;
  const retrying = async <T>(f: () => Promise<T>): Promise<T> => {
    for (let i = 0; ; i++) {
      try { return await f(); } catch (e) {
        if (!(fresh && i < 4 && /^revoked:/.test((e as Error).message))) throw e;
        await new Promise(r => setTimeout(r, 1500));
      }
    }
  };
  setBusy(1);
  try {
    let result: unknown;
    switch (method) {
      case 'signEvent': {
        const t = params.event ?? params;
        const tmpl = { kind: t.kind, content: t.content ?? '', tags: t.tags ?? [], created_at: t.created_at ?? Math.floor(Date.now() / 1000) };
        try { result = await retrying(() => d.sign(tmpl, grant)); }
        catch (e) {
          const held = /^needs-approval:\s*(.*)$/s.exec((e as Error).message);
          if (!held) throw e;
          await ask(tabId, origin, 'approve', held[1]);
          result = await d.sign(tmpl, grant, true);
        }
        break;
      }
      case 'nip44.encrypt': result = nip44.v2.encrypt(params.plaintext, extract(sha256, await retrying(() => d.ecdh(params.peer, grant)), utf8ToBytes('nip44-v2'))); break;
      case 'nip44.decrypt': result = nip44.v2.decrypt(params.ciphertext, extract(sha256, await retrying(() => d.ecdh(params.peer, grant)), utf8ToBytes('nip44-v2'))); break;
      case 'nip04.encrypt': result = await nip04(await retrying(() => d.ecdh(params.peer, grant)), params.plaintext, false); break;
      case 'nip04.decrypt': result = await nip04(await retrying(() => d.ecdh(params.peer, grant)), params.ciphertext, true); break;
      default: throw new Error(`Not supported: ${method}`);
    }
    const now = await load();
    if (now.sites[origin]) { now.sites[origin].lastUsed = Date.now(); await saveSites(now.sites); }
    return result;
  } catch (e) {
    if (/^revoked:/.test((e as Error).message)) { await forgetSite(origin, false); throw new Error(`${host(origin)} was removed.`); }
    throw new Error(String((e as Error).message).replace(/^(needs-approval|refused):\s*/, ''));
  } finally { setBusy(-1); }
}

/** NIP-04: AES-256-CBC keyed by the shared x coordinate. */
async function nip04(x: Uint8Array, text: string, decrypt: boolean): Promise<string> {
  const key = await crypto.subtle.importKey('raw', x as BufferSource, { name: 'AES-CBC' }, false, ['encrypt', 'decrypt']);
  const b64 = (u: Uint8Array) => btoa(String.fromCharCode(...u));
  const unb64 = (s: string) => Uint8Array.from(atob(s), c => c.charCodeAt(0));
  if (decrypt) {
    const [ct, iv] = text.split('?iv=');
    return new TextDecoder().decode(await crypto.subtle.decrypt({ name: 'AES-CBC', iv: unb64(iv) }, key, unb64(ct)));
  }
  const iv = crypto.getRandomValues(new Uint8Array(16));
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-CBC', iv }, key, new TextEncoder().encode(text)));
  return `${b64(ct)}?iv=${b64(iv)}`;
}

// --- Create account ---------------------------------------------------------------

let creating: Promise<void> | undefined;
function create(name: string, say: (s: string) => void): Promise<void> {
  return creating ??= (async () => {
    say('Finding servers…');
    // Tests (and, later, settings) can name servers to use alongside the ones found.
    const { devSeeds } = await api.storage.local.get('devSeeds') as { devSeeds?: Seed[] };
    const servers = await chooseServers(devSeeds ?? []).catch(() => []);
    // Servers that don't answer in time don't hold you up: the key is made here instead,
    // kept wrapped and used at once ("not protected yet"), and dealt to them later.
    let gaveUp = false;
    const made = await Promise.race([
      createAccount({ name, servers, onStatus: say, gaveUp: () => gaveUp }).catch(() => null),
      new Promise<null>(r => setTimeout(() => r(null), 30_000)),
    ]);
    gaveUp = !made;
    if (!made) {
      const p = await makePending(name);
      await savePending(p);
      await setNotNow([]);
      changed();
      await publishProfileLocally(p).catch(() => {});
      for (const a of asks.values()) if (a.kind === 'pair') { await pendingGrant(a.origin); a.resolve(); }
      void tryDeal();
      return;
    }
    const m = made;
    await saveMember(m);
    const d = deviceFor(m);
    await setNotNow([]);
    changed();
    say('Publishing your profile…');
    await publishProfile(m, d).catch(() => { /* the account works without it; try again later */ });
    // Every site that was waiting for you to sign in goes ahead: you started from it.
    const tabs = new Set<number>();
    for (const a of asks.values()) if (a.kind === 'pair') { await memberGrant(d, a.origin); a.resolve(); if (a.tabId !== undefined) tabs.add(a.tabId); }
    // The next setup step: a way back if this browser's data goes. Skippable; the popup
    // keeps a card up until it's done.
    if (!d.safe()) void api.tabs.create({ url: api.runtime.getURL('safety.html#paper&setup') });
    void tabs;
  })().finally(() => { creating = undefined; });
}

async function publishProfile(m: MemberState, d: Device) {
  if (!m.ownGrant) { m.ownGrant = newKey(); await saveMember(m); }
  const grant = getPublicKey(hexToBytes(m.ownGrant));
  d.grant(grant, 'Marble', deviceName());
  const content = JSON.stringify({ name: m.profile?.name, display_name: m.profile?.name, ...(m.profile?.picture ? { picture: m.profile.picture } : {}) });
  let ev;
  for (let i = 0; ; i++) {
    try { ev = await d.sign({ kind: 0, content, tags: [], created_at: Math.floor(Date.now() / 1000) }, grant); break; }
    catch (e) { if (i >= 5 || !/^revoked:/.test((e as Error).message)) throw e; await new Promise(r => setTimeout(r, 1500)); }
  }
  m.profileEvent = ev; await saveMember(m);
  // Tests sign it but don't post it to public relays.
  if (((await api.storage.local.get('devNoPublish')) as { devNoPublish?: boolean }).devNoPublish) return;
  const relays = [...new Set([...m.group.relays, ...DEFAULT_RELAYS])];
  const pool = new SimplePool();
  try { await Promise.any(pool.publish(relays, ev)); } finally { setTimeout(() => pool.destroy(), 5000); }
}

/** Testing: be added to an existing key by one of its devices (cosign:join code). */
function joinExisting(say: (s: string, extra?: object) => void): Promise<void> {
  return joining ??= Device.joinExisting(deviceName(), uri => say('Scan this with one of your devices.', { code: uri }), say).then(async m => {
    await saveMember(m);
    deviceFor(m);
    await setNotNow([]);
    changed();
  }).finally(() => { joining = undefined; });
}

// --- Not protected yet: a key made here, until servers take it -----------------------------

async function loadPending(): Promise<Pending | undefined> {
  return ((await api.storage.local.get('pending')) as { pending?: Pending }).pending;
}
const savePending = (p: Pending | undefined) => p ? api.storage.local.set({ pending: p }) : api.storage.local.remove('pending');

async function pendingGrant(origin: string) {
  const { sites } = await load();
  if (!sites[origin]) { sites[origin] = { added: Date.now(), lastUsed: Date.now() }; await saveSites(sites); changed(); }
}

/** Sign with the key this browser holds whole, until it's dealt. */
async function withKey<T>(p: Pending, f: (sk: Uint8Array) => T | Promise<T>): Promise<T> {
  const sk = await unwrap(p);
  try { return await f(sk); } finally { sk.fill(0); }
}

async function pendingNip07(p: Pending, origin: string, tabId: number | undefined, method: string, params: any): Promise<unknown> {
  void tryDeal();
  const { sites } = await load();
  if (!sites[origin]) {
    if ((await notNow()).includes(origin)) throw new Error('You said not now to this site.');
    await ask(tabId, origin, 'allow');
    await pendingGrant(origin);
  }
  if (method === 'getPublicKey') return p.pubkey;
  if (method === 'getRelays') return {};
  return withKey(p, sk => {
    switch (method) {
      case 'signEvent': { const t = params.event ?? params; return finalizeEvent({ kind: t.kind, content: t.content ?? '', tags: t.tags ?? [], created_at: t.created_at ?? Math.floor(Date.now() / 1000) }, sk); }
      case 'nip44.encrypt': return nip44.v2.encrypt(params.plaintext, nip44.v2.utils.getConversationKey(sk, params.peer));
      case 'nip44.decrypt': return nip44.v2.decrypt(params.ciphertext, nip44.v2.utils.getConversationKey(sk, params.peer));
      case 'nip04.encrypt': return nip04lib.encrypt(sk, params.peer, params.plaintext);
      case 'nip04.decrypt': return nip04lib.decrypt(sk, params.peer, params.ciphertext);
      default: throw new Error(`Not supported: ${method}`);
    }
  });
}

async function publishProfileLocally(p: Pending) {
  const ev = await withKey(p, sk => finalizeEvent({ kind: 0, content: JSON.stringify({ name: p.name, display_name: p.name }), tags: [], created_at: Math.floor(Date.now() / 1000) }, sk));
  if (((await api.storage.local.get('devNoPublish')) as { devNoPublish?: boolean }).devNoPublish) return;
  const pool = new SimplePool();
  try { await Promise.any(pool.publish(DEFAULT_RELAYS, ev)); } finally { setTimeout(() => pool.destroy(), 5000); }
}

/** Deal the pending key to servers as soon as two answer, become a device, wipe the key. */
let dealing: Promise<void> | undefined, lastTry = 0;
function tryDeal(): Promise<void> {
  if (dealing || Date.now() - lastTry < 20_000) return dealing ?? Promise.resolve();
  lastTry = Date.now();
  return dealing = (async () => {
    const p = await loadPending();
    if (!p) return;
    const { devSeeds } = await api.storage.local.get('devSeeds') as { devSeeds?: Seed[] };
    const servers = await chooseServers(devSeeds ?? []).catch(() => []);
    if (servers.length < 2) return;
    const m = await dealTo(p, servers).catch(() => null);
    if (!m) return;
    await saveMember(m);
    await savePending(undefined); // the whole key is gone from here: only this browser's 3 shares remain
    deviceFor(m);
    changed();
    if (!deviceFor(m).safe()) void api.tabs.create({ url: api.runtime.getURL('safety.html#paper&setup') });
  })().finally(() => { dealing = undefined; });
}
api.alarms?.create?.('deal', { periodInMinutes: 1 });
api.alarms?.onAlarm?.addListener((a: { name: string }) => { if (a.name === 'deal') { void tryDeal(); void nag(); } });
/** Still not protected after a day: a "!" on the toolbar icon until it is. */
async function nag() {
  const p = await loadPending();
  const action = api.action ?? (api as any).browserAction;
  if (p && Date.now() - p.since > 24 * 3600_000) action?.setBadgeText({ text: '!' })?.catch?.(() => {});
  else if (!p && busy === 0) action?.setBadgeText({ text: '' })?.catch?.(() => {});
}

let joining: Promise<void> | undefined;
function join(uri: string, status: (s: string) => void): Promise<void> {
  return joining ??= Device.join(uri, status).then(async m => {
    await saveMember(m);
    deviceFor(m);
    await setNotNow([]);
    changed();
  }).finally(() => { joining = undefined; });
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
  const m = await loadMember();
  if (tellServer && m && s.sk) deviceFor(m).forget(getPublicKey(hexToBytes(s.sk)));
  else if (tellServer && pairing && s.sk) {
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
  // `action` in the newer format; Firefox's build uses the older `browserAction`.
  const action = api.action ?? (api as any).browserAction;
  action?.setBadgeText({ text: busy > 0 ? '…' : '' })?.catch?.(() => {});
}

async function nip07(origin: string, tabId: number | undefined, method: string, params: any): Promise<unknown> {
  const member = await loadMember();
  if (member) return memberNip07(member, origin, tabId, method, params);
  const pending = await loadPending();
  if (pending) return pendingNip07(pending, origin, tabId, method, params);
  let { pairing, sites } = await load();

  if (!pairing) {
    if ((await notNow()).includes(origin)) throw new Error('Not signed in.');
    startPairing(); // the code is ready by the time you tap Sign in with Marble
    await ask(tabId, origin, 'pair');
    // Signed in by phone (a pairing), or made an account here (a member).
    const made = await loadMember();
    if (made) return memberNip07(made, origin, tabId, method, params);
    const madeHere = await loadPending();
    if (madeHere) return pendingNip07(madeHere, origin, tabId, method, params);
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

interface Ask { id: string; tabId?: number; origin: string; kind: 'pair' | 'allow' | 'approve' | 'notice' | 'backup'; detail?: string; window?: number; tab?: number; answered?: boolean; done: Promise<void>; resolve(): void; reject(e: Error): void }
const asks = new Map<string, Ask>();
/** Tabs whose page said it's on a phone-sized screen. */
const narrowTabs = new Set<number>();
// Closing the window without choosing is "Not now".
api.windows?.onRemoved?.addListener((w: number) => { for (const a of asks.values()) if (a.window === w && !a.answered) void answer(a.id, false); });
api.tabs?.onRemoved?.addListener((t: number) => { for (const a of asks.values()) if (a.tab === t && !a.answered) void answer(a.id, false); });
/** Tests: the hardened in-page card instead of a window or tab (dev flag `devInPageCard`). */
let forceInPage = false;
api.storage.local.get('devInPageCard').then((r: any) => { forceInPage = !!r.devInPageCard; }).catch(() => {});
api.storage.onChanged.addListener((c: any) => { if (c.devInPageCard) forceInPage = !!c.devInPageCard.newValue; });

function ask(tabId: number | undefined, origin: string, kind: 'pair' | 'allow' | 'approve' | 'notice' | 'backup', detail?: string): Promise<void> {
  // Approvals and notices are one each; sign-in and Allow are one per tab and site.
  const existing = kind !== 'approve' && kind !== 'notice' && kind !== 'backup' && [...asks.values()].find(a => a.tabId === tabId && a.origin === origin && a.kind === kind);
  if (existing) return existing.done;
  if (tabId === undefined) return Promise.reject(new Error('Not signed in.'));
  const id = newKey().slice(0, 32);
  let resolve!: () => void, reject!: (e: Error) => void;
  const done = new Promise<void>((y, n) => { resolve = y; reject = n; });
  const a: Ask = { id, tabId, origin, kind, detail, done, resolve, reject };
  asks.set(id, a);
  // Allow and approvals are decided where the page can't cover, fade or move them under
  // your pointer: a window of the extension's own on a computer, a tab of its own on a
  // phone (then back to the site). The page only gets a pointer. If neither can be opened,
  // the in-page card, hardened, is the fallback.
  const decision = kind === 'allow' || kind === 'approve';
  const own = decision && !narrowTabs.has(tabId) && !!api.windows?.create && !forceInPage;
  const ownTab = decision && narrowTabs.has(tabId) && !!api.tabs?.create && !forceInPage;
  let win: number | undefined, tab: number | undefined;
  const close = () => {
    asks.delete(id);
    api.tabs.sendMessage(tabId, { type: 'card', url: null, id }).catch(() => {});
    if (win !== undefined) api.windows.remove(win).catch(() => {});
    if (tab !== undefined) { api.tabs.remove(tab).catch(() => {}); api.tabs.update(tabId, { active: true }).catch(() => {}); }
  };
  done.then(close, close);
  const inPage = (hash: string) => api.tabs.sendMessage(tabId, { type: 'card', id, url: api.runtime.getURL(`card.html#${hash}`) }).catch(() => reject(new Error('Couldn’t show the sign-in card on this page.')));
  if (ownTab) {
    void (async () => {
      try {
        const t = await api.tabs.create({ url: api.runtime.getURL(`card.html#${id}&window`), active: true, openerTabId: tabId });
        tab = t?.id;
        a.tab = tab;
        void inPage(`${id}&pointer&tab`);
      } catch { void inPage(id); }
    })();
    return done;
  }
  if (!own) { void inPage(id); return done; }
  void (async () => {
    try {
      const cur = await api.windows.getLastFocused().catch(() => undefined);
      const base = { url: api.runtime.getURL(`card.html#${id}&window`), type: 'popup' as const, focused: true, width: 400, height: kind === 'approve' ? 300 : 260 };
      // Near the top right of the browser window; wherever the system likes if that's off screen.
      const near = cur?.left !== undefined && cur.width ? { left: Math.max(0, cur.left + cur.width - 420), top: (cur.top ?? 0) + 80 } : {};
      const w = await api.windows.create({ ...base, ...near }).catch(() => api.windows.create(base));
      win = w?.id;
      a.window = win;
      void inPage(`${id}&pointer`);
    } catch { void inPage(id); } // no windows here after all: the hardened card
  })();
  return done;
}

async function answer(id: string, yes: boolean) {
  const a = asks.get(id);
  if (!a || a.answered) return;
  a.answered = true; // the first answer stands (a window closing after a tap isn't a second one)
  if (yes) {
    if (a.kind === 'allow') {
      const { pairing, sites } = await load();
      const m = await loadMember();
      if (m) await memberGrant(deviceFor(m), a.origin);
      else if (pairing) await grantFor(pairing, sites, a.origin).catch(e => a.reject(e));
    }
    a.resolve();
  } else if (a.kind === 'backup') {
    a.resolve(); // Later
  } else if (a.kind === 'notice') {
    // "Not you? Sign out"
    a.resolve();
    await signOut();
  } else if (a.kind === 'approve') {
    a.reject(new Error('You didn’t approve it.'));
  } else {
    await setNotNow([...new Set([...(await notNow()), a.origin])]);
    a.reject(new Error('You said not now.'));
  }
}

// --- Pairing ----------------------------------------------------------------------
//
// One pairing code at a time, shared by every card and the toolbar popup showing it.

/** `busy`: you said Sign in and it's under way; the code is spent and isn't shown again. */
interface PairView { uri?: string; status: string; confirm?: string; error?: string; done?: boolean; busy?: boolean; note?: string }
/**
 * `byLink`: the code went to Cosign on this same phone by the Open Cosign button, not
 * shown for a camera. Then there's no "Sign in as …?" here: the code was never on a
 * screen anyone could photograph, and you just said Connect in Cosign.
 */
let pairing: { t: Transfer<Ticket>; view: PairView; byLink?: boolean } | undefined;
const pairPorts = new Set<chrome.runtime.Port>();

function pairEmit() { for (const p of pairPorts) p.postMessage({ type: 'pair', view: pairing?.view ?? { status: '' } }); }

function startPairing() {
  if (pairing && !pairing.view.error && !pairing.view.done) return pairEmit();
  const store = new Store(undefined, () => Math.floor(Date.now() / 1000), FALLBACK_RELAYS, DEFAULT_RELAYS);
  // Shows the code 0.4 s after the first relay passes, not QRST's 1.2 s: a second relay
  // that answers in that time is still listed, and a slow one no longer holds up the code.
  const t = Transfer.show({ profile: signerTicket, store, env: browserEnv(), baseUrl: BASE_URL, origin: originClaim(), check: 'none', timing: { graceMs: 400 } }, 'receiver');
  const view: PairView = { status: 'Getting a code…' };
  pairing = { t, view };
  // QRST keeps trying relays until one works; say so (with Retry) instead of "Getting a
  // code…" for ever. The code is drawn the moment one arrives.
  const me = pairing;
  const slow = () => {
    if (pairing !== me || view.uri || view.error || view.done || view.busy) return;
    Object.assign(view, { status: 'Can’t reach the network — retrying…', offline: true });
    pairEmit();
  };
  setTimeout(slow, navigator.onLine === false ? 0 : 6000);
  let finished = false, contacted: ReturnType<typeof setTimeout> | undefined;
  t.onChange = () => {
    const v = t.view();
    // A phone has reached the code: its answer should follow within seconds. If it
    // doesn't, say so rather than sit there.
    if (v.session.phase !== 'waiting' && v.session.phase !== 'accept' && !contacted && !finished) {
      contacted = setTimeout(() => {
        if (finished || view.confirm || view.busy || view.done) return;
        finished = true; t.dispose();
        view.error = 'Your phone’s answer didn’t arrive. Try again.'; view.uri = undefined;
        pairEmit();
      }, 20_000);
    }
    if (v.uri && v.uri !== view.uri && !view.busy) { view.uri = v.uri; view.status = ''; view.offline = undefined; }
    view.confirm = !view.busy && v.session.phase === 'accept' && v.session.rendering ? v.session.rendering : undefined;
    if (view.confirm && pairing?.byLink) {
      Object.assign(view, { busy: true, uri: undefined, confirm: undefined, status: 'Signing in…' });
      t.accept();
    }
    if (v.stage === 'ended' && !finished) {
      finished = true;
      if (v.value) void finish(v.value);
      else { view.error = v.session.outcome === 'second-responder' ? 'Two phones answered that code, so nothing was sent. Try again.' : `Not signed in (${v.session.outcome ?? 'ended'}). Try again.`; view.uri = undefined; }
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
    const byLink = !!pairing?.byLink;
    // With no "Sign in as …?" (same phone), say who you are once you're in.
    const profile = byLink ? Promise.race([fetchProfile(p), new Promise(r => setTimeout(r, 3000))]) : fetchProfile(p);
    if (!byLink) void profile;
    // Every site that was waiting on this sign-in goes ahead: you signed in from it.
    const tabs = new Set<number>();
    for (const a of asks.values()) if (a.kind === 'pair') { await grantFor(p, (await load()).sites, a.origin).catch(() => {}); a.resolve(); if (a.tabId !== undefined) tabs.add(a.tabId); }
    if (byLink) {
      await profile;
      const cur = (await load()).pairing;
      const npub = nip19.npubEncode(p.pubkey);
      const who = cur?.profile?.name ?? `${npub.slice(0, 12)}…${npub.slice(-6)}`;
      for (const tab of tabs) void ask(tab, '', 'notice', who).catch(() => {});
    }
  } catch (e) {
    view.error = (e as Error).message; view.uri = undefined; view.busy = false; pairEmit();
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
  const m = await loadMember();
  const pend = !m ? await loadPending() : undefined;
  if (pend) void tryDeal();
  return {
    type: 'state',
    signedIn: !!p || !!m || !!pend,
    /** Made here while no servers answered: whole on this browser until they do. */
    pending: pend ? { since: pend.since, long: Date.now() - pend.since > 24 * 3600_000 } : undefined,
    /** Holds part of your key (phase 2), rather than signing through a pairing. */
    member: !!m,
    /** An account here with no other device and no paper: the popup keeps a card up. */
    unsafe: !!m && !m.removed && !deviceFor(m).safe(),
    /** Another device removed this browser from the key: final. */
    removed: m?.removed ? { by: m.removed.by } : undefined,
    name: m || pend ? deviceName() : p?.perSite === false && p.claim ? new URL(p.claim).host : p?.name,
    pubkey: m?.group.groupX ?? pend?.pubkey ?? p?.pubkey,
    profile: m?.profile ?? (pend ? { name: pend.name } : undefined) ?? p?.profile,
    perSite: p?.perSite,
    sites: Object.entries(sites).map(([origin, s]) => ({ origin, lastUsed: s.lastUsed })).sort((a, b) => b.lastUsed - a.lastUsed),
    notNow: await notNow(),
  };
}
function changed() { void state().then(s => { for (const p of popupPorts) p.postMessage(s); cachePopup(s); }); }
/** The popup draws this at once, before the background (maybe asleep) answers. */
function cachePopup(s: object) { void api.storage.local.set({ popupCache: s }).catch(() => {}); }

// --- Wiring -----------------------------------------------------------------------

const isOurs = (sender: chrome.runtime.MessageSender) => sender.id === api.runtime.id && (sender.url ?? '').startsWith(api.runtime.getURL(''));

api.runtime.onMessage.addListener((msg: any, sender: chrome.runtime.MessageSender, reply: (r: unknown) => void) => {
  if (msg?.type === 'nip07') {
    if (isOurs(sender)) return false;
    const origin = sender.origin ?? (sender.url ? new URL(sender.url).origin : '');
    if (!/^https?:\/\//.test(origin)) { reply({ error: 'This page can’t sign.' }); return false; }
    if (sender.tab?.id !== undefined) { if (msg.narrow) narrowTabs.add(sender.tab.id); else narrowTabs.delete(sender.tab.id); }
    nip07(origin, sender.tab?.id, String(msg.method), msg.params ?? {}).then(result => reply({ result }), e => reply({ error: (e as Error).message ?? String(e) }));
    return true;
  }
  if (!isOurs(sender)) return false;
  switch (msg?.type) {
    case 'card-info': { const a = asks.get(msg.id); reply(a ? { kind: a.kind, origin: a.origin, detail: a.detail } : null); return false; }
    case 'card-focus': {
      const a = asks.get(msg.id);
      if (a?.window !== undefined) void api.windows.update(a.window, { focused: true }).catch(() => {});
      if (a?.tab !== undefined) void api.tabs.update(a.tab, { active: true }).catch(() => {});
      return false;
    }
    case 'card-answer': void answer(msg.id, !!msg.yes); return false;
    case 'remove-site': void forgetSite(msg.origin, true); return false;
    case 'allow-site': void (async () => { await setNotNow((await notNow()).filter(o => o !== msg.origin)); const { pairing: p, sites } = await load(); if (p) await grantFor(p, sites, msg.origin).catch(() => {}); changed(); })(); return false;
    case 'sign-out': void signOut(); return false;
    case 'open-safety': void openSafety(msg.which === 'phone' ? 'phone' : 'paper'); return false;
  }
  return false;
});

api.runtime.onConnect.addListener((port: chrome.runtime.Port) => {
  if (!isOurs(port.sender!)) return port.disconnect();
  if (port.name === 'pair') {
    pairPorts.add(port);
    port.onDisconnect.addListener(() => pairPorts.delete(port));
    port.onMessage.addListener((m: any) => {
      if (m.type === 'ping') return; // keeps the background awake while a code is showing
    if (m.type === 'by-link') { if (pairing) pairing.byLink = true; return; }
    // Back from Cosign. If nothing of its answer arrived, that code is spent on Cosign's
    // side ("already used"): give the card a fresh one to try again with.
    if (m.type === 'returned') {
      const p = pairing;
      if (!p?.byLink) return;
      setTimeout(() => {
        if (pairing !== p || p.view.busy || p.view.done || p.view.confirm) return;
        if (p.t.view().session.phase !== 'waiting') return;
        p.t.dispose(); pairing = undefined;
        startPairing();
        if (pairing) (pairing as { view: PairView }).view.note = 'That didn’t go through. Tap Open Marble to try again.';
        pairEmit();
      }, 4000);
      return;
    }
    if (m.type === 'start') startPairing();
      if (m.type === 'retry') { pairing?.t.dispose(); pairing = undefined; startPairing(); }
      if (m.type === 'accept' && pairing) {
        Object.assign(pairing.view, { busy: true, uri: undefined, confirm: undefined, status: 'Signing in…' });
        pairEmit();
        pairing.t.accept();
      }
      if (m.type === 'decline') pairing?.t.decline();
    });
    void load().then(({ pairing: p }) => p ? port.postMessage({ type: 'pair', view: { done: true, status: 'Signed in.' } }) : startPairing());
  }
  if (port.name === 'safety') {
    safetyPorts.add(port);
    port.onDisconnect.addListener(() => safetyPorts.delete(port));
    port.onMessage.addListener(async (msg: any) => {
      const m = await loadMember();
      if (!m) return port.postMessage({ type: 'paper', error: (await loadPending()) ? 'Finish setup first: your servers haven’t answered yet, and a backup needs them.' : 'This browser doesn’t hold an account.' });
      const d = deviceFor(m);
      if (msg.type === 'invite') port.postMessage({ type: 'invite', code: d.invite() });
      if (msg.type === 'paper') d.makePaper().then(code => port.postMessage({ type: 'paper', code, pubkey: d.pubkey, name: m.profile?.name }), e => port.postMessage({ type: 'paper', error: (e as Error).message }));
      if (msg.type === 'add' && /^[0-9a-f]{64}$/.test(msg.pk)) {
        d.vouch({ pk: msg.pk, name: String(msg.name ?? 'Your phone'), relays: msg.relays ?? [] });
        // Done once the servers list it as one of yours.
        const until = Date.now() + 120_000;
        const check = setInterval(() => {
          if (d.st.group.members.some(x => x.pk === msg.pk)) { clearInterval(check); try { port.postMessage({ type: 'added', name: msg.name }); } catch { /* closed */ } }
          else if (Date.now() > until) clearInterval(check);
        }, 1500);
      }
    });
  }
  if (port.name === 'create') {
    port.onMessage.addListener((m: any) => {
      if (m.type === 'ping') return;
      if (m.type !== 'create') return;
      const say = (text: string, extra: object = {}) => { try { port.postMessage({ type: 'create', text, ...extra }); } catch { /* closed */ } };
      create(String(m.name ?? ''), say).then(() => say('Done.', { done: true }), e => say((e as Error).message, { error: true }));
    });
  }
  if (port.name === 'popup') {
    popupPorts.add(port);
    port.onDisconnect.addListener(() => popupPorts.delete(port));
    void state().then(s => {
      port.postMessage(s); cachePopup(s);
      if (!s.signedIn) startPairing(); // ready before Sign in with Marble is tapped
    });
    port.onMessage.addListener((m: any) => {
      const say = (text: string, extra: object = {}) => { try { port.postMessage({ type: 'join', text, ...extra }); } catch { /* popup closed */ } };
      if (m.type === 'join-existing') joinExisting(say).then(() => say('Done.', { done: true }), e => say((e as Error).message, { error: true }));
      if (m.type !== 'join') return;
      join(String(m.uri ?? ''), say).then(() => say('Done.', { done: true }), e => say((e as Error).message, { error: true }));
    });
  }
});

// For tests: what the device is doing (read-only, nothing secret).
(globalThis as any).__cosignStatus = () => ({
  device: !!device,
  servers: device?.servers().map(s => `${s.name}:${s.pk?.slice(0, 8)}`),
  relaysUp: (device as any)?.box?.connected,
  waiting: (device as any)?.waiting?.size,
  epoch: device?.st.epoch ?? 0,
});
