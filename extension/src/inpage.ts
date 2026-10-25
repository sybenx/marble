// window.nostr (NIP-07), in the page itself. Every call goes to the extension through
// the content script; nothing secret is ever in the page.

type Pending = { resolve(v: any): void; reject(e: Error): void };
const pending = new Map<string, Pending>();
const TAG = '__cosign';

window.addEventListener('message', e => {
  if (e.source !== window || e.data?.[TAG] !== 'res') return;
  const p = pending.get(e.data.id);
  if (!p) return;
  pending.delete(e.data.id);
  e.data.error ? p.reject(new Error(e.data.error)) : p.resolve(e.data.result);
});

function call(method: string, params: unknown = {}): Promise<any> {
  const id = Math.random().toString(36).slice(2) + Date.now().toString(36);
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    window.postMessage({ [TAG]: 'req', id, method, params }, location.origin === 'null' ? '*' : location.origin);
  });
}

const nostr = {
  getPublicKey: () => call('getPublicKey'),
  signEvent: (event: object) => call('signEvent', { event }),
  getRelays: () => call('getRelays'),
  nip04: {
    encrypt: (peer: string, plaintext: string) => call('nip04.encrypt', { peer, plaintext }),
    decrypt: (peer: string, ciphertext: string) => call('nip04.decrypt', { peer, ciphertext }),
  },
  nip44: {
    encrypt: (peer: string, plaintext: string) => call('nip44.encrypt', { peer, plaintext }),
    decrypt: (peer: string, ciphertext: string) => call('nip44.decrypt', { peer, ciphertext }),
  },
};

if (!(window as any).nostr) (window as any).nostr = nostr;
