#!/usr/bin/env python3
"""Brings the Google Play store listing in line with store-listing/google-play/.

Dry run by default: it opens an edit, reads what Play has, prints every difference and discards
the edit. Nothing is written without --apply:

    pip install google-api-python-client google-auth-httplib2
    PLAY_SERVICE_ACCOUNT_JSON="$(cat key.json)" python3 scripts/play_listing.py            # texts
    PLAY_SERVICE_ACCOUNT_JSON="$(cat key.json)" python3 scripts/play_listing.py --images   # + images
    PLAY_SERVICE_ACCOUNT_JSON="$(cat key.json)" python3 scripts/play_listing.py --images --validate-only
    PLAY_SERVICE_ACCOUNT_JSON="$(cat key.json)" python3 scripts/play_listing.py --images --apply

CI runs the last one when a release PR merges, after the build is promoted to closed testing
(.github/workflows/ci.yml, job play-listing), so the listing changes with the release that has
what it describes. Each commit goes through Play's review like a change made in Play Console.

For each locale folder (a folder with title.txt; Play's language codes, e.g. en-GB, en-US):
- title.txt, short-description.txt, full-description.txt: the listing's title, short and full
  description. A listing Play doesn't have yet (en-US) is created; an existing one is patched
  with only the fields that differ, so its promo video is kept.
- with --images: phone-screenshots/ (in file-number order, at most 8) and feature-graphic.png;
  the shared icon-512.png goes to the default language. Images are compared by the SHA-256 that
  Play returns, so unchanged ones are not uploaded again. Play has no call to reorder images:
  the screenshots after the first one that differs are deleted and uploaded again in order. A
  locale without image files is left as it is, and Play shows the default language's.

scripts/check_store_listing.py runs first; text over a limit stops the run before Play is
contacted. Authenticates exactly like scripts/play_upload.py (its `publisher()`).
"""
import argparse
import contextlib
import hashlib
import io
import pathlib
import re
import sys

SCRIPTS = pathlib.Path(__file__).resolve().parent
LISTING_DIR = SCRIPTS.parent / "store-listing" / "google-play"
TEXT_FIELDS = {"title.txt": "title", "short-description.txt": "shortDescription",
               "full-description.txt": "fullDescription"}
PHONE_SCREENSHOT_LIMIT = 8
IMAGE_SUFFIXES = (".png", ".jpg", ".jpeg")
ICON_FILE = "icon-512.png"


class ListingError(Exception):
    """The repo's listing can't be applied as it is."""


def listing_locales(root=LISTING_DIR):
    return sorted(p.parent.name for p in pathlib.Path(root).glob("*/title.txt"))


def read_texts(folder):
    texts = {}
    for name, field in TEXT_FIELDS.items():
        path = pathlib.Path(folder) / name
        if path.exists():
            texts[field] = path.read_text(encoding="utf-8").strip()
    return texts


def text_changes(current, wanted):
    """{field: (now, wanted)} for each wanted field that differs from the listing Play has."""
    current = current or {}
    return {k: ((current.get(k) or "").strip(), v) for k, v in wanted.items()
            if (current.get(k) or "").strip() != v}


def describe_change(field, old, new):
    if "\n" not in old + new and len(old) <= 200 and len(new) <= 200:
        return [f"    {field}: {old!r} -> {new!r}"]
    import difflib
    lines = [f"    {field}: {len(old)} -> {len(new)} characters"]
    diff = difflib.unified_diff(old.splitlines(), new.splitlines(), "Play", "repo", lineterm="", n=0)
    return lines + [f"      {line}" for line in list(diff)[2:]]


def image_number(path):
    match = re.match(r"(\d+)", path.stem)
    return (int(match.group(1)), path.name) if match else (float("inf"), path.name)


def local_image(path):
    data = pathlib.Path(path).read_bytes()
    return {"name": pathlib.Path(path).name, "path": pathlib.Path(path),
            "sha256": hashlib.sha256(data).hexdigest(), "sha1": hashlib.sha1(data).hexdigest()}


def local_screenshots(folder):
    files = sorted((p for p in pathlib.Path(folder).iterdir()
                    if p.is_file() and p.suffix.lower() in IMAGE_SUFFIXES), key=image_number)
    if len(files) > PHONE_SCREENSHOT_LIMIT:
        raise ListingError(f"{folder} has {len(files)} screenshots; Play takes at most "
                           f"{PHONE_SCREENSHOT_LIMIT} phone screenshots per language")
    return [local_image(p) for p in files]


def same_image(local, remote):
    """Whether a Play image (its sha256, or sha1 if that is all it has) is the local file."""
    if remote.get("sha256"):
        return remote["sha256"].lower() == local["sha256"]
    return bool(remote.get("sha1")) and remote["sha1"].lower() == local["sha1"]


def plan_images(local, remote):
    """(kept, delete, upload): Play shows images in upload order and can't reorder them, so the
    remote images matching the local ones from the start are kept, and every later remote image
    is deleted and every later local one uploaded."""
    kept = 0
    while kept < min(len(local), len(remote)) and same_image(local[kept], remote[kept]):
        kept += 1
    return kept, remote[kept:], local[kept:]


def image_targets(root, locales, default_language):
    """[(language, imageType, [local images])] for every image the repo has."""
    targets = []
    for locale in locales:
        folder = pathlib.Path(root) / locale
        if (folder / "phone-screenshots").is_dir():
            shots = local_screenshots(folder / "phone-screenshots")
            if shots:
                targets.append((locale, "phoneScreenshots", shots))
        if (folder / "feature-graphic.png").is_file():
            targets.append((locale, "featureGraphic", [local_image(folder / "feature-graphic.png")]))
    if (pathlib.Path(root) / ICON_FILE).is_file() and default_language:
        targets.append((default_language, "icon", [local_image(pathlib.Path(root) / ICON_FILE)]))
    return targets


def check_listing_texts():
    sys.path.insert(0, str(SCRIPTS))
    import check_store_listing
    with contextlib.redirect_stdout(io.StringIO()):
        problems = check_store_listing.check(stores=("google-play",))
    if problems:
        raise ListingError("scripts/check_store_listing.py found problems in "
                           "store-listing/google-play/:\n" + "\n".join(f"- {p}" for p in problems))


def sync_texts(edits, package, edit_id, root, locales, write):
    """Creates or patches each locale's listing. Returns whether anything differed."""
    listings = {item.get("language"): item for item in edits.listings().list(
        packageName=package, editId=edit_id).execute().get("listings", [])}
    print(f"Texts: {', '.join(locales)} in the repo; Play has {', '.join(sorted(listings)) or 'none'}")
    changed = False
    for locale in locales:
        wanted = read_texts(pathlib.Path(root) / locale)
        if locale not in listings:
            changed = True
            print(f"  {locale}: {'creating' if write else 'would create'} the listing")
            for field, text in wanted.items():
                print(*describe_change(field, "", text), sep="\n")
            if write:
                edits.listings().update(packageName=package, editId=edit_id, language=locale,
                                        body={"language": locale, **wanted}).execute()
            continue
        changes = text_changes(listings[locale], wanted)
        if not changes:
            print(f"  {locale}: up to date")
            continue
        changed = True
        print(f"  {locale}: {'updating' if write else 'would update'}")
        for field, (old, new) in changes.items():
            print(*describe_change(field, old, new), sep="\n")
        if write:
            edits.listings().patch(packageName=package, editId=edit_id, language=locale,
                                   body={"language": locale,
                                         **{k: new for k, (_, new) in changes.items()}}).execute()
    for locale in sorted(set(listings) - set(locales)):
        print(f"  {locale}: no folder in the repo; left as it is")
    return changed


def sync_images(edits, package, edit_id, targets, write, upload_media):
    """Syncs each (language, imageType) the repo has images for. Returns whether anything
    differed."""
    changed = False
    print("Images:")
    for language, image_type, local in targets:
        remote = edits.images().list(packageName=package, editId=edit_id, language=language,
                                     imageType=image_type).execute().get("images", []) or []
        kept, delete, upload = plan_images(local, remote)
        where = f"  {language} {image_type}: {len(local)} in the repo, {len(remote)} on Play"
        if not delete and not upload:
            print(f"{where}; up to date")
            continue
        changed = True
        print(where)
        if kept:
            print("    unchanged: " + ", ".join(i["name"] for i in local[:kept]))
        if delete:
            print(f"    {'deleting' if write else 'would delete'} {len(delete)} from position "
                  f"{kept + 1}: " + ", ".join((r.get("sha256") or r.get("sha1") or r.get("id") or "?")[:12]
                                             for r in delete))
        if upload:
            print(f"    {'uploading' if write else 'would upload'}: " + ", ".join(i["name"] for i in upload))
        if not write:
            continue
        for image in delete:
            edits.images().delete(packageName=package, editId=edit_id, language=language,
                                  imageType=image_type, imageId=image["id"]).execute()
        for image in upload:
            edits.images().upload(packageName=package, editId=edit_id, language=language,
                                  imageType=image_type, media_body=upload_media(image["path"])).execute()
    return changed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--apply", action="store_true", help="commit the changes; without it nothing is written")
    parser.add_argument("--validate-only", action="store_true",
                        help="make the changes in an edit, have Play validate it, then discard it")
    parser.add_argument("--images", action="store_true",
                        help="also sync phone screenshots, feature graphics and the icon")
    parser.add_argument("--locale", action="append", metavar="LANGUAGE",
                        help="only this locale folder (repeatable); default all")
    args = parser.parse_args()
    if args.apply and args.validate_only:
        parser.error("pass at most one of --apply and --validate-only")
    try:
        check_listing_texts()
        locales = listing_locales()
        if args.locale:
            unknown = sorted(set(args.locale) - set(locales))
            if unknown:
                parser.error(f"no folder in store-listing/google-play/ for {', '.join(unknown)}")
            locales = [loc for loc in locales if loc in args.locale]
        if not locales:
            raise ListingError(f"No locale folders in {LISTING_DIR}")
    except ListingError as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1

    sys.path.insert(0, str(SCRIPTS))
    from play_upload import PACKAGE_NAME, publisher
    from googleapiclient.errors import HttpError
    from googleapiclient.http import MediaFileUpload

    def media(path):
        return MediaFileUpload(str(path), mimetype="image/png" if path.suffix.lower() == ".png"
                               else "image/jpeg")

    api = publisher(timeout=300)
    if api is None:
        print("PLAY_SERVICE_ACCOUNT_JSON is not set", file=sys.stderr)
        return 2
    write = args.apply or args.validate_only
    mode = "APPLY" if args.apply else "VALIDATE ONLY (edit discarded)" if args.validate_only \
        else "DRY RUN (pass --apply to write)"
    print(f"{mode}: {PACKAGE_NAME}{'' if args.images else ' (texts only; --images for images)'}")
    edits = api.edits()
    edit_id = edits.insert(packageName=PACKAGE_NAME, body={}).execute()["id"]
    try:
        default_language = edits.details().get(packageName=PACKAGE_NAME, editId=edit_id) \
            .execute().get("defaultLanguage")
        print(f"Default language: {default_language or '?'}")
        changed = sync_texts(edits, PACKAGE_NAME, edit_id, LISTING_DIR, locales, write)
        if args.images:
            # The icon belongs to the default language, so only when that locale is included.
            icon_language = default_language if default_language in locales else None
            targets = image_targets(LISTING_DIR, locales, icon_language)
            changed |= sync_images(edits, PACKAGE_NAME, edit_id, targets, write, media)
        if args.apply and changed:
            edits.commit(packageName=PACKAGE_NAME, editId=edit_id).execute()
            print("Committed; Play reviews the listing change")
            return 0
        if args.validate_only and changed:
            edits.validate(packageName=PACKAGE_NAME, editId=edit_id).execute()
            print("Play validated the changes")
        edits.delete(packageName=PACKAGE_NAME, editId=edit_id).execute()
        print("Edit discarded; nothing was written" if changed else "The listing is up to date")
        return 0
    except (HttpError, ListingError) as error:
        edits.delete(packageName=PACKAGE_NAME, editId=edit_id).execute()
        print(f"::error::The Play listing was not changed (edit discarded): {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
