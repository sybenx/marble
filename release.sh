#!/bin/sh
# Build Marble for Android and publish it as a GitHub release of github.com/sybenx/marble
# (which Obtainium follows), with two copies of the APK: Marble-<version>.apk, and
# Marble.apk, so https://github.com/sybenx/marble/releases/latest/download/Marble.apk is
# always the newest. Every build is signed with the same key, so each one installs over
# the last.
#
#   ./release.sh        (bump versionCode and versionName in android/app/build.gradle.kts first)
set -e
cd "$(dirname "$0")"
# The Cosign library is built from the submodule, at the commit this repository records.
[ -f cosign/android/settings.gradle.kts ] || git submodule update --init
export ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
VERSION=$(sed -n 's/.*versionName = "\(.*\)"/\1/p' android/app/build.gradle.kts)
[ -n "$VERSION" ] || { echo "no versionName"; exit 1; }
[ -e "Marble-$VERSION.apk" ] && { echo "Marble-$VERSION.apk already exists: bump the version first"; exit 1; }

(cd android && JAVA_HOME=${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home} ./gradlew -q :app:assembleRelease)
rm -f Marble-*.apk Marble.apk
# Signed with the app's release key (kept off the repo, in $COSIGN_KEYS), with the
# lineage that proves it took over from the debug key the first builds were signed
# with, so every build still installs over the last (APK key rotation, Android 9+).
KEYS=${COSIGN_KEYS:-$HOME/cosign-release}
if [ -f "$KEYS/cosign-release.jks" ]; then
  BT=$(ls -d ${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}/build-tools/* | sort -V | tail -1)
  JAVA_HOME=${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home} "$BT/apksigner" sign \
    --ks "$KEYS/old-debug.keystore" --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android \
    --next-signer --ks "$KEYS/cosign-release.jks" --ks-pass "file:$KEYS/password.txt" --ks-key-alias cosign \
    --lineage "$KEYS/lineage.bin" --rotation-min-sdk-version 28 \
    --out "Marble-$VERSION.apk" android/app/build/outputs/apk/release/app-release.apk
  rm -f "Marble-$VERSION.apk.idsig"
else
  echo "no release key in $KEYS: signing with the debug key"; cp android/app/build/outputs/apk/release/app-release.apk "Marble-$VERSION.apk"
fi
cp "Marble-$VERSION.apk" Marble.apk

git add -A
git diff --cached --quiet || git commit -q -m "Release Marble $VERSION

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
# Push even when there was nothing to commit: the release tag is made from origin's main.
git push -q origin main || { echo "push failed: not releasing"; exit 1; }
# Notes: release-notes/<version>.md if there is one, then the same lines every release has.
NOTES="Android build of Marble $VERSION. Install over the previous version; your account stays on your phone.

Updating from 0.11.0 or older with Obtainium: 0.11.1 moved to a new signing certificate (Android's rotation, which keeps your data). Obtainium warns that the certificate changed; choose Install anyway, or install this version once from https://github.com/sybenx/marble/releases/latest/download/Marble.apk. Either way your account stays. Don't uninstall."
[ -f "release-notes/$VERSION.md" ] && NOTES="$(cat "release-notes/$VERSION.md")

$NOTES"
gh release create "v$VERSION" "Marble-$VERSION.apk" Marble.apk --repo sybenx/marble --title "Marble $VERSION" --notes "$NOTES"
echo "Marble $VERSION is at https://github.com/sybenx/marble/releases/latest/download/Marble.apk"
