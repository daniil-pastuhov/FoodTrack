"""Tests for scripts/screenshot_check.py's pure parts: arguments, the device list, reg-cli's JSON
and the summary. Offline: no device, npx or Pillow is needed.

    python3 -m unittest discover -s scripts/tests
"""
import contextlib
import io
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))
import screenshot_check as sc  # noqa: E402


def parse(*argv):
    with contextlib.redirect_stderr(io.StringIO()):
        return sc.parse_args(list(argv))


class ArgsTest(unittest.TestCase):
    def test_defaults_are_every_language_and_the_default_devices(self):
        a = parse()
        self.assertEqual(a.langs, ["en", "ru", "nb", "es", "de", "fr", "sv", "da"])
        self.assertEqual(a.device_keys, [k for k, d in sc.DEVICES.items() if d["default"]])
        self.assertNotIn("ios-duo-inner", a.device_keys)  # needs Device Hub: only when named
        self.assertFalse(a.approve or a.no_build or a.keep_device)

    def test_lists_are_comma_separated(self):
        a = parse("--langs", "es, de", "--devices", "android-phone,ios-ipad-13")
        self.assertEqual(a.langs, ["es", "de"])
        self.assertEqual(a.device_keys, ["android-phone", "ios-ipad-13"])

    def test_unknown_language_or_device_exits_2(self):
        for argv in (["--langs", "en,xx"], ["--devices", "pixel"], ["--langs", ""],
                     ["--approve-last", "--approve"], ["--platform", "web"]):
            with self.assertRaises(SystemExit) as e:
                parse(*argv)
            self.assertEqual(e.exception.code, 2, argv)

    def test_platform_filters_the_devices(self):
        self.assertEqual(parse("--platform", "android").device_keys, ["android-phone"])
        ios = parse("--platform", "ios").device_keys
        self.assertTrue(ios and all(k.startswith("ios-") for k in ios))
        with self.assertRaises(SystemExit):
            parse("--platform", "android", "--devices", "ios-ipad-13")


class DevicesTest(unittest.TestCase):
    def test_table_order_is_kept_whatever_order_they_are_named_in(self):
        self.assertEqual(sc.select_devices("all", ["ios-ipad-13", "android-phone"]),
                         ["android-phone", "ios-ipad-13"])

    def test_every_device_names_what_its_platform_needs(self):
        for key, d in sc.DEVICES.items():
            self.assertIn(d["platform"], ("android", "ios"))
            self.assertTrue(key.startswith(d["platform"] + "-"))
            self.assertIn("avd" if d["platform"] == "android" else "name", d)


class SummaryTest(unittest.TestCase):
    REG = {"failedItems": ["b/en/2.png", "a/en/1.png"], "newItems": ["a/ru/1.png"], "deletedItems": [],
           "passedItems": ["a/en/3.png"], "diffItems": ["a/en/1.png", "b/en/2.png"]}

    def test_classify_sorts_reg_cli_lists(self):
        g = sc.classify(self.REG)
        self.assertEqual(g["changed"], ["a/en/1.png", "b/en/2.png"])
        self.assertEqual((g["new"], g["deleted"], g["unchanged"]), (["a/ru/1.png"], [], ["a/en/3.png"]))

    def test_exit_codes(self):
        empty = sc.classify({})
        self.assertEqual(sc.result_code(empty, []), 0)
        self.assertEqual(sc.result_code(sc.classify({"newItems": ["x"]}), []), 1)
        self.assertEqual(sc.result_code(sc.classify({"deletedItems": ["x"]}), []), 1)
        self.assertEqual(sc.result_code(empty, ["android-phone"]), 2)

    def test_summary_lists_changes_with_ratios(self):
        g = sc.classify(self.REG)
        text = sc.format_summary(g, {"a/en/1.png": 0.0123, "b/en/2.png": None}, [],
                                 {"report": "r/index.html"}, 1)
        lines = text.splitlines()
        self.assertEqual(lines[0], "changed 2 · new 1 · deleted 0 · unchanged 1")
        self.assertIn("  ~ a/en/1.png  1.230%", lines)
        self.assertIn("  ~ b/en/2.png  ?", lines)
        self.assertIn("  + a/ru/1.png", lines)
        self.assertEqual(lines[-2:], ["report: r/index.html", "result: differences"])

    def test_long_lists_are_capped(self):
        g = sc.classify({"failedItems": ["f%02d.png" % i for i in range(40)]})
        lines = sc.format_summary(g, {}, [], {}, 1).splitlines()
        self.assertIn("…and %d more" % (40 - sc.LIST_CAP), lines)
        self.assertEqual(sum(l.startswith("  ~ ") for l in lines), sc.LIST_CAP)

    def test_verdicts(self):
        self.assertEqual(sc.verdict(0), "no differences")
        self.assertEqual(sc.verdict(0, approved=12), "baseline approved")
        self.assertEqual(sc.verdict(2, no_baseline=True), "no baseline")
        self.assertEqual(sc.verdict(2), "run failed")
        text = sc.format_summary(None, {}, ["no baseline …"], {}, 2, no_baseline=True)
        self.assertTrue(text.endswith("result: no baseline"))
        self.assertNotIn("changed", text)

    def test_device_line(self):
        self.assertEqual(sc.device_line(dict(key="android-phone", ok=True, shots=48, seconds=754.4, error=None)),
                         "  android-phone    48 shots  12m34s")
        self.assertIn("FAILED: no boot", sc.device_line(dict(key="ios-ipad-13", ok=False, shots=0, seconds=5,
                                                                error="no boot")))

    def test_stale_baseline_is_noted_per_device(self):
        meta = {"a/en": {"date": "2026-11-14"}, "a/ru": {"date": "2026-11-15"}, "b/en": {"date": "2026-11-15"}}
        notes = sc.stale_baseline_notes(meta, [("a", "en"), ("a", "ru"), ("b", "en")], "2026-11-15")
        self.assertEqual(len(notes), 1)
        self.assertTrue(notes[0].startswith("a: baseline from 2026-11-14"))
        self.assertEqual(sc.stale_baseline_notes({}, [("a", "en")], "2026-11-15"), [])


class PairsTest(unittest.TestCase):
    def test_pairs_are_device_and_language_folders_holding_pngs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            for rel in ("android-phone/en/1-home.png", "android-phone/ru/1-home.png", "ios-ipad-13/en/x.txt"):
                (root / rel).parent.mkdir(parents=True, exist_ok=True)
                (root / rel).write_bytes(b"")
            (root / "baseline.json").write_text("{}")
            self.assertEqual(sc.pairs_in(root), [("android-phone", "en"), ("android-phone", "ru")])
            self.assertEqual(sc.pairs_in(root / "missing"), [])


if __name__ == "__main__":
    unittest.main()
