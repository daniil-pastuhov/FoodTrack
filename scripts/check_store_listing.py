#!/usr/bin/env python3
"""Checks the store texts in store-listing/ against the stores' limits and the listing rules.

    python3 scripts/check_store_listing.py          # prints every count, exits 1 on a problem

App Store (store-listing/app-store/<locale>/): name and subtitle at most 30 characters, keywords
at most 100, promotional text at most 170, description at most 4000; the App Review notes
(store-listing/app-store/review-notes.txt, one file for every language) at most 4000. Keywords are
comma-separated with no space after a comma, and no keyword word repeats a word of the same
locale's name or subtitle (or its English plural/singular), since Apple indexes those already.
Google Play (store-listing/google-play/<locale>/): title at most 30, short description at most
80, full description at most 4000; no "free", "best", "#1", "top" or emoji in the title.
Every text: never call the paid unit a credit or token (CLAUDE.md, "Recipe").
In-app event (store-listing/app-store/in-app-event/): see check_in_app_event below.
Characters are counted as Unicode code points, as both stores count them.
"""
import json
import pathlib
import re
import sys
import unicodedata

ROOT = pathlib.Path(__file__).resolve().parent.parent / "store-listing"
APP_STORE_LIMITS = {"name.txt": 30, "subtitle.txt": 30, "keywords.txt": 100,
                    "promotional_text.txt": 170, "description.txt": 4000}
PLAY_LIMITS = {"title.txt": 30, "short-description.txt": 80, "full-description.txt": 4000}
REVIEW_NOTES = ("review-notes.txt", 4000)  # in store-listing/app-store/, not per locale
PLAY_TITLE_WORDS = {"free", "best", "top", "#1", "gratis", "бесплатно", "лучший"}
UNIT_WORDS = re.compile(r"\b(credits?|tokens?|кредит\w*|токен\w*|kreditt\w*)\b", re.IGNORECASE)

problems = []


def words(text):
    return {w for w in re.findall(r"[^\W_]+", text.casefold())}


def read(path):
    return path.read_text(encoding="utf-8").strip()


def check_lengths(folder, limits):
    texts = {}
    for name, limit in limits.items():
        path = folder / name
        if not path.exists():
            problems.append(f"{path.relative_to(ROOT)}: missing")
            continue
        text = read(path)
        texts[name] = text
        flag = "" if 0 < len(text) <= limit else "  <-- over the limit" if text else "  <-- empty"
        print(f"  {name:24} {len(text):5d} / {limit}{flag}")
        if flag:
            problems.append(f"{path.relative_to(ROOT)}: {len(text)} characters (limit {limit})")
        if UNIT_WORDS.search(text):
            problems.append(f"{path.relative_to(ROOT)}: calls the paid unit a credit or token")
    return texts


def check_keywords(folder, texts):
    keywords = texts.get("keywords.txt")
    if keywords is None:
        return
    where = (folder / "keywords.txt").relative_to(ROOT)
    if ", " in keywords or " ," in keywords:
        problems.append(f"{where}: a space next to a comma wastes a character")
    items = keywords.split(",")
    if any(not item.strip() for item in items):
        problems.append(f"{where}: an empty keyword")
    folded = [item.casefold() for item in items]
    for item in {i for i in folded if folded.count(i) > 1}:
        problems.append(f"{where}: '{item}' is listed twice")
    indexed = words(texts.get("name.txt", "") + " " + texts.get("subtitle.txt", ""))
    for word in sorted(words(keywords)):
        variants = {word, word + "s", word + "es", word[:-1] if word.endswith("s") else word}
        if variants & indexed:
            problems.append(f"{where}: '{word}' repeats the name or subtitle")


def check_play_title(folder, texts):
    title = texts.get("title.txt", "")
    found = {w for w in re.findall(r"#1|[^\W_]+", title.casefold())} & PLAY_TITLE_WORDS
    if found:
        problems.append(f"{(folder / 'title.txt').relative_to(ROOT)}: {', '.join(sorted(found))}")
    if any(unicodedata.category(c) == "So" for c in title):
        problems.append(f"{(folder / 'title.txt').relative_to(ROOT)}: an emoji or symbol")


def check_review_notes():
    name, limit = REVIEW_NOTES
    path = ROOT / "app-store" / name
    if not path.exists():
        problems.append(f"{path.relative_to(ROOT)}: missing")
        return
    text = read(path)
    flag = "" if 0 < len(text) <= limit else "  <-- over the limit" if text else "  <-- empty"
    print(f"app-store/{name:16} {len(text):5d} / {limit}{flag}")
    if flag:
        problems.append(f"{path.relative_to(ROOT)}: {len(text)} characters (limit {limit})")
    if UNIT_WORDS.search(text):
        problems.append(f"{path.relative_to(ROOT)}: calls the paid unit a credit or token")


def check(stores=("app-store", "google-play")):
    """Checks the given stores' folders and returns the problems found (empty when all pass).
    scripts/testflight.py and scripts/play_listing.py call this before writing to a store."""
    problems.clear()
    for store in stores:
        limits = APP_STORE_LIMITS if store == "app-store" else PLAY_LIMITS
        folders = sorted(p.parent for p in (ROOT / store).glob(f"*/{next(iter(limits))}"))
        if not folders:
            problems.append(f"{store}: no locale folders")
        for folder in folders:
            print(f"{store}/{folder.name}")
            texts = check_lengths(folder, limits)
            if store == "app-store":
                check_keywords(folder, texts)
            else:
                check_play_title(folder, texts)
        if store == "app-store":
            check_review_notes()
    return list(problems)


# --- In-app event (store-listing/app-store/in-app-event/) ------------------------------------------
#
# Limits from App Store Connect Help, "Offer In-App Events" (developer.apple.com/help/
# app-store-connect/offer-in-app-events/offer-in-app-events): reference name up to 64 characters
# (only shown in App Store Connect), event name 30, short description 50, long description 120;
# the event lasts at most 31 days (and at least 15 minutes) and is published at most 14 days
# before it starts. Media from "In-App Event media and audio specifications" (developer.apple.com/
# help/app-store-connect/reference/in-app-event-media-and-audio-specifications): the event card
# 16:9 from 1920x1080 to 3840x2160, the event details page 9:16 from 1080x1920 to 2160x3840.
# developer.apple.com/app-store/in-app-events/ rules out specific prices in the metadata.

EVENT_DIR = ROOT / "app-store" / "in-app-event"
EVENT_CONFIG = "event.json"
EVENT_LIMITS = {"name.txt": 30, "short_description.txt": 50, "long_description.txt": 120}
EVENT_REFERENCE_LIMIT = 64
EVENT_PRIMARY_FALLBACK = "en-GB"
# The API's allowed values (developer.apple.com/documentation/appstoreconnectapi/appevent/
# attributes-data.dictionary).
EVENT_VALUES = {
    "badge": {"LIVE_EVENT", "PREMIERE", "CHALLENGE", "COMPETITION", "NEW_SEASON", "MAJOR_UPDATE",
              "SPECIAL_EVENT"},
    "purpose": {"APPROPRIATE_FOR_ALL_USERS", "ATTRACT_NEW_USERS", "KEEP_ACTIVE_USERS_INFORMED",
                "BRING_BACK_LAPSED_USERS"},
    "priority": {"HIGH", "NORMAL"},
    "purchaseRequirement": {"NO_COST_ASSOCIATED", "IN_APP_PURCHASE"},
}
EVENT_MAX_DAYS = 31
EVENT_MAX_PUBLISH_DAYS_BEFORE = 14
# file -> (aspect ratio, smallest size, largest size)
EVENT_MEDIA = {"event-card.png": ((16, 9), (1920, 1080), (3840, 2160)),
               "event-details.png": ((9, 16), (1080, 1920), (2160, 3840))}
PRICE = re.compile(r"[$€£¥₽]|\d\s*(kr|nok|usd|eur|gbp|руб|р\.)(?!\w)", re.IGNORECASE)


def event_locales(root=EVENT_DIR):
    """The event's locale folders: those with a name.txt."""
    return sorted(p.parent.name for p in pathlib.Path(root).glob("*/name.txt"))


def event_media(root, locale):
    """{file name: path} of the media a localization takes: its own, or en-GB's for another
    English localization without them (the listing's rule for header art)."""
    media = {}
    for name in EVENT_MEDIA:
        for source in (locale, EVENT_PRIMARY_FALLBACK if locale.startswith("en-") else None):
            if source and (pathlib.Path(root) / source / name).is_file():
                media[name] = pathlib.Path(root) / source / name
                break
    return media


def png_info(path):
    """(width, height, has alpha) of a PNG, or None for another format."""
    with open(path, "rb") as f:
        head = f.read(26)
    if head[:8] != b"\x89PNG\r\n\x1a\n" or head[12:16] != b"IHDR":
        return None
    return int.from_bytes(head[16:20], "big"), int.from_bytes(head[20:24], "big"), head[25] in (4, 6)


def check_event_config(config, locales, found):
    def where(key):
        return f"app-store/in-app-event/{EVENT_CONFIG}: {key}"

    reference = config.get("referenceName") or ""
    print(f"  {'referenceName':24} {len(reference):5d} / {EVENT_REFERENCE_LIMIT}")
    if not 0 < len(reference) <= EVENT_REFERENCE_LIMIT:
        found.append(f"{where('referenceName')} {len(reference)} characters (limit {EVENT_REFERENCE_LIMIT})")
    for key, allowed in EVENT_VALUES.items():
        if config.get(key) not in allowed:
            found.append(f"{where(key)} {config.get(key)!r} is not one of {', '.join(sorted(allowed))}")
    if not re.fullmatch(r"[a-z][a-z0-9+.-]*://\S+", config.get("deepLink") or ""):
        found.append(f"{where('deepLink')} {config.get('deepLink')!r} is not a URL")
    if config.get("primaryLocale") not in locales:
        found.append(f"{where('primaryLocale')} {config.get('primaryLocale')!r} has no folder")
    territories = config.get("territories")
    if territories != "app" and not (isinstance(territories, list) and territories
                                     and all(re.fullmatch(r"[A-Z]{3}", str(t)) for t in territories)):
        found.append(f"{where('territories')} is \"app\" or a list of 3-letter territory codes")
    release = config.get("releaseDay")
    if release is not None and not re.fullmatch(r"\d{4}-\d{2}-\d{2}", str(release)):
        found.append(f"{where('releaseDay')} {release!r} is not null or YYYY-MM-DD")
    if not re.fullmatch(r"([01]\d|2[0-3]):[0-5]\d", str(config.get("startTimeUtc"))):
        found.append(f"{where('startTimeUtc')} {config.get('startTimeUtc')!r} is not HH:MM")
    before = config.get("publishDaysBefore")
    if not (isinstance(before, int) and 0 <= before <= EVENT_MAX_PUBLISH_DAYS_BEFORE):
        found.append(f"{where('publishDaysBefore')} {before!r}: a whole number of days from 0 to "
                     f"{EVENT_MAX_PUBLISH_DAYS_BEFORE}")
    days = config.get("durationDays")
    if not (isinstance(days, int) and 1 <= days <= EVENT_MAX_DAYS):
        found.append(f"{where('durationDays')} {days!r}: a whole number of days from 1 to {EVENT_MAX_DAYS}")


def check_in_app_event(root=EVENT_DIR):
    """Checks the in-app event's texts, settings and media; returns the problems found and adds
    them to problems. scripts/testflight.py event calls this before it writes anything."""
    found = []
    root = pathlib.Path(root)
    if not root.is_dir():
        return found
    locales = event_locales(root)
    if not locales:
        found.append("app-store/in-app-event: no locale folders")
    print("app-store/in-app-event")
    try:
        config = json.loads((root / EVENT_CONFIG).read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        found.append(f"app-store/in-app-event/{EVENT_CONFIG}: {error}")
        config = None
    if config is not None:
        check_event_config(config, locales, found)
    for locale in locales:
        folder = root / locale
        print(f"app-store/in-app-event/{locale}")
        for name, limit in EVENT_LIMITS.items():
            path = folder / name
            where = f"app-store/in-app-event/{locale}/{name}"
            if not path.is_file():
                found.append(f"{where}: missing")
                continue
            text = read(path)
            flag = "" if 0 < len(text) <= limit else "  <-- over the limit" if text else "  <-- empty"
            print(f"  {name:24} {len(text):5d} / {limit}{flag}")
            if flag:
                found.append(f"{where}: {len(text)} characters (limit {limit})")
            if "\n" in text:
                found.append(f"{where}: more than one line")
            if UNIT_WORDS.search(text):
                found.append(f"{where}: calls the paid unit a credit or token")
            if PRICE.search(text):
                found.append(f"{where}: names a price, which App Review rejects in event metadata")
        media = event_media(root, locale)
        for name, (ratio, smallest, largest) in EVENT_MEDIA.items():
            where = f"app-store/in-app-event/{locale}/{name}"
            if name not in media:
                found.append(f"{where}: missing")
                continue
            info = png_info(media[name])
            source = "" if media[name].parent.name == locale else f" ({media[name].parent.name}'s)"
            if info is None:
                found.append(f"{where}: not a PNG")
                continue
            width, height, alpha = info
            print(f"  {name:24} {width}x{height}{source}")
            if not (smallest[0] <= width <= largest[0] and smallest[1] <= height <= largest[1]
                    and width * ratio[1] == height * ratio[0]):
                found.append(f"{where}: {width}x{height}; it takes {ratio[0]}:{ratio[1]} from "
                             f"{smallest[0]}x{smallest[1]} to {largest[0]}x{largest[1]}")
            if alpha:
                found.append(f"{where}: has an alpha channel")
    problems.extend(found)
    return found


def main():
    check()
    check_in_app_event()
    if problems:
        print("\nProblems:\n" + "\n".join(f"- {p}" for p in problems))
        return 1
    print("\nAll store texts are within the limits.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
