// Keeping an account that lives in this browser safe: a paper backup, or your phone.
// Opened from the popup's card and from the step right after Create account.

import { bech32 } from '@scure/base';
import { api } from '../env.ts';
import { qrSvg } from './pair.ts';
import { deviceLock } from './lock.ts';
import { entropyToMnemonic } from '@scure/bip39';
import { wordlist } from '@scure/bip39/wordlists/english.js';
import { hexToBytes } from '@noble/hashes/utils.js';

const $ = (s: string) => document.getElementById(s)!;
const port = api.runtime.connect({ name: 'safety' });
setInterval(() => { try { port.postMessage({ type: 'ping' }); } catch { /* closed */ } }, 10_000);
let request: { pk: string; name: string; relays: string[] } | undefined;

function show(which: 'paper' | 'phone') {
  $('paper').hidden = which !== 'paper';
  $('phone').hidden = which !== 'phone';
  $('tabPaper').classList.toggle('on', which === 'paper');
  $('tabPhone').classList.toggle('on', which === 'phone');
  history.replaceState(null, '', `#${which}${location.hash.includes('setup') ? '&setup' : ''}`);
  if (which === 'phone') port.postMessage({ type: 'invite' });
}
$('tabPaper').onclick = () => show('paper');
$('tabPhone').onclick = () => show('phone');

$('makePaper').onclick = async () => {
  const b = $('makePaper') as HTMLButtonElement;
  b.disabled = true;
  $('paperStatus').textContent = 'Confirm with your device lock…';
  const ok = await deviceLock().catch(e => { $('paperStatus').textContent = (e as Error).message; return false; });
  if (!ok) { b.disabled = false; if (!$('paperStatus').textContent?.startsWith('This')) $('paperStatus').textContent = ''; return; }
  $('paperStatus').textContent = 'Making it with your servers…';
  port.postMessage({ type: 'paper' });
};
$('print').onclick = () => window.print();

// --- Ways to keep the backup ------------------------------------------------------------

let backup: { code: string; words: string[]; label: string } | undefined;
function keep(which: 'words' | 'qr' | 'copy') {
  for (const [id, w] of [['viewWords', 'words'], ['viewQr', 'qr'], ['viewCopy', 'copy']] as const) $(id).hidden = w !== which;
  $('keepWords').classList.toggle('on', which === 'words');
  $('keepQr').classList.toggle('on', which === 'qr');
  $('keepCopy').classList.toggle('on', which === 'copy');
}
$('keepWords').onclick = () => keep('words');
$('keepQr').onclick = () => keep('qr');
$('keepCopy').onclick = () => keep('copy');

/** 24 words (BIP-39, English) whose entropy is the backup's secret: the same key, written down. */
function showWords(code: string) {
  const k = new URL(code.replace('cosign:', 'cosign://')).searchParams.get('k')!;
  const words = entropyToMnemonic(hexToBytes(k), wordlist).split(' ');
  $('words').innerHTML = words.map(w => `<li>${w}</li>`).join('');
  return words;
}
let asked: number[] = [];
$('wroteThem').onclick = () => {
  const n = backup!.words.length;
  const pick = new Set<number>();
  while (pick.size < 3) pick.add(crypto.getRandomValues(new Uint32Array(1))[0] % n);
  asked = [...pick].sort((a, b) => a - b);
  $('checks').innerHTML = asked.map(i => `<label>Word ${i + 1}<input autocomplete="off" autocapitalize="off" spellcheck="false" data-i="${i}"></label>`).join('');
  $('words').hidden = true; $('wroteThem').hidden = true; $('checkWords').hidden = false;
  ($('checks').querySelector('input') as HTMLInputElement).focus();
};
$('checkGo').onclick = () => {
  const ok = [...$('checks').querySelectorAll('input')].every(el => (el as HTMLInputElement).value.trim().toLowerCase() === backup!.words[Number((el as HTMLInputElement).dataset.i)]);
  if (ok) { $('checkWords').hidden = true; $('wordsStatus').textContent = 'Those match. Keep the words somewhere safe.'; }
  else { $('wordsStatus').textContent = 'That doesn’t match. Have another look.'; $('words').hidden = false; $('wroteThem').hidden = false; $('checkWords').hidden = true; }
};

$('copy').onclick = async () => {
  try { await navigator.clipboard.writeText(`${backup!.label}\n${backup!.code}`); $('copyStatus').textContent = 'Copied. Paste it into your password manager as a secure note, then clear your clipboard.'; }
  catch { $('copyStatus').textContent = 'Couldn’t copy here. Select the code under “Print or save” and copy it.'; }
};

$('saveImg').onclick = async () => {
  // The QR as a PNG, with the label and the code beneath it.
  const svg = $('paperQr').querySelector('svg')!;
  const img = new Image();
  img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(new XMLSerializer().serializeToString(svg));
  await img.decode();
  const c = document.createElement('canvas'); c.width = 800; c.height = 920;
  const g = c.getContext('2d')!;
  g.fillStyle = '#fff'; g.fillRect(0, 0, c.width, c.height);
  g.imageSmoothingEnabled = false; g.drawImage(img, 80, 60, 640, 640);
  g.fillStyle = '#000'; g.font = '600 28px system-ui'; g.fillText('Marble backup', 80, 40);
  g.font = '18px system-ui'; g.fillText(backup!.label.replace('Marble backup — ', ''), 80, 740);
  g.fillText('To use it: Marble → Restore a backup → scan this.', 80, 780);
  const a = document.createElement('a');
  a.href = c.toDataURL('image/png'); a.download = 'cosign-backup.png'; a.click();
};

$('askYes').onclick = async () => {
  if (!request) return;
  const b = $('askYes') as HTMLButtonElement;
  b.disabled = true;
  const ok = await deviceLock().catch(e => { $('phoneStatus').textContent = (e as Error).message; return false; });
  if (!ok) { b.disabled = false; return; }
  $('phoneStatus').textContent = `Adding ${request.name}…`;
  port.postMessage({ pk: request.pk, name: request.name, relays: request.relays, type: 'add' });
  $('ask').hidden = true;
};

port.onMessage.addListener((m: any) => {
  if (m.type === 'paper') {
    if (m.error) { $('paperStatus').textContent = m.error; ($('makePaper') as HTMLButtonElement).disabled = false; return; }
    $('paperStart').hidden = true; $('paperDone').hidden = false;
    $('paperQr').innerHTML = qrSvg(m.code);
    $('paperCode').textContent = m.code;
    const who = `${m.name ?? 'your account'} (${bech32.encode('npub', bech32.toWords(hexToBytes(m.pubkey)), 1000).slice(0, 16)}…)`;
    $('paperWho').textContent = `For ${who}, made ${new Date().toLocaleDateString()}`;
    backup = { code: m.code, words: showWords(m.code), label: `Marble backup — ${who}` };
    // 24 words come once a restore can find the servers from the words alone (Cosign's
    // backup_find); until then they're for testing only.
    api.storage.local.get('dev').then((r: any) => { if (r.dev) $('keepWords').hidden = false; }).catch(() => {});
    keep('qr');
  }
  if (m.type === 'invite') { $('inviteQr').innerHTML = qrSvg(m.code); $('inviteQr').dataset.code = m.code; }
  if (m.type === 'join_request') {
    request = m;
    $('askName').textContent = `Add ${m.name} to your key?`;
    $('ask').hidden = false;
    $('phoneStatus').textContent = 'Your phone asked to join.';
  }
  if (m.type === 'added') $('phoneStatus').textContent = `${m.name} is now one of your devices. You’re covered.`;
});

// Opened as the step right after Create account: say so, and let it be skipped.
const setup = location.hash.includes('setup');
$('setupStep').hidden = !setup;
$('skip').hidden = !setup;
$('skip').onclick = () => window.close();
show(location.hash.startsWith('#phone') ? 'phone' : 'paper');
