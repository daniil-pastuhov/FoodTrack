#!/usr/bin/env python3
"""Frames raw simulator screenshots for the App Store: a caption on the teal gradient above the
screenshot in a dark device bezel, the same style as the existing 6.9" iPhone and 13" iPad sets.

    scripts/ios_screenshots.sh build/raw                 # raw captures, one folder per language
    scripts/frame_screenshots.py build/raw duo-inner-screenshots

reads <raw>/<lang>/<n>-<tab>.png (as ios_screenshots.sh writes them) and writes
store-listing/app-store/<listing folder>/<set>/<n>.png at the raw screenshot's own size, which
is the size App Store Connect wants for that device. Needs Pillow; the captions are set in
Helvetica Neue Bold from macOS.
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
FONT = "/System/Library/Fonts/HelveticaNeue.ttc"
FONT_BOLD_INDEX = 1

# ios_screenshots.sh language code -> store-listing/app-store folder
LISTING_FOLDERS = {"en": "en-GB", "ru": "ru", "nb": "no"}

# One caption per tab, in screenshot order (home, calendar, shopping, settings). "\n" is where a
# caption breaks on a narrow (iPhone-shaped) screenshot; wider ones put it on one line if it fits.
CAPTIONS = {
    "en": ["Know what's\nabout to expire", "Your week of\ndates at a glance",
           "Used it up?\nIt's on your list", "Reminders\nwhen it suits you"],
    "ru": ["Видно, что скоро\nиспортится", "Все сроки на\nнеделю вперёд",
           "Закончилось —\nуже в списке", "Напоминания в\nудобное время"],
    "nb": ["Se hva som\nsnart går ut", "Ukens datoer\nmed et blikk",
           "Brukt opp? Den\nstår på listen", "Påminnelser når\ndet passer deg"],
}

# The background runs from the top-left corner towards the bottom, sampled from the existing sets.
GRADIENT_STOPS = [(0.0, (22, 185, 163)), (0.25, (17, 166, 149)), (0.5, (12, 126, 116)),
                  (1.0, (10, 94, 87))]
BEZEL = (11, 31, 28)
WHITE = (255, 255, 255)


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


def frame(raw_path, caption, out_path):
    shot = Image.open(raw_path).convert("RGB")
    width, height = shot.size
    spec = layout(width, height)
    canvas = gradient(width, height)
    draw = ImageDraw.Draw(canvas)

    font = ImageFont.truetype(FONT, round(spec["font"]), index=FONT_BOLD_INDEX)
    lines = caption_lines(caption, font, 0.86 * width, spec["wrap"])
    pitch = 1.13 * spec["font"]
    top = spec["caption_top"]
    for i, line in enumerate(lines):
        draw.text((width / 2, top + i * pitch), line, font=font, fill=WHITE, anchor="mt")
    caption_bottom = top + (len(lines) - 1) * pitch + spec["font"]

    # A caption longer than its set's usual one pushes the device down rather than into it.
    frame_top = max(spec["frame_top"], caption_bottom + 0.05 * height)
    frame_height = spec["frame_height"] - (frame_top - spec["frame_top"])
    bezel = round(spec["bezel"])
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
    for lang, folder in LISTING_FOLDERS.items():
        raws = sorted((raw_dir / lang).glob("[0-9]-*.png"))
        if len(raws) != len(CAPTIONS[lang]):
            sys.exit(f"expected {len(CAPTIONS[lang])} screenshots in {raw_dir / lang}, found {len(raws)}")
        for n, (raw, caption) in enumerate(zip(raws, CAPTIONS[lang]), start=1):
            out = ROOT / "store-listing" / "app-store" / folder / set_name / f"{n}.png"
            frame(raw, caption, out)
            print(out.relative_to(ROOT))


if __name__ == "__main__":
    main()
