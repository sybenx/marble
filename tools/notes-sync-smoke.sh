#!/bin/zsh
# Release smoke test for notes sync: a note written on one client is read on another. Fails (exit 1) when it isn't.
#   tools/notes-sync-smoke.sh            (release.sh runs it before building; run it before `npx wrangler deploy` too)
#
#  1. Web engine (site/js/vault/): two browsers and a fresh one, across a new key generation, with a signer that
#     refuses once (tools/notes-sync-smoke.test.mjs), plus the web engine's own sync checks, and the page when a
#     storage server refuses (tools/vault-refusals-page.test.mjs, headless Chromium; skipped without Playwright).
#  2. Android engine (android/vaults): phone, tablet and a fresh install, the same way (SyncSmokeTest).
#  3. Android ↔ Web for real, over a local relay and two Blossom servers (android/tools/vaults-test-server.ts, test keys
#     only): Android's engine makes a notes vault and removes 20 devices from it, Web reads it and edits a note, Android reads Web's edit and edits
#     another, and a fresh Web device reads Android's edit.
# Gradle runs under ~/.claude/heavy-lock.sh when it's there. The local servers are stopped on exit, failure included.
set -e
cd "$(dirname "$0")/.."
ROOT=$PWD
export JAVA_HOME=${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}
export ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
# (NOTES_SMOKE_LOCK="" when the caller already holds a heavy-lock slot, as a locked release.sh run does)
LOCK=${NOTES_SMOKE_LOCK-$HOME/.claude/heavy-lock.sh}; [ -x "$LOCK" ] || LOCK=""
P1=${SMOKE_PORT:-7457}; P2=$((P1 + 1))
WORK=$(mktemp -d -t notes-smoke)
PIDS=()
cleanup() { local s=$?; for p in $PIDS; do kill $p 2>/dev/null; done; wait 2>/dev/null; rm -rf "$WORK"; exit $s; }
trap cleanup EXIT INT TERM
fail() { echo "NOTES SYNC SMOKE FAILED: $1"; exit 1; }

if [ -n "$SMOKE_ONLY_CROSS" ]; then :; else
echo "1. web engine"
node tools/notes-sync-smoke.test.mjs
node tools/vaults-sync.test.mjs | tail -1
# (Marble Web's Notes in headless Chromium when a storage server refuses: the change waits, said so, and goes up later)
node tools/vault-refusals-page.test.mjs || fail "web refusals page test"

echo "2. Android engine"
(cd android && $LOCK ./gradlew -q :vaults:testDebugUnitTest --tests cc.aaro.vaults.SyncSmokeTest --tests cc.aaro.vaults.EngineTest) || fail "Android engine tests"

fi
echo "3. Android ↔ Web on local servers (ports $P1, $P2)"
for p in $P1 $P2; do lsof -nP -iTCP:$p -sTCP:LISTEN >/dev/null 2>&1 && fail "port $p is taken (SMOKE_PORT=<port> picks others)"; done
bun android/tools/vaults-test-server.ts $P1 "$WORK/s1.json" >"$WORK/s1.log" 2>&1 & PIDS+=($!)
bun android/tools/vaults-test-server.ts $P2 "$WORK/s2.json" >"$WORK/s2.log" 2>&1 & PIDS+=($!)
for i in {1..50}; do curl -s "http://127.0.0.1:$P1/" >/dev/null && curl -s "http://127.0.0.1:$P2/" >/dev/null && break; sleep 0.2; done
export VAULTS_RELAY="ws://127.0.0.1:$P1" VAULTS_SERVERS="http://127.0.0.1:$P1,http://127.0.0.1:$P2"
android_run() { echo "  (android: ${1:0:20}…)" >&2; (cd android && VAULTS_INTEROP="$1" $LOCK ./gradlew -i :vaults:testDebugUnitTest --tests cc.aaro.vaults.InteropTest 2>&1) | grep -E "^\s*(NOTE|CREATED|EDITED|DEVICES)" | sed 's/^ *//'; }

# (20 other devices join and are removed first: the moves to new generations then carry 20 revocations)
OUT=$(android_run create:20); echo "$OUT" | grep CREATED
NSEC=$(echo "$OUT" | sed -n 's/.*CREATED nsec=\([0-9a-f]*\).*/\1/p')
[ -n "$NSEC" ] || fail "Android's engine didn't make a vault"
# (each Android run is the same device: its store is kept in android/vaults/build/interop-<npub>)
R=$(node tools/vault-read.mjs "$NSEC" --brief) || fail "Web couldn't open Android's vault"
echo "$R" | grep -q '"Buy milk"' || fail "Web doesn't see Android's note"
echo "$R" | grep -q '"After the checkpoint"' || fail "Web doesn't see Android's note written after its checkpoint"
echo "$R" | grep -q '"After 20 removals"' || fail "Web doesn't see Android's note written after 20 devices were removed"
echo "  Android → Web: read"
node tools/vault-read.mjs "$NSEC" --edit c000000000000001 "Buy oat milk (edited on Web)" >/dev/null || fail "Web couldn't write"
A=$(android_run "open:${NSEC}:c000000000000002=Done one (edited on Android)")
echo "$A" | grep -q "c000000000000001.*Buy oat milk (edited on Web)" || { echo "$A"; fail "Android doesn't see Web's edit"; }
echo "$A" | grep -q EDITED || fail "Android couldn't write"
echo "  Web → Android: read"
R=$(node tools/vault-read.mjs "$NSEC" --brief)
echo "$R" | grep -q '"Done one (edited on Android)"' || { echo "$R"; fail "a fresh Web device doesn't see Android's edit"; }
echo "$R" | grep -q '"Buy oat milk (edited on Web)"' || fail "a fresh Web device doesn't see Web's edit"
echo "  Android → fresh Web: read"
rm -rf android/vaults/build/interop-*
echo "notes sync smoke: all passed"
