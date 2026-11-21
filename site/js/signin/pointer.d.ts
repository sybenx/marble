// Types for pointer.js, for a site written in TypeScript (a server reading the pointer).
export { originOnly } from "./pure.js";
export declare const POINTER: 10046;
export declare const LOGIN_D: "keykin/login";
export type SignedEvent = { id: string; pubkey: string; created_at: number; kind: number; tags: string[][]; content: string; sig: string };
/** What the pointers say: the page (url is its origin, shown before going there) or none (removed: taken away). */
export type Pointer =
  | { url: string; template: string; event: SignedEvent; legacy?: true }
  | { none: true; removed?: true };
/** The origin an old pointer (kind 30078) names, if it is that account's own, signed, and a plain origin. Or null. */
export declare function pageFrom(event: unknown, pubkey: string, local?: boolean): string | null;
/** The decision over the events found: every event checked in full (id and signature), the newest wins. */
export declare function pointerOf(found: readonly unknown[], pubkey: string, local?: boolean): Pointer;
/** Looks the pointer up on relays. Needs a browser (location, WebSocket). */
export declare function loginPage(pubkey: string, options?: { timeout?: number }): Promise<Pointer | { unreachable: true }>;
/** Whose account was typed, looked up with the resolveNip05 hook when it is a name. */
export declare function whose(typed: unknown): Promise<string | null>;
