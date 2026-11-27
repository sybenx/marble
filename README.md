# Marble (beta)

A FROST (keykin) android signer with a search engine and note sync. Search also available at https://marble.syb.lol.

Marble is a Nostr client and signer for Android. It holds the account's key, signs for
sites and for other apps on the phone, and has search, a reader and notes. The notes sync
between devices and with Marble Web through an encrypted vault.

## Status

Beta, 0.16.x. Not audited.

- Signing uses the Zcash Foundation's `frost-secp256k1-tr` crate. The
  [Keykin](https://github.com/sybenx/keykin) spec around it is a draft.
- [QRST](https://github.com/sybenx/qr-secret-transfer), used to move a key or a sign-in
  by a scanned code, is a draft protocol.
- `extension/` was written for Cosign, the signer Marble used before 0.13. It has not
  been ported to Keykin.

## Install

- APK: https://github.com/sybenx/marble/releases/latest/download/Marble.apk
- Obtainium: add `https://github.com/sybenx/marble`.
- Android 11 or newer. Release builds are arm64 only.
- Marble Web (search and notes): https://marble.syb.lol

## How the key is held

Without a box, the key is on the phone, sealed by the Android Keystore, and signs there.
No server is involved.

A Keykin box is optional. After signing in to one, the phone and the box each hold one
FROST piece and any two pieces sign. The phone keeps the whole key as a backup until it is
told to forget it. Reporting a device lost and cycling the keys both need a box. The box
is in the [keykin](https://github.com/sybenx/keykin) repository, packaged for Umbrel in
[keykin-umbrel](https://github.com/sybenx/keykin-umbrel).

There is no paper backup. The key can be moved to another device by QRST, or exported as
an nsec from Advanced.

## What it does

- Signs for sites over NIP-46 (`bunker://`, `nostrconnect://`, or a scanned QRST code)
  and for apps on the phone over NIP-55.
- Gives each site a child account derived from the main one by default. Reading private
  lists and DMs are per-site permissions.
- Asks before a relay-list change, and asks for a fingerprint before a wiped profile,
  emptied follows or mass deletion.
- Keeps a history of list changes, including ones made by other apps, with undo.
- Stores notes in a vault: encrypted on the device, kept on the account's relays and
  Blossom servers. See `android/docs/vaults.md`.
- Searches people, posts, articles and apps. The app index is downloaded once a day and
  matched on the phone (`apps-index/`).
- Reader for chosen people, sites and relays, with read state synced between devices and
  translation done on the phone.

## Layout

| Path | What |
| --- | --- |
| `android/app` | The app. |
| `android/signer` | Keykin v2 signer. Keykin's Kotlin core is copied in under `cc/aaro/keykin/core`. See `android/docs/keykin-v2.md`. |
| `android/vaults` | Encrypted sync on Nostr and Blossom. |
| `qr-secret-transfer/` | Submodule: QRST. Its Kotlin build is included by `android/settings.gradle.kts`. |
| `site/`, `worker/` | Marble Web, on Cloudflare Workers (`wrangler.jsonc`). |
| `apps-index/` | The app list that search uses. |
| `extension/` | Browser extension (Cosign era, see Status). |
| `tools/` | Tests and scripts for the site and vaults. |
| `release-notes/` | One file per version. |

## Building

Needs JDK 17, the Android SDK (compileSdk 35), and JDK 21 for the app's unit tests.

```sh
git clone --recursive https://github.com/sybenx/marble && cd marble
cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

- In a checkout made without `--recursive`: `git submodule update --init`.
- Set `ANDROID_HOME`, or put `sdk.dir` in `android/local.properties`.
- `libkeykin_frost.so` (arm64-v8a, x86_64) is committed in
  `android/signer/src/main/jniLibs`. To rebuild it, in a Keykin checkout:

  ```sh
  cd frost && cargo ndk -t arm64-v8a -t x86_64 -P 30 \
    -o <marble>/android/signer/src/main/jniLibs build --release --features jni
  ```

- Unit tests that run FROST are skipped unless `KEYKIN_FROSTLIB` points at a desktop
  build of the library (`cargo build --release --features jni` in Keykin's `frost/`).
- Site tests: `for f in tools/*.test.mjs; do node --test "$f"; done`
- Marble Web: `npx wrangler dev`, `npx wrangler deploy`. Before a deploy, run `tools/search-smoke.sh` (search on web
  and Android, against fake relays and then the public ones; `SEARCH_OFFLINE=1` for the offline part only), as
  `release.sh` does before a release.
- Extension: `cd extension && npm install && node build.mjs`

## Releasing

Bump `versionCode` and `versionName` in `android/app/build.gradle.kts`, add the notes to
`release-notes/`, then run `./release.sh`. The signing files are read from
`~/cosign-release`, outside the repository. `./zapstore.sh` publishes the newest release
to Zapstore.

## Names kept from Cosign

Marble was split out of the Cosign repository at 0.12.4 (tag `v0.12.4` there has the
earlier history). These names are unchanged so that builds install over earlier ones and
old links keep working:

- application id `cc.aaro.cosign`, and the Kotlin package names
- the `cosign.aaro.cc` app link
- `~/cosign-release`

## License

MIT.
