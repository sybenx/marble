# Cosign extension

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

## Tests

With the throwaway Cosign servers running (`~/cosign/server`: `node server.ts data/a`
and `data/b`):

```bash
node build.mjs && node --experimental-transform-types test/e2e.ts
```

Chromium with the extension: sign in by the card, sign, nip44, a second site's Allow,
removing one site, removing the whole browser. `test/fake-phone.ts` stands in for the
phone (it answers a code given as a link or a screenshot); `test/tube.ts` signs in to
tube.syb.lol; `test/firefox.ts` does the same in desktop Firefox; `test/serve.mjs`
serves a test site on port 8799.

QRST is vendored from `~/cosign/site/vendor` (QRST commit in `vendor/QRST-COMMIT`).
