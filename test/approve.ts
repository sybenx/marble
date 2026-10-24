// For tests on a computer: approvals ("Allow this site?", a big change) open in a window of
// the extension's own. Wait for it, optionally give it a simulated device lock, and press
// its button.
import type { BrowserContext, Page } from 'playwright-core';

export async function decide(ctx: BrowserContext, button: '#yes' | '#no' = '#yes', o: { lock?: boolean; timeout?: number } = {}): Promise<Page> {
  const end = Date.now() + (o.timeout ?? 30_000);
  let win: Page | undefined;
  while (!win && Date.now() < end) {
    win = ctx.pages().find(p => p.url().includes('/card.html') && p.url().includes('&window'));
    if (!win) await new Promise(r => setTimeout(r, 200));
  }
  if (!win) throw new Error('no approval window opened');
  if (o.lock) {
    const cdp = await ctx.newCDPSession(win);
    await cdp.send('WebAuthn.enable');
    await cdp.send('WebAuthn.addVirtualAuthenticator', { options: { protocol: 'ctap2', transport: 'internal', hasResidentKey: true, hasUserVerification: true, isUserVerified: true } });
  }
  await win.waitForSelector(`${button}:not([disabled])`, { timeout: 10_000 });
  await win.click(button);
  return win;
}

/** The approval window's main line (the site, or what the change is). */
export async function windowText(ctx: BrowserContext): Promise<string> {
  const win = ctx.pages().find(p => p.url().includes('/card.html') && p.url().includes('&window'));
  return (await win?.textContent('body').catch(() => '')) ?? '';
}
