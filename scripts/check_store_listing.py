#!/usr/bin/env python3
"""Checks the store texts in store-listing/ against the stores' limits and the listing rules.

    python3 scripts/check_store_listing.py          # prints every count, exits 1 on a problem

App Store (store-listing/app-store/<locale>/): name and subtitle at most 30 characters, keywords
at most 100, promotional text at most 170, description at most 4000. Keywords are
comma-separated with no space after a comma, and no keyword word repeats a word of the same
locale's name or subtitle (or its English plural/singular), since Apple indexes those already.
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


def main():
    for store, limits in (("app-store", APP_STORE_LIMITS), ("google-play", PLAY_LIMITS)):
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
    if problems:
        print("\nProblems:\n" + "\n".join(f"- {p}" for p in problems))
        return 1
    print("\nAll store texts are within the limits.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
