#!/usr/bin/env python3
"""Writes the demo pantry that the store screenshots show, as a backup file both apps restore:

    scripts/demo_backup.py <en|ru|nb|es|de|fr|sv|da> <out.json> [scan picture url]

Used by scripts/ios_screenshots.sh and scripts/android_screenshots.sh. Dates are relative to
today, so the "expires today / tomorrow" labels always read right. Item ids are fixed
("demo-0" to "demo-12", in the order below), so a script can open a given item; demo-4 is the
salmon. The product cache holds the yoghurt the scan demo reads (scripts/demo_scan_image.py), so
its lookup needs no network; [scan picture url] is its picture, where the app can read it.
"""
import datetime, json, os, sys
from demo_scan_image import BARCODE, NAMES as SCAN_NAMES

lang, out = sys.argv[1], sys.argv[2]
scan_picture = sys.argv[3] if len(sys.argv) > 3 else None
today = datetime.date.today()
day = lambda offset: (today + datetime.timedelta(days=offset)).isoformat()
# Stored keys stay English (the app translates built-in locations and categories itself);
# only the product names are per language.
names = {
    "en": ["Sourdough bread", "Baby spinach", "Greek yoghurt", "Semi-skimmed milk", "Salmon fillet",
           "Strawberries", "Orange juice", "Free-range eggs", "Cheddar", "Chicken breast",
           "Frozen peas", "Dark chocolate", "Spaghetti",
           "Butter", "Bananas", "Tomatoes", "Coffee beans", "Oat milk"],
    "ru": ["Бородинский хлеб", "Шпинат", "Кефир", "Молоко 2,5%", "Филе лосося",
           "Клубника", "Апельсиновый сок", "Яйца", "Сыр", "Куриное филе",
           "Зелёный горошек", "Тёмный шоколад", "Гречка",
           "Сливочное масло", "Бананы", "Помидоры", "Кофе в зёрнах", "Творог"],
    "nb": ["Grovbrød", "Babyspinat", "Gresk yoghurt", "Lettmelk", "Laksefilet",
           "Jordbær", "Appelsinjuice", "Egg", "Gulost", "Kyllingfilet",
           "Frosne erter", "Mørk sjokolade", "Spagetti",
           "Smør", "Bananer", "Tomater", "Kaffebønner", "Brunost"],
    "es": ["Pan de masa madre", "Espinacas baby", "Yogur griego", "Leche semidesnatada", "Lomo de salmón",
           "Fresas", "Zumo de naranja", "Huevos camperos", "Queso manchego", "Pechuga de pollo",
           "Guisantes congelados", "Chocolate negro", "Espaguetis",
           "Mantequilla", "Plátanos", "Tomates", "Café en grano", "Queso fresco"],
    "de": ["Sauerteigbrot", "Babyspinat", "Griechischer Joghurt", "Fettarme Milch", "Lachsfilet",
           "Erdbeeren", "Orangensaft", "Freilandeier", "Bergkäse", "Hähnchenbrustfilet",
           "TK-Erbsen", "Zartbitterschokolade", "Spaghetti",
           "Butter", "Bananen", "Tomaten", "Kaffeebohnen", "Quark"],
    "fr": ["Pain au levain", "Pousses d'épinard", "Yaourt à la grecque", "Lait demi-écrémé", "Pavé de saumon",
           "Fraises", "Jus d'orange", "Œufs plein air", "Comté", "Blanc de poulet",
           "Petits pois surgelés", "Chocolat noir", "Spaghetti",
           "Beurre", "Bananes", "Tomates", "Café en grains", "Crème fraîche"],
    "sv": ["Surdegsbröd", "Babyspenat", "Grekisk yoghurt", "Mellanmjölk", "Laxfilé",
           "Jordgubbar", "Apelsinjuice", "Ägg", "Prästost", "Kycklingfilé",
           "Frysta ärter", "Mörk choklad", "Spagetti",
           "Smör", "Bananer", "Tomater", "Kaffebönor", "Havredryck"],
    "da": ["Rugbrød", "Babyspinat", "Græsk yoghurt", "Letmælk", "Laksefilet",
           "Jordbær", "Appelsinjuice", "Frilandsæg", "Danbo", "Kyllingebryst",
           "Frosne ærter", "Mørk chokolade", "Spaghetti",
           "Smør", "Bananer", "Tomater", "Kaffebønner", "Skyr"],
}[lang]
pantry = [  # (storage location, category, days until expiry, quantity)
    ("Pantry", "Bakery", -1, 1), ("Fridge", "Vegetables", 0, 1), ("Fridge", "Dairy", 1, 2),
    ("Fridge", "Dairy", 2, 1), ("Fridge", "Meat & fish", 3, 2), ("Fridge", "Fruit", 4, 1),
    ("Fridge", "Drinks", 6, 1), ("Fridge", "Dairy", 9, 1), ("Fridge", "Dairy", 12, 1),
    ("Freezer", "Meat & fish", 45, 2), ("Freezer", "Vegetables", 120, 1),
    ("Pantry", "Sweets", 90, 1), ("Pantry", "Pantry", 300, 2),
]
shopping = [("Dairy", 1), ("Fruit", 6), ("Vegetables", 4), ("Drinks", 1), ("Dairy", 2)]
items = [{
    "id": f"demo-{i}", "name": names[i], "brand": None, "barcode": None, "imageUrl": None,
    "expiryDate": day(offset), "addedDate": day(-(i % 5) - 1), "storageLocation": location,
    "category": category, "quantity": quantity, "notes": None, "essential": True,
} for i, (location, category, offset, quantity) in enumerate(pantry)]
entries = [{
    "id": f"demo-entry-{i}", "productName": names[len(pantry) + i], "barcode": None,
    "category": category, "quantity": quantity, "addedDate": day(0), "sortOrder": float(i),
} for i, (category, quantity) in enumerate(shopping)]
# DEMO_ITEMS=1,2,3 keeps only those items (by index, ids unchanged): the Menu set screenshot on an
# iPhone, where a short "Cook with" leaves Days and Courses in view with the form at its top.
if os.environ.get("DEMO_ITEMS"):
    keep = {int(i) for i in os.environ["DEMO_ITEMS"].split(",")}
    items = [item for i, item in enumerate(items) if i in keep]
document = {
    "version": 1, "exportedAtEpochMillis": 0, "items": items, "shoppingList": entries,
    "categoryPreferences": [],
    "products": [{"barcode": BARCODE, "name": SCAN_NAMES[lang], "brand": None, "imageUrl": scan_picture,
                  "category": "Dairy", "shelfLifeDays": None, "storageLocation": "Fridge",
                  "nutrition": {"nutriScore": None, "novaGroup": None, "allergens": [], "ingredients": None}}],
    "knownProductNames": [],
    "settings": {
        "notifications": {"enabled": True, "leadDays": 1, "hour": 9, "minute": 0},
        "expiringSoonDays": 3, "storageLocations": ["Fridge", "Freezer", "Pantry"],
        "defaultStorageLocation": "Pantry", "rememberLastStorageLocation": True,
        "lastUsedStorageLocation": None, "theme": "SYSTEM", "sortOption": "EXPIRY_DATE",
        "allergens": [], "stores": [], "storeRemindersEnabled": False,
    },
}
json.dump(document, open(out, "w"), ensure_ascii=False)
