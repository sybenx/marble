// Types for pure.js, for a site written in TypeScript (a server reading the pointer, or a page's checks).
export declare const PLACEHOLDER: "<nostrconnect>";
/** An https origin and nothing else, or null. local: whether http://localhost or 127.0.0.1 counts (default: this page runs there). */
export declare function originOnly(url: unknown, local?: boolean): string | null;
/** An account's nostrconnect_url template, checked, with the origin it goes to; or null. local: as originOnly. */
export declare function loginTemplate(template: unknown, local?: boolean): { template: string; origin: string } | null;
/** The old kind's origin as a template: <origin>/#connect=<nostrconnect>. */
export declare function legacyTemplate(origin: string): string;
/** The template with its placeholder replaced by the code, encoded once. */
export declare function loginLink(template: string, code: string): string;
/** The key in an npub or nprofile (all lower or all upper case), as lower case hex, or null. */
export declare function bech32Key(text: unknown): string | null;
export type Typed = { pubkey: string } | { name: string; domain: string };
/** What was typed: a key, a NIP-05 name to look up (a bare domain is _), or null. */
export declare function typedName(typed: unknown): Typed | null;
/** The key a NIP-05 answer body gives for name, lower case hex, or null. */
export declare function nip05Key(body: unknown, name: string): string | null;
/** The page a signer app asks you to approve at (NIP-46 auth_url): http or https with a host, else null. */
export declare function approvalPage(url: unknown): { href: string; host: string } | null;
