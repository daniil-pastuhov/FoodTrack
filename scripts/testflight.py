#!/usr/bin/env python3
"""Hands iOS builds to TestFlight testers and App Review through the App Store Connect API.

Used by .github/workflows/ci.yml as part of the release flow (see "Releasing" in README.md):

    # After scripts/ios_release.sh uploaded build N of a release branch: wait for processing,
    # set the localized "What to Test" text and give it to the internal group.
    python3 scripts/testflight.py notes --version 1.3.0 --build N --notes-dir release-notes/v1.3.0

    # After the release PR is merged: put the newest build of that version on its App Store
    # version, with the notes as "What's New" and the listing from store-listing/app-store/
    # (with --screenshots, its screenshot sets and header and search-result art too), and submit
    # it for App Review.
    python3 scripts/testflight.py appstore --version 1.3.0 --notes-dir release-notes/v1.3.0 \\
        --screenshots

    # Before an archive on CI: revoke the Apple Development certificates earlier runs left behind.
    python3 scripts/testflight.py revoke-ci-certs

    # Read-only: the in-app purchases (state, review screenshot, localizations) and what
    # `appstore` would do with them; it adds those still to be reviewed to the version's review
    # submission (--iaps-attached: submit without adding them, after adding them by hand).
    python3 scripts/testflight.py iaps

    # The in-app event in store-listing/app-store/in-app-event/ (texts, settings, schedule, card
    # and details images): dry run by default, --apply creates or updates it. It is never
    # submitted; --add-to-review puts it into a draft review submission (the version's, if one
    # is in preparation) for the release job's `appstore` to send with the version.
    python3 scripts/testflight.py event [--apply [--add-to-review]]

    # The in-app purchases' prices against scripts/pack_prices.py (base territory USA, the other
    # storefronts by Apple's equalisation): dry run by default, --apply sets a new price schedule
    # for each one whose USA price differs.
    python3 scripts/testflight.py iap-prices [--apply] [PRODUCT_ID ...]

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
API_ROOT = "https://api.appstoreconnect.apple.com"
API = API_ROOT + "/v1"
# Release notes are stored under Play's locale codes; App Store Connect names the same
# languages as in store-listing/app-store/.
ASC_LOCALES = {"en-GB": "en-GB", "ru-RU": "ru", "no-NO": "no", "es-ES": "es-ES", "es-419": "es-MX",
               "de-DE": "de-DE", "fr-FR": "fr-FR", "fr-CA": "fr-CA", "sv-SE": "sv", "da-DK": "da"}
# One folder per App Store Connect locale, holding promotional_text.txt among others.
LISTING_DIR = pathlib.Path(__file__).resolve().parent.parent / "store-listing" / "app-store"
# The other English localizations (en-US, en-AU, en-CA) take the en-GB release notes, as the notes
# are written once per language, and every en-GB listing text and image their folder doesn't have
# (all of them without a folder; en-AU and en-CA hold only keywords.txt). Apple asks for What's
# New in every localization of an update.
FALLBACK_LOCALE = "en-GB"
# Regional variants of another language take that language's folder the same way: every text,
# screenshot set, Duo set and header/search-result art their own folder lacks. Unlike en-GB,
# es-ES and fr-FR are not the primary language, so App Store Connect would show en-GB's
# screenshots there; sync_screenshots places the fallback's instead.
REGIONAL_FALLBACKS = {"es-MX": "es-ES", "fr-CA": "fr-FR"}


def fallback(locale):
    if locale in REGIONAL_FALLBACKS:
        return REGIONAL_FALLBACKS[locale]
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
        # Paths are /v1 ones unless they name /v2/ themselves (the in-app purchase endpoints).
        url = (API_ROOT if path.startswith("/v2/") else API) + path
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


# --- In-app purchases: submitted for App Review with the version ---------------------------------
#
# developer.apple.com/documentation/appstoreconnectapi/managing-in-app-purchases (API 4.4.1): an
# in-app purchase's reviewable metadata lives on an In-App Purchase version, and the version goes
# to App Review as an item of a review submission (`inAppPurchaseVersion`), as the app version
# does; POST /v1/inAppPurchaseSubmissions is deprecated. The first in-app purchase of each type
# must be submitted together with a new app version, in the same submission (App Store Connect
# Help, "Submit an In-App Purchase"). The API article adds that the review-submission calls
# "don't apply to that first submission", which it says goes through the website, while the
# website itself adds these items through the same reviewSubmissionItems endpoint. So the
# items are added here, in the submission that holds the app version, before it is submitted; a
# refusal stops the run before anything is sent, and says how to add them on the website, after
# which a re-run finds them in the submission and carries on.

# Parent states of an in-app purchase that still has to be reviewed.
IAP_TO_SUBMIT_STATES = {"READY_TO_SUBMIT", "DEVELOPER_ACTION_NEEDED", "REJECTED"}
# In-App Purchase version states: a draft, or one already in a submission not yet sent.
IAP_DRAFT_VERSION_STATES = {"PREPARE_FOR_SUBMISSION", "READY_FOR_REVIEW"}
OPEN_SUBMISSION_STATES = "READY_FOR_REVIEW,UNRESOLVED_ISSUES"


def version_number(version):
    """An In-App Purchase version's number (an integer in Apple's examples), for sorting."""
    try:
        return int((version.get("attributes") or {}).get("version") or 0)
    except (TypeError, ValueError):
        return 0


def app_iaps(asc, app_id):
    """The app's in-app purchases with what review needs: the draft version (newest one in
    PREPARE_FOR_SUBMISSION or READY_FOR_REVIEW), its locales and whether a review screenshot is
    uploaded."""
    iaps, _ = get_all(asc, f"/apps/{app_id}/inAppPurchasesV2", {"limit": 200})
    result = []
    for iap in iaps:
        attrs = iap.get("attributes") or {}
        versions, _ = get_all(asc, f"/v2/inAppPurchases/{iap['id']}/versions", {"limit": 200})
        drafts = sorted((v for v in versions
                         if (v.get("attributes") or {}).get("state") in IAP_DRAFT_VERSION_STATES),
                        key=version_number, reverse=True)
        draft = drafts[0] if drafts else None
        locales = []
        if draft:
            localizations, _ = get_all(asc, f"/inAppPurchaseVersions/{draft['id']}/localizations",
                                       {"limit": 50})
            locales = sorted((l.get("attributes") or {}).get("locale") or "?" for l in localizations)
        try:
            screenshot = asc.call("GET", f"/v2/inAppPurchases/{iap['id']}/appStoreReviewScreenshot")
            has_screenshot = bool(screenshot.get("data"))
        except AscError as error:
            if error.status != 404:
                raise
            has_screenshot = False
        result.append({
            "id": iap["id"], "product": attrs.get("productId") or iap["id"],
            "type": attrs.get("inAppPurchaseType"), "state": attrs.get("state"),
            "version": draft["id"] if draft else None,
            "version_state": (draft.get("attributes") or {}).get("state") if draft else None,
            "versions": ", ".join(f"{(v.get('attributes') or {}).get('version')} "
                                  f"{(v.get('attributes') or {}).get('state')}" for v in versions),
            "locales": locales, "screenshot": has_screenshot})
    return result


def open_submissions(asc, app_id):
    """The app's review submissions not sent yet (or sent back), each with the app versions and
    In-App Purchase versions it holds."""
    submissions = asc.call("GET", "/reviewSubmissions", {
        "filter[app]": app_id, "filter[platform]": "IOS",
        "filter[state]": OPEN_SUBMISSION_STATES}).get("data") or []
    result = []
    for submission in submissions:
        items, _ = get_all(asc, f"/reviewSubmissions/{submission['id']}/items",
                           {"include": "appStoreVersion,inAppPurchaseVersion", "limit": 200})
        held = {"appStoreVersion": set(), "inAppPurchaseVersion": set()}
        for item in items:
            for kind in held:
                linked = ((item.get("relationships") or {}).get(kind) or {}).get("data") or {}
                if linked.get("id"):
                    held[kind].add(linked["id"])
        result.append({"submission": submission, "versions": held["appStoreVersion"],
                       "iap_versions": held["inAppPurchaseVersion"]})
    return result


def plan_iaps(iaps, submissions, version_id):
    """What `appstore` does with each in-app purchase. Returns (submission to use or None for a
    new one, [(iap, action, detail)]), where action is "add" (an item for its draft version),
    "held" (already in that submission), "elsewhere" (in another open submission: stop),
    "missing" (to review but no draft version: stop), "skip" or "warn"."""
    chosen = next((s for s in submissions if version_id in s["versions"]), None)
    draft_ids = {i["version"] for i in iaps if i["version"]}
    chosen = chosen or next((s for s in submissions if s["iap_versions"] & draft_ids), None)
    chosen = chosen or (submissions[0] if submissions else None)
    plan = []
    for iap in iaps:
        held_in = [s for s in submissions if iap["version"] and iap["version"] in s["iap_versions"]]
        if chosen and iap["version"] in chosen["iap_versions"]:
            plan.append((iap, "held", "already in the review submission"))
        elif held_in:
            plan.append((iap, "elsewhere", "its version is in another open review submission ("
                         + ", ".join(s["submission"]["id"] for s in held_in) + ")"))
        elif iap["state"] in IAP_TO_SUBMIT_STATES or iap["version_state"] == "PREPARE_FOR_SUBMISSION":
            if iap["version_state"] == "PREPARE_FOR_SUBMISSION":
                plan.append((iap, "add", "added to the review submission with the app version"))
            elif iap["state"] == "READY_TO_SUBMIT":
                plan.append((iap, "missing", "no In-App Purchase version in PREPARE_FOR_SUBMISSION "
                             f"(versions: {iap['versions'] or 'none'})"))
            else:
                plan.append((iap, "warn", f"{iap['state']} with no draft version; left out"))
        elif iap["state"] in ("MISSING_METADATA", "WAITING_FOR_UPLOAD", "PROCESSING_CONTENT"):
            plan.append((iap, "warn", f"{iap['state']}: not ready for review; left out"))
        else:
            plan.append((iap, "skip", f"{iap['state']}: nothing to do"))
    return chosen, plan


def describe_iaps(plan):
    for iap, action, detail in plan:
        print(f"  {iap['product']} ({iap['type'] or '?'}, id {iap['id']}): {iap['state']}; draft "
              f"version {iap['version'] or 'none'} {iap['version_state'] or ''}; review screenshot "
              f"{'yes' if iap['screenshot'] else 'NO'}; localizations "
              f"{', '.join(iap['locales']) or 'none'}")
        print(f"    -> {detail}")


IAP_BY_HAND = (
    "Add them by hand, then re-run the job: in App Store Connect, open each in-app purchase "
    "(Monetization > In-App Purchases), click Add for Review and add it to the draft submission "
    "that holds this app version (or, where the version page still has the section, App Store "
    "Connect > the version page > In-App Purchases and Subscriptions > select them > Save). Don't "
    "click Submit for Review there; the job submits the version and the purchases together. "
    "If App Store Connect shows them in the version's submission but this job can't see them, "
    "re-run it with --iaps-attached.")


def submit_for_review(asc, app_id, version, release_type, iaps_attached=False):
    """Puts the app version and every in-app purchase still to be reviewed into one review
    submission and sends it. Stops before sending when an in-app purchase can't be added."""
    try:
        iaps = app_iaps(asc, app_id)
        submissions = open_submissions(asc, app_id)
        chosen, plan = plan_iaps(iaps, submissions, version["id"])
        if iaps:
            print("In-app purchases:")
            describe_iaps(plan)
        for iap, action, detail in plan:
            if action == "warn":
                print(f"::warning::In-app purchase {iap['product']}: {detail}")
        blocking = [(iap, detail) for iap, action, detail in plan if action in ("elsewhere", "missing")]
        if blocking and not iaps_attached:
            sys.exit("::error::Not submitted: these in-app purchases can't go into this version's "
                     "review submission:\n" + "\n".join(f"- {i['product']}: {d}" for i, d in blocking)
                     + "\nPut the app version and the purchases in one draft submission (App Store "
                     "Connect > App Review > Drafts), then re-run the job.")
        submission = chosen["submission"] if chosen else asc.call("POST", "/reviewSubmissions", body={
            "data": {"type": "reviewSubmissions", "attributes": {"platform": "IOS"},
                     "relationships": {"app": {"data": {"type": "apps", "id": app_id}}}}})["data"]
        if not chosen or version["id"] not in chosen["versions"]:
            asc.call("POST", "/reviewSubmissionItems", body={"data": {
                "type": "reviewSubmissionItems", "relationships": {
                    "reviewSubmission": {"data": {"type": "reviewSubmissions",
                                                  "id": submission["id"]}},
                    "appStoreVersion": {"data": {"type": "appStoreVersions",
                                                 "id": version["id"]}}}}})
        # After the app version: the first in-app purchase of a type is reviewed with it.
        refused = []
        for iap, action, _ in plan:
            if action != "add":
                continue
            if iaps_attached:
                print(f"  {iap['product']}: not added (--iaps-attached)")
                continue
            try:
                asc.call("POST", "/reviewSubmissionItems", body={"data": {
                    "type": "reviewSubmissionItems", "relationships": {
                        "reviewSubmission": {"data": {"type": "reviewSubmissions",
                                                      "id": submission["id"]}},
                        "inAppPurchaseVersion": {"data": {"type": "inAppPurchaseVersions",
                                                          "id": iap["version"]}}}}})
                print(f"  {iap['product']}: added to the review submission")
            except AscError as error:
                if error.status not in (403, 409, 422):
                    raise
                refused.append((iap, error.body))
        if refused:
            sys.exit("::error::App Store Connect refused to add these in-app purchases to the "
                     "review submission, so the version was not submitted (a build that sells them "
                     "would be rejected without them):\n"
                     + "\n".join(f"- {i['product']}: {body}" for i, body in refused)
                     + "\n" + IAP_BY_HAND)
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
    try:
        left = [iap["product"] for iap in app_iaps(asc, app_id) if iap["state"] == "READY_TO_SUBMIT"]
    except AscError as error:  # the submission is sent; a failed check mustn't fail the job
        print(f"::warning::Could not read the in-app purchases back after submitting: {error}")
        left = []
    if left:
        print(f"::warning::Still READY_TO_SUBMIT after the submission: {', '.join(left)}. Check in "
              "App Store Connect that they are in review with the version.")
    return 0


def cmd_iaps(args):
    """Read-only: the app's in-app purchases and what `appstore` would do with them."""
    asc = ReadOnlyAsc(Asc())
    app_id = asc.app_id()
    version, _, _, summary = listing_target(asc, app_id, args.version)
    print(summary)
    iaps = app_iaps(asc, app_id)
    submissions = open_submissions(asc, app_id)
    for s in submissions:
        print(f"Open review submission {s['submission']['id']} "
              f"({(s['submission'].get('attributes') or {}).get('state')}): "
              f"{len(s['versions'])} app version(s), {len(s['iap_versions'])} in-app purchase version(s)")
    if not iaps:
        print("No in-app purchases")
        return 0
    chosen, plan = plan_iaps(iaps, submissions, version["id"] if version else None)
    print("In-app purchases (what `appstore` would do):")
    describe_iaps(plan)
    print("Review submission: " + (f"{chosen['submission']['id']} (open)" if chosen else
                                   "a new one, with the app version"))
    return 0


# --- In-app purchase prices: scripts/pack_prices.py onto each purchase's price schedule ---------
#
# developer.apple.com/documentation/appstoreconnectapi: an in-app purchase's prices are one price
# schedule (GET /v2/inAppPurchases/{id}/iapPriceSchedule) with a base territory and manual prices
# (GET /v1/inAppPurchasePriceSchedules/{id}/manualPrices, each an inAppPurchasePrice pointing at
# an inAppPurchasePricePoint, whose customerPrice is the price); the other storefronts follow the
# base territory's price point by Apple's equalisation (automaticPrices). A change is a new
# schedule, POST /v1/inAppPurchasePriceSchedules (InAppPurchasePriceScheduleCreateRequest:
# relationships inAppPurchase, baseTerritory and manualPrices, all required, the manual prices
# inline in `included` as InAppPurchasePriceInlineCreate with relationships inAppPurchaseV2 and
# inAppPurchasePricePoint; "a base territory is now required"). It replaces the whole schedule,
# so a manual price set by hand in another territory, or a change scheduled for later, is
# dropped: the dry run lists them. The price point comes from GET
# /v2/inAppPurchases/{id}/pricePoints?filter[territory]=USA.

PRICE_BASE_TERRITORY = "USA"
# Storefronts the dry run shows besides the base, through the price points' equalizations.
PRICE_SAMPLE_TERRITORIES = ("GBR", "DEU", "FRA", "ESP", "NOR", "SWE", "DNK", "JPN")
NEW_PRICE_ID = "${new-base-price}"


def price_in_force(prices, today):
    """Of a schedule's manual prices in one territory ({"start", "end", ...}, ISO dates or None),
    the one in force on `today` (an ISO date): started (no start, or on or before today) and not
    ended (no end, or after today); the latest start wins."""
    live = [p for p in prices if (not p.get("start") or p["start"] <= today)
            and (not p.get("end") or p["end"] > today)]
    return max(live, key=lambda p: p.get("start") or "", default=None)


def pick_price_point(points, usd):
    """The price point whose customerPrice is `usd` (a decimal string), or None."""
    from decimal import Decimal, InvalidOperation
    wanted = Decimal(usd)
    for point in points:
        try:
            if Decimal((point.get("attributes") or {}).get("customerPrice") or "x") == wanted:
                return point
        except InvalidOperation:
            continue
    return None


def price_schedule_body(iap_id, price_point_id, territory=PRICE_BASE_TERRITORY):
    """A new price schedule: `territory` as base, at that price point, starting now."""
    return {
        "data": {
            "type": "inAppPurchasePriceSchedules",
            "relationships": {
                "inAppPurchase": {"data": {"type": "inAppPurchases", "id": iap_id}},
                "baseTerritory": {"data": {"type": "territories", "id": territory}},
                "manualPrices": {"data": [{"type": "inAppPurchasePrices", "id": NEW_PRICE_ID}]},
            },
        },
        "included": [{
            "type": "inAppPurchasePrices", "id": NEW_PRICE_ID,
            "attributes": {"startDate": None},
            "relationships": {
                "inAppPurchaseV2": {"data": {"type": "inAppPurchases", "id": iap_id}},
                "inAppPurchasePricePoint": {"data": {"type": "inAppPurchasePricePoints",
                                                     "id": price_point_id}},
            },
        }],
    }


def plan_price(base_territory, manual, usd, today):
    """What `iap-prices` does with one in-app purchase. `manual` is the schedule's manual prices
    as [{"territory", "start", "end", "price"}]. Returns (action, detail, current base price or
    None, the other manual prices a new schedule would drop): action "same", "change" or
    "unknown" (no price in force in the base territory: set it anyway)."""
    from decimal import Decimal
    base = [p for p in manual if p["territory"] == PRICE_BASE_TERRITORY]
    current = price_in_force(base, today)
    dropped = [p for p in manual if p is not current]
    if base_territory == PRICE_BASE_TERRITORY and current and current.get("price") is not None \
            and Decimal(current["price"]) == Decimal(usd):
        return "same", f"USD {current['price']} in {PRICE_BASE_TERRITORY}, as the table", current, []
    if current is None or current.get("price") is None:
        return "unknown", f"no price in force in {PRICE_BASE_TERRITORY}; would set USD {usd}", \
            None, dropped
    note = "" if base_territory == PRICE_BASE_TERRITORY else \
        f" (base territory now {base_territory}; would become {PRICE_BASE_TERRITORY})"
    return "change", f"USD {current['price']} -> USD {usd}{note}", current, dropped


def schedule_prices(asc, schedule_id):
    """A price schedule's manual prices as [{"territory", "start", "end", "price", "point"}]."""
    prices, included = get_all(asc, f"/v1/inAppPurchasePriceSchedules/{schedule_id}/manualPrices", {
        "include": "inAppPurchasePricePoint,territory",
        "fields[inAppPurchasePrices]": "startDate,endDate,manual,inAppPurchasePricePoint,territory",
        "fields[inAppPurchasePricePoints]": "customerPrice,territory", "limit": 200})
    points = {i["id"]: i for i in included if i.get("type") == "inAppPurchasePricePoints"}
    result = []
    for price in prices:
        attrs = price.get("attributes") or {}
        rel = price.get("relationships") or {}
        point_id = ((rel.get("inAppPurchasePricePoint") or {}).get("data") or {}).get("id")
        territory = ((rel.get("territory") or {}).get("data") or {}).get("id")
        point = points.get(point_id) or {}
        if not territory:
            territory = (((point.get("relationships") or {}).get("territory") or {})
                         .get("data") or {}).get("id")
        result.append({"territory": territory, "start": attrs.get("startDate"),
                       "end": attrs.get("endDate"), "point": point_id,
                       "price": (point.get("attributes") or {}).get("customerPrice")})
    return result


def equalized(asc, point_id):
    """Some storefronts' prices for a price point (PRICE_SAMPLE_TERRITORIES), as
    {territory: "CUR price"}; empty when App Store Connect doesn't answer."""
    try:
        points, included = get_all(asc, f"/v1/inAppPurchasePricePoints/{point_id}/equalizations", {
            "filter[territory]": ",".join(PRICE_SAMPLE_TERRITORIES), "include": "territory",
            "fields[inAppPurchasePricePoints]": "customerPrice,territory",
            "fields[territories]": "currency", "limit": 200}, pages=2)
    except AscError as error:
        print(f"    (equalizations of {point_id} not read: HTTP {error.status})")
        return {}
    currencies = {i["id"]: (i.get("attributes") or {}).get("currency")
                  for i in included if i.get("type") == "territories"}
    shown = {}
    for point in points:
        territory = (((point.get("relationships") or {}).get("territory") or {})
                     .get("data") or {}).get("id")
        shown[territory] = f"{currencies.get(territory) or '?'} " \
                           f"{(point.get('attributes') or {}).get('customerPrice')}"
    return shown


def cmd_iap_prices(args):
    """Sets each in-app purchase's price to scripts/pack_prices.py's, through a new price schedule
    with the United States as base territory (dry run unless --apply)."""
    from pack_prices import USD_PRICES
    live = Asc()
    asc = live if args.apply else ReadOnlyAsc(live)
    print("APPLY" if args.apply else "DRY RUN (pass --apply to write)")
    app_id = asc.app_id()
    today = datetime.datetime.now(datetime.timezone.utc).date().isoformat()
    iaps, _ = get_all(asc, f"/apps/{app_id}/inAppPurchasesV2", {"limit": 200})
    by_product = {(i.get("attributes") or {}).get("productId"): i for i in iaps}
    failed = False
    for product, usd in USD_PRICES.items():
        if args.products and product not in args.products:
            continue
        iap = by_product.get(product)
        if not iap:
            print(f"{product}: no such in-app purchase in App Store Connect")
            failed = True
            continue
        state = (iap.get("attributes") or {}).get("state")
        try:
            schedule = asc.call("GET", f"/v2/inAppPurchases/{iap['id']}/iapPriceSchedule",
                                {"include": "baseTerritory"})
        except AscError as error:
            if error.status != 404:
                raise
            schedule = {}
        schedule_id = (schedule.get("data") or {}).get("id")
        base = (((schedule.get("data") or {}).get("relationships") or {}).get("baseTerritory")
                or {}).get("data") or {}
        manual = schedule_prices(asc, schedule_id) if schedule_id else []
        action, detail, current, dropped = plan_price(base.get("id"), manual, usd, today)
        print(f"{product} (id {iap['id']}, {state}): {detail}")
        if action == "same":
            continue
        for price in dropped:
            print(f"    dropped by the new schedule: {price['territory']} {price['price']} "
                  f"from {price['start'] or 'the start'} to {price['end'] or 'no end'}")
        points, _ = get_all(asc, f"/v2/inAppPurchases/{iap['id']}/pricePoints", {
            "filter[territory]": PRICE_BASE_TERRITORY, "fields[inAppPurchasePricePoints]":
            "customerPrice,territory", "limit": 8000}, pages=3)
        point = pick_price_point(points, usd)
        if not point:
            print(f"    ::error::no {PRICE_BASE_TERRITORY} price point at USD {usd} "
                  f"(of {len(points)}); not changed")
            failed = True
            continue
        print(f"    price point {point['id']} (USD {point['attributes']['customerPrice']})")
        old = equalized(asc, current["point"]) if current and current.get("point") else {}
        new = equalized(asc, point["id"])
        for territory in PRICE_SAMPLE_TERRITORIES:
            if territory in old or territory in new:
                print(f"    {territory}: {old.get(territory, '?')} -> {new.get(territory, '?')}")
        if not args.apply:
            continue
        live.call("POST", "/inAppPurchasePriceSchedules", body=price_schedule_body(iap["id"], point["id"]))
        after = schedule_prices(live, live.call("GET", f"/v2/inAppPurchases/{iap['id']}/iapPriceSchedule")
                                ["data"]["id"])
        now = price_in_force([p for p in after if p["territory"] == PRICE_BASE_TERRITORY], today)
        print(f"    set; App Store Connect now has USD {now['price'] if now else '?'}")
        failed |= not now or now["point"] != point["id"]
    return 1 if failed else 0


# --- Store listing: store-listing/app-store/<locale>/ onto the version in preparation ----------
#
# Each locale folder's texts go to two resources: the version's localization (keywords,
# description, promotional text) and the app info's (name, subtitle, privacy policy URL), which
# App Store Connect only lets change while a version is in preparation (Apple's "Required,
# localizable, and editable properties": the privacy policy URL is localized and not editable
# between versions). A field is written only when its file exists (in the locale's folder, or for
# another English localization in en-GB's, which fills in what a partial folder such as en-AU/
# leaves out) and differs from what App Store Connect has, so the support and marketing URLs and
# anything else the repo has no file for are never touched.

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
    "privacy_url.txt": ("info", "privacyPolicyUrl"),
}
# Copied from the primary language's localization when a localization is created, so a new one
# (en-US) is not left without them: App Review needs a support URL in every localization. A field
# the localization's own files give (privacyPolicyUrl from privacy_url.txt) is not copied.
COPIED_ON_CREATE = {"version": ("supportUrl", "marketingUrl"),
                    "info": ("privacyPolicyUrl", "privacyChoicesUrl")}
RESOURCE_TYPES = {"version": "appStoreVersionLocalizations", "info": "appInfoLocalizations"}
# The privacy policy site: a page per language (privacy_url.txt names it; check_store_listing.py's
# PRIVACY_SITE is the same) and the language chooser at the root. A page that doesn't answer 200
# when the listing is synced (one not yet published) is never set: the chooser is set instead,
# and the next sync, once the page is live, sets the page.
PRIVACY_SITE = "https://daniil-pastuhov.github.io/bestbefore-privacy/"
PRIVACY_ATTRIBUTE = "privacyPolicyUrl"
# The App Review notes (the version's appStoreReviewDetail.notes), one text for all languages.
REVIEW_NOTES_FILE = "review-notes.txt"

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
# iPhone Duo folder -> which display it shows, in the order the sync puts them in the version's
# one Duo group (inner first, as App Store Connect's website does). ScreenshotDisplayType has no
# Duo value, so these go through the App Asset Library (`sync_duo` below), whose IPHONE_DUO
# display class has a single placement group holding both displays' screenshots, at most 10 in
# all. The two are told apart by size, from the group's specs in the reference data.
DUO_FOLDERS = {"duo-inner-screenshots": "inner", "duo-outer-screenshots": "outer"}
DUO_SIZES = {"inner": {(2853, 2007), (2007, 2853)}, "outer": {(1398, 2034), (2034, 1398)}}
DUO_DISPLAY_CLASS = "IPHONE_DUO"
DUO_PLATFORM = "IPHONE_APP_STORE"
# The classic API shows the same group as a set of this type; the classic sync leaves it alone.
CLASSIC_DUO_TYPE = "APP_IPHONE_DUO"
# The version page's "Header and Search Results" art (iOS 27): file in a locale folder -> the
# App Asset Library placement type, one of each per localization, in the DEFAULT display class's
# group, as creative assets (all read from the reference data).
ART_FILES = {"header.png": "PRODUCT_PAGE_HEADER_ASSET",
             "search-result.png": "APP_STORE_SEARCH_RESULTS_ASSET"}
ART_LABELS = {"PRODUCT_PAGE_HEADER_ASSET": "header", "APP_STORE_SEARCH_RESULTS_ASSET": "search result"}
ART_DISPLAY_CLASS = "DEFAULT"
ART_PLATFORM = "ANY"
SCREENSHOT_LIMIT = 10  # per display type and localization
SCREENSHOT_SUFFIXES = (".png", ".jpg", ".jpeg")
SCREENSHOT_WAIT_SECONDS = 600


# A locale folder's name: an App Store Connect locale code (en-GB, ru, no, zh-Hans), which
# iap-review/ and in-app-event/ are not.
LOCALE_FOLDER = re.compile(r"[a-z]{2,3}(-[A-Za-z0-9]{2,4})?")


def listing_locales(root=LISTING_DIR):
    """The locale folders under root: named like a locale code and holding at least one listing
    text. A partial folder (en-AU and en-CA hold only keywords.txt) counts; listing_files
    completes it from its fallback."""
    return sorted(p.name for p in pathlib.Path(root).iterdir()
                  if p.is_dir() and LOCALE_FOLDER.fullmatch(p.name)
                  and any((p / name).is_file() for name in LISTING_FIELDS))


def read_listing_files(root, sources):
    """{"version": {attribute: text}, "info": {attribute: text}} from {file name: locale folder
    the file is read from}."""
    texts = {"version": {}, "info": {}}
    for name, source in sources.items():
        resource, attribute = LISTING_FIELDS[name]
        texts[resource][attribute] = (pathlib.Path(root) / source / name).read_text(
            encoding="utf-8").strip()
    return texts


def read_listing(folder):
    """{"version": {attribute: text}, "info": {attribute: text}} for the files folder has."""
    folder = pathlib.Path(folder)
    return read_listing_files(folder.parent, {name: folder.name for name in LISTING_FIELDS
                                              if (folder / name).is_file()})


def listing_source(locale, folders):
    """The folder a localization is based on: its own, or en-GB's for another English
    localization without one. None when the repo has nothing for it."""
    if locale in folders:
        return locale
    return fallback(locale) if fallback(locale) in folders else None


def listing_files(root, locale, folders):
    """{file name: locale folder} for each listing text a localization takes: its own folder's
    file, else its fallback's (en-GB's, for another English localization). So a partial folder
    (en-AU: keywords.txt) still gets a complete listing, and a localization created from it has
    every field the primary language has. A language without a fallback takes only its own."""
    sources = {}
    for name in LISTING_FIELDS:
        for source in (locale, fallback(locale)):
            if source in folders and (pathlib.Path(root) / source / name).is_file():
                sources[name] = source
                break
    return sources


def describe_sources(locale, sources):
    """The dry run's line on where a localization's texts come from; None when all are its own."""
    own, inherited = [], {}
    for name, source in sources.items():
        attribute = LISTING_FIELDS[name][1]
        if source == locale:
            own.append(attribute)
        else:
            inherited.setdefault(source, []).append(attribute)
    if not inherited:
        return None
    if not own:
        return f"  {locale} takes the {', '.join(sorted(inherited))} texts (no folder of its own)"
    return (f"  {locale}: {', '.join(own)} from its folder; "
            + "; ".join(f"{', '.join(fields)} inherited from {source}"
                        for source, fields in sorted(inherited.items())))


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


def url_status(url, timeout=20):
    """The HTTP status a GET of url ends with (redirects followed), or None when it can't be
    reached at all."""
    request = urllib.request.Request(url, headers={"User-Agent": "BestBefore-release-script"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status
    except urllib.error.HTTPError as error:
        error.close()
        return error.code
    except (urllib.error.URLError, OSError, ValueError):
        return None


def live_privacy_url(locale, url, status_of):
    """The privacy policy URL to set for a localization: url when it answers 200; otherwise the
    language chooser (PRIVACY_SITE) when that does, so a listing never points at a page that isn't
    published yet; None (leave App Store Connect's as it is) when neither answers."""
    status = status_of(url)
    if status == 200:
        return url
    said = f"HTTP {status}" if status else "no answer"
    if url != PRIVACY_SITE and status_of(PRIVACY_SITE) == 200:
        print(f"::warning::{locale}: the privacy policy page {url} isn't live ({said}); "
              f"using the language chooser {PRIVACY_SITE} until it is. Publish the page and "
              "sync the listing again (the next release does)")
        return PRIVACY_SITE
    print(f"::warning::{locale}: neither the privacy policy page {url} ({said}) nor the "
          f"language chooser {PRIVACY_SITE} answers; {PRIVACY_ATTRIBUTE} left as App Store "
          "Connect has it")
    return None


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
        copied = {k: (primary or {}).get("attributes", {}).get(k) for k in COPIED_ON_CREATE[resource]
                  if k not in wanted}
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


def sync_listing(asc, version_id, app_info_id, apply, root=LISTING_DIR, status_of=None):
    """Brings the version's and the app info's localizations in line with root's locale
    folders. Returns {locale: version localization id} as App Store Connect has them after it,
    and whether anything differed. status_of(url) -> HTTP status or None (url_status by default)
    checks each privacy policy page before it is set (live_privacy_url)."""
    checked = {}

    def status(url):
        if url not in checked:
            checked[url] = (status_of or url_status)(url)
        return checked[url]

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
        sources = listing_files(root, locale, folders)
        texts = read_listing_files(root, sources)
        if PRIVACY_ATTRIBUTE in texts["info"]:
            url = live_privacy_url(locale, texts["info"][PRIVACY_ATTRIBUTE], status)
            if url is None:
                del texts["info"][PRIVACY_ATTRIBUTE]
            else:
                texts["info"][PRIVACY_ATTRIBUTE] = url
        line = describe_sources(locale, sources)
        if line:
            print(line)
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


def review_notes(root=LISTING_DIR):
    """The App Review notes in the repo (store-listing/app-store/review-notes.txt), or None."""
    path = pathlib.Path(root) / REVIEW_NOTES_FILE
    return path.read_text(encoding="utf-8").strip() if path.is_file() else None


def sync_review_notes(asc, version_id, apply, root=LISTING_DIR):
    """Writes the version's App Review notes (appStoreReviewDetail.notes) from the repo, and
    nothing else of the review detail: the contact and demo-account fields stay as App Store
    Connect has them. Creates the review detail only when the version has none. Returns whether
    the notes differed."""
    wanted = review_notes(root)
    if wanted is None:
        print(f"  App Review notes: no {REVIEW_NOTES_FILE}; left as they are")
        return False
    try:
        detail = asc.call("GET", f"/appStoreVersions/{version_id}/appStoreReviewDetail").get("data")
    except AscError as error:
        if error.status != 404:
            raise
        detail = None
    if not detail:
        print(f"  App Review notes: no review detail yet; {'creating' if apply else 'would create'} "
              "one with the notes only")
        print(*describe_change("notes", "", wanted), sep="\n")
        if apply:
            asc.call("POST", "/appStoreReviewDetails", body={"data": {
                "type": "appStoreReviewDetails", "attributes": {"notes": wanted},
                "relationships": {"appStoreVersion": {"data": {"type": "appStoreVersions",
                                                               "id": version_id}}}}})
        return True
    current = ((detail.get("attributes") or {}).get("notes") or "").strip()
    if current == wanted:
        print("  App Review notes: up to date")
        return False
    print(f"  App Review notes: {'updating' if apply else 'would update'}")
    print(*describe_change("notes", current, wanted), sep="\n")
    if apply:
        asc.call("PATCH", f"/appStoreReviewDetails/{detail['id']}", body={"data": {
            "type": "appStoreReviewDetails", "id": detail["id"], "attributes": {"notes": wanted}}})
    return True


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
    return [local_image(path) for path in files]


def local_image(path):
    """One image with its MD5 (the checksum App Store Connect keeps as sourceFileChecksum)."""
    path = pathlib.Path(path)
    data = path.read_bytes()
    return {"name": path.name, "path": path, "size": len(data),
            "md5": hashlib.md5(data).hexdigest(), "dimensions": image_size(path)}


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


def send_parts(asc, reserved, shot):
    """Uploads a reserved file's bytes as its uploadOperations say."""
    data = pathlib.Path(shot["path"]).read_bytes()
    operations = (reserved.get("attributes") or {}).get("uploadOperations") or []
    if not operations:
        raise ListingError(f"App Store Connect gave no upload operations for {shot['path']}")
    for operation in operations:
        offset, length = int(operation.get("offset") or 0), int(operation["length"])
        asc.upload_part(operation, data[offset:offset + length])


def upload_screenshot(asc, set_id, shot):
    """Reserves, uploads and commits one screenshot (developer.apple.com/documentation/
    appstoreconnectapi/uploading-assets-to-app-store-connect). Returns its id."""
    reserved = asc.call("POST", "/appScreenshots", body={"data": {
        "type": "appScreenshots",
        "attributes": {"fileName": shot["name"], "fileSize": shot["size"]},
        "relationships": {"appScreenshotSet": {"data": {"type": "appScreenshotSets",
                                                        "id": set_id}}}}})["data"]
    send_parts(asc, reserved, shot)
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


# --- Through the App Asset Library: iPhone Duo screenshots, header and search-result art --------
#
# developer.apple.com/documentation/appstoreconnectapi/understanding-the-app-asset-library: an
# image is uploaded once into the app's library, and a placement puts it on a version
# localization with a placement type (screenshot, header...) and group (the display). The library
# and the set-based API are two views of the same screenshots (the classic APP_IPHONE_DUO set lists
# the IPHONE_DUO_PROFILE group's images under the same ids), so the classic sync leaving that set
# alone and this one touching only its own placement types and groups don't get in each other's
# way. The library keeps no checksum, so each image uploaded here carries the file's MD5 in its
# reference name; a placement whose image has none (one uploaded on the website) is replaced.
# Images are never deleted from the library, as an older version's placements may still use them;
# only placements are.

LIBRARY_READY_STATES = {"PREPARE_FOR_SUBMISSION", "READY_FOR_REVIEW", "WAITING_FOR_REVIEW",
                        "IN_REVIEW", "ACCEPTED", "APPROVED"}
# States in which an image can still be placed, or will be once processed.
LIBRARY_REUSABLE_STATES = LIBRARY_READY_STATES | {"UPLOAD_COMPLETE"}
MD5_TAG = re.compile(r"\bmd5:([0-9a-f]{32})\b")
# How far a file's width/height may be from the aspect ratio of a spec with a size range.
ASPECT_TOLERANCE = 0.01


def size_text(dimensions):
    return f"{dimensions[0]}×{dimensions[1]}" if dimensions else "?×?"


def duo_kind(dimensions):
    """"inner" or "outer" for an iPhone Duo screenshot's size, else None."""
    return next((kind for kind, sizes in DUO_SIZES.items() if tuple(dimensions or ()) in sizes), None)


def reference_md5(reference_name):
    match = MD5_TAG.search(reference_name or "")
    return match.group(1) if match else ""


def spec_fits(spec, dimensions, suffix=None):
    """Whether an image of that size (and file suffix) meets an imageSpecs entry: within its
    width and height ranges, at its aspect ratio, with one of its extensions."""
    if not dimensions:
        return False
    width, height = dimensions
    size = spec.get("dimensions") or {}
    if not (size.get("minWidth", 0) <= width <= size.get("maxWidth", width)
            and size.get("minHeight", 0) <= height <= size.get("maxHeight", height)):
        return False
    # A size range comes with an aspect ratio; a fixed size names one only loosely (Apple calls
    # 2853×2007 "3:2"), so it is checked only for a range.
    ratio = re.fullmatch(r"(\d+):(\d+)", spec.get("aspectRatio") or "")
    ranged = size.get("minWidth") != size.get("maxWidth") or size.get("minHeight") != size.get("maxHeight")
    if ranged and ratio:
        nominal = int(ratio.group(1)) / int(ratio.group(2))
        if abs(width / height - nominal) > ASPECT_TOLERANCE * nominal:
            return False
    extensions = spec.get("fileExtensions")
    return not (suffix and extensions and suffix.lower() not in extensions)


def spec_text(spec):
    size = spec.get("dimensions") or {}
    if size.get("minWidth") == size.get("maxWidth") and size.get("minHeight") == size.get("maxHeight"):
        sizes = size_text((size.get("minWidth"), size.get("minHeight")))
    else:
        sizes = (f"{size_text((size.get('minWidth'), size.get('minHeight')))} to "
                 f"{size_text((size.get('maxWidth'), size.get('maxHeight')))}")
    return f"{spec.get('shortName')} {sizes} {spec.get('aspectRatio') or ''}".strip()


class AssetLibrary:
    """What the library syncs need, read once per run: the library id, the reference data
    (placement groups, their specs and limits, the asset category each placement type takes) and
    the images that carry an MD5."""

    def __init__(self, asc, app_id):
        library = asc.call("GET", f"/apps/{app_id}/assetLibrary").get("data") or {}
        if not library.get("id"):
            raise ListingError("App Store Connect returned no asset library for the app")
        self.id = library["id"]
        data, _ = get_all(asc, "/appAssetLibraryRefData")
        self.ref = (data[0] if data else {}).get("attributes") or {}
        images, _ = get_all(asc, f"/appAssetLibraries/{self.id}/images", {"limit": 50})
        self.by_md5 = {}  # (md5, category) -> image
        for image in images:
            attrs = image.get("attributes") or {}
            md5 = reference_md5(attrs.get("referenceName"))
            if md5 and attrs.get("state") in LIBRARY_REUSABLE_STATES:
                self.by_md5.setdefault((md5, attrs.get("category")), image)

    def group(self, display_class, platform):
        """The one placement group of a display class on a platform."""
        groups = [g.get("placementProfileGroupId") for g in self.ref.get("placementProfileGroups") or []
                  if g.get("displayClassId") == display_class and g.get("platform") == platform]
        if len(groups) != 1:
            raise ListingError(f"The asset library's reference data has {len(groups)} placement "
                               f"groups for {display_class} on {platform}, not one: {groups}")
        return groups[0]

    def target(self, placement_type, group):
        """A placement type in a group: its specs, limit and the asset category it takes."""
        entry = next((t for t in self.ref.get("placementTypes") or []
                      if t.get("placementTypeId") == placement_type), None)
        if not entry:
            raise ListingError(f"The asset library's reference data has no {placement_type}")
        categories = entry.get("acceptsAssetCategories") or []
        if len(categories) != 1:
            raise ListingError(f"{placement_type} takes the asset categories {categories}, not one")
        spec_ids = set()
        for mapping in entry.get("specMappings") or []:
            if mapping.get("placementGroupId") == group:
                spec_ids |= set(mapping.get("specs") or [])
        specs = {s["specId"]: s for s in self.ref.get("imageSpecs") or [] if s.get("specId") in spec_ids}
        if not specs:
            raise ListingError(f"The asset library's reference data has no image specs for "
                               f"{placement_type} in {group}")
        limit = None
        for feature in self.ref.get("features") or []:
            if feature.get("featureId") != "APP_STORE_VERSIONS":
                continue
            for policy in feature.get("placementPolicies") or []:
                if policy.get("placementType") == placement_type:
                    for group_limit in policy.get("groupLimits") or []:
                        if group in (group_limit.get("groupIds") or []) and group_limit.get("maxCount"):
                            limit = int(group_limit["maxCount"])
        if limit is None:
            raise ListingError(f"The asset library's reference data has no limit for "
                               f"{placement_type} in {group} on App Store versions")
        return {"placement_type": placement_type, "group": group, "specs": specs,
                "limit": limit, "category": categories[0]}


def describe_target(target):
    return (f"{target['placement_type']} in {target['group']}, at most {target['limit']}, "
            f"{target['category']}, specs " + ", ".join(sorted(map(spec_text, target["specs"].values()))))


def local_duo(folder):
    """The locale folder's Duo screenshots, inner then outer, each in file-number order, with
    its display ("kind"). Sizes that don't fit their folder's display stop the sync."""
    shots = []
    for name, kind in DUO_FOLDERS.items():
        if not (folder / name).is_dir():
            continue
        for shot in local_screenshots(folder / name):
            if duo_kind(shot["dimensions"]) != kind:
                raise ListingError(
                    f"{folder.name}/{name}/{shot['name']} is {size_text(shot['dimensions'])}; the "
                    f"iPhone Duo {kind} display takes "
                    + " or ".join(size_text(s) for s in sorted(DUO_SIZES[kind])))
            shots.append({**shot, "kind": kind, "label": f"{folder.name}/{name}/{shot['name']}"})
    return shots


def has_screenshots(folder):
    return any((pathlib.Path(folder) / name).is_dir() for name in (*SCREENSHOT_SETS, *DUO_FOLDERS))


def screenshot_folder(root, locale):
    """The folder whose screenshot sets a localization gets: its own, or for a regional variant
    without any (es-MX, fr-CA) its language's (es-ES, fr-FR), which App Store Connect would not
    show there by itself. Another English localization keeps its own (empty) folder: App Store
    Connect shows the primary language's, en-GB's, without anything placed."""
    own = pathlib.Path(root) / locale
    regional = REGIONAL_FALLBACKS.get(locale)
    if not has_screenshots(own) and regional and has_screenshots(pathlib.Path(root) / regional):
        return pathlib.Path(root) / regional
    return own


def store_art(root, locale):
    """{placement type: local image} for the header and search-result art a localization takes:
    its own folder's files, or en-GB's for another English localization without them (as the
    listing texts; Apple's help documents no fallback for these assets, so they're placed)."""
    art = {}
    for name, placement_type in ART_FILES.items():
        for source in (locale, fallback(locale)):
            path = pathlib.Path(root) / source / name if source else None
            if path and path.is_file():
                art[placement_type] = {**local_image(path), "kind": ART_LABELS[placement_type],
                                       "label": f"{source}/{name}"}
                break
    return art


def remote_placements(asc, localization_id, placement_type, group, kind=lambda dimensions: None):
    """The localization's placements of one type in one group, in display order, shaped like
    remote_screenshot's entries for plan_screenshots: "md5" from the image's reference name
    (empty when it has none), "state" None for a usable placement."""
    placements, included = get_all(
        asc, f"/appStoreVersionLocalizations/{localization_id}/placements",
        {"filter[placementType]": placement_type, "filter[placementGroup]": group,
         "sort": "placementGroupPosition", "include": "image", "limit": 200})
    images = {i.get("id"): i.get("attributes") or {} for i in included}
    remote = []
    for placement in placements:
        attrs = placement.get("attributes") or {}
        image_id = (((placement.get("relationships") or {}).get("image") or {}).get("data") or {}).get("id")
        image = images.get(image_id) or {}
        asset = image.get("imageAsset") or {}
        dimensions = (asset.get("width"), asset.get("height")) if asset.get("width") else None
        usable = attrs.get("state") != "FAILED" and image.get("state") in LIBRARY_REUSABLE_STATES
        remote.append({
            "id": placement["id"], "image": image_id, "name": image.get("fileName") or "",
            "md5": reference_md5(image.get("referenceName")) if usable else "",
            "state": None if usable else f"{attrs.get('state')}/{image.get('state')}",
            "placement_state": attrs.get("state"), "image_state": image.get("state"),
            "reference": image.get("referenceName"), "spec": image.get("specId"),
            "dimensions": dimensions, "kind": kind(dimensions)})
    return remote


def upload_library_image(asc, library_id, shot, category, reference_name):
    """Reserves, uploads and commits one image in the asset library (developer.apple.com/
    documentation/appstoreconnectapi/uploading-and-managing-image-assets). Returns its id."""
    reserved = asc.call("POST", "/appAssetLibraryImages", body={"data": {
        "type": "appAssetLibraryImages",
        "attributes": {"fileName": shot["name"], "fileSize": shot["size"], "category": category,
                       "referenceName": reference_name},
        "relationships": {"assetLibrary": {"data": {"type": "appAssetLibraries",
                                                    "id": library_id}}}}})["data"]
    send_parts(asc, reserved, shot)
    asc.call("PATCH", f"/appAssetLibraryImages/{reserved['id']}", body={"data": {
        "type": "appAssetLibraryImages", "id": reserved["id"], "attributes": {"uploaded": True}}})
    return reserved["id"]


def wait_for_library_images(asc, pending, specs, timeout=SCREENSHOT_WAIT_SECONDS, interval=5):
    """Waits until every image ({id: shot}) is processed, and checks that App Store Connect
    matched it to one of the target's specs that the file's size meets."""
    deadline = time.time() + timeout
    pending = dict(pending)
    while pending:
        for image_id, shot in list(pending.items()):
            attrs = asc.call("GET", f"/appAssetLibraryImages/{image_id}")["data"].get("attributes") or {}
            if attrs.get("state") == "FAILED":
                raise ListingError(f"App Store Connect could not process {shot['path']}: "
                                   f"{json.dumps(attrs.get('stateDetails') or [])}")
            if attrs.get("state") in LIBRARY_READY_STATES:
                spec = specs.get(attrs.get("specId"))
                if not spec or not spec_fits(spec, shot["dimensions"]):
                    raise ListingError(
                        f"App Store Connect matched {shot['path']} to spec {attrs.get('specId')}, "
                        f"not to a spec of its placement that takes {size_text(shot['dimensions'])}")
                del pending[image_id]
        if not pending:
            return
        if time.time() > deadline:
            raise ListingError("Images still processing after "
                               f"{timeout // 60} min: "
                               + ", ".join(str(s["path"]) for s in pending.values()))
        time.sleep(interval)


def sync_placements(asc, library, where, localization_id, local, target, apply, kind=lambda d: None):
    """Makes one localization's placements of a placement type and group hold the local images
    ({path, name, size, md5, dimensions, kind, label}), in that order. Returns whether anything
    differed."""
    if len(local) > target["limit"]:
        raise ListingError(f"{where}: {len(local)} images; {target['placement_type']} in "
                           f"{target['group']} takes at most {target['limit']}")
    for shot in local:
        if not any(spec_fits(s, shot["dimensions"], pathlib.Path(shot["path"]).suffix)
                   for s in target["specs"].values()):
            raise ListingError(f"{shot['label']} is {size_text(shot['dimensions'])}; "
                               f"{describe_target(target)} has no spec for it")
    remote = remote_placements(asc, localization_id, target["placement_type"], target["group"], kind)
    plan = plan_screenshots(local, remote)
    names = {spec_id: spec.get("shortName") for spec_id, spec in target["specs"].items()}
    print(f"  {where}: {len(local)} in the repo, {len(remote)} placed in App Store Connect; "
          f"{describe_target(target)}")
    for shot in local:
        print(f"    repo {shot['kind']}: {shot['label']} {size_text(shot['dimensions'])} md5 {shot['md5']}")
    for r in remote:
        print(f"    App Store Connect {r['kind'] or 'unknown display'}: {r['name'] or '?'} "
              f"{size_text(r['dimensions'])} spec {names.get(r['spec'], r['spec'])}, "
              f"reference {r['reference']!r} (md5 {r['md5'] or 'none'}), placement "
              f"{r['placement_state']}, image {r['image_state']}")
    if not (plan["upload"] or plan["delete"] or plan["reorder"]):
        print("    up to date")
        return False

    def verb(doing, would):
        return doing if apply else would

    category = target["category"]
    if plan["delete"]:
        print(f"    {verb('removing', 'would remove')} the placements of: " + ", ".join(
            f"{r['kind'] or '?'} {r['name'] or r['id']} {size_text(r['dimensions'])} "
            f"(md5 {r['md5'] or 'not recorded'})" for r in plan["delete"])
            + " (their images stay in the library)")
    if plan["upload"]:
        print(f"    {verb('placing', 'would place')}: " + ", ".join(
            f"{local[i]['kind']} {local[i]['label']} ("
            + (f"library image {library.by_md5[(local[i]['md5'], category)]['id']} has it"
               if (local[i]["md5"], category) in library.by_md5 else "new upload") + ")"
            for i in plan["upload"]))
    if plan["keep"]:
        print("    unchanged: " + ", ".join(f"{local[i]['kind']} {local[i]['label']}"
                                           for i in sorted(plan["keep"])))
    ordered = len(local) > 1 and (plan["reorder"] or plan["upload"])
    if ordered:
        print(f"    {verb('ordering', 'would order')} as " + ", ".join(
            f"{s['kind']} {s['name']}" for s in local))
    if not apply:
        return True
    # Placements first, as a group holds a limited number; their images stay in the library.
    for r in plan["delete"]:
        asc.call("DELETE", f"/appAssetLibraryPlacements/{r['id']}")
    images, uploaded = {}, {}
    for index in plan["upload"]:
        shot = local[index]
        existing = library.by_md5.get((shot["md5"], category))
        if existing:
            images[index] = existing["id"]
            if (existing.get("attributes") or {}).get("state") not in LIBRARY_READY_STATES:
                uploaded[existing["id"]] = shot
        else:
            images[index] = upload_library_image(asc, library.id, shot, category,
                                                 f"{shot['label']} md5:{shot['md5']}")
            uploaded[images[index]] = shot
            library.by_md5[(shot["md5"], category)] = {
                "id": images[index], "attributes": {"state": "UPLOAD_COMPLETE"}}
    wait_for_library_images(asc, uploaded, target["specs"])
    for image_id, shot in uploaded.items():
        library.by_md5[(shot["md5"], category)] = {
            "id": image_id, "attributes": {"state": "PREPARE_FOR_SUBMISSION"}}
    ids = {i: r["id"] for i, r in plan["keep"].items()}
    for index in plan["upload"]:
        ids[index] = asc.call("POST", "/appAssetLibraryPlacements", body={"data": {
            "type": "appAssetLibraryPlacements",
            "attributes": {"placementType": target["placement_type"], "placementGroup": target["group"]},
            "relationships": {
                "image": {"data": {"type": "appAssetLibraryImages", "id": images[index]}},
                "appStoreVersionLocalization": {"data": {"type": "appStoreVersionLocalizations",
                                                         "id": localization_id}}}}})["data"]["id"]
    if ordered:
        asc.call("POST", "/appAssetLibraryPlacementOrderingRequests", body={"data": {
            "type": "appAssetLibraryPlacementOrderingRequests",
            "attributes": {"placementGroup": target["group"]},
            "relationships": {
                "orderedPlacements": {"data": [{"type": "appAssetLibraryPlacements", "id": ids[i]}
                                               for i in range(len(local))]},
                "appStoreVersionLocalization": {"data": {"type": "appStoreVersionLocalizations",
                                                         "id": localization_id}}}}})
    print("    done")
    return True


def sync_duo(asc, library, locale, localization_id, folder, apply):
    """Makes the localization's iPhone Duo group hold folder's duo-inner-screenshots/ and
    duo-outer-screenshots/, inner first, each in file-number order. Both displays share the one
    group and its limit; the specs tell them apart by size."""
    local = local_duo(folder)
    target = library.target("APP_SCREENSHOT", library.group(DUO_DISPLAY_CLASS, DUO_PLATFORM))
    where = f"{locale}/duo-*-screenshots ({target['group']})"
    if not local:
        print(f"::warning::{where}: no images; the group is left as it is")
        return False
    return sync_placements(asc, library, where, localization_id, local, target, apply, duo_kind)


def sync_store_art(asc, library, locale, localization_id, art, apply):
    """Places header.png and search-result.png ({placement type: local image}) on the
    localization, one each. Returns whether anything differed."""
    changed = False
    group = library.group(ART_DISPLAY_CLASS, ART_PLATFORM)
    for placement_type, shot in art.items():
        target = library.target(placement_type, group)
        where = f"{locale} {ART_LABELS[placement_type]} ({placement_type})"
        changed |= sync_placements(asc, library, where, localization_id, [shot], target, apply,
                                   lambda dimensions, label=ART_LABELS[placement_type]: label)
    return changed


def sync_screenshots(asc, localizations, apply, root=LISTING_DIR, app_id=None):
    """Syncs every locale folder's screenshot sets to its version localization ({locale: id}):
    the classic sets through appScreenshotSets; the iPhone Duo folders and the header and
    search-result art through the App Asset Library (which needs app_id). A localization without
    screenshot folders of its own (en-US, and the keywords-only en-AU and en-CA) shows the primary
    language's; the other English ones get en-GB's header and search-result art placed."""
    changed = False
    library = None
    locales = sorted(set(listing_locales(root)) | {l for l in localizations if fallback(l)})
    for locale in locales:
        folder = screenshot_folder(root, locale)
        present = {name: folder / name for name in SCREENSHOT_SETS if (folder / name).is_dir()}
        duo = [name for name in DUO_FOLDERS if (folder / name).is_dir()]
        art = store_art(root, locale)
        if folder.name != locale and (present or duo):
            print(f"  {locale}: no screenshot folders of its own; takes {folder.name}'s")
        if not present and not duo:
            print(f"  {locale}: no screenshot folders; App Store Connect shows the "
                  f"{FALLBACK_LOCALE} ones")
            if not art:
                continue
        if locale not in localizations:
            if apply:
                raise ListingError(f"The version has no {locale} localization for its screenshots; "
                                   "run `testflight.py listing --apply` first")
            print(f"  {locale}: the version has no localization yet (the listing step creates it); "
                  "every image would be placed")
            for name in (*present, *duo):
                print(f"    {name}: {len(local_screenshots(folder / name))} images")
            for shot in art.values():
                print(f"    {shot['kind']}: {shot['label']} {size_text(shot['dimensions'])}")
            changed = True
            continue
        if present or duo:
            sets = {}
            for item in asc.call("GET", f"/appStoreVersionLocalizations/{localizations[locale]}"
                                        "/appScreenshotSets", {"limit": 50}).get("data", []):
                sets[(item.get("attributes") or {}).get("screenshotDisplayType")] = item
            managed = {SCREENSHOT_SETS[name] for name in present} | ({CLASSIC_DUO_TYPE} if duo else set())
            others = sorted(t for t in sets if t not in managed)
            if others:
                print(f"  {locale}: also has {', '.join(map(str, others))} sets; left as they are")
            for name, path in present.items():
                changed |= sync_screenshot_set(asc, localizations[locale], sets, SCREENSHOT_SETS[name],
                                               path, apply)
        if duo or art:
            if library is None:
                if app_id is None:
                    raise ListingError("The iPhone Duo screenshots and the header and search-result "
                                       "art need the app id")
                library = AssetLibrary(asc, app_id)
            if duo:
                changed |= sync_duo(asc, library, locale, localizations[locale], folder, apply)
            if art:
                changed |= sync_store_art(asc, library, locale, localizations[locale], art, apply)
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
        # Only links back to the API itself, so the token never goes anywhere else.
        if not following or not following.startswith(API_ROOT + "/"):
            break
        parsed = urllib.parse.urlsplit(following[len(API_ROOT):])
        path, params = parsed.path, dict(urllib.parse.parse_qsl(parsed.query))
        if path.startswith("/v1/"):
            path = path[len("/v1"):]
    return data, included


# The App Asset Library display classes of the folders in store-listing/app-store/
# (developer.apple.com/documentation/appstoreconnectapi/appassetlibrarydisplayclass).
INSPECT_DISPLAY_CLASSES = ("IPHONE_DUO", "IPHONE_DYNAMIC_ISLAND_LARGE_DISPLAY",
                           "IPHONE_DYNAMIC_ISLAND_MEDIUM_DISPLAY", "IPAD_13_DISPLAY", "DEFAULT")
INSPECT_PLACEMENT_TYPES = ("APP_SCREENSHOT", *ART_FILES.values())


def strings_in(value):
    """Every string anywhere inside a JSON value."""
    if isinstance(value, str):
        return {value}
    if isinstance(value, dict):
        return set().union(*map(strings_in, value.values())) if value else set()
    if isinstance(value, list):
        return set().union(*map(strings_in, value)) if value else set()
    return set()


def without_upload_operations(value):
    """A JSON value with every uploadOperations (presigned upload URLs) left out."""
    if isinstance(value, dict):
        return {k: without_upload_operations(v) for k, v in value.items() if k != "uploadOperations"}
    if isinstance(value, list):
        return [without_upload_operations(v) for v in value]
    return value


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
            type_id = placement_type.get("placementTypeId")
            if type_id not in INSPECT_PLACEMENT_TYPES:
                continue
            dump(f"{type_id} accepts", placement_type.get("acceptsAssetCategories"))
            for mapping in placement_type.get("specMappings") or []:
                found = strings_in(mapping)
                if found & relevant_ids or "DUO" in json.dumps(mapping):
                    dump(f"{type_id} spec mapping", mapping)
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
        for name in ART_FILES:
            path = LISTING_DIR / locale / name
            if path.is_file():
                shot = local_image(path)
                print(f"  {locale}/{name}: {size_text(shot['dimensions'])} md5 {shot['md5']}")
        for name in (*SCREENSHOT_SETS, *DUO_FOLDERS):
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
            json.dumps(without_upload_operations(record), ensure_ascii=False, indent=1,
                       sort_keys=True), encoding="utf-8")
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
    changed |= sync_review_notes(asc, version["id"], apply)
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
    changed = sync_screenshots(asc, localizations, apply, app_id=app_id)
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
        sync_review_notes(asc, version["id"], apply=True)
        if screenshots:
            print("Applying the screenshots")
            sync_screenshots(asc, localizations, apply=True, app_id=app_id)
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
        return submit_for_review(asc, app_id, version, args.release_type, args.iaps_attached)
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

    return submit_for_review(asc, app_id, version, args.release_type, args.iaps_attached)


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


# --- In-app event: store-listing/app-store/in-app-event/ onto App Store Connect -----------------
#
# `testflight.py event` creates the in-app event the folder describes, or brings the existing one
# (found by its reference name, unique per app) in line with it: the event's settings and
# schedule (event.json), one localization per locale folder (name.txt, short_description.txt,
# long_description.txt) and each localization's event card and event details page images.
# Dry run unless --apply; only what differs is written, so a rerun changes nothing.
#
# The API (developer.apple.com/documentation/appstoreconnectapi/app-events-and-metadata):
# appEvents and appEventLocalizations hold the texts; the media go through the App Asset Library,
# as appEventScreenshots and appEventVideoClips are deprecated in its favour
# (developer.apple.com/documentation/appstoreconnectapi/migrating-to-the-app-asset-library): an
# image is uploaded once into the library (category CREATIVE_ASSETS) and placed on an event
# localization with the placement type EVENT_CARD_ASSET or EVENT_DETAILS_PAGE_ASSET, one asset per
# type ("In-app event localizations aren't orderable ... each in-app event placement type allows
# a single asset per group", placing-assets-on-your-app-store-surfaces). The placement group,
# specs, category and limit are read from the library's reference data (feature IN_APP_EVENTS),
# not hard-coded. Images carry their MD5 in the reference name, as the screenshot sync does.
#
# The event is never submitted for review here. --add-to-review puts it into a draft review
# submission without sending it: the one that holds the app version in preparation if there is
# one, else an open draft, else a new one. The release job's `appstore` then sends the version
# into that same submission (it uses an open draft when none holds the version), so the event is
# reviewed with the version that has the Recipe tab. Without the flag, the owner does it on the
# website (In-App Events > the event > Add for Review).

EVENT_DIR = LISTING_DIR / "in-app-event"
EVENT_CONFIG_FILE = "event.json"
EVENT_TEXT_FIELDS = {"name.txt": "name", "short_description.txt": "shortDescription",
                     "long_description.txt": "longDescription"}
EVENT_MEDIA_TYPES = {"event-card.png": "EVENT_CARD_ASSET",
                     "event-details.png": "EVENT_DETAILS_PAGE_ASSET"}
EVENT_MEDIA_LABELS = {"EVENT_CARD_ASSET": "event card", "EVENT_DETAILS_PAGE_ASSET": "event details page"}
EVENT_FEATURE = "IN_APP_EVENTS"
# event.json keys sent to the API as they are.
EVENT_SETTINGS = ("badge", "purpose", "priority", "purchaseRequirement", "deepLink", "primaryLocale")
# States in which the event and its localizations are written; in any other (in review, approved,
# published...) the command only compares. A rejected event is fixed and submitted again.
EVENT_EDITABLE_STATES = {"DRAFT", "REJECTED"}


def read_event(root=EVENT_DIR):
    """The repo's event: its settings, {locale: {attribute: text}} and {locale: {placement type:
    local image}} (another English locale without images takes en-GB's, as the listing does)."""
    root = pathlib.Path(root)
    config = json.loads((root / EVENT_CONFIG_FILE).read_text(encoding="utf-8"))
    locales = sorted(p.parent.name for p in root.glob("*/name.txt"))
    texts, media = {}, {}
    for locale in locales:
        texts[locale] = {attribute: (root / locale / name).read_text(encoding="utf-8").strip()
                         for name, attribute in EVENT_TEXT_FIELDS.items()
                         if (root / locale / name).is_file()}
        media[locale] = {}
        for name, placement_type in EVENT_MEDIA_TYPES.items():
            for source in (locale, fallback(locale)):
                path = root / source / name if source else None
                if path and path.is_file():
                    media[locale][placement_type] = {
                        **local_image(path), "kind": EVENT_MEDIA_LABELS[placement_type],
                        "label": f"in-app-event/{source}/{name}"}
                    break
    return {"config": config, "texts": texts, "media": media}


def check_event_files(root=EVENT_DIR):
    """scripts/check_store_listing.py's event checks as a gate, like check_listing_texts."""
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
    import check_store_listing
    with contextlib.redirect_stdout(io.StringIO()):
        problems = check_store_listing.check_in_app_event(root)
    if problems:
        raise ListingError("scripts/check_store_listing.py found problems in the in-app event:\n"
                           + "\n".join(f"- {p}" for p in problems))


def event_time(text):
    """A date-time from the API or the repo as an aware UTC datetime (the API writes them as
    2026-12-01T08:00:00.000+0000 or with Z), or None."""
    if not text:
        return None
    text = re.sub(r"\.\d+", "", str(text)).replace("Z", "+00:00")
    text = re.sub(r"([+-]\d{2})(\d{2})$", r"\1:\2", text)
    value = datetime.datetime.fromisoformat(text)
    return (value if value.tzinfo else value.replace(tzinfo=datetime.timezone.utc)).astimezone(
        datetime.timezone.utc)


def api_time(value):
    return value.strftime("%Y-%m-%dT%H:%M:%SZ")


def event_schedule(config):
    """(publishStart, eventStart, eventEnd) from event.json: the event starts on releaseDay at
    startTimeUtc, lasts durationDays and is published publishDaysBefore days before it starts.
    None while releaseDay is null."""
    if not config.get("releaseDay"):
        return None
    hours, minutes = map(int, config["startTimeUtc"].split(":"))
    start = datetime.datetime.strptime(config["releaseDay"], "%Y-%m-%d").replace(
        hour=hours, minute=minutes, tzinfo=datetime.timezone.utc)
    return (start - datetime.timedelta(days=config["publishDaysBefore"]), start,
            start + datetime.timedelta(days=config["durationDays"]))


def app_territories(asc, app_id):
    """The territories the app is available in, as 3-letter codes."""
    availability = asc.call("GET", f"/apps/{app_id}/appAvailabilityV2").get("data") or {}
    if not availability.get("id"):
        raise ListingError("App Store Connect returned no availability for the app")
    items, _ = get_all(asc, f"/v2/appAvailabilities/{availability['id']}/territoryAvailabilities",
                       {"include": "territory", "limit": 200})
    territories = set()
    for item in items:
        if (item.get("attributes") or {}).get("available"):
            territory = (((item.get("relationships") or {}).get("territory") or {}).get("data") or {})
            if territory.get("id"):
                territories.add(territory["id"])
    if not territories:
        raise ListingError("The app is available in no territory, so the event can't be scheduled")
    return sorted(territories)


def schedule_key(schedules):
    """Territory schedules in a comparable form: sorted (territories, publish, start, end)."""
    return sorted((tuple(sorted(s.get("territories") or [])), event_time(s.get("publishStart")),
                   event_time(s.get("eventStart")), event_time(s.get("eventEnd")))
                  for s in schedules or [])


def describe_schedules(schedules):
    if not schedules:
        return "none"
    return "; ".join(f"{len(territories)} territories, published {publish:%Y-%m-%d %H:%M}, "
                     f"{start:%Y-%m-%d %H:%M} to {end:%Y-%m-%d %H:%M} UTC"
                     if publish and start and end else f"{len(territories)} territories, incomplete"
                     for territories, publish, start, end in schedule_key(schedules))


def wanted_event_attributes(asc, app_id, config):
    """The appEvents attributes the repo asks for. The schedule is left out while releaseDay is
    null, so App Store Connect keeps whatever it has."""
    attributes = {key: config[key] for key in EVENT_SETTINGS}
    attributes["referenceName"] = config["referenceName"]
    schedule = event_schedule(config)
    if schedule:
        territories = (app_territories(asc, app_id) if config["territories"] == "app"
                       else sorted(config["territories"]))
        publish, start, end = schedule
        attributes["territorySchedules"] = [{
            "territories": territories, "publishStart": api_time(publish),
            "eventStart": api_time(start), "eventEnd": api_time(end)}]
    return attributes


def event_attribute_changes(current, wanted):
    """{attribute: (now, wanted)} for each wanted attribute App Store Connect has otherwise."""
    current = current or {}
    changes = {}
    for key, value in wanted.items():
        if key == "territorySchedules":
            if schedule_key(current.get(key)) != schedule_key(value):
                changes[key] = (current.get(key) or [], value)
        elif (current.get(key) or "") != value:
            changes[key] = (current.get(key) or "", value)
    return changes


def describe_event_change(attribute, old, new):
    if attribute == "territorySchedules":
        return [f"    schedule: {describe_schedules(old)} -> {describe_schedules(new)}"]
    return describe_change(attribute, old, new)


def find_event(asc, app_id, reference_name):
    events, _ = get_all(asc, f"/apps/{app_id}/appEvents", {"limit": 200})
    matches = [e for e in events if (e.get("attributes") or {}).get("referenceName") == reference_name]
    if len(matches) > 1:
        raise ListingError(f"App Store Connect has {len(matches)} events named {reference_name!r}")
    return matches[0] if matches else None


def sync_event(asc, app_id, config, apply):
    """Creates the event or updates its settings and schedule. Returns (event or None for one a
    dry run would create, editable, whether anything differs)."""
    wanted = wanted_event_attributes(asc, app_id, config)
    if not event_schedule(config):
        print("  schedule: releaseDay is null in event.json, so none is sent; set it before the "
              "event goes for review")
    event = find_event(asc, app_id, config["referenceName"])
    if event is None:
        print(f"  event {config['referenceName']!r}: {'creating' if apply else 'would create'}")
        for attribute, value in wanted.items():
            print(*describe_event_change(attribute, "" if attribute != "territorySchedules" else [],
                                         value), sep="\n")
        if not apply:
            return None, True, True
        created = asc.call("POST", "/appEvents", body={"data": {
            "type": "appEvents", "attributes": wanted,
            "relationships": {"app": {"data": {"type": "apps", "id": app_id}}}}})["data"]
        return created, True, True
    state = (event.get("attributes") or {}).get("eventState")
    editable = state in EVENT_EDITABLE_STATES
    changes = event_attribute_changes(event.get("attributes"), wanted)
    label = f"  event {config['referenceName']!r} ({event['id']}, {state})"
    if not changes:
        print(f"{label}: up to date")
        return event, editable, False
    print(f"{label}: {'updating' if apply and editable else 'would update' if editable else 'differs'}")
    for attribute, (old, new) in changes.items():
        print(*describe_event_change(attribute, old, new), sep="\n")
    if apply and editable:
        asc.call("PATCH", f"/appEvents/{event['id']}", body={"data": {
            "type": "appEvents", "id": event["id"],
            "attributes": {k: new for k, (_, new) in changes.items()}}})
    return event, editable, True


def sync_event_localizations(asc, event_id, texts, apply):
    """Creates or updates one localization per locale folder. Returns ({locale: id} as App Store
    Connect has them afterwards, whether anything differs). Localizations the repo has no folder
    for are left as they are."""
    existing = {}
    if event_id:
        items, _ = get_all(asc, f"/appEvents/{event_id}/localizations", {"limit": 50})
        existing = {(i.get("attributes") or {}).get("locale"): i for i in items}
    result, changed = {}, False
    for locale, wanted in sorted(texts.items()):
        current = existing.get(locale)
        if current is None:
            print(f"  localization {locale}: {'creating' if apply else 'would create'}")
            for attribute, text in wanted.items():
                print(*describe_change(attribute, "", text), sep="\n")
            changed = True
            if apply:
                result[locale] = asc.call("POST", "/appEventLocalizations", body={"data": {
                    "type": "appEventLocalizations", "attributes": {"locale": locale, **wanted},
                    "relationships": {"appEvent": {"data": {"type": "appEvents",
                                                            "id": event_id}}}}})["data"]["id"]
            continue
        result[locale] = current["id"]
        changes = field_changes(current.get("attributes"), wanted)
        if not changes:
            print(f"  localization {locale}: up to date")
            continue
        changed = True
        print(f"  localization {locale}: {'updating' if apply else 'would update'}")
        for attribute, (old, new) in changes.items():
            print(*describe_change(attribute, old, new), sep="\n")
        if apply:
            asc.call("PATCH", f"/appEventLocalizations/{current['id']}", body={"data": {
                "type": "appEventLocalizations", "id": current["id"],
                "attributes": {k: new for k, (_, new) in changes.items()}}})
    for locale in sorted(set(existing) - set(texts)):
        print(f"  localization {locale}: no folder in the repo; left as it is")
    return result, changed


def event_media_target(library, placement_type):
    """Where an event image goes, from the reference data: the one placement group that both
    maps specs for the placement type and has an IN_APP_EVENTS limit for it, with those specs,
    the limit and the asset category the type takes."""
    entry = next((t for t in library.ref.get("placementTypes") or []
                  if t.get("placementTypeId") == placement_type), None)
    if not entry:
        raise ListingError(f"The asset library's reference data has no {placement_type}")
    categories = entry.get("acceptsAssetCategories") or []
    if len(categories) != 1:
        raise ListingError(f"{placement_type} takes the asset categories {categories}, not one")
    mapped = {}
    for mapping in entry.get("specMappings") or []:
        mapped.setdefault(mapping.get("placementGroupId"), set()).update(mapping.get("specs") or [])
    limits = {}
    for feature in library.ref.get("features") or []:
        if feature.get("featureId") != EVENT_FEATURE:
            continue
        for policy in feature.get("placementPolicies") or []:
            if policy.get("placementType") == placement_type:
                for group_limit in policy.get("groupLimits") or []:
                    for group in group_limit.get("groupIds") or []:
                        if group_limit.get("maxCount"):
                            limits[group] = int(group_limit["maxCount"])
    groups = sorted(g for g in mapped if g in limits)
    if len(groups) != 1:
        raise ListingError(f"The asset library's reference data has {len(groups)} placement groups "
                           f"for {placement_type} on in-app events, not one: {groups}")
    group = groups[0]
    specs = {s["specId"]: s for s in library.ref.get("imageSpecs") or [] if s.get("specId") in mapped[group]}
    if not specs:
        raise ListingError(f"The asset library's reference data has no image specs for "
                           f"{placement_type} in {group}")
    return {"placement_type": placement_type, "group": group, "specs": specs,
            "limit": limits[group], "category": categories[0]}


def event_placements(asc, localization_id, target):
    """The event localization's placements of the target's type, shaped for plan_screenshots."""
    placements, included = get_all(
        asc, f"/appEventLocalizations/{localization_id}/placements",
        {"filter[placementType]": target["placement_type"], "filter[placementGroup]": target["group"],
         "include": "image", "limit": 50})
    images = {i.get("id"): i.get("attributes") or {} for i in included}
    remote = []
    for placement in placements:
        attrs = placement.get("attributes") or {}
        image_id = (((placement.get("relationships") or {}).get("image") or {}).get("data") or {}).get("id")
        image = images.get(image_id) or {}
        asset = image.get("imageAsset") or {}
        usable = attrs.get("state") != "FAILED" and image.get("state") in LIBRARY_REUSABLE_STATES
        remote.append({
            "id": placement["id"], "image": image_id, "name": image.get("fileName") or "",
            "md5": reference_md5(image.get("referenceName")) if usable else "",
            "state": None if usable else f"{attrs.get('state')}/{image.get('state')}",
            "dimensions": (asset.get("width"), asset.get("height")) if asset.get("width") else None})
    return remote


def sync_event_image(asc, library, locale, localization_id, shot, target, apply):
    """Makes the localization's one placement of the target's type show shot. Returns whether
    anything differs."""
    where = f"  {locale} {EVENT_MEDIA_LABELS[target['placement_type']]}"
    if not any(spec_fits(s, shot["dimensions"], pathlib.Path(shot["path"]).suffix)
               for s in target["specs"].values()):
        raise ListingError(f"{shot['label']} is {size_text(shot['dimensions'])}; "
                           f"{describe_target(target)} has no spec for it")
    category = target["category"]
    in_library = (shot["md5"], category) in library.by_md5
    source = "already in the library" if in_library else "new upload"
    if localization_id is None:
        print(f"{where}: {'placing' if apply else 'would place'} {shot['label']} "
              f"{size_text(shot['dimensions'])} ({source})")
        return True
    remote = event_placements(asc, localization_id, target)
    plan = plan_screenshots([shot], remote)
    if not (plan["upload"] or plan["delete"]):
        print(f"{where}: up to date ({shot['label']})")
        return False
    verb = "placing" if apply else "would place"
    replaced = ", ".join(f"{r['name'] or r['id']} (md5 {r['md5'] or 'not recorded'})" for r in plan["delete"])
    print(f"{where}: {verb} {shot['label']} {size_text(shot['dimensions'])} ({source})"
          + (f", replacing {replaced}" if replaced else ""))
    if not apply:
        return True
    for r in plan["delete"]:
        asc.call("DELETE", f"/appAssetLibraryPlacements/{r['id']}")
    existing = library.by_md5.get((shot["md5"], category))
    if existing:
        image_id = existing["id"]
        if (existing.get("attributes") or {}).get("state") not in LIBRARY_READY_STATES:
            wait_for_library_images(asc, {image_id: shot}, target["specs"])
    else:
        image_id = upload_library_image(asc, library.id, shot, category,
                                        f"{shot['label']} md5:{shot['md5']}")
        wait_for_library_images(asc, {image_id: shot}, target["specs"])
    library.by_md5[(shot["md5"], category)] = {"id": image_id,
                                               "attributes": {"state": "PREPARE_FOR_SUBMISSION"}}
    asc.call("POST", "/appAssetLibraryPlacements", body={"data": {
        "type": "appAssetLibraryPlacements",
        "attributes": {"placementType": target["placement_type"], "placementGroup": target["group"]},
        "relationships": {
            "image": {"data": {"type": "appAssetLibraryImages", "id": image_id}},
            "appEventLocalization": {"data": {"type": "appEventLocalizations",
                                              "id": localization_id}}}}})
    return True


def sync_event_media(asc, app_id, media, localizations, apply):
    """Places each locale's event card and details page image. Returns whether anything
    differs."""
    library = AssetLibrary(asc, app_id)
    targets = {t: event_media_target(library, t) for t in EVENT_MEDIA_TYPES.values()}
    for target in targets.values():
        print(f"  media: {describe_target(target)}")
    changed = False
    for locale, shots in sorted(media.items()):
        for placement_type, shot in shots.items():
            changed |= sync_event_image(asc, library, locale, localizations.get(locale), shot,
                                        targets[placement_type], apply)
    return changed


def event_submissions(asc, app_id, event_id):
    """The app's open review submissions, with the app versions and events each holds."""
    submissions = asc.call("GET", "/reviewSubmissions", {
        "filter[app]": app_id, "filter[platform]": "IOS",
        "filter[state]": OPEN_SUBMISSION_STATES}).get("data") or []
    result = []
    for submission in submissions:
        items, _ = get_all(asc, f"/reviewSubmissions/{submission['id']}/items",
                           {"include": "appStoreVersion,appEvent", "limit": 200})
        held = {"appStoreVersion": set(), "appEvent": set()}
        for item in items:
            for kind in held:
                linked = ((item.get("relationships") or {}).get(kind) or {}).get("data") or {}
                if linked.get("id"):
                    held[kind].add(linked["id"])
        result.append({"submission": submission, "versions": held["appStoreVersion"],
                       "events": held["appEvent"]})
    return result


EVENT_REVIEW_BY_HAND = (
    "The event is not submitted for review by this command. Either let the release job send it: "
    "re-run with --add-to-review before the release PR merges, and `testflight.py appstore` puts "
    "the version into the same draft submission and submits both; or, on the website: Apps > "
    "BestBefore > In-App Events > the event > Add for Review, choosing the draft submission that "
    "holds the version, then Submit for Review (with the version) once everything is ready.")


def add_event_to_review(asc, app_id, event_id, apply):
    """Puts the event into a draft review submission without sending it: the one holding the app
    version in preparation, else the first open one, else a new one."""
    submissions = event_submissions(asc, app_id, event_id)
    holding = [s for s in submissions if event_id in s["events"]]
    if holding:
        print(f"  review: already in draft submission {holding[0]['submission']['id']}; not sent")
        return False
    versions = app_store_versions(asc, app_id)
    preparing = {v["id"] for v in versions if version_state(v) in EDITABLE_STATES}
    chosen = next((s for s in submissions if s["versions"] & preparing), None)
    chosen = chosen or (submissions[0] if submissions else None)
    where = (f"draft submission {chosen['submission']['id']}" if chosen
             else "a new draft submission")
    print(f"  review: {'adding' if apply else 'would add'} the event to {where}; it is not sent")
    if not apply:
        return True
    submission = chosen["submission"] if chosen else asc.call("POST", "/reviewSubmissions", body={
        "data": {"type": "reviewSubmissions", "attributes": {"platform": "IOS"},
                 "relationships": {"app": {"data": {"type": "apps", "id": app_id}}}}})["data"]
    asc.call("POST", "/reviewSubmissionItems", body={"data": {
        "type": "reviewSubmissionItems", "relationships": {
            "reviewSubmission": {"data": {"type": "reviewSubmissions", "id": submission["id"]}},
            "appEvent": {"data": {"type": "appEvents", "id": event_id}}}}})
    return True


def run_event(asc, app_id, apply, add_to_review=False, root=EVENT_DIR):
    """The `event` command against an App Store Connect client (Asc, or a fake in the tests).
    Returns the exit code."""
    check_event_files(root)
    event_files = read_event(root)
    config = event_files["config"]
    requested = apply
    print(f"In-app event from {pathlib.Path(root).name}/: {', '.join(sorted(event_files['texts']))}")
    print("APPLY" if apply else "DRY RUN (pass --apply to write)")
    event, editable, changed = sync_event(asc, app_id, config, apply)
    if event is not None and not editable:
        state = (event.get("attributes") or {}).get("eventState")
        print(f"  The event is {state}, which App Store Connect doesn't let change; only compared")
        apply = False
    event_id = event["id"] if event else None
    localizations, differs = sync_event_localizations(asc, event_id, event_files["texts"], apply)
    changed |= differs
    changed |= sync_event_media(asc, app_id, event_files["media"], localizations, apply)
    state = (event.get("attributes") or {}).get("eventState") if event else "DRAFT"
    if add_to_review and state in EVENT_EDITABLE_STATES:
        if event_id is None:
            print("  review: would add the new event to a draft submission once it is created")
        else:
            changed |= add_event_to_review(asc, app_id, event_id, apply)
    if not changed:
        print("The in-app event is up to date")
    elif apply:
        print("Applied the differences above")
    elif event is not None and not editable:
        print("The differences above can't be written while the event is in this state")
    else:
        print("Pass --apply to write the differences above")
    if not add_to_review:
        print(EVENT_REVIEW_BY_HAND)
    return 1 if requested and changed and event is not None and not editable else 0


def cmd_event(args):
    if args.add_to_review and not args.apply:
        print("--add-to-review without --apply: showing what it would do")
    asc = Asc()
    return run_event(asc, asc.app_id(), args.apply, args.add_to_review)


def add_event_command(commands):
    event = commands.add_parser(
        "event", help="compare the in-app event in store-listing/app-store/in-app-event/ with App "
                      "Store Connect (dry run unless --apply); never submits it")
    event.add_argument("--apply", action="store_true", help="create or update the event")
    event.add_argument("--add-to-review", action="store_true",
                       help="also put the event into a draft review submission (with the version "
                            "in preparation if there is one) without sending it")
    event.set_defaults(func=cmd_event)


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
    appstore.add_argument("--iaps-attached", action="store_true",
                          help="submit even though in-app purchases still to be reviewed couldn't "
                               "be added or seen in the submission, after adding them by hand")
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
    iaps = commands.add_parser("iaps", help="read-only: the in-app purchases and what `appstore` "
                                            "would do with them")
    iaps.add_argument("--version", help="the version to plan for (default: the one in "
                                        "preparation, else the newest)")
    iaps.set_defaults(func=cmd_iaps)
    prices = commands.add_parser("iap-prices", help="set the in-app purchases' prices to "
                                                    "scripts/pack_prices.py's (dry run unless --apply)")
    prices.add_argument("products", nargs="*", metavar="PRODUCT_ID", help="only these; default all")
    prices.add_argument("--apply", action="store_true", help="set the new price schedules")
    prices.set_defaults(func=cmd_iap_prices)
    commands.add_parser("status", help="list groups and recent builds").set_defaults(func=cmd_status)
    revoke = commands.add_parser("revoke-ci-certs", help="revoke development certificates left by CI runs")
    revoke.set_defaults(func=cmd_revoke_ci_certs)
    add_event_command(commands)
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
