#!/usr/bin/env python3
"""Makes the App Store "Header and Search Results" images (iOS 27 and later) from raw simulator
screenshots, on the same Larder green gradient and device bezel as the screenshot sets:

    DEVICE_NAME="iPhone 17 Pro" scripts/ios_screenshots.sh build/raw
    scripts/store_art.py build/raw

writes store-listing/app-store/<listing folder>/header.png (3840x1646, 21:9: three phones, no
text, so it suits every language) and search-result.png (3840x2560, 3:2: a headline and the home
screen). Apple's templates keep key artwork in a safe area, which these layouts stay inside:
header x 1097-2743, y 493-1154; search results x 836-3004, y 765-1795. The back and share buttons
sit over the header's top edge.

    ANDROID_SERIAL=<emulator> scripts/android_screenshots.sh build/raw-android
    scripts/store_art.py build/raw build/raw-android

also writes store-listing/google-play/<listing folder>/feature-graphic.png (1024x500): the icon,
the app's name and a line about it beside the Android home screen. Play may crop the edges or
put its play button over the middle, so the text keeps clear of the left edge and the phone of
the right one. Needs Pillow.
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

from frame_screenshots import (BEZEL, FONT, FONT_BOLD_INDEX, LISTING_FOLDERS, PLAY_FOLDERS, ROOT,
                               WHITE, gradient)

FEATURE_SIZE = (1024, 500)
# The feature graphic's name (Android's app_name in each language) and the line under it, the
# Play short description in brief.
APP_NAMES = {"en": "BestBefore", "ru": "Срокоед", "nb": "Matvakt"}
TAGLINES = {
    "en": "Scan groceries. Track dates.\nWaste less food.",
    "ru": "Сканируйте продукты.\nСледите за сроками.\nВыбрасывайте меньше.",
    "nb": "Skann matvarer. Følg datoene.\nKast mindre mat.",
}
PLAY_ICON = ROOT / "store-listing" / "google-play" / "icon-512.png"
FONT_MEDIUM = str(ROOT / "androidApp" / "src" / "main" / "res" / "font" / "vollkorn_medium.ttf")

HEADER_SIZE = (3840, 1646)
SEARCH_SIZE = (3840, 2560)
SEARCH_SAFE = (836, 765, 3004, 1795)

# Search shows the image about 365 points wide, so the headline is short and large. "\n" is where
# it breaks.
HEADLINES = {
    "en": "Know what's\nabout to\nexpire",
    "ru": "Видно,\nчто скоро\nиспортится",
    "nb": "Se hva\nsom snart\ngår ut",
}


def phone(shot, screen_width):
    """The screenshot in a dark bezel with rounded corners, plus its soft shadow, as RGBA."""
    bezel = round(0.0167 * screen_width)
    screen_height = round(screen_width * shot.height / shot.width)
    width, height = screen_width + 2 * bezel, screen_height + 2 * bezel
    radius = round(0.1 * width)

    body = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    ImageDraw.Draw(body).rounded_rectangle((0, 0, width - 1, height - 1), radius=radius,
                                           fill=BEZEL + (255,))
    screen = shot.resize((screen_width, screen_height), Image.LANCZOS)
    mask = Image.new("L", screen.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, screen_width - 1, screen_height - 1),
                                           radius=radius - bezel, fill=255)
    body.paste(screen, (bezel, bezel), mask)
    return body


def paste_with_shadow(canvas, body, left, top):
    blur = round(0.04 * body.width)
    shadow = Image.new("RGBA", (body.width + 4 * blur, body.height + 4 * blur), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle(
        (2 * blur, 2 * blur, 2 * blur + body.width, 2 * blur + body.height),
        radius=round(0.1 * body.width), fill=(0, 30, 26, 110))
    shadow = shadow.filter(ImageFilter.GaussianBlur(blur))
    canvas.alpha_composite(shadow, (left - 2 * blur, top - 2 * blur + blur // 2))
    canvas.alpha_composite(body, (left, top))


def header(shots, out_path):
    width, height = HEADER_SIZE
    canvas = gradient(width, height).convert("RGBA")
    # Home in the middle, Recipe and the shopping list a step lower on either side. They run off
    # the bottom edge; their top halves, with the titles, sit in the safe area.
    centre = phone(shots["home"], 820)
    side_width = 700
    left_phone, right_phone = phone(shots["recipe"], side_width), phone(shots["shopping"], side_width)
    gap = 110
    paste_with_shadow(canvas, left_phone, width // 2 - centre.width // 2 - gap - left_phone.width, 470)
    paste_with_shadow(canvas, right_phone, width // 2 + centre.width // 2 + gap, 470)
    paste_with_shadow(canvas, centre, width // 2 - centre.width // 2, 300)
    save(canvas, out_path)


def search_result(shots, headline, out_path):
    width, height = SEARCH_SIZE
    safe_left, safe_top, safe_right, safe_bottom = SEARCH_SAFE
    canvas = gradient(width, height).convert("RGBA")
    draw = ImageDraw.Draw(canvas)

    # The phone runs off the bottom edge; its top, with the item list, fills the safe area's
    # right half.
    body = phone(shots["home"], 1120)
    phone_left = safe_right - body.width
    paste_with_shadow(canvas, body, phone_left, 560)

    # The largest size at which every line fits between the safe area's left edge and the phone.
    lines = headline.split("\n")
    max_width = phone_left - 140 - safe_left
    size = 260
    while True:
        font = ImageFont.truetype(FONT, size, index=FONT_BOLD_INDEX)
        if max(font.getlength(line) for line in lines) <= max_width:
            break
        size -= 4
    # Centred on the safe area's height, from the first line's cap height to the last baseline.
    pitch = round(1.12 * size)
    block = (len(lines) - 1) * pitch + font.getbbox("H", anchor="ls")[3] - font.getbbox("H", anchor="ls")[1]
    top = (safe_top + safe_bottom - block) // 2
    for i, line in enumerate(lines):
        draw.text((safe_left, top + i * pitch), line, font=font, fill=WHITE, anchor="lt")
    save(canvas, out_path)


def fitted_font(path, lines, max_width, size):
    """The largest size up to [size] at which every line fits in [max_width]."""
    while True:
        font = ImageFont.truetype(path, size, index=FONT_BOLD_INDEX)
        if max(font.getlength(line) for line in lines) <= max_width or size <= 8:
            return font
        size -= 1


def feature_graphic(home, name, tagline, out_path):
    width, height = FEATURE_SIZE
    canvas = gradient(width, height).convert("RGBA")
    draw = ImageDraw.Draw(canvas)

    # The phone on the right runs off the bottom edge, its title and first items in view.
    body = phone(home, 300)
    phone_left = width - 84 - body.width
    paste_with_shadow(canvas, body, phone_left, 46)

    # Icon, name and tagline as one block on the left, centred on the canvas's height.
    text_left = 72
    max_width = phone_left - 48 - text_left
    icon_size = 120
    icon = Image.open(PLAY_ICON).convert("RGBA").resize((icon_size, icon_size), Image.LANCZOS)
    mask = Image.new("L", icon.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, icon_size - 1, icon_size - 1),
                                           radius=round(0.225 * icon_size), fill=255)
    icon.putalpha(mask)
    title_font = fitted_font(FONT, [name], max_width, 84)
    lines = tagline.split("\n")
    line_font = fitted_font(FONT_MEDIUM, lines, max_width, 34)
    line_pitch = round(1.3 * line_font.size)
    gap_icon, gap_title = 30, 22
    block = icon_size + gap_icon + title_font.size + gap_title + len(lines) * line_pitch
    top = (height - block) // 2
    paste_with_shadow(canvas, icon, text_left, top)  # lifts the green icon off the green
    y = top + icon_size + gap_icon
    draw.text((text_left, y), name, font=title_font, fill=WHITE, anchor="lt")
    y += title_font.size + gap_title
    for i, line in enumerate(lines):
        draw.text((text_left, y + i * line_pitch), line, font=line_font, fill=WHITE, anchor="lt")
    save(canvas, out_path)


def save(canvas, out_path):
    out_path.parent.mkdir(parents=True, exist_ok=True)
    canvas.convert("RGB").save(out_path)  # no alpha channel, which App Store Connect rejects
    print(out_path.relative_to(ROOT))


def main():
    if len(sys.argv) not in (2, 3):
        sys.exit(__doc__)
    raw_dir = Path(sys.argv[1])
    for lang, folder in LISTING_FOLDERS.items():
        shots = {}
        for tab in ("home", "recipe", "shopping"):
            matches = sorted((raw_dir / lang).glob(f"[0-9]-{tab}.png"))
            if not matches:
                sys.exit(f"no {tab} screenshot in {raw_dir / lang}")
            shots[tab] = Image.open(matches[0]).convert("RGB")
        listing = ROOT / "store-listing" / "app-store" / folder
        header(shots, listing / "header.png")
        search_result(shots, HEADLINES[lang], listing / "search-result.png")
    if len(sys.argv) == 3:
        android_dir = Path(sys.argv[2])
        for lang, folder in PLAY_FOLDERS.items():
            matches = sorted((android_dir / lang).glob("[0-9]-home.png"))
            if not matches:
                sys.exit(f"no home screenshot in {android_dir / lang}")
            feature_graphic(Image.open(matches[0]).convert("RGB"), APP_NAMES[lang], TAGLINES[lang],
                            ROOT / "store-listing" / "google-play" / folder / "feature-graphic.png")


if __name__ == "__main__":
    main()
