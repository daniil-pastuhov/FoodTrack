"""Offline checks of the apps' translations against English, on both platforms.

Android: androidApp/src/main/res/values-<lang>/strings.xml against values/strings.xml.
iOS: iosApp/FoodTracker/<lang>.lproj/{Localizable.strings,Localizable.stringsdict,InfoPlist.strings}
against en.lproj.

For every app language: the same keys as English (Android strings marked translatable="false"
excepted), the same format placeholders, no apostrophe aapt2 would refuse, on iOS only the
{markers} English has for that key (where English has {device}, either it or {the_device}, as
L10n.withDevice replaces both), and the paid unit (a "spark" in English) never called a credit or token (CLAUDE.md,
"Recipe") where English doesn't use the word itself (the App Check debug token). Standard library only.
"""
import pathlib
import plistlib
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]
RES = ROOT / "androidApp" / "src" / "main" / "res"
IOS = ROOT / "iosApp" / "FoodTracker"
# The app's languages besides English: Android values-<code>, iOS <code>.lproj.
LANGUAGES = ["ru", "nb", "es", "de", "fr", "sv", "da"]
IOS_FILES = ["Localizable.strings", "Localizable.stringsdict", "InfoPlist.strings"]

# printf-style placeholders: Android %1$s, %d, %.1f; iOS %@, %ld, %1$@, %#@name@.
PRINTF = re.compile(r"%(?:\d+\$)?(?:#@\w+@|[-#+ 0,(]*\d*(?:\.\d+)?(?:l{0,2})[sdfx@iu])")
BRACES = re.compile(r"\{[^{}\s]*\}")
IOS_BRACES = {"{device}", "{the_device}"}
UNIT_WORDS = re.compile(r"(?i)(?<!\w)(credits?|tokens?|crédits?|créditos?|kredit\w*|jetons?|кредит\w*|токен\w*)(?!\w)")


def android_strings(path):
    """{name: [texts]} for each translatable string, plural and string-array, with the raw XML
    text of each (for the apostrophe check)."""
    raw = path.read_text(encoding="utf-8")
    root = ET.fromstring(raw)
    out = {}
    for element in root:
        if element.get("translatable") == "false":
            continue
        name = element.get("name")
        if element.tag == "string":
            out[f"string/{name}"] = ["".join(element.itertext())]
        elif element.tag == "plurals":
            out[f"plurals/{name}"] = ["".join(item.itertext()) for item in element.findall("item")]
        elif element.tag == "string-array":
            out[f"array/{name}"] = ["".join(item.itertext()) for item in element.findall("item")]
    return out


def ios_strings(path):
    """{key: [value]} of an old-style .strings file (comments and escapes allowed)."""
    text = path.read_text(encoding="utf-8")
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"(?m)^\s*//.*$", "", text)
    pairs = re.findall(r'"((?:[^"\\]|\\.)*)"\s*=\s*"((?:[^"\\]|\\.)*)"\s*;', text, flags=re.S)
    return {key: [value] for key, value in pairs}


def ios_stringsdict(path):
    """{key: [every string in its plural rules]}."""
    with open(path, "rb") as f:
        data = plistlib.load(f)
    out = {}
    for key, entry in data.items():
        texts = []

        def walk(value):
            if isinstance(value, str):
                texts.append(value)
            elif isinstance(value, dict):
                for k, v in value.items():
                    if k not in ("NSStringFormatSpecTypeKey", "NSStringFormatValueTypeKey"):
                        walk(v)

        walk(entry)
        out[key] = texts
    return out


def placeholders(texts):
    return sorted({p.replace("l", "") for t in texts for p in PRINTF.findall(t.replace("%%", ""))})


class TranslationTest(unittest.TestCase):
    def compare(self, where, english, other, braces=None):
        missing = sorted(set(english) - set(other))
        extra = sorted(set(other) - set(english))
        self.assertEqual(missing, [], f"{where}: keys missing")
        self.assertEqual(extra, [], f"{where}: keys English doesn't have")
        for key, texts in other.items():
            if key not in english:
                continue
            self.assertEqual(placeholders(texts), placeholders(english[key]),
                             f"{where}: {key} has other placeholders than English")
            # English's own "App Check debug token" is a technical term, not the paid unit.
            technical = any(UNIT_WORDS.search(t) for t in english[key])
            known = {m for t in english[key] for m in BRACES.findall(t)}
            for text in texts:
                if not technical:
                    self.assertIsNone(UNIT_WORDS.search(text), f"{where}: {key} calls the paid unit a credit or token: {text}")
                if braces is not None:
                    unknown = set(BRACES.findall(text)) - known - (braces if known & braces else set())
                    self.assertEqual(unknown, set(), f"{where}: {key} has a marker English doesn't")

    def test_android(self):
        english = android_strings(RES / "values" / "strings.xml")
        for language in LANGUAGES:
            path = RES / f"values-{language}" / "strings.xml"
            with self.subTest(language=language):
                self.assertTrue(path.is_file(), f"{path.relative_to(ROOT)} is missing")
                self.compare(f"values-{language}", english, android_strings(path))
                # aapt2 refuses an apostrophe that isn't escaped (outside a "quoted" string).
                for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                    body = re.sub(r"<[^>]+>", "", line)
                    if '"' not in body:
                        self.assertIsNone(re.search(r"(?<!\\)'", body),
                                          f"values-{language}/strings.xml:{number}: unescaped apostrophe")

    def test_ios(self):
        for name in IOS_FILES:
            read = ios_stringsdict if name.endswith("dict") else ios_strings
            english = read(IOS / "en.lproj" / name)
            for language in LANGUAGES:
                path = IOS / f"{language}.lproj" / name
                with self.subTest(language=language, file=name):
                    self.assertTrue(path.is_file(), f"{path.relative_to(ROOT)} is missing")
                    self.compare(f"{language}.lproj/{name}", english, read(path), IOS_BRACES)

    def test_every_language_is_registered_in_the_xcode_project(self):
        project = (ROOT / "iosApp" / "FoodTracker.xcodeproj" / "project.pbxproj").read_text(encoding="utf-8")
        regions = re.search(r"knownRegions = \((.*?)\);", project, flags=re.S).group(1)
        for language in LANGUAGES:
            self.assertIn(language, re.findall(r"[\w-]+", regions))
            for name in IOS_FILES:
                self.assertIn(f"path = {language}.lproj/{name};", project, f"{language}.lproj/{name} isn't in the project")


if __name__ == "__main__":
    unittest.main()
