#!/bin/zsh
# Release smoke test for search: Marble Web and Marble Android find what you typed. Fails (exit 1) when they don't.
#   tools/search-smoke.sh               (release.sh runs it before building; run it before `npx wrangler deploy` too)
#   SEARCH_OFFLINE=1 tools/search-smoke.sh    (no network: the offline part only)
#
#  1. Offline, every time: fake relays that answer the way real ones do at their worst (a search that takes a post
#     with ANY of the words, one that ignores the search and sends everything, an archive that matches any word,
#     copycats of fiatjaf), from one world (tools/search-fixtures.mjs / .json):
#       web:     the real site in headless Chromium (tools/search-page.test.mjs), and the helpers' own tests
#       Android: the real search (SearchRun) against FakeSearchRelay on 127.0.0.1 (SearchSmokeTest), and its helpers
#  2. Live, read only, against the public relays: "failed" finds posts with the word; "from:fiatjaf failed" only
#     fiatjaf's (3bf0c63f…) posts with "failed". Web: tools/search-live.mjs. Android: SearchLiveTest.
# Gradle and Chromium run under ~/.claude/heavy-lock.sh when it's there. Nothing is left running: the browsers close in
# each test's teardown, the fake relays with the test JVM, and anything still running is stopped on exit.
set -e
setopt pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME=${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}
export ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
# (SEARCH_SMOKE_LOCK="" when the caller already holds a heavy-lock slot)
LOCK=${SEARCH_SMOKE_LOCK-$HOME/.claude/heavy-lock.sh}; [ -x "$LOCK" ] || LOCK=""
cleanup() { local s=$?; pkill -P $$ 2>/dev/null; wait 2>/dev/null; exit $s; }
trap cleanup EXIT INT TERM
fail() { echo "SEARCH SMOKE FAILED: $1"; exit 1; }
node tools/search-fixtures.mjs >/dev/null # (the world loads)

echo "1. offline (fake relays)"
echo "  web"
node tools/author-query.test.mjs || fail "web: from:/@name helpers (tools/author-query.test.mjs)"
node tools/archive.test.mjs | tail -1 || fail "web: archive helpers (tools/archive.test.mjs)"
$LOCK node tools/search-page.test.mjs || fail "web: search in the page (tools/search-page.test.mjs)"
echo "  Android"
(cd android && $LOCK ./gradlew -q :app:testDebugUnitTest --tests cc.aaro.cosign.SearchSmokeTest --tests cc.aaro.cosign.AuthorQueryTest --tests cc.aaro.cosign.PrivateRelaysTest) \
  || fail "Android: search against fake relays (SearchSmokeTest; report: android/app/build/reports/tests/testDebugUnitTest/index.html)"

if [ -n "$SEARCH_OFFLINE" ]; then echo "2. live: SKIPPED (SEARCH_OFFLINE)"; echo "search smoke: offline part passed"; exit 0; fi
echo "2. live (public relays, read only)"
echo "  web"
$LOCK node tools/search-live.mjs || fail "web, live: see the step and relays above (offline? SEARCH_OFFLINE=1)"
echo "  Android"
(cd android && SEARCH_LIVE=1 $LOCK ./gradlew -q :app:testDebugUnitTest --tests cc.aaro.cosign.SearchLiveTest --rerun) || {
  # (what failed, with each step and relay, from the test's report)
  for f in android/app/build/test-results/testDebugUnitTest/TEST-cc.aaro.cosign.SearchLiveTest.xml(N); do
    awk '/<failure/{on=1; sub(/.*">/, "")} on && /^\tat /{on=0} on' "$f" | sed 's/&quot;/"/g; s/&lt;/</g; s/&gt;/>/g; s/&amp;/\&/g; s/&#10;//g'
  done
  fail "Android, live: see the step and relays above (offline? SEARCH_OFFLINE=1)"
}
echo "search smoke: all passed"
