# signin: name-first sign-in for web sites

The one shared copy of the sign-in that Marble Web, Discover and Tube use: you type your name, the site finds the
login page your account names (Keykin SPEC 11.1, the login-page pointer) and sends you there with a `nostrconnect://`
code, or you sign in with your phone or another signer app. It was Marble Web's code (`site/js/` at Marble main
d639beb). It was moved here as it is: the same markup, class names, words, storage keys, code contents, perms and
relays. Fixed text still names Marble.

Plain browser ES modules with relative imports. No build step, no bundler, no npm packages at run time. A site serves
the files as they are.

This is not `site/signin.ts` in this repo. That one is the account page's scan-code widget (QRST signer-ticket), built
with esbuild. It is a different thing and is left alone.

## Three layers

| Layer | Entry | Files | Needs |
|---|---|---|---|
| 1. Rules, no screen | `rules.js` | `connect.js`, `pointer.js`, `hooks.js`, `pool.js`, `ids.js`, `vendor/` | nothing at import; imports in Node |
| 2. Who is signed in | `me.js` | layer 1 | a browser (`localStorage`, `location`) |
| 3. The dialog | `dialog.js` | `toast.js`, `account-page.js`, layers 1 and 2 | a page (`document`), `signin.css` |

Layer 1 loads none of layer 2 or 3, and layer 2 loads nothing of layer 3 (`test/signin-layers.test.ts` checks this).

**Layer 1 in Node.** Everything imports cleanly. The pure rules work: `loginTemplate`, `legacyTemplate`, `loginLink`,
`originOnly`, `pageFrom`, `pointerOf`, `siteCode`, `deviceLabel`, `shortKey`, `parseBunker`, `encrypt`, `decrypt`,
`conversationKey`, and `whose` given a `resolveNip05` hook (or Node's own `fetch`). The parts that talk to relays
(`loginPage`, `connectRelays`, `phoneSignIn`, `bunkerSignIn`, `Remote`, and the default `outbox` and `profile` hooks)
read the page's `location` (pool.js `testRelay`) and use `WebSocket` and `document`, so in Node they throw
`ReferenceError: location is not defined` today. That is how Marble's code was; it was not changed.

## Files and exports

- `connect.js` (from Marble's `connect.js`, unchanged): the NIP-46 client and the sign-in code.
  `connectRelays`, `encrypt`, `decrypt`, `conversationKey`, `Remote`, `siteCode`, `deviceLabel`, `shortKey`,
  `loginTemplate`, `legacyTemplate`, `loginLink`, `phoneSignIn`, `parseBunker`, `bunkerSignIn`, `codeSvg`.
- `pointer.js` (from Marble's `account-page.js`, its rules): `POINTER` (10046), `LOGIN_D` ("keykin/login"),
  `originOnly`, `pageFrom`, `pointerOf`, `loginPage`, `whose`. Its calls to Marble's `data.js` go through the hooks.
- `hooks.js` (new): `configure`, `hooks`, `defaults`, `watchProfiles`. See Hooks below.
- `rules.js` (new): layer 1 in one import. Re-exports all of `connect.js` and `pointer.js`, and `configure`,
  `hooks`, `defaults`.
- `pool.js`, `ids.js`, `vendor/noble.js`, `vendor/sign.js`: Marble's, unchanged. `pool.js`: `FAST`, `INDEXERS`,
  `MORE`, `verify`, `testRelay`, `normalize`, `relays`, `warm`, `req`, `count`, `subscribe`, `publish`, `foundOn`,
  `connectTimes`. `ids.js`: `toHex`, `fromHex`, `isHex64`, `decode`, `npub`, `note`, `nevent`, `nprofile`, `naddr`,
  `parseInput`, `pathFor`.
- `me.js` (from Marble's `me.js`, without its Marble-only parts): `extensionSignedOut`, `onMe`, `onAsk`, `current`,
  `method`, `phoneSecret`, `onSignedOut`, `signedInWithPhone`, `choose`, `neither`, `signOut`, `sign`,
  `nip44DecryptFrom`, `myWriteRelays`.
- `toast.js` (from Marble's `act.js`): `toast`, `approving` (was not exported in Marble).
- `dialog.js` (from Marble's `act.js`, lines 36 to 370): `openSignIn`, `loginLink` (re-export, as act.js had),
  `lookAgain`, and for a site's own flows in the same dialog: `dialog`, `view`, `pageOf`, `rememberedPage`,
  `forgetPage`, `EMAIL`, `SPINNER`, `nextAttempt`, `attemptNow`, `lastTypedName`, `setLastTyped`.
- `account-page.js` (from Marble's `account-page.js`, its dialog): `openAccountPage`.
- `signin.css`: the dialog's styles, copied from Marble Web's `index.html`.
- `demo/index.html`: a bare page with the default hooks and a Sign in button. Serve `signin/` and open `/demo/`.
- `vectors/pointer.json`: shared cases (see below).

### Left in Marble Web

From `me.js`, Marble-only: `seenContacts`, `contactsSeen`, `followsNow`, `myFollows` (and the `follows` cache that
`set()` cleared on every change of account), `canVault`, `nip44Self`. Marble keeps them in a file of its own. The
follows cache must be cleared on each change of account: `onMe(() => { follows = null; })`, registered before any
other `onMe` listener that reads follows, since `set()` used to clear it before telling anyone.

From `act.js`: the imports and `ALSO` (lines 1 to 15), "Adding Marble on a Mac" (lines 372 to 477), `canAct`, and
the reactions and replies.

### What "Adding Marble on a Mac" calls back into

That flow draws in the same dialog. It needs, from `dialog.js`: `dialog`, `view`, `SPINNER` (was `spinner`), `EMAIL`,
`pageOf`, `rememberedPage`, `forgetPage`, `nextAttempt()` (was `++attempt` and `attempt++`), `attemptNow()` (was
reading `attempt`), `lastTypedName()` and `setLastTyped(t)` (were reading and setting `lastTyped`). From
`pointer.js`: `whose`, `loginPage`. From `connect.js`: `codeSvg`. From `me.js`: `current`. A module cannot assign
another module's `let`, so those four functions stand in for the shared variables. The dialog's own click handler
still answers its `data-si="copy"` and `data-si="close"` buttons, as before. Load the flow after `dialog.js`, so the
dialog's handlers and its "pick up a pending sign-in" timer come first, as they did in one file.

## Hooks

The sign-in reads a few things from the site around it. `configure({...})` passes the site's own, so its caches stay
shared. Each is read at the moment it is called, so `configure` may come after the imports. An unknown name throws.

| Hook | Called as | Default |
|---|---|---|
| `outbox` | `outbox(pk)` → `Promise<{write, read}>` | kind 10002 from the indexers and fast relays, as Marble's `data.js` |
| `resolveNip05` | `resolveNip05("name@domain" or "domain")` → `Promise<{pubkey, relays} \| null>` | `/.well-known/nostr.json`, as Marble's `data.js` |
| `profile` | `profile(pk)` → `Promise<profile \| null>` | kind 0 from the fast relays and indexers, kept in a small store |
| `profileOf` | `profileOf(pk)` → profile or undefined, what is known now | that store |
| `onProfile` | `onProfile(fn)` → a function that stops it; `fn(pk, profile)` | that store |
| `esc` | `esc(text)` → HTML-safe text | Marble's `render.js` |
| `name` | `name(pk)` → text | display name, else name, else a short npub (`npub1abcdefg…`), as Marble's `render.js` |
| `avatar` | `avatar(pk, size)` → HTML | Marble's `render.js` markup (`.av`) |

A profile is an object with `display_name`, `name` and `picture`. Marble Web passes its own:

```js
import { configure } from "./signin/rules.js";
import { outbox, resolveNip05, profile, profiles, onProfile } from "./data.js";
import { esc, name, avatar } from "./render.js";
configure({ outbox, resolveNip05, profile, profileOf: (pk) => profiles.get(pk), onProfile, esc, name, avatar });
```

The dialog listens for profiles from the moment it loads (`watchProfiles`); when a site passes its own `onProfile`,
that listener moves over to it.

## What a site provides

- **Styles.** Link `signin.css` (or keep your own copy of the same rules). It reads the page's colors: `--bg`,
  `--ink`, `--muted`, `--accent`, `--accent-soft`, `--on-accent`, `--card`, `--line`, `--field`, `--field-line`
  (`demo/index.html` has Marble's values). Its last part is page-wide rules from Marble Web the dialog also uses:
  `.visually-hidden`, `.link`, `.av`. Marble Web keeps the rules in its `index.html` until it switches.
- **The dialog element**, optional: `<dialog id="signin" aria-labelledby="si-title"><div class="si"></div></dialog>`.
  With none on the page, `dialog.js` adds exactly that to `<body>` when it loads.
- **The site's name**, in `<meta name="application-name" content="…">`. The code's `name=` is that, else the hostname.
- **A Sign in button** that calls `openSignIn()`.
- **"Your account page"** in the dialog is a `[data-account-visitor]` button. The site answers it, as Marble Web's
  `app.js` does: close `#signin`, then `openAccountPage(null)` from `account-page.js`.
- **Who is signed in** comes from `me.js`: `current()`, `onMe(fn)`, `signOut()`, `sign(template)`.

### Storage and addresses it uses (kept from Marble, so people stay signed in)

- `localStorage` `marble.me`: the sign-in. `marble.loginpage.<pubkey>`: a login page seen after an earlier sign-in.
- `sessionStorage` `marble.signin`: a code waiting on the login page, for this tab. `marble.local`: on localhost only,
  a test relay that everything goes to instead of the public ones.
- `#signin-no-app` in the address: back from an Android intent link no app took.
- `?as=<hex>` on localhost only: act as that account, read only.

## Taking a copy

```bash
node tools/signin-copy.mjs ../my-site/js/signin          # copies signin/ there, and writes SOURCE
node tools/signin-copy.mjs --check ../my-site/js/signin  # exits 1, naming the files, if the copy was edited or differs
```

`SOURCE` holds the Keykin commit and a sha256 per file. Edit here, then copy again; never edit the copy. A folder that
has files and no `SOURCE` is left alone.

## Shared cases

`vectors/pointer.json` holds plain data cases for reading the pointer: templates and the origin shown, the old kind's
`url`, links, pointer events (signed with two fixed test keys) and what they say, typed names, and NIP-05 answers.
They were gathered from the tests of Marble Web, Keykin Login (`login/static/pointer.js`), Tube and Marble Android.
`expect` is the rule. `differs` names an implementation that answers otherwise today, with what it gives.
`test/signin-vectors.test.ts` runs them against this package and against `login/static/pointer.js`.
`node tools/signin-vectors.mjs` writes the file again.

### The rules, as the cases have them

- A template is `https://` (or `http://localhost` or `127.0.0.1` when the reader runs there), at most 500
  characters, with no white space, no user or password, and one `<nostrconnect>` at its very end. The origin shown is
  where the link goes, so the placeholder may not be part of the host.
- The old kind's `url` is an https origin written as itself (no path, query, fragment, user, upper case or `:443`),
  at most 200 characters.
- Only the account's own events, with a good id and signature, count. The newest 10046 decides: a good template,
  or removed. Only with no 10046 at all, the newest 30078 with `d` = `keykin/login`. At the same `created_at`, the
  lower id wins (NIP-01). A 30078's `d` is its first `d` tag.
- A typed name is `name@domain` (NIP-05 names: a-z, 0-9, `.`, `_`, `-`), a bare domain (`_@domain`), an npub or
  nprofile (all lower or all upper case), or 64 hex, each with or without `nostr:`, at most 400 characters.
- A NIP-05 answer's key is 64 hex, either case, given back lower case.

Where this package differs from these today is listed in the cases. It was moved, not fixed.
