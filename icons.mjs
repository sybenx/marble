// Draws the icon: two overlapping rings (two pieces signing together) on violet.
import { deflateSync } from 'node:zlib';
import { writeFileSync } from 'node:fs';

function png(size) {
  const px = Buffer.alloc(size * (size * 4 + 1));
  const S = 4; // supersampling
  for (let y = 0; y < size; y++) {
    px[y * (size * 4 + 1)] = 0;
    for (let x = 0; x < size; x++) {
      let a = 0, w = 0;
      for (let sy = 0; sy < S; sy++) for (let sx = 0; sx < S; sx++) {
        const u = (x + (sx + .5) / S) / size, v = (y + (sy + .5) / S) / size;
        // rounded square
        const r = .22, dx = Math.max(Math.abs(u - .5) - (.5 - r), 0), dy = Math.max(Math.abs(v - .5) - (.5 - r), 0);
        if (Math.hypot(dx, dy) > r) continue;
        a++;
        const ring = (cx) => Math.abs(Math.hypot(u - cx, v - .5) - .19) < .055;
        if (ring(.38) || ring(.62)) w++;
      }
      const o = y * (size * 4 + 1) + 1 + x * 4, n = S * S;
      const t = w / Math.max(a, 1);
      px[o] = Math.round(0x6b + (255 - 0x6b) * t); px[o + 1] = Math.round(0x4e + (255 - 0x4e) * t); px[o + 2] = 255; px[o + 3] = Math.round(255 * a / n);
    }
  }
  const crc = (b) => { let c, t = []; for (let n = 0; n < 256; n++) { c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; } let r = 0xffffffff; for (const x of b) r = t[(r ^ x) & 255] ^ (r >>> 8); return (r ^ 0xffffffff) >>> 0; };
  const chunk = (type, data) => { const l = Buffer.alloc(4); l.writeUInt32BE(data.length); const td = Buffer.concat([Buffer.from(type), data]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([l, td, c]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(size, 0); ihdr.writeUInt32BE(size, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(px)), chunk('IEND', Buffer.alloc(0))]);
}
for (const s of [16, 32, 48, 96, 128, 256, 512]) writeFileSync(`static/icon-${s}.png`, png(s));
console.log('icons written');
