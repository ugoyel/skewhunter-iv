"""Generate the Skew Hunter PWA icons.

Pure standard library (zlib only) so it runs anywhere the collector runs.
The glyph is the app's own motif: diverging skew columns above and below a
centre rule -- blue = call-side skew, orange = put-side skew, matching the
series colours used in app.html.

Run:  python tools/make_icons.py
"""
import os
import struct
import zlib

OUT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "icons")

SUPERSAMPLE = 4                   # render at size*SUPERSAMPLE, box-downsample for anti-aliasing
BG     = (0x0D, 0x0D, 0x0D, 255)  # page plane, dark
RULE   = (0x52, 0x51, 0x4E, 255)  # secondary ink
UP     = (0x39, 0x87, 0xE5, 255)  # series 1, blue  -- CE / call-side skew
DOWN   = (0xD9, 0x59, 0x26, 255)  # series 2, orange -- PE / put-side skew

# Columns as (centre x, signed height), unit coords relative to the glyph box.
COLUMNS = [(0.14, 0.60), (0.38, -0.45), (0.62, 1.00), (0.86, -0.70)]
COL_W   = 0.145
HALF_H  = 0.42   # height of a full-scale (|h| == 1) column


class Canvas:
    def __init__(self, size, bg):
        self.size = size
        self.rows = [bytearray(bg * size) for _ in range(size)]

    def fill_rounded_rect(self, x0, y0, x1, y1, r, color):
        """Hard-edged fill; anti-aliasing comes from the downsample."""
        x0, y0, x1, y1 = int(round(x0)), int(round(y0)), int(round(x1)), int(round(y1))
        r = max(0.0, min(float(r), (x1 - x0) / 2.0, (y1 - y0) / 2.0))
        px = bytes(color)
        for y in range(max(0, y0), min(self.size, y1)):
            cy = y + 0.5
            if cy < y0 + r:
                dy = (y0 + r) - cy
            elif cy > y1 - r:
                dy = cy - (y1 - r)
            else:
                dy = 0.0
            inset = r - (r * r - dy * dy) ** 0.5 if dy else 0.0
            xa = max(0, int(round(x0 + inset)))
            xb = min(self.size, int(round(x1 - inset)))
            if xb > xa:
                self.rows[y][xa * 4:xb * 4] = px * (xb - xa)

    def fill_column(self, x0, x1, base, tip, r, color):
        """A column with a rounded data-end and a square baseline end -- it has
        to look like it grows out of the centre rule, not float beside it."""
        top, bot = (tip, base) if tip < base else (base, tip)
        self.fill_rounded_rect(x0, top, x1, bot, r, color)
        if tip < base:                       # rounded at the top, square at the base
            self.fill_rounded_rect(x0, bot - r, x1, bot, 0, color)
        else:                                # square at the base, rounded at the bottom
            self.fill_rounded_rect(x0, top, x1, top + r, 0, color)

    def downsample(self, target):
        """Box filter the supersampled canvas down to target."""
        f = self.size // target
        assert f * target == self.size, "canvas must be an exact multiple of target"
        area = f * f
        out = bytearray(target * target * 4)
        for oy in range(target):
            src = self.rows[oy * f:(oy + 1) * f]
            base = oy * target * 4
            for ox in range(target):
                r = g = b = a = 0
                lo, hi = ox * f * 4, (ox * f + f) * 4
                for row in src:
                    chunk = row[lo:hi]
                    for i in range(0, len(chunk), 4):
                        r += chunk[i]
                        g += chunk[i + 1]
                        b += chunk[i + 2]
                        a += chunk[i + 3]
                o = base + ox * 4
                out[o]     = r // area
                out[o + 1] = g // area
                out[o + 2] = b // area
                out[o + 3] = a // area
        return out


def draw(canvas, corner_radius, glyph_scale):
    s = canvas.size
    canvas.fill_rounded_rect(0, 0, s, s, corner_radius * s, BG)

    # Glyph box: centred, sized by glyph_scale (the maskable icon shrinks it
    # into the safe zone so Android's mask can never crop the columns).
    g = glyph_scale * s
    gx = (s - g) / 2.0
    half = HALF_H * g
    col_w = COL_W * g
    radius = col_w * 0.28                # the 4px rounded data-end, to scale

    # Place the baseline so the drawn columns -- not the notional glyph box --
    # end up centred: the up and down extents are deliberately unequal.
    up = max(h for _, h in COLUMNS if h > 0)
    down = max(-h for _, h in COLUMNS if h < 0)
    base = s / 2.0 - half * (down - up) / 2.0

    rule = 0.012 * g
    canvas.fill_rounded_rect(gx, base - rule, gx + g, base + rule, rule, RULE)

    for cx, h in COLUMNS:
        x = gx + cx * g
        canvas.fill_column(x - col_w / 2, x + col_w / 2, base, base - half * h,
                           radius, UP if h > 0 else DOWN)


def render(size, corner_radius, glyph_scale):
    """Draw at size*SUPERSAMPLE (an exact multiple, so nothing is cropped or
    shifted by the box filter) and reduce to the requested size."""
    canvas = Canvas(size * SUPERSAMPLE, BG)
    draw(canvas, corner_radius, glyph_scale)
    return canvas.downsample(size)


def write_png(path, size, rgba):
    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    raw = bytearray()
    for y in range(size):
        raw.append(0)                                     # filter type 0 (None)
        raw += rgba[y * size * 4:(y + 1) * size * 4]
    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
           + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)
    print(f"  {os.path.relpath(path, os.getcwd())}  ({size}x{size}, {len(png):,} bytes)")


def main():
    os.makedirs(OUT_DIR, exist_ok=True)

    # (filename, size, corner radius, glyph scale)
    targets = [
        ("icon-512.png",         512, 0.22, 0.68),
        ("icon-192.png",         192, 0.22, 0.68),
        # Maskable: full bleed, glyph shrunk into the 80% safe zone so Android's
        # mask (circle, squircle, teardrop...) can never crop the columns.
        ("maskable-512.png",     512, 0.00, 0.52),
        ("maskable-192.png",     192, 0.00, 0.52),
        # iOS applies its own corner mask, so ship it square.
        ("apple-touch-icon.png", 180, 0.00, 0.68),
    ]
    for name, size, radius, glyph in targets:
        write_png(os.path.join(OUT_DIR, name), size, render(size, radius, glyph))


if __name__ == "__main__":
    main()
