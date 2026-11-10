@echo off
rem Builds apps.json for Marble's app search: once a day from the scheduled task "marble-apps-index", at Idle priority
rem (about 3 min, 15 s of CPU, 250 MB). Writes H:\Programs\diamonds-feed\public\apps.json, which serve-feed.ts serves as
rem diamonds.syb.lol/apps.json (and discover.syb.lol/apps.json through its Worker). A gutted build (relays down) exits
rem non-zero and keeps yesterday's file. No loop and no cd, so the folder can be swapped between runs.
set DIR=H:\Programs\marble-apps-index
set OUT=H:\Programs\diamonds-feed\public\apps.json
set STATE=H:\Programs\marble-apps-index-state.json
if not exist H:\Programs\marble-apps-index-logs mkdir H:\Programs\marble-apps-index-logs
echo ---- %date% %time% >> H:\Programs\marble-apps-index-logs\build.log
start "" /low /wait /b H:\Programs\bun\bun.exe %DIR%\apps-index.js >> H:\Programs\marble-apps-index-logs\build.log 2>&1
