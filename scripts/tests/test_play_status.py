"""Tests for scripts/play_status.py against a fake androidpublisher edits service. Offline:
no Google library or credential is needed.

    python3 -m unittest discover -s scripts/tests
"""
import contextlib
import io
import pathlib
import sys
import types
import unittest
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))
import play_status  # noqa: E402

PACKAGE = "com.foodtracker.android"


class FakeHttpError(Exception):
    def __init__(self, status, reason):
        super().__init__(reason)
        self.resp = types.SimpleNamespace(status=status)
        self.reason = reason


class Request:
    def __init__(self, result):
        self.result = result

    def execute(self):
        if isinstance(self.result, Exception):
            raise self.result
        return self.result


class FakeResource:
    def __init__(self, edits, name):
        self.edits, self.name = edits, name

    def __getattr__(self, method):
        def call(**kwargs):
            self.edits.calls.append((self.name, method, kwargs))
            return Request(self.edits.answer(self.name, method, kwargs))
        return call


class FakeEdits:
    """An edits() service answering from tables; any call that isn't a read is recorded."""

    def __init__(self, tracks, countries=None, testers=None, details=None):
        self.tracks_table = tracks
        self.countries_table = countries or {}
        self.testers_table = testers or {}
        self.details_table = details if details is not None else {"defaultLanguage": "en-GB",
                                                                   "contactEmail": "owner@example.com"}
        self.calls = []

    def __getattr__(self, name):
        if name in ("details", "tracks", "countryavailability", "testers"):
            return lambda: FakeResource(self, name)
        raise AttributeError(name)

    def insert(self, **kwargs):
        self.calls.append(("edits", "insert", kwargs))
        return Request({"id": "edit-1"})

    def delete(self, **kwargs):
        self.calls.append(("edits", "delete", kwargs))
        return Request({})

    def commit(self, **kwargs):
        self.calls.append(("edits", "commit", kwargs))
        return Request({})

    def answer(self, name, method, kwargs):
        if method != "get" and method != "list":
            return {}
        if name == "details":
            return self.details_table
        if name == "tracks":
            return {"tracks": self.tracks_table}
        table = self.countries_table if name == "countryavailability" else self.testers_table
        return table.get(kwargs["track"], FakeHttpError(404, f"no {name} for {kwargs['track']}"))

    def writes(self):
        return [(n, m) for n, m, _ in self.calls if m not in ("get", "list", "insert", "delete")]


TRACKS = [
    {"track": "production", "releases": []},
    {"track": "alpha", "releases": [{"name": "2.0.0 (1061)", "versionCodes": ["1061"],
                                     "status": "completed",
                                     "releaseNotes": [{"language": "en-GB", "text": "x"},
                                                      {"language": "ru-RU", "text": "y"}]}]},
    {"track": "internal", "releases": [{"name": "Recipe preview 2.0.0 (1063)",
                                        "versionCodes": ["1063"], "status": "completed"}]},
]


class CollectTests(unittest.TestCase):
    def collect(self, edits):
        return play_status.collect_status(edits, PACKAGE, "edit-1", error_types=(FakeHttpError,))

    def test_reads_every_track_in_order_and_only_reads(self):
        edits = FakeEdits(TRACKS,
                          countries={"alpha": {"countries": [{"countryCode": "NO"}, {"countryCode": "GB"}],
                                               "syncWithProduction": True},
                                     "production": {"countries": []}},
                          testers={"alpha": {"googleGroups": ["bestbefore-testers@googlegroups.com"]}})
        status = self.collect(edits)
        self.assertEqual([t["track"] for t in status["tracks"]], ["internal", "alpha", "production"])
        self.assertEqual(edits.writes(), [])
        alpha = status["tracks"][1]
        self.assertEqual(alpha["testers"]["googleGroups"], ["bestbefore-testers@googlegroups.com"])
        self.assertTrue(alpha["countries"]["syncWithProduction"])

    def test_internal_has_no_country_call_and_production_no_testers_call(self):
        edits = FakeEdits(TRACKS)
        self.collect(edits)
        country_tracks = [k["track"] for n, m, k in edits.calls if n == "countryavailability"]
        tester_tracks = [k["track"] for n, m, k in edits.calls if n == "testers"]
        self.assertNotIn("internal", country_tracks)
        self.assertNotIn("production", tester_tracks)
        self.assertIn("internal", tester_tracks)

    def test_a_refused_section_is_reported_not_fatal(self):
        status = self.collect(FakeEdits(TRACKS))
        internal = status["tracks"][0]
        self.assertIn("HTTP 404", internal["testersError"])

    def test_contact_details_are_not_kept(self):
        status = self.collect(FakeEdits(TRACKS))
        self.assertNotIn("owner@example.com", repr(status))
        self.assertEqual(status["defaultLanguage"], "en-GB")

    def test_refused_track_list_raises(self):
        edits = FakeEdits(TRACKS)
        edits.answer = lambda name, method, kwargs: (FakeHttpError(403, "denied") if name == "tracks"
                                                     else {})
        with self.assertRaises(play_status.ApiError):
            self.collect(edits)


class FormatTests(unittest.TestCase):
    def test_output_names_releases_groups_countries_and_the_api_limits(self):
        edits = FakeEdits(TRACKS,
                          countries={"alpha": {"countries": [{"countryCode": "NO"}, {"countryCode": "GB"}],
                                               "restOfWorld": False, "syncWithProduction": True}},
                          testers={"alpha": {"googleGroups": ["bestbefore-testers@googlegroups.com"]},
                                   "internal": {}})
        status = play_status.collect_status(edits, PACKAGE, "edit-1", error_types=(FakeHttpError,))
        text = "\n".join(play_status.format_status(status))
        self.assertIn("Track 'alpha' (closed testing", text)
        self.assertIn("Release '2.0.0 (1061)': completed, version codes 1061", text)
        self.assertIn("release notes in en-GB, ru-RU", text)
        self.assertIn("2 countries/regions: GB, NO; synced with production", text)
        self.assertIn("Testers (Google Groups): bestbefore-testers@googlegroups.com", text)
        self.assertIn("Testers (Google Groups): none", text)
        self.assertIn("opted-in tester count", text)
        self.assertIn("Releases: none", text)

    def test_rollout_fraction_and_custom_track(self):
        status = {"package": PACKAGE, "defaultLanguage": "en-GB", "tracks": [
            {"track": "qa", "releases": [{"name": "1", "versionCodes": [5], "status": "inProgress",
                                          "userFraction": 0.2,
                                          "countryTargeting": {"countries": ["NO"],
                                                               "includeRestOfWorld": True}}]}]}
        text = "\n".join(play_status.format_status(status))
        self.assertIn("closed testing (custom track)", text)
        self.assertIn("rollout 20 %", text)
        self.assertIn("targets 1 countries/regions plus rest of world", text)


class MainTests(unittest.TestCase):
    def run_main(self, edits):
        api = types.SimpleNamespace(edits=lambda: edits)
        fake_upload = types.ModuleType("play_upload")
        fake_upload.PACKAGE_NAME = PACKAGE
        fake_upload.publisher = lambda timeout=600, static_discovery=True: api
        errors = types.ModuleType("googleapiclient.errors")
        errors.HttpError = FakeHttpError
        modules = {"play_upload": fake_upload, "googleapiclient": types.ModuleType("googleapiclient"),
                   "googleapiclient.errors": errors}
        out, err = io.StringIO(), io.StringIO()
        with mock.patch.dict(sys.modules, modules), mock.patch.object(sys, "argv", ["play_status.py"]), \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = play_status.main()
        return code, out.getvalue(), err.getvalue()

    def test_edit_is_deleted_and_never_committed(self):
        edits = FakeEdits(TRACKS)
        code, out, _ = self.run_main(edits)
        self.assertEqual(code, 0)
        self.assertIn("Track 'internal'", out)
        methods = [(n, m) for n, m, _ in edits.calls if n == "edits"]
        self.assertEqual(methods, [("edits", "insert"), ("edits", "delete")])
        self.assertEqual(edits.writes(), [])

    def test_edit_is_deleted_when_play_refuses(self):
        edits = FakeEdits(TRACKS)
        edits.answer = lambda name, method, kwargs: (FakeHttpError(403, "denied") if name == "tracks"
                                                     else {})
        code, _, err = self.run_main(edits)
        self.assertEqual(code, 1)
        self.assertIn("HTTP 403", err)
        self.assertIn(("edits", "delete"), [(n, m) for n, m, _ in edits.calls])

    def test_without_a_key_it_stops(self):
        fake_upload = types.ModuleType("play_upload")
        fake_upload.PACKAGE_NAME = PACKAGE
        fake_upload.publisher = lambda timeout=600, static_discovery=True: None
        errors = types.ModuleType("googleapiclient.errors")
        errors.HttpError = FakeHttpError
        with mock.patch.dict(sys.modules, {"play_upload": fake_upload,
                                           "googleapiclient": types.ModuleType("googleapiclient"),
                                           "googleapiclient.errors": errors}), \
                mock.patch.object(sys, "argv", ["play_status.py"]), \
                contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(play_status.main(), 2)


if __name__ == "__main__":
    unittest.main()
