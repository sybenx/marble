// Makes a notes vault on local test servers, as Android does, for trying Marble Web in a browser.
//   node tools/vault-seed.mjs [relay ws url] [blossom url…]   prints the test account's nsec hex and npub hex
// Test keys only: never a real account.
import * as C from "../site/js/vault/crypto.js";
import * as N from "../site/js/vault/notes.js";
import { makeVault } from "./vault-fixtures.mjs";
import { realNet } from "./vault-net.mjs";

const relay = process.argv[2] || "ws://127.0.0.1:7447";
const servers = process.argv.length > 3 ? process.argv.slice(3) : ["http://127.0.0.1:7447", "http://127.0.0.1:7448"];
const sec = C.randomKey();
const t = Math.floor(Date.now() / 1000);
const puts = [
  N.notePut({ id: "0000000000000001", text: "Buy oat milk", created: t - 86400, updated: t - 86400 }, []),
  N.notePut({ id: "0000000000000002", text: "Call the dentist about Thursday", created: t - 3600, updated: t - 3600, done: t - 60 }, []),
  N.notePut({ id: "0000000000000003", text: "Nostr vaults explained", created: t - 7200, updated: t - 7200, url: "https://example.com/vaults" }, []),
];
const v = await makeVault(realNet, sec, { relays: [relay], servers, puts });
console.log(JSON.stringify({ nsec_hex: C.hex(sec), npub_hex: C.pubHex(sec), genesis: v.genesis, phone: { root_hex: C.hex(v.device.rootSec), dvk: v.device.dvk } }));
