#!/usr/bin/env python3
"""Creates the recipe-pack one-time products on Google Play: recipes_10, recipes_50, recipes_100.

Dry run by default: it reads what Play already has, asks Play to convert each base price into
every region's price, and prints what it would create. Nothing is written without --apply:

    pip install google-api-python-client google-auth-httplib2
    PLAY_SERVICE_ACCOUNT_JSON="$(cat key.json)" python3 scripts/play_products.py           # dry run
    PLAY_SERVICE_ACCOUNT_JSON="$(cat key.json)" python3 scripts/play_products.py --apply   # create
    PLAY_SERVICE_ACCOUNT_JSON="$(cat key.json)" python3 scripts/play_products.py --apply recipes_10

Authenticates exactly like scripts/play_upload.py (its `publisher()`); the key never lives in the
repository. The service account needs Play Console permission to manage the app's in-app
products, and Play only creates in-app products once it has seen a build with Play Billing on a
testing track (docs/recipe-setup.md, step 4).

Idempotent: a product that already exists is not changed; if its purchase option `buy` is not
active, --apply only activates it. Each new product is a consumable with one purchase option `buy`
(legacy-compatible, so Play Billing's ProductDetails sees it; multi-quantity off), available in
every region at Play's conversion of the USD base price (`convertRegionPrices`), and in regions
Play adds later at the converted USD and EUR prices; listings in en-GB, ru-RU and no-NO. The pack
sizes must match PRODUCT_CREDITS in functions/src/constants.ts, and the USD prices the App Store
base prices in store-listing/app-store/README.md.
"""
import argparse
import json
import sys
from decimal import Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from play_upload import PACKAGE_NAME, publisher  # noqa: E402

from googleapiclient.errors import HttpError  # noqa: E402

PURCHASE_OPTION = "buy"

# productId: (recipes, USD base price, the count spelt out in en / ru / nb).
PRODUCTS = {
    "recipes_10": (10, "3.99", "Ten", "Десять", "Ti"),
    "recipes_50": (50, "10.99", "Fifty", "Пятьдесят", "Femti"),
    "recipes_100": (100, "18.99", "A hundred", "Сто", "Hundre"),
}


def listings(product_id):
    n, _, en, ru, nb = PRODUCTS[product_id]
    return [
        {"languageCode": "en-GB", "title": f"{n} recipes",
         "description": f"{en} dishes written for you from what is about to expire. Unused recipes don't expire."},
        {"languageCode": "ru-RU", "title": f"{n} рецептов",
         "description": f"{ru} блюд, составленных для вас из продуктов, у которых скоро выходит срок. "
                        "Неиспользованные рецепты не сгорают."},
        {"languageCode": "no-NO", "title": f"{n} oppskrifter",
         "description": f"{nb} retter laget for deg av det som snart går ut på dato. Ubrukte oppskrifter utløper ikke."},
    ]


def money(usd):
    """A Play Money from a decimal string, e.g. "3.99" -> 3 units, 990000000 nanos."""
    units, frac = divmod(Decimal(usd), 1)
    return {"currencyCode": "USD", "units": str(int(units)), "nanos": int(frac * 1_000_000_000)}


def existing(api, product_id):
    """The product as Play has it, or None when it does not exist."""
    try:
        return api.monetization().onetimeproducts().get(packageName=PACKAGE_NAME, productId=product_id).execute()
    except HttpError as e:
        if e.resp.status == 404:
            return None
        raise


def create_body(api, product_id):
    """The product to create and the regions version its prices were converted under."""
    conv = api.monetization().convertRegionPrices(
        packageName=PACKAGE_NAME, body={"price": money(PRODUCTS[product_id][1])}).execute()
    regional = [{"regionCode": code, "price": v["price"], "availability": "AVAILABLE"}
                for code, v in sorted(conv["convertedRegionPrices"].items())]
    other = conv["convertedOtherRegionsPrice"]
    body = {
        "packageName": PACKAGE_NAME,
        "productId": product_id,
        "listings": listings(product_id),
        "purchaseOptions": [{
            "purchaseOptionId": PURCHASE_OPTION,
            "buyOption": {"legacyCompatible": True, "multiQuantityEnabled": False},
            "regionalPricingAndAvailabilityConfigs": regional,
            "newRegionsConfig": {"usdPrice": other["usdPrice"], "eurPrice": other["eurPrice"],
                                 "availability": "AVAILABLE"},
        }],
    }
    return body, conv["regionVersion"]["version"]


def activate(api, product_id):
    api.monetization().onetimeproducts().purchaseOptions().batchUpdateStates(
        packageName=PACKAGE_NAME, productId=product_id,
        body={"requests": [{"activatePurchaseOptionRequest": {
            "packageName": PACKAGE_NAME, "productId": product_id, "purchaseOptionId": PURCHASE_OPTION}}]},
    ).execute()


def option_state(product):
    for option in product.get("purchaseOptions", []):
        if option.get("purchaseOptionId") == PURCHASE_OPTION:
            return option.get("state")
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("products", nargs="*", metavar="PRODUCT_ID",
                        help=f"only these ({', '.join(PRODUCTS)}); default all")
    parser.add_argument("--apply", action="store_true", help="create and activate; without it nothing is written")
    args = parser.parse_args()
    unknown = [p for p in args.products if p not in PRODUCTS]
    if unknown:
        parser.error(f"unknown product id: {', '.join(unknown)}")
    api = publisher(timeout=60, static_discovery=False)
    if api is None:
        print("PLAY_SERVICE_ACCOUNT_JSON is not set", file=sys.stderr)
        return 2
    mode = "APPLY" if args.apply else "DRY RUN (pass --apply to write)"
    print(f"{mode}: {PACKAGE_NAME}")
    failed = False
    for product_id in args.products or list(PRODUCTS):
        try:
            product = existing(api, product_id)
            if product is not None:
                state = option_state(product)
                if state == "ACTIVE":
                    print(f"{product_id}: exists, option '{PURCHASE_OPTION}' active; nothing to do")
                elif args.apply:
                    activate(api, product_id)
                    print(f"{product_id}: exists; activated option '{PURCHASE_OPTION}' (was {state})")
                else:
                    print(f"{product_id}: exists; would activate option '{PURCHASE_OPTION}' (now {state})")
                continue
            body, regions_version = create_body(api, product_id)
            if not args.apply:
                option = body["purchaseOptions"][0]
                summary = {
                    "listings": body["listings"],
                    "buyOption": option["buyOption"],
                    "regions": len(option["regionalPricingAndAvailabilityConfigs"]),
                    "regionsVersion": regions_version,
                    "newRegionsConfig": option["newRegionsConfig"],
                }
                print(f"{product_id}: would create")
                print(json.dumps(summary, indent=1, ensure_ascii=False))
                continue
            api.monetization().onetimeproducts().patch(
                packageName=PACKAGE_NAME, productId=product_id, allowMissing=True,
                updateMask="listings,purchaseOptions", regionsVersion_version=regions_version, body=body,
            ).execute()
            activate(api, product_id)
            print(f"{product_id}: created, option '{PURCHASE_OPTION}' {option_state(existing(api, product_id))}")
        except HttpError as e:
            failed = True
            print(f"{product_id}: HTTP {e.resp.status} {e.content.decode(errors='replace')[:2000]}", file=sys.stderr)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
