// Child-key test vectors from the Keykin TypeScript (branch claude/child-keys), for the Kotlin port.
// Run from a checkout of that branch (with its node_modules):
//   node android/tools/child-vectors.ts <keykin checkout> > android/signer/src/test/resources/child-vectors.json
const root = process.argv[2];
const C = await import(root + '/src/child.ts');
const K = await import(root + '/src/curve.ts');
const E = await import(root + '/src/ecdh.ts');
const B = await import(root + '/src/bytes.ts');
const R = await import(root + '/src/round.ts');
const { schnorr } = await import(root + '/node_modules/@noble/curves/secp256k1.js');
const { v2: nip44 } = await import(root + '/node_modules/nostr-tools/lib/esm/nip44.js');

const hex = (n: bigint) => K.scalarHex(n);
const labels = ['home', 'work', 'a', 'x'.repeat(100), 'house 3 \u{1F3E0}', 'naïve', 'two words', '\uD800x', 'Home', ' home'];

// The label's point, and which counter byte gave it (the first that is a point).
const points = labels.map((l) => {
  let n = 0;
  for (; n < 256; n++) { const x = B.bytesToHex(B.sha('keykin2-child-point', Uint8Array.of(n), B.utf8ToBytes(l))); try { K.Point.fromHex('02' + x); break; } catch { } }
  return { label: l, labelUtf8: B.bytesToHex(B.utf8ToBytes(l)), point: C.labelPoint(l), n };
});

const tweaks = [
  { sharedX: '00'.repeat(32), label: 'home' },
  { sharedX: 'ab'.repeat(32), label: 'work' },
  { sharedX: B.bytesToHex(B.sha('x')), label: 'house 3 \u{1F3E0}' },
].map((v) => ({ ...v, t: C.tweakFrom(v.sharedX, v.label) }));

// Whole accounts: a key (some with odd y, so the even-y form matters), a fixed second coefficient, the two
// pieces, and for each label the shared x (from the two pieces' parts), the tweak, the child's npub, both
// child pieces and the child's whole secret (for the test's own check of tier 1 only).
const peer = 'f'.repeat(63) + '1';
const peerSk = B.sha('the other person');
const peerPub = B.bytesToHex(schnorr.getPublicKey(peerSk));
const accounts = [];
for (let k = 1; k <= 6; k++) {
  const nsec = K.mod(BigInt('0x' + B.bytesToHex(B.sha('nsec ' + k))));
  let a0 = nsec;
  if (K.mul(a0).toAffine().y % 2n === 1n) a0 = K.mod(K.N - a0);
  const a1 = K.mod(BigInt('0x' + B.bytesToHex(B.sha('a1 ' + k))));
  const Cm = [K.mul(a0), K.mul(a1)];
  const p1 = { C: Cm, i: 1, share: K.mod(a0 + a1) }, p2 = { C: Cm, i: 2, share: K.mod(a0 + 2n * a1) };
  const children = [];
  for (const label of ['home', 'work', 'house 3 \u{1F3E0}', 'x'.repeat(100)]) {
    const Q = C.labelPoint(label);
    const sharedX = E.ecdhShared(E.ecdhPart(1, p1.share, 2, Q), E.ecdhPart(2, p2.share, 1, Q));
    const t = C.tweakFrom(sharedX, label);
    const tt = BigInt('0x' + t);
    const c1 = C.childPiece(p1, tt), c2 = C.childPiece(p2, tt);
    let s = K.mod(a0 + tt); const flip = K.mul(s).toAffine().y % 2n === 1n; if (flip) s = K.mod(K.N - s);
    // Messages as the child, with another person: the conversation key from the child's two pieces.
    const childX = E.ecdhShared(E.ecdhPart(1, c1.share, 2, peerPub), E.ecdhPart(2, c2.share, 1, peerPub));
    const convKey = B.bytesToHex(E.nip44Key(childX));
    const theirs = B.bytesToHex(nip44.utils.getConversationKey(peerSk, C.childNpub(Cm, tt)));
    if (convKey !== theirs) throw new Error('nip44 mismatch');
    // One signature as the child, by the two child pieces (FROST), checked against the child's npub.
    const msg = B.bytesToHex(B.sha('event ' + k + label));
    const mine = R.ask(c1, msg); const a = R.answer(c2, msg, mine.commit); const sig = R.finish(c1, msg, mine, a);
    if (!schnorr.verify(B.hexToBytes(sig), B.hexToBytes(msg), B.hexToBytes(C.childNpub(Cm, tt)))) throw new Error('sig');
    children.push({
      label, point: Q, sharedX, t, npub: C.childNpub(Cm, tt), flip,
      commitment: c1.C.map(K.compHex), share1: hex(c1.share), share2: hex(c2.share), secret: hex(s),
      peer: peerPub, conversationKey: convKey,
    });
  }
  accounts.push({ nsec: hex(nsec), oddY: a0 !== nsec, a0: hex(a0), a1: hex(a1), npub: K.xOnly(Cm[0]), commitment: Cm.map(K.compHex), share1: hex(p1.share), share2: hex(p2.share), children });
}

const bad: { t: unknown; code: string }[] = [];
for (const t of ['00'.repeat(32), 'ff'.repeat(32), hex(K.N - 1n).replace(/^/, ''), K.N.toString(16), 'xyz', 'AB'.repeat(32), 'ab'.repeat(31), 'ab'.repeat(33), 7, null]) {
  try { C.tweakOf(t); bad.push({ t, code: 'ok' }); } catch (e: any) { bad.push({ t, code: e.code }); }
}
const badLabels: { label: string; code: string }[] = [];
for (const l of ['', 'x'.repeat(101), '\u{1F3E0}'.repeat(25), '\u{1F3E0}'.repeat(26)]) {
  try { C.labelPoint(l); badLabels.push({ label: l, code: 'ok' }); } catch (e: any) { badLabels.push({ label: l, code: e.code }); }
}

console.log(JSON.stringify({ points, tweaks, accounts, tweakOf: bad, labels: badLabels, peer, peerSk: B.bytesToHex(peerSk) }, null, 1));
