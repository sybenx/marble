// Who may ask this browser for a piece of a decryption. One breached server used to be
// enough: this browser answered any server's ecdh_req with three points' worth of partials,
// for any peer it named, so that server plus its own share held the secret that opens
// everything the account encrypts to itself — app data and notes — and a conversation key
// for every peer it chose. The rule now matches the Android library's (Helping.helps) and
// the servers' own: your devices read your messages, your servers don't.
//
//   node --experimental-transform-types test/ecdh.ts

import { NOT_FOR_SERVERS, readsWithUs } from '../src/member/device.ts';

let failed = 0;
const check = (ok: boolean, what: string) => { console.log(`${ok ? 'ok  ' : 'FAIL'} ${what}`); if (!ok) failed++; };

check(readsWithUs('phone') === true, 'the phone reads with us');
check(readsWithUs('device') === true, 'another device reads with us');
check(readsWithUs('server') === false, 'a server does not');
check(readsWithUs('backup') === false, 'the paper does not');
check(readsWithUs(undefined) === false, 'nor does something with no role at all');
check(/^not-allowed:/.test(NOT_FOR_SERVERS), 'the refusal starts with a code a client can act on');
check(/your servers don/.test(NOT_FOR_SERVERS), 'and says why in words');

// The handler for a server's request must not compute anything: the file should hold no
// path from an ecdh_req to ecdhPartial any more.
const src = await (await import('node:fs')).promises.readFile(new URL('../src/member/device.ts', import.meta.url), 'utf8');
const branch = src.slice(src.indexOf("m.type === 'ecdh_req'"), src.indexOf("m.type === 'ecdh_req'") + 900);
check(!/ecdhPartial/.test(branch), 'answering an ecdh_req computes no partials');
check(/NOT_FOR_SERVERS/.test(branch), 'it answers with the refusal');

console.log(failed ? `${failed} failed` : 'all passed');
process.exit(failed ? 1 : 0);
