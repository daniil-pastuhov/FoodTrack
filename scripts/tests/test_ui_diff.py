"""Tests for scripts/ui_diff.py's pure parts: reading Roborazzi's results and the JUnit XML, the
manifest, and the comment it builds. Offline: no Gradle, git or gh is run.

    python3 -m unittest discover -s scripts/tests
"""
import json
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))
import ui_diff as ud  # noqa: E402

BASE = "a" * 40
HEAD = "b" * 40
P = "com.foodtracker.android.ui.design.StatusChipKt.StatusChipPreview"


def entry(kind, name, **extra):
    e = {"type": kind, "golden_file_path": f"/repo/androidApp/src/test/snapshots/images/{name}.png"}
    if kind != "unchanged":
        e["compare_file_path"] = f"/repo/androidApp/build/outputs/roborazzi/{name}_compare.png"
        e["actual_file_path"] = f"/repo/androidApp/build/outputs/roborazzi/{name}_actual.png"
    e.update(extra)
    return e


def manifest(changed=(), added=(), removed=(), unchanged=10):
    entries = [entry("changed", n, diff_percentage=0.0004) for n in changed] + \
              [entry("added", n) for n in added] + \
              [entry("unchanged", f"same{i}") for i in range(unchanged)]
    base_names = [*changed, *removed, *(f"same{i}" for i in range(unchanged))]
    return ud.build_manifest(entries, base_names, [], BASE, HEAD)


class ReadTest(unittest.TestCase):
    def test_results_from_every_summary_and_failures_from_the_xml(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp, "roborazzi", "debug")
            d.mkdir(parents=True)
            (d / "results-summary.json").write_text(json.dumps({"results": [entry("unchanged", "x")]}))
            xml = pathlib.Path(tmp, "xml")
            xml.mkdir()
            (xml / "TEST-a.xml").write_text(
                '<testsuite><testcase classname="A" name="ok"/>'
                '<testcase classname="A" name="boom"><error message="x"/></testcase>'
                '<testcase classname="B" name="bad"><failure message="y"/></testcase></testsuite>')
            self.assertEqual(len(ud.load_entries(str(pathlib.Path(tmp, "roborazzi")))), 1)
            self.assertEqual(ud.failed_tests(str(xml)), ["A.boom", "B.bad"])

    def test_shot_name_is_the_file_name_without_png(self):
        self.assertEqual(ud.shot_name(f"/x/{P}.Light.png"), f"{P}.Light")


class ManifestTest(unittest.TestCase):
    def test_changed_new_removed_and_unchanged(self):
        m = manifest(changed=[f"{P}.Light"], added=[f"{P}.New"], removed=[f"{P}.Gone"], unchanged=3)
        self.assertEqual([s["name"] for s in m["changed"]], [f"{P}.Light"])
        self.assertEqual([s["name"] for s in m["added"]], [f"{P}.New"])
        self.assertEqual([s["name"] for s in m["removed"]], [f"{P}.Gone"])
        self.assertEqual(m["unchanged"], 3)
        self.assertEqual(ud.status(m), "differences")

    def test_nothing_different(self):
        self.assertEqual(ud.status(manifest()), "none")

    def test_no_base(self):
        self.assertEqual(ud.status({"base": BASE, "head": HEAD, "has_base": False}), "no-base")

    def test_image_files(self):
        self.assertEqual(ud.image_file("changed", "x"), "x_compare.png")
        self.assertEqual(ud.image_file("new", "x"), "x.png")


class CommentTest(unittest.TestCase):
    def url(self, kind, name):
        return f"https://img/{ud.image_file(kind, name)}"

    def test_marker_counts_and_inline_images(self):
        body = ud.comment_body(manifest(changed=[f"{P}.Light"], added=[f"{P}.New"], removed=[f"{P}.Gone"]),
                               self.url, artifact_url="https://run/artifact")
        self.assertTrue(body.startswith(ud.MARKER))
        self.assertIn("**1 changed, 1 new, 1 removed** (10 unchanged)", body)
        self.assertIn("`aaaaaaa` → head `bbbbbbb`", body)
        self.assertIn(f'<img src="https://img/{P}.Light_compare.png" alt="ui.design.StatusChipKt.StatusChipPreview.Light: reference | diff | new"', body)
        self.assertIn(f'<img src="https://img/{P}.New.png"', body)
        self.assertIn("changed, 0.04 % of pixels", body)
        # Removed ones have no picture in the comment, only their name.
        self.assertIn("- `ui.design.StatusChipKt.StatusChipPreview.Gone` · removed", body)
        self.assertIn("[run's artifact](https://run/artifact)", body)

    def test_inline_images_are_capped_and_the_rest_listed(self):
        names = [f"{P}.N{i:02}" for i in range(ud.INLINE_LIMIT + 5)]
        body = ud.comment_body(manifest(changed=names), self.url)
        self.assertEqual(body.count("<img "), ud.INLINE_LIMIT)
        self.assertEqual(body.count("<details open>"), ud.OPEN_LIMIT)
        self.assertIn("The other 5 screenshots:", body)
        self.assertIn(f"- `ui.design.StatusChipKt.StatusChipPreview.N{ud.INLINE_LIMIT + 4}`", body)

    def test_a_long_list_is_cut(self):
        names = [f"{P}.N{i:03}" for i in range(ud.NAME_LIST_LIMIT + 7)]
        body = ud.comment_body(manifest(changed=names))
        self.assertNotIn("<img ", body)
        self.assertIn("- and 7 more", body)

    def test_an_image_not_uploaded_is_listed_instead(self):
        body = ud.comment_body(manifest(changed=[f"{P}.A", f"{P}.B"]),
                               lambda kind, name: None if name.endswith(".B") else self.url(kind, name))
        self.assertEqual(body.count("<img "), 1)
        self.assertIn("The other 1 screenshot:", body)

    def test_no_differences_and_no_base(self):
        none = ud.comment_body(manifest())
        self.assertTrue(none.startswith(ud.MARKER))
        self.assertIn("No differences", none)
        no_base = ud.comment_body({"base": BASE, "head": HEAD, "has_base": False})
        self.assertIn("has no UI screenshot check", no_base)
        no_android = {"head": HEAD, "android": False}
        self.assertEqual(ud.status(no_android), "no-android")
        self.assertIn("no longer changes the Android app", ud.comment_body(no_android))


if __name__ == "__main__":
    unittest.main()
