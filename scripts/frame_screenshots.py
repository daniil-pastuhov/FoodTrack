#!/usr/bin/env python3
"""Frames raw screenshots for the stores: a caption on a Larder green gradient above the
screenshot in a dark device bezel.

    scripts/ios_screenshots.sh build/raw                 # raw captures, one folder per language
    scripts/frame_screenshots.py build/raw duo-inner-screenshots

    scripts/android_screenshots.sh build/raw-android
    scripts/frame_screenshots.py build/raw-android phone-screenshots

reads <raw>/<lang>/<n>-<tab>.png (as those scripts write them). An App Store set is written to
store-listing/app-store/<listing folder>/<set>/<n>.png at the raw screenshot's own size, which
is the size App Store Connect wants for that device. The Google Play set, phone-screenshots,
goes to store-listing/google-play/<listing folder>/phone-screenshots/<n>.png at 1080x1920: Play
refuses a screenshot more than twice as tall as it is wide, so the phone runs off the bottom
edge. Needs Pillow. The colours are Larder's (docs/design/tokens.json) and the captions are set
in Vollkorn SemiBold, the app's display face.
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
FONT = str(ROOT / "iosApp" / "FoodTracker" / "Resources" / "Fonts" / "Vollkorn-SemiBold.ttf")
FONT_BOLD_INDEX = 0

# Screenshot script language code -> store-listing/app-store folder
LISTING_FOLDERS = {"en": "en-GB", "ru": "ru", "nb": "no"}
# The same for store-listing/google-play
PLAY_FOLDERS = {"en": "en-GB", "ru": "ru-RU", "nb": "no-NO"}
PLAY_SET = "phone-screenshots"
PLAY_SIZE = (1080, 1920)

# One caption per screenshot, in order: Home, the scan, a recipe, the three-day menu, the shopping
# list and Settings. Both stores get the same six. The recipe and the menu are what the routes
# write, so their captions say what they do, not what they cost. "\n" is where a caption breaks
# on a narrow (iPhone-shaped) screenshot; wider ones put it on one line if it fits.
CAPTIONS = {
    "en": ["Know what's\nabout to expire", "Scan the pack,\nthe date is read", "Cook what needs\nusing first",
           "A three-day menu\naround what you have", "Used it up?\nIt's on your list",
           "Reminders\nwhen it suits you"],
    "ru": ["Видно, что скоро\nиспортится", "Наведите камеру —\nсрок распознан", "Готовьте из того,\nчто пора съесть",
           "Меню на три дня\nпод ваши продукты", "Закончилось —\nуже в списке", "Напоминания в\nудобное время"],
    "nb": ["Se hva som\nsnart går ut", "Skann pakken,\ndatoen leses av", "Lag mat av det\nsom må brukes først",
           "Meny for tre dager\nrundt det du har", "Brukt opp? Den\nstår på listen", "Påminnelser når\ndet passer deg"],
}
# App Store Connect keeps the two iPhone Duo sets in one group of at most 10 images per language,
# so each has five: the shots above without the last, Settings.
SHOT_LIMITS = {"duo-inner-screenshots": 5, "duo-outer-screenshots": 5}

# The background runs from the top-left corner towards the bottom, through Larder's greens
# (green.600, 700, 800 and 900 in tokens.json).
GRADIENT_STOPS = [(0.0, (0x2F, 0x62, 0x46)), (0.25, (0x23, 0x4E, 0x37)), (0.5, (0x1F, 0x43, 0x31)),
                  (1.0, (0x10, 0x2A, 0x1C))]
BEZEL = (0x14, 0x12, 0x0D)  # neutral.950
WHITE = (0xFF, 0xFD, 0xF8)  # neutral.0, the warm white the app uses


def gradient(width, height):
    # t = x/2W + y/H: 0 at the top-left corner, 0.5 at the top-right and halfway down the left
    # edge, and 1 or more along the bottom.
    def colour(t):
        t = min(t, 1.0)
        for (t0, c0), (t1, c1) in zip(GRADIENT_STOPS, GRADIENT_STOPS[1:]):
            if t <= t1:
                f = (t - t0) / (t1 - t0)
                return tuple(round(a + (b - a) * f) for a, b in zip(c0, c1))
        return GRADIENT_STOPS[-1][1]

    # Rendered small and scaled up: the gradient is smooth, and per-pixel Python is slow.
    small = Image.new("RGB", (64, 64))
    small.putdata([colour(x / 63 / 2 + y / 63) for y in range(64) for x in range(64)])
    return small.resize((width, height), Image.BICUBIC)


def layout(width, height):
    """Sizes as fractions of the canvas, measured from the existing sets: iPhone-shaped
    screenshots follow the 6.9" set, wider ones (iPad, iPhone Duo) the 13" iPad set."""
    if width / height < 0.55:
        return dict(font=0.0765 * width, caption_top=0.066 * height, frame_top=0.195 * height,
                    frame_height=0.7245 * height, bezel=0.0167 * width, radius=0.1 * width, wrap=False)
    return dict(font=0.0567 * width, caption_top=0.061 * height, frame_top=0.189 * height,
                frame_height=0.72 * height, bezel=0.0145 * width, radius=0.05 * width, wrap=True)


def caption_lines(text, font, max_width, wrap):
    if not wrap:
        return text.split("\n")
    one_line = text.replace("\n", " ")
    return [one_line] if font.getlength(one_line) <= max_width else text.split("\n")


def frame(raw_path, caption, out_path, canvas_size=None):
    """[canvas_size] other than the screenshot's own size is Play's: the phone keeps the
    screenshot's shape and runs off the bottom edge."""
    shot = Image.open(raw_path).convert("RGB")
    width, height = canvas_size or shot.size
    spec = layout(width, height)
    if canvas_size:
        # Measured from the earlier Play set: a phone 72% of the width, starting 22% down.
        spec.update(font=0.0765 * width, caption_top=0.06 * height, frame_top=0.22 * height,
                    bezel=0.0167 * width, radius=0.07 * width, wrap=False, play_width=0.722 * width)
    canvas = gradient(width, height)
    draw = ImageDraw.Draw(canvas)

    font = ImageFont.truetype(FONT, round(spec["font"]), index=FONT_BOLD_INDEX)
    lines = caption_lines(caption, font, 0.86 * width, spec["wrap"])
    # A line too long for the canvas (a longer translation) is set smaller rather than clipped.
    while max(font.getlength(line) for line in lines) > 0.9 * width:
        spec["font"] *= 0.97
        font = ImageFont.truetype(FONT, round(spec["font"]), index=FONT_BOLD_INDEX)
    pitch = 1.13 * spec["font"]
    top = spec["caption_top"]
    for i, line in enumerate(lines):
        draw.text((width / 2, top + i * pitch), line, font=font, fill=WHITE, anchor="mt")
    caption_bottom = top + (len(lines) - 1) * pitch + spec["font"]

    # A caption longer than its set's usual one pushes the device down rather than into it.
    frame_top = max(spec["frame_top"], caption_bottom + 0.05 * height)
    frame_height = spec["frame_height"] - (frame_top - spec["frame_top"])
    bezel = round(spec["bezel"])
    if "play_width" in spec:
        screen_width = round(spec["play_width"]) - 2 * bezel
        screen_height = round(screen_width * shot.height / shot.width)
    else:
        screen_height = round(frame_height) - 2 * bezel
        screen_width = round(screen_height * width / height)
    frame_width = screen_width + 2 * bezel
    left = round((width - frame_width) / 2)
    frame_top = round(frame_top)

    radius = round(spec["radius"])
    draw.rounded_rectangle((left, frame_top, left + frame_width, frame_top + screen_height + 2 * bezel),
                           radius=radius, fill=BEZEL)
    screen = shot.resize((screen_width, screen_height), Image.LANCZOS)
    mask = Image.new("L", screen.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, screen_width - 1, screen_height - 1),
                                           radius=max(radius - bezel, 0), fill=255)
    canvas.paste(screen, (left + bezel, frame_top + bezel), mask)

    out_path.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(out_path)  # RGB, so no alpha channel, which App Store Connect rejects


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    raw_dir, set_name = Path(sys.argv[1]), sys.argv[2]
    play = set_name == PLAY_SET
    for lang, folder in (PLAY_FOLDERS if play else LISTING_FOLDERS).items():
        captions = CAPTIONS[lang]
        raws = sorted((raw_dir / lang).glob("[0-9]-*.png"))
        if len(raws) != len(captions):
            sys.exit(f"expected {len(captions)} screenshots in {raw_dir / lang}, found {len(raws)}")
        limit = SHOT_LIMITS.get(set_name, len(captions))
        captions, raws = captions[:limit], raws[:limit]
        store = "google-play" if play else "app-store"
        # Last run's shots past the new count would otherwise stay behind in the set.
        for stale in (ROOT / "store-listing" / store / folder / set_name).glob("[0-9].png"):
            if int(stale.stem) > len(captions):
                stale.unlink()
        for n, (raw, caption) in enumerate(zip(raws, captions), start=1):
            out = ROOT / "store-listing" / store / folder / set_name / f"{n}.png"
            frame(raw, caption, out, PLAY_SIZE if play else None)
            print(out.relative_to(ROOT))


if __name__ == "__main__":
    main()
