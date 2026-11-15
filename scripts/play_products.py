#!/usr/bin/env python3
"""Creates the recipe-pack one-time products on Google Play (recipes_10, recipes_50, recipes_100)
and, with --reprice, brings their prices in line with scripts/pack_prices.py.

Dry run by default: it reads what Play already has, asks Play to convert each base price into
every region's price, and prints what it would create or change. Nothing is written without
--apply:

    pip install google-api-python-client google-auth-httplib2
    PLAY_SERVICE_ACCOUNT_JSON="$(cat key.json)" python3 scripts/play_products.py           # dry run
    PLAY_SERVICE_ACCOUNT_JSON="$(cat key.json)" python3 scripts/play_products.py --apply   # create
    PLAY_SERVICE_ACCOUNT_JSON="$(cat key.json)" python3 scripts/play_products.py --apply recipes_10
    PLAY_SERVICE_ACCOUNT_JSON="$(cat key.json)" python3 scripts/play_products.py --reprice  # dry run
    PLAY_SERVICE_ACCOUNT_JSON="$(cat key.json)" python3 scripts/play_products.py --reprice --apply

Authenticates exactly like scripts/play_upload.py (its `publisher()`); the key never lives in the
repository. The service account needs Play Console permission to manage the app's in-app
products, and Play only creates in-app products once it has seen a build with Play Billing on a
testing track (docs/recipe-setup.md, step 4).

Without --reprice it is idempotent: a product that already exists is not changed; if its purchase
option `buy` is not active, --apply only activates it. Each new product is a consumable with one
purchase option `buy` (legacy-compatible, so Play Billing's ProductDetails sees it; multi-quantity
off), available in every region at Play's conversion of the USD base price (`convertRegionPrices`),
and in regions Play adds later at the converted USD and EUR prices; listings in every language of
LISTINGS (the store listing's languages).

--reprice changes only prices: for each existing product whose US price differs from the table
(USD 2.99 / 10.99 / 18.99 since 2026-11-15), it asks Play to convert the table's USD price to
every region, as creation does, and patches the purchase options (`updateMask=purchaseOptions`,
the regions version of that conversion) with the `buy` option's regional prices and new-regions
prices replaced. Listings, the option's state and availability, and every other field are sent
back as Play has them. The dry run prints the US price, a few regions and how many change.

The prices are scripts/pack_prices.py's (shared with scripts/testflight.py `iap-prices`, the App
Store's base prices); the pack sizes must match PRODUCT_CREDITS in functions/src/constants.ts.
"""
import argparse
import copy
import json
import sys
from decimal import Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from pack_prices import USD_PRICES  # noqa: E402
from play_upload import PACKAGE_NAME, publisher  # noqa: E402

PURCHASE_OPTION = "buy"

# productId: (recipes, USD base price, the count spelt out per language as the description starts).
# The prices are scripts/pack_prices.py's, never written here.
PRODUCTS = {
    "recipes_10": (10, USD_PRICES["recipes_10"], {"en": "Ten", "ru": "Десять", "nb": "Ti", "es": "Diez", "de": "Zehn",
                                "fr": "Dix", "sv": "Tio", "da": "Ti"}),
    "recipes_50": (50, USD_PRICES["recipes_50"], {"en": "Fifty", "ru": "Пятьдесят", "nb": "Femti", "es": "Cincuenta",
                                 "de": "Fünfzig", "fr": "Cinquante", "sv": "Femtio", "da": "Halvtreds"}),
    "recipes_100": (100, USD_PRICES["recipes_100"], {"en": "A hundred", "ru": "Сто", "nb": "Hundre", "es": "Cien",
                                   "de": "Hundert", "fr": "Cent", "sv": "Hundra", "da": "Hundrede"}),
}

# Play language code -> (language of the count above, title with {n}, description with {count}).
LISTINGS = {
    "en-GB": ("en", "{n} recipes",
              "{count} dishes written for you from what is about to expire. Unused recipes don't expire."),
    "ru-RU": ("ru", "{n} рецептов",
              "{count} блюд, составленных для вас из продуктов, у которых скоро выходит срок. "
              "Неиспользованные рецепты не сгорают."),
    "no-NO": ("nb", "{n} oppskrifter",
              "{count} retter laget for deg av det som snart går ut på dato. Ubrukte oppskrifter utløper ikke."),
    "es-ES": ("es", "{n} recetas",
              "{count} platos pensados para ti con lo que está a punto de caducar. Las recetas sin usar no caducan."),
    "es-419": ("es", "{n} recetas",
               "{count} platillos pensados para ti con lo que está por caducar. Las recetas sin usar no vencen."),
    "de-DE": ("de", "{n} Rezepte",
              "{count} Gerichte, für dich zusammengestellt aus dem, was bald abläuft. "
              "Unbenutzte Rezepte verfallen nicht."),
    "fr-FR": ("fr", "{n} recettes",
              "{count} plats imaginés pour vous avec ce qui arrive bientôt à date. "
              "Les recettes non utilisées n'expirent pas."),
    "fr-CA": ("fr", "{n} recettes",
              "{count} plats pensés pour vous avec ce qui arrive bientôt à échéance. "
              "Les recettes inutilisées n'expirent pas."),
    "sv-SE": ("sv", "{n} recept",
              "{count} rätter skapade åt dig av det som snart passerar bäst före-datum. "
              "Oanvända recept går inte ut."),
    "da-DK": ("da", "{n} opskrifter",
              "{count} retter lavet til dig af det, der snart når sin dato. Ubrugte opskrifter udløber ikke."),
}


def listings(product_id):
    n, _, counts = PRODUCTS[product_id]
    return [{"languageCode": code, "title": title.format(n=n),
             "description": description.format(count=counts[language])}
            for code, (language, title, description) in LISTINGS.items()]


def money(usd):
    """A Play Money from a decimal string, e.g. "3.99" -> 3 units, 990000000 nanos."""
    units, frac = divmod(Decimal(usd), 1)
    return {"currencyCode": "USD", "units": str(int(units)), "nanos": int(frac * 1_000_000_000)}


def existing(api, product_id):
    """The product as Play has it, or None when it does not exist."""
    from googleapiclient.errors import HttpError
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


# --- --reprice: bring existing products' prices in line with the table ---------------------------
#
# monetization.onetimeproducts.patch (developers.google.com/android-publisher/api-ref/rest/v3/
# monetization.onetimeproducts/patch): `updateMask` names the fields to change and
# `regionsVersion.version` is required, the version of the regions the prices were converted
# under. purchaseOptions is a list, so the mask replaces it whole: every option is sent back as Play
# has it, with only the `buy` option's prices changed. A purchase option's `state` is output only
# ("cannot be changed by updating the resource") and is left out.

# Regions the dry run shows besides the US (the app's store languages' main markets and two far
# currencies).
SAMPLE_REGIONS = ("GB", "DE", "FR", "ES", "NO", "SE", "DK", "JP", "IN", "BR")


def amount(price):
    """A Play Money as a Decimal (units plus nanos)."""
    return Decimal(price.get("units") or "0") + Decimal(price.get("nanos") or 0) / 1_000_000_000


def shown(price):
    return f"{price.get('currencyCode', '?')} {amount(price).normalize():f}" if price else "none"


def buy_option(product):
    return next((o for o in product.get("purchaseOptions") or []
                 if o.get("purchaseOptionId") == PURCHASE_OPTION), None)


def us_price(product):
    """The `buy` option's US price as a Decimal, or None when it has none."""
    option = buy_option(product) or {}
    for config in option.get("regionalPricingAndAvailabilityConfigs") or []:
        if config.get("regionCode") == "US" and config.get("price"):
            return amount(config["price"])
    return None


def reprice_body(product, conversion):
    """The patch body that gives the `buy` option the converted prices, and what changes.

    Returns (body, changes, unconverted): changes is [(regionCode, old price, new price)] for every
    region whose price differs, ("new regions USD"/"EUR", old, new) for the new-regions prices;
    unconverted lists the product's regions the conversion has no price for (left as they are).
    Each region keeps its availability; regions the product doesn't have are not added."""
    converted = conversion["convertedRegionPrices"]
    options = copy.deepcopy(product.get("purchaseOptions") or [])
    changes, unconverted = [], []
    for option in options:
        option.pop("state", None)
        if option.get("purchaseOptionId") != PURCHASE_OPTION:
            continue
        for config in option.get("regionalPricingAndAvailabilityConfigs") or []:
            code = config.get("regionCode")
            if code not in converted:
                unconverted.append(code)
                continue
            new = converted[code]["price"]
            if config.get("price") != new:
                changes.append((code, config.get("price"), new))
                config["price"] = new
        other = conversion["convertedOtherRegionsPrice"]
        regions = option.setdefault("newRegionsConfig", {"availability": "AVAILABLE"})
        for field, label in (("usdPrice", "new regions USD"), ("eurPrice", "new regions EUR")):
            if regions.get(field) != other[field]:
                changes.append((label, regions.get(field), other[field]))
                regions[field] = other[field]
    body = {"packageName": product.get("packageName", PACKAGE_NAME),
            "productId": product["productId"], "purchaseOptions": options}
    return body, changes, unconverted


NEW_REGIONS = ("new regions USD", "new regions EUR")


def describe_reprice(product_id, current, target, changes, unconverted, option):
    """The dry run's lines for one product: the US and SAMPLE_REGIONS, old -> new, how many
    regions change, and the few that keep their price (a price at a currency's floor)."""
    configs = option.get("regionalPricingAndAvailabilityConfigs") or []
    changed = {code for code, _, _ in changes if code not in NEW_REGIONS}
    lines = [f"{product_id}: US USD {current.normalize():f} -> USD {target.normalize():f} "
             f"(option '{PURCHASE_OPTION}' {option.get('state')}); {len(changed)} "
             f"of {len(configs)} regions change"]
    by_region = {code: (old, new) for code, old, new in changes}
    for code in ("US",) + SAMPLE_REGIONS:
        if code in by_region:
            old, new = by_region[code]
            lines.append(f"  {code}: {shown(old)} -> {shown(new)}")
    for label in NEW_REGIONS:
        if label in by_region:
            old, new = by_region[label]
            lines.append(f"  {label}: {shown(old)} -> {shown(new)}")
    same = [f"{c.get('regionCode')} {shown(c.get('price'))}" for c in configs
            if c.get("regionCode") not in changed and c.get("regionCode") not in unconverted]
    if same:
        lines.append(f"  unchanged ({len(same)}): {', '.join(same[:10])}{', ...' if len(same) > 10 else ''}")
    if unconverted:
        lines.append(f"  not converted by Play, left as they are: {', '.join(unconverted)}")
    return lines


def reprice(args):
    from googleapiclient.errors import HttpError
    api = publisher(timeout=60, static_discovery=False)
    if api is None:
        print("PLAY_SERVICE_ACCOUNT_JSON is not set", file=sys.stderr)
        return 2
    mode = "APPLY" if args.apply else "DRY RUN (pass --apply to write)"
    print(f"{mode}: prices of {PACKAGE_NAME} against scripts/pack_prices.py")
    failed = False
    for product_id in args.products or list(PRODUCTS):
        try:
            product = existing(api, product_id)
            if product is None:
                print(f"{product_id}: does not exist; run without --reprice to create it")
                continue
            target = Decimal(PRODUCTS[product_id][1])
            current = us_price(product)
            if current == target:
                print(f"{product_id}: US USD {current.normalize():f}, as the table; nothing to do")
                continue
            if current is None:
                print(f"{product_id}: no US price in option '{PURCHASE_OPTION}'; not changed", file=sys.stderr)
                failed = True
                continue
            conversion = api.monetization().convertRegionPrices(
                packageName=PACKAGE_NAME, body={"price": money(PRODUCTS[product_id][1])}).execute()
            version = conversion["regionVersion"]["version"]
            body, changes, unconverted = reprice_body(product, conversion)
            for line in describe_reprice(product_id, current, target, changes, unconverted,
                                         buy_option(product)):
                print(line)
            print(f"  regions version {version} (the product's: "
                  f"{(product.get('regionsVersion') or {}).get('version')})")
            if not args.apply:
                continue
            api.monetization().onetimeproducts().patch(
                packageName=PACKAGE_NAME, productId=product_id, updateMask="purchaseOptions",
                regionsVersion_version=version, body=body,
            ).execute()
            after = us_price(existing(api, product_id))
            print(f"  changed; Play now has US USD {after.normalize():f}" if after is not None
                  else "  changed; Play's answer has no US price")
            failed |= after != target
        except HttpError as e:
            failed = True
            print(f"{product_id}: HTTP {e.resp.status} {e.content.decode(errors='replace')[:2000]}", file=sys.stderr)
    return 1 if failed else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("products", nargs="*", metavar="PRODUCT_ID",
                        help=f"only these ({', '.join(PRODUCTS)}); default all")
    parser.add_argument("--apply", action="store_true",
                        help="create and activate (with --reprice: change the prices); without it nothing is written")
    parser.add_argument("--reprice", action="store_true",
                        help="bring existing products' prices in line with scripts/pack_prices.py instead of creating")
    args = parser.parse_args()
    unknown = [p for p in args.products if p not in PRODUCTS]
    if unknown:
        parser.error(f"unknown product id: {', '.join(unknown)}")
    if args.reprice:
        return reprice(args)
    from googleapiclient.errors import HttpError
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
