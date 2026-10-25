// Making a key with nobody ever holding it (DKG), ported from ~/cosign/server/dkg.ts:
// every holder runs the library's three parts for each of its shares, sending part-1
// packages to everyone and part-2 packages to each share's holder, re-sent every 4 s
// until done. Packages between this device's own shares never leave it.

import * as F from '../frost.ts';

export interface DkgPlan { session: string; threshold: number; holders: { idx: number; pk: string }[] }
export interface DkgResult { groupX: string; shares: { idx: number; secret: string }[]; members: { idx: number; sharePub: string }[] }

export class DkgRun {
  done: DkgResult | null = null;
  private mine: number[];
  private secrets1 = new Map<number, string>();
  private packages1 = new Map<number, string>();
  private secrets2 = new Map<number, string>();
  private outgoing2 = new Map<string, [number, number, string][]>();
  private incoming2 = new Map<number, Map<number, string>>();
  private timer: ReturnType<typeof setInterval> | undefined;

  constructor(readonly plan: DkgPlan, private me: string, private send: (toPk: string, msg: object) => void, private onDone: (r: DkgResult) => void) {
    this.mine = plan.holders.filter(h => h.pk === me).map(h => h.idx);
    for (const idx of this.mine) {
      const r = F.dkg.part1(idx, plan.holders.length, plan.threshold);
      this.secrets1.set(idx, r.secret);
      this.packages1.set(idx, r.package);
    }
  }

  private others() { return [...new Set(this.plan.holders.map(h => h.pk))].filter(pk => pk !== this.me); }
  private all() { return this.plan.holders.map(h => h.idx); }

  start() {
    // Runs until stop(): 20 s past done, since someone may still be waiting for ours.
    const tick = () => {
      const ours = this.mine.map(i => [i, this.packages1.get(i)!]);
      for (const pk of this.others()) {
        this.send(pk, { type: 'dkg1', session: this.plan.session, packages: ours });
        const r2 = this.outgoing2.get(pk);
        if (r2) this.send(pk, { type: 'dkg2', session: this.plan.session, packages: r2 });
      }
    };
    tick();
    this.timer = setInterval(tick, 4000);
  }

  stop() { clearInterval(this.timer); }

  take(msg: any, fromPk: string) {
    if (msg.session !== this.plan.session || this.done) return;
    const theirs = new Set(this.plan.holders.filter(h => h.pk === fromPk).map(h => h.idx));
    if (msg.type === 'dkg1') {
      for (const [idx, p] of msg.packages ?? []) if (theirs.has(idx) && !this.packages1.has(idx)) this.packages1.set(idx, String(p));
      this.part2();
    } else if (msg.type === 'dkg2') {
      for (const [from, to, p] of msg.packages ?? []) {
        if (!theirs.has(from) || !this.mine.includes(to)) continue;
        if (!this.incoming2.has(to)) this.incoming2.set(to, new Map());
        this.incoming2.get(to)!.set(from, String(p));
      }
      this.part3();
    }
  }

  private part2() {
    if (this.secrets2.size || this.packages1.size < this.all().length) return;
    for (const idx of this.mine) {
      const others = this.all().filter(i => i !== idx).map(i => [i, this.packages1.get(i)!] as [number, string]);
      const r = F.dkg.part2(this.secrets1.get(idx)!, others);
      this.secrets2.set(idx, r.secret);
      for (const [to, p] of r.packages) {
        const holder = this.plan.holders.find(h => h.idx === to)!.pk;
        if (holder === this.me) {
          if (!this.incoming2.has(to)) this.incoming2.set(to, new Map());
          this.incoming2.get(to)!.set(idx, p);
        } else {
          if (!this.outgoing2.has(holder)) this.outgoing2.set(holder, []);
          this.outgoing2.get(holder)!.push([idx, to, p]);
        }
      }
    }
    for (const [pk, r2] of this.outgoing2) this.send(pk, { type: 'dkg2', session: this.plan.session, packages: r2 });
    this.part3();
  }

  private part3() {
    if (this.done || !this.secrets2.size) return;
    const need = this.all().length - 1;
    if (!this.mine.every(i => (this.incoming2.get(i)?.size ?? 0) >= need)) return;
    const results = this.mine.map(idx => {
      const others = this.all().filter(i => i !== idx).map(i => [i, this.packages1.get(i)!] as [number, string]);
      return { idx, ...F.dkg.part3(idx, this.secrets2.get(idx)!, others, [...this.incoming2.get(idx)!]) };
    });
    const key = results[0].groupX;
    if (results.some(r => r.groupX !== key || JSON.stringify(r.members) !== JSON.stringify(results[0].members))) throw new Error('our shares disagree about the key');
    this.done = { groupX: key, shares: results.map(r => ({ idx: r.idx, secret: r.share })), members: results[0].members };
    this.secrets1.clear(); this.secrets2.clear();
    // Keep sending part 2 a little longer: someone may still be waiting for ours.
    setTimeout(() => this.stop(), 20_000);
    this.onDone(this.done);
  }
}
