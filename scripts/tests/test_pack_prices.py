"""Tests for the pack price table (scripts/pack_prices.py) and the two price syncs that read it:
scripts/play_products.py --reprice and scripts/testflight.py iap-prices. Offline: only the pure
parts (diffing, request bodies, picking the price point), no Google library or credential.

    python3 -m unittest discover -s scripts/tests
"""
import copy
import pathlib
import sys
import unittest
from decimal import Decimal

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))
import pack_prices  # noqa: E402
import play_products  # noqa: E402
import testflight  # noqa: E402


def money(currency, units, nanos=0):
    price = {"currencyCode": currency, "units": str(units)}
    if nanos:
        price["nanos"] = nanos
    return price


def product(us=money("USD", 3, 990000000), extra_options=()):
    return {
        "packageName": "com.foodtracker.android", "productId": "recipes_10",
        "listings": [{"languageCode": "en-GB", "title": "10 recipes", "description": "Ten"}],
        "regionsVersion": {"version": "2026/01"},
        "purchaseOptions": [{
            "purchaseOptionId": "buy", "state": "ACTIVE", "buyOption": {"legacyCompatible": True},
            "regionalPricingAndAvailabilityConfigs": [
                {"regionCode": "US", "price": us, "availability": "AVAILABLE"},
                {"regionCode": "GB", "price": money("GBP", 3, 590000000), "availability": "AVAILABLE"},
                {"regionCode": "PY", "price": money("PYG", 20000), "availability": "AVAILABLE"},
                {"regionCode": "XK", "price": money("EUR", 4, 290000000), "availability": "NO_LONGER_AVAILABLE"},
            ],
            "newRegionsConfig": {"usdPrice": money("USD", 3, 990000000),
                                 "eurPrice": money("EUR", 3, 620000000), "availability": "AVAILABLE"},
        }, *extra_options],
    }


CONVERSION = {
    "regionVersion": {"version": "2026/02"},
    "convertedRegionPrices": {
        "US": {"regionCode": "US", "price": money("USD", 2, 990000000)},
        "GB": {"regionCode": "GB", "price": money("GBP", 2, 690000000)},
        "PY": {"regionCode": "PY", "price": money("PYG", 20000)},
        "XK": {"regionCode": "XK", "price": money("EUR", 3, 190000000)},
        "FR": {"regionCode": "FR", "price": money("EUR", 3, 290000000)},
    },
    "convertedOtherRegionsPrice": {"usdPrice": money("USD", 2, 990000000),
                                   "eurPrice": money("EUR", 2, 710000000)},
}


class TableTests(unittest.TestCase):
    def test_both_scripts_read_the_one_table(self):
        self.assertEqual({p: v[1] for p, v in play_products.PRODUCTS.items()}, pack_prices.USD_PRICES)
        self.assertEqual(pack_prices.usd("recipes_10"), Decimal("2.99"))

    def test_every_price_is_a_plain_usd_amount(self):
        for product_id, price in pack_prices.USD_PRICES.items():
            value = Decimal(price)
            self.assertEqual(value, value.quantize(Decimal("0.01")), product_id)
            self.assertGreater(value, 0, product_id)

    def test_money_and_amount_round_trip(self):
        for price in ("2.99", "10.99", "18.99", "20000", "0.5"):
            self.assertEqual(play_products.amount(play_products.money(price)), Decimal(price))
        self.assertEqual(play_products.money("2.99"),
                         {"currencyCode": "USD", "units": "2", "nanos": 990000000})


class PlayRepriceTests(unittest.TestCase):
    def test_us_price_is_the_buy_options_us_region(self):
        self.assertEqual(play_products.us_price(product()), Decimal("3.99"))
        self.assertIsNone(play_products.us_price({"purchaseOptions": []}))
        other = product()
        other["purchaseOptions"][0]["purchaseOptionId"] = "rent"
        self.assertIsNone(play_products.us_price(other))

    def test_body_replaces_the_converted_prices_only(self):
        before = product()
        body, changes, unconverted = play_products.reprice_body(before, CONVERSION)
        option = body["purchaseOptions"][0]
        prices = {c["regionCode"]: c for c in option["regionalPricingAndAvailabilityConfigs"]}
        self.assertEqual(prices["US"]["price"], money("USD", 2, 990000000))
        self.assertEqual(prices["GB"]["price"], money("GBP", 2, 690000000))
        # A region keeps its availability, and a region the product lacks (FR) is not added.
        self.assertEqual(prices["XK"]["availability"], "NO_LONGER_AVAILABLE")
        self.assertNotIn("FR", prices)
        self.assertEqual(option["newRegionsConfig"], {"usdPrice": money("USD", 2, 990000000),
                                                      "eurPrice": money("EUR", 2, 710000000),
                                                      "availability": "AVAILABLE"})
        self.assertEqual(option["buyOption"], {"legacyCompatible": True})
        self.assertEqual([c[0] for c in changes], ["US", "GB", "XK", "new regions USD", "new regions EUR"])
        self.assertEqual(unconverted, [])
        self.assertEqual(before, product(), "the product read from Play is not modified")

    def test_body_holds_only_the_masked_fields_and_no_output_only_state(self):
        body, _, _ = play_products.reprice_body(product(), CONVERSION)
        self.assertEqual(set(body), {"packageName", "productId", "purchaseOptions"})
        self.assertNotIn("state", body["purchaseOptions"][0])

    def test_other_purchase_options_go_back_unchanged(self):
        rent = {"purchaseOptionId": "rent", "state": "INACTIVE", "rentOption": {"rentalPeriod": "P1D"},
                "regionalPricingAndAvailabilityConfigs": [
                    {"regionCode": "US", "price": money("USD", 1), "availability": "AVAILABLE"}]}
        body, changes, _ = play_products.reprice_body(product(extra_options=[copy.deepcopy(rent)]), CONVERSION)
        expected = dict(rent)
        expected.pop("state")
        self.assertEqual(body["purchaseOptions"][1], expected)
        self.assertEqual(sum(1 for c in changes if c[0] == "US"), 1)

    def test_a_region_the_conversion_lacks_is_left_and_reported(self):
        conversion = copy.deepcopy(CONVERSION)
        del conversion["convertedRegionPrices"]["GB"]
        body, _, unconverted = play_products.reprice_body(product(), conversion)
        prices = {c["regionCode"]: c["price"]
                  for c in body["purchaseOptions"][0]["regionalPricingAndAvailabilityConfigs"]}
        self.assertEqual(prices["GB"], money("GBP", 3, 590000000))
        self.assertEqual(unconverted, ["GB"])

    def test_dry_run_lines(self):
        before = product()
        _, changes, unconverted = play_products.reprice_body(before, CONVERSION)
        lines = play_products.describe_reprice("recipes_10", Decimal("3.99"), Decimal("2.99"), changes,
                                               unconverted, play_products.buy_option(before))
        self.assertEqual(lines[0], "recipes_10: US USD 3.99 -> USD 2.99 (option 'buy' ACTIVE); "
                                   "3 of 4 regions change")
        self.assertIn("  US: USD 3.99 -> USD 2.99", lines)
        self.assertIn("  GB: GBP 3.59 -> GBP 2.69", lines)
        self.assertIn("  new regions EUR: EUR 3.62 -> EUR 2.71", lines)
        self.assertIn("  unchanged (1): PY PYG 20000", lines)


def manual(territory, price, start=None, end=None, point="p"):
    return {"territory": territory, "price": price, "start": start, "end": end, "point": point}


class AppStorePriceTests(unittest.TestCase):
    TODAY = "2026-11-15"

    def test_price_in_force(self):
        prices = [manual("USA", "3.99", None, "2026-12-01"), manual("USA", "4.99", "2026-12-01")]
        self.assertEqual(testflight.price_in_force(prices, self.TODAY)["price"], "3.99")
        self.assertEqual(testflight.price_in_force(prices, "2026-12-01")["price"], "4.99")
        self.assertIsNone(testflight.price_in_force([manual("USA", "1", "2027-01-01")], self.TODAY))
        later = [manual("USA", "3.99"), manual("USA", "2.99", "2026-11-01")]
        self.assertEqual(testflight.price_in_force(later, self.TODAY)["price"], "2.99")

    def test_picks_the_price_point_by_value(self):
        points = [{"id": "a", "attributes": {"customerPrice": "2.49"}},
                  {"id": "b", "attributes": {"customerPrice": "2.99"}},
                  {"id": "c", "attributes": {"customerPrice": "29.99"}},
                  {"id": "d", "attributes": {}}]
        self.assertEqual(testflight.pick_price_point(points, "2.99")["id"], "b")
        self.assertEqual(testflight.pick_price_point([{"id": "e", "attributes": {"customerPrice": "3.0"}}],
                                                     "3.00")["id"], "e")
        self.assertIsNone(testflight.pick_price_point(points, "2.98"))

    def test_schedule_body(self):
        body = testflight.price_schedule_body("6834563751", "pp-299")
        self.assertEqual(body["data"], {
            "type": "inAppPurchasePriceSchedules",
            "relationships": {
                "inAppPurchase": {"data": {"type": "inAppPurchases", "id": "6834563751"}},
                "baseTerritory": {"data": {"type": "territories", "id": "USA"}},
                "manualPrices": {"data": [{"type": "inAppPurchasePrices", "id": "${new-base-price}"}]},
            }})
        (price,) = body["included"]
        self.assertEqual(price["id"], body["data"]["relationships"]["manualPrices"]["data"][0]["id"])
        self.assertEqual(price["type"], "inAppPurchasePrices")
        self.assertEqual(price["attributes"], {"startDate": None})
        self.assertEqual(price["relationships"], {
            "inAppPurchaseV2": {"data": {"type": "inAppPurchases", "id": "6834563751"}},
            "inAppPurchasePricePoint": {"data": {"type": "inAppPurchasePricePoints", "id": "pp-299"}}})

    def test_plan_same_price(self):
        action, _, _, dropped = testflight.plan_price("USA", [manual("USA", "10.99")], "10.99", self.TODAY)
        self.assertEqual((action, dropped), ("same", []))

    def test_plan_change_lists_what_the_new_schedule_drops(self):
        prices = [manual("USA", "3.99", point="old"), manual("USA", "4.99", "2027-01-01"),
                  manual("GBR", "3.49")]
        action, detail, current, dropped = testflight.plan_price("USA", prices, "2.99", self.TODAY)
        self.assertEqual(action, "change")
        self.assertEqual(detail, "USD 3.99 -> USD 2.99")
        self.assertEqual(current["point"], "old")
        self.assertEqual([(p["territory"], p["price"]) for p in dropped], [("USA", "4.99"), ("GBR", "3.49")])

    def test_plan_another_base_territory_is_changed_to_usa(self):
        prices = [manual("USA", "2.99"), manual("GBR", "2.49")]
        action, detail, _, _ = testflight.plan_price("GBR", prices, "2.99", self.TODAY)
        self.assertEqual(action, "change")
        self.assertIn("base territory now GBR", detail)

    def test_plan_without_a_base_price(self):
        action, _, current, _ = testflight.plan_price(None, [], "2.99", self.TODAY)
        self.assertEqual((action, current), ("unknown", None))

    def test_dry_run_refuses_writes(self):
        class Live:
            def call(self, *args, **kwargs):
                raise AssertionError("not reached")
        with self.assertRaises(AssertionError) as raised:
            testflight.ReadOnlyAsc(Live()).call("POST", "/inAppPurchasePriceSchedules", body={})
        self.assertIn("read-only", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
