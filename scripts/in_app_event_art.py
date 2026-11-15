#!/usr/bin/env python3
"""Makes the App Store in-app event images for the Recipe launch from the framed iPhone
screenshots already in the repo, on Larder green with the same device bezel and shadow as the
store art:

    scripts/in_app_event_art.py

reads store-listing/app-store/<listing folder>/screenshots/{2,3,4}.png (the scan, a written
recipe and the three-day menu, as scripts/frame_screenshots.py made them), cuts the phone out of
each (without its caption) and writes store-listing/app-store/in-app-event/<listing folder>/:

- event-card.png, 3840x2160 (16:9): the three phones side by side, the recipe in the middle.
- event-details.png, 2160x3840 (9:16): the recipe in front, the scan and the menu behind it.

Apple's specs (developer.apple.com/help/app-store-connect/reference/in-app-event-media-and-audio-
specifications): event card 16:9, 1920x1080 to 3840x2160; event details page 9:16, 1080x1920 to
2160x3840; .png or .jpg. developer.apple.com/app-store/in-app-events/ asks for no text or logos
(above all not the event's or the app's name), no borders and no added gradients, and says the
App Store crops the media and lays its own gradient under the event name and description, which
it sets over the bottom of the card and of the details page. Apple publishes no safe-area
figures, so the layouts keep what matters (the phones' title bars and first lines; on the
details page, the front phone's) in the top 55% and the middle 80% of the width: on the card the
phones run off the bottom edge under the text, and the details page leaves its lower third
plain green for it. The only words are the app's own screens. Needs Pillow.
"""
import sys

from PIL import Image, ImageDraw

from frame_screenshots import BEZEL, LISTING_FOLDERS, ROOT
from store_art import paste_with_shadow

CARD_SIZE = (3840, 2160)
DETAILS_SIZE = (2160, 3840)
# Larder green.800 (docs/design/tokens.json), flat: Apple adds its own gradient under the text.
BACKGROUND = (0x1F, 0x43, 0x31)
# The framed screenshots each phone is cut from: the scan, the recipe and the menu.
SHOTS = {"scan": "2.png", "recipe": "3.png", "menu": "4.png"}
# What the layouts keep clear: the share of the height (from the top) that holds every phone's
# title bar and first lines, and the share of the width they stay within.
KEY_HEIGHT = 0.55
KEY_WIDTH = 0.8
OUT_DIR = ROOT / "store-listing" / "app-store" / "in-app-event"


def is_bezel(pixel, tolerance=10):
    return all(abs(a - b) <= tolerance for a, b in zip(pixel, BEZEL))


def cut_phone(framed):
    """The device (bezel and screen) out of a framed iPhone screenshot, as RGBA with its rounded
    corners transparent. Found by walking in from each edge to the first bezel pixel, so nothing
    on the screen itself can be mistaken for the bezel."""
    framed = framed.convert("RGB")
    width, height = framed.size
    pixels = framed.load()
    row, column = round(0.6 * height), width // 2
    left = next(x for x in range(width) if is_bezel(pixels[x, row]))
    right = next(x for x in range(width - 1, -1, -1) if is_bezel(pixels[x, row]))
    top = next(y for y in range(height) if is_bezel(pixels[column, y]))
    bottom = next(y for y in range(height - 1, -1, -1) if is_bezel(pixels[column, y]))
    body = framed.crop((left, top, right + 1, bottom + 1)).convert("RGBA")
    # frame_screenshots.layout() draws iPhone frames with a corner radius of 0.1 x the canvas
    # width.
    mask = Image.new("L", body.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, body.width - 1, body.height - 1),
                                           radius=round(0.1 * width), fill=255)
    body.putalpha(mask)
    return body


def scaled(body, width):
    return body.resize((width, round(body.height * width / body.width)), Image.LANCZOS)


def check_key_area(name, canvas_size, boxes):
    """Stops if a phone's top (its title bar and first lines, the top fifth of the device) leaves
    the key area."""
    width, height = canvas_size
    margin = (1 - KEY_WIDTH) / 2 * width
    for label, (left, top, phone_width, phone_height) in boxes.items():
        if left < margin or left + phone_width > width - margin or top + 0.2 * phone_height > KEY_HEIGHT * height:
            sys.exit(f"{name}: the {label} phone's top leaves the key area")


def event_card(phones, out_path):
    """16:9: the scan, the recipe and the menu in a row, the recipe larger and higher."""
    width, height = CARD_SIZE
    canvas = Image.new("RGBA", CARD_SIZE, BACKGROUND + (255,))
    centre = scaled(phones["recipe"], round(0.27 * width))
    side_width = round(0.22 * width)
    left_phone, right_phone = scaled(phones["scan"], side_width), scaled(phones["menu"], side_width)
    gap = round(0.035 * width)
    centre_left = (width - centre.width) // 2
    centre_top, side_top = round(0.07 * height), round(0.16 * height)
    boxes = {
        "scan": (centre_left - gap - left_phone.width, side_top, left_phone.width, left_phone.height),
        "menu": (centre_left + centre.width + gap, side_top, right_phone.width, right_phone.height),
        "recipe": (centre_left, centre_top, centre.width, centre.height),
    }
    check_key_area(out_path.name, CARD_SIZE, boxes)
    for label, body in (("scan", left_phone), ("menu", right_phone), ("recipe", centre)):
        paste_with_shadow(canvas, body, *boxes[label][:2])
    save(canvas, out_path)


def event_details(phones, out_path):
    """9:16: the recipe in front, the scan and the menu behind it to either side, a step lower."""
    width, height = DETAILS_SIZE
    canvas = Image.new("RGBA", DETAILS_SIZE, BACKGROUND + (255,))
    front = scaled(phones["recipe"], round(0.58 * width))
    side_width = round(0.42 * width)
    left_phone, right_phone = scaled(phones["scan"], side_width), scaled(phones["menu"], side_width)
    front_left = (width - front.width) // 2
    front_top, side_top = round(0.09 * height), round(0.18 * height)
    # The phones behind show their left and right thirds past the front one; only the front one
    # has to stay in the key area.
    edge = round(0.03 * width)
    boxes = {
        "scan": (edge, side_top, left_phone.width, left_phone.height),
        "menu": (width - edge - right_phone.width, side_top, right_phone.width, right_phone.height),
        "recipe": (front_left, front_top, front.width, front.height),
    }
    check_key_area(out_path.name, DETAILS_SIZE, {"recipe": boxes["recipe"]})
    for label, body in (("scan", left_phone), ("menu", right_phone), ("recipe", front)):
        paste_with_shadow(canvas, body, *boxes[label][:2])
    save(canvas, out_path)


def save(canvas, out_path):
    out_path.parent.mkdir(parents=True, exist_ok=True)
    canvas.convert("RGB").save(out_path, optimize=True)  # no alpha channel
    print(out_path.relative_to(ROOT))


def main():
    if len(sys.argv) != 1:
        sys.exit(__doc__)
    for folder in LISTING_FOLDERS.values():
        source = ROOT / "store-listing" / "app-store" / folder / "screenshots"
        if not source.is_dir():
            # A language whose screenshots haven't been framed yet; es-MX and fr-CA take es-ES's
            # and fr-FR's media (scripts/testflight.py, fallback).
            print(f"{folder}: no screenshots yet, skipped")
            continue
        phones = {}
        for label, name in SHOTS.items():
            if not (source / name).is_file():
                sys.exit(f"no {source / name}; frame the screenshots first")
            phones[label] = cut_phone(Image.open(source / name))
        event_card(phones, OUT_DIR / folder / "event-card.png")
        event_details(phones, OUT_DIR / folder / "event-details.png")


if __name__ == "__main__":
    main()
