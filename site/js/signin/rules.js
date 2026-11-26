// Layer 1, the rules, no screen: who a typed name is, the login-page pointer, its template and link, the sign-in code,
// bunker links and the NIP-46 client. Imports in Node: nothing here touches document, window or storage when loaded.
// (Calling the parts that talk to relays still needs a browser's location and WebSocket: see README.md.)
export {
  connectRelays, encrypt, decrypt, conversationKey, Remote, siteCode, deviceLabel, shortKey,
  loginTemplate, legacyTemplate, loginLink, phoneSignIn, parseBunker, bunkerSignIn, codeSvg,
} from "./connect.js";
export { POINTER, LOGIN_D, originOnly, pageFrom, pointerOf, loginPage, whose } from "./pointer.js";
export { PLACEHOLDER, typedName, bech32Key, nip05Key, approvalPage } from "./pure.js";
export { configure, hooks, defaults } from "./hooks.js";
