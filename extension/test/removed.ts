// @ts-nocheck (a test script, typed loosely)
// "This browser was removed from your key" is taken only from a server: from another member (a device, a paper, or one
// a hostile server wrote into the list) it changes nothing and signs no open tab out. No network: Device.handle on
// its own, with a made-up group.
//
//   node --experimental-transform-types test/removed.ts
import { Device } from '../src/member/device.ts';
import * as nip44 from 'nostr-tools/nip44';
import { getPublicKey, generateSecretKey } from 'nostr-tools/pure';
import { bytesToHex } from '@noble/hashes/utils.js';

const key = () => { const sk = generateSecretKey(); return { sk: bytesToHex(sk), pk: getPublicKey(sk) }; };
const me = key(), server = key(), phone = key(), stranger = key();
let failed = 0;
const check = (ok, what) => { if (!ok) failed++; console.log(`${ok ? 'ok  ' : 'FAIL'} ${what}`); };

function device() {
  const d = Object.create(Device.prototype);
  d.st = { sk: me.sk, group: { members: [{ pk: server.pk, role: 'server' }, { pk: phone.pk, role: 'phone' }, { pk: me.pk, role: 'device' }] } };
  d.saved = 0; d.save = () => d.saved++;
  d.told = 0; d.onRemoved = () => d.told++;
  d.waiting = new Map(); d.requests = new Set();
  return d;
}
const mail = (from, inner) => ({ type: 'mail', from: from.pk, content: nip44.v2.encrypt(JSON.stringify(inner), nip44.v2.utils.getConversationKey(Uint8Array.from(Buffer.from(from.sk, 'hex')), me.pk)) });

let d = device();
d.handle({ type: 'removed', by: 'Phone' }, phone.pk, Date.now() / 1000);
check(!d.st.removed && d.told === 0 && d.saved === 0, 'removed from a member that is a phone: nothing changes, no tab told');
d.handle({ type: 'removed', by: 'Me' }, me.pk, Date.now() / 1000);
check(!d.st.removed && d.told === 0, 'removed from this device itself: nothing');
d.handle({ type: 'removed' }, stranger.pk, Date.now() / 1000);
check(!d.st.removed && d.told === 0, 'removed from someone not in the key: nothing');
d.handle(mail(phone, { type: 'removed', by: 'Phone' }), server.pk, Date.now() / 1000);
check(!d.st.removed && d.told === 0, 'removed from a phone, as mail a server kept: nothing');
d.handle(mail(phone, { type: 'removed', by: 'Phone' }), phone.pk, Date.now() / 1000);
check(!d.st.removed && d.told === 0, 'removed from a phone, as mail it sent itself: nothing');

d.handle({ type: 'removed', by: 'Your phone' }, server.pk, Date.now() / 1000);
check(d.st.removed?.by === 'Your phone' && d.told === 1 && d.saved === 1, 'removed from a server: recorded, open tabs told');
d.handle({ type: 'removed', by: 'Your phone' }, server.pk, Date.now() / 1000);
check(d.told === 1, 'a second one: tabs told once');

console.log(failed ? `${failed} FAILED` : 'all passed');
process.exit(failed ? 1 : 0);
