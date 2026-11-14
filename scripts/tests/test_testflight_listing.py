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
    """Answers GETs from a {path: data} table (or {path: whole response} in responses, for
    included resources) and records every call."""

    def __init__(self, gets=None, responses=None):
        self.gets = gets or {}
        self.responses = responses or {}
        self.calls = []
        self.parts = []
        self.created = 0
        # Library images: id -> (state, specId) once processed; a reserved one gets the spec
        # image_spec gives its attributes.
        self.images = {}
        self.image_spec = lambda attributes: None

    def call(self, method, path, params=None, body=None):
        self.calls.append((method, path, body))
        if method == "GET":
            if path.startswith("/appScreenshots/"):
                return {"data": {"id": path.rsplit("/", 1)[1],
                                 "attributes": {"assetDeliveryState": {"state": "COMPLETE"}}}}
            if path.startswith("/appAssetLibraryImages/"):
                state, spec = self.images[path.rsplit("/", 1)[1]]
                return {"data": {"attributes": {"state": state, "specId": spec}}}
            if path in self.responses:
                return self.responses[path]
            return {"data": self.gets.get(path, [])}
        if method == "POST":
            self.created += 1
            data = {"id": f"new-{self.created}", "type": body["data"]["type"],
                    "attributes": dict(body["data"].get("attributes", {}))}
            if body["data"]["type"] == "appAssetLibraryImages":
                self.images[data["id"]] = (
                    "PREPARE_FOR_SUBMISSION", self.image_spec(data["attributes"]))
            if body["data"]["type"] in ("appScreenshots", "appAssetLibraryImages"):
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
            # Both iPhone Duo displays share one group of at most 10, and each image must have
            # its display's size.
            duo = testflight.local_duo(testflight.LISTING_DIR / locale)
            self.assertLessEqual(len(duo), testflight.SCREENSHOT_LIMIT, locale)

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


DUO_SPECS = {"spec-inner": (2853, 2007), "spec-inner-upright": (2007, 2853),
             "spec-outer": (1398, 2034), "spec-outer-sideways": (2034, 1398)}


def duo_ref_data(max_count=10):
    """Reference data shaped like the live account's: one IPHONE_DUO group for both displays."""
    return [{"id": "ref", "attributes": {
        "placementProfileGroups": [
            {"placementProfileGroupId": "IPHONE_DUO_PROFILE", "displayClassId": "IPHONE_DUO",
             "platform": "IPHONE_APP_STORE"},
            {"placementProfileGroupId": "IMESSAGE_IPHONE_DUO_PROFILE", "displayClassId": "IPHONE_DUO",
             "platform": "IMESSAGE_APP_STORE"},
            {"placementProfileGroupId": "IPAD_13_PROFILE", "displayClassId": "IPAD_13_DISPLAY",
             "platform": "IPAD_APP_STORE"}],
        "placementTypes": [{"placementTypeId": "APP_SCREENSHOT", "specMappings": [
            {"placementGroupId": "IPHONE_DUO_PROFILE", "specs": sorted(DUO_SPECS)},
            {"placementGroupId": "IPAD_13_PROFILE", "specs": ["spec-ipad"]}]}],
        "imageSpecs": [{"specId": k, "shortName": f"i{w}x{h}a0", "dimensions": {
            "minWidth": w, "maxWidth": w, "minHeight": h, "maxHeight": h}}
            for k, (w, h) in {**DUO_SPECS, "spec-ipad": (2064, 2752)}.items()],
        "features": [{"featureId": "APP_STORE_VERSIONS", "placementPolicies": [
            {"placementType": "APP_SCREENSHOT", "groupLimits": [
                {"groupIds": ["IPAD_13_PROFILE", "IPHONE_DUO_PROFILE"], "maxCount": max_count}]}]}],
    }}]


def placement(id_, image_id):
    return {"id": id_, "attributes": {"placementType": "APP_SCREENSHOT",
                                      "placementGroup": "IPHONE_DUO_PROFILE", "state": "ACTIVE"},
            "relationships": {"image": {"data": {"type": "appAssetLibraryImages", "id": image_id}}}}


def library_image(id_, name, size, reference, state="APPROVED"):
    spec = next(k for k, v in DUO_SPECS.items() if v == size)
    return {"id": id_, "attributes": {"fileName": name, "referenceName": reference, "state": state,
                                      "specId": spec, "imageAsset": {"width": size[0], "height": size[1]}}}


class DuoTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)
        self.gb = write_listing(self.root, "en-GB", name="N")
        write_listing(self.root, "en-US", name="N")
        self.files = {}
        for folder, size in (("duo-outer-screenshots", (1398, 2034)),
                             ("duo-inner-screenshots", (2853, 2007))):
            (self.gb / folder).mkdir()
            for i in (1, 2):
                data = png(*size, f"{folder}{i}".encode())
                (self.gb / folder / f"{i}.png").write_bytes(data)
                self.files[(folder, i)] = data
        # As the website leaves them: inner first, reference names without an MD5.
        self.website = [library_image("w-in1", "1.png", (2853, 2007), "1.png (71)"),
                        library_image("w-in2", "2.png", (2853, 2007), "2.png (72)"),
                        library_image("w-out1", "1.png", (1398, 2034), "1.png (75)"),
                        library_image("w-out2", "2.png", (1398, 2034), "2.png (76)")]
        self.asc = self.fake(self.website)

    def tearDown(self):
        self.tmp.cleanup()

    def fake(self, placed, library=None):
        asc = FakeAsc({
            "/appStoreVersionLocalizations/vl-gb/appScreenshotSets": [
                {"id": "set-duo", "attributes": {"screenshotDisplayType": "APP_IPHONE_DUO"}}],
            "/apps/app/assetLibrary": {"id": "lib", "type": "appAssetLibraries"},
            "/appAssetLibraryRefData": duo_ref_data(),
            "/appAssetLibraries/lib/images": list(library if library is not None else placed),
        }, responses={"/appStoreVersionLocalizations/vl-gb/placements": {
            "data": [placement(f"p-{image['id']}", image["id"]) for image in placed],
            "included": placed}})
        asc.image_spec = lambda attrs: ("spec-outer" if "duo-outer" in attrs["referenceName"]
                                        else "spec-inner")
        return asc

    def tagged(self, folder, i, id_=None):
        size = (1398, 2034) if "outer" in folder else (2853, 2007)
        return library_image(id_ or f"{folder}-{i}", f"{i}.png", size,
                             f"en-GB/{folder}/{i}.png md5:{md5(self.files[(folder, i)])}")

    def sync(self, apply, asc=None):
        return quiet(testflight.sync_screenshots, asc or self.asc, {"en-GB": "vl-gb"}, apply,
                     self.root, app_id="app")

    def test_library_reads_the_one_duo_group_its_specs_and_limit(self):
        asc = self.fake([], library=[self.tagged("duo-outer-screenshots", 1),
                                     library_image("x", "x.png", (1398, 2034), "x md5:" + "a" * 32,
                                                   state="FAILED"),
                                     library_image("y", "y.png", (1398, 2034), "no tag")])
        library = testflight.DuoLibrary(asc, "app")
        self.assertEqual(library.group, "IPHONE_DUO_PROFILE")
        self.assertEqual(library.limit, 10)
        self.assertEqual(library.sizes(), set(DUO_SPECS.values()))
        self.assertEqual(list(library.by_md5),
                         [md5(self.files[("duo-outer-screenshots", 1)])])

    def test_local_order_is_inner_then_outer_by_number(self):
        shots = testflight.local_duo(self.gb)
        self.assertEqual([(s["kind"], s["name"]) for s in shots],
                         [("inner", "1.png"), ("inner", "2.png"), ("outer", "1.png"), ("outer", "2.png")])

    def test_an_image_in_the_wrong_display_folder_stops(self):
        (self.gb / "duo-outer-screenshots" / "3.png").write_bytes(png(2853, 2007))
        with self.assertRaisesRegex(testflight.ListingError, "outer display takes"):
            testflight.local_duo(self.gb)

    def test_both_displays_share_the_groups_limit(self):
        for i in range(3, 10):  # 9 outer and 2 inner: each folder within 10, not both
            (self.gb / "duo-outer-screenshots" / f"{i}.png").write_bytes(png(1398, 2034, bytes([i])))
        with self.assertRaisesRegex(testflight.ListingError, "at most 10 for both displays"):
            self.sync(False)

    def test_dry_run_describes_both_sides_and_writes_nothing(self):
        changed, out = self.sync(False)
        self.assertTrue(changed)
        self.assertEqual(self.asc.writes(), [])
        self.assertNotIn("APP_IPHONE_DUO sets; left as they are", out)
        self.assertIn("group IPHONE_DUO_PROFILE, at most 10", out)
        self.assertIn(f"repo outer: duo-outer-screenshots/1.png 1398×2034 md5 "
                      f"{md5(self.files[('duo-outer-screenshots', 1)])}", out)
        self.assertIn("App Store Connect inner: 1.png 2853×2007 spec i2853x2007a0, "
                      "reference '1.png (71)' (md5 none), placement ACTIVE, image APPROVED", out)
        self.assertIn("would remove the placements of: inner 1.png 2853×2007 (md5 not recorded)", out)
        self.assertIn("would place: inner 1.png (new upload)", out)
        self.assertIn("would order as inner 1.png, inner 2.png, outer 1.png, outer 2.png", out)

    def test_apply_replaces_untagged_placements_and_orders_the_group(self):
        self.sync(True)
        writes = self.asc.writes()
        deletes = [w[1] for w in writes if w[0] == "DELETE"]
        self.assertEqual(deletes, [f"/appAssetLibraryPlacements/p-{i['id']}" for i in self.website])
        self.assertTrue(all(w[0] == "DELETE" for w in writes[:4]))
        reserved = [w[2]["data"] for w in writes if w[1] == "/appAssetLibraryImages"]
        self.assertEqual([r["attributes"]["referenceName"] for r in reserved], [
            f"en-GB/{folder}/{i}.png md5:{md5(self.files[(folder, i)])}"
            for folder in ("duo-inner-screenshots", "duo-outer-screenshots") for i in (1, 2)])
        self.assertEqual({r["attributes"]["category"] for r in reserved}, {"APP_SCREENSHOTS_AND_PREVIEWS"})
        self.assertEqual(reserved[0]["relationships"]["assetLibrary"]["data"]["id"], "lib")
        commits = [w[2]["data"]["attributes"] for w in writes
                   if w[0] == "PATCH" and w[1].startswith("/appAssetLibraryImages/")]
        self.assertEqual(commits, [{"uploaded": True}] * 4)
        placed = [w for w in writes if w[1] == "/appAssetLibraryPlacements"]
        self.assertEqual([p[2]["data"]["attributes"] for p in placed],
                         [{"placementType": "APP_SCREENSHOT", "placementGroup": "IPHONE_DUO_PROFILE"}] * 4)
        self.assertEqual(placed[0][2]["data"]["relationships"]["appStoreVersionLocalization"]["data"]["id"],
                         "vl-gb")
        order = [w for w in writes if w[1] == "/appAssetLibraryPlacementOrderingRequests"]
        self.assertEqual(len(order), 1)
        body = order[0][2]["data"]
        self.assertEqual(body["attributes"], {"placementGroup": "IPHONE_DUO_PROFILE"})
        # The placements are created in local order (inner, then outer), and ordered so.
        self.assertEqual(len(body["relationships"]["orderedPlacements"]["data"]), 4)
        self.assertEqual(body["relationships"]["appStoreVersionLocalization"]["data"]["id"], "vl-gb")
        self.assertEqual(writes[-1], order[0])
        self.assertFalse(any("/appScreenshot" in w[1] for w in writes))

    def test_rerun_with_tagged_placements_changes_nothing(self):
        placed = [self.tagged(f, i) for f in ("duo-inner-screenshots", "duo-outer-screenshots")
                  for i in (1, 2)]
        asc = self.fake(placed)
        changed, out = self.sync(True, asc)
        self.assertFalse(changed)
        self.assertEqual(asc.writes(), [])
        self.assertIn("up to date", out)

    def test_swapped_order_only_posts_an_ordering_request(self):
        placed = [self.tagged(f, i) for f in ("duo-outer-screenshots", "duo-inner-screenshots")
                  for i in (1, 2)]
        asc = self.fake(placed)
        self.sync(True, asc)
        writes = asc.writes()
        self.assertEqual([w[1] for w in writes], ["/appAssetLibraryPlacementOrderingRequests"])
        self.assertEqual([p["id"] for p in writes[0][2]["data"]["relationships"]["orderedPlacements"]["data"]],
                         ["p-duo-inner-screenshots-1", "p-duo-inner-screenshots-2",
                          "p-duo-outer-screenshots-1", "p-duo-outer-screenshots-2"])

    def test_an_image_already_in_the_library_is_placed_without_uploading(self):
        placed = [self.tagged("duo-inner-screenshots", 1), self.tagged("duo-inner-screenshots", 2),
                  self.tagged("duo-outer-screenshots", 1)]
        asc = self.fake(placed, library=placed + [self.tagged("duo-outer-screenshots", 2, "lib-out2")])
        _, out = self.sync(True, asc)
        self.assertIn("placing: outer 2.png (library image lib-out2 has it)", out)
        writes = asc.writes()
        self.assertFalse(any(w[1] == "/appAssetLibraryImages" for w in writes))
        self.assertEqual(asc.parts, [])
        placed_images = [w[2]["data"]["relationships"]["image"]["data"]["id"]
                         for w in writes if w[1] == "/appAssetLibraryPlacements"]
        self.assertEqual(placed_images, ["lib-out2"])

    def test_a_file_matched_to_another_spec_stops(self):
        self.asc.image_spec = lambda attrs: "spec-ipad"
        with self.assertRaisesRegex(testflight.ListingError, "not to the 2853×2007 spec"):
            self.sync(True)
        self.assertFalse(any(w[1] == "/appAssetLibraryPlacements" for w in self.asc.writes()))

    def test_processing_failure_stops(self):
        real = self.asc.call

        def failing(method, path, params=None, body=None):
            result = real(method, path, params, body)
            if method == "POST" and path == "/appAssetLibraryImages":
                self.asc.images[result["data"]["id"]] = ("FAILED", None)
            return result

        self.asc.call = failing
        with self.assertRaisesRegex(testflight.ListingError, "could not process"):
            self.sync(True)

    def test_without_the_app_id_the_duo_folders_cannot_sync(self):
        with self.assertRaisesRegex(testflight.ListingError, "need the app id"):
            quiet(testflight.sync_screenshots, self.asc, {"en-GB": "vl-gb"}, False, self.root)

    def test_more_than_one_duo_group_is_not_guessed(self):
        ref = duo_ref_data()
        ref[0]["attributes"]["placementProfileGroups"].append(
            {"placementProfileGroupId": "IPHONE_DUO_OTHER", "displayClassId": "IPHONE_DUO",
             "platform": "IPHONE_APP_STORE"})
        self.asc.gets["/appAssetLibraryRefData"] = ref
        with self.assertRaisesRegex(testflight.ListingError, "2 placement groups"):
            testflight.DuoLibrary(self.asc, "app")


class InspectFake:
    """Answers GETs from {path: response} (whole responses, so links.next and included work)."""

    def __init__(self, responses):
        self.responses = responses
        self.calls = []

    def call(self, method, path, params=None, body=None):
        self.calls.append((method, path, dict(params or {})))
        key = path + ("?" + "&".join(f"{k}={v}" for k, v in sorted(params.items()))
                      if params and "cursor" in params else "")
        return self.responses.get(key, {"data": []})


class InspectTests(unittest.TestCase):
    def setUp(self):
        api = testflight.API
        duo_shot = {"id": "s1", "type": "appScreenshots", "attributes": {
            "fileName": "1.png", "sourceFileChecksum": "abc", "imageAsset": {"width": 1398, "height": 2034},
            "uploadOperations": [{"url": "https://secret.invalid"}]},
            "relationships": {"appScreenshotSet": {"links": {"self": "x"}}}}
        self.asc = InspectFake({
            "/apps": {"data": [{"id": "app1"}]},
            "/appStoreVersions/v1/appStoreVersionLocalizations": {"data": [loc("vl-gb", "en-GB")]},
            "/appStoreVersionLocalizations/vl-gb/appScreenshotSets": {"data": [
                {"id": "set-duo", "attributes": {"screenshotDisplayType": "APP_IPHONE_DUO"}}]},
            "/appScreenshotSets/set-duo/appScreenshots": {
                "data": [duo_shot],
                "links": {"next": f"{api}/appScreenshotSets/set-duo/appScreenshots?cursor=2&limit=50"}},
            "/appScreenshotSets/set-duo/appScreenshots?cursor=2&limit=50": {"data": [
                {"id": "s2", "attributes": {"fileName": "1-inner.png",
                                            "imageAsset": {"width": 2853, "height": 2007}}}]},
            "/apps/app1/assetLibrary": {"data": {"id": "lib1", "type": "appAssetLibraries"}},
            "/appAssetLibraryRefData": {"data": [{"id": "ref", "attributes": {
                "placementProfileGroups": [
                    {"placementProfileGroupId": "IPHONE_DUO_PROFILE", "displayClassId": "IPHONE_DUO"},
                    {"placementProfileGroupId": "MAC_PROFILE", "displayClassId": "DEFAULT"}],
                "displayClasses": [{"displayClassId": "IPHONE_DUO", "screenDimensions": ["1398x2034"]}],
                "placementTypes": [{"placementTypeId": "APP_SCREENSHOT", "specMappings": [
                    {"placementGroup": "IPHONE_DUO_PROFILE", "specIds": ["spec-outer"]},
                    {"placementGroup": "MAC_PROFILE", "specIds": ["spec-mac"]}]}],
                "imageSpecs": [{"specId": "spec-outer", "shortName": "i1398x2034a0"},
                               {"specId": "spec-mac", "shortName": "mac"}]}}]},
            "/appAssetLibraries/lib1/images": {"data": [
                {"id": "img1", "attributes": {"fileName": "1.png", "specId": "spec-outer"}}]},
            "/appStoreVersionLocalizations/vl-gb/placements": {
                "data": [{"id": "p1", "attributes": {"placementType": "APP_SCREENSHOT",
                                                     "placementGroup": "IPHONE_DUO_PROFILE"},
                          "relationships": {"image": {"data": {"id": "img1"}}}}],
                "included": [{"id": "img1", "attributes": {
                    "fileName": "1.png", "imageAsset": {"width": 1398, "height": 2034}}}]},
        })

    def run_inspect(self, dump_path=None):
        return quiet(testflight.inspect_screenshots, testflight.ReadOnlyAsc(self.asc), "app1",
                     {"id": "v1", "attributes": {}}, dump_path)

    def test_only_gets_and_everything_relevant_is_printed(self):
        with tempfile.TemporaryDirectory() as tmp:
            dump_path = pathlib.Path(tmp) / "inspect.json"
            result, out = self.run_inspect(dump_path)
            self.assertEqual(result, 0)
            self.assertEqual({c[0] for c in self.asc.calls}, {"GET"})
            # Both pages of the set, raw attributes and relationships, without upload URLs.
            self.assertIn("APP_IPHONE_DUO", out)
            self.assertIn('"sourceFileChecksum": "abc"', out)
            self.assertIn("1-inner.png", out)
            self.assertIn("appScreenshotSet", out)
            self.assertNotIn("secret.invalid", out)
            # Reference data narrowed to the groups here; the placement with its image.
            self.assertIn("IPHONE_DUO_PROFILE", out)
            self.assertIn("i1398x2034a0", out)
            self.assertNotIn('"shortName": "mac"', out)
            self.assertIn("IPHONE_DUO_PROFILE [None] placement p1 -> image img1: '1.png' 1398×2034",
                          out)
            self.assertIn('"placementGroup": "IPHONE_DUO_PROFILE"',
                          dump_path.read_text(encoding="utf-8"))

    def test_an_unreadable_asset_library_still_shows_the_sets(self):
        real = self.asc.call

        def failing(method, path, params=None, body=None):
            if path.endswith("/assetLibrary"):
                raise testflight.AscError(403, "FORBIDDEN")
            return real(method, path, params, body)

        self.asc.call = failing
        result, out = self.run_inspect()
        self.assertEqual(result, 0)
        self.assertIn("APP_IPHONE_DUO", out)
        self.assertIn("could not be read: HTTP 403", out)

    def test_read_only_wrapper_refuses_writes(self):
        wrapper = testflight.ReadOnlyAsc(self.asc)
        for method in ("POST", "PATCH", "DELETE"):
            with self.assertRaises(AssertionError):
                wrapper.call(method, "/appAssetLibraryPlacements", body={})
        self.assertEqual(self.asc.calls, [])
        self.assertEqual(wrapper.app_id(), "app1")


if __name__ == "__main__":
    unittest.main()
