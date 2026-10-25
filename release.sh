#!/bin/sh
# Build Marble for Android and publish it as a GitHub release (which Obtainium follows:
# github.com/sybenx/marble) and on cosign.aaro.cc.
#
#   ./release.sh        (bump versionCode and versionName in android/app/build.gradle.kts first)
#
# The page links the newest build as Cosign-<version>.apk; /cosign.apk is always the
# newest too, for people. Every build is signed with the same key, so each one installs
# over the last.
#
# cosign.aaro.cc is the Cosign repository's site (link/ there): this puts the APK on it
# from your checkout of Cosign, $COSIGN (default ~/cosign), deploys it, and commits the
# page's new link there without pushing.
set -e
cd "$(dirname "$0")"
COSIGN=${COSIGN:-$HOME/cosign}
[ -d "$COSIGN/link/public" ] || { echo "no Cosign checkout at $COSIGN (set COSIGN)"; exit 1; }
# The Cosign library is built from the submodule, at the commit this repository records.
[ -f cosign/android/settings.gradle.kts ] || git submodule update --init
export ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
VERSION=$(sed -n 's/.*versionName = "\(.*\)"/\1/p' android/app/build.gradle.kts)
[ -n "$VERSION" ] || { echo "no versionName"; exit 1; }
[ -e "Cosign-$VERSION.apk" ] && { echo "Cosign-$VERSION.apk already exists: bump the version first"; exit 1; }

(cd android && JAVA_HOME=${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home} ./gradlew -q :app:assembleRelease)
rm -f Cosign-*.apk "$COSIGN"/link/public/Cosign-*.apk
# Signed with Cosign's release key (kept off the repo, in $COSIGN_KEYS), with the
# lineage that proves it took over from the debug key the first builds were signed
# with, so every build still installs over the last (APK key rotation, Android 9+).
KEYS=${COSIGN_KEYS:-$HOME/cosign-release}
if [ -f "$KEYS/cosign-release.jks" ]; then
  BT=$(ls -d ${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}/build-tools/* | sort -V | tail -1)
  JAVA_HOME=${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home} "$BT/apksigner" sign \
    --ks "$KEYS/old-debug.keystore" --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android \
    --next-signer --ks "$KEYS/cosign-release.jks" --ks-pass "file:$KEYS/password.txt" --ks-key-alias cosign \
    --lineage "$KEYS/lineage.bin" --rotation-min-sdk-version 28 \
    --out "Cosign-$VERSION.apk" android/app/build/outputs/apk/release/app-release.apk
  rm -f "Cosign-$VERSION.apk.idsig"
else
  echo "no release key in $KEYS: signing with the debug key"; cp android/app/build/outputs/apk/release/app-release.apk "Cosign-$VERSION.apk"
fi
cp "Cosign-$VERSION.apk" "$COSIGN/link/public/Cosign-$VERSION.apk"
cp "Cosign-$VERSION.apk" "$COSIGN/link/public/cosign.apk"
sed -i '' -E "s#/Cosign-[0-9.]+\.apk\">Cosign-[0-9.]+\.apk#/Cosign-$VERSION.apk\">Cosign-$VERSION.apk#" "$COSIGN/link/public/index.html"
grep -q "Cosign-$VERSION.apk" "$COSIGN/link/public/index.html" || { echo "couldn't update the page's link"; exit 1; }

# The live list of public servers (cosign.aaro.cc/servers), from site/src/servers.ts.
(cd "$COSIGN/site" && npx esbuild src/servers-page.ts --bundle --format=iife --minify --target=es2022 --outfile=../link/public/servers.js --log-level=warning)
(cd "$COSIGN/link" && wrangler deploy)
git -C "$COSIGN" commit -q -m "cosign.aaro.cc: Marble $VERSION" -- link/public/index.html link/public/servers.js || true
git add -A && git commit -q -m "Release Cosign $VERSION

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" && git push -q origin main
# Notes: release-notes/<version>.md if there is one, then the same lines every release has.
NOTES="Android build of Cosign $VERSION. Install over the previous version; your phone stays in your key.

Updating from 0.11.0 or older with Obtainium: 0.11.1 moved to a new signing key (Android's key rotation, which keeps your data). Obtainium warns that the certificate changed; choose Install anyway, or install this version once from https://cosign.aaro.cc. Either way your keys stay. Don't uninstall."
[ -f "release-notes/$VERSION.md" ] && NOTES="$(cat "release-notes/$VERSION.md")

$NOTES"
gh release create "v$VERSION" "Cosign-$VERSION.apk" --title "Cosign $VERSION" --notes "$NOTES"
echo "Cosign $VERSION is at https://cosign.aaro.cc/Cosign-$VERSION.apk"
echo "$COSIGN has the page's new link committed, not pushed."
