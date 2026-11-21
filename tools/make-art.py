#!/usr/bin/env python3
"""Draws Qtide's icon and banner into assets/, so the art is reproducible and reviewable.

    python tools/make-art.py

No fonts are needed: the wordmark is drawn from rounded rectangles, so the same file comes out on
every machine. mochy wants a square icon (512 is plenty) and a ~2:1 banner whose bottom third stays
calm (the icon and the module's name sit over it), and takes the page's colour from the banner.
"""

import math
import os
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "assets")

INK = (8, 11, 14)          # near black, mochy's own page
TEAL = (0, 214, 190)       # the accent the page's tint is taken from
TEAL_DIM = (0, 120, 122)
VIOLET = (126, 88, 240)
WHITE = (238, 246, 245)


def gradient(size, top, bottom):
    """a vertical two-colour gradient"""
    w, h = size
    img = Image.new("RGB", (w, h), top)
    draw = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        # a soft ease so the middle doesn't band
        t = t * t * (3 - 2 * t)
        colour = tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        draw.line([(0, y), (w, y)], fill=colour)
    return img


def glow(base, centre, radius, colour, strength=1.0):
    """a soft radial glow added on top, the light behind the mark"""
    layer = Image.new("RGB", base.size, (0, 0, 0))
    draw = ImageDraw.Draw(layer)
    steps = 28
    for i in range(steps, 0, -1):
        t = i / steps
        r = radius * t
        a = int(255 * strength * (1 - t) ** 2.2)
        box = [centre[0] - r, centre[1] - r, centre[0] + r, centre[1] + r]
        draw.ellipse(box, fill=tuple(round(c * a / 255) for c in colour))
    layer = layer.filter(ImageFilter.GaussianBlur(radius / 9))
    return ImageChops.add(base, layer)


def rounded(draw, box, radius, fill):
    draw.rounded_rectangle(box, radius=radius, fill=fill)


def mark(img, x, y, size, colour=TEAL):
    """the Qtide mark: a Q whose tail runs out toward the play button, drawn as shapes at [x, y]"""
    draw = ImageDraw.Draw(img, "RGBA")
    ring = size * 0.62
    width = size * 0.13
    draw.ellipse([x, y, x + ring, y + ring], outline=colour + (255,), width=max(2, round(width)))
    # the tail: a rounded bar on the ring's diagonal, from its stroke out past the edge
    cx, cy = x + ring / 2, y + ring / 2
    d0, d1 = ring * 0.40, ring * 0.70
    thick = width * 0.80
    s = (cx + d0 * 0.707, cy + d0 * 0.707)
    e = (cx + d1 * 0.707, cy + d1 * 0.707)
    draw.line([s, e], fill=colour + (255,), width=round(thick))
    for p in (s, e):
        draw.ellipse([p[0] - thick / 2, p[1] - thick / 2, p[0] + thick / 2, p[1] + thick / 2], fill=colour + (255,))
    # the play inside the ring, a little right of centre so it reads as a play button
    s = ring * 0.30
    px = x + ring * 0.50 - s * 0.22
    py = y + ring * 0.50
    tri = [(px - s * 0.45, py - s * 0.62), (px - s * 0.45, py + s * 0.62), (px + s * 0.78, py)]
    draw.polygon(tri, fill=colour + (255,))


def bars(img, x, y, width, height):
    """three soft equaliser bars under the mark: the "tide" in the name"""
    draw = ImageDraw.Draw(img, "RGBA")
    gap = width * 0.16
    bar = (width - gap * 2) / 3
    heights = (0.55, 1.0, 0.72)
    for i, h in enumerate(heights):
        left = x + i * (bar + gap)
        top = y + height * (1 - h)
        rounded(draw, [left, top, left + bar, y + height], bar / 2, TEAL_DIM + (235,))


def icon(path):
    size = 512
    img = gradient((size, size), (14, 20, 26), INK)
    img = glow(img, (size * 0.30, size * 0.26), size * 0.55, TEAL_DIM, 0.55)
    img = glow(img, (size * 0.82, size * 0.86), size * 0.45, VIOLET, 0.32)
    mark(img, size * 0.20, size * 0.15, size * 0.62)
    bars(img, size * 0.28, size * 0.74, size * 0.44, size * 0.10)
    img.save(path, "PNG", optimize=True)
    return img


def banner(path):
    w, h = 1200, 600
    img = gradient((w, h), (12, 17, 23), INK)
    # the light lives in the top two thirds: the icon and the module's name sit over the bottom
    img = glow(img, (w * 0.18, h * 0.22), h * 0.62, TEAL_DIM, 0.60)
    img = glow(img, (w * 0.72, h * 0.10), h * 0.55, VIOLET, 0.38)
    # a few tide lines, low contrast, fading out toward the calm bottom
    draw = ImageDraw.Draw(img, "RGBA")
    for i in range(7):
        y = h * (0.16 + i * 0.075)
        alpha = int(46 - i * 5)
        if alpha <= 0:
            continue
        points = []
        for px in range(0, w + 12, 12):
            phase = px / w * math.tau * 1.4 + i * 0.7
            points.append((px, y + math.sin(phase) * (10 + i * 2)))
        draw.line(points, fill=TEAL + (alpha,), width=2, joint="curve")
    mark(img, w * 0.055, h * 0.10, h * 0.42)
    # a thin rule where the calm band starts, so text over it stays readable
    draw.line([(0, h * 0.70), (w, h * 0.70)], fill=(255, 255, 255, 18), width=2)
    img.save(path, "PNG", optimize=True)
    return img


def main():
    os.makedirs(OUT, exist_ok=True)
    icon(os.path.join(OUT, "icon.png"))
    banner(os.path.join(OUT, "banner.png"))
    print("wrote", os.path.normpath(OUT))


if __name__ == "__main__":
    sys.exit(main())
