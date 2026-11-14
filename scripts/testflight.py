#!/usr/bin/env python3
"""Hands iOS builds to TestFlight testers and App Review through the App Store Connect API.

Used by .github/workflows/ci.yml as part of the release flow (see "Releasing" in README.md):

    # After scripts/ios_release.sh uploaded build N of a release branch: wait for processing,
    # set the localized "What to Test" text and give it to the internal group.
    python3 scripts/testflight.py notes --version 1.3.0 --build N --notes-dir release-notes/v1.3.0

    # After the release PR is merged: put the newest build of that version on its App Store
    # version, with the notes as "What's New" and the listing from store-listing/app-store/
    # (with --screenshots, its screenshot sets too), and submit it for App Review.
    python3 scripts/testflight.py appstore --version 1.3.0 --notes-dir release-notes/v1.3.0 \\
        --screenshots

    # Before an archive on CI: revoke the Apple Development certificates earlier runs left behind.
    python3 scripts/testflight.py revoke-ci-certs

    # Read-only overview of the app's builds and groups.
    python3 scripts/testflight.py status

    # The store listing in store-listing/app-store/ against the version in preparation: dry run
    # by default (prints what differs), --apply writes. `appstore` applies the texts by itself,
    # and the screenshots too with --screenshots, before it submits.
    python3 scripts/testflight.py listing [--apply]
    python3 scripts/testflight.py screenshots [--apply]

    # Read-only: the raw screenshot sets and App Asset Library images and placements of that
    # version, to check the folders' mapping against the account (GET requests only).
    python3 scripts/testflight.py screenshots --inspect [--dump FILE]

Reads ASC_KEY_ID, ASC_ISSUER_ID and ASC_KEY_PATH (the .p8 file), the same as ios_release.sh.
Needs the `cryptography` package for the ES256 token signature.
"""
import argparse
import base64
import contextlib
import datetime
import difflib
import hashlib
import io
import json
import os
import pathlib
import re
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BUNDLE_ID = "com.foodbest.ios"
API = "https://api.appstoreconnect.apple.com/v1"
# Release notes are stored under Play's locale codes; App Store Connect names the same
# languages as in store-listing/app-store/.
ASC_LOCALES = {"en-GB": "en-GB", "ru-RU": "ru", "no-NO": "no"}
# One folder per App Store Connect locale, holding promotional_text.txt among others.
LISTING_DIR = pathlib.Path(__file__).resolve().parent.parent / "store-listing" / "app-store"
# The other English localizations (en-US, en-AU, en-CA) take the en-GB release notes, as the notes
# are written once per language, and the en-GB promotional text when they have no folder of their
# own (en-US has one). Apple asks for What's New in every localization of an update.
FALLBACK_LOCALE = "en-GB"


def fallback(locale):
    return FALLBACK_LOCALE if locale.startswith("en-") and locale != FALLBACK_LOCALE else None
# "What to Test" is limited to 4000 characters; the notes are much shorter (Play allows 500).
WHATS_NEW_LIMIT = 4000
# App Store version states in which the version and its metadata can still be edited.
EDITABLE_STATES = {"PREPARE_FOR_SUBMISSION", "DEVELOPER_REJECTED", "REJECTED",
                   "METADATA_REJECTED", "INVALID_BINARY"}


class AscError(Exception):
    def __init__(self, status, body):
        super().__init__(f"HTTP {status}: {body}")
        self.status = status
        self.body = body


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def make_token() -> str:
    # Imported here so the listing code and its tests (scripts/tests/) load without the package.
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

    key_id = os.environ.get("ASC_KEY_ID")
    issuer = os.environ.get("ASC_ISSUER_ID")
    key_path = os.environ.get("ASC_KEY_PATH")
    if not (key_id and issuer and key_path):
        sys.exit("Set ASC_KEY_ID, ASC_ISSUER_ID and ASC_KEY_PATH")
    key = serialization.load_pem_private_key(pathlib.Path(key_path).expanduser().read_bytes(), None)
    now = int(time.time())
    header = b64url(json.dumps({"alg": "ES256", "kid": key_id, "typ": "JWT"}).encode())
    payload = b64url(json.dumps(
        {"iss": issuer, "iat": now, "exp": now + 15 * 60, "aud": "appstoreconnect-v1"}).encode())
    signing_input = f"{header}.{payload}".encode()
    r, s = decode_dss_signature(key.sign(signing_input, ec.ECDSA(hashes.SHA256())))
    return f"{header}.{payload}.{b64url(r.to_bytes(32, 'big') + s.to_bytes(32, 'big'))}"


class Asc:
    def __init__(self):
        self.token = make_token()
        self.issued = time.time()

    def call(self, method, path, params=None, body=None):
        # Tokens live 15 minutes and a processing wait can take longer.
        if time.time() - self.issued > 10 * 60:
            self.token, self.issued = make_token(), time.time()
        url = API + path
        if params:
            url += "?" + urllib.parse.urlencode(params)
        request = urllib.request.Request(
            url, method=method, data=json.dumps(body).encode() if body is not None else None,
            headers={"Authorization": f"Bearer {self.token}", "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                data = response.read()
                return json.loads(data) if data else {}
        except urllib.error.HTTPError as error:
            raise AscError(error.code, error.read().decode(errors="replace")) from None

    @staticmethod
    def upload_part(operation, data):
        """Sends one part of a screenshot as an upload operation says. The URL is presigned: it
        takes no token."""
        request = urllib.request.Request(
            operation["url"], method=operation.get("method") or "PUT", data=data,
            headers={h["name"]: h["value"] for h in operation.get("requestHeaders") or []})
        try:
            with urllib.request.urlopen(request, timeout=300) as response:
                response.read()
        except urllib.error.HTTPError as error:
            raise AscError(error.code, error.read().decode(errors="replace")) from None

    def app_id(self):
        apps = self.call("GET", "/apps", {"filter[bundleId]": BUNDLE_ID})["data"]
        if not apps:
            sys.exit(f"No App Store Connect app with bundle ID {BUNDLE_ID}")
        return apps[0]["id"]

    def builds(self, app_id, version, build=None):
        params = {"filter[app]": app_id, "filter[preReleaseVersion.version]": version,
                  "sort": "-uploadedDate", "limit": 50}
        if build:
            params["filter[version]"] = build
        return self.call("GET", "/builds", params)["data"]

    def groups(self, app_id):
        return self.call("GET", "/betaGroups", {"filter[app]": app_id, "limit": 50})["data"]


def read_notes(notes_dir):
    notes = {}
    for path in sorted(pathlib.Path(notes_dir).glob("*.txt")):
        locale = ASC_LOCALES.get(path.stem)
        if not locale:
            print(f"Skipping {path.name}: no App Store Connect locale for {path.stem}")
            continue
        text = path.read_text(encoding="utf-8").strip()
        if len(text) > WHATS_NEW_LIMIT:
            sys.exit(f"{path} is longer than {WHATS_NEW_LIMIT} characters")
        notes[locale] = text
    if not notes:
        sys.exit(f"No release notes in {notes_dir}")
    return notes


def wait_for_build(asc, app_id, version, build_number, timeout_minutes):
    """Waits until App Store Connect lists the build and has finished processing it."""
    deadline = time.time() + timeout_minutes * 60
    while True:
        found = asc.builds(app_id, version, build_number)
        state = found[0]["attributes"]["processingState"] if found else "NOT LISTED YET"
        if state == "VALID":
            return found[0]
        if state in ("FAILED", "INVALID"):
            sys.exit(f"Build {build_number} of {version} is {state} in App Store Connect")
        if time.time() > deadline:
            sys.exit(f"Build {build_number} of {version} still {state} after {timeout_minutes} min")
        print(f"Build {build_number} of {version}: {state}; checking again in 30 s")
        time.sleep(30)


def set_whats_new(asc, build_id, notes):
    existing = {loc["attributes"]["locale"]: loc["id"] for loc in asc.call(
        "GET", f"/builds/{build_id}/betaBuildLocalizations", {"limit": 50})["data"]}
    for locale, text in notes.items():
        if locale in existing:
            asc.call("PATCH", f"/betaBuildLocalizations/{existing[locale]}", body={"data": {
                "type": "betaBuildLocalizations", "id": existing[locale],
                "attributes": {"whatsNew": text}}})
        else:
            asc.call("POST", "/betaBuildLocalizations", body={"data": {
                "type": "betaBuildLocalizations",
                "attributes": {"locale": locale, "whatsNew": text},
                "relationships": {"build": {"data": {"type": "builds", "id": build_id}}}}})
        print(f"Set What to Test ({locale})")


def add_to_group(asc, group, build_id):
    asc.call("POST", f"/betaGroups/{group['id']}/relationships/builds",
             body={"data": [{"type": "builds", "id": build_id}]})
    print(f"Added the build to '{group['attributes']['name']}'")


def cmd_notes(args):
    asc = Asc()
    app_id = asc.app_id()
    notes = read_notes(args.notes_dir)
    build = wait_for_build(asc, app_id, args.version, args.build, args.timeout)
    set_whats_new(asc, build["id"], notes)
    internal = [g for g in asc.groups(app_id) if g["attributes"]["isInternalGroup"]]
    if not internal:
        print("::warning::No internal TestFlight group; create one in App Store Connect → "
              "TestFlight → Internal Testing so release candidates reach testers")
    for group in internal:
        # A group with access to all builds gets every build by itself.
        if not group["attributes"].get("hasAccessToAllBuilds"):
            add_to_group(asc, group, build["id"])
    return 0


def newest_valid_build(asc, app_id, version):
    valid = [b for b in asc.builds(app_id, version)
             if b["attributes"]["processingState"] == "VALID"]
    if not valid:
        sys.exit(f"No processed build of {version} in App Store Connect")
    print(f"Using build {valid[0]['attributes']['version']} of {version}")
    return valid[0]


def version_state(version):
    attrs = version["attributes"]
    return attrs.get("appVersionState") or attrs.get("appStoreState")


def submit_for_review(asc, app_id, version, release_type):
    try:
        # An open submission (not sent yet, or sent back by App Review) is reused: an app can
        # have only one.
        open_submissions = asc.call("GET", "/reviewSubmissions", {
            "filter[app]": app_id, "filter[platform]": "IOS",
            "filter[state]": "READY_FOR_REVIEW,UNRESOLVED_ISSUES"})["data"]
        if open_submissions:
            submission = open_submissions[0]
        else:
            submission = asc.call("POST", "/reviewSubmissions", body={"data": {
                "type": "reviewSubmissions", "attributes": {"platform": "IOS"},
                "relationships": {"app": {"data": {"type": "apps", "id": app_id}}}}})["data"]
        items = asc.call("GET", f"/reviewSubmissions/{submission['id']}/items",
                         {"include": "appStoreVersion"})["data"]
        if not any((item["relationships"].get("appStoreVersion", {}).get("data") or {}).get("id")
                   == version["id"] for item in items):
            asc.call("POST", "/reviewSubmissionItems", body={"data": {
                "type": "reviewSubmissionItems", "relationships": {
                    "reviewSubmission": {"data": {"type": "reviewSubmissions",
                                                  "id": submission["id"]}},
                    "appStoreVersion": {"data": {"type": "appStoreVersions",
                                                 "id": version["id"]}}}}})
        asc.call("PATCH", f"/reviewSubmissions/{submission['id']}", body={"data": {
            "type": "reviewSubmissions", "id": submission["id"],
            "attributes": {"submitted": True}}})
    except AscError as error:
        if error.status in (409, 422):
            sys.exit("App Store Connect refused the App Review submission. Check that the version "
                     "page in App Store Connect has no missing items (App Privacy, App Review "
                     f"contact, screenshots), then re-run the job.\n{error.body}")
        raise
    when = "once Apple approves it" if release_type == "AFTER_APPROVAL" else \
        "when you release it in App Store Connect after approval"
    print(f"Submitted for App Review; it goes live {when}")
    return 0


# --- Store listing: store-listing/app-store/<locale>/ onto the version in preparation ----------
#
# Each locale folder's texts go to two resources: the version's localization (keywords,
# description, promotional text) and the app info's (name, subtitle), which App Store Connect
# only lets change while a version is in preparation. A field is written only when its file
# exists and differs from what App Store Connect has, so the URLs and anything else the repo has
# no file for are never touched.

class ListingError(Exception):
    """The repo's listing can't be applied as it is (a limit, a missing resource)."""


# file name -> (resource, attribute). "version" is appStoreVersionLocalizations, "info"
# appInfoLocalizations.
LISTING_FIELDS = {
    "name.txt": ("info", "name"),
    "subtitle.txt": ("info", "subtitle"),
    "keywords.txt": ("version", "keywords"),
    "promotional_text.txt": ("version", "promotionalText"),
    "description.txt": ("version", "description"),
}
# Copied from the primary language's localization when a localization is created, so a new one
# (en-US) is not left without them: App Review needs a support URL in every localization.
COPIED_ON_CREATE = {"version": ("supportUrl", "marketingUrl"),
                    "info": ("privacyPolicyUrl", "privacyChoicesUrl")}
RESOURCE_TYPES = {"version": "appStoreVersionLocalizations", "info": "appInfoLocalizations"}

# Screenshot folder -> screenshotDisplayType (developer.apple.com/documentation/
# appstoreconnectapi/screenshotdisplaytype). The enum names display types by their older
# sizes: APP_IPHONE_67 is the set App Store Connect now calls "iPhone with Dynamic Island (large
# display)" (6.9", 1320×2868), APP_IPHONE_61 the medium one (6.3", 1206×2622), and
# APP_IPAD_PRO_3GEN_129 the iPad 13" one (2064×2752). `screenshots` prints each set's image
# sizes, so a dry run against a version with hand-uploaded screenshots shows the match.
SCREENSHOT_SETS = {
    "screenshots": "APP_IPHONE_67",
    "medium-screenshots": "APP_IPHONE_61",
    "ipad-screenshots": "APP_IPAD_PRO_3GEN_129",
}
# The set-based API has no iPhone Duo display type (only the newer App Asset Library has an
# IPHONE_DUO display class), so these folders are left for App Store Connect's website.
UNSYNCED_SCREENSHOT_FOLDERS = ("duo-outer-screenshots", "duo-inner-screenshots")
SCREENSHOT_LIMIT = 10  # per display type and localization
SCREENSHOT_SUFFIXES = (".png", ".jpg", ".jpeg")
SCREENSHOT_WAIT_SECONDS = 600


def listing_locales(root=LISTING_DIR):
    """The locale folders under root: those with a name.txt (iap-review/ has none)."""
    return sorted(p.parent.name for p in pathlib.Path(root).glob("*/name.txt"))


def read_listing(folder):
    """{"version": {attribute: text}, "info": {attribute: text}} for the files folder has."""
    texts = {"version": {}, "info": {}}
    for name, (resource, attribute) in LISTING_FIELDS.items():
        path = pathlib.Path(folder) / name
        if path.exists():
            texts[resource][attribute] = path.read_text(encoding="utf-8").strip()
    return texts


def listing_source(locale, folders):
    """The folder whose texts a localization takes: its own, or en-GB's for another English
    localization without one (en-AU, en-CA). None when the repo has nothing for it."""
    if locale in folders:
        return locale
    return fallback(locale) if fallback(locale) in folders else None


def field_changes(current, wanted):
    """{attribute: (now, wanted)} for each wanted attribute that differs from current."""
    current = current or {}
    return {k: ((current.get(k) or "").strip(), v) for k, v in wanted.items()
            if (current.get(k) or "").strip() != v}


def describe_change(attribute, old, new):
    """Lines that show one field's change; a long text as a line diff."""
    if "\n" not in old + new and len(old) <= 200 and len(new) <= 200:
        return [f"    {attribute}: {old!r} -> {new!r}"]
    lines = [f"    {attribute}: {len(old)} -> {len(new)} characters"]
    diff = difflib.unified_diff(old.splitlines(), new.splitlines(), "App Store Connect", "repo",
                                lineterm="", n=0)
    lines += [f"      {line}" for line in list(diff)[2:]]
    return lines


def check_listing_texts():
    """scripts/check_store_listing.py as a gate: over-limit text never reaches the store."""
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
    import check_store_listing
    with contextlib.redirect_stdout(io.StringIO()):
        problems = check_store_listing.check(stores=("app-store",))
    if problems:
        raise ListingError("scripts/check_store_listing.py found problems in "
                           "store-listing/app-store/:\n" + "\n".join(f"- {p}" for p in problems))


def localizations_by_locale(asc, path):
    return {loc["attributes"]["locale"]: loc
            for loc in asc.call("GET", path, {"limit": 50}).get("data", [])}


def sync_resource(asc, resource, locale, existing, wanted, parent, primary, apply):
    """Creates or updates one localization so it has the wanted texts. Returns the
    localization's id (None for one a dry run would create) and whether anything differs."""
    kind = RESOURCE_TYPES[resource]
    label = "version" if resource == "version" else "app info"
    if existing is None:
        if resource == "info" and "name" not in wanted:
            raise ListingError(f"{locale}: no name.txt, and App Store Connect needs a name to add "
                               "the localization")
        copied = {k: (primary or {}).get("attributes", {}).get(k) for k in COPIED_ON_CREATE[resource]}
        copied = {k: v for k, v in copied.items() if v}
        print(f"  {label} localization {locale}: {'creating' if apply else 'would create'}")
        for attribute, text in wanted.items():
            print(*describe_change(attribute, "", text), sep="\n")
        for attribute, value in copied.items():
            print(f"    {attribute}: {value!r} (copied from {FALLBACK_LOCALE})")
        if not apply:
            return None, True
        relationship = "appStoreVersion" if resource == "version" else "appInfo"
        created = asc.call("POST", f"/{kind}", body={"data": {
            "type": kind, "attributes": {"locale": locale, **wanted, **copied},
            "relationships": {relationship: {"data": parent}}}})["data"]
        return created["id"], True
    changes = field_changes(existing.get("attributes"), wanted)
    if not changes:
        print(f"  {label} localization {locale}: up to date")
        return existing["id"], False
    print(f"  {label} localization {locale}: {'updating' if apply else 'would update'}")
    for attribute, (old, new) in changes.items():
        print(*describe_change(attribute, old, new), sep="\n")
    if apply:
        asc.call("PATCH", f"/{kind}/{existing['id']}", body={"data": {
            "type": kind, "id": existing["id"],
            "attributes": {k: new for k, (_, new) in changes.items()}}})
    return existing["id"], True


def sync_listing(asc, version_id, app_info_id, apply, root=LISTING_DIR):
    """Brings the version's and the app info's localizations in line with root's locale
    folders. Returns {locale: version localization id} as App Store Connect has them after it,
    and whether anything differed."""
    folders = listing_locales(root)
    if not folders:
        raise ListingError(f"No locale folders in {root}")
    version_locs = localizations_by_locale(
        asc, f"/appStoreVersions/{version_id}/appStoreVersionLocalizations")
    info_locs = localizations_by_locale(asc, f"/appInfos/{app_info_id}/appInfoLocalizations")
    # Every locale the repo has a folder for, and the other English ones App Store Connect has.
    locales = sorted(set(folders) | {loc for loc in set(version_locs) | set(info_locs)
                                     if listing_source(loc, folders)})
    print(f"Listing: {', '.join(folders)} in the repo; App Store Connect has "
          f"{', '.join(sorted(version_locs)) or 'no'} version and "
          f"{', '.join(sorted(info_locs)) or 'no'} app info localizations")
    result, changed = {}, False
    for locale in locales:
        source = listing_source(locale, folders)
        texts = read_listing(pathlib.Path(root) / source)
        if source != locale:
            print(f"  {locale} takes the {source} texts (no folder of its own)")
        loc_id, differs = sync_resource(
            asc, "version", locale, version_locs.get(locale), texts["version"],
            {"type": "appStoreVersions", "id": version_id}, version_locs.get(FALLBACK_LOCALE), apply)
        changed |= differs
        if loc_id:
            result[locale] = loc_id
        if apply and locale not in version_locs and locale not in info_locs:
            # Adding a language may add it to the app info as well; read again so it is
            # updated rather than created twice.
            info_locs = localizations_by_locale(asc, f"/appInfos/{app_info_id}/appInfoLocalizations")
        _, differs = sync_resource(
            asc, "info", locale, info_locs.get(locale), texts["info"],
            {"type": "appInfos", "id": app_info_id}, info_locs.get(FALLBACK_LOCALE), apply)
        changed |= differs
    for locale in sorted(set(version_locs) - set(locales)):
        print(f"  {locale}: no folder in the repo; left as it is")
        result[locale] = version_locs[locale]["id"]
    return result, changed


# --- Screenshots ---------------------------------------------------------------------------------

def screenshot_number(path):
    """Sort key: 2.png before 10.png; names without a number last, by name."""
    match = re.match(r"(\d+)", path.stem)
    return (int(match.group(1)), path.name) if match else (float("inf"), path.name)


def image_size(path):
    """(width, height) of a PNG, or None for another format."""
    with open(path, "rb") as f:
        head = f.read(24)
    if head[:8] == b"\x89PNG\r\n\x1a\n" and head[12:16] == b"IHDR":
        return struct.unpack(">II", head[16:24])
    return None


def local_screenshots(folder):
    """The folder's images in display order, each with its MD5 (the checksum App Store Connect
    keeps as sourceFileChecksum)."""
    files = sorted((p for p in pathlib.Path(folder).iterdir()
                    if p.is_file() and p.suffix.lower() in SCREENSHOT_SUFFIXES), key=screenshot_number)
    if len(files) > SCREENSHOT_LIMIT:
        raise ListingError(f"{folder} has {len(files)} screenshots; App Store Connect takes at "
                           f"most {SCREENSHOT_LIMIT} per display type")
    shots = []
    for path in files:
        data = path.read_bytes()
        shots.append({"name": path.name, "path": path, "size": len(data),
                      "md5": hashlib.md5(data).hexdigest(), "dimensions": image_size(path)})
    return shots


def remote_screenshot(item):
    attrs = item.get("attributes") or {}
    image = attrs.get("imageAsset") or {}
    return {"id": item["id"], "name": attrs.get("fileName") or "",
            "md5": (attrs.get("sourceFileChecksum") or "").lower(),
            "state": (attrs.get("assetDeliveryState") or {}).get("state"),
            "dimensions": (image.get("width"), image.get("height")) if image.get("width") else None}


def plan_screenshots(local, remote):
    """What turns the remote set into the local one: which remote screenshots stay (same MD5,
    preferring the same file name), which local files are uploaded, which remote ones are
    deleted, and whether the result then needs reordering. Screenshots that failed processing
    or were never uploaded are deleted and uploaded again."""
    usable = [r for r in remote if r["state"] in ("COMPLETE", "UPLOAD_COMPLETE", None) and r["md5"]]
    used, keep, upload = set(), {}, []
    for index, shot in enumerate(local):
        candidates = [r for r in usable if r["id"] not in used and r["md5"] == shot["md5"]]
        match = next((r for r in candidates if r["name"] == shot["name"]),
                     candidates[0] if candidates else None)
        if match:
            used.add(match["id"])
            keep[index] = match
        else:
            upload.append(index)
    delete = [r for r in remote if r["id"] not in used]
    # After deleting and uploading, the set holds the kept ones in their current order followed
    # by the uploads in local order.
    after = [i for i in sorted(keep, key=lambda i: [r["id"] for r in remote].index(keep[i]["id"]))]
    after += upload
    return {"keep": keep, "upload": upload, "delete": delete,
            "reorder": after != list(range(len(local)))}


def upload_screenshot(asc, set_id, shot):
    """Reserves, uploads and commits one screenshot (developer.apple.com/documentation/
    appstoreconnectapi/uploading-assets-to-app-store-connect). Returns its id."""
    reserved = asc.call("POST", "/appScreenshots", body={"data": {
        "type": "appScreenshots",
        "attributes": {"fileName": shot["name"], "fileSize": shot["size"]},
        "relationships": {"appScreenshotSet": {"data": {"type": "appScreenshotSets",
                                                        "id": set_id}}}}})["data"]
    data = pathlib.Path(shot["path"]).read_bytes()
    operations = (reserved.get("attributes") or {}).get("uploadOperations") or []
    if not operations:
        raise ListingError(f"App Store Connect gave no upload operations for {shot['path']}")
    for operation in operations:
        offset, length = int(operation.get("offset") or 0), int(operation["length"])
        asc.upload_part(operation, data[offset:offset + length])
    asc.call("PATCH", f"/appScreenshots/{reserved['id']}", body={"data": {
        "type": "appScreenshots", "id": reserved["id"],
        "attributes": {"uploaded": True, "sourceFileChecksum": shot["md5"]}}})
    return reserved["id"]


def wait_for_screenshots(asc, uploaded, timeout=SCREENSHOT_WAIT_SECONDS, interval=5):
    """Waits until App Store Connect has processed every uploaded screenshot ({id: path})."""
    deadline = time.time() + timeout
    pending = dict(uploaded)
    while pending:
        for shot_id, path in list(pending.items()):
            attrs = asc.call("GET", f"/appScreenshots/{shot_id}")["data"].get("attributes") or {}
            delivery = attrs.get("assetDeliveryState") or {}
            if delivery.get("state") == "COMPLETE":
                del pending[shot_id]
            elif delivery.get("state") == "FAILED":
                raise ListingError(f"App Store Connect could not process {path}: "
                                   f"{json.dumps(delivery.get('errors') or [])}")
        if not pending:
            return
        if time.time() > deadline:
            raise ListingError("Screenshots still processing after "
                               f"{timeout // 60} min: {', '.join(map(str, pending.values()))}")
        time.sleep(interval)


def sync_screenshot_set(asc, localization_id, sets, display_type, folder, apply):
    """Makes one display type's set of one localization match folder. Returns whether anything
    differed."""
    local = local_screenshots(folder)
    where = f"{folder.parent.name}/{folder.name} ({display_type})"
    if not local:
        print(f"::warning::{where}: no images; the set is left as it is")
        return False
    sizes = sorted({f"{s['dimensions'][0]}×{s['dimensions'][1]}" for s in local if s["dimensions"]})
    existing = sets.get(display_type)
    remote = []
    if existing:
        remote = [remote_screenshot(item) for item in asc.call(
            "GET", f"/appScreenshotSets/{existing['id']}/appScreenshots", {"limit": 50}).get("data", [])]
    remote_sizes = sorted({f"{r['dimensions'][0]}×{r['dimensions'][1]}" for r in remote if r["dimensions"]})
    plan = plan_screenshots(local, remote)
    print(f"  {where}: {len(local)} in the repo ({', '.join(sizes) or '?'}), "
          f"{len(remote)} in App Store Connect ({', '.join(remote_sizes) or 'none'})"
          + ("" if existing else "; no set yet"))
    if not (plan["upload"] or plan["delete"] or plan["reorder"]):
        print("    up to date")
        return False
    def verb(doing, would):
        return doing if apply else would

    if plan["delete"]:
        print(f"    {verb('deleting', 'would delete')}: " + ", ".join(
            f"{r['name'] or r['id']} ({r['state'] or '?'}, md5 {r['md5'][:8] or 'none'})"
            for r in plan["delete"]))
    if plan["upload"]:
        print(f"    {verb('uploading', 'would upload')}: "
              + ", ".join(local[i]["name"] for i in plan["upload"]))
    if plan["keep"]:
        print("    unchanged: " + ", ".join(local[i]["name"] for i in sorted(plan["keep"])))
    if plan["reorder"]:
        print(f"    {verb('reordering', 'would reorder')} to " + ", ".join(s["name"] for s in local))
    if not apply:
        return True
    if not existing:
        existing = asc.call("POST", "/appScreenshotSets", body={"data": {
            "type": "appScreenshotSets", "attributes": {"screenshotDisplayType": display_type},
            "relationships": {"appStoreVersionLocalization": {"data": {
                "type": "appStoreVersionLocalizations", "id": localization_id}}}}})["data"]
    # Deleted first, as a set holds at most ten.
    for shot in plan["delete"]:
        asc.call("DELETE", f"/appScreenshots/{shot['id']}")
    ids = {i: shot["id"] for i, shot in plan["keep"].items()}
    uploaded = {}
    for index in plan["upload"]:
        ids[index] = upload_screenshot(asc, existing["id"], local[index])
        uploaded[ids[index]] = local[index]["path"]
    wait_for_screenshots(asc, uploaded)
    if plan["reorder"]:
        asc.call("PATCH", f"/appScreenshotSets/{existing['id']}/relationships/appScreenshots",
                 body={"data": [{"type": "appScreenshots", "id": ids[i]} for i in range(len(local))]})
    print("    done")
    return True


def sync_screenshots(asc, localizations, apply, root=LISTING_DIR):
    """Syncs every locale folder's screenshot sets to its version localization ({locale: id}).
    A localization without folders of its own (en-US) shows the primary language's."""
    changed = False
    unsynced = [f"{locale}/{name}" for locale in listing_locales(root)
                for name in UNSYNCED_SCREENSHOT_FOLDERS if (pathlib.Path(root) / locale / name).is_dir()]
    if unsynced:
        print(f"::warning::Not synced: {', '.join(unsynced)}. The App Store Connect API's "
              "screenshotDisplayType has no iPhone Duo value; upload these in App Store Connect "
              "by hand")
    for locale in listing_locales(root):
        folder = pathlib.Path(root) / locale
        present = {name: folder / name for name in SCREENSHOT_SETS if (folder / name).is_dir()}
        if not present:
            print(f"  {locale}: no screenshot folders; App Store Connect shows the "
                  f"{FALLBACK_LOCALE} ones")
            continue
        if locale not in localizations:
            if apply:
                raise ListingError(f"The version has no {locale} localization for its screenshots; "
                                   "run `testflight.py listing --apply` first")
            print(f"  {locale}: the version has no localization yet (the listing step creates it); "
                  "every screenshot would be uploaded")
            for name, path in present.items():
                print(f"    {name}: {len(local_screenshots(path))} images")
            changed = True
            continue
        sets = {}
        for item in asc.call("GET", f"/appStoreVersionLocalizations/{localizations[locale]}"
                                    "/appScreenshotSets", {"limit": 50}).get("data", []):
            sets[(item.get("attributes") or {}).get("screenshotDisplayType")] = item
        managed = {SCREENSHOT_SETS[name] for name in present}
        others = sorted(t for t in sets if t not in managed)
        if others:
            print(f"  {locale}: also has {', '.join(map(str, others))} sets; left as they are")
        for name, path in present.items():
            changed |= sync_screenshot_set(asc, localizations[locale], sets, SCREENSHOT_SETS[name],
                                           path, apply)
    return changed


# --- Inspecting what App Store Connect holds (read-only) -----------------------------------------

class ReadOnlyAsc:
    """Wraps an Asc so that anything but a GET raises: `screenshots --inspect` can't write."""

    def __init__(self, asc):
        self._asc = asc

    def call(self, method, path, params=None, body=None):
        if method != "GET":
            raise AssertionError(f"read-only: refused {method} {path}")
        return self._asc.call(method, path, params)

    app_id = Asc.app_id


def get_all(asc, path, params=None, pages=20):
    """Every item of a list endpoint, following links.next. Returns (data, included)."""
    data, included = [], []
    for _ in range(pages):
        response = asc.call("GET", path, params)
        data += response.get("data") or []
        included += response.get("included") or []
        following = (response.get("links") or {}).get("next")
        if not following or not following.startswith(API):
            break
        parsed = urllib.parse.urlsplit(following[len(API):])
        path, params = parsed.path, dict(urllib.parse.parse_qsl(parsed.query))
    return data, included


# The App Asset Library display classes of the folders in store-listing/app-store/
# (developer.apple.com/documentation/appstoreconnectapi/appassetlibrarydisplayclass).
INSPECT_DISPLAY_CLASSES = ("IPHONE_DUO", "IPHONE_DYNAMIC_ISLAND_LARGE_DISPLAY",
                           "IPHONE_DYNAMIC_ISLAND_MEDIUM_DISPLAY", "IPAD_13_DISPLAY")


def strings_in(value):
    """Every string anywhere inside a JSON value."""
    if isinstance(value, str):
        return {value}
    if isinstance(value, dict):
        return set().union(*map(strings_in, value.values())) if value else set()
    if isinstance(value, list):
        return set().union(*map(strings_in, value)) if value else set()
    return set()


def dump(label, value, indent="    "):
    print(f"{indent}{label}: {json.dumps(value, ensure_ascii=False, sort_keys=True)}")


def inspect_ref_data(asc, record):
    """Prints the parts of the asset library's reference data that concern the folders here:
    their placement groups, display classes, APP_SCREENSHOT spec mappings, the specs those name,
    and the App Store version feature's limits."""
    data, _ = get_all(asc, "/appAssetLibraryRefData")
    record["appAssetLibraryRefData"] = data
    for datum in data:
        attrs = datum.get("attributes") or {}
        groups = attrs.get("placementProfileGroups") or []
        print(f"  ref data {datum.get('id')}: {len(groups)} placement groups, "
              f"{len(attrs.get('displayClasses') or [])} display classes, "
              f"{len(attrs.get('imageSpecs') or [])} image specs, "
              f"{len(attrs.get('placementTypes') or [])} placement types")
        relevant = [g for g in groups if g.get("displayClassId") in INSPECT_DISPLAY_CLASSES
                    or "DUO" in json.dumps(g)]
        relevant_ids = {g.get("placementProfileGroupId") for g in relevant} - {None}
        for group in relevant:
            dump("placement group", group)
        print("    every placement group id: " + ", ".join(
            sorted(str(g.get("placementProfileGroupId")) for g in groups)))
        for display in attrs.get("displayClasses") or []:
            if display.get("displayClassId") in INSPECT_DISPLAY_CLASSES or "DUO" in json.dumps(display):
                dump("display class", display)
        spec_ids = set()
        for placement_type in attrs.get("placementTypes") or []:
            if placement_type.get("placementTypeId") != "APP_SCREENSHOT":
                continue
            dump("APP_SCREENSHOT accepts", placement_type.get("acceptsAssetCategories"))
            for mapping in placement_type.get("specMappings") or []:
                found = strings_in(mapping)
                if found & relevant_ids or "DUO" in json.dumps(mapping):
                    dump("APP_SCREENSHOT spec mapping", mapping)
                    spec_ids |= found
        for spec in attrs.get("imageSpecs") or []:
            if spec.get("specId") in spec_ids:
                dump("image spec", spec)
        for feature in attrs.get("features") or []:
            if "APP_STORE_VERSION" in json.dumps(feature):
                dump("feature", feature)


def inspect_asset_library(asc, app_id, localizations, record):
    """Prints the app's asset library: its id, its images and each localization's placements."""
    library = asc.call("GET", f"/apps/{app_id}/assetLibrary").get("data") or {}
    record["assetLibrary"] = library
    print(f"Asset library: {json.dumps(library, ensure_ascii=False, sort_keys=True)}")
    inspect_ref_data(asc, record)
    if library.get("id"):
        images, _ = get_all(asc, f"/appAssetLibraries/{library['id']}/images", {"limit": 50})
        record["images"] = images
        print(f"  {len(images)} images in the library")
        for image in images:
            dump(f"image {image.get('id')}", {k: v for k, v in (image.get("attributes") or {}).items()
                                              if k != "uploadOperations"})
    record["placements"] = {}
    for locale, loc_id in sorted(localizations.items()):
        placements, included = get_all(
            asc, f"/appStoreVersionLocalizations/{loc_id}/placements",
            {"limit": 200, "sort": "placementGroupPosition", "include": "image"})
        record["placements"][locale] = {"data": placements, "included": included}
        images = {i.get("id"): i for i in included}
        print(f"  {locale}: {len(placements)} placements (in placementGroupPosition order)")
        for placement in placements:
            attrs = placement.get("attributes") or {}
            image_ref = ((placement.get("relationships") or {}).get("image") or {}).get("data") or {}
            image = (images.get(image_ref.get("id")) or {}).get("attributes") or {}
            print(f"    {attrs.get('placementType')} / {attrs.get('placementGroup')} "
                  f"[{attrs.get('state')}] placement {placement.get('id')} -> image "
                  f"{image_ref.get('id')}: {image.get('fileName')!r} "
                  f"{(image.get('imageAsset') or {}).get('width')}×"
                  f"{(image.get('imageAsset') or {}).get('height')} spec {image.get('specId')} "
                  f"ref {image.get('referenceName')!r} [{image.get('state')}]")


def inspect_screenshot_sets(asc, localizations, record):
    """Prints every screenshot set of every localization with its screenshots' raw attributes
    (upload operations left out) and their relationships and links."""
    record["screenshotSets"] = {}
    for locale, loc_id in sorted(localizations.items()):
        sets, _ = get_all(asc, f"/appStoreVersionLocalizations/{loc_id}/appScreenshotSets",
                          {"limit": 50})
        record["screenshotSets"][locale] = []
        print(f"  {locale} (localization {loc_id}): {len(sets)} screenshot sets")
        for item in sets:
            shots, _ = get_all(asc, f"/appScreenshotSets/{item['id']}/appScreenshots", {"limit": 50})
            record["screenshotSets"][locale].append({"set": item, "screenshots": shots})
            print(f"    set {item['id']}: "
                  f"{json.dumps(item.get('attributes'), ensure_ascii=False, sort_keys=True)}")
            dump("set relationships", item.get("relationships"), "      ")
            for shot in shots:
                attrs = {k: v for k, v in (shot.get("attributes") or {}).items()
                         if k != "uploadOperations"}
                dump(f"screenshot {shot.get('id')}", attrs, "      ")
                if shot.get("relationships"):
                    dump("relationships", shot["relationships"], "        ")


def inspect_screenshots(asc, app_id, version, dump_path=None):
    """Read-only: what the version's screenshot sets and the app's asset library hold, raw, so
    the mapping of the repo's folders can be checked against the live account. Only GETs."""
    asc = ReadOnlyAsc(asc)
    record = {"version": version}
    print("In the repo:")
    for locale in listing_locales():
        for name in (*SCREENSHOT_SETS, *UNSYNCED_SCREENSHOT_FOLDERS):
            folder = LISTING_DIR / locale / name
            if folder.is_dir():
                print(f"  {locale}/{name}: " + ", ".join(
                    f"{s['name']} {s['dimensions'][0]}×{s['dimensions'][1]} md5 {s['md5']}"
                    if s["dimensions"] else f"{s['name']} md5 {s['md5']}"
                    for s in local_screenshots(folder)))
    localizations = {locale: loc["id"] for locale, loc in localizations_by_locale(
        asc, f"/appStoreVersions/{version['id']}/appStoreVersionLocalizations").items()}
    print(f"Localizations: {', '.join(f'{k} {v}' for k, v in sorted(localizations.items()))}")
    print("Screenshot sets (appScreenshotSets):")
    inspect_screenshot_sets(asc, localizations, record)
    try:
        inspect_asset_library(asc, app_id, localizations, record)
    except AscError as error:
        print(f"Asset library: could not be read: {error}")
        record["assetLibraryError"] = str(error)
    if dump_path:
        pathlib.Path(dump_path).write_text(
            json.dumps(record, ensure_ascii=False, indent=1, sort_keys=True), encoding="utf-8")
        print(f"Wrote every response to {dump_path}")
    return 0


# --- Finding what can be edited --------------------------------------------------------------

def app_store_versions(asc, app_id):
    return asc.call("GET", f"/apps/{app_id}/appStoreVersions",
                    {"filter[platform]": "IOS", "limit": 50}).get("data", [])


def info_state(app_info):
    attrs = app_info.get("attributes") or {}
    return attrs.get("state") or attrs.get("appStoreState")


def pick_version(versions, version_string=None):
    """(version, editable): the editable version (the one named, if given); otherwise the newest
    one, for a read-only comparison. (None, False) when there is none."""
    if version_string:
        versions = [v for v in versions if v["attributes"].get("versionString") == version_string]
    editable = [v for v in versions if version_state(v) in EDITABLE_STATES]
    if editable:
        return editable[0], True
    newest = sorted(versions, key=lambda v: v["attributes"].get("createdDate") or "", reverse=True)
    return (newest[0], False) if newest else (None, False)


def pick_app_info(app_infos):
    """(app info, editable): the one in preparation; otherwise the one not yet live (in review),
    or the live one."""
    editable = [i for i in app_infos if info_state(i) in EDITABLE_STATES]
    if editable:
        return editable[0], True
    live = ("READY_FOR_DISTRIBUTION", "READY_FOR_SALE")
    pending = [i for i in app_infos if info_state(i) not in live]
    return ((pending or app_infos or [None])[0]), False


def listing_target(asc, app_id, version_string=None):
    """The version and app info to compare with, whether both can be written, and a sentence
    that says which they are."""
    versions = app_store_versions(asc, app_id)
    version, version_editable = pick_version(versions, version_string)
    app_info, info_editable = pick_app_info(
        asc.call("GET", f"/apps/{app_id}/appInfos", {"limit": 10}).get("data", []))
    if version is None or app_info is None:
        listed = ", ".join(f"{v['attributes'].get('versionString')} {version_state(v)}"
                           for v in versions) or "none"
        return None, None, False, f"No App Store version to compare with (versions: {listed})"
    name = f"{version['attributes'].get('versionString')} ({version_state(version)})"
    if version_editable and info_editable:
        return version, app_info, True, f"App Store version {name} is in preparation"
    if version_editable:
        return version, app_info, False, (
            f"App Store version {name} is in preparation, but no app info is (app info: "
            f"{info_state(app_info)}), so the name and subtitle can't be written. Showing the "
            "difference for information")
    listed = ", ".join(f"{v['attributes'].get('versionString')} {version_state(v)}"
                       for v in versions)
    return version, app_info, False, (
        f"No App Store version is editable (versions: {listed}; app info: {info_state(app_info)}). "
        f"Showing the difference with {name} for information. Nothing can be written until a new "
        "version is in preparation; `testflight.py appstore` creates one when a release PR merges")


def outcome(changed, applied, editable, what):
    if not changed:
        return f"The {what} is up to date" if what == "listing" else f"The {what} are up to date"
    if applied:
        return "Applied the differences above"
    if editable:
        return "Pass --apply to write the differences above"
    return ("The differences above would be written to the next version once it is in "
            "preparation")


def cmd_listing(args):
    check_listing_texts()
    asc = Asc()
    app_id = asc.app_id()
    version, app_info, editable, summary = listing_target(asc, app_id, args.version)
    print(summary)
    if version is None:
        return 1 if args.apply else 0
    apply = args.apply and editable
    print("APPLY" if apply else "DRY RUN (pass --apply to write)" if editable else "READ ONLY")
    _, changed = sync_listing(asc, version["id"], app_info["id"], apply)
    print(outcome(changed, apply, editable, "listing"))
    return 1 if args.apply and not editable else 0


def cmd_screenshots(args):
    inspect = getattr(args, "inspect", False)
    if inspect and args.apply:
        sys.exit("--inspect only reads; leave out --apply")
    asc = ReadOnlyAsc(Asc()) if inspect else Asc()
    app_id = asc.app_id()
    version, _, _, summary = listing_target(asc, app_id, args.version)
    print(summary)
    if version is None:
        return 1 if args.apply else 0
    if inspect:
        return inspect_screenshots(asc, app_id, version, args.dump)
    # Screenshots belong to the version alone; the app info's state doesn't matter here.
    editable = version_state(version) in EDITABLE_STATES
    apply = args.apply and editable
    print("APPLY" if apply else "DRY RUN (pass --apply to write)" if editable else "READ ONLY")
    localizations = {locale: loc["id"] for locale, loc in localizations_by_locale(
        asc, f"/appStoreVersions/{version['id']}/appStoreVersionLocalizations").items()}
    changed = sync_screenshots(asc, localizations, apply)
    print(outcome(changed, apply, editable, "screenshots"))
    return 1 if args.apply and not editable else 0


def apply_store_listing(asc, app_id, version, screenshots):
    """Writes the repo's listing (and with screenshots, the screenshot sets) to the version in
    preparation. Any failure stops the run before the version is submitted: a rerun picks up
    where this one stopped, as only what still differs is written."""
    try:
        app_info, editable = pick_app_info(
            asc.call("GET", f"/apps/{app_id}/appInfos", {"limit": 10}).get("data", []))
        if not editable:
            raise ListingError("No app info is in preparation (app info: "
                               f"{info_state(app_info) if app_info else 'none'}), so the name and "
                               "subtitle can't be set")
        print("Applying the store listing from store-listing/app-store/")
        localizations, _ = sync_listing(asc, version["id"], app_info["id"], apply=True)
        if screenshots:
            print("Applying the screenshots")
            sync_screenshots(asc, localizations, apply=True)
    except (ListingError, AscError) as error:
        sys.exit("::error::The store listing was not fully applied, so the version was not "
                 "submitted for review. Fix the cause and re-run the job; what is already "
                 f"applied is skipped.\n{error}")


def cmd_appstore(args):
    asc = Asc()
    app_id = asc.app_id()
    notes = read_notes(args.notes_dir)
    try:
        check_listing_texts()
    except ListingError as error:
        sys.exit(f"::error::{error}")
    versions = app_store_versions(asc, app_id)
    version = next((v for v in versions if v["attributes"]["versionString"] == args.version), None)
    if version and version_state(version) == "READY_FOR_REVIEW":
        # Left in a submission that was never sent, e.g. by a run that failed on the last step.
        print(f"App Store version {args.version} is in an unsent review submission; sending it")
        return submit_for_review(asc, app_id, version, args.release_type)
    if version and version_state(version) not in EDITABLE_STATES:
        print(f"App Store version {args.version} is already {version_state(version)}; nothing to do")
        return 0
    # Only one version can be in preparation at a time; one created by hand is taken over.
    version = version or next((v for v in versions if version_state(v) in EDITABLE_STATES), None)
    attributes = {"versionString": args.version, "releaseType": args.release_type}
    if version:
        asc.call("PATCH", f"/appStoreVersions/{version['id']}", body={"data": {
            "type": "appStoreVersions", "id": version["id"], "attributes": attributes}})
        print(f"Updating App Store version {args.version} ({version_state(version)})")
    else:
        version = asc.call("POST", "/appStoreVersions", body={"data": {
            "type": "appStoreVersions", "attributes": {"platform": "IOS", **attributes},
            "relationships": {"app": {"data": {"type": "apps", "id": app_id}}}}})["data"]
        print(f"Created App Store version {args.version}")

    # The newest build of the version is the one testers have been trying; it is submitted as is
    # rather than rebuilt from the merge commit.
    build = newest_valid_build(asc, app_id, args.version)
    asc.call("PATCH", f"/appStoreVersions/{version['id']}/relationships/build",
             body={"data": {"type": "builds", "id": build["id"]}})

    # A new version starts with the previous one's localizations, except What's New and the
    # promotional text, which Apple leaves empty. The listing step fills in the rest from the
    # repo and adds the localizations the repo has and the version lacks (en-US).
    apply_store_listing(asc, app_id, version, args.screenshots)
    localizations = {loc["attributes"]["locale"]: loc["id"] for loc in asc.call(
        "GET", f"/appStoreVersions/{version['id']}/appStoreVersionLocalizations",
        {"limit": 50})["data"]}
    for locale in localizations:
        if locale not in notes and fallback(locale) in notes:
            notes[locale] = notes[fallback(locale)]
    for locale, text in notes.items():
        if locale not in localizations:
            print(f"::warning::The App Store listing has no {locale} localization; "
                  f"its What's New was not set")
            continue
        asc.call("PATCH", f"/appStoreVersionLocalizations/{localizations[locale]}", body={"data": {
            "type": "appStoreVersionLocalizations", "id": localizations[locale],
            "attributes": {"whatsNew": text}}})
        print(f"Set What's New ({locale})")

    return submit_for_review(asc, app_id, version, args.release_type)


def cmd_status(_args):
    asc = Asc()
    app_id = asc.app_id()
    for group in asc.groups(app_id):
        attrs = group["attributes"]
        print(f"group: {attrs['name']} (internal={attrs['isInternalGroup']}, "
              f"all builds={attrs.get('hasAccessToAllBuilds')})")
    builds = asc.call("GET", "/builds", {"filter[app]": app_id, "sort": "-uploadedDate",
                                         "limit": 5, "include": "preReleaseVersion"})
    versions = {v["id"]: v["attributes"]["version"] for v in builds.get("included", [])}
    for build in builds["data"]:
        pre = build["relationships"]["preReleaseVersion"]["data"]
        print(f"build: {versions.get(pre['id'] if pre else None, '?')} "
              f"({build['attributes']['version']}) {build['attributes']['processingState']}")
    return 0


# The archive is signed with an Apple Development certificate, and a fresh CI runner has none, so
# every run makes xcodebuild create one through the API key; its private key is gone with the
# runner. Nothing reuses them, and once the team's certificate limit is reached signing fails. The
# team's other apps do the same, so those under a day old are kept, as a running build may need one.
def cmd_revoke_ci_certs(_args):
    asc = Asc()
    cutoff = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(days=364)
    certs = asc.call("GET", "/certificates",
                     {"filter[certificateType]": "DEVELOPMENT", "limit": 200})["data"]
    for cert in certs:
        attrs = cert["attributes"]
        expires = datetime.datetime.strptime(attrs["expirationDate"], "%Y-%m-%dT%H:%M:%S.%f%z")
        if attrs.get("displayName") == "Created via API" and expires < cutoff:
            print(f"Revoking {attrs['name']} ({cert['id']}), left by an earlier CI run")
            asc.call("DELETE", f"/certificates/{cert['id']}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    notes = commands.add_parser("notes", help="set What to Test and hand a build to internal testers")
    notes.add_argument("--version", required=True, help="marketing version, e.g. 1.3.0")
    notes.add_argument("--build", required=True, help="build number (CURRENT_PROJECT_VERSION)")
    notes.add_argument("--notes-dir", required=True)
    notes.add_argument("--timeout", type=int, default=45, help="minutes to wait for processing")
    notes.set_defaults(func=cmd_notes)
    appstore = commands.add_parser("appstore", help="submit the newest build for App Review")
    appstore.add_argument("--version", required=True)
    appstore.add_argument("--notes-dir", required=True)
    appstore.add_argument("--release-type", choices=["AFTER_APPROVAL", "MANUAL"],
                          default="AFTER_APPROVAL",
                          help="go live as soon as Apple approves, or wait for a manual release")
    appstore.add_argument("--screenshots", action="store_true",
                          help="also sync the screenshot sets from store-listing/app-store/")
    appstore.set_defaults(func=cmd_appstore)
    for name, func, what in (("listing", cmd_listing, "names, subtitles, keywords, descriptions "
                              "and promotional texts"),
                             ("screenshots", cmd_screenshots, "screenshot sets")):
        command = commands.add_parser(
            name, help=f"compare the {what} in store-listing/app-store/ with the version in "
                       "preparation (dry run unless --apply)")
        command.add_argument("--apply", action="store_true", help="write the differences")
        command.add_argument("--version", help="the version to compare with (default: the one "
                                               "in preparation, else the newest)")
        command.set_defaults(func=func)
        if name == "screenshots":
            command.add_argument("--inspect", action="store_true",
                                 help="read-only: print the raw screenshot sets, asset library "
                                      "images and placements of the version (GET requests only)")
            command.add_argument("--dump", metavar="FILE",
                                 help="with --inspect, also write every response to FILE as JSON")
    commands.add_parser("status", help="list groups and recent builds").set_defaults(func=cmd_status)
    revoke = commands.add_parser("revoke-ci-certs", help="revoke development certificates left by CI runs")
    revoke.set_defaults(func=cmd_revoke_ci_certs)
    args = parser.parse_args()
    try:
        return args.func(args)
    except AscError as error:
        print(f"App Store Connect API error: {error}", file=sys.stderr)
        return 1
    except ListingError as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
