"""Tests for the platform choice of a release: scripts/release's --platform and notes base, and
scripts/check_release_version.sh's reading of release-notes/vX.Y.Z/platform. Offline: no git
remote, gh or claude is needed.

    python3 -m unittest discover -s scripts/tests
"""
import contextlib
import importlib.machinery
import importlib.util
import io
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
_loader = importlib.machinery.SourceFileLoader("release", str(SCRIPTS / "release"))
_spec = importlib.util.spec_from_loader("release", _loader)
release = importlib.util.module_from_spec(_spec)
_loader.exec_module(release)

LOCALES = ["en-GB", "ru-RU", "no-NO", "es-ES", "es-419", "de-DE", "fr-FR", "fr-CA", "sv-SE",
           "da-DK"]


class ReleaseArgumentsTest(unittest.TestCase):
    def test_default_is_both_apps(self):
        args = release.parse_args([])
        self.assertIsNone(args.platform)
        self.assertEqual(release.platforms_of(args.platform), ("android", "ios"))

    def test_one_platform(self):
        self.assertEqual(release.parse_args(["--platform", "ios"]).platform, "ios")
        self.assertEqual(release.parse_args(["--platform", "android", "--dry-run"]).platform,
                         "android")

    def test_unknown_platform_is_refused(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            release.parse_args(["--platform", "web"])

    def test_locales_match_the_check(self):
        # scripts/check_release_version.sh requires the notes scripts/release writes.
        check = (SCRIPTS / "check_release_version.sh").read_text(encoding="utf-8")
        self.assertIn("LOCALES=(" + " ".join(release.LOCALES) + ")", check)


class ReadPlatformTest(unittest.TestCase):
    def test_no_file_is_both(self):
        self.assertIsNone(release.read_platform(None))

    def test_platforms(self):
        self.assertEqual(release.read_platform("ios\n"), "ios")
        self.assertEqual(release.read_platform("android"), "android")

    def test_anything_else_stops(self):
        for text in ("iOS", "both", "android ios", ""):
            with self.subTest(text=text), self.assertRaises(SystemExit):
                release.read_platform(text)


class NotesBaseTest(unittest.TestCase):
    # Newest first, as released_tags() returns them.
    BOTH = [("v2.2.2", None), ("v2.2.1", None)]
    AFTER_IOS_ONLY = [("v2.3.0", "ios"), ("v2.2.2", None)]

    def test_every_release_for_both(self):
        for platform in (None, "android", "ios"):
            with self.subTest(platform=platform):
                self.assertEqual(release.notes_base(self.BOTH, platform, "v2.2.2"), "v2.2.2")

    def test_android_after_an_ios_only_release_counts_from_its_own_last(self):
        self.assertEqual(release.notes_base(self.AFTER_IOS_ONLY, "android", "v2.3.0"), "v2.2.2")
        self.assertEqual(release.notes_base(self.AFTER_IOS_ONLY, "ios", "v2.3.0"), "v2.3.0")
        # Both apps: from the older, so Android hears what the iOS-only release brought too.
        self.assertEqual(release.notes_base(self.AFTER_IOS_ONLY, None, "v2.3.0"), "v2.2.2")

    def test_versions_compare_as_numbers(self):
        tags = [("v2.10.0", "android"), ("v2.9.0", "ios")]
        self.assertEqual(release.notes_base(tags, None, "v2.10.0"), "v2.9.0")

    def test_platform_never_released_falls_back(self):
        self.assertEqual(release.notes_base([("v2.3.0", "ios")], "android", "v2.3.0"), "v2.3.0")


class CheckReleaseVersionTest(unittest.TestCase):
    VERSION = "2.3.0"

    def setUp(self):
        self.root = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)
        (self.root / "scripts").mkdir()
        shutil.copy(SCRIPTS / "check_release_version.sh", self.root / "scripts")
        (self.root / "Version.xcconfig").write_text(f"MARKETING_VERSION = {self.VERSION}\n")
        project = self.root / "iosApp" / "FoodTracker.xcodeproj"
        project.mkdir(parents=True)
        (project / "project.pbxproj").write_text("// no version here\n")
        self.notes = self.root / "release-notes" / f"v{self.VERSION}"
        self.notes.mkdir(parents=True)
        for locale in LOCALES:
            (self.notes / f"{locale}.txt").write_text("• Fixes\n")

    def check(self, *args):
        output = self.root / "github_output"
        env = dict(os.environ, GITHUB_OUTPUT=str(output))
        env.pop("GITHUB_ENV", None)
        result = subprocess.run(["bash", str(self.root / "scripts" / "check_release_version.sh"),
                                 self.VERSION, *args], capture_output=True, text=True, env=env)
        written = output.read_text() if output.exists() else ""
        return result.returncode, result.stdout, written

    def set_platform(self, text):
        (self.notes / "platform").write_text(text)

    def test_without_the_file_the_release_is_for_both(self):
        code, out, written = self.check()
        self.assertEqual(code, 0, out)
        self.assertIn("(both)", out)
        self.assertEqual(written, "platform=both\n")
        for platform in ("android", "ios"):
            self.assertEqual(self.check(platform)[0], 0)

    def test_one_platform(self):
        self.set_platform("ios\n")
        code, out, written = self.check()
        self.assertEqual(code, 0, out)
        self.assertEqual(written, "platform=ios\n")
        self.assertEqual(self.check("ios")[0], 0)
        code, out, _ = self.check("android")
        self.assertEqual(code, 1)
        self.assertIn("for ios only, not android", out)

    def test_a_wrong_platform_fails(self):
        for text in ("iOS\n", "both\n", "android ios\n", ""):
            with self.subTest(text=text):
                self.set_platform(text)
                code, out, written = self.check()
                self.assertEqual(code, 1, out)
                self.assertIn("::error::", out)
                self.assertEqual(written, "")

    def test_a_wrong_wanted_platform_fails(self):
        self.assertEqual(self.check("web")[0], 1)

    def test_notes_still_required_for_one_platform(self):
        self.set_platform("android\n")
        (self.notes / "es-419.txt").unlink()
        code, out, _ = self.check("android")
        self.assertEqual(code, 1)
        self.assertIn("es-419", out)


if __name__ == "__main__":
    unittest.main()
