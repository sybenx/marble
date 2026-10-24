// FROST rounds for a member holding several shares (ported from ~/cosign/server/sign.ts,
// library scheme only). A round is every share of the members taking part.

import * as F from '../frost.ts';

export interface Commit { idx: number; D: string; E: string }
export interface Share { idx: number; secret: string }
/** A group as the servers describe it: a member holds one share (a server) or several (a device). */
export interface Group { groupX: string; threshold: number; members: { idx: number; sharePub: string; shares?: { idx: number; sharePub: string }[]; role?: string; pk?: string | null; name?: string }[] }

/** What the library calls the group: every share point, whoever holds it. */
export const flat = (g: Group): F.Group => ({ groupX: g.groupX, threshold: g.threshold, members: g.members.flatMap(m => m.shares ?? [{ idx: m.idx, sharePub: m.sharePub }]) });
export const sharesOf = (m: Group['members'][number]) => m.shares?.map(s => s.idx) ?? [m.idx];

export function commitAll(g: Group, mine: Share[]) {
  const fg = flat(g), nonces: Record<number, string> = {}, commits: Commit[] = [];
  for (const s of mine) { const c = F.commit(fg, s.idx, s.secret); nonces[s.idx] = c.nonces; commits.push(c.commit); }
  return { nonces, commits };
}

export const partialAll = (g: Group, mine: Share[], nonces: Record<number, string>, msg: string, commits: Commit[]): [number, string][] =>
  mine.map(s => [s.idx, F.sign(flat(g), s.idx, s.secret, nonces[s.idx], msg, commits)]);

export const allValid = (g: Group, msg: string, commits: Commit[], partials: [number, string][]) =>
  partials.every(([i, z]) => F.verifyPartial(flat(g), msg, commits, i, z));

export const combine = (g: Group, msg: string, commits: Commit[], partials: [number, string][]) => F.aggregate(flat(g), msg, commits, partials);
