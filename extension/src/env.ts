// Which browser and device this is, and the extension API under whichever name it has.

export const api: typeof chrome = (globalThis as any).browser ?? (globalThis as any).chrome;

const ua = () => (globalThis.navigator?.userAgent ?? '');

export function isAndroid() { return /Android/i.test(ua()); }

/**
 * A phone-sized screen: the card is a sheet along the bottom and the popup fills the
 * view. An iPad (which Safari reports as a Mac) isn't: it gets the corner card and a
 * popover of fixed width. Whether to offer Open Cosign is `isAndroid`, the only place
 * Cosign runs.
 */
export function isNarrow() {
  const s = globalThis.screen;
  return !!s && Math.min(s.width, s.height) < 600;
}

/** "Firefox on Mac", "Safari on iPhone": what your phone calls this browser. */
export function deviceName() {
  const u = ua();
  const browser =
    /Edg\//.test(u) ? 'Edge' :
    /Firefox\/|FxiOS/.test(u) ? 'Firefox' :
    /OPR\//.test(u) ? 'Opera' :
    /Chrome\/|CriOS/.test(u) ? ((globalThis.navigator as any)?.brave ? 'Brave' : 'Chrome') :
    /Safari\//.test(u) ? 'Safari' : 'Browser';
  const device =
    /iPad/.test(u) || (/Macintosh/.test(u) && (globalThis.navigator?.maxTouchPoints ?? 0) > 1) ? 'iPad' :
    /iPhone/.test(u) ? 'iPhone' :
    /Android/.test(u) ? 'Android' :
    /Mac OS X|Macintosh/.test(u) ? 'Mac' :
    /Windows/.test(u) ? 'Windows' :
    /CrOS/.test(u) ? 'Chromebook' :
    /Linux/.test(u) ? 'Linux' : 'computer';
  return `${browser} on ${device}`;
}
