# Notes in a vault

Marble's notes live in a vault (`~/nostr-sync-nip/vaults.md`, draft 7.14, the `notes` profile). The engine is the
`vaults` module (`cc.aaro.vaults`); the app's side is `app/…/notes/VaultNotes.kt` (Later's items to and from versions),
`VaultNet.kt` (relays, Blossom, the signer) and `VaultStore.kt` (the vault's state on the phone, sealed by a Keystore
key, not backed up).

## What the app does

- **First run after the update**: finds the account's notes vault from its keyring entries (the lowest genesis id
  among owner entries with profile `notes`), or makes one. Relays: your write relays, or Marble's defaults if fewer
  than two. Blossom: your kind 10063 servers as you list them, or Marble's defaults (nostr.download,
  blossom.yakihonne.com) if you have no list; nothing is published for you.
- **Blobs** (decided 2026-11-18): each goes to the first two servers on your kind 10063 list, as published now, that
  take it, with the Blossom auth signed by your npub (through the signer or Keykin, no prompt). A 401, 403 or 415 skips
  to the next server; the vault's debug state shows why under `refused`. Reads try your list, then the vault's
  config servers. Notes are at most 500 characters, so only the import, segments and checkpoints make blobs.
- **Import, once** (§17): the v3 packs and `marble/later` (removals included, as deleted versions) go in as one batch,
  only for entries the vault never had. Until the import reads back whole, the packs are still read (read only) for
  display. Nothing writes packs or `marble/later` any more; the old events stay.
- **Writing**: a version half a minute after a change, and at once on leaving.
- **Conflicts**: "2 versions" under a note; both texts side by side with Keep this, Keep both and Edit.
- **Trash**: removed notes, with Restore. Empty Trash asks Yes or No (§12.4) and only forgets them on this phone.
- **Removal**: at each catch-up the vault asks Keykin for `members`; a member blocked is distrusted, one gone after a
  cycle is revoked, and a new generation starts that the removed device can't open.

The mapping (meta, entry ids, data) is agreed with Marble Web; see the side-session notes `vaults-android.md`.

## Not built yet

Sharing and readers (invitations, wraps, contributions taken in), the pinner, the monthly audit and repair of
Blossom copies (§6.4), garbage collection (§6.5), forks and re-rooting (§7.4), `sync-request`, the mass-change
notice (§10), History. Deleting blobs (§6.5) waits for the spec: under npub auth one device's delete removes
every device's copy.

## Tests

- `./gradlew :vaults:testDebugUnitTest`: the shared vectors (`VectorsTest`, verify.mjs's 81 checks plus remakes; copy
  `vectors/vaults.json` into `vaults/src/test/resources` when it changes) and `EngineTest` (spec §18 for notes in
  memory: conflicts, checkpoints and segments, a batch, removal, tests 10, 25, 33, 36, 37).
- `./gradlew :app:testDebugUnitTest --tests '*VaultNotesTest*'`: the import of real v3 packs, two devices, a conflict,
  Trash, a blob note.
- On an emulator, with a throwaway Keykin box (`DeviceTest`: the removal and tests 33 and 36 against Marble itself):

  ```
  bun tools/vaults-test-server.ts 7447 & bun tools/vaults-test-server.ts 7448 & bun tools/vaults-box.ts &
  adb reverse tcp:7447 tcp:7447; adb reverse tcp:7448 tcp:7448; adb reverse tcp:7460 tcp:7460
  adb shell pm clear cc.aaro.cosign
  KEYKIN_BOX=http://127.0.0.1:7461 KEYKIN_FROSTLIB=~/keykin/frost/target/release/libkeykin_frost.dylib \
    ANDROID_SERIAL=emulator-5586 ./gradlew :vaults:testDebugUnitTest --tests '*DeviceTest*'
  ```

- With Marble Web's core: `VAULTS_INTEROP=open:<nsec>[:<id>=<text>][:done=<id>][:R<id>=<text>]` or `create`
  (`InteropTest`), against the same local servers. `probe:<server>,…` runs the §6.4 probe against real servers.
- Debug intents: `--es vault state|sync|save|remove|edit|checkpoint|lost --es arg …`,
  `--es vault_servers http://127.0.0.1:7447,http://127.0.0.1:7448` with `--ez local_only true --es relay ws://127.0.0.1:7447`;
  the vault logs under `MarbleVault`.
