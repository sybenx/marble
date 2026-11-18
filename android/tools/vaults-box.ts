// A throwaway Keykin v2 box for the vaults tests on the emulator, from the Keykin repo (~/keykin):
//
//   bun android/tools/vaults-box.ts [dir]      box on http://127.0.0.1:7460 (the emulator reaches it as 10.0.2.2:7460)
//
// GET http://127.0.0.1:7461/code answers a fresh setup code (an approved request), naming the box as the emulator
// sees it; GET /code?local=1 names it as this Mac sees it (127.0.0.1), for devices running here.
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const { startBox } = await import((process.env.KEYKIN_REPO ?? `${process.env.HOME}/keykin`) + '/box/server.ts');
const dir = process.argv[2] ?? mkdtempSync(join(tmpdir(), 'vaults-box-'));
const b = await startBox({ dir, port: 7460, url: 'http://10.0.2.2:7460', log: () => {} });
const now = () => Math.floor(Date.now() / 1000);
Bun.serve({ port: 7461, fetch(req) {
  const u = new URL(req.url);
  if (u.pathname === '/code') return new Response(b.box.newSetupCode(u.searchParams.get('local') ? 'http://127.0.0.1:7460' : 'http://10.0.2.2:7460', now()));
  return new Response('vaults box', { status: 404 });
} });
console.log(`keykin box in ${dir} on 7460; codes on http://127.0.0.1:7461/code`);
