"""Tests for `testflight.py previews` (the App Store preview videos), against a fake App Store
Connect: no network, no credentials.

    python3 -m unittest discover -s scripts/tests
"""
import contextlib
import hashlib
import io
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))
import testflight  # noqa: E402


class FakeAsc:
    """GETs from a {path: data} table; records every call; reservations get upload operations."""

    def __init__(self, gets=None):
        self.gets = gets or {}
        self.calls = []
        self.parts = []

    def call(self, method, path, params=None, body=None):
        self.calls.append((method, path, body))
        if method == "GET":
            return {"data": self.gets.get(path, [])}
        if method == "POST" and path == "/appPreviewSets":
            return {"data": {"id": "set-new"}}
        if method == "POST" and path == "/appPreviews":
            size = body["data"]["attributes"]["fileSize"]
            return {"data": {"id": "preview-new", "attributes": {"uploadOperations": [
                {"url": "https://upload.example/1", "method": "PUT", "offset": 0, "length": size}]}}}
        return {}

    def upload_part(self, operation, data):
        self.parts.append((operation["url"], len(data)))


def write_preview(root, folder, data=b"video", poster=None):
    path = pathlib.Path(root) / folder
    path.mkdir(parents=True, exist_ok=True)
    (path / "preview.mp4").write_bytes(data)
    if poster is not None:
        (path / "poster_time.txt").write_text(f"{poster}\n")
    return hashlib.md5(data).hexdigest()


def quiet(fn, *args, **kwargs):
    with contextlib.redirect_stdout(io.StringIO()) as out:
        result = fn(*args, **kwargs)
    return result, out.getvalue()


class TimeCodeTest(unittest.TestCase):
    def test_frames_at_thirty_per_second(self):
        self.assertEqual(testflight.time_code(4.6), "00:00:04:18")
        self.assertEqual(testflight.time_code(5), "00:00:05:00")
        self.assertEqual(testflight.time_code(0), "00:00:00:00")
        self.assertEqual(testflight.time_code(61.5), "00:01:01:15")

    def test_a_frame_rounding_up_carries_into_the_seconds(self):
        self.assertEqual(testflight.time_code(4.999), "00:00:05:00")


class PreviewFolderTest(unittest.TestCase):
    def test_own_folder_then_the_fallback(self):
        with tempfile.TemporaryDirectory() as root:
            write_preview(root, "en-GB")
            write_preview(root, "es-ES")
            write_preview(root, "ru")
            self.assertEqual(testflight.preview_folder(root, "ru").name, "ru")
            self.assertEqual(testflight.preview_folder(root, "es-MX").name, "es-ES")
            self.assertEqual(testflight.preview_folder(root, "en-US").name, "en-GB")
            self.assertEqual(testflight.preview_folder(root, "en-AU").name, "en-GB")
            # fr-CA's parent has none, and no other language falls back.
            self.assertIsNone(testflight.preview_folder(root, "fr-CA"))
            self.assertIsNone(testflight.preview_folder(root, "de-DE"))

    def test_poster_time_from_the_folder_or_apples_default(self):
        with tempfile.TemporaryDirectory() as root:
            write_preview(root, "da", poster=4.6)
            write_preview(root, "sv")
            self.assertEqual(testflight.local_preview(pathlib.Path(root) / "da")["time_code"], "00:00:04:18")
            self.assertEqual(testflight.local_preview(pathlib.Path(root) / "sv")["time_code"], "00:00:05:00")


class PlanPreviewTest(unittest.TestCase):
    local = {"md5": "a" * 32, "time_code": "00:00:04:18"}

    def remote(self, id_, md5, state="COMPLETE", time_code="00:00:04:18"):
        return {"id": id_, "name": f"{id_}.mp4", "md5": md5, "state": state, "time_code": time_code}

    def test_same_file_is_kept(self):
        plan = testflight.plan_preview(self.local, [self.remote("p1", "a" * 32)])
        self.assertEqual((plan["upload"], plan["delete"], plan["time_code"]), (False, [], False))

    def test_same_file_with_another_poster_only_sets_the_poster(self):
        plan = testflight.plan_preview(self.local, [self.remote("p1", "a" * 32, time_code="00:00:05:00")])
        self.assertEqual((plan["upload"], plan["delete"], plan["time_code"]), (False, [], True))

    def test_another_file_is_replaced(self):
        plan = testflight.plan_preview(self.local, [self.remote("p1", "b" * 32)])
        self.assertTrue(plan["upload"])
        self.assertEqual([r["id"] for r in plan["delete"]], ["p1"])
        self.assertFalse(plan["time_code"])

    def test_a_failed_copy_is_uploaded_again_and_extra_ones_go(self):
        plan = testflight.plan_preview(self.local, [self.remote("p1", "a" * 32, state="FAILED"),
                                                    self.remote("p2", "c" * 32)])
        self.assertTrue(plan["upload"])
        self.assertEqual([r["id"] for r in plan["delete"]], ["p1", "p2"])

    def test_keeps_one_and_deletes_the_rest(self):
        plan = testflight.plan_preview(self.local, [self.remote("p1", "c" * 32), self.remote("p2", "a" * 32)])
        self.assertFalse(plan["upload"])
        self.assertEqual(plan["keep"]["id"], "p2")
        self.assertEqual([r["id"] for r in plan["delete"]], ["p1"])

    def test_nothing_remote_uploads(self):
        plan = testflight.plan_preview(self.local, [])
        self.assertTrue(plan["upload"])
        self.assertEqual(plan["delete"], [])


class SyncPreviewsTest(unittest.TestCase):
    def test_dry_run_writes_nothing(self):
        with tempfile.TemporaryDirectory() as root:
            write_preview(root, "en-GB", poster=4.6)
            asc = FakeAsc({"/appStoreVersionLocalizations/loc-gb/appPreviewSets": [
                {"id": "set1", "attributes": {"previewType": "IPHONE_67"}}],
                "/appPreviewSets/set1/appPreviews": [{"id": "old", "attributes": {
                    "fileName": "preview.mp4", "sourceFileChecksum": "f" * 32,
                    "videoDeliveryState": {"state": "COMPLETE"}}}]})
            changed, out = quiet(testflight.sync_previews, asc, {"en-GB": "loc-gb"}, False, root)
            self.assertTrue(changed)
            self.assertEqual({c[0] for c in asc.calls}, {"GET"})
            self.assertIn("would upload preview-en-GB.mp4 with poster 00:00:04:18", out)
            self.assertIn("would delete preview.mp4", out)

    def test_apply_creates_the_set_uploads_and_commits(self):
        with tempfile.TemporaryDirectory() as root:
            md5 = write_preview(root, "es-ES", data=b"spanish video", poster=4.6)
            asc = FakeAsc()
            changed, _ = quiet(testflight.sync_previews, asc, {"es-MX": "loc-mx"}, True, root)
            self.assertTrue(changed)
            writes = [(m, p) for m, p, _ in asc.calls if m != "GET"]
            self.assertEqual(writes, [("POST", "/appPreviewSets"), ("POST", "/appPreviews"),
                                      ("PATCH", "/appPreviews/preview-new")])
            created = asc.calls[[p for _, p, _ in asc.calls].index("/appPreviewSets")][2]["data"]
            self.assertEqual(created["attributes"]["previewType"], "IPHONE_67")
            self.assertEqual(created["relationships"]["appStoreVersionLocalization"]["data"]["id"], "loc-mx")
            reserve = asc.calls[[p for _, p, _ in asc.calls].index("/appPreviews")][2]["data"]["attributes"]
            self.assertEqual(reserve, {"fileName": "preview-es-MX.mp4", "fileSize": len(b"spanish video"),
                                       "mimeType": "video/mp4", "previewFrameTimeCode": "00:00:04:18"})
            commit = asc.calls[-1][2]["data"]["attributes"]
            self.assertEqual(commit["sourceFileChecksum"], md5)
            self.assertTrue(commit["uploaded"])
            self.assertEqual(asc.parts, [("https://upload.example/1", len(b"spanish video"))])

    def test_up_to_date_and_other_preview_types_left_alone(self):
        with tempfile.TemporaryDirectory() as root:
            md5 = write_preview(root, "da", poster=4.6)
            asc = FakeAsc({"/appStoreVersionLocalizations/loc-da/appPreviewSets": [
                {"id": "ipad", "attributes": {"previewType": "IPAD_PRO_3GEN_129"}},
                {"id": "set1", "attributes": {"previewType": "IPHONE_67"}}],
                "/appPreviewSets/set1/appPreviews": [{"id": "p", "attributes": {
                    "fileName": "preview.mp4", "sourceFileChecksum": md5.upper(),
                    "previewFrameTimeCode": "00:00:04:18", "videoDeliveryState": {"state": "COMPLETE"}}}]})
            changed, out = quiet(testflight.sync_previews, asc, {"da": "loc-da"}, True, root)
            self.assertFalse(changed)
            self.assertIn("up to date", out)
            self.assertEqual({c[0] for c in asc.calls}, {"GET"})
            self.assertNotIn("/appPreviewSets/ipad/appPreviews", [p for _, p, _ in asc.calls])

    def test_a_locale_without_a_video_is_left_alone(self):
        with tempfile.TemporaryDirectory() as root:
            asc = FakeAsc()
            changed, out = quiet(testflight.sync_previews, asc, {"de-DE": "loc-de"}, True, root)
            self.assertFalse(changed)
            self.assertEqual(asc.calls, [])
            self.assertIn("left as it is", out)


if __name__ == "__main__":
    unittest.main()
