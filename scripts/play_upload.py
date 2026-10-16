#!/usr/bin/env python3
"""Uploads an Android App Bundle to a Google Play track, or promotes one to another track.

Used by .github/workflows/ci.yml in the release flow (see "Releasing" in README.md), and
runnable by hand:

    pip install google-api-python-client google-auth-httplib2
    PLAY_SERVICE_ACCOUNT_JSON="$(cat key.json)" python3 scripts/play_upload.py \\
        --aab androidApp/build/outputs/bundle/release/androidApp-release.aab --track internal \\
        --release-notes-dir release-notes/v1.3.0 --release-name "1.3.0 (161)"

    # Every push to a release/vX.Y.Z branch uploads to internal as above; merging it moves the
    # build testers have been trying from internal to closed testing, instead of rebuilding:
    PLAY_SERVICE_ACCOUNT_JSON="$(cat key.json)" python3 scripts/play_upload.py \\
        --promote-from internal --version 1.3.0 --track alpha --release-notes-dir release-notes/v1.3.0

Kept in the repo rather than using a third-party GitHub Action so the Play service-account key
and the signing key are only ever handled by code in this repository.

--status defaults to "completed", which rolls the release out to the track's testers. Before the
app's first review Play only accepted "draft" ("Only releases with status draft may be created on
draft app").
"""
import argparse
import json
import os
import pathlib
import sys

import google_auth_httplib2
import httplib2
from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError
from googleapiclient.http import MediaFileUpload

PACKAGE_NAME = "com.foodtracker.android"
# Play's limit for "What's new in this release", per language.
RELEASE_NOTES_LIMIT = 500


def read_release_notes(notes_dir):
    """One release-notes entry per <play-locale>.txt file in notes_dir, e.g. en-GB.txt."""
    notes = []
    for path in sorted(pathlib.Path(notes_dir).glob("*.txt")):
        text = path.read_text(encoding="utf-8").strip()
        if len(text) > RELEASE_NOTES_LIMIT:
            raise SystemExit(f"{path} has {len(text)} characters; Play allows {RELEASE_NOTES_LIMIT}")
        notes.append({"language": path.stem, "text": text})
    if not notes:
        raise SystemExit(f"No release notes in {notes_dir}")
    return notes


def find_release(edits, edit_id, track, version):
    """The newest release on track whose name starts with "<version> (", as the upload names it."""
    releases = edits.tracks().get(packageName=PACKAGE_NAME, editId=edit_id, track=track) \
        .execute().get("releases", [])
    matching = [r for r in releases if r.get("name", "").startswith(f"{version} (")]
    if not matching:
        names = ", ".join(r.get("name", "?") for r in releases) or "none"
        raise SystemExit(f"No release of {version} on '{track}' (releases there: {names})")
    return max(matching, key=lambda r: max(int(code) for code in r["versionCodes"]))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--aab", help="path to the .aab to upload")
    parser.add_argument("--promote-from", metavar="TRACK",
                        help="instead of uploading, copy --version's release from this track")
    parser.add_argument("--version", help="with --promote-from: the versionName to promote")
    parser.add_argument("--track", default="internal", help="Play track (internal, alpha, beta, production)")
    parser.add_argument("--status", default="completed", choices=["draft", "completed"])
    parser.add_argument("--mapping", help="R8 mapping.txt, so Play can deobfuscate crash reports")
    parser.add_argument("--release-name", help="defaults to the uploaded version code")
    parser.add_argument("--release-notes-dir", help="folder of <play-locale>.txt release notes")
    parser.add_argument("--validate-only", action="store_true",
                        help="run every step, then discard the edit instead of committing it")
    args = parser.parse_args()
    if bool(args.aab) == bool(args.promote_from):
        parser.error("pass exactly one of --aab and --promote-from")
    if args.promote_from and not args.version:
        parser.error("--promote-from needs --version")
    notes = read_release_notes(args.release_notes_dir) if args.release_notes_dir else None

    key = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON")
    if not key:
        print("PLAY_SERVICE_ACCOUNT_JSON is not set", file=sys.stderr)
        return 2
    credentials = service_account.Credentials.from_service_account_info(
        json.loads(key), scopes=["https://www.googleapis.com/auth/androidpublisher"]
    )
    # The default 60 s socket timeout is shorter than a ~60 MB bundle upload takes.
    http = google_auth_httplib2.AuthorizedHttp(credentials, http=httplib2.Http(timeout=600))
    edits = build("androidpublisher", "v3", http=http, cache_discovery=False).edits()

    edit_id = edits.insert(packageName=PACKAGE_NAME, body={}).execute()["id"]
    try:
        if args.promote_from:
            source = find_release(edits, edit_id, args.promote_from, args.version)
            version_codes = source["versionCodes"]
            name = args.release_name or source["name"]
            notes = notes or source.get("releaseNotes")
            print(f"Promoting '{source['name']}' from '{args.promote_from}'")
        else:
            bundle = edits.bundles().upload(
                packageName=PACKAGE_NAME, editId=edit_id,
                media_body=MediaFileUpload(args.aab, mimetype="application/octet-stream", resumable=True),
            ).execute()
            version_code = bundle["versionCode"]
            print(f"Uploaded bundle, versionCode {version_code}")

            if args.mapping:
                edits.deobfuscationfiles().upload(
                    packageName=PACKAGE_NAME, editId=edit_id, apkVersionCode=version_code,
                    deobfuscationFileType="proguard",
                    media_body=MediaFileUpload(args.mapping, mimetype="application/octet-stream"),
                ).execute()
                print("Uploaded R8 mapping")
            version_codes = [str(version_code)]
            name = args.release_name or str(version_code)

        release = {"name": name, "versionCodes": version_codes, "status": args.status}
        if notes:
            release["releaseNotes"] = notes
        edits.tracks().update(
            packageName=PACKAGE_NAME, editId=edit_id, track=args.track,
            body={"track": args.track, "releases": [release]},
        ).execute()

        if args.validate_only:
            edits.validate(packageName=PACKAGE_NAME, editId=edit_id).execute()
            edits.delete(packageName=PACKAGE_NAME, editId=edit_id).execute()
            print(f"Validated a {args.status} release '{name}' on '{args.track}'; edit discarded")
        else:
            edits.commit(packageName=PACKAGE_NAME, editId=edit_id).execute()
            print(f"Released '{name}' to '{args.track}' as {args.status}")
        return 0
    except (HttpError, SystemExit) as error:
        edits.delete(packageName=PACKAGE_NAME, editId=edit_id).execute()
        if isinstance(error, SystemExit):
            raise
        print(f"Google Play rejected the upload: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
