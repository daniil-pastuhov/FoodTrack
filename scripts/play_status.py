#!/usr/bin/env python3
"""Prints, read-only, what Google Play holds for the app's tracks.

    pip install google-api-python-client google-auth-httplib2
    PLAY_SERVICE_ACCOUNT_JSON="$(cat key.json)" python3 scripts/play_status.py

For every track (internal, alpha = closed testing, any other closed track, production): its
releases (name, version codes, status, rollout fraction, countries a release targets), the
track's country availability, and for testing tracks the Google Groups the API returns as its
testers. It also prints the app's default language.

It writes nothing. Play's API reads go through an "edit", so it opens one and always deletes it
again without committing (as scripts/play_listing.py does on a dry run); nothing in the console
changes. It authenticates exactly like scripts/play_upload.py (`publisher()`, the key JSON in
PLAY_SERVICE_ACCOUNT_JSON, never a file path).

What the API can't show (see docs/play-production.md, "Checking the tester count"): how many
testers have opted in, and for how long; the members of email lists (edits.testers returns only
Google Groups); whether the 14-day requirement is met; the production-access application;
pre-registration; the review state of a release or of the app. Those are only in Play Console.
"""
import argparse
import pathlib
import sys

PROTECTED_TRACKS = ("production",)
KNOWN_TRACK_NAMES = {
    "internal": "internal testing",
    "alpha": "closed testing (the default closed track)",
    "beta": "open testing",
    "production": "production",
}


class ApiError(Exception):
    """A read Play refused; the message is printed in place of the section."""


def _call(request, error_types):
    try:
        return request.execute()
    except error_types as error:  # HttpError in real use; any exception type in tests
        raise ApiError(_short_error(error)) from error


def _short_error(error):
    status = getattr(getattr(error, "resp", None), "status", None)
    reason = getattr(error, "reason", None) or str(error)
    reason = " ".join(str(reason).split())
    if len(reason) > 160:
        reason = reason[:157] + "..."
    return f"HTTP {status}: {reason}" if status else reason


def collect_status(edits, package, edit_id, error_types=(Exception,)):
    """Everything this script prints, as plain data, read through an open edit."""
    status = {"package": package, "tracks": []}
    try:
        details = _call(edits.details().get(packageName=package, editId=edit_id), error_types)
        # Only the default language: the contact email and phone are not printed.
        status["defaultLanguage"] = details.get("defaultLanguage")
    except ApiError as error:
        status["defaultLanguage"] = None
        status["detailsError"] = str(error)

    tracks = _call(edits.tracks().list(packageName=package, editId=edit_id), error_types)
    for track in sorted(tracks.get("tracks", []), key=lambda t: _track_order(t.get("track", ""))):
        name = track.get("track", "?")
        entry = {"track": name, "releases": track.get("releases", [])}
        if name != "internal":
            # Country targeting does not apply to internal testing (Play Console Help 9874837).
            try:
                entry["countries"] = _call(edits.countryavailability().get(
                    packageName=package, editId=edit_id, track=name), error_types)
            except ApiError as error:
                entry["countriesError"] = str(error)
        if name not in PROTECTED_TRACKS:
            try:
                entry["testers"] = _call(edits.testers().get(
                    packageName=package, editId=edit_id, track=name), error_types)
            except ApiError as error:
                entry["testersError"] = str(error)
        status["tracks"].append(entry)
    return status


def _track_order(name):
    order = ["internal", "alpha", "beta", "production"]
    return (order.index(name) if name in order else 2, name)


def _countries_line(availability):
    if availability is None:
        return None
    codes = sorted(c.get("countryCode", "?") for c in availability.get("countries", []))
    parts = [f"{len(codes)} countries/regions" + (f": {', '.join(codes)}" if codes else "")]
    if availability.get("restOfWorld"):
        parts.append("plus rest of world")
    if availability.get("syncWithProduction"):
        parts.append("synced with production")
    return "; ".join(parts)


def format_status(status):
    lines = [f"Google Play: {status['package']} (read-only; the edit used to read is discarded)"]
    if status.get("detailsError"):
        lines.append(f"Default language: not readable ({status['detailsError']})")
    else:
        lines.append(f"Default language: {status.get('defaultLanguage') or '?'}")
    if not status["tracks"]:
        lines.append("No tracks.")
    for entry in status["tracks"]:
        name = entry["track"]
        label = KNOWN_TRACK_NAMES.get(name, "closed testing (custom track)")
        lines.append("")
        lines.append(f"Track '{name}' ({label})")
        releases = entry["releases"]
        if not releases:
            lines.append("  Releases: none")
        for release in releases:
            codes = ", ".join(str(c) for c in release.get("versionCodes", [])) or "no version codes"
            text = f"  Release '{release.get('name', '?')}': {release.get('status', '?')}, version codes {codes}"
            if release.get("userFraction") is not None:
                text += f", rollout {float(release['userFraction']) * 100:g} %"
            lines.append(text)
            targeting = release.get("countryTargeting")
            if targeting:
                codes = sorted(c for c in targeting.get("countries", []))
                lines.append(f"    targets {len(codes)} countries/regions"
                             + (" plus rest of world" if targeting.get("includeRestOfWorld") else ""))
            notes = release.get("releaseNotes") or []
            if notes:
                lines.append("    release notes in " + ", ".join(sorted(n.get("language", "?") for n in notes)))
        if "countries" in entry:
            lines.append(f"  Availability: {_countries_line(entry['countries'])}")
        elif "countriesError" in entry:
            lines.append(f"  Availability: not readable ({entry['countriesError']})")
        if "testers" in entry:
            groups = entry["testers"].get("googleGroups") or []
            lines.append("  Testers (Google Groups): " + (", ".join(groups) if groups else "none"))
            lines.append("    Email lists, and how many testers have opted in, are not in the API:"
                         " Play Console only.")
        elif "testersError" in entry:
            lines.append(f"  Testers: not readable ({entry['testersError']})")
    lines.append("")
    lines.append("Not shown by the API: opted-in tester count and days, email-list members, the"
                 " production-access application, pre-registration, review states."
                 " See docs/play-production.md.")
    return lines


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.parse_args()

    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
    from play_upload import PACKAGE_NAME, publisher
    from googleapiclient.errors import HttpError

    api = publisher(timeout=120)
    if api is None:
        print("PLAY_SERVICE_ACCOUNT_JSON is not set", file=sys.stderr)
        return 2
    edits = api.edits()
    edit_id = edits.insert(packageName=PACKAGE_NAME, body={}).execute()["id"]
    try:
        status = collect_status(edits, PACKAGE_NAME, edit_id, error_types=(HttpError,))
    except ApiError as error:
        print(f"::error::Google Play refused to list the tracks: {error}", file=sys.stderr)
        return 1
    finally:
        edits.delete(packageName=PACKAGE_NAME, editId=edit_id).execute()
    print("\n".join(format_status(status)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
