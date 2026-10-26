#!/usr/bin/env python3
"""
Marble of the day: cut the collection into the app.

    python3 android/tools/marbles.py ~/igloo/marble-hero/daily

Takes every pair <name>-light.jpg and <name>-dark.jpg in that folder (renders like the swirl's: the marble
centred on a plain backdrop). Finds each marble's circle by itself (a manifest can override it: a file
marbles.json there, {"<name>": {"light": [cx, cy, r], "dark": [cx, cy, r]}}), cuts it as a sharp circle
just inside the glass with a small soft shadow, and writes 1024 px WebP pairs to res/drawable(-night)/
marble_<name>.webp and the list in ui/Marbles.kt. Needs Pillow (pip install pillow).
To add a marble: put its two renders in the folder, run this, build. The day's marble is picked by date.
"""
import json, os, re, sys
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, '../app/src/main/res')
KT = os.path.join(HERE, '../app/src/main/java/cc/aaro/cosign/ui/Marbles.kt')
N, SS, FRAC = 1024, 3, 0.278          # output size (big enough for a pulled-open header), supersampling, the marble's radius in it

def find(img):
    """
    The marble's centre and radius. The marble is full of detail and the backdrop (and the soft shadow under
    it) is smooth, so: where the picture has edges, filled in, and the widest row of that.
    """
    k = 4
    small = img.convert('L').resize((img.width // k, img.height // k)).filter(ImageFilter.GaussianBlur(1))
    edges = small.filter(ImageFilter.FIND_EDGES).filter(ImageFilter.BoxBlur(6))
    w, h = edges.size; px = edges.load()
    m = 8                                                    # the filter's own edge at the picture's border: not the marble
    level = sorted(px[x, y] for x in range(m, w - m, 3) for y in range(m, h - m, 3))
    cut_at = max(6, level[int(len(level) * 0.6)])           # textured: well above the backdrop's own grain
    # The largest connected textured patch is the marble: its width is the diameter, its top the circle's top.
    on = [[px[x, y] > cut_at for x in range(w)] for y in range(h)]
    seen = [[False] * w for _ in range(h)]
    best = None
    for y0 in range(m, h - m):
        for x0 in range(m, w - m):
            if not on[y0][x0] or seen[y0][x0]: continue
            stack = [(x0, y0)]; seen[y0][x0] = True; n = 0; x1 = x2 = x0; top = y0
            while stack:
                x, y = stack.pop(); n += 1
                x1 = min(x1, x); x2 = max(x2, x); top = min(top, y)
                for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                    if m <= nx < w - m and m <= ny < h - m and on[ny][nx] and not seen[ny][nx]:
                        seen[ny][nx] = True; stack.append((nx, ny))
            if best is None or n > best[0]: best = (n, x1, x2, top)
    _, x1, x2, top = best
    grow = 3                                                 # what the fill blur adds round the edge
    r = (x2 - x1) / 2 - grow
    return (x1 + x2) / 2 * k, (top + grow + r) * k, r * k

def cut(img, cx, cy, r, out):
    r *= 0.97                         # just inside the glass: no backdrop at the edge
    R = FRAC * N
    marble = img.crop((round(cx - r), round(cy - r), round(cx + r), round(cy + r))).resize((int(2 * R * SS),) * 2, Image.LANCZOS)
    mask = Image.new('L', marble.size, 0); ImageDraw.Draw(mask).ellipse((0, 0, marble.size[0] - 1, marble.size[1] - 1), fill=255)
    big = Image.new('RGBA', (N * SS, N * SS), (0, 0, 0, 0))
    sh = Image.new('L', big.size, 0); y = N / 2 + R * 1.07
    ImageDraw.Draw(sh).ellipse(((N / 2 - 0.73 * R) * SS, (y - 0.12 * R) * SS, (N / 2 + 0.73 * R) * SS, (y + 0.12 * R) * SS), fill=int(255 * 0.40))
    big.paste(Image.new('RGBA', big.size, (0, 0, 0, 255)), (0, 0), sh.filter(ImageFilter.GaussianBlur(R * 0.06 * SS)))
    big.paste(marble.convert('RGBA'), (int((N / 2 - R) * SS),) * 2, mask)
    big.resize((N, N), Image.LANCZOS).save(out, 'WEBP', quality=86, method=6)

def main(folder):
    folder = os.path.expanduser(folder)
    manifest = json.load(open(os.path.join(folder, 'marbles.json'))) if os.path.exists(os.path.join(folder, 'marbles.json')) else {}
    names = sorted({m.group(1) for f in os.listdir(folder) if (m := re.match(r'(.+)-light\.jpg$', f)) and os.path.exists(os.path.join(folder, m.group(1) + '-dark.jpg'))})
    entries = []
    for name in names:
        key = re.sub(r'[^a-z0-9]+', '_', name.lower()).strip('_')
        detail = 0
        for mode, sub in (('light', 'drawable'), ('dark', 'drawable-night')):
            img = Image.open(os.path.join(folder, f'{name}-{mode}.jpg')).convert('RGB')
            cx, cy, r = manifest.get(name, {}).get(mode) or find(img)
            os.makedirs(os.path.join(RES, sub), exist_ok=True)
            cut(img, cx, cy, r, os.path.join(RES, sub, f'marble_{key}.webp'))
            detail = max(detail, int(r))
            print(f'{name} {mode}: centre ({cx:.0f}, {cy:.0f}), radius {r:.0f}')
        entries.append((key, name.replace('-', ' ').title(), detail))
    lines = ',\n'.join(f'    Marble("{t}", R.drawable.marble_{k}, {d})' for k, t, d in entries)
    open(KT, 'w').write(f'''package cc.aaro.cosign.ui

import cc.aaro.cosign.R

// Written by android/tools/marbles.py from the collection; run it again to add a marble.

/**
 * One of the daily marbles: its name, its picture (light and dark, shadow included), and its radius in the render,
 * in pixels (how big it can be shown). [file] (on the phone) or [url]: the night's own marble instead (a transparent,
 * centred PNG; no shadow).
 */
data class Marble(val name: String, val res: Int, val detail: Int, val file: java.io.File? = null, val url: String? = null)

/** The collection; the day's is picked by date ([Marbles.today]). */
val MARBLES = listOf(
{lines},
)
''')
    print(f'{len(entries)} marbles → {os.path.relpath(KT)}')

if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else '~/igloo/marble-hero/daily')
