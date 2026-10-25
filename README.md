# Marble (beta)

A home screen for the open social web, for Android, with a browser extension: search
people and posts, a Discover feed with pictures and videos, replies and mentions, likes
and follows, and signing in to sites and to other apps on your phone. Your account,
devices, sites and backups are behind your picture.

**Marble is one app that uses [Cosign](https://github.com/sybenx/cosign)**, which keeps
your account from sitting whole in any one place: it's split into pieces, each of your
devices holds 3, each server 1, and it takes 4 to act for you. Marble uses Cosign only
through its library, the way any other app can. **Any app can build Cosign in**, and
apps that do will be featured above Marble wherever a site suggests a sign-in app.

**Beta.** Cosign's signing maths is the Zcash Foundation's audited FROST library; its own
protocol around it hasn't had an outside review yet.

## What it does

- **Create an account that no one ever holds whole**: your phone and your servers make
  it together. Or **bring an account you already have** over from a web app in one scan.
- **Sign in to sites** by scanning their code and tapping *Connect*. Each site gets
  access of its own, which you remove with one tap. **Other apps on your phone sign in
  through Marble.**
- **Everyday things go through at once.** Destructive ones (most of the people you
  follow gone, your profile wiped, a pile of deletions) ask you first.
- **Undo.** Every change to your lists is kept, with Undo. Deletions wait 10 seconds,
  and Marble keeps a copy with *Post again*.
- **Add your other devices** by scanning a code and confirming with your phone's lock.
  **Report a device lost**, and its pieces become worthless.
- **Paper backup**: a code to print or keep in a password manager, which gets your
  account back on a new phone after a wait your other devices can stop.
- **Take your account out** whenever you like.

## Getting Marble

Releases are on GitHub (github.com/sybenx/marble/releases), so Obtainium can follow
them: add `https://github.com/sybenx/marble` as an app. The newest build is also at
https://cosign.aaro.cc/cosign.apk.

For approvals while Android has put Marble to sleep, install **ntfy**. Without it,
approvals arrive while Marble is running in the background.

The browser extension is in `extension/` (Chrome, Edge, Brave, Firefox, Safari).

## Privacy

- **Your servers see what you sign through them**, and nothing else: never your
  messages, and never enough of your account to act as you.
- **Searching for people** sends what you type to the servers Marble searches. When a
  result shows an address like `name@example.com`, Marble asks that site whether the
  address really belongs to the person shown. That site can see your IP address while it
  answers.
- **The feed loads pictures and videos from wherever they are kept**, which is usually
  not the place the post came from. Those places can see your IP address, as they can in
  any app that shows you a picture.

## Building

```sh
git clone --recursive https://github.com/sybenx/marble && cd marble
(cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest)
(cd extension && npm install && node build.mjs)
```

- **Cosign** is a git submodule at `cosign/`. `android/settings.gradle.kts` includes its
  build (`includeBuild("../cosign/android")`), and the app depends on
  `cc.aaro.cosign:cosign`, which Gradle builds from that source. There is no published
  package yet; the submodule pins the exact Cosign commit each Marble build uses. To
  take a newer Cosign, check it out in `cosign/` and commit the new pointer.
- **The Android SDK**: set `ANDROID_HOME`, or put `sdk.dir` in a `local.properties` in
  both `android/` and `cosign/android/` (Gradle reads one per build).
- The app keeps the id `cc.aaro.cosign` and the Kotlin packages it had in the Cosign
  repository, so every build installs over the ones before it.
- **Releases**: `./release.sh` (bump `versionCode` and `versionName` in
  `android/app/build.gradle.kts` first). The release signing files stay outside any
  repository (`~/cosign-release`). `./zapstore.sh` publishes the newest release to
  Zapstore. Notes for each version are in `release-notes/`.

Marble's history up to 0.12.4 is in the Cosign repository (tag `v0.12.4`); the
extension's history came along with it.

## License

MIT.
