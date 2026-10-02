#!/usr/bin/env python3
"""Uploads an Android App Bundle to a Google Play track.

Used by .github/workflows/ci.yml on every push to main, and runnable by hand:

    pip install google-api-python-client google-auth-httplib2
    PLAY_SERVICE_ACCOUNT_JSON="$(cat key.json)" python3 scripts/play_upload.py \\
        --aab androidApp/build/outputs/bundle/release/androidApp-release.aab --track internal

Kept in the repo rather than using a third-party GitHub Action so the Play service-account key
and the signing key are only ever handled by code in this repository.

--status defaults to "draft" because Play only accepts draft releases from the API until the
app has been through its first review ("Only releases with status draft may be created on
draft app"); after that, "completed" rolls the release out to the track's testers.
"""
import argparse
import json
import os
import sys

import google_auth_httplib2
import httplib2
from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError
from googleapiclient.http import MediaFileUpload

PACKAGE_NAME = "com.foodtracker.android"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--aab", required=True, help="path to the .aab to upload")
    parser.add_argument("--track", default="internal", help="Play track (internal, alpha, beta, production)")
    parser.add_argument("--status", default="draft", choices=["draft", "completed"])
    parser.add_argument("--mapping", help="R8 mapping.txt, so Play can deobfuscate crash reports")
    parser.add_argument("--release-name", help="defaults to the uploaded version code")
    parser.add_argument("--validate-only", action="store_true",
                        help="run every step, then discard the edit instead of committing it")
    args = parser.parse_args()

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

        release = {
            "name": args.release_name or str(version_code),
            "versionCodes": [str(version_code)],
            "status": args.status,
        }
        edits.tracks().update(
            packageName=PACKAGE_NAME, editId=edit_id, track=args.track,
            body={"track": args.track, "releases": [release]},
        ).execute()

        if args.validate_only:
            edits.validate(packageName=PACKAGE_NAME, editId=edit_id).execute()
            edits.delete(packageName=PACKAGE_NAME, editId=edit_id).execute()
            print(f"Validated a {args.status} release of {version_code} on '{args.track}'; edit discarded")
        else:
            edits.commit(packageName=PACKAGE_NAME, editId=edit_id).execute()
            print(f"Released {version_code} to '{args.track}' as {args.status}")
        return 0
    except HttpError as error:
        edits.delete(packageName=PACKAGE_NAME, editId=edit_id).execute()
        print(f"Google Play rejected the upload: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
