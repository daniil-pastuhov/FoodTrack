#!/usr/bin/env python3
"""Draws the app icon in the Larder palette (docs/design/tokens.json) and writes every size both
apps and the Play listing use:

    scripts/gen_app_icon.py

The artwork is the jar with a barcode label and a clock badge, on a 1024 grid. The background is
bottle green, the jar oat paper, and the badge honey, the colour the design gives food that is
coming up soon. Needs Pillow.
"""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SIZE = 1024
SUPERSAMPLE = 4

# Larder primitives, from docs/design/tokens.json.
GREEN_600 = "#2f6246"  # light-theme primary
GREEN_800 = "#1f4331"
GREEN_900 = "#102a1c"
PAPER = "#fffdf8"  # neutral.0
HONEY_300 = "#eac27a"
APRICOT_300 = "#edb183"

# Also the gradient in androidApp/src/main/res/drawable/ic_launcher_background.xml.
BACKGROUND = (GREEN_600, GREEN_900)
BADGE = (HONEY_300, APRICOT_300)

LID = (452, 196, 571, 250, 20)
JAR = (288, 248, 736, 688, 68)
BARS_Y = (418, 509)
BARS_X = [(392, 401), (412, 429), (440, 447), (458, 471), (482, 489), (500, 519),
          (530, 539), (550, 557), (568, 583), (594, 601), (612, 623)]
BADGE_CENTER, BADGE_RADIUS = (700, 646), 128
CLOCK_RADIUS, CLOCK_STROKE = 80, 16
CLOCK_HANDS = [(700, 597), (735, 664)]

# Android adaptive foreground: a 108dp canvas, of which launchers always show a circle of this
# radius in the middle. The art is centred and scaled so none of it falls outside.
FOREGROUND_CANVAS_DP = 108
SAFE_ZONE_RADIUS_DP = 33
RESAMPLING_MARGIN_DP = 0.5  # resizing blurs the edge outward by a few pixels
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
IOS_SIZES = {
    "AppIcon-20@1x.png": 20, "AppIcon-20@2x.png": 40, "AppIcon-20@2x-ipad.png": 40,
    "AppIcon-20@3x.png": 60, "AppIcon-29@1x.png": 29, "AppIcon-29@2x.png": 58,
    "AppIcon-29@2x-ipad.png": 58, "AppIcon-29@3x.png": 87, "AppIcon-40@1x.png": 40,
    "AppIcon-40@2x.png": 80, "AppIcon-40@2x-ipad.png": 80, "AppIcon-40@3x.png": 120,
    "AppIcon-60@2x.png": 120, "AppIcon-60@3x.png": 180, "AppIcon-76@1x.png": 76,
    "AppIcon-76@2x.png": 152, "AppIcon-83.5@2x.png": 167, "AppIcon-1024.png": 1024,
}


def rgb(hex_colour):
    return tuple(int(hex_colour[i:i + 2], 16) for i in (1, 3, 5))


def diagonal_gradient(size, start, end):
    """Top-left to bottom-right, like the 315° gradient in the Android background drawable."""
    start, end = rgb(start), rgb(end)
    small = Image.new("RGB", (256, 256))
    px = small.load()
    for y in range(256):
        for x in range(256):
            t = (x + y) / 510
            px[x, y] = tuple(round(a + (b - a) * t) for a, b in zip(start, end))
    return small.resize((size, size), Image.BICUBIC)


def art():
    """The jar and badge on a transparent 1024 canvas."""
    k = SUPERSAMPLE
    s = SIZE * k
    layer = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)

    def box(x0, y0, x1, y1):
        return [x0 * k, y0 * k, x1 * k, y1 * k]

    for x0, y0, x1, y1, r in (LID, JAR):
        draw.rounded_rectangle(box(x0, y0, x1, y1), radius=r * k, fill=PAPER)
    for x0, x1 in BARS_X:
        draw.rounded_rectangle(box(x0, BARS_Y[0], x1, BARS_Y[1]), radius=4 * k, fill=GREEN_600)

    cx, cy = BADGE_CENTER
    r = BADGE_RADIUS
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).ellipse(box(cx - r, cy - r, cx + r, cy + r), fill=255)
    badge = diagonal_gradient(2 * r * k, *BADGE)
    badge_full = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    badge_full.paste(badge, ((cx - r) * k, (cy - r) * k))
    layer.paste(badge_full, (0, 0), mask)

    rc = CLOCK_RADIUS
    draw.ellipse(box(cx - rc, cy - rc, cx + rc, cy + rc), outline=GREEN_800, width=CLOCK_STROKE * k)
    hands = [(x * k, y * k) for x, y in (CLOCK_HANDS[0], BADGE_CENTER, CLOCK_HANDS[1])]
    draw.line(hands, fill=GREEN_800, width=CLOCK_STROKE * k, joint="curve")
    cap = CLOCK_STROKE * k / 2
    for x, y in hands:
        draw.ellipse([x - cap, y - cap, x + cap, y + cap], fill=GREEN_800)

    return layer.resize((SIZE, SIZE), Image.LANCZOS)


def extent(layer):
    """The centre of the art's bounding box, and how far from it the furthest visible pixel is,
    both in 1024-grid units."""
    left, top, right, bottom = layer.getbbox()
    cx, cy = (left + right) / 2, (top + bottom) / 2
    alpha = layer.getchannel("A").load()
    reach = max(
        ((x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2) ** 0.5
        for y in range(top, bottom) for x in range(left, right) if alpha[x, y] > 0
    )
    return (cx, cy), reach


def save(image, path, size):
    path.parent.mkdir(parents=True, exist_ok=True)
    image.resize((size, size), Image.LANCZOS).save(path, optimize=True)


def main():
    foreground = art()
    full = diagonal_gradient(SIZE, *BACKGROUND).convert("RGBA")
    full.alpha_composite(foreground)
    opaque = full.convert("RGB")  # App Store icons must not have an alpha channel

    ios = ROOT / "iosApp/FoodTracker/Assets.xcassets/AppIcon.appiconset"
    for name, size in IOS_SIZES.items():
        save(opaque, ios / name, size)
    save(opaque, ROOT / "store-listing/google-play/icon-512.png", 512)

    circle = Image.new("L", (SIZE * SUPERSAMPLE,) * 2, 0)
    ImageDraw.Draw(circle).ellipse([0, 0, SIZE * SUPERSAMPLE - 1, SIZE * SUPERSAMPLE - 1], fill=255)
    round_icon = full.copy()
    round_icon.putalpha(circle.resize((SIZE, SIZE), Image.LANCZOS))

    centre, reach = extent(foreground)
    res = ROOT / "androidApp/src/main/res"
    for density, factor in DENSITIES.items():
        folder = res / f"mipmap-{density}"
        save(opaque, folder / "ic_launcher.png", round(48 * factor))
        save(round_icon, folder / "ic_launcher_round.png", round(48 * factor))
        canvas = round(FOREGROUND_CANVAS_DP * factor)
        scale = (SAFE_ZONE_RADIUS_DP - RESAMPLING_MARGIN_DP) * factor / reach
        fg = foreground.resize((round(SIZE * scale),) * 2, Image.LANCZOS)
        layer = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        layer.alpha_composite(fg, (round(canvas / 2 - centre[0] * scale),
                                   round(canvas / 2 - centre[1] * scale)))
        layer.save(folder / "ic_launcher_foreground.png", optimize=True)


if __name__ == "__main__":
    main()
