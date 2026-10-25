# Paused (feature freeze, 2026-10-24)

From here on: only bug fixes from real daily use, reported through the status session.
These wait until the freeze lifts.

## Waiting on Cosign
- **24-word backup (paused):** built and tested, shown only with the dev flag. Don't
  switch it on until the user decides, after living with the current backup options,
  and Cosign ships `backup_find` (restore from words alone). Then add restore in the
  extension too: words, the text code, or the QR.
- **Add your phone (real phone):** the invite flow (`cosign:invite`) is built and tested
  with a stand-in; real phones need the next Cosign release.

## Decisions for the user
- **"Sign in with your phone" when the key already lives on the phone:** make the
  browser a full device (signs faster, no phone needed; built behind the dev flag as
  "Add this browser to my key") or keep it a site connection through the servers (today;
  nothing new holds a piece). Recommendation: a device.

## Not built yet
- The marble icon: replace static/icon-*.png (and the Safari app icon) when the Cosign session makes it.
- Report a device lost from the extension (it only follows a refresh today).
- Sign out in device mode. It's hidden because it would discard this browser's part of
  the key; it could be allowed once a phone or paper exists.
- Swap a server that stays down (reshare); show who runs each server in settings; "Use my
  own server".
- Create account: a picture; "I already have a key" (import).
- Clickjacking on phones, fixed: Allow and approvals open in a tab of the extension's own
  and return to the site afterwards (as a window does on computers). The hardened in-page
  card (real click, 800 ms settle, visibility check in Chromium) is only the fallback if
  a tab can't be opened; there, Firefox for Android and Safari on iOS can't detect a plain
  pass-through overlay. Not yet tried on a real phone.
- Replace the native `confirm()` for "Sign out of this browser" with the extension's own.
- Firefox for Android: tapping Open Cosign leaves an about:blank tab behind (the link uses
  `target=_blank`).

## Not verified
- A real person's tap on the phone Allow card (the test harness's clicks into an
  extension frame are untrusted, which the card correctly ignores).
- The device lock (WebAuthn) in Firefox and Safari extensions; checked in Chromium only.
- Safari on a real iPad (the layout is checked in Chromium dressed as iPad Safari).
- Desktop Firefox's `wasm-unsafe-eval` and device mode (phase 1 is tested in Firefox).

## Not done on purpose
- No extension store listing (Chrome Web Store, AMO, App Store): ask before publishing.
