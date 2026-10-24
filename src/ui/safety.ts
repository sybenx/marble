// Keeping an account that lives in this browser safe: a paper backup, or your phone.
// Opened from the popup's card and from the step right after Create account.

import { nip19 } from 'nostr-tools';
import { api } from '../env.ts';
import { qrSvg } from './pair.ts';
import { deviceLock } from './lock.ts';

const $ = (s: string) => document.getElementById(s)!;
const port = api.runtime.connect({ name: 'safety' });
setInterval(() => { try { port.postMessage({ type: 'ping' }); } catch { /* closed */ } }, 10_000);
let request: { pk: string; name: string; relays: string[] } | undefined;

function show(which: 'paper' | 'phone') {
  $('paper').hidden = which !== 'paper';
  $('phone').hidden = which !== 'phone';
  $('tabPaper').classList.toggle('on', which === 'paper');
  $('tabPhone').classList.toggle('on', which === 'phone');
  history.replaceState(null, '', `#${which}`);
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
    $('paperWho').textContent = `For ${m.name ?? 'your account'} (${nip19.npubEncode(m.pubkey).slice(0, 16)}…), made ${new Date().toLocaleDateString()}`;
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

show(location.hash === '#phone' ? 'phone' : 'paper');
