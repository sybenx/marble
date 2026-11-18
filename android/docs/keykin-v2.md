# Marble on Keykin v2: what carried over

Written 2026-11-17, on branch `claude/keykin-v2-signing-f608b8`. Not merged, not released.

Marble's signer used the older Cosign library: weighted pieces (each device 3, each server 1, 4
to act) on public Cosign servers. It now uses Keykin v2: every device and one box hold one
piece each, any two sign, and there are no public servers. The plan is `PLAN.md` in the Keykin
repo, and the rules are its `SPEC.md`.

## How it is built

- **`android/signer/`** replaces the Cosign library from the `cosign` submodule. It began as a
  copy of that library, so the seam to the app is the same: the signer process (`:signer`),
  `Accounts`, `Phone`, `Ui`, `SignerService`, NIP-55. The screens kept working with few changes.
- **The engine under the seam is new:** `Signer.kt`, built on the Keykin repo's Kotlin core
  (copied into `signer/src/main/java/cc/aaro/keykin/core/`, with its native FROST library).
- **The `cosign` submodule is still there for one thing:** the QRST library (sign-in by a
  scanned code), which lives in its own submodule inside it.
- **Fingerprint:** a P-256 key in the Android Keystore for each account on the phone
  (`Chip.kt`). It signs only after a fingerprint, and the box checks the signature.

## Verified on the `keykintest` emulator, against a throwaway box

| What | Result |
|---|---|
| Make an account with the box's code | Works |
| Join an account as the new device (invite, then the join text) | Works |
| Sign an event (Marble's own) | Signature verifies |
| NIP-44 to self, NIP-44 and NIP-04 with another key | Match a standard library, both ways |
| NIP-55: connect, quiet signing, the hold on a destructive event, message permission, NIP-44, NIP-04 | Works, with the NIP-55 tester app |
| Sign in to a site by a scanned code (QRST) | Works |
| Sign in by a pasted code, comparing five digits | Works |
| Sign in by `nostrconnect://` (this is how marble.syb.lol signs in) | Works |
| Sign in by `bunker://`, and the link refused a second time | Works |
| A destructive request from a site waits for Yes on the phone | Works |
| Add a device, fingerprint (the box refuses without it) | Works. The new device then signed |
| Report lost: block, then cycle the keys with a fingerprint | Works. The blocked device is refused |
| Form the whole key, fingerprint, shown only by the signer's secure screen | Works |
| Log a browser in to the box's page by its code, fingerprint | Works |
| Username and password for the account page, fingerprint | Works. The box accepted the password |
| The login-page pointer event | Published and read back, signature verifies |
| Sites and apps reported to the box; revoke from the page; revoke from the phone | Works both ways |
| The account page asks the phone: bunker link, `nostrconnect://` code, add a device | Works, no fingerprint (Aaron, 2026-11-17) |
| Keykin Login (password session) leaves a `nostrconnect` note; Marble answers it with the app closed (task removed, service running), the test site signs a kind 1, and the grant shows on the account page (2026-11-18) | Works. Answered in 21 s and 29 s: the service polls the box every 30 s |
| The account page asks to remove blocked devices: Yes and No on the phone | Works. No signs that browser out |
| This phone blocked from the page, then unblocked | Works. The phone says it is blocked |
| Two accounts on one phone and one box | Each signs under its own key |
| Undo a change to the follow list | The earlier version is signed and published again |
| Remove this phone from an account | Blocked at the box, wiped here, other account untouched |
| The app restarted with an account and sites | Sites keep working |
| Unit tests | 289 in the app and 10 in the signer pass |
| Bring in an existing nsec | Same npub, signature verifies, the key is kept across a restart, and Forget removes it |
| Release build | Assembles, and keeps the native signing library. Not run |

## Not verified

- **On a real phone.** Everything above is the emulator. The camera scan was not used: codes
  were fed in as text.
- **A release build running.** It cannot reach a plain `http` box (see below).
- **Sign-in from the web pane** (Marble Web and Jumble inside the app). It uses the same code
  path as a scanned code, but was not tried in the pane.
- **Unblock from the app.** The button and the call are there. Unblock was tried from the
  account page only.
- **"Undo all" for a site, and posting a deleted thing again.** Built, not tried.
- **Approve and Deny in a notification.** The in-app card was tried.
- **Staying connected for hours in the background.**

## Every account and signing feature, old to new

### Carried over

| Old | New |
|---|---|
| I already have an account (paste an nsec) | Unchanged to use: paste the nsec, then scan the box's code. The phone splits the key with the box and signs with the pieces. It keeps the whole key, sealed, as the backup, until you press "Forget the whole key on this phone" (Aaron, 2026-11-17) |
| Create your account (with public Cosign servers) | Create it with your box: scan or paste the code on the box's page |
| Set up with a server's code | The same thing: the box's code |
| Sign in with another device (one scan) | Two scans: the new phone shows its code, a device in the account scans it and gives a fingerprint, then the new phone scans that device's answer. There is no relay between devices any more, so the second scan carries where the box is |
| Several accounts on one phone, Switch, Add another | Unchanged |
| Device list, rename by long press | Unchanged. The list comes from the box. Names you gave under the old keys do not carry, since devices have new keys |
| Add a device, behind the screen lock | Behind a fingerprint that the box checks |
| Report lost, behind the screen lock | Block at once (one press), then cycle the keys with a fingerprint. If the cycle is cancelled, a "Cycle my keys" line stays until it is done |
| Remove this phone from your account | Blocks this phone at the box, then wipes it here |
| "This phone was reported lost" | "This phone is blocked", with Check again |
| Marble's own signing, with a question for anything destructive | Unchanged |
| NIP-44 to self for app data, and the latest version of your lists | Unchanged |
| NIP-55 for other apps: connect, quiet answers, message permission, NIP-04 and NIP-44 | Unchanged |
| Sign in to a site by scanning its code | Unchanged. This phone is now the remote signer itself |
| Sign in by `nostrconnect://` | Unchanged |
| Apps and sites list, by site, with Remove and Remove all | Unchanged. Removing now also revokes at the box, so it holds on every device |
| Approvals: the card, and the notification with Approve and Deny | Unchanged. A site or app waits up to two minutes |
| Take my account out (the nsec) | Form the whole key with a fingerprint, at once. Shown by the signer process with screenshots off, as before |
| The log under Advanced | Unchanged |
| The always-on service and start at boot | Unchanged. It now keeps the sites' relay connections open, which is the background service the Keykin spec listed as missing |
| Keys only in the signer process | Unchanged, and still tested |

### Carried over, but narrower

| Old | New | Why |
|---|---|---|
| Recent changes, with Undo | Only changes signed through this phone | The servers kept every version. The box keeps a list of what happened for the account page, without Undo |
| Deleted, with copies kept, and post again | Only for things this phone signed | Same reason |
| Undo all for a site | Only what the site did through this phone | Same reason |
| Online dots for every device and server | Only whether the box answers | Devices do not talk to each other |

### New

- Unblock a device, and Cycle my keys, each with a fingerprint.
- Get a `bunker://` link to paste into a site.
- A pasted site code is checked by comparing five digits.
- Log in to your box's page by scanning its code.
- Your account page: its address (the login-page pointer) and a username and password.
- The account page can ask this phone to add a device, make a bunker link, take a
  `nostrconnect://` code, or remove blocked devices (that one asks Yes or No here).
- What is signed in is reported to the box, so the account page can list and revoke it.

### Not carried over. Each needs Aaron's say

| Old | Why it cannot carry over as it is | Nearest equivalent |
|---|---|---|
| **Accounts already in Marble on Cosign servers** | Weighted pieces cannot become Keykin pieces. A phone updated to this build opens on the welcome screen | Take the nsec out of the old Marble first (Advanced, Take my account out), then bring it in here. Do not install this build over a phone that still holds a Cosign account |
| **Paper backup:** make it, the nudge, restore from it, the "someone is restoring" card | One paper piece does nothing alone under "any two sign". Open in `PLAN.md` | The nudge now says "Add a second device". Forming the whole key and keeping the nsec safe is the other way today |
| **Add a server, choose a server** | There is one box. A second box, or changing boxes, is the transfer that is not built | None yet |
| **Three-day waits and "Stop it" notices** for taking the account out | The fingerprint replaces the wait. Forming the key is immediate | The box's list of what happened shows it on the account page |
| **A few seconds to undo a deletion, and one approval for a batch** | The servers held deletions back. The phone signs when asked | Five or more deletions at once still need your Yes, and copies are kept |
| **The list of events dated in the future** | It was the servers' list | Such events are still refused or held for your Yes |
| **A key moving in from a web page** | Web pages no longer hold a key | None needed |
| **ntfy wake-ups** | Nothing sends one. Approvals now start on this phone | The always-on service |

## Things to decide or know

1. **Marble connects over `https` only.** The box's public address, `https://account.syb.lol`,
   is live and is what its codes name, so this is no longer in the way. Debug builds may also
   reach the emulator's host over plain `http`.
2. **Asking a box for an account** (Keykin spec 11.4, `ask`) is in the core but has no screen
   yet. The box now returns its key with the answer, so the screen can be built.
3. **A link opened on the same phone, and the web pane, still skip the five digits,** as they
   did before. Only "Paste a code" compares. The Keykin spec says a handed-over code should
   compare.
4. **Sites and apps never get a fingerprint prompt,** and neither does everyday signing.

## Testing it again

- Java 17 for Gradle: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home`.
- Emulator: `/opt/homebrew/share/android-commandlinetools/emulator/emulator -avd keykintest -port 5582 -no-window`.
- A debug build takes codes and actions with no tap:
  `adb shell am start -n cc.aaro.cosign/.MainActivity --es pair '<code>'`, `--es paste '<code>'`,
  `--es act accept` (see `MainActivity.kt` for the list), and `--es act state` prints the
  account's state to logcat under `MarbleState`.
- A relay on this Mac: `adb reverse tcp:7447 tcp:7447`, since QRST takes a `ws://` relay only on
  loopback.

## Notes from the account page: how fast

The foreground service (`CosignService`, started at boot) runs `Signer.refresh()` every 30 seconds
for each account. That call reports grants and takes notes (`invite`, `bunker`, `nostrconnect`,
and `cycle`, which asks Yes or No), so a note is answered within about 30 seconds whether or not
Marble is open. The box has no push, so the delay can only get shorter by polling more often. Not
checked: deep Doze on a real phone with the screen off for a long time, where Android may hold
the timer back.
