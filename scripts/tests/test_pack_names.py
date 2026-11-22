"""Tests for the pack names and descriptions in the stores: scripts/play_products.py --relist
(Play's listings) and scripts/testflight.py iap-names (the App Store's in-app purchase
localizations). Offline: the tables against the app's strings and the stores' limits, and the
pure planning parts, with App Store Connect faked; no Google library or credential.

    python3 -m unittest discover -s scripts/tests
"""
import argparse
import contextlib
import copy
import io
import pathlib
import re
import sys
import unittest
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import play_products  # noqa: E402
import testflight  # noqa: E402
from test_translations import UNIT_WORDS  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parents[2]
RES = ROOT / "androidApp" / "src" / "main" / "res"
# Play Console's limits for a one-time product's listing.
PLAY_TITLE_LIMIT = 55
PLAY_DESCRIPTION_LIMIT = 200
# App Store Connect locale or Play code -> the Android values folder of its language.
VALUES = {"en": "values", "ru": "values-ru", "no": "values-nb", "nb": "values-nb", "es": "values-es",
          "de": "values-de", "fr": "values-fr", "sv": "values-sv", "da": "values-da"}


def pack_name(folder, n):
    """recipe_pack_name for n from a strings.xml: Russian's "many" form for 10, 50 and 100 (they
    end in 0), every other app language's "other"."""
    text = (RES / folder / "strings.xml").read_text(encoding="utf-8")
    block = re.search(r'<plurals name="recipe_pack_name">(.*?)</plurals>', text, re.S).group(1)
    items = dict(re.findall(r'<item quantity="(\w+)">(.*?)</item>', block))
    form = "many" if folder == "values-ru" else "other"
    return items[form].replace("%1$d", str(n))


def quiet(func, *args, **kwargs):
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        result = func(*args, **kwargs)
    return result, out.getvalue()


class PlayListingTableTests(unittest.TestCase):
    def test_titles_are_the_apps_pack_names(self):
        for product, (n, _, _) in play_products.PRODUCTS.items():
            for listing in play_products.listings(product):
                folder = VALUES[listing["languageCode"].split("-")[0]]
                self.assertEqual(listing["title"], pack_name(folder, n), (product, listing["languageCode"]))

    def test_within_plays_limits_and_no_unit_words(self):
        for product in play_products.PRODUCTS:
            for listing in play_products.listings(product):
                where = (product, listing["languageCode"])
                self.assertLessEqual(len(listing["title"]), PLAY_TITLE_LIMIT, where)
                self.assertLessEqual(len(listing["description"]), PLAY_DESCRIPTION_LIMIT, where)
                self.assertIsNone(UNIT_WORDS.search(listing["title"] + " " + listing["description"]), where)


def play_product(listings):
    return {"packageName": "com.foodtracker.android", "productId": "recipes_10",
            "listings": listings, "regionsVersion": {"version": "2026/01"},
            "purchaseOptions": [{"purchaseOptionId": "buy", "state": "ACTIVE"}]}


class RelistTests(unittest.TestCase):
    def test_equal_listings_mean_nothing_to_do(self):
        wanted = play_products.listings("recipes_10")
        body, changes, kept = play_products.relist_body(play_product(copy.deepcopy(wanted)), wanted)
        self.assertIsNone(body)
        self.assertEqual((changes, kept), ([], []))

    def test_changed_and_new_languages_and_only_listings_sent(self):
        wanted = play_products.listings("recipes_10")
        old = [{"languageCode": "en-GB", "title": "10 recipes", "description": "Ten dishes."},
               {"languageCode": "ru-RU", **{k: v for k, v in wanted[1].items() if k != "languageCode"}},
               {"languageCode": "it-IT", "title": "10 ricette", "description": "Dieci."}]
        body, changes, kept = play_products.relist_body(play_product(old), wanted)
        self.assertEqual(set(body), {"packageName", "productId", "listings"})
        codes = [c for c, _, _ in changes]
        self.assertIn("en-GB", codes)
        self.assertNotIn("ru-RU", codes)
        self.assertEqual(len(codes), len(wanted) - 1)
        self.assertEqual(kept, ["it-IT"])
        # A language Play has and the table doesn't is sent back, or the patch would delete it.
        self.assertIn({"languageCode": "it-IT", "title": "10 ricette", "description": "Dieci."}, body["listings"])
        self.assertEqual(len(body["listings"]), len(wanted) + 1)
        lines = "\n".join(play_products.describe_relist("recipes_10", changes, kept))
        self.assertIn("'10 recipes'", lines)
        self.assertIn("-> '10 sparks'", lines)


class IapTableTests(unittest.TestCase):
    def setUp(self):
        self.table = testflight.iap_name_table()

    def test_every_pack_has_every_locale(self):
        self.assertEqual(set(self.table), set(play_products.PRODUCTS))
        for locales in self.table.values():
            self.assertEqual(set(locales), set(testflight.IAP_LISTINGS))

    def test_covers_every_app_store_listing_locale(self):
        self.assertLessEqual(set(testflight.listing_locales()), set(testflight.IAP_LISTINGS))

    def test_within_limits_and_no_unit_words(self):
        self.assertEqual(testflight.check_iap_names(self.table), [])
        for product, locales in self.table.items():
            for locale, texts in locales.items():
                self.assertLessEqual(len(texts["name"]), testflight.IAP_NAME_LIMIT)
                self.assertLessEqual(len(texts["description"]), testflight.IAP_DESCRIPTION_LIMIT)
                self.assertIsNone(UNIT_WORDS.search(texts["name"] + " " + texts["description"]),
                                  (product, locale))

    def test_names_are_the_apps_pack_names(self):
        for product, (n, _, _) in play_products.PRODUCTS.items():
            for locale, texts in self.table[product].items():
                self.assertEqual(texts["name"], pack_name(VALUES[locale.split("-")[0]], n), (product, locale))

    def test_over_a_limit_is_reported(self):
        table = {"recipes_10": {"en-GB": {"name": "x" * 31, "description": "y" * 46}}}
        problems = testflight.check_iap_names(table)
        self.assertEqual(len(problems), 2)
        self.assertEqual(testflight.check_iap_names({"recipes_10": {"ru": {"name": "", "description": "d"}}}),
                         ["recipes_10 ru: empty name"])


def version(id_, number, state):
    return {"id": id_, "type": "inAppPurchaseVersions", "attributes": {"version": number, "state": state}}


class IapTargetTests(unittest.TestCase):
    def test_approved_only_makes_a_new_version_from_it(self):
        action, base, _ = testflight.iap_name_target([version("v1", 1, "APPROVED")])
        self.assertEqual((action, base["id"]), ("new", "v1"))

    def test_draft_is_edited_in_place(self):
        action, target, _ = testflight.iap_name_target(
            [version("v1", 1, "APPROVED"), version("v2", 2, "PREPARE_FOR_SUBMISSION")])
        self.assertEqual((action, target["id"]), ("edit", "v2"))

    def test_review_under_way_blocks(self):
        for state in ("READY_FOR_REVIEW", "WAITING_FOR_REVIEW", "IN_REVIEW", "DEVELOPER_REJECTED"):
            action, target, _ = testflight.iap_name_target(
                [version("v1", 1, "APPROVED"), version("v2", 2, state)])
            self.assertEqual((action, target["id"]), ("blocked", "v2"), state)

    def test_history_before_the_live_version_is_ignored(self):
        action, base, _ = testflight.iap_name_target(
            [version("v1", 1, "DEVELOPER_REJECTED"), version("v2", 2, "REJECTED"), version("v3", 3, "APPROVED")])
        self.assertEqual((action, base["id"]), ("new", "v3"))

    def test_rejected_after_live_makes_a_new_version_from_the_live_one(self):
        action, base, _ = testflight.iap_name_target([version("v1", 1, "APPROVED"), version("v2", 2, "REJECTED")])
        self.assertEqual((action, base["id"]), ("new", "v1"))


class IapPlanTests(unittest.TestCase):
    WANTED = {"en-GB": {"name": "10 sparks", "description": "New."},
              "ru": {"name": "10 искр", "description": "Новое."},
              "de-DE": {"name": "10 Funken", "description": "Neu."}}

    def test_update_same_missing_extra(self):
        current = {"en-GB": {"id": "l1", "name": "10 recipes", "description": "Old."},
                   "ru": {"id": "l2", "name": "10 искр", "description": "Новое."},
                   "it": {"id": "l3", "name": "10 ricette", "description": "Vecchio."}}
        plan = {locale: action for locale, action, _, _ in testflight.plan_iap_names(current, self.WANTED)}
        self.assertEqual(plan, {"en-GB": "update", "ru": "same", "de-DE": "missing", "it": "extra"})
        plan = {locale: action for locale, action, _, _ in
                testflight.plan_iap_names(current, self.WANTED, add_missing=True)}
        self.assertEqual(plan["de-DE"], "create")

    def test_missing_description_counts_as_empty(self):
        plan = testflight.plan_iap_names({"en-GB": {"id": "l1", "name": "10 sparks"}},
                                         {"en-GB": {"name": "10 sparks", "description": "New."}})
        self.assertEqual(plan[0][1], "update")


class FakeAsc:
    """App Store Connect with one purchase, recipes_10 (id iap10), whose versions and their
    localizations change as the calls do."""

    def __init__(self, versions, localizations):
        self.versions = versions                  # [version(...)]
        self.localizations = localizations        # {version id: {locale: {"id", "name", "description"}}}
        self.calls = []

    def app_id(self):
        return "app"

    def call(self, method, path, params=None, body=None):
        self.calls.append((method, path, body))
        if method == "GET":
            if path == "/apps":
                return {"data": [{"id": "app"}]}
            if path == "/apps/app/inAppPurchasesV2":
                return {"data": [{"id": "iap10", "attributes": {"productId": "recipes_10",
                                                                "inAppPurchaseType": "CONSUMABLE",
                                                                "state": "APPROVED"}}]}
            if path == "/v2/inAppPurchases/iap10/versions":
                return {"data": self.versions}
            if path == "/v2/inAppPurchases/iap10/inAppPurchaseLocalizations":
                raise testflight.AscError(404, "gone")
            match = re.fullmatch(r"/inAppPurchaseVersions/(\w+)/localizations", path)
            if match:
                return {"data": [{"id": l["id"], "attributes": {"locale": locale, "name": l["name"],
                                                               "description": l["description"]}}
                                 for locale, l in self.localizations.get(match.group(1), {}).items()]}
            return {"data": []}
        if method == "POST" and path == "/inAppPurchaseVersions":
            new = version("v2", 2, "PREPARE_FOR_SUBMISSION")
            self.versions.append(new)
            self.localizations["v2"] = {locale: {**l, "id": l["id"] + "-v2"}
                                        for locale, l in copy.deepcopy(self.localizations["v1"]).items()}
            return {"data": new}
        if method == "PATCH" and path.startswith("/v2/inAppPurchaseLocalizations/"):
            lid = path.rsplit("/", 1)[1]
            for locs in self.localizations.values():
                for l in locs.values():
                    if l["id"] == lid:
                        l.update(body["data"]["attributes"])
            return {}
        if method == "POST" and path == "/v2/inAppPurchaseLocalizations":
            attrs = body["data"]["attributes"]
            vid = body["data"]["relationships"]["version"]["data"]["id"]
            self.localizations[vid][attrs["locale"]] = {"id": "added", "name": attrs["name"],
                                                       "description": attrs["description"]}
            return {"data": {"id": "added"}}
        raise AssertionError(f"unexpected {method} {path}")

    def writes(self):
        return [(m, p) for m, p, _ in self.calls if m != "GET"]


def args(apply=False, add_missing=False):
    return argparse.Namespace(apply=apply, add_missing=add_missing, products=["recipes_10"])


class CmdIapNamesTests(unittest.TestCase):
    def fake(self):
        return FakeAsc([version("v1", 1, "APPROVED")],
                       {"v1": {"en-GB": {"id": "l1", "name": "10 recipes", "description": "Ten recipes."},
                               "ru": {"id": "l2", "name": "10 рецептов", "description": "Десять рецептов."}}})

    def run_cmd(self, fake, **kw):
        with mock.patch.object(testflight, "Asc", lambda: fake):
            return quiet(testflight.cmd_iap_names, args(**kw))

    def test_dry_run_reads_and_writes_nothing(self):
        fake = self.fake()
        result, out = self.run_cmd(fake)
        self.assertEqual(result, 0)
        self.assertEqual(fake.writes(), [])
        self.assertIn("'10 recipes'", out)
        self.assertIn("-> '10 sparks'", out)
        self.assertIn("would create a new version", out)
        self.assertIn("de-DE: not on App Store Connect", out)

    def test_apply_makes_a_version_and_patches_its_localizations_only(self):
        fake = self.fake()
        result, out = self.run_cmd(fake, apply=True)
        self.assertEqual(result, 0, out)
        self.assertEqual(fake.writes(), [("POST", "/inAppPurchaseVersions"),
                                         ("PATCH", "/v2/inAppPurchaseLocalizations/l1-v2"),
                                         ("PATCH", "/v2/inAppPurchaseLocalizations/l2-v2")])
        self.assertEqual(fake.localizations["v1"]["en-GB"]["name"], "10 recipes")  # the live version untouched
        self.assertEqual(fake.localizations["v2"]["ru"]["name"], "10 искр")
        # A second run edits nothing: the draft has the table's texts.
        fake.calls.clear()
        result, out = self.run_cmd(fake, apply=True)
        self.assertEqual((result, fake.writes()), (0, []), out)
        self.assertIn("nothing to change", out)

    def test_add_missing_adds_the_tables_other_locales(self):
        fake = self.fake()
        result, out = self.run_cmd(fake, apply=True, add_missing=True)
        self.assertEqual(result, 0, out)
        added = [p for m, p in fake.writes() if p == "/v2/inAppPurchaseLocalizations"]
        self.assertEqual(len(added), len(testflight.IAP_LISTINGS) - 2)

    def test_review_under_way_writes_nothing_and_fails(self):
        fake = self.fake()
        fake.versions.append(version("v2", 2, "WAITING_FOR_REVIEW"))
        fake.localizations["v2"] = copy.deepcopy(fake.localizations["v1"])
        result, out = self.run_cmd(fake, apply=True)
        self.assertEqual((result, fake.writes()), (1, []), out)


if __name__ == "__main__":
    unittest.main()
