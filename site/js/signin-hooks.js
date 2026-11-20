// The sign-in (signin/, Keykin's shared copy: never edit it here) reads profiles, names and pictures through hooks. Marble
// passes its own, so the sign-in shares data.js's caches and draws people as render.js does. Imported first by me.js and
// act.js, so this runs before the dialog loads or anything draws.
import { configure } from "./signin/hooks.js";
import { outbox, resolveNip05, profile, profiles, onProfile } from "./data.js";
import { esc, name, avatar } from "./render.js";

configure({ outbox, resolveNip05, profile, profileOf: (pk) => profiles.get(pk), onProfile, esc, name, avatar });
