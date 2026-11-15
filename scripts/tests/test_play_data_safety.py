"""Tests for scripts/play_data_safety.py: validation offline, and --apply against a fake
androidpublisher client. No Google library or credential is needed.

    python3 -m unittest discover -s scripts/tests
"""
import contextlib
import csv
import io
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))
import play_data_safety as pds  # noqa: E402

REPO_CSV = pds.CSV_PATH.read_text(encoding="utf-8")


def rows_of(text):
    return list(csv.reader(io.StringIO(text)))


def text_of(rows):
    out = io.StringIO()
    csv.writer(out, lineterminator="\n").writerows(rows)
    return out.getvalue()


def edited(changes, text=REPO_CSV):
    """The repo CSV with {(question, response): value} changed."""
    rows = rows_of(text)
    for row in rows[1:]:
        if (row[0], row[1]) in changes:
            row[2] = changes.pop((row[0], row[1]))
    assert not changes, f"no such rows: {changes}"
    return text_of(rows)


def problems_of(text):
    form, problems = pds.parse(text)
    return problems or pds.check(form)[0]


def usage(data_type, sub):
    return pds.usage_question(data_type, sub)


class FakeRequest:
    def __init__(self, error=None):
        self.error = error

    def execute(self):
        if self.error:
            raise self.error
        return {}


class FakeApplications:
    def __init__(self, calls, error):
        self.calls, self.error = calls, error

    def dataSafety(self, **kwargs):
        self.calls.append(kwargs)
        return FakeRequest(self.error)


class FakeApi:
    def __init__(self, error=None):
        self.calls, self.error = [], error

    def applications(self):
        return FakeApplications(self.calls, self.error)


def run(argv, api=None):
    factory_calls = []

    def factory(**kwargs):
        factory_calls.append(kwargs)
        return api

    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = pds.main(argv, api_factory=factory)
    return code, out.getvalue(), err.getvalue(), factory_calls


class LayoutTests(unittest.TestCase):
    def test_layout_has_every_row_once(self):
        keys = [(q, r) for q, r, _, _ in pds.layout()]
        self.assertEqual(len(keys), 782)  # the size of a current Play Console export
        self.assertEqual(len(set(keys)), len(keys))
        self.assertEqual(len(pds.DATA_TYPES), 38)

    def test_repo_csv_is_the_layout_in_order(self):
        rows = rows_of(REPO_CSV)
        self.assertEqual(rows[0], pds.HEADER)
        self.assertEqual([(r[0], r[1], r[3]) for r in rows[1:]],
                         [(q, r, req) for q, r, req, _ in pds.layout()])


class RepoCsvTests(unittest.TestCase):
    def setUp(self):
        self.form, problems = pds.parse(REPO_CSV)
        self.assertEqual(problems, [])

    def test_is_valid(self):
        self.assertEqual(pds.check(self.form), ([], []))

    def test_declares_what_the_app_sends(self):
        self.assertEqual(self.form.selected_types(), [
            "PSL_NAME", "PSL_EMAIL", "PSL_USER_ACCOUNT", "PSL_PURCHASE_HISTORY", "PSL_HEALTH",
            "PSL_CRASH_LOGS", "PSL_PERFORMANCE_DIAGNOSTICS", "PSL_USER_GENERATED_CONTENT", "PSL_DEVICE_ID"])

    def test_nothing_is_shared_and_no_ads(self):
        for t in self.form.selected_types():
            self.assertFalse(self.form.usage_true(t, pds.SHARING_Q, pds.SHARED), t)
            self.assertNotIn("PSL_ADVERTISING", self.form.purposes(t, pds.COLLECTION_PURPOSE_Q), t)

    def test_recipe_data_is_optional_and_crash_reports_required(self):
        for t in ("PSL_NAME", "PSL_EMAIL", "PSL_USER_ACCOUNT", "PSL_PURCHASE_HISTORY", "PSL_HEALTH",
                  "PSL_USER_GENERATED_CONTENT"):
            self.assertTrue(self.form.usage_true(t, pds.CONTROL_Q, pds.OPTIONAL), t)
        for t in ("PSL_CRASH_LOGS", "PSL_PERFORMANCE_DIAGNOSTICS", "PSL_DEVICE_ID"):
            self.assertTrue(self.form.usage_true(t, pds.CONTROL_Q, pds.REQUIRED), t)

    def test_no_location(self):
        self.assertNotIn("PSL_APPROX_LOCATION", self.form.selected_types())
        self.assertNotIn("PSL_PRECISE_LOCATION", self.form.selected_types())


class ValidationTests(unittest.TestCase):
    def test_wrong_header(self):
        rows = rows_of(REPO_CSV)
        rows[0][0] = "Question ID"
        self.assertIn("header", problems_of(text_of(rows))[0])

    def test_unknown_id(self):
        rows = rows_of(REPO_CSV)
        rows.append(["PSL_MADE_UP", "", "TRUE", "OPTIONAL", "?"])
        self.assertTrue(any("unknown question" in p for p in problems_of(text_of(rows))))

    def test_missing_row(self):
        rows = rows_of(REPO_CSV)
        del rows[5]
        self.assertTrue(any("missing" in p for p in problems_of(text_of(rows))))

    def test_duplicate_row(self):
        rows = rows_of(REPO_CSV)
        rows.append(rows[1])
        self.assertTrue(any("twice" in p for p in problems_of(text_of(rows))))

    def test_wrong_requirement(self):
        rows = rows_of(REPO_CSV)
        rows[1][3] = "OPTIONAL"
        self.assertTrue(any("is REQUIRED" in p for p in problems_of(text_of(rows))))

    def test_value_must_be_boolean(self):
        text = edited({("PSL_DATA_COLLECTION_ENCRYPTED_IN_TRANSIT", ""): "yes"})
        self.assertTrue(any("TRUE, FALSE or blank" in p for p in problems_of(text)))

    def test_lower_case_booleans_are_accepted(self):
        text = edited({("PSL_DATA_COLLECTION_ENCRYPTED_IN_TRANSIT", ""): "true"})
        self.assertEqual(problems_of(text), [])

    def test_collected_type_needs_a_purpose(self):
        text = edited({(usage("PSL_HEALTH", pds.COLLECTION_PURPOSE_Q), "PSL_APP_FUNCTIONALITY"): ""})
        self.assertTrue(any("Health info: collected, but no purpose" in p for p in problems_of(text)))

    def test_sharing_purpose_without_sharing(self):
        text = edited({(usage("PSL_EMAIL", pds.SHARING_PURPOSE_Q), "PSL_ANALYTICS"): "TRUE"})
        self.assertTrue(any("not marked shared" in p for p in problems_of(text)))

    def test_shared_type_needs_a_sharing_purpose(self):
        text = edited({(usage("PSL_EMAIL", pds.SHARING_Q), pds.SHARED): "TRUE"})
        self.assertTrue(any("shared, but no purpose" in p for p in problems_of(text)))

    def test_required_or_optional_is_single_choice(self):
        text = edited({(usage("PSL_EMAIL", pds.CONTROL_Q), pds.REQUIRED): "TRUE"})
        self.assertTrue(any("exactly one of required / optional" in p for p in problems_of(text)))

    def test_ephemeral_must_be_answered(self):
        text = edited({(usage("PSL_EMAIL", pds.EPHEMERAL_Q), ""): ""})
        self.assertTrue(any("ephemerally" in p for p in problems_of(text)))

    def test_answers_for_an_unselected_type(self):
        text = edited({(usage("PSL_PHOTOS", pds.SHARING_Q), pds.COLLECTED): "TRUE"})
        self.assertTrue(any("Photos is not selected" in p for p in problems_of(text)))

    def test_selected_type_without_answers(self):
        text = edited({("PSL_DATA_TYPES_PHOTOS_AND_VIDEOS", "PSL_PHOTOS"): "TRUE"})
        problems = problems_of(text)
        self.assertTrue(any("Photos: say whether" in p for p in problems))
        self.assertTrue(any("Photos: choose exactly one" in p for p in problems))

    def test_no_accounts_forbids_the_deletion_url(self):
        text = edited({("PSL_SUPPORTED_ACCOUNT_CREATION_METHODS", "PSL_ACM_OAUTH"): "",
                       ("PSL_SUPPORTED_ACCOUNT_CREATION_METHODS", "PSL_ACM_NONE"): "TRUE"})
        self.assertTrue(any("must be blank when the app has no accounts" in p for p in problems_of(text)))

    def test_accounts_need_the_deletion_url(self):
        text = edited({("PSL_ACCOUNT_DELETION_URL", ""): ""})
        self.assertTrue(any("PSL_ACCOUNT_DELETION_URL is required" in p for p in problems_of(text)))

    def test_no_accounts_with_a_method(self):
        text = edited({("PSL_SUPPORTED_ACCOUNT_CREATION_METHODS", "PSL_ACM_NONE"): "TRUE"})
        self.assertTrue(any("can't be chosen with" in p for p in problems_of(text)))

    def test_url_must_be_https(self):
        text = edited({("PSL_DATA_DELETION_URL", ""): "http://example.com/delete"})
        self.assertTrue(any("https://" in p for p in problems_of(text)))

    def test_deletion_answer_is_single_choice(self):
        text = edited({("PSL_SUPPORT_DATA_DELETION_BY_USER", "DATA_DELETION_NO"): "TRUE"})
        self.assertTrue(any("exactly one TRUE" in p for p in problems_of(text)))

    def test_no_collection_with_types(self):
        text = edited({("PSL_DATA_COLLECTION_COLLECTS_PERSONAL_DATA", ""): "FALSE"})
        self.assertTrue(any("selects data types" in p for p in problems_of(text)))


class MainTests(unittest.TestCase):
    def test_dry_run_sends_nothing_and_says_there_is_no_read_back(self):
        code, out, _, factory_calls = run([])
        self.assertEqual(code, 0)
        self.assertEqual(factory_calls, [])
        self.assertIn("DRY RUN", out)
        self.assertIn("no call that reads the Data safety form back", out)
        self.assertIn("Health and fitness > Health info: collected, optional, not ephemeral", out)

    def test_apply_posts_the_file_as_is(self):
        api = FakeApi()
        code, out, _, _ = run(["--apply"], api)
        self.assertEqual(code, 0)
        self.assertEqual(api.calls, [{"packageName": "com.foodtracker.android",
                                      "body": {"safetyLabels": REPO_CSV}}])
        self.assertIn("APPLIED", out)

    def test_apply_without_credentials(self):
        code, _, err, _ = run(["--apply"], None)
        self.assertEqual(code, 2)
        self.assertIn("PLAY_SERVICE_ACCOUNT_JSON", err)

    def test_play_refusal(self):
        api = FakeApi(error=RuntimeError("You cannot answer PSL_ACCOUNT_DELETION_URL."))
        code, _, err, _ = run(["--apply"], api)
        self.assertEqual(code, 1)
        self.assertIn("Play rejected", err)

    def test_invalid_file_is_never_sent(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "bad.csv"
            path.write_text(edited({("PSL_ACCOUNT_DELETION_URL", ""): ""}), encoding="utf-8")
            api = FakeApi()
            code, _, err, _ = run(["--csv", str(path), "--apply"], api)
        self.assertEqual(code, 1)
        self.assertEqual(api.calls, [])
        self.assertIn("nothing was sent", err)


if __name__ == "__main__":
    unittest.main()
