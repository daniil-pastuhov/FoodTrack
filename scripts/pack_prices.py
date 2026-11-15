"""The recipe packs' base prices, in US dollars: the one table both stores are set from.

scripts/play_products.py creates the Play products at these prices and, with --reprice, brings
existing ones in line (Play converts the USD price to every other region). scripts/testflight.py
`iap-prices` does the same on the App Store, where the United States is the base territory and
Apple's equalisation sets every other storefront. Change a price here, then run both dry runs
("Changing a pack's price" in README.md). The pack sizes are PRODUCT_CREDITS in
functions/src/constants.ts; what the app shows is always the store's own price.
"""
from decimal import Decimal

USD_PRICES = {
    "recipes_10": "2.99",
    "recipes_50": "10.99",
    "recipes_100": "18.99",
}


def usd(product_id):
    """The table's price of a product as a Decimal."""
    return Decimal(USD_PRICES[product_id])
