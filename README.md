# Marble (beta)

A FROST (keykin) android signer with a search engine and note sync. Search also available at https://marble.syb.lol.

A home for the open social web on Android: search people, posts, articles and apps, read
the sources you choose, keep notes that sync to all your devices, and sign in to sites and
to other apps on your phone. Your account, devices, sites and history are behind your picture.

**Your key starts on your phone.** Marble works with no server: the key is kept on the
phone, sealed, and signs from there. **A [Keykin](https://github.com/sybenx/keykin) box
is optional.** Sign in to one and your phone and the box each hold a piece of the key;
any two pieces sign, and one alone does nothing. The phone keeps the whole key as your
backup until you choose to forget it. You can run a box yourself, for example on an
Umbrel ([keykin-umbrel](https://github.com/sybenx/keykin-umbrel)).

**Beta.** Signing uses the Zcash Foundation's FROST crate (`frost-secp256k1-tr`). Keykin's
spec around it is still a draft, and [QRST](https://github.com/sybenx/qr-secret-transfer),
which carries keys and sign-ins by a scanned code, calls itself a draft protocol with
unaudited code.

## What it does

- **Start how you like**: create an account, bring the secret key you already have, or
  move your account over from another device by scanning one code.
- **Sign in to sites** by a scanned code, `nostrconnect://` or a `bunker://` link. Apps
  on your phone sign through Marble (NIP-55).
- **Each new site gets an account of its own** by default, made from your main one, so
  there is nothing new to back up. *Use my main account* signs in as you instead.
- **Sites get only what you allow**: posting as you is always on; reading your private
  lists and reading and sending DMs are switches you change any time.
- **Hard things ask first.** An app or site changing your relay lists asks every time.
  Wiping your profile, emptying your follows or deleting everything you've posted
  takes Yes and then your fingerprint.
- **Recent changes, with Undo**: every change to your lists, from Marble or another app,
  with Undo. A change that lost something gets a notification.
- **Notes in a vault**: encrypted on your phone, kept on your own relays and Blossom
  servers, the same on every device and on Marble Web. A note changed in two places keeps
  both versions; removed notes go to Trash.
- **Search as you type**: people, posts, articles and apps. Apps and any web address
  open in Marble's web pane, and *Keep* makes them a chip.
- **Reader**: stories from the people, sites and relays you choose, in folders, with
  read state that follows you to your other devices. Posts in another language are
  translated on the phone.
- **Devices**: add one by scanning its code and confirming with your fingerprint. With a
  box, report a lost device and cycle the keys so it can't sign any more.
- **Homes**: accounts Marble makes for sites and apps go into your home, the relay you
  own or help run, so the people there can reach them.
- **Take your account out** whenever you like: Advanced shows your secret key after your
  fingerprint.

## Getting Marble

Releases are on GitHub (github.com/sybenx/marble/releases), so Obtainium can follow
them: add `https://github.com/sybenx/marble` as an app. The newest build is always at
https://github.com/sybenx/marble/releases/latest/download/Marble.apk. It needs Android 11
or newer on a phone or tablet (arm64).

**Marble Web**, at https://marble.syb.lol, has search and your notes in a browser.

The browser extension in `extension/` (Chrome, Edge, Brave, Firefox, Safari) was written
for Cosign, the signer Marble used before Keykin, and has not been changed for Keykin.

## Privacy

- **Without a box, your key signs on the phone** and goes nowhere else unless you move
  it to another device, sealed, by a scanned code.
- **With a box, the box sees what you sign through it**, keeps a log of it for your
  account page, and knows which sites and apps are signed in. For messages it sees only
  whose key they are with, never what they say. It never holds enough to sign alone.
- **Notes are encrypted on your phone** before they go to your relays and Blossom
  servers. With no Blossom list of your own, Marble uses nostr.download and
  blossom.yakihonne.com.
- **Searching for people** sends what you type to the servers Marble searches. App search
  matches a list downloaded once a day, on the phone. When a result shows an address like
  `name@example.com`, Marble asks that site whether it belongs to the person shown, and
  that site can see your IP address.
- **Pictures and videos load from wherever they are kept**, which can see your IP
  address, as in any app that shows you a picture.

## Building

```sh
git clone --recursive https://github.com/sybenx/marble && cd marble
(cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest)
(cd extension && npm install && node build.mjs)
```

- **QRST** is a git submodule at `qr-secret-transfer/`. `android/settings.gradle.kts`
  includes its Kotlin build (`includeBuild("../qr-secret-transfer/kotlin")`). In a
  checkout made without `--recursive`, run `git submodule update --init`.
- **Modules**: `android/app`, `android/signer` (Keykin v2: Keykin's Kotlin core, copied in
  under `cc/aaro/keykin/core`) and `android/vaults` (end-to-end encrypted sync on Nostr
  and Blossom). Notes on each are in `android/docs/`.
- **The FROST library** (`libkeykin_frost.so`, arm64-v8a and x86_64) is committed in
  `android/signer/src/main/jniLibs`, so a build needs nothing more. To rebuild it after
  Keykin's `frost/` changes, in a Keykin checkout:
  `cd frost && cargo ndk -t arm64-v8a -t x86_64 -P 30 -o <marble>/android/signer/src/main/jniLibs build --release --features jni`
  (needs `cargo-ndk` and `ANDROID_NDK_HOME`).
- **Tests that run FROST** are skipped unless `KEYKIN_FROSTLIB` names a desktop build of
  it (`cargo build --release --features jni` in Keykin's `frost/`).
- **Java and the Android SDK**: Gradle runs on Java 17, and the app's unit tests use a
  Java 21 toolchain. Set `ANDROID_HOME`, or put `sdk.dir` in `android/local.properties`.
- **Kept names**: the app keeps the id `cc.aaro.cosign` and its Kotlin package names from
  the Cosign days, so every build installs over the ones before it. Links to sign-in codes
  on `cosign.aaro.cc`, Cosign's old address, still open in the app.
- **Releases**: `./release.sh` (bump `versionCode` and `versionName` in
  `android/app/build.gradle.kts` first). The release signing files stay outside any
  repository (`~/cosign-release`). `./zapstore.sh` publishes the newest release to
  Zapstore. Notes for each version are in `release-notes/`.
- **Marble Web** is `site/`, with `worker/index.js` for shared links, on Cloudflare
  Workers: `npx wrangler dev` to try it, `npx wrangler deploy` to publish.

Marble's history up to 0.12.4 is in the Cosign repository (tag `v0.12.4`); the
extension's history came along with it.

## License

MIT.
