// The device's own lock (Touch ID, Windows Hello, a passcode), asked for with a passkey
// that belongs to this extension, made the first time.

import { api } from '../env.ts';

/**
 * Ask for this device's lock (Touch ID, Windows Hello, a passcode) with a passkey that
 * belongs to this extension, made the first time. True only when the lock was used.
 */
export async function deviceLock(): Promise<boolean> {
  if (!window.PublicKeyCredential) throw new Error('This browser can’t ask for your device lock, so it can’t approve this here.');
  const b64 = (u: ArrayBuffer) => btoa(String.fromCharCode(...new Uint8Array(u)));
  const unb64 = (s: string) => Uint8Array.from(atob(s), c => c.charCodeAt(0));
  const challenge = crypto.getRandomValues(new Uint8Array(32));
  const { lock } = await api.storage.local.get('lock') as { lock?: { id: string } };
  let data: ArrayBuffer;
  try {
    if (!lock) {
      const c = await navigator.credentials.create({ publicKey: {
        rp: { name: 'Cosign' }, challenge, pubKeyCredParams: [{ type: 'public-key', alg: -7 }, { type: 'public-key', alg: -257 }],
        user: { id: crypto.getRandomValues(new Uint8Array(16)), name: 'Cosign', displayName: 'Cosign on this device' },
        authenticatorSelection: { authenticatorAttachment: 'platform', userVerification: 'required', residentKey: 'discouraged' },
      } }) as PublicKeyCredential;
      await api.storage.local.set({ lock: { id: b64(c.rawId) } });
      data = (c.response as AuthenticatorAttestationResponse).getAuthenticatorData();
    } else {
      const a = await navigator.credentials.get({ publicKey: { challenge, allowCredentials: [{ type: 'public-key', id: unb64(lock.id) }], userVerification: 'required' } }) as PublicKeyCredential;
      data = (a.response as AuthenticatorAssertionResponse).authenticatorData;
    }
  } catch (e) {
    if ((e as Error).name === 'NotAllowedError') return false; // cancelled
    throw new Error('Your device lock isn’t available in this browser, so it can’t approve this here.');
  }
  return (new Uint8Array(data)[32] & 0x04) !== 0; // UV: the lock itself was used
}

