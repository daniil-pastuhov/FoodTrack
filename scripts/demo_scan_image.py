#!/usr/bin/env python3
"""Draws the still picture the scanner shows in its screenshot demo, in place of the camera, which
simulators and emulators don't have:

    scripts/demo_scan_image.py <en|ru|nb> <yyyy-mm-dd> <picture.png> <thumb.png>

<picture.png> is a carton of yoghurt on a kitchen counter, seen as a phone camera would, with an
EAN-13 barcode and a dot-printed best-before date; <thumb.png> is the carton's front, for the
product's picture on the result card. Everything is drawn here, from the repository's own fonts
(Vollkorn and Google Sans, SIL Open Font License): no photograph, no real product or brand. The
barcode starts with 200, a prefix GS1 leaves to shops for their own items, so it is nobody's
product. scripts/ios_screenshots.sh and scripts/android_screenshots.sh make both files, hand them
to the debug-only scan demo (`-demoRoute scan` / `--es demoRoute scan`) and put the product in
the demo pantry's product cache (scripts/demo_backup.py), so the scan finds it without the
network. Needs Pillow.
"""
import datetime
import random
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
FONTS = ROOT / "androidApp" / "src" / "main" / "res" / "font"
SERIF = str(FONTS / "vollkorn_semibold.ttf")
SANS = str(FONTS / "google_sans_medium.ttf")

# The product's name on the pack and in the app (demo_backup.py's product cache uses the same).
NAMES = {"en": "Natural yoghurt", "ru": "Йогурт натуральный", "nb": "Naturell yoghurt"}
PACK_LINES = {"en": ["NATURAL", "YOGHURT"], "ru": ["ЙОГУРТ", "НАТУРАЛЬНЫЙ"], "nb": ["NATURELL", "YOGHURT"]}
WEIGHT = {"en": "500 g · 3.5% fat", "ru": "500 г · жирность 3,5%", "nb": "500 g · 3,5 % fett"}
BEST_BEFORE = {"en": "BEST BEFORE", "ru": "ГОДЕН ДО", "nb": "BEST FØR"}

# The picture is 3:4, with the barcode's bars and digits centred on it, BARCODE_WIDTH of its
# width across. The scan demo centres the picture on the aiming frame, 4.5 times the frame's
# width (ScannerSheetView's ScanDemoBackdrop, Android's ScanDemo), so the barcode fills about 80%
# of the frame and the picture still covers the whole camera area, even on an iPad.
WIDTH, HEIGHT = 4200, 5600
BARCODE_WIDTH = 0.18


def ean13(body):
    """The full 13 digits, check digit included."""
    total = sum(int(d) * (3 if i % 2 else 1) for i, d in enumerate(body))
    return body + str((10 - total % 10) % 10)


L_CODES = ["0001101", "0011001", "0010011", "0111101", "0100011", "0110001", "0101111", "0111011",
           "0110111", "0001011"]
G_CODES = ["".join("1" if b == "0" else "0" for b in reversed(code)) for code in L_CODES]
R_CODES = ["".join("1" if b == "0" else "0" for b in code) for code in L_CODES]
PARITY = ["LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG", "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL",
          "LGGLGL"]


# The barcode, with its check digit. The scan demo is handed it, and demo_backup.py caches the
# product under it.
BARCODE = ean13("200123456789")


def ean13_modules(code):
    """The 95 bars and spaces of an EAN-13, "1" for a bar."""
    parity = PARITY[int(code[0])]
    left = "".join((L_CODES if p == "L" else G_CODES)[int(d)] for p, d in zip(parity, code[1:7]))
    right = "".join(R_CODES[int(d)] for d in code[7:])
    return "101" + left + "01010" + right + "101"


def draw_barcode(draw, code, left, top, module, bar_height):
    modules = ean13_modules(code)
    guards = set(range(0, 3)) | set(range(45, 50)) | set(range(92, 95))
    for i, bit in enumerate(modules):
        if bit == "1":
            extra = module * 5 if i in guards else 0
            x = left + i * module
            draw.rectangle((x, top, x + module - 1, top + bar_height + extra), fill=(28, 28, 30))
    font = ImageFont.truetype(SANS, round(module * 9))
    text_y = top + bar_height + module * 1.5
    draw.text((left - module * 7, text_y), code[0], font=font, fill=(28, 28, 30), anchor="lt")
    for group, start in ((code[1:7], 3), (code[7:], 50)):
        centre = left + (start + 21) * module
        draw.text((centre, text_y), " ".join(group), font=font, fill=(28, 28, 30), anchor="mt")


def dot_text(canvas, text, left, top, height, colour):
    """[text] as an inkjet date coder prints it: dots on a grid, sampled from a rendered font."""
    rows = 9
    font = ImageFont.truetype(SANS, 40)
    box = font.getbbox(text)
    mask = Image.new("L", (box[2] - box[0] + 4, box[3] - box[1] + 4), 0)
    ImageDraw.Draw(mask).text((2 - box[0], 2 - box[1]), text, font=font, fill=255)
    cols = round(mask.width / mask.height * rows)
    small = mask.resize((cols, rows), Image.BILINEAR)
    pitch = height / rows
    draw = ImageDraw.Draw(canvas)
    radius = pitch * 0.38
    for y in range(rows):
        for x in range(cols):
            if small.getpixel((x, y)) > 110:
                cx, cy = left + x * pitch, top + y * pitch
                draw.ellipse((cx - radius, cy - radius, cx + radius, cy + radius), fill=colour)
    return cols * pitch


def pack_front(lang, date, width, height):
    """The carton's front face, flat, as RGBA."""
    face = Image.new("RGBA", (width, height), (250, 248, 242, 255))
    draw = ImageDraw.Draw(face)
    # A blue band across the top, with the name on it, and a thin green rule under it.
    band = round(height * 0.36)
    draw.rectangle((0, 0, width, band), fill=(46, 92, 150, 255))
    draw.rectangle((0, band, width, band + round(height * 0.012)), fill=(84, 150, 96, 255))
    lines = PACK_LINES[lang]
    size = round(width * 0.15)
    font = ImageFont.truetype(SERIF, size)
    while max(font.getlength(line) for line in lines) > width * 0.84:
        size -= 2
        font = ImageFont.truetype(SERIF, size)
    y = band * 0.2
    for line in lines:
        draw.text((width / 2, y), line, font=font, fill=(255, 253, 248, 255), anchor="mt")
        y += size * 1.08
    small = ImageFont.truetype(SANS, round(width * 0.055))
    draw.text((width / 2, band - width * 0.05), WEIGHT[lang], font=small, fill=(220, 232, 245, 255),
              anchor="mb")
    # A bowl of yoghurt, in a few flat shapes.
    cx, cy, r = width / 2, band + height * 0.09, width * 0.14
    draw.ellipse((cx - r, cy - r * 0.32, cx + r, cy + r * 0.32), fill=(255, 255, 255, 255),
                 outline=(200, 205, 214, 255), width=4)
    draw.chord((cx - r, cy - r * 0.9, cx + r, cy + r * 0.9), 0, 180, fill=(70, 120, 180, 255))
    draw.ellipse((cx - r * 0.92, cy - r * 0.26, cx + r * 0.92, cy + r * 0.26), fill=(252, 251, 247, 255))
    for dx, dy, rr in ((-0.35, -0.02, 0.1), (0.15, 0.05, 0.08), (0.42, -0.06, 0.07)):
        draw.ellipse((cx + dx * r - rr * r, cy + dy * r - rr * r * 0.6, cx + dx * r + rr * r,
                      cy + dy * r + rr * r * 0.6), fill=(200, 40, 70, 255))
    return face


def best_before_text(lang, date):
    return f"{BEST_BEFORE[lang]} {date.strftime('%d.%m.%Y')}"


def scene(lang, date):
    random.seed(7)
    module = round(WIDTH * BARCODE_WIDTH / 95)
    bar_w, bar_h = 95 * module, 40 * module
    # The bars and the digits under them, centred on the picture.
    bar_left = round(WIDTH / 2 - bar_w / 2)
    bar_top = round(HEIGHT / 2 - (bar_h + module * 11) / 2)
    # The carton's front, laid out around the barcode on its white lower half.
    face_w = round(bar_w / 0.6)
    face_h = round(face_w * 1.42)
    face_left = round(WIDTH / 2 - face_w * 0.53)
    face_top = round(bar_top + bar_h / 2 - face_h * 0.68)
    side_w = round(face_w * 0.14)
    counter_top = face_top + face_h - round(face_h * 0.04)

    canvas = Image.new("RGB", (WIDTH, HEIGHT))
    draw = ImageDraw.Draw(canvas)
    # A pale tiled wall above a warm wooden counter, with a jar and a pot on it; all out of focus,
    # so blurred hard.
    for y in range(HEIGHT):
        if y < counter_top:
            t = y / counter_top
            colour = (round(214 - 30 * t), round(208 - 30 * t), round(196 - 28 * t))
        else:
            t = (y - counter_top) / (HEIGHT - counter_top)
            colour = (round(150 - 40 * t), round(104 - 30 * t), round(66 - 20 * t))
        draw.line((0, y, WIDTH, y), fill=colour)
    for _ in range(140):
        y = random.randint(counter_top, HEIGHT)
        draw.line((0, y, WIDTH, y + random.randint(-40, 40)), fill=(120, 80, 48), width=random.randint(3, 9))
    tile = round(face_w * 0.3)
    for x in range(face_left % tile, WIDTH, tile):
        draw.line((x, 0, x, counter_top), fill=(196, 190, 178), width=8)
    for y in range(counter_top % tile, counter_top, tile):
        draw.line((0, y, WIDTH, y), fill=(196, 190, 178), width=8)
    draw.rounded_rectangle((face_left - face_w * 1.1, counter_top - face_h * 0.55, face_left - face_w * 0.2,
                            counter_top + 20), radius=120, fill=(170, 120, 70))
    draw.rounded_rectangle((face_left + face_w * 1.45, counter_top - face_h * 0.4, face_left + face_w * 2.6,
                            counter_top + 20), radius=80, fill=(120, 150, 110))
    canvas = canvas.filter(ImageFilter.GaussianBlur(32))

    # The carton: its front, a darker side for depth, a top flap and a shadow on the counter.
    shadow = Image.new("L", canvas.size, 0)
    ImageDraw.Draw(shadow).ellipse((face_left - 60, face_top + face_h - 90, face_left + face_w + side_w + 140,
                                    face_top + face_h + 110), fill=170)
    shadow = shadow.filter(ImageFilter.GaussianBlur(40))
    canvas.paste((40, 26, 16), (0, 0), shadow)
    face = pack_front(lang, date, face_w, face_h)
    side = Image.new("RGBA", (side_w, face_h), (0, 0, 0, 0))
    ImageDraw.Draw(side).polygon([(0, 0), (side_w, 40), (side_w, face_h - 30), (0, face_h)],
                                 fill=(205, 202, 194, 255))
    ImageDraw.Draw(side).polygon([(0, 0), (side_w, 40), (side_w, round(face_h * 0.36) + 30),
                                  (0, round(face_h * 0.36))], fill=(34, 70, 118, 255))
    canvas.paste(side, (face_left + face_w, face_top), side)
    canvas.paste(face, (face_left, face_top), face)
    ImageDraw.Draw(canvas).polygon(
        [(face_left, face_top), (face_left + side_w, face_top - 70), (face_left + face_w + side_w, face_top - 30),
         (face_left + face_w, face_top)], fill=(232, 230, 224))

    # The barcode on a white label, and the printed date under it.
    draw = ImageDraw.Draw(canvas)
    pad = module * 10
    draw.rectangle((bar_left - pad - module * 4, bar_top - pad, bar_left + bar_w + pad, bar_top + bar_h + module * 20),
                   fill=(255, 255, 255))
    draw_barcode(draw, BARCODE, bar_left, bar_top, module, bar_h)
    stamp = best_before_text(lang, date)
    stamp_h = round(module * 7.5)
    stamp_top = bar_top + bar_h + module * 26
    width_needed = dot_text(Image.new("RGB", (10, 10)), stamp, 0, -100, stamp_h, (0, 0, 0))
    dot_text(canvas, stamp, round(WIDTH / 2 - width_needed / 2), stamp_top, stamp_h, (40, 40, 52))

    # Light from the upper left, falling off across the picture, then a camera's softness and grain.
    light = Image.new("L", canvas.size, 0)
    for x in range(0, WIDTH, 4):
        ImageDraw.Draw(light).line((x, 0, x, HEIGHT), fill=round(60 * x / WIDTH))
    canvas.paste((20, 16, 10), (0, 0), light)
    canvas = canvas.filter(ImageFilter.GaussianBlur(1.6))
    noise = Image.effect_noise(canvas.size, 18).convert("RGB")
    canvas = Image.blend(canvas, noise, 0.05)
    thumb = face.convert("RGB").crop((0, 0, face_w, round(face_h * 0.62))).resize((360, 360), Image.LANCZOS)
    return canvas, thumb


def main():
    if len(sys.argv) != 5:
        sys.exit(__doc__)
    lang, day, picture_path, thumb_path = sys.argv[1:]
    date = datetime.date.fromisoformat(day)
    picture, thumb = scene(lang, date)
    picture.save(picture_path)
    thumb.save(thumb_path)


if __name__ == "__main__":
    main()
