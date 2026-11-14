"""Tests for scripts/play_listing.py against a fake androidpublisher edits service.

    python3 -m unittest discover -s scripts/tests
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
import play_listing  # noqa: E402


class Request:
    def __init__(self, result):
        self.result = result

    def execute(self):
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
    """listings() and images() of an edit; GETs answered from listings / images tables."""

    def __init__(self, listings=None, images=None):
        self.listings_table = listings or []
        self.images_table = images or {}
        self.calls = []

    def listings(self):
        return FakeResource(self, "listings")

    def images(self):
        return FakeResource(self, "images")

    def answer(self, name, method, kwargs):
        if name == "listings" and method == "list":
            return {"listings": self.listings_table}
        if name == "images" and method == "list":
            return {"images": self.images_table.get((kwargs["language"], kwargs["imageType"]), [])}
        return {}

    def writes(self):
        return [(n, m, k) for n, m, k in self.calls if m != "list"]


def write(folder, name, text):
    path = pathlib.Path(folder) / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text + "\n", encoding="utf-8")


def quiet(func, *args, **kwargs):
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        result = func(*args, **kwargs)
    return result, out.getvalue()


def sha(data):
    return hashlib.sha256(data).hexdigest()


class TextTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)
        for locale, suffix in (("en-GB", ""), ("en-US", " US")):
            write(self.root / locale, "title.txt", "BestBefore" + suffix)
            write(self.root / locale, "short-description.txt", "Short" + suffix)
            write(self.root / locale, "full-description.txt", "Full" + suffix)

    def tearDown(self):
        self.tmp.cleanup()

    def test_locales_are_folders_with_a_title(self):
        (self.root / "phone-screenshots").mkdir()
        self.assertEqual(play_listing.listing_locales(self.root), ["en-GB", "en-US"])

    def test_dry_run_writes_nothing(self):
        edits = FakeEdits([{"language": "en-GB", "title": "Old", "shortDescription": "Short",
                            "fullDescription": "Full", "video": "https://youtu.be/x"}])
        changed, out = quiet(play_listing.sync_texts, edits, "pkg", "e", self.root,
                             ["en-GB", "en-US"], False)
        self.assertTrue(changed)
        self.assertEqual(edits.writes(), [])
        self.assertIn("title: 'Old' -> 'BestBefore'", out)
        self.assertIn("en-US: would create", out)

    def test_apply_patches_changed_fields_and_creates_missing_listing(self):
        edits = FakeEdits([{"language": "en-GB", "title": "Old", "shortDescription": "Short",
                            "fullDescription": "Full", "video": "https://youtu.be/x"},
                           {"language": "de-DE", "title": "Alt"}])
        _, out = quiet(play_listing.sync_texts, edits, "pkg", "e", self.root, ["en-GB", "en-US"], True)
        writes = edits.writes()
        patch = next(k for n, m, k in writes if m == "patch")
        # Only the title: the video and the unchanged texts are not sent.
        self.assertEqual(patch["body"], {"language": "en-GB", "title": "BestBefore"})
        update = next(k for n, m, k in writes if m == "update")
        self.assertEqual(update["language"], "en-US")
        self.assertEqual(update["body"], {"language": "en-US", "title": "BestBefore US",
                                          "shortDescription": "Short US", "fullDescription": "Full US"})
        self.assertEqual(len(writes), 2)
        self.assertIn("de-DE: no folder in the repo; left as it is", out)

    def test_up_to_date(self):
        edits = FakeEdits([{"language": "en-GB", "title": "BestBefore", "shortDescription": "Short",
                            "fullDescription": "Full\n"}])
        changed, _ = quiet(play_listing.sync_texts, edits, "pkg", "e", self.root, ["en-GB"], True)
        self.assertFalse(changed)
        self.assertEqual(edits.writes(), [])


def img(name, data):
    return {"name": name, "path": pathlib.Path(name), "sha256": sha(data),
            "sha1": hashlib.sha1(data).hexdigest()}


class PlanImageTests(unittest.TestCase):
    def test_matching_prefix_is_kept(self):
        local = [img("1.png", b"a"), img("2.png", b"b2"), img("3.png", b"c")]
        remote = [{"id": "1", "sha256": sha(b"a")}, {"id": "2", "sha256": sha(b"b")},
                  {"id": "3", "sha256": sha(b"c")}]
        kept, delete, upload = play_listing.plan_images(local, remote)
        self.assertEqual(kept, 1)
        self.assertEqual([r["id"] for r in delete], ["2", "3"])
        self.assertEqual([i["name"] for i in upload], ["2.png", "3.png"])

    def test_identical_is_left_alone_and_case_is_ignored(self):
        local = [img("1.png", b"a")]
        kept, delete, upload = play_listing.plan_images(local, [{"id": "1", "sha256": sha(b"a").upper()}])
        self.assertEqual((kept, delete, upload), (1, [], []))

    def test_sha1_when_sha256_is_missing(self):
        local = [img("1.png", b"a")]
        kept, _, _ = play_listing.plan_images(local, [{"id": "1", "sha1": hashlib.sha1(b"a").hexdigest()}])
        self.assertEqual(kept, 1)

    def test_extra_remote_images_are_deleted(self):
        kept, delete, upload = play_listing.plan_images([img("1.png", b"a")],
                                                        [{"id": "1", "sha256": sha(b"a")}, {"id": "2"}])
        self.assertEqual((kept, [r["id"] for r in delete], upload), (1, ["2"], []))

    def test_more_than_eight_screenshots_fails(self):
        with tempfile.TemporaryDirectory() as folder:
            for i in range(1, 10):
                (pathlib.Path(folder) / f"{i}.png").write_bytes(bytes([i]))
            with self.assertRaises(play_listing.ListingError):
                play_listing.local_screenshots(folder)

    def test_screenshots_in_number_order(self):
        with tempfile.TemporaryDirectory() as folder:
            for name in ("10.png", "2.png", "1.png", "notes.txt"):
                (pathlib.Path(folder) / name).write_bytes(name.encode())
            self.assertEqual([i["name"] for i in play_listing.local_screenshots(folder)],
                             ["1.png", "2.png", "10.png"])


class ImageSyncTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)
        for locale in ("en-GB", "en-US"):
            write(self.root / locale, "title.txt", "T")
        shots = self.root / "en-GB" / "phone-screenshots"
        shots.mkdir()
        for i in (1, 2):
            (shots / f"{i}.png").write_bytes(b"shot%d" % i)
        (self.root / "en-GB" / "feature-graphic.png").write_bytes(b"feature")
        (self.root / "icon-512.png").write_bytes(b"icon")

    def tearDown(self):
        self.tmp.cleanup()

    def test_targets_leave_locales_without_images_alone(self):
        targets = play_listing.image_targets(self.root, ["en-GB", "en-US"], "en-GB")
        self.assertEqual([(lang, kind, len(files)) for lang, kind, files in targets],
                         [("en-GB", "phoneScreenshots", 2), ("en-GB", "featureGraphic", 1),
                          ("en-GB", "icon", 1)])
        self.assertEqual([t[1] for t in play_listing.image_targets(self.root, ["en-US"], None)], [])

    def test_apply_deletes_then_uploads_in_order(self):
        edits = FakeEdits(images={
            ("en-GB", "phoneScreenshots"): [{"id": "p1", "sha256": sha(b"shot1")},
                                            {"id": "p-old", "sha256": sha(b"old")}],
            ("en-GB", "featureGraphic"): [{"id": "f", "sha256": sha(b"feature")}],
            ("en-GB", "icon"): [{"id": "i", "sha256": sha(b"old icon")}],
        })
        targets = play_listing.image_targets(self.root, ["en-GB"], "en-GB")
        changed, out = quiet(play_listing.sync_images, edits, "pkg", "e", targets, True, lambda p: p.name)
        self.assertTrue(changed)
        writes = [(m, k.get("imageType"), k.get("imageId"), k.get("media_body")) for _, m, k in edits.writes()]
        self.assertEqual(writes, [("delete", "phoneScreenshots", "p-old", None),
                                  ("upload", "phoneScreenshots", None, "2.png"),
                                  ("delete", "icon", "i", None),
                                  ("upload", "icon", None, "icon-512.png")])
        self.assertIn("featureGraphic: 1 in the repo, 1 on Play; up to date", out)

    def test_dry_run_uploads_nothing(self):
        edits = FakeEdits()
        targets = play_listing.image_targets(self.root, ["en-GB"], "en-GB")
        changed, out = quiet(play_listing.sync_images, edits, "pkg", "e", targets, False, lambda p: p)
        self.assertTrue(changed)
        self.assertEqual(edits.writes(), [])
        self.assertIn("would upload: 1.png, 2.png", out)


class GateTests(unittest.TestCase):
    def test_over_limit_text_stops_the_sync(self):
        import check_store_listing
        with tempfile.TemporaryDirectory() as root:
            folder = pathlib.Path(root) / "google-play" / "en-GB"
            write(folder, "title.txt", "Best food app")
            write(folder, "short-description.txt", "S" * 81)
            write(folder, "full-description.txt", "F")
            with mock.patch.object(check_store_listing, "ROOT", pathlib.Path(root)):
                with self.assertRaises(play_listing.ListingError) as caught:
                    play_listing.check_listing_texts()
        message = str(caught.exception)
        self.assertIn("81 characters (limit 80)", message)
        self.assertIn("best", message)

    def test_repo_listing_passes(self):
        play_listing.check_listing_texts()

    def test_repo_image_folders_are_within_limits(self):
        play_listing.image_targets(play_listing.LISTING_DIR,
                                   play_listing.listing_locales(), "en-GB")


if __name__ == "__main__":
    unittest.main()
