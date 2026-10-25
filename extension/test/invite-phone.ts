// A stand-in for a phone with no key scanning a device's invite (cosign:invite?pk=…&r=…):
// it makes a join key, asks that device to add it (join_request, every 5 s), and when a
// server's welcome and the device's sigmas arrive, sums its shares and says joined.
// Prints JOINED <its member key> when done. Built on ~/cosign/server's own modules.
//
//   node --experimental-transform-types test/invite-phone.ts '<cosign:invite?… uri>' [name]

const SERVER = `${process.env.HOME}/cosign/server`;
const { createRequire } = await import('node:module');
const { Pool, sealParts, openFrom, Parts, pubOf, KIND_MEMBER } = await import(`${SERVER}/net.ts`);
const F = await import(`${SERVER}/frost.ts`);
const Sig = await import(`${SERVER}/sign.ts`);
const W = createRequire(`${SERVER}/x.js`)('./frost-wasm/cosign_frost.js');

const u = new URL(process.argv[2].replace('cosign:', 'cosign://'));
const name = process.argv[3] ?? 'Test phone';
const device = u.searchParams.get('pk')!, relays = u.searchParams.getAll('r');
const sk = F.bytesToHex(F.randomBytes(32)), pk = pubOf(sk);
const parts = new Parts();
const got: { welcome?: any; server?: string; sigmas: [number, number, string][] } = { sigmas: [] };
let joined = false;
const pool = new Pool(relays, (e: any) => { const m = parts.take(e.pubkey, openFrom(sk, e)); if (m) handle(m, e.pubkey); });
const send = (to: string, m: object) => { for (const e of sealParts(sk, to, m)) pool.publish(e); };

function handle(m: any, from: string) {
  if (joined) return;
  if (m.type === 'welcome') { got.welcome = m; got.server = from; got.sigmas.push(...(m.sigmas ?? [])); }
  else if (m.type === 'sigma') got.sigmas.push(...(m.sigmas ?? []));
  else return;
  const w = got.welcome;
  if (!w) return;
  const ks: number[] = w.ks ?? [w.k];
  if (!ks.every(k => got.sigmas.filter(x => x[0] === k).length >= (w.helpers?.length ?? 2))) return;
  const g = JSON.stringify({ groupX: w.group.groupX, threshold: w.group.threshold, members: Sig.sharePoints(w.group) });
  const shares = ks.map(k => ({ idx: k, secret: W.repairStep3(g, got.sigmas.filter(x => x[0] === k).map(x => x[2]), k) as string }));
  const points = shares.map(x => ({ idx: x.idx, sharePub: W.sharePub(x.secret) }));
  send(got.server!, { type: 'joined', id: w.id, sharePubs: points.map(p => [p.idx, p.sharePub]), sharePub: points[0].sharePub });
  joined = true;
  console.log(`JOINED ${pk} shares ${ks.join(',')}`);
  setTimeout(() => process.exit(0), 5000);
}

pool.start([{ kinds: [KIND_MEMBER], '#p': [pk] }]);
const ask = () => { if (!joined) send(device, { type: 'join_request', pk, name, relays }); };
setTimeout(ask, 1500);
setInterval(ask, 5000);
setTimeout(() => { console.log('gave up'); process.exit(1); }, 180_000);
