#!/usr/bin/env python3
"""Builds Google Play store graphics from the Robolectric renders in docs/play-store/raw.

Outputs (docs/play-store/graphics):
  icon-512.png               hi-res icon (512x512, full-bleed; Play applies the mask)
  feature-graphic.png        1024x500
  phone-1..5.png             1080x1920 captioned phone screenshots
Usage: python3 tools/play-store/make_graphics.py  (from the ClearTether directory)
"""
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
RAW = os.path.join(ROOT, "docs", "play-store", "raw")
OUT = os.path.join(ROOT, "docs", "play-store", "graphics")
FONT_BOLD = "/usr/share/fonts/opentype/inter/Inter-Bold.otf"
FONT_MED = "/usr/share/fonts/opentype/inter/Inter-Medium.otf"
TEAL_BRIGHT, TEAL, INDIGO, INK = (46, 230, 197), (11, 163, 145), (74, 91, 214), (11, 20, 22)


def gradient(w, h, stops):
    """Diagonal linear gradient (top-left -> bottom-right) through colour stops."""
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = (x / max(w - 1, 1) + y / max(h - 1, 1)) / 2
            for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
                if p0 <= t <= p1:
                    f = (t - p0) / (p1 - p0) if p1 > p0 else 0
                    px[x, y] = tuple(int(c0[i] + (c1[i] - c0[i]) * f) for i in range(3))
                    break
    return img


BRAND = [(0.0, TEAL_BRIGHT), (0.45, TEAL), (1.0, INDIGO)]


def glyph(size, color=(255, 255, 255, 255)):
    """The ClearTether mark (same geometry as ic_launcher_foreground.xml, 108-unit viewport)."""
    ss = 4
    s = size * ss / 108.0
    img = Image.new("RGBA", (size * ss, size * ss), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    def arc(r, width):
        # PIL grows the stroke inwards from the box, so pad by half the width to centre it on r.
        o = r + width / 2
        box = [(54 - o) * s, (60 - o) * s, (54 + o) * s, (60 + o) * s]
        d.arc(box, start=225, end=315, fill=color, width=int(width * s))
        for ang in (225, 315):  # round caps
            import math
            cx = 54 + r * math.cos(math.radians(ang))
            cy = 60 + r * math.sin(math.radians(ang))
            rr = width / 2
            d.ellipse([(cx - rr) * s, (cy - rr) * s, (cx + rr) * s, (cy + rr) * s], fill=color)

    arc(16, 5)
    arc(26, 5)
    for x2 in (33, 75):
        d.line([54 * s, 60 * s, x2 * s, 74 * s], fill=color, width=int(3.5 * s))
    for cx, cy, r in ((54, 60, 7.5), (33, 74, 5), (75, 74, 5)):
        d.ellipse([(cx - r) * s, (cy - r) * s, (cx + r) * s, (cy + r) * s], fill=color)
    return img.resize((size, size), Image.LANCZOS)


def icon512():
    bg = gradient(512, 512, BRAND).convert("RGBA")
    bg.alpha_composite(glyph(512))
    bg.convert("RGB").save(os.path.join(OUT, "icon-512.png"))


def feature_graphic():
    img = gradient(1024, 500, BRAND).convert("RGBA")
    mark = glyph(300)
    img.alpha_composite(mark, (36, 90))
    d = ImageDraw.Draw(img)
    d.text((350, 120), "ClearTether", font=ImageFont.truetype(FONT_BOLD, 92), fill="white")
    d.text((354, 240), "Share your phone's internet", font=ImageFont.truetype(FONT_MED, 40), fill=(255, 255, 255, 240))
    d.text((354, 292), "with every device you own", font=ImageFont.truetype(FONT_MED, 40), fill=(255, 255, 255, 240))
    chips = ["Wi-Fi Direct", "QR & NFC", "HTTPS-safe"]
    x = 354
    f = ImageFont.truetype(FONT_MED, 26)
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)
    for c in chips:
        w = d.textlength(c, font=f) + 36
        od.rounded_rectangle([x, 372, x + w, 420], radius=24, fill=(255, 255, 255, 52))
        od.text((x + 18, 396), c, font=f, fill="white", anchor="lm")
        x += w + 14
    img.alpha_composite(overlay)
    img.convert("RGB").save(os.path.join(OUT, "feature-graphic.png"))


SHOTS = [
    ("1-sharing", "Share mobile data", "with laptops, iPhones & tablets", True),
    ("2-idle", "One switch.", "Private Wi-Fi Direct network", False),
    ("3-client", "Zero setup on Android", "Every app just works", True),
    ("4-premium", "Unlimited with Premium", "Monthly or yearly. Cancel anytime", False),
    ("5-guide", "One-tap setup", "for Windows, Mac & iPhone", False),
]


def phone_screenshots():
    for i, (name, title, subtitle, dark) in enumerate(SHOTS, start=1):
        canvas = (gradient(1080, 1920, BRAND) if not dark else gradient(1080, 1920, [(0, (16, 40, 44)), (1, INK)])).convert("RGBA")
        d = ImageDraw.Draw(canvas)
        tf = ImageFont.truetype(FONT_BOLD, 76)
        sf = ImageFont.truetype(FONT_MED, 42)
        d.text((540, 150), title, font=tf, fill="white", anchor="mm")
        d.text((540, 245), subtitle, font=sf, fill=(255, 255, 255, 225), anchor="mm")
        shot = Image.open(os.path.join(RAW, name + ".png")).convert("RGBA")
        w = 860
        h = int(shot.height * w / shot.width)
        shot = shot.resize((w, h), Image.LANCZOS)
        top = 340
        visible = 1920 - top + 60  # let the phone run off the bottom edge
        shot = shot.crop((0, 0, w, min(h, visible)))
        mask = Image.new("L", shot.size, 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, shot.width, shot.height + 80], radius=56, fill=255)
        shadow = Image.new("RGBA", (shot.width + 80, shot.height + 80), (0, 0, 0, 0))
        ImageDraw.Draw(shadow).rounded_rectangle([40, 50, shot.width + 40, shot.height + 120], radius=60, fill=(0, 0, 0, 120))
        shadow = shadow.filter(ImageFilter.GaussianBlur(24))
        x = (1080 - w) // 2
        canvas.alpha_composite(shadow, (x - 40, top - 40))
        frame = Image.new("RGBA", (shot.width + 24, shot.height + 24), (0, 0, 0, 0))
        ImageDraw.Draw(frame).rounded_rectangle([0, 0, frame.width, frame.height + 80], radius=66, fill=(20, 24, 26, 255))
        canvas.alpha_composite(frame, (x - 12, top - 12))
        canvas.paste(shot, (x, top), mask)
        canvas.convert("RGB").save(os.path.join(OUT, f"phone-{i}.png"))


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    icon512()
    feature_graphic()
    phone_screenshots()
    for f in sorted(os.listdir(OUT)):
        with Image.open(os.path.join(OUT, f)) as im:
            print(f, im.size)
