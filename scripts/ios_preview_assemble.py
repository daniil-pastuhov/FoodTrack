#!/usr/bin/env python3
"""Cuts the clips scripts/ios_preview.sh recorded into the App Store app preview, one per
listing language:

    scripts/ios_preview_assemble.py [raw-dir] [out-dir] [--no-captions]
        # defaults: build/ios-preview, build/app-store-preview

Writes <out-dir>/<listing folder>/preview.mp4, poster.png and poster_time.txt (en-GB, ru, no,
es-ES, de-DE, fr-FR, sv, da; es-MX and fr-CA take the es-ES and fr-FR files; `scripts/testflight.py
previews` uploads them and sets the poster frame). The video follows App Store Connect's
"App preview specifications" (developer.apple.com/help/app-store-connect/reference/
app-preview-specifications) for the iPhone 6.9" size (Dynamic Island, large display), which the
smaller iPhones scale down from: 886x1920 portrait, 30 fps, H.264 High Profile level 4.0,
progressive, at Apple's 10-12 Mbps target, with a stereo 256 kbps AAC track at 48 kHz (silent:
previews autoplay muted and the captions tell the story), 15 to 30 seconds long (this one is
about 28), about 35 MB.

The story (SHOTS below): the scanner reads a pack's barcode and then its printed date; the item is
saved and lands in the list; the Recipe tab with the items that expire first ticked; a recipe
written from them; the Recipe form choosing a menu set (Days, Courses, Generate menu); the
four-day menu it writes. Each shot is cut from its clip between two of the moments
ios_preview.sh wrote to timeline.json, and the shots are joined with short dissolves (Apple asks
for simple transitions). The captions are the screenshots' own (scripts/frame_screenshots.py's
CAPTIONS; the menu set's and the menu's add a smaller line saying they need a recipe pack,
PACK_NOTES),
in Vollkorn SemiBold, warm white on a Larder green band across the top, over the status
bar, so no app content and nothing at the bottom of the frame, where the App Store puts its
controls, is covered. poster.png is the frame at POSTER_AT (poster_time.txt says when); App Store Connect uses the frame 5
seconds in unless it is told otherwise (Media Manager, Edit Poster Frame). Needs ffmpeg and
Pillow.
"""
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parent))
from frame_screenshots import CAPTIONS, FONT, FONT_BOLD_INDEX, LISTING_FOLDERS, WHITE  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
WIDTH, HEIGHT, FPS = 886, 1920, 30
FADE = 0.4  # seconds of dissolve between shots, and of a caption's change
BAND = 236  # caption band height: the status bar and the navigation row under it
GREEN = (0x1F, 0x43, 0x31)  # green.800

# (clip, (moment, offset), (moment, offset), caption): a shot runs from one moment of its clip's
# timeline (seconds, plus the offset) to another; the moments are ios_preview.sh's marks, each
# Maestro command's start (a tap lands about a second later, once the screen has settled).
# "scanned" is when the scan demo reads the date: it starts counting as the scanner sheet comes
# up, about a second before the screen counts as drawn, and reads the barcode after one pace and
# the date after two. The caption is an index into CAPTIONS: 0 the list, 1 the scan, 2 a recipe,
# 3 the Menu set form ("Choose the days and the courses"), 4 the menu ("Plan up to four days").
SHOTS = [
    ("scan", ("drawn", -0.3), ("scanned", 2.2), 1),   # the pack, its barcode, then its date
    ("scan", ("save", -1.4), ("save", 0.75), 1),      # Add item with the date filled in; Save
    ("scan", ("save", 0.75), ("save", 2.9), 0),       # Home: "saved to Fridge"
    ("scan", ("list", 0.1), ("list", 2.3), 0),        # scrolled down to it
    ("scan", ("recipe", 1.0), ("recipe", 3.8), 2),    # Recipe: what expires first, ticked
    ("recipe", ("swipe", -1.0), ("swipe", 3.0), 2),   # the recipe, down to the method
    ("menuset", ("swipe", -0.8), ("swipe", 5.4), 3),  # Menu set: down to Days, Courses, Generate menu
    ("menu", ("swipe", -0.6), ("swipe", 4.4), 4),     # the four-day menu, down to the fourth day
]
# The captions of what only a recipe pack writes (the menu set and its menu): Apple asks a preview
# that shows something needing a purchase to say so, so they get this smaller line under them.
PAID_CAPTIONS = {3, 4}
PACK_NOTES = {
    "en": "Needs a recipe pack",
    "ru": "Нужен пакет рецептов",
    "nb": "Krever en oppskriftspakke",
    "es": "Requiere un paquete de recetas",
    "de": "Erfordert ein Rezeptpaket",
    "fr": "Nécessite un pack de recettes",
    "sv": "Kräver ett receptpaket",
    "da": "Kræver en opskriftspakke",
}
POSTER_AT = 5.5  # seconds into the preview: the scan card with the product and its date


def run(*args):
    result = subprocess.run(args, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
    if result.returncode:
        sys.exit(f"{args[0]} failed:\n{result.stderr}")


def band_png(path):
    Image.new("RGBA", (WIDTH, BAND), GREEN + (255,)).save(path)


def caption_png(text, path, note=None):
    """The caption's lines, centred on a transparent image the size of the band, with [note] in
    a smaller size under them."""
    size = 56
    font = ImageFont.truetype(FONT, size, index=FONT_BOLD_INDEX)
    lines = text.split("\n")
    while max(font.getlength(line) for line in lines) > 0.88 * WIDTH:
        size -= 2
        font = ImageFont.truetype(FONT, size, index=FONT_BOLD_INDEX)
    if note:
        # Two caption lines and the note must fit the band.
        size = min(size, 50)
        font = ImageFont.truetype(FONT, size, index=FONT_BOLD_INDEX)
    pitch = round(1.16 * size)
    note_size = round(0.62 * size)
    note_font = ImageFont.truetype(FONT, note_size, index=FONT_BOLD_INDEX) if note else None
    while note and note_font.getlength(note) > 0.88 * WIDTH:
        note_size -= 1
        note_font = ImageFont.truetype(FONT, note_size, index=FONT_BOLD_INDEX)
    image = Image.new("RGBA", (WIDTH, BAND), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    height = pitch * (len(lines) - 1) + size + (round(0.35 * size) + note_size if note else 0)
    # Vertically centred on the band, a little below its middle, under the rounded screen corners.
    top = (BAND - height) / 2 + 0.06 * size
    for i, line in enumerate(lines):
        draw.text((WIDTH / 2, top + i * pitch), line, font=font, fill=WHITE, anchor="mt")
    if note:
        draw.text((WIDTH / 2, top + (len(lines) - 1) * pitch + size + round(0.35 * size)), note,
                  font=note_font, fill=WHITE + (225,), anchor="mt")
    image.save(path)


def moment(timeline, clip, point):
    name, offset = point
    marks = timeline[clip]
    at = marks["drawn"] - 1.0 + 2 * marks["pace"] if name == "scanned" else marks[name]
    return at + offset


def assemble(lang, raw, out, captions):
    timeline = json.loads((raw / "timeline.json").read_text())
    folder = out / LISTING_FOLDERS[lang]
    folder.mkdir(parents=True, exist_ok=True)
    clips = sorted({shot[0] for shot in SHOTS})
    inputs = []
    for clip in clips:
        inputs += ["-i", str(raw / f"{clip}.mp4")]
    filters, starts, lengths = [], [], []
    # Each clip is split into as many copies as it has shots.
    for clip in clips:
        uses = [n for n, shot in enumerate(SHOTS) if shot[0] == clip]
        # simctl records at the screen's size (1320x2868, a hair taller than 886x1920): scaled to
        # the width with the few spare rows cut from the bottom, no bars. Its frame rate varies
        # with what changes on screen, so it is resampled to a constant 30 first.
        filters.append(f"[{clips.index(clip)}:v]fps={FPS},scale={WIDTH}:-2:flags=lanczos,"
                       f"crop={WIDTH}:{HEIGHT}:0:0,setsar=1,format=yuv420p,"
                       f"split={len(uses)}" + "".join(f"[c{n}]" for n in uses))
    for n, (clip, start, end, _) in enumerate(SHOTS):
        a, b = moment(timeline, clip, start), moment(timeline, clip, end)
        if not 0 <= a < b <= timeline[clip]["end"] + 0.3:
            sys.exit(f"{lang}: shot {n + 1} ({clip} {a:.2f}-{b:.2f} s) is outside its recording "
                     f"(0-{timeline[clip]['end']} s)")
        lengths.append(b - a)
        filters.append(f"[c{n}]trim=start={a:.3f}:end={b:.3f},setpts=PTS-STARTPTS[s{n}]")
    current, elapsed = "s0", lengths[0]
    starts.append(0.0)
    for n in range(1, len(SHOTS)):
        offset = elapsed - FADE
        starts.append(offset)
        filters.append(f"[{current}][s{n}]xfade=transition=fade:duration={FADE}:offset={offset:.3f}[x{n}]")
        current, elapsed = f"x{n}", offset + lengths[n]
    duration = elapsed
    if not 15 <= duration <= 30:
        sys.exit(f"{lang}: the preview would be {duration:.1f} s; App Store Connect takes 15 to 30")

    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        if captions:
            band_png(tmp / "band.png")
            inputs += ["-loop", "1", "-framerate", str(FPS), "-t", f"{duration:.3f}", "-i", str(tmp / "band.png")]
            filters.append(f"[{current}][{inputs.count('-i') - 1}:v]overlay=0:0[band]")
            current = "band"
            # One caption per run of shots that share it, changing with a cross-fade at the
            # dissolve between them.
            runs = []
            for n, shot in enumerate(SHOTS):
                if runs and runs[-1][0] == shot[3]:
                    runs[-1][2] = n
                else:
                    runs.append([shot[3], n, n])
            for r, (caption, first, last) in enumerate(runs):
                png = tmp / f"caption{r}.png"
                # The runs of what only a pack writes carry the purchase note.
                caption_png(CAPTIONS[lang][caption], png,
                            PACK_NOTES[lang] if caption in PAID_CAPTIONS else None)
                shown = starts[first] if first else 0.0
                hidden = starts[last] + lengths[last] if last < len(SHOTS) - 1 else duration
                inputs += ["-loop", "1", "-framerate", str(FPS), "-t", f"{duration:.3f}", "-i", str(png)]
                fades = []
                if first:
                    fades.append(f"fade=in:st={shown:.3f}:d={FADE}:alpha=1")
                if last < len(SHOTS) - 1:
                    fades.append(f"fade=out:st={hidden - FADE:.3f}:d={FADE}:alpha=1")
                filters.append(f"[{inputs.count('-i') - 1}:v]format=rgba" + "".join("," + f for f in fades)
                               + f"[t{r}]")
                filters.append(f"[{current}][t{r}]overlay=0:0:enable='between(t,{shown:.3f},{hidden:.3f})'[o{r}]")
                current = f"o{r}"
        filters.append(f"[{current}]format=yuv420p[v]")
        video = folder / "preview.mp4"
        audio = inputs.count("-i")
        run("ffmpeg", "-y", "-loglevel", "error", *inputs,
            "-f", "lavfi", "-t", f"{duration:.3f}", "-i", "anullsrc=channel_layout=stereo:sample_rate=48000",
            "-filter_complex", ";".join(filters), "-map", "[v]", "-map", f"{audio}:a",
            "-r", str(FPS), "-c:v", "libx264", "-profile:v", "high", "-level:v", "4.0", "-pix_fmt", "yuv420p",
            # Constant 10 Mbps, the low end of Apple's 10-12 Mbps target: left to itself, x264
            # spends half that on these mostly still screens.
            "-preset", "slow", "-b:v", "10M", "-minrate", "10M", "-maxrate", "10M", "-bufsize", "10M",
            "-x264-params", "nal-hrd=cbr",
            "-c:a", "aac", "-b:a", "256k", "-ar", "48000", "-ac", "2",
            "-t", f"{duration:.3f}", "-movflags", "+faststart", str(video))
    run("ffmpeg", "-y", "-loglevel", "error", "-ss", f"{POSTER_AT}", "-i", str(video),
        "-frames:v", "1", str(folder / "poster.png"))
    # The poster time, for `testflight.py previews` (previewFrameTimeCode).
    (folder / "poster_time.txt").write_text(f"{POSTER_AT}\n")
    return video, duration, starts


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    captions = "--no-captions" not in sys.argv
    raw = Path(args[0]) if args else ROOT / "build" / "ios-preview"
    out = Path(args[1]) if len(args) > 1 else ROOT / "build" / "app-store-preview"
    if not shutil.which("ffmpeg"):
        sys.exit("needs ffmpeg (e.g. brew install ffmpeg)")
    for lang in LISTING_FOLDERS:
        if not (raw / lang / "timeline.json").exists():
            print(f"{lang}: no clips in {raw / lang}, skipped")
            continue
        video, duration, starts = assemble(lang, raw / lang, out, captions)
        print(f"{lang}: {video} ({duration:.1f} s, {video.stat().st_size / 1e6:.1f} MB; shots start at "
              + ", ".join(f"{s:.1f}" for s in starts) + ")")


if __name__ == "__main__":
    main()
