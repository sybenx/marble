// A stand-in for the Cosign phone app: answer a sign-in code as if you'd scanned it and
// tapped Connect. For testing against the throwaway servers only.
//
//   node --experimental-transform-types test/fake-phone.ts <code-uri | screenshot.png>
//
// It tells server A about the ticket (as server B, whose key it reads from
// ~/cosign/server/data/b) and hands the ticket over by QRST.

import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { finalizeEvent } from 'nostr-tools/pure';
import { Relay } from 'nostr-tools/relay';
import * as nip44 from 'nostr-tools/nip44';
import { bytesToHex, hexToBytes, randomBytes } from '@noble/hashes/utils.js';
import decodeQR from 'qr/decode.js';
import { Store, Transfer, browserEnv } from '../vendor/qrst-web/index.ts';
import { parseUri } from '../vendor/qrst-core/index.ts';
import { signerTicket, type Ticket } from '../src/ticket.ts';

const SERVERS = process.env.COSIGN_SERVERS ?? `${process.env.HOME}/cosign/server/data`;
const A = JSON.parse(readFileSync(`${SERVERS}/a/state.json`, 'utf8'));
const B = JSON.parse(readFileSync(`${SERVERS}/b/state.json`, 'utf8'));
const serverA = A.group.members.find((m: any) => m.idx === A.self.idx).pk;

/** Read a QR code from a PNG, by way of an uncompressed BMP that sips makes. */
function readCode(png: string): string {
  const bmp = `${png}.bmp`;
  execFileSync('sips', ['-s', 'format', 'bmp', png, '--out', bmp], { stdio: 'ignore' });
  const b = readFileSync(bmp);
  const off = b.readUInt32LE(10), w = b.readInt32LE(18), h0 = b.readInt32LE(22), bpp = b.readUInt16LE(28) / 8;
  const h = Math.abs(h0), row = Math.ceil((w * bpp) / 4) * 4;
  const data = new Uint8Array(w * h * 3);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const src = off + (h0 > 0 ? h - 1 - y : y) * row + x * bpp, dst = (y * w + x) * 3;
    data[dst] = b[src + 2]; data[dst + 1] = b[src + 1]; data[dst + 2] = b[src];
  }
  return decodeQR({ width: w, height: h, data });
}

const arg = process.argv[2];
if (!arg) { console.log('usage: fake-phone.ts <code-uri | screenshot.png>'); process.exit(2); }
const uri = arg.endsWith('.png') ? readCode(arg) : arg;
const params = parseUri(uri);
console.log(`scanned: ${params.profile}, check ${params.check}, claims ${params.origin}`);

const ticket: Ticket = { v: 1, pubkey: A.group.groupX, signer: A.signer.pk, relays: A.group.relays, t: bytesToHex(randomBytes(16)), expires: Math.floor(Date.now() / 1000) + 900 };
const relays = await Promise.all(A.group.relays.map((u: string) => Relay.connect(u).catch(() => null)));
const ck = nip44.v2.utils.getConversationKey(hexToBytes(B.self.sk), serverA);
const e = finalizeEvent({ kind: 28001, created_at: Math.floor(Date.now() / 1000), tags: [['p', serverA]], content: nip44.v2.encrypt(JSON.stringify({ type: 'ticket', t: ticket.t, origin: params.origin, expires: ticket.expires }), ck) }, hexToBytes(B.self.sk));
for (const r of relays) r?.publish(e).catch(() => {});

const mem = () => { const m = new Map<string, string>(); return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v), removeItem: (k: string) => void m.delete(k) }; };
const phone = Transfer.join({ profile: signerTicket, store: new Store(mem(), () => Math.floor(Date.now() / 1000)), env: browserEnv(), baseUrl: '', origin: '', check: 'none' }, params, { viaCamera: true }, ticket);
let released = false;
phone.onChange = () => {
  const v = phone.view();
  if (v.session.phase === 'release' && !released) {
    released = true; console.log('Connect tapped'); phone.release();
    // The ticket is on its way; the site's "Sign in" comes later, from someone else.
    setTimeout(() => { for (const r of relays) r?.close(); process.exit(0); }, 4000);
  }
  if (v.stage === 'ended') { console.log(`done: ${v.session.outcome}`); for (const r of relays) r?.close(); phone.dispose(); process.exit(0); }
};
setTimeout(() => { console.log('gave up after 60 s'); process.exit(1); }, 60_000);
