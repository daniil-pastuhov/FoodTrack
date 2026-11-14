"""Tests for the store-listing sync in scripts/testflight.py, against a fake App Store Connect.

    python3 -m unittest discover -s scripts/tests

No network and no credentials: the fake answers the calls the sync makes and records the writes.
"""
import contextlib
import hashlib
import io
import pathlib
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))
import testflight  # noqa: E402


def png(width, height, salt=b""):
    """A PNG header with the given size (enough for image_size), plus salt for a distinct hash."""
    return (b"\x89PNG\r\n\x1a\n" + b"\x00\x00\x00\x0dIHDR" + width.to_bytes(4, "big")
            + height.to_bytes(4, "big") + b"\x08\x02\x00\x00\x00" + salt)


def md5(data):
    return hashlib.md5(data).hexdigest()


class FakeAsc:
    """Answers GETs from a {path: data} table and records every call."""

    def __init__(self, gets=None):
        self.gets = gets or {}
        self.calls = []
        self.parts = []
        self.created = 0

    def call(self, method, path, params=None, body=None):
        self.calls.append((method, path, body))
        if method == "GET":
            if path.startswith("/appScreenshots/"):
                return {"data": {"id": path.rsplit("/", 1)[1],
                                 "attributes": {"assetDeliveryState": {"state": "COMPLETE"}}}}
            return {"data": self.gets.get(path, [])}
        if method == "POST":
            self.created += 1
            data = {"id": f"new-{self.created}", "type": body["data"]["type"],
                    "attributes": dict(body["data"].get("attributes", {}))}
            if body["data"]["type"] == "appScreenshots":
                size = body["data"]["attributes"]["fileSize"]
                data["attributes"]["uploadOperations"] = [
                    {"method": "PUT", "url": "https://upload.invalid/a", "offset": 0, "length": size,
                     "requestHeaders": [{"name": "Content-Type", "value": "image/png"}]}]
            return {"data": data}
        return {}

    def upload_part(self, operation, data):
        self.parts.append((operation["url"], len(data)))

    def writes(self, method=None):
        return [c for c in self.calls if c[0] != "GET" and (method is None or c[0] == method)]


def loc(id_, locale, **attributes):
    return {"id": id_, "type": "x", "attributes": {"locale": locale, **attributes}}


def write_listing(root, locale, **files):
    folder = pathlib.Path(root) / locale
    folder.mkdir(parents=True, exist_ok=True)
    for name, text in files.items():
        (folder / f"{name}.txt").write_text(text + "\n", encoding="utf-8")
    return folder


def quiet(func, *args, **kwargs):
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        result = func(*args, **kwargs)
    return result, out.getvalue()


class LocaleTests(unittest.TestCase):
    def test_locale_folders_are_those_with_a_name(self):
        with tempfile.TemporaryDirectory() as root:
            write_listing(root, "en-GB", name="A")
            write_listing(root, "ru", name="Б")
            (pathlib.Path(root) / "iap-review").mkdir()
            self.assertEqual(testflight.listing_locales(root), ["en-GB", "ru"])

    def test_english_without_a_folder_takes_en_gb(self):
        folders = ["en-GB", "en-US", "ru"]
        self.assertEqual(testflight.listing_source("en-US", folders), "en-US")
        self.assertEqual(testflight.listing_source("en-AU", folders), "en-GB")
        self.assertIsNone(testflight.listing_source("de-DE", folders))

    def test_read_listing_splits_by_resource_and_skips_missing_files(self):
        with tempfile.TemporaryDirectory() as root:
            folder = write_listing(root, "en-GB", name="Name", keywords="a,b")
            self.assertEqual(testflight.read_listing(folder),
                             {"version": {"keywords": "a,b"}, "info": {"name": "Name"}})


class FieldChangeTests(unittest.TestCase):
    def test_only_differing_fields(self):
        current = {"name": "Same", "subtitle": "Old ", "privacyPolicyUrl": "https://x"}
        self.assertEqual(testflight.field_changes(current, {"name": "Same", "subtitle": "New"}),
                         {"subtitle": ("Old", "New")})

    def test_missing_remote_value_counts_as_empty(self):
        self.assertEqual(testflight.field_changes({"promotionalText": None}, {"promotionalText": "P"}),
                         {"promotionalText": ("", "P")})

    def test_long_text_is_shown_as_a_diff(self):
        lines = testflight.describe_change("description", "one\ntwo", "one\nthree")
        self.assertIn("-two", "\n".join(lines))
        self.assertIn("+three", "\n".join(lines))


class SyncListingTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name
        write_listing(self.root, "en-GB", name="BestBefore", subtitle="Sub", keywords="k",
                      promotional_text="Promo", description="Desc")
        write_listing(self.root, "en-US", name="BestBefore US", subtitle="Sub US", keywords="ku",
                      promotional_text="Promo US", description="Desc US")
        self.asc = FakeAsc({
            "/appStoreVersions/v1/appStoreVersionLocalizations": [
                loc("vl-gb", "en-GB", keywords="k", promotionalText=None, description="Old desc",
                    supportUrl="https://support", marketingUrl=None, whatsNew=None),
                loc("vl-au", "en-AU", keywords="k", description="Desc", promotionalText="Promo"),
                loc("vl-de", "de-DE", description="Deutsch"),
            ],
            "/appInfos/i1/appInfoLocalizations": [
                loc("il-gb", "en-GB", name="BestBefore", subtitle="Sub",
                    privacyPolicyUrl="https://privacy"),
            ],
        })

    def tearDown(self):
        self.tmp.cleanup()

    def test_dry_run_writes_nothing_and_reports(self):
        (result, changed), out = quiet(testflight.sync_listing, self.asc, "v1", "i1", False, self.root)
        self.assertTrue(changed)
        self.assertEqual(self.asc.writes(), [])
        self.assertIn("would create", out)
        self.assertIn("en-AU takes the en-GB texts", out)
        self.assertIn("de-DE: no folder in the repo; left as it is", out)
        self.assertNotIn("en-US", result)  # not created by a dry run

    def test_apply_patches_only_what_differs_and_creates_en_us(self):
        (result, _), _ = quiet(testflight.sync_listing, self.asc, "v1", "i1", True, self.root)
        patches = {c[1]: c[2]["data"]["attributes"] for c in self.asc.writes("PATCH")}
        self.assertEqual(patches["/appStoreVersionLocalizations/vl-gb"],
                         {"promotionalText": "Promo", "description": "Desc"})
        # en-AU already has en-GB's texts.
        self.assertNotIn("/appStoreVersionLocalizations/vl-au", patches)
        self.assertNotIn("/appInfoLocalizations/il-gb", patches)
        posts = [c[2]["data"] for c in self.asc.writes("POST")]
        version_post = next(p for p in posts if p["type"] == "appStoreVersionLocalizations"
                            and p["attributes"]["locale"] == "en-US")
        self.assertEqual(version_post["attributes"]["supportUrl"], "https://support")
        self.assertNotIn("marketingUrl", version_post["attributes"])  # empty on en-GB
        self.assertNotIn("whatsNew", version_post["attributes"])
        self.assertEqual(version_post["relationships"]["appStoreVersion"]["data"]["id"], "v1")
        info_posts = [p for p in posts if p["type"] == "appInfoLocalizations"]
        self.assertEqual({p["attributes"]["locale"] for p in info_posts}, {"en-US", "en-AU"})
        us_info = next(p for p in info_posts if p["attributes"]["locale"] == "en-US")
        self.assertEqual(us_info["attributes"]["name"], "BestBefore US")
        self.assertEqual(us_info["attributes"]["privacyPolicyUrl"], "https://privacy")
        self.assertIn("en-US", result)
        self.assertEqual(result["de-DE"], "vl-de")

    def test_rerun_after_apply_changes_nothing(self):
        gets = self.asc.gets
        gets["/appStoreVersions/v1/appStoreVersionLocalizations"] = [
            loc("vl-gb", "en-GB", keywords="k", promotionalText="Promo", description="Desc"),
            loc("vl-us", "en-US", keywords="ku", promotionalText="Promo US", description="Desc US"),
        ]
        gets["/appInfos/i1/appInfoLocalizations"] = [
            loc("il-gb", "en-GB", name="BestBefore", subtitle="Sub"),
            loc("il-us", "en-US", name="BestBefore US", subtitle="Sub US"),
        ]
        (_, changed), _ = quiet(testflight.sync_listing, self.asc, "v1", "i1", True, self.root)
        self.assertFalse(changed)
        self.assertEqual(self.asc.writes(), [])

    def test_new_app_info_localization_needs_a_name(self):
        with self.assertRaises(testflight.ListingError):
            quiet(testflight.sync_resource, self.asc, "info", "ru", None, {"subtitle": "S"},
                  {"type": "appInfos", "id": "i1"}, None, True)


class GateTests(unittest.TestCase):
    def test_over_limit_text_stops_the_sync(self):
        import check_store_listing
        with tempfile.TemporaryDirectory() as root:
            write_listing(pathlib.Path(root) / "app-store", "en-GB", name="N" * 31, subtitle="S",
                          keywords="k", promotional_text="P", description="D")
            with mock.patch.object(check_store_listing, "ROOT", pathlib.Path(root)):
                with self.assertRaises(testflight.ListingError) as caught:
                    testflight.check_listing_texts()
        self.assertIn("31 characters (limit 30)", str(caught.exception))

    def test_repo_listing_passes(self):
        testflight.check_listing_texts()


class ScreenshotOrderTests(unittest.TestCase):
    def test_numeric_order(self):
        paths = [pathlib.Path(n) for n in ("10.png", "2.png", "1.png", "cover.png")]
        self.assertEqual([p.name for p in sorted(paths, key=testflight.screenshot_number)],
                         ["1.png", "2.png", "10.png", "cover.png"])

    def test_more_than_ten_fails(self):
        with tempfile.TemporaryDirectory() as folder:
            for i in range(1, 12):
                (pathlib.Path(folder) / f"{i}.png").write_bytes(png(10, 20, bytes([i])))
            with self.assertRaises(testflight.ListingError):
                testflight.local_screenshots(folder)

    def test_repo_screenshot_folders_are_within_the_limit(self):
        for locale in testflight.listing_locales():
            for name in testflight.SCREENSHOT_SETS:
                folder = testflight.LISTING_DIR / locale / name
                if folder.is_dir():
                    testflight.local_screenshots(folder)

    def test_png_size(self):
        with tempfile.TemporaryDirectory() as folder:
            path = pathlib.Path(folder) / "1.png"
            path.write_bytes(png(1320, 2868))
            self.assertEqual(testflight.image_size(path), (1320, 2868))


def shot(name, md5_):
    return {"name": name, "md5": md5_}


def remote(id_, name, md5_, state="COMPLETE"):
    return {"id": id_, "name": name, "md5": md5_, "state": state, "dimensions": None}


class PlanScreenshotTests(unittest.TestCase):
    def test_identical_set_is_left_alone(self):
        plan = testflight.plan_screenshots([shot("1.png", "a"), shot("2.png", "b")],
                                           [remote("r1", "1.png", "a"), remote("r2", "2.png", "b")])
        self.assertEqual((plan["upload"], plan["delete"], plan["reorder"]), ([], [], False))

    def test_changed_image_is_replaced_and_moved_back_in_place(self):
        plan = testflight.plan_screenshots(
            [shot("1.png", "a"), shot("2.png", "new"), shot("3.png", "c")],
            [remote("r1", "1.png", "a"), remote("r2", "2.png", "b"), remote("r3", "3.png", "c")])
        self.assertEqual(plan["upload"], [1])
        self.assertEqual([r["id"] for r in plan["delete"]], ["r2"])
        self.assertTrue(plan["reorder"])  # the upload lands after 3.png

    def test_appended_images_need_no_reorder(self):
        plan = testflight.plan_screenshots(
            [shot("1.png", "a"), shot("2.png", "b"), shot("5.png", "e"), shot("6.png", "f")],
            [remote("r1", "1.png", "a"), remote("r2", "2.png", "b")])
        self.assertEqual(plan["upload"], [2, 3])
        self.assertFalse(plan["reorder"])

    def test_removed_images_are_deleted(self):
        plan = testflight.plan_screenshots([shot("1.png", "a")],
                                           [remote("r1", "1.png", "a"), remote("r2", "2.png", "b")])
        self.assertEqual([r["id"] for r in plan["delete"]], ["r2"])
        self.assertEqual(plan["upload"], [])

    def test_same_image_under_another_name_is_kept(self):
        plan = testflight.plan_screenshots([shot("1.png", "a")], [remote("r1", "Screen 1.png", "a")])
        self.assertEqual((plan["upload"], plan["delete"]), ([], []))

    def test_order_swap_is_a_reorder_without_uploads(self):
        plan = testflight.plan_screenshots([shot("1.png", "b"), shot("2.png", "a")],
                                           [remote("r1", "1.png", "a"), remote("r2", "2.png", "b")])
        self.assertEqual((plan["upload"], plan["delete"], plan["reorder"]), ([], [], True))

    def test_failed_or_unfinished_uploads_are_redone(self):
        plan = testflight.plan_screenshots(
            [shot("1.png", "a"), shot("2.png", "b")],
            [remote("r1", "1.png", "a", "FAILED"), remote("r2", "2.png", "b", "AWAITING_UPLOAD")])
        self.assertEqual(plan["upload"], [0, 1])
        self.assertEqual({r["id"] for r in plan["delete"]}, {"r1", "r2"})

    def test_duplicate_images_match_one_each(self):
        plan = testflight.plan_screenshots([shot("1.png", "a"), shot("2.png", "a")],
                                           [remote("r1", "1.png", "a")])
        self.assertEqual(plan["upload"], [1])


class SyncScreenshotTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)
        gb = write_listing(self.root, "en-GB", name="N")
        write_listing(self.root, "en-US", name="N")
        (gb / "screenshots").mkdir()
        (gb / "duo-outer-screenshots").mkdir()
        self.images = {}
        for i in (1, 2, 3):
            data = png(1320, 2868, bytes([i]))
            (gb / "screenshots" / f"{i}.png").write_bytes(data)
            self.images[i] = data
        self.asc = FakeAsc({
            "/appStoreVersionLocalizations/vl-gb/appScreenshotSets": [
                {"id": "set-67", "attributes": {"screenshotDisplayType": "APP_IPHONE_67"}},
                {"id": "set-65", "attributes": {"screenshotDisplayType": "APP_IPHONE_65"}},
            ],
            "/appScreenshotSets/set-67/appScreenshots": [
                {"id": "r1", "attributes": {"fileName": "1.png", "sourceFileChecksum": md5(self.images[1]),
                                            "assetDeliveryState": {"state": "COMPLETE"},
                                            "imageAsset": {"width": 1320, "height": 2868}}},
                {"id": "r-old", "attributes": {"fileName": "2.png", "sourceFileChecksum": "0" * 32,
                                               "assetDeliveryState": {"state": "COMPLETE"}}},
            ],
        })

    def tearDown(self):
        self.tmp.cleanup()

    def test_dry_run(self):
        changed, out = quiet(testflight.sync_screenshots, self.asc,
                             {"en-GB": "vl-gb", "en-US": "vl-us"}, False, self.root)
        self.assertTrue(changed)
        self.assertEqual(self.asc.writes(), [])
        self.assertIn("would upload: 2.png, 3.png", out)
        self.assertIn("would delete: 2.png", out)
        self.assertIn("APP_IPHONE_65 sets; left as they are", out)
        self.assertIn("en-US: no screenshot folders", out)
        self.assertIn("Not synced: en-GB/duo-outer-screenshots", out)
        self.assertIn("1320×2868", out)

    def test_apply_deletes_first_then_uploads_commits_and_orders(self):
        quiet(testflight.sync_screenshots, self.asc, {"en-GB": "vl-gb"}, True, self.root)
        writes = self.asc.writes()
        self.assertEqual(writes[0][:2], ("DELETE", "/appScreenshots/r-old"))
        reservations = [w for w in writes if w[0] == "POST"]
        self.assertEqual([r[2]["data"]["attributes"]["fileName"] for r in reservations], ["2.png", "3.png"])
        self.assertEqual(self.asc.parts, [("https://upload.invalid/a", len(self.images[2])),
                                          ("https://upload.invalid/a", len(self.images[3]))])
        commits = [w[2]["data"]["attributes"] for w in writes
                   if w[0] == "PATCH" and w[1].startswith("/appScreenshots/")]
        self.assertEqual(commits, [{"uploaded": True, "sourceFileChecksum": md5(self.images[2])},
                                   {"uploaded": True, "sourceFileChecksum": md5(self.images[3])}])
        # 1.png was kept and the uploads were appended in order: no reorder needed.
        self.assertFalse(any(w[1].endswith("/relationships/appScreenshots") for w in writes))

    def test_missing_localization_on_apply_fails(self):
        with self.assertRaises(testflight.ListingError):
            quiet(testflight.sync_screenshots, self.asc, {}, True, self.root)

    def test_processing_failure_stops(self):
        asc = self.asc
        original = asc.call

        def failing(method, path, params=None, body=None):
            if method == "GET" and path.startswith("/appScreenshots/"):
                asc.calls.append((method, path, body))
                return {"data": {"attributes": {"assetDeliveryState": {
                    "state": "FAILED", "errors": [{"code": "IMAGE_INCORRECT_DIMENSIONS"}]}}}}
            return original(method, path, params, body)
        asc.call = failing
        with self.assertRaises(testflight.ListingError) as caught:
            quiet(testflight.sync_screenshots, asc, {"en-GB": "vl-gb"}, True, self.root)
        self.assertIn("IMAGE_INCORRECT_DIMENSIONS", str(caught.exception))


class TargetTests(unittest.TestCase):
    def version(self, id_, string, state, created):
        return {"id": id_, "attributes": {"versionString": string, "appVersionState": state,
                                          "createdDate": created}}

    def test_editable_version_is_picked(self):
        versions = [self.version("a", "2.0.0", "WAITING_FOR_REVIEW", "2026-11-10"),
                    self.version("b", "2.1.0", "PREPARE_FOR_SUBMISSION", "2026-11-12")]
        self.assertEqual(testflight.pick_version(versions), (versions[1], True))

    def test_without_an_editable_version_the_newest_is_shown_read_only(self):
        versions = [self.version("a", "1.0.0", "READY_FOR_DISTRIBUTION", "2026-01-01"),
                    self.version("b", "2.0.0", "WAITING_FOR_REVIEW", "2026-11-10")]
        self.assertEqual(testflight.pick_version(versions), (versions[1], False))
        self.assertEqual(testflight.pick_version([]), (None, False))

    def test_app_info_in_review_is_preferred_over_the_live_one(self):
        live = {"id": "1", "attributes": {"state": "READY_FOR_DISTRIBUTION"}}
        pending = {"id": "2", "attributes": {"state": "WAITING_FOR_REVIEW"}}
        self.assertEqual(testflight.pick_app_info([live, pending]), (pending, False))
        editable = {"id": "3", "attributes": {"appStoreState": "PREPARE_FOR_SUBMISSION"}}
        self.assertEqual(testflight.pick_app_info([live, editable]), (editable, True))

    def test_no_editable_version_is_said_plainly(self):
        asc = FakeAsc({
            "/apps/app/appStoreVersions": [self.version("a", "2.0.0", "WAITING_FOR_REVIEW", "x")],
            "/apps/app/appInfos": [{"id": "i", "attributes": {"state": "WAITING_FOR_REVIEW"}}],
        })
        version, _, editable, summary = testflight.listing_target(asc, "app")
        self.assertFalse(editable)
        self.assertEqual(version["id"], "a")
        self.assertIn("No App Store version is editable", summary)


if __name__ == "__main__":
    unittest.main()
