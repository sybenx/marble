// Cosign's signing math (the Zcash Foundation's FROST, RFC 9591, BIP-340), as WebAssembly
// built from ~/cosign/frost and vendored in vendor/cosign-frost. Phase 2: the extension
// holds pieces of its own and signs with a server.
//
// The module is loaded on first use from the extension's own files; extension pages and
// the background need 'wasm-unsafe-eval' in their content security policy for it.

import * as w from '../vendor/cosign-frost/cosign_frost.js';
import { api } from './env.ts';

export interface Member { idx: number; sharePub: string }
export interface Group { groupX: string; threshold: number; members: Member[] }
export interface Commit { idx: number; D: string; E: string }

let ready: Promise<void> | undefined;
export function load(): Promise<void> {
  return ready ??= fetch(api.runtime.getURL('cosign_frost_bg.wasm'))
    .then(r => r.arrayBuffer())
    .then(bytes => { w.initSync({ module: bytes }); });
}

const j = JSON.stringify;

export const dkg = {
  part1: (idx: number, maxSigners: number, minSigners: number) => JSON.parse(w.dkgPart1(idx, maxSigners, minSigners)) as { secret: string; package: string },
  part2: (secret: string, others: [number, string][]) => JSON.parse(w.dkgPart2(secret, j(others))) as { secret: string; packages: [number, string][] },
  part3: (idx: number, secret2: string, othersRound1: [number, string][], round2: [number, string][]) =>
    JSON.parse(w.dkgPart3(idx, secret2, j(othersRound1), j(round2))) as { share: string; groupX: string; members: Member[] },
};

export const commit = (g: Group, idx: number, share: string) => JSON.parse(w.commit(j(g), idx, share)) as { nonces: string; commit: Commit };
export const sign = (g: Group, idx: number, share: string, nonces: string, msg: string, commits: Commit[]) => w.sign(j(g), idx, share, nonces, msg, j(commits));
export const verifyPartial = (g: Group, msg: string, commits: Commit[], idx: number, z: string) => w.verifyPartial(j(g), msg, j(commits), idx, z);
export const aggregate = (g: Group, msg: string, commits: Commit[], partials: [number, string][]) => w.aggregate(j(g), msg, j(commits), j(partials));

// Repair (adding a device): the new member sums its helpers' sigmas into a share.
export const repairStep3 = (g: Group, sigmas: string[], k: number) => w.repairStep3(j(g), sigmas, k);
export const sharePub = (share: string) => w.sharePub(share);

// Threshold ECDH for NIP-04/NIP-44 (not FROST): each share gives λ_i·s_i·P; the sum is s·P.
export const ecdhPartial = (idx: number, share: string, participants: number[], peerX: string) => w.ecdhPartial(idx, share, Uint16Array.from(participants), peerX);
export const ecdhCombine = (points: string[]) => w.ecdhCombine(points);
