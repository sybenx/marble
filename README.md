# Marble extension (Cosign)

The app is called **Marble**; the protocol underneath is Cosign. Extension IDs
(`cosign@aaro.cc`, `cc.aaro.cosign.extension`) and the `cosign:` codes stay as they are,
so updates and Marble's phone app keep working.

Sign in to Nostr sites with your phone. The extension gives every page `window.nostr`
(NIP-07), so sites that already support extensions just work. Your key never sits in
the browser: signing happens through your Cosign server, with your phone co-signing.

**Phase 1** (this): the extension holds no piece of your key. It pairs with your
servers the way a site does, by a code your phone scans, and sends each request to
your server, re-sending until it's answered. Phase 2 makes it a member device with a
piece of its own, once Cosign can add devices.

## How it feels

- The first time a site asks for Nostr, a card appears over the page: **Sign in to
  *site* with your phone** and a code. Scan it with Cosign and tap **Connect**, then
  **Sign in** on the card. Once per browser.
- On an Android phone the card says **Open Cosign** instead (you can't scan your own
  screen). On iPhone or iPad, scan the card with your Android phone.
- After that, a new site gets one small card: **Let *site* sign in as you?** with
  **Allow** or **Not now**. That tap is this device saying yes; your phone isn't
  asked. Destructive things still go to your phone, as always.
- The toolbar popup shows who you're signed in as, the sites using it (each with
  **Remove**), and **Sign out of this browser**.
- On your phone (Cosign 0.5.0 or later), each site lists this browser inside it
  ("Chrome on Mac"). Remove one site, or remove the browser to stop every site at
  once. Older servers list the browser once, as one grant.

## Build

```bash
npm install
```

```bash
node build.mjs
```

That writes `dist/chrome` (Chrome, Edge, Brave, Opera), `dist/firefox` (desktop and
Android) and `dist/safari` (used by the Xcode project in `safari/`).

## Install for testing

- **Chrome / Brave / Edge:** `chrome://extensions` → Developer mode → Load unpacked →
  `dist/chrome`.
- **Firefox (desktop):** `about:debugging#/runtime/this-firefox` → Load Temporary
  Add-on → `dist/firefox/manifest.json`.
- **Firefox for Android:** unsigned add-ons need Firefox Nightly. Set
  `xpinstall.signatures.required` to false in `about:config`, then Settings → About
  Firefox Nightly → tap the logo five times → Install add-on from file →
  `cosign-firefox.xpi` (`cd dist/firefox && zip -r ../../cosign-firefox.xpi .`).
- **Safari (iPhone, iPad, Mac):** open `safari/Cosign/Cosign.xcodeproj`, pick the
  *Cosign (iOS)* scheme and your device, set your team under Signing, Run. Then on the
  phone: Settings → Apps → Safari → Extensions → Cosign → Allow Extension, and set
  *All Websites* to **Allow** (otherwise Safari asks on every site).

## Phase 2 (in progress): this browser holds part of your key

With Cosign's weighted keys (COSIGN.md 0.2: 4 points to sign, each device 3, each
server 1, paper 2), the extension can be a device of its own: it signs with one of
your servers, no phone needed. Code in `src/member/`, Cosign's FROST as WebAssembly in
`vendor/cosign-frost` (see `SOURCE`).

- **Create account:** the sign-in card and the popup lead with *Create account*: a
  name, and this browser and up to three servers (found on Nostr, `src/servers.ts`,
  one per operator, fastest first) make your key; your profile is published. Public
  servers get no NIP-46 signer key (COSIGN.md §9.2). Pictures and *I already have a
  key* are still to come.
- **Joining an existing weighted group:** popup → *Join with a server code* → paste the `cosign:pair?…` code from
  a weighted server's page. The browser and the servers make the key together (DKG);
  nobody ever holds it. *Create account* will do the same without a code once public
  servers take new accounts.
- **Signing:** this browser runs each round and asks its fastest server for the last
  point, the next one 300 ms later if that one is slow.
- **Destructive things** (emptying your follows, wiping your profile…) show a card:
  *Approve*, confirmed with your device lock (a passkey of the extension's own).
- **Refresh:** when another device reports one lost, this browser follows the
  refresh (`refresh_plan`, `refresh_part`, and `mail` the servers kept while it was
  away) and moves to the new epoch, same npub (`src/member/refresh.ts`, ported from
  `~/cosign/server/refresh.ts`).
- **When servers don't answer:** if Create account can't reach servers within ~30 s,
  the key is made here (`src/member/pending.ts`), kept wrapped (AES-GCM under a
  non-extractable browser key) and signs at once; the popup says "Not protected yet"
  (with a "!" on the icon after a day), and sign-out/backup are off. Every minute, and
  whenever a site asks or the popup opens, it tries again; once two servers answer, the
  key is dealt to them (Cosign's 'dealt' enrollment: this browser 3 shares, each server
  1) and the whole key is wiped.
- **Keeping it safe:** an account that lives only in this browser is lost if the
  browser's data goes. Right after Create account the next step is *Make a paper
  backup* (or *Add your phone*); skippable, but the popup keeps a card up until one
  exists. The paper (`safety.html#paper`) is made after the device lock, by repair with
  a server, its piece sealed to the paper's key and kept by the servers; its code,
  `cosign:backup?k=…&s=…&r=…`, is what Cosign restores from. *Add your phone* shows
  `cosign:invite?pk=…&r=…`; Cosign (next release) scans it and asks to join; you say yes
  with the device lock and the extension vouches for it.
- **Not yet:** reporting a device lost from here, the paper backup, swapping a dead server, and
  signing out (it would throw away this browser's part). No public server announces
  itself yet (SEEDS is empty until syb.lol runs the shared server).

## Tests

With the throwaway Cosign servers running (`~/cosign/server`: `node server.ts data/a`
and `data/b`):

```bash
node build.mjs && node --experimental-transform-types test/e2e.ts
```

Chromium with the extension: sign in by the card, sign, nip44, a second site's Allow,
removing one site, removing the whole browser. `test/fake-phone.ts` stands in for the
phone (it answers a code given as a link or a screenshot); `test/tube.ts` signs in to
tube.syb.lol; `test/firefox.ts` does the same in desktop Firefox (it needs a profile made first with `firefox -CreateProfile "cosigntest <dir>"`, passed as `FF_PROFILE`; `SCAN_DELAY=45` waits like a person scanning); `test/serve.mjs`
serves a test site on port 8799.

`test/member.ts` is phase 2 end to end: it sets up a fresh weighted group from
`~/cosign/server` on port 8761 (in `.test-group/`), joins it through the popup, then
signs, encrypts, approves a held event with a simulated device lock, survives server A
going down, and removes a site. `test/weighted.ts` runs the weighted DKG alone; `test/refresh.ts` runs a refresh with
a removed server inside the extension.
`test/offline.ts` is Create account with the servers down, then back. `test/create.ts` is Create account against two shared servers (`server.ts --root`,
open enrollment) named to the extension as seeds.

QRST is vendored from `~/cosign/site/vendor` (QRST commit in `vendor/QRST-COMMIT`).
