#!/bin/sh
# Ships the app index builder to Took the way feedback-took-remote-deploys says: built on the Mac, one archive,
# extracted into a NEW folder, then swapped by rename with the old folder kept as .prev. Nothing is installed on Took
# (the build is one bundled file; H:\Programs\bun\bun.exe runs it).
#
#   sh apps-index/deploy-took.sh stage   # bundle, copy, extract to H:\Programs\marble-apps-index.new
#   sh apps-index/deploy-took.sh swap    # marble-apps-index -> .prev, .new -> marble-apps-index
#
# Once (needs Aaron's OK, it's a scheduled task): see README.md "On Took". serve-feed.ts needs took/serve-feed.ts.
set -eu
cd "$(dirname "$0")"
case "${1:-}" in
  stage)
    bun build build.ts --target=bun --outfile dist/apps-index.js
    rm -f dist/marble-apps-index.tgz
    tar -czf /tmp/marble-apps-index.tgz -C dist apps-index.js -C ../took run-apps-index.cmd
    mv /tmp/marble-apps-index.tgz dist/marble-apps-index.tgz
    scp -q dist/marble-apps-index.tgz took:'H:/Programs/marble-apps-index.tgz'
    ssh took '(if exist H:\Programs\marble-apps-index.new rmdir /s /q H:\Programs\marble-apps-index.new) & mkdir H:\Programs\marble-apps-index.new && tar -xzf H:\Programs\marble-apps-index.tgz -C H:\Programs\marble-apps-index.new && dir /b H:\Programs\marble-apps-index.new'
    ;;
  swap)
    # Nothing runs from the folder between builds (the task runs once a day for ~3 min); state and logs live beside it.
    ssh took 'cd /d H:\Programs && (if exist marble-apps-index.prev rmdir /s /q marble-apps-index.prev) & (if exist marble-apps-index ren marble-apps-index marble-apps-index.prev) & ren marble-apps-index.new marble-apps-index && dir /b marble-apps-index'
    ;;
  *) echo "usage: $0 stage|swap" >&2; exit 2 ;;
esac
