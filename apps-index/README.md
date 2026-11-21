# apps-index: the list Marble's app search searches

`build.ts` builds `apps.json`, the Nostr web apps and nsites that Marble (Android and Marble Web) searches. The phone
and the page download it once a day and match it themselves, so nothing typed is sent anywhere. Research and plan:
`~/igloo/marble-app-search-research.md`.

## What goes in

| Source | Kinds | Relays |
|---|---|---|
| nsites (NIP-5A) | 15128 root, 35128 named | relay.ditto.pub, relay.nsite.lol, purplepag.es, relay.primal.net, syb.lol |
| NIP-89 apps and who recommends them | 31990, 31989 | same |
| Zapstore apps | 32267 | relay.zapstore.dev only (it accepts only publishers Zapstore lets in) |

Rules, in order:

1. **Trust gate.** The author is the seed account (Aaron), someone it follows, someone they follow, or in the
   nostr-builders directory. Everything else is dropped as it arrives. Zapstore listings count because Zapstore's
   relay vets publishers, except catalogs (a publisher with over 40 listings, like "Zapstore Catalog", which
   republishes general FOSS apps).
2. **Signatures.** Every event kept is checked (id and Schnorr signature), so a relay can't plant an app.
3. **Apps by domain.** Zapstore `url` and NIP-89 `web` URLs are grouped by domain. `/.well-known/nostr.json` on that
   domain says which keys are official (all names when it lists 20 or fewer, else only `_`).
4. **nsites.** Need `/index.html`, an address a gateway can serve (`d` of 1 to 13 of `[a-z0-9-]`), and no SEO spam
   title. One published by a key the app's domain names is attached to that app (and makes it official); the rest
   stand alone and open at `https://<npub>.nsite.lol/` or `https://<base36 key><d>.nsite.lol/`.
5. **Liveness.** Each nsite's `index.html` is fetched from its Blossom servers and its hash checked. Three failed
   builds in a row and it's dropped (`STATE` keeps the count).
6. **Copies and floods.** An nsite with the same aggregate hash or name as a better entry is counted as a copy, not
   listed. At most 12 nsites per author.
7. **Fail closed.** Fewer than 200 apps, or under 60% of the last good build: the old file stays and the build exits 3.

Score (the phone and the page add text match and "you follow them"): official 40, web app 10, on Zapstore 10,
recommenders `8 x log2(1 + n)`, the seed's network `0.6 x min(40, follows + 25 if followed directly)`, nostr-builder 5,
live 5, updated in 90 days 3, nsite substance (3+ files 3, else -10; no description -3), Android-only -15.

## Your own sites (own-apps/)

A site is only in the index if a trusted key has published a NIP-89 handler (kind 31990) or an nsite for it. Typing an
address that isn't there still works in Marble: the "Go to" row's "In Marble" button opens any https site on the web pane,
where Keep makes it a chip. `own-apps/` holds the unsigned handler events for Aaron's own sites (astral.syb.lol,
discover.syb.lol). Each domain's `/.well-known/nostr.json` names Aaron's key as `_`, so the listing counts as official.
Sign and publish one with your own key (nak asks nothing else; `--sec` takes an nsec, ncryptsec or bunker URL):

    nak event --sec <yours> wss://relay.ditto.pub wss://purplepag.es wss://relay.primal.net wss://syb.lol < own-apps/astral.json

It's in the next daily build. Republishing with the same `d` replaces it.

## apps.json

```json
{ "v": 1, "generated": "2026-11-10T13:15:44Z", "gateway": "nsite.lol", "apps": [
  { "a": "31990:<pk>:<d>", "kind": "web", "name": "Jumble", "about": "…", "icon": "https://…", "url": "https://jumble.social/",
    "domain": "jumble.social", "site": "35128:<pk>:jumble", "gw": "https://…nsite.lol/", "pk": "<hex>", "by": "Cody",
    "official": true, "zapstore": "com.codytseng.jumble", "recs": 1, "follows": 73, "copies": 3, "live": true,
    "updated": 1794300000, "score": 92 } ] }
```

`kind` is `web` (opens on its own domain), `nsite` (opens through the gateway) or `android` (opens its Zapstore page).
`follows` is how many of the seed's follows follow the author; it's the builder's network, so clients don't show it as
a number. The matching rules are `site/js/apps.js` and `AppIndex.kt`; `bun apps-index/vectors.ts` rewrites the shared
test vectors (`android/app/src/test/resources/apps/vectors.json`) from the JS, and `AppIndexVectorsTest` checks Kotlin.

## Run

```sh
cd apps-index && bun install && OUT=public/apps.json STATE=state.json bun build.ts
```

On the Mac 2026-11-10: about 3 minutes, 15 s of CPU, 215 MB peak; 538 apps (193 web, 284 nsites, 61 Android), 355 KB
(about 75 KB gzipped).

## On Took (deployed 2026-11-10, Aaron's OK)

Live: `H:\Programs\marble-apps-index` (bundle + `run-apps-index.cmd`), `serve-feed.ts` swapped (old one at
`H:\Programs\diamonds-feed\serve-feed.ts.bak-2026-11-10`), first build run by hand: 546 apps at
https://discover.syb.lol/apps.json. **The daily task is not created yet**: this session's permission check refused
it as a persistence change, so step 3 is for Aaron to run (or approve) himself.

Took has no hands, so (feedback-took-remote-deploys): built on the Mac as one bundled file, one archive, a new
folder, a swap by rename, `.prev` kept. Nothing is installed on Took; `H:\Programs\bun\bun.exe` runs the bundle.

1. `sh apps-index/deploy-took.sh stage` then `sh apps-index/deploy-took.sh swap` puts it in `H:\Programs\marble-apps-index`
   (`apps-index.js`, `run-apps-index.cmd`). State and logs live beside it (`marble-apps-index-state.json`,
   `marble-apps-index-logs\`) so the swap never touches them.
2. Serve it: replace `H:\Programs\diamonds-feed\serve-feed.ts` with `took/serve-feed.ts` (keep the old one as
   `serve-feed.ts.bak-<date>`; the only change is `/apps.json` served from `public/` like `/diamonds.json`, cached an
   hour) and end its `bun.exe serve-feed.ts` process; `run-feed.cmd`'s loop restarts it in 5 s. The discover.syb.lol
   Worker already passes `/apps.json` through, so no Worker change.
3. Daily task (SYSTEM is fine, it only needs the network), at an hour nobody plays, killed after 30 minutes. In an
   elevated PowerShell on Took:
   ```powershell
   Register-ScheduledTask -TaskName marble-apps-index -Action (New-ScheduledTaskAction -Execute cmd.exe -Argument '/c H:\Programs\marble-apps-index\run-apps-index.cmd') -Trigger (New-ScheduledTaskTrigger -Daily -At 05:30) -Settings (New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Minutes 30) -Priority 7 -StartWhenAvailable -MultipleInstances IgnoreNew) -Principal (New-ScheduledTaskPrincipal -UserId SYSTEM -LogonType ServiceAccount)
   ```
   Log: `H:\Programs\marble-apps-index-logs\build.log`. State: `H:\Programs\marble-apps-index-state.json`.

It's light (one Bun process, Idle priority, a few minutes, nothing on C:\mc), so it doesn't take `heavy-job.lock`.
Rollback: `schtasks /delete /tn marble-apps-index /f`; copy `serve-feed.ts.bak-2026-11-10` over `serve-feed.ts` and end
its `bun.exe serve-feed.ts` (the loop restarts it); delete `H:\Programs\marble-apps-index` (or rename `.prev` back after
a later deploy) and `H:\Programs\diamonds-feed\public\apps.json`.
