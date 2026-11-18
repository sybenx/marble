// The one step that catches a swapped sign-in code.
//
// A browser showing a sign-in code holds what arrives and asks "Sign in as …?" before it
// keeps it. That card is the only thing standing between the user and a doctored page (or a
// shoulder-surfer) whose own QR was shown in place of the browser's: without it the browser
// would sign in as *their* account, with the user's own phone having said yes to a code it
// could not tell apart. QRST's second-responder abort covers two phones answering; nothing
// but this card covers one phone answering the wrong code.
//
// So the invariant: a sign-in completes only after someone says Sign in on that card, with
// one exception — the same-phone handoff, where the code was never on a screen anyone could
// photograph and Marble on that phone already asked. This reads the source back rather than
// driving a browser, because the property is "there is no other way in", which a happy-path
// browser test cannot show.
//
//   node --experimental-transform-types test/pairing-confirm.ts

import { readFileSync } from 'node:fs';

let failed = 0;
const check = (ok: boolean, what: string) => { console.log(`${ok ? 'ok  ' : 'FAIL'} ${what}`); if (!ok) failed++; };
const bg = readFileSync(new URL('../src/background.ts', import.meta.url), 'utf8');
const ui = readFileSync(new URL('../src/ui/pair.ts', import.meta.url), 'utf8');

// Every way the held payload can be kept.
const accepts = [...bg.matchAll(/\.accept\(\)/g)].length;
check(accepts === 2, `the payload is accepted in exactly two places (found ${accepts})`);
check(/if \(m\.type === 'accept' && pairing\)/.test(bg), 'one is the card saying so');
check(/if \(view\.confirm && pairing\?\.byLink\)/.test(bg), 'the other is the same-phone handoff, and only it');

// The card's own wiring.
check(/\$\('\.yes'\)\.onclick = \(\) => port\.postMessage\(\{ type: 'accept' \}\)/.test(ui), 'only the Sign in button sends accept');
check(/Sign in as \$\{v\.confirm\}\?/.test(ui), 'the card names the account it is about to become');
check(/\$\('\.confirm'\)\.hidden = !v\.confirm/.test(ui), 'and it is shown whenever there is something to confirm');

// The handoff is the exception, and it cannot happen by itself.
check(/\$\('\.open'\)\.addEventListener\('click'/.test(ui), 'byLink is set by a click on Open Marble and nothing else');
check(/isAndroid\(\) && !preferQr/.test(ui), 'which is only offered on a phone, so a desktop scan can never take it');

// What the background must never do: accept because the phone said it came from a camera.
check(!/viaCamera/.test(bg), 'the browser does not take the sender’s word for how the code travelled');

// And it is said afterwards, whichever way it went.
check(/Signed in as \$\{who\}/.test(bg), 'after signing in, the account is named plainly');

console.log(failed ? `${failed} failed` : 'all passed');
process.exit(failed ? 1 : 0);
