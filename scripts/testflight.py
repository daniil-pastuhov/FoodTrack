#!/usr/bin/env python3
"""Hands iOS builds to TestFlight testers and App Review through the App Store Connect API.

Used by .github/workflows/ci.yml as part of the release flow (see "Releasing" in README.md):

    # After scripts/ios_release.sh uploaded build N of a release branch: wait for processing,
    # set the localized "What to Test" text and give it to the internal group.
    python3 scripts/testflight.py notes --version 1.3.0 --build N --notes-dir release-notes/v1.3.0

    # After the release PR is merged: put the newest build of that version on its App Store
    # version, with the notes as "What's New", and submit it for App Review.
    python3 scripts/testflight.py appstore --version 1.3.0 --notes-dir release-notes/v1.3.0

    # Read-only overview of the app's builds and groups.
    python3 scripts/testflight.py status

Reads ASC_KEY_ID, ASC_ISSUER_ID and ASC_KEY_PATH (the .p8 file), the same as ios_release.sh.
Needs the `cryptography` package for the ES256 token signature.
"""
import argparse
import base64
import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

BUNDLE_ID = "com.foodbest.ios"
API = "https://api.appstoreconnect.apple.com/v1"
# Release notes are stored under Play's locale codes; App Store Connect names the same
# languages as in store-listing/app-store/.
ASC_LOCALES = {"en-GB": "en-GB", "ru-RU": "ru", "no-NO": "no"}
# One folder per App Store Connect locale, holding promotional_text.txt among others.
LISTING_DIR = pathlib.Path(__file__).resolve().parent.parent / "store-listing" / "app-store"
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


def set_promotional_text(asc, localizations):
    """Sets each localization's promotional text from store-listing/app-store/<locale>/."""
    for locale, localization_id in localizations.items():
        path = LISTING_DIR / locale / "promotional_text.txt"
        if not path.exists():
            print(f"::warning::{path.relative_to(LISTING_DIR.parent.parent)} is missing; "
                  f"the {locale} promotional text was not set")
            continue
        asc.call("PATCH", f"/appStoreVersionLocalizations/{localization_id}", body={"data": {
            "type": "appStoreVersionLocalizations", "id": localization_id,
            "attributes": {"promotionalText": path.read_text(encoding="utf-8").strip()}}})
        print(f"Set promotional text ({locale})")


def cmd_appstore(args):
    asc = Asc()
    app_id = asc.app_id()
    notes = read_notes(args.notes_dir)
    versions = asc.call("GET", f"/apps/{app_id}/appStoreVersions",
                        {"filter[platform]": "IOS", "limit": 50})["data"]
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
    # promotional text, which Apple leaves empty.
    localizations = {loc["attributes"]["locale"]: loc["id"] for loc in asc.call(
        "GET", f"/appStoreVersions/{version['id']}/appStoreVersionLocalizations",
        {"limit": 50})["data"]}
    set_promotional_text(asc, localizations)
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
    appstore.set_defaults(func=cmd_appstore)
    commands.add_parser("status", help="list groups and recent builds").set_defaults(func=cmd_status)
    args = parser.parse_args()
    try:
        return args.func(args)
    except AscError as error:
        print(f"App Store Connect API error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
