#!/usr/bin/env python3
"""Checks store-listing/google-play/data-safety.csv and sends it to Play's Data safety form.

Dry run by default: it validates the CSV offline and prints what the form would declare. Nothing
is sent, and no credential is needed, without --apply:

    python3 scripts/play_data_safety.py                    # validate + summary, offline
    python3 scripts/play_data_safety.py --csv other.csv    # another file, e.g. a fresh export
    pip install google-api-python-client google-auth-httplib2
    PLAY_SERVICE_ACCOUNT_JSON="$(cat key.json)" python3 scripts/play_data_safety.py --apply

--apply posts the file to androidpublisher v3 `applications.dataSafety`
(POST .../applications/{packageName}/dataSafety, body {"safetyLabels": "<the CSV>"}), which
replaces every answer of the form, like Play Console's "Import from CSV". The API has no read
call, so this script cannot show what Play has now or diff against it: compare with Play Console
> App content > Data safety (or its "Export to CSV") yourself. Authenticates exactly like
scripts/play_upload.py (its `publisher()`); the key never lives in the repo.

The CSV has Play Console's export format: the header below and one row per answer choice
(Question ID, Response ID, Response value TRUE/FALSE/blank or text, Answer requirement, label).
The layout here (every question and response ID, in export order) was taken from Google's sample
CSV linked from https://support.google.com/googleplay/android-developer/answer/10787469 ("Import
or export your form responses") and from real exports in public repositories; if Play changes the
form, export the current CSV from Play Console and update GENERAL / DATA_TYPES to match first.
"""
import argparse
import csv
import io
import pathlib
import sys

SCRIPTS = pathlib.Path(__file__).resolve().parent
CSV_PATH = SCRIPTS.parent / "store-listing" / "google-play" / "data-safety.csv"

HEADER = ["Question ID (machine readable)", "Response ID (machine readable)", "Response value",
          "Answer requirement", "Human-friendly question label"]

# Questions before the data types: (question ID, response ID, requirement, label).
ACM = "Which of the following methods of account creation does your app support? Select all that apply / "
DELETION = "Do you provide a way for users to request that their data is deleted? / "
OUTSIDE = "How are these accounts created? / "
GENERAL = [
    ("PSL_DATA_COLLECTION_COLLECTS_PERSONAL_DATA", "", "REQUIRED",
     "Does your app collect or share any of the required user data types?"),
    ("PSL_DATA_COLLECTION_ENCRYPTED_IN_TRANSIT", "", "MAYBE_REQUIRED",
     "Is all of the user data collected by your app encrypted in transit?"),
    ("PSL_SUPPORTED_ACCOUNT_CREATION_METHODS", "PSL_ACM_USER_ID_PASSWORD", "MULTIPLE_CHOICE", ACM + "Username and password"),
    ("PSL_SUPPORTED_ACCOUNT_CREATION_METHODS", "PSL_ACM_USER_ID_OTHER_AUTH", "MULTIPLE_CHOICE",
     ACM + "Username and other authentication"),
    ("PSL_SUPPORTED_ACCOUNT_CREATION_METHODS", "PSL_ACM_USER_ID_PASSWORD_OTHER_AUTH", "MULTIPLE_CHOICE",
     ACM + "Username, password, and other authentication"),
    ("PSL_SUPPORTED_ACCOUNT_CREATION_METHODS", "PSL_ACM_OAUTH", "MULTIPLE_CHOICE", ACM + "OAuth"),
    ("PSL_SUPPORTED_ACCOUNT_CREATION_METHODS", "PSL_ACM_OTHER", "MULTIPLE_CHOICE", ACM + "Other"),
    ("PSL_SUPPORTED_ACCOUNT_CREATION_METHODS", "PSL_ACM_NONE", "MULTIPLE_CHOICE",
     ACM + "My app does not allow users to create an account"),
    ("PSL_ACM_SPECIFY", "", "MAYBE_REQUIRED", "Describe the method of account creation that your app supports"),
    ("PSL_ACCOUNT_DELETION_URL", "", "MAYBE_REQUIRED",
     "Add a link that users can use to request that their account and associated data is deleted"),
    ("PSL_SUPPORT_DATA_DELETION_BY_USER", "DATA_DELETION_YES", "SINGLE_CHOICE", DELETION + "Yes"),
    ("PSL_SUPPORT_DATA_DELETION_BY_USER", "DATA_DELETION_NO", "SINGLE_CHOICE", DELETION + "No"),
    ("PSL_SUPPORT_DATA_DELETION_BY_USER", "DATA_DELETION_NO_AUTO_DELETED", "SINGLE_CHOICE",
     DELETION + "No, but user data is automatically deleted within 90 days"),
    ("PSL_DATA_DELETION_URL", "", "MAYBE_REQUIRED", "Delete data URL"),
    ("PSL_DATA_COLLECTION_COMPLIES_FAMILY_POLICY", "", "OPTIONAL",
     "Only answer this question if you've indicated that your app's target age group includes children, or "
     "you've opted into the Designed for Families program. If either of the above is true, you are required to "
     "follow the Google Play Families Policy (https://support.google.com/googleplay/android-developer/answer/9893335). "
     "Do you want to let users know about this commitment in the Data safety section on your store listing?"),
    ("PSL_INDEPENDENTLY_VALIDATED", "", "OPTIONAL",
     "Has your app successfully completed an independent security review, according to the Mobile Application "
     "Security Assessment (MASA) framework? Only answer \"yes\" if the review is in good standing."),
    ("PSL_UPI_BADGE_OPT_IN", "", "OPTIONAL", "Do you want to show this badge on your store listing?"),
    ("PSL_HAS_OUTSIDE_APP_ACCOUNTS", "", "OPTIONAL",
     "Can users login to your app with accounts created outside of the app?"),
    ("PSL_OUTSIDE_APP_ACCOUNT_TYPES", "PSL_LOGIN_WITH_OUTSIDE_APP_ID", "MULTIPLE_CHOICE",
     OUTSIDE + "Out of app identification (e.g. SIM binding, service subscription)"),
    ("PSL_OUTSIDE_APP_ACCOUNT_TYPES", "PSL_LOGIN_THROUGH_EMPLOYMENT_OR_ENTERPRISE_ACCOUNT", "MULTIPLE_CHOICE",
     OUTSIDE + "Through employment, or enterprise accounts"),
    ("PSL_OUTSIDE_APP_ACCOUNT_TYPES", "PSL_OUTSIDE_APP_ACCOUNT_TYPE_OTHER", "MULTIPLE_CHOICE", OUTSIDE + "Other"),
    ("PSL_OUTSIDE_APP_ACCOUNT_TYPE_SPECIFY", "", "MAYBE_REQUIRED", "Describe how these accounts are created"),
]
TEXT_QUESTIONS = {"PSL_ACM_SPECIFY", "PSL_ACCOUNT_DELETION_URL", "PSL_DATA_DELETION_URL",
                  "PSL_OUTSIDE_APP_ACCOUNT_TYPE_SPECIFY"}
URL_QUESTIONS = {"PSL_ACCOUNT_DELETION_URL", "PSL_DATA_DELETION_URL"}

# Data types in export order: (category question ID, data type ID, category label, type label).
DATA_TYPES = [
    ("PSL_DATA_TYPES_PERSONAL", t, "Personal info", label) for t, label in [
        ("PSL_NAME", "Name"), ("PSL_EMAIL", "Email address"), ("PSL_USER_ACCOUNT", "User IDs"),
        ("PSL_ADDRESS", "Address"), ("PSL_PHONE", "Phone number"), ("PSL_RACE_ETHNICITY", "Race and ethnicity"),
        ("PSL_POLITICAL_RELIGIOUS", "Political or religious beliefs"),
        ("PSL_SEXUAL_ORIENTATION_GENDER_IDENTITY", "Sexual orientation"), ("PSL_OTHER_PERSONAL", "Other info")]
] + [
    ("PSL_DATA_TYPES_FINANCIAL", t, "Financial info", label) for t, label in [
        ("PSL_CREDIT_DEBIT_BANK_ACCOUNT_NUMBER", "User payment info"), ("PSL_PURCHASE_HISTORY", "Purchase history"),
        ("PSL_CREDIT_SCORE", "Credit score"), ("PSL_OTHER", "Other financial info")]
] + [
    ("PSL_DATA_TYPES_LOCATION", "PSL_APPROX_LOCATION", "Location", "Approximate location"),
    ("PSL_DATA_TYPES_LOCATION", "PSL_PRECISE_LOCATION", "Location", "Precise location"),
    ("PSL_DATA_TYPES_SEARCH_AND_BROWSING", "PSL_WEB_BROWSING_HISTORY", "Web browsing", "Web browsing history"),
    ("PSL_DATA_TYPES_EMAIL_AND_TEXT", "PSL_EMAILS", "Messages", "Emails"),
    ("PSL_DATA_TYPES_EMAIL_AND_TEXT", "PSL_SMS_CALL_LOG", "Messages", "SMS or MMS"),
    ("PSL_DATA_TYPES_EMAIL_AND_TEXT", "PSL_OTHER_MESSAGES", "Messages", "Other in-app messages"),
    ("PSL_DATA_TYPES_PHOTOS_AND_VIDEOS", "PSL_PHOTOS", "Photos and videos", "Photos"),
    ("PSL_DATA_TYPES_PHOTOS_AND_VIDEOS", "PSL_VIDEOS", "Photos and videos", "Videos"),
    ("PSL_DATA_TYPES_AUDIO", "PSL_AUDIO", "Audio files", "Voice or sound recordings"),
    ("PSL_DATA_TYPES_AUDIO", "PSL_MUSIC", "Audio files", "Music files"),
    ("PSL_DATA_TYPES_AUDIO", "PSL_OTHER_AUDIO", "Audio files", "Other audio files"),
    ("PSL_DATA_TYPES_HEALTH_AND_FITNESS", "PSL_HEALTH", "Health and fitness", "Health info"),
    ("PSL_DATA_TYPES_HEALTH_AND_FITNESS", "PSL_FITNESS", "Health and fitness", "Fitness info"),
    ("PSL_DATA_TYPES_CONTACTS", "PSL_CONTACTS", "Contacts", "Contacts"),
    ("PSL_DATA_TYPES_CALENDAR", "PSL_CALENDAR", "Calendar", "Calendar events"),
    ("PSL_DATA_TYPES_APP_PERFORMANCE", "PSL_CRASH_LOGS", "App info and performance", "Crash logs"),
    ("PSL_DATA_TYPES_APP_PERFORMANCE", "PSL_PERFORMANCE_DIAGNOSTICS", "App info and performance", "Diagnostics"),
    ("PSL_DATA_TYPES_APP_PERFORMANCE", "PSL_OTHER_PERFORMANCE", "App info and performance",
     "Other app performance data"),
    ("PSL_DATA_TYPES_FILES_AND_DOCS", "PSL_FILES_AND_DOCS", "Files and docs", "Files and docs"),
    ("PSL_DATA_TYPES_APP_ACTIVITY", "PSL_USER_INTERACTION", "App activity", "App interactions"),
    ("PSL_DATA_TYPES_APP_ACTIVITY", "PSL_IN_APP_SEARCH_HISTORY", "App activity", "In-app search history"),
    ("PSL_DATA_TYPES_APP_ACTIVITY", "PSL_APPS_ON_DEVICE", "App activity", "Installed apps"),
    ("PSL_DATA_TYPES_APP_ACTIVITY", "PSL_USER_GENERATED_CONTENT", "App activity", "Other user-generated content"),
    ("PSL_DATA_TYPES_APP_ACTIVITY", "PSL_OTHER_APP_ACTIVITY", "App activity", "Other actions"),
    ("PSL_DATA_TYPES_IDENTIFIERS", "PSL_DEVICE_ID", "Device or other IDs", "Device or other IDs"),
]
TYPE_LABELS = {t: f"{category} > {label}" for _, t, category, label in DATA_TYPES}

COLLECTED, SHARED = "PSL_DATA_USAGE_ONLY_COLLECTED", "PSL_DATA_USAGE_ONLY_SHARED"
OPTIONAL, REQUIRED = "PSL_DATA_USAGE_USER_CONTROL_OPTIONAL", "PSL_DATA_USAGE_USER_CONTROL_REQUIRED"
PURPOSES = [("PSL_APP_FUNCTIONALITY", "App functionality"), ("PSL_ANALYTICS", "Analytics"),
            ("PSL_DEVELOPER_COMMUNICATIONS", "Developer communications"),
            ("PSL_FRAUD_PREVENTION_SECURITY", "Fraud prevention, security, and compliance"),
            ("PSL_ADVERTISING", "Advertising or marketing"), ("PSL_PERSONALIZATION", "Personalization"),
            ("PSL_ACCOUNT_MANAGEMENT", "Account management")]
PURPOSE_LABELS = dict(PURPOSES)
SHARING_Q, EPHEMERAL_Q, CONTROL_Q = ("PSL_DATA_USAGE_COLLECTION_AND_SHARING", "PSL_DATA_USAGE_EPHEMERAL",
                                     "DATA_USAGE_USER_CONTROL")
COLLECTION_PURPOSE_Q, SHARING_PURPOSE_Q = "DATA_USAGE_COLLECTION_PURPOSE", "DATA_USAGE_SHARING_PURPOSE"


def usage_question(data_type, sub):
    return f"PSL_DATA_USAGE_RESPONSES:{data_type}:{sub}"


def layout():
    """Every row of the form in export order: [(question ID, response ID, requirement, label)]."""
    rows = list(GENERAL)
    rows += [(category, t, "MULTIPLE_CHOICE", f"{cat_label} / {label}")
             for category, t, cat_label, label in DATA_TYPES]
    for _, t, _, label in DATA_TYPES:
        head = f"Data usage and handling ({label}) / "
        both = head + "Is this data collected, shared, or both? / "
        control = head + "Is this data required for your app, or can users choose whether it's collected? / "
        rows += [
            (usage_question(t, SHARING_Q), COLLECTED, "MULTIPLE_CHOICE", both + "Collected"),
            (usage_question(t, SHARING_Q), SHARED, "MULTIPLE_CHOICE", both + "Shared"),
            (usage_question(t, EPHEMERAL_Q), "", "MAYBE_REQUIRED", head + "Is this data processed ephemerally?"),
            (usage_question(t, CONTROL_Q), OPTIONAL, "SINGLE_CHOICE",
             control + "Users can choose whether this data is collected"),
            (usage_question(t, CONTROL_Q), REQUIRED, "SINGLE_CHOICE",
             control + "Data collection is required (users can't turn off this data collection)"),
        ]
        for question, why in ((COLLECTION_PURPOSE_Q, "collected"), (SHARING_PURPOSE_Q, "shared")):
            rows += [(usage_question(t, question), p, "MULTIPLE_CHOICE",
                      f"{head}Why is this user data {why}? Select all that apply. / {p_label}")
                     for p, p_label in PURPOSES]
    return rows


class Form:
    """The parsed CSV: answers[(question, response)] = value as written ('' when blank)."""

    def __init__(self, answers):
        self.answers = answers

    def value(self, question, response=""):
        return self.answers.get((question, response), "").strip()

    def true(self, question, response=""):
        return self.value(question, response).upper() == "TRUE"

    def chosen(self, question, responses):
        return [r for r in responses if self.true(question, r)]

    def usage(self, data_type, sub, response=""):
        return self.value(usage_question(data_type, sub), response)

    def usage_true(self, data_type, sub, response=""):
        return self.true(usage_question(data_type, sub), response)

    def purposes(self, data_type, question):
        return [p for p, _ in PURPOSES if self.usage_true(data_type, question, p)]

    def selected_types(self):
        return [t for category, t, _, _ in DATA_TYPES if self.true(category, t)]


def parse(text):
    """(form, problems): the rows of a CSV in the export format, checked against layout()."""
    problems = []
    reader = csv.reader(io.StringIO(text.lstrip("﻿")))
    rows = list(reader)
    if not rows or [c.strip() for c in rows[0]] != HEADER:
        return Form({}), [f"the header must be exactly: {','.join(HEADER)}"]
    expected = {(q, r): req for q, r, req, _ in layout()}
    answers = {}
    for number, row in enumerate(rows[1:], start=2):
        if not any(cell.strip() for cell in row):
            continue
        if len(row) != len(HEADER):
            problems.append(f"line {number}: {len(row)} columns, expected {len(HEADER)}")
            continue
        question, response, value, requirement = (c.strip() for c in row[:4])
        key = (question, response)
        if key not in expected:
            problems.append(f"line {number}: unknown question/response {question} / {response or '(none)'}")
            continue
        if key in answers:
            problems.append(f"line {number}: {question} / {response or '(none)'} appears twice")
            continue
        if requirement != expected[key]:
            problems.append(f"line {number}: {question} is {expected[key]}, not {requirement or '(blank)'}")
        if question not in TEXT_QUESTIONS and value.upper() not in ("", "TRUE", "FALSE"):
            problems.append(f"line {number}: {question} takes TRUE, FALSE or blank, not {value!r}")
        answers[key] = value
    missing = [k for k in expected if k not in answers]
    if missing:
        problems.append(f"{len(missing)} rows of the form are missing, e.g. {missing[0][0]} / "
                        f"{missing[0][1] or '(none)'}; Play's export has every row, answered or not")
    return Form(answers), problems


def check(form):
    """(problems, warnings): what Play would refuse or what contradicts itself."""
    problems, warnings = [], []
    collects = form.value("PSL_DATA_COLLECTION_COLLECTS_PERSONAL_DATA").upper()
    if collects not in ("TRUE", "FALSE"):
        problems.append("PSL_DATA_COLLECTION_COLLECTS_PERSONAL_DATA (collects or shares data?) must be TRUE or FALSE")
    selected = form.selected_types()
    if collects == "TRUE":
        if not selected:
            problems.append("the form says data is collected or shared but selects no data type")
        if form.value("PSL_DATA_COLLECTION_ENCRYPTED_IN_TRANSIT").upper() not in ("TRUE", "FALSE"):
            problems.append("PSL_DATA_COLLECTION_ENCRYPTED_IN_TRANSIT must be answered when data is collected")
        deletion = form.chosen("PSL_SUPPORT_DATA_DELETION_BY_USER",
                               ["DATA_DELETION_YES", "DATA_DELETION_NO", "DATA_DELETION_NO_AUTO_DELETED"])
        if len(deletion) != 1:
            problems.append("PSL_SUPPORT_DATA_DELETION_BY_USER needs exactly one TRUE")
    elif collects == "FALSE" and selected:
        problems.append("the form says no data is collected or shared but selects data types")

    methods = form.chosen("PSL_SUPPORTED_ACCOUNT_CREATION_METHODS", [
        r for q, r, _, _ in GENERAL if q == "PSL_SUPPORTED_ACCOUNT_CREATION_METHODS"])
    account_url = form.value("PSL_ACCOUNT_DELETION_URL")
    if not methods:
        problems.append("PSL_SUPPORTED_ACCOUNT_CREATION_METHODS needs at least one TRUE (PSL_ACM_NONE if none)")
    elif "PSL_ACM_NONE" in methods:
        if len(methods) > 1:
            problems.append("PSL_ACM_NONE (no accounts) can't be chosen with an account-creation method")
        if account_url:
            problems.append("PSL_ACCOUNT_DELETION_URL must be blank when the app has no accounts "
                            "(Play: \"You cannot answer PSL_ACCOUNT_DELETION_URL.\")")
    elif not account_url:
        problems.append("the app lets users create an account, so PSL_ACCOUNT_DELETION_URL is required")
    if "PSL_ACM_OTHER" in methods and not form.value("PSL_ACM_SPECIFY"):
        problems.append("PSL_ACM_OTHER needs PSL_ACM_SPECIFY (describe the method)")
    for question in URL_QUESTIONS:
        url = form.value(question)
        if url and not url.startswith("https://"):
            problems.append(f"{question} must be an https:// URL, not {url!r}")
    if form.true("PSL_SUPPORT_DATA_DELETION_BY_USER", "DATA_DELETION_YES") and not form.value("PSL_DATA_DELETION_URL"):
        warnings.append("deletion requests are offered but PSL_DATA_DELETION_URL is blank")
    outside = form.chosen("PSL_OUTSIDE_APP_ACCOUNT_TYPES", [
        r for q, r, _, _ in GENERAL if q == "PSL_OUTSIDE_APP_ACCOUNT_TYPES"])
    if form.true("PSL_HAS_OUTSIDE_APP_ACCOUNTS") and not outside:
        problems.append("PSL_HAS_OUTSIDE_APP_ACCOUNTS is TRUE but no PSL_OUTSIDE_APP_ACCOUNT_TYPES is chosen")
    if outside and not form.true("PSL_HAS_OUTSIDE_APP_ACCOUNTS"):
        problems.append("PSL_OUTSIDE_APP_ACCOUNT_TYPES are chosen but PSL_HAS_OUTSIDE_APP_ACCOUNTS is not TRUE")

    for _, t, _, _ in DATA_TYPES:
        name = TYPE_LABELS[t]
        answered = [(q, r) for (q, r), v in form.answers.items()
                    if q.startswith(f"PSL_DATA_USAGE_RESPONSES:{t}:") and v.strip()]
        if t not in selected:
            if answered:
                problems.append(f"{name} is not selected but has answers ({answered[0][0].split(':')[2]})")
            continue
        collected = form.usage_true(t, SHARING_Q, COLLECTED)
        shared = form.usage_true(t, SHARING_Q, SHARED)
        if not (collected or shared):
            problems.append(f"{name}: say whether it is collected, shared or both")
        if form.usage(t, EPHEMERAL_Q).upper() not in ("TRUE", "FALSE") and collected:
            problems.append(f"{name}: \"processed ephemerally?\" must be TRUE or FALSE")
        if len([r for r in (OPTIONAL, REQUIRED) if form.usage_true(t, CONTROL_Q, r)]) != 1:
            problems.append(f"{name}: choose exactly one of required / optional")
        collection_purposes = form.purposes(t, COLLECTION_PURPOSE_Q)
        sharing_purposes = form.purposes(t, SHARING_PURPOSE_Q)
        if collected and not collection_purposes:
            problems.append(f"{name}: collected, but no purpose for collecting it")
        if not collected and collection_purposes:
            problems.append(f"{name}: has collection purposes but is not marked collected")
        if shared and not sharing_purposes:
            problems.append(f"{name}: shared, but no purpose for sharing it")
        if not shared and sharing_purposes:
            problems.append(f"{name}: has sharing purposes but is not marked shared")
    return problems, warnings


def summary(form):
    """The form as a reader would check it, one line per answer that is set."""
    def yes_no(question):
        value = form.value(question).upper()
        return {"TRUE": "Yes", "FALSE": "No"}.get(value, "(blank)")

    methods = [r.replace("PSL_ACM_", "") for q, r, _, _ in GENERAL
               if q == "PSL_SUPPORTED_ACCOUNT_CREATION_METHODS" and form.true(q, r)]
    deletion = [r for q, r, _, _ in GENERAL if q == "PSL_SUPPORT_DATA_DELETION_BY_USER" and form.true(q, r)]
    lines = [
        f"Collects or shares user data: {yes_no('PSL_DATA_COLLECTION_COLLECTS_PERSONAL_DATA')}",
        f"Encrypted in transit: {yes_no('PSL_DATA_COLLECTION_ENCRYPTED_IN_TRANSIT')}",
        f"Account creation: {', '.join(methods) or '(blank)'}",
        f"Account deletion URL: {form.value('PSL_ACCOUNT_DELETION_URL') or '(blank)'}",
        f"Users can request data deletion: {', '.join(deletion) or '(blank)'}",
        f"Data deletion URL: {form.value('PSL_DATA_DELETION_URL') or '(blank)'}",
        f"Accounts created outside the app: {yes_no('PSL_HAS_OUTSIDE_APP_ACCOUNTS')}",
        "Data types:",
    ]
    selected = form.selected_types()
    for t in selected:
        how = [w for w, r in (("collected", COLLECTED), ("shared", SHARED)) if form.usage_true(t, SHARING_Q, r)]
        control = "optional" if form.usage_true(t, CONTROL_Q, OPTIONAL) else \
            "required" if form.usage_true(t, CONTROL_Q, REQUIRED) else "?"
        ephemeral = {"TRUE": "ephemeral", "FALSE": "not ephemeral"}.get(form.usage(t, EPHEMERAL_Q).upper(), "ephemeral ?")
        lines.append(f"  {TYPE_LABELS[t]}: {' and '.join(how) or '?'}, {control}, {ephemeral}")
        for question, why in ((COLLECTION_PURPOSE_Q, "collected for"), (SHARING_PURPOSE_Q, "shared for")):
            purposes = form.purposes(t, question)
            if purposes:
                lines.append(f"      {why}: {', '.join(PURPOSE_LABELS[p] for p in purposes)}")
    if not selected:
        lines.append("  (none)")
    lines.append(f"Not declared: {len(DATA_TYPES) - len(selected)} other data types")
    return lines


def main(argv=None, api_factory=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--csv", type=pathlib.Path, default=CSV_PATH, help=f"default {CSV_PATH.name} in the repo")
    parser.add_argument("--apply", action="store_true",
                        help="post the CSV to Play (replaces the whole form); without it nothing is sent")
    args = parser.parse_args(argv)
    try:
        text = args.csv.read_text(encoding="utf-8")
    except OSError as error:
        print(f"::error::Cannot read {args.csv}: {error}", file=sys.stderr)
        return 1
    form, problems = parse(text)
    more, warnings = check(form) if not problems else ([], [])
    problems += more
    print(f"{args.csv}: {len(form.answers)} rows")
    if problems:
        for problem in problems:
            print(f"::error::{problem}", file=sys.stderr)
        print("Not valid; nothing was sent", file=sys.stderr)
        return 1
    for warning in warnings:
        print(f"warning: {warning}")
    print(*summary(form), sep="\n")
    print("Play's API has no call that reads the Data safety form back, so this cannot show what is "
          "live now or diff against it; compare with Play Console > App content > Data safety.")
    if not args.apply:
        print("DRY RUN: valid; nothing was sent (pass --apply to replace the form on Play)")
        return 0

    sys.path.insert(0, str(SCRIPTS))
    from play_upload import PACKAGE_NAME, publisher
    api = (api_factory or publisher)(timeout=60)
    if api is None:
        print("PLAY_SERVICE_ACCOUNT_JSON is not set", file=sys.stderr)
        return 2
    try:
        api.applications().dataSafety(packageName=PACKAGE_NAME, body={"safetyLabels": text}).execute()
    except Exception as error:  # googleapiclient's HttpError, without importing it for the dry run
        print(f"::error::Play rejected the Data safety form; nothing changed: {error}", file=sys.stderr)
        return 1
    print(f"APPLIED: Play's Data safety form for {PACKAGE_NAME} now has these answers; Play reviews them "
          "with the next app review")
    return 0


if __name__ == "__main__":
    sys.exit(main())
