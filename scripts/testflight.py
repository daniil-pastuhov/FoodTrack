#!/usr/bin/env python3
"""Hands an uploaded iOS build to TestFlight testers through the App Store Connect API.

Used by .github/workflows/ci.yml as part of the release flow (see "Releasing" in README.md):

    # After scripts/ios_release.sh uploaded build N of a release branch: wait for processing,
    # set the localized "What to Test" text and give it to the internal group.
    python3 scripts/testflight.py notes --version 1.3.0 --build N --notes-dir release-notes/v1.3.0

    # After the release PR is merged: give the newest build of that version to the external
    # group and submit it for beta review.
    python3 scripts/testflight.py submit --version 1.3.0 --notes-dir release-notes/v1.3.0

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
EXTERNAL_GROUP = "External testers"
# Release notes are stored under Play's locale codes; App Store Connect names the same
# languages as in store-listing/app-store/.
ASC_LOCALES = {"en-GB": "en-GB", "ru-RU": "ru", "no-NO": "no"}
# "What to Test" is limited to 4000 characters; the notes are much shorter (Play allows 500).
WHATS_NEW_LIMIT = 4000


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


def cmd_submit(args):
    asc = Asc()
    app_id = asc.app_id()
    notes = read_notes(args.notes_dir)
    valid = [b for b in asc.builds(app_id, args.version)
             if b["attributes"]["processingState"] == "VALID"]
    if not valid:
        sys.exit(f"No processed build of {args.version} in App Store Connect")
    build = valid[0]
    print(f"Using build {build['attributes']['version']} of {args.version}")
    # Notes again, in case the newest build came from a push whose notes step failed.
    set_whats_new(asc, build["id"], notes)

    group = next((g for g in asc.groups(app_id) if g["attributes"]["name"] == EXTERNAL_GROUP
                  and not g["attributes"]["isInternalGroup"]), None)
    if group is None:
        group = asc.call("POST", "/betaGroups", body={"data": {
            "type": "betaGroups", "attributes": {"name": EXTERNAL_GROUP},
            "relationships": {"app": {"data": {"type": "apps", "id": app_id}}}}})["data"]
        print(f"Created the external group '{EXTERNAL_GROUP}'; add testers to it in App Store Connect")
    add_to_group(asc, group, build["id"])

    try:
        asc.call("POST", "/betaAppReviewSubmissions", body={"data": {
            "type": "betaAppReviewSubmissions",
            "relationships": {"build": {"data": {"type": "builds", "id": build["id"]}}}}})
        print("Submitted for TestFlight beta review; testers get it once Apple approves")
    except AscError as error:
        if error.status == 409 and "already" in error.body.lower():
            print("The build was already submitted for beta review")
        elif error.status == 409:
            sys.exit("App Store Connect refused the beta review submission. Fill in TestFlight → "
                     "Test Information (feedback email, beta description) and the Beta App Review "
                     f"contact once, then re-run the job.\n{error.body}")
        else:
            raise
    return 0


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
    submit = commands.add_parser("submit", help="hand the newest build to external testers")
    submit.add_argument("--version", required=True)
    submit.add_argument("--notes-dir", required=True)
    submit.set_defaults(func=cmd_submit)
    commands.add_parser("status", help="list groups and recent builds").set_defaults(func=cmd_status)
    args = parser.parse_args()
    try:
        return args.func(args)
    except AscError as error:
        print(f"App Store Connect API error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
