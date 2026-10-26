# The feed log

What you do with Discover's posts and today's stories, written down so what Discover should learn can be worked out from evidence. Code: `app/src/main/java/cc/aaro/cosign/FeedLog.kt`.

## Turning it on
- **Off by default.** Nothing is written until it's switched on.
- **The switch:** Account › Advanced. Tap the version line at the bottom 7 times, and **Record how I use Discover** appears.
- **Where it's kept:** on this phone only, in the app's private files (`files/feedlog/`).
  - It's never synced and never sent. Backups are off for the app.
  - One file per day, `yyyy-mm-dd.jsonl`, by the phone's date.
  - Days older than 60 are dropped, and older days go once the total passes 30 MB.
- **Export:** writes `Downloads/Marble/marble-feedlog-yyyymmdd-hhmm.zip` and opens the share sheet. From a Mac:
  `adb shell ls /sdcard/Download/Marble/`, then `adb pull /sdcard/Download/Marble/<file>.zip`.
- **Delete what's recorded** removes the files kept in the app's private storage. Exports already in Downloads stay.

## A line
Each line is one JSON object, with no nulls:

```json
{"t": 1792999999123, "s": "t3k2ab-417", "ev": "imp", "id": "…", "screen": "discover", "rank": 4, "ms": 2350, "most": 1.0}
```

- **`t`:** Unix milliseconds.
- **`s`:** the session.
  - A new session starts when Marble launches, when the log is switched on, and when you come back after 5 minutes or more away.
  - Shorter hops (the browser, a text) stay in the same session.
- **`ev`:** the event; its fields are below.

**Ids:**
- a post: its 64-hex event id;
- a story: `t:` and its topic-feeds id.

These are the same keys the Discover lists use.

## Events
| `ev` | When | Fields |
|---|---|---|
| `log` | switched on or off | `on` |
| `session` | a new session | `why` (launch / back / on), `hour`, `dow` (1 = Monday), `tz` (minutes from UTC), `app` (version), `rv` |
| `away` | Marble left the screen | (none) |
| `back` | back within 5 minutes | `gap` (seconds) |
| `view` | Discover or Today's stories shown or left | `screen` (discover / stories), `on`, and on leaving: `ms`, `deep` (the furthest rank reached) |
| `feed` | a list put in order | `screen`, `why` (discover: load / pull / back / cache), `rv`, `prof`, `list` |
| `item` | the first time an item appears in a list each day | see below |
| `imp` | an item stopped being on screen | `id`, `screen`, `rank`, `ms` (how long at least half of it was on screen), `most` (the largest share of it on screen, 0.5–1) |
| `scroll` | once a second while scrolling, and where it stopped | `screen`, `top` (the rank at the top), `deep`, `speed` (items a second; negative is up), `stop` |
| `pull` | pull to refresh, or a tap on "Discover" | `screen` |
| `open` | an item opened | `id`, `what`, `again` (seconds since it was last opened, when it was), `site` (for comments) |
| `tab` | a page opened from Discover, in the browser's Custom Tab | `id`, `e`: see below |
| `act` | something you did to an item | `id`, `a`, and per action: `topic`, `emoji`, `first`, `why`, `author`, `src`, `root` |

**What `open` covers (`what`):**
- `story`: a headline;
- `comments`: its discussion, with `site`;
- `link`: a post's link card;
- `thread`: a post tapped;
- `replyBox`;
- `image`;
- `profile`: carries `author` and no `id`.

**What `tab` reports (`e`):**
- `shown`;
- `hidden`, with `ms`, `scrolls` (scroll gestures) and `pages` (pages loaded, the first one included);
- `read`, with `pct` (the furthest scrolled, in steps of 10; Chrome only);
- `end`, with `touched` (whether you interacted) and `ms`.

**The actions in `act` (`a`):**
- like; react (`emoji`), with `first` (true the first time);
- reply; post (a story's link) and quote (a post's);
- save and unsave (bookmarks); copyLink;
- more and less (`topic`); notint (Not interested, with `author`); report (`why`);
- hideAuthor (`author`; `id` is the post it was hidden from); hideSource (`src`); mute (`author`, no `id`);
- followTopic and unfollowTopic (`topic`, no `id`).

## `feed`
- **`rv`:** the ranking that put the list in order (`FeedLog.RANKER`). Change it whenever Calm's ranking changes.
- **`prof`:** what the ranking knew of you at that moment.
  - `i`: topic → interest, from 0 to 1 (`Interests.of`);
  - `fol`: the topics you follow.
- **`list`:** the items in the order shown, top first.
  - **Discover:** `{id, lift, sp}`.
    - `lift`: how many seconds earlier your interests moved the post;
    - `sp`: true for a Special Follow post.
  - **Stories:** `{id, sc, shown}`.
    - `sc`: `Calm.storyScore`;
    - `shown`: how many earlier fresh opens showed it.

## `item`
**A post:**
- `kind: "post"`, `ek` (the event kind), `src` (the author's pubkey; for a repost, the reposted author), `at` (when it was posted);
- `from`: pyramid or special; `why`: the pick's own reason, if any;
- `topics` (topic → strength, the top 3), `words` (up to 8 keywords), `len` (characters of text);
- `link`, `pics`, `vids`, `digest` (a newsletter), `reply`, `repost`;
- `follow`: you follow the author; `likes`: when known.

**A story:**
- `kind: "story"`, `src` (the site's id), `srcName`, `topic`, `at`;
- `words` (from the headline), `img`;
- `disc`: `[{site, n}]`, where it's being discussed and how many comments.

## Not logged
- Post text and search text.
- Inline video plays.
- The four-story box: it's no longer on screen in this version.
- Anything outside Discover and Today's stories: a post opened from the bell, say, logs its `open` but has no `item`.
