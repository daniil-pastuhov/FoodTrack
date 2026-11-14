#!/usr/bin/env python3
"""Checks the store texts in store-listing/ against the stores' limits and the listing rules.

    python3 scripts/check_store_listing.py          # prints every count, exits 1 on a problem

App Store (store-listing/app-store/<locale>/): name and subtitle at most 30 characters, keywords
at most 100, promotional text at most 170, description at most 4000; the App Review notes
(store-listing/app-store/review-notes.txt, one file for every language) at most 4000. Keywords are
comma-separated with no space after a comma, and no keyword word repeats a word of the same
locale's name or subtitle (or its English plural/singular), since Apple indexes those already.
Another English App Store folder (en-AU, en-CA) may hold only some files: the rest are en-GB's,
as scripts/testflight.py sends them, and the keyword rules check the name and subtitle it
inherits. en-AU's keywords also may not repeat en-GB's keywords, name or subtitle: Apple's
localisation table lists English (U.K.) as an additional language of the Australian storefront,
so both are assumed to be searched there and a repeat wastes en-AU's field.
Google Play (store-listing/google-play/<locale>/): title at most 30, short description at most
80, full description at most 4000; no "free", "best", "#1", "top" or emoji in the title.
Every text: never call the paid unit a credit or token (CLAUDE.md, "Recipe").
Characters are counted as Unicode code points, as both stores count them.
"""
import pathlib
import re
import sys
import unicodedata

ROOT = pathlib.Path(__file__).resolve().parent.parent / "store-listing"
APP_STORE_LIMITS = {"name.txt": 30, "subtitle.txt": 30, "keywords.txt": 100,
                    "promotional_text.txt": 170, "description.txt": 4000}
PLAY_LIMITS = {"title.txt": 30, "short-description.txt": 80, "full-description.txt": 4000}
REVIEW_NOTES = ("review-notes.txt", 4000)  # in store-listing/app-store/, not per locale
# An English App Store folder other than en-GB takes the en-GB file of every text it doesn't have
# (scripts/testflight.py, listing_files). play_listing.py has no such fallback.
FALLBACK_LOCALE = "en-GB"
# Locale -> the localization its storefront also offers (Apple's "App Store localizations" table:
# Australia, default English (Australia), additional English (U.K.); Canada's additional language
# is French (Canada), so en-CA has no entry). Its keywords may not repeat that one's keywords,
# name or subtitle.
ALSO_SHOWN_WITH = {"en-AU": "en-GB"}
# A locale folder's name (en-GB, ru, no, zh-Hans), which iap-review/ and in-app-event/ are not.
LOCALE_FOLDER = re.compile(r"[a-z]{2,3}(-[A-Za-z]{2,4})?")
PLAY_TITLE_WORDS = {"free", "best", "top", "#1", "gratis", "бесплатно", "лучший"}
UNIT_WORDS = re.compile(r"\b(credits?|tokens?|кредит\w*|токен\w*|kreditt\w*)\b", re.IGNORECASE)

problems = []


def words(text):
    return {w for w in re.findall(r"[^\W_]+", text.casefold())}


def read(path):
    return path.read_text(encoding="utf-8").strip()


def fallback_folder(folder, store):
    """The en-GB folder another English App Store folder inherits missing files from, or None."""
    if store != "app-store" or not folder.name.startswith("en-") or folder.name == FALLBACK_LOCALE:
        return None
    return folder.parent / FALLBACK_LOCALE


def check_lengths(folder, limits, inherit_from=None):
    texts = {}
    for name, limit in limits.items():
        path = folder / name
        if not path.exists() and inherit_from is not None and (inherit_from / name).exists():
            # Checked in its own folder; counted here so the output shows the whole listing.
            texts[name] = read(inherit_from / name)
            print(f"  {name:24} {len(texts[name]):5d} / {limit}  (from {inherit_from.name})")
            continue
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
    other = folder.parent / ALSO_SHOWN_WITH[folder.name] if folder.name in ALSO_SHOWN_WITH else None
    shown = set()
    if other is not None:
        shown = words(" ".join(read(other / name) for name in ("name.txt", "subtitle.txt", "keywords.txt")
                               if (other / name).exists()))
    for word in sorted(words(keywords)):
        variants = {word, word + "s", word + "es", word[:-1] if word.endswith("s") else word}
        if variants & indexed:
            problems.append(f"{where}: '{word}' repeats the name or subtitle")
        elif variants & shown:
            problems.append(f"{where}: '{word}' repeats {other.name}'s keywords, name or subtitle, "
                            "which the same storefront also searches")


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
        store_root = ROOT / store
        folders = sorted(p for p in store_root.iterdir() if p.is_dir()
                         and LOCALE_FOLDER.fullmatch(p.name)
                         and any((p / name).exists() for name in limits)) if store_root.is_dir() else []
        if not folders:
            problems.append(f"{store}: no locale folders")
        for folder in folders:
            print(f"{store}/{folder.name}")
            texts = check_lengths(folder, limits, fallback_folder(folder, store))
            if store == "app-store":
                check_keywords(folder, texts)
            else:
                check_play_title(folder, texts)
        if store == "app-store":
            check_review_notes()
    return list(problems)


def main():
    check()
    if problems:
        print("\nProblems:\n" + "\n".join(f"- {p}" for p in problems))
        return 1
    print("\nAll store texts are within the limits.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
