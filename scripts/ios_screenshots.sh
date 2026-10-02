#!/bin/zsh
# Takes the raw App Store screenshots: builds the debug app, loads a demo pantry in each of the
# app's languages and captures every tab on a 6.9" iPhone simulator (1320x2868, the size App
# Store Connect requires).
#
#   scripts/ios_screenshots.sh [output-dir]      # default: build/ios-screenshots
#
# The demo data is loaded through the debug-only -demoBackup / -demoTab launch arguments (see
# RootTabView.applyScreenshotLaunchArguments), so release builds are unaffected. Dates in the demo
# pantry are relative to today, so the "expires today / tomorrow" badges always read right.
set -euo pipefail

ROOT=${0:A:h:h}
OUT=${1:-$ROOT/build/ios-screenshots}
DEVICE_NAME="iPhone 17 Pro Max"
BUNDLE_ID=com.foodbest.ios
DERIVED=$ROOT/build/ios-screenshots-derived-data

UDID=$(xcrun simctl list devices available -j | python3 -c '
import json, sys
name = sys.argv[1]
for runtime, devices in json.load(sys.stdin)["devices"].items():
    if "iOS" in runtime:
        for d in devices:
            if d["name"] == name:
                print(d["udid"]); sys.exit()
sys.exit("no available simulator named " + name)' "$DEVICE_NAME")

xcrun simctl boot "$UDID" 2>/dev/null || true
xcrun simctl bootstatus "$UDID" -b >/dev/null
xcrun simctl ui "$UDID" appearance light
xcrun simctl status_bar "$UDID" override --time 9:41 --dataNetwork wifi --wifiMode active \
  --wifiBars 3 --cellularMode active --cellularBars 4 --batteryState charged --batteryLevel 100

xcodebuild -quiet -project "$ROOT/iosApp/FoodTracker.xcodeproj" -scheme FoodTracker \
  -configuration Debug -destination "id=$UDID" -derivedDataPath "$DERIVED" build
xcrun simctl install "$UDID" "$DERIVED/Build/Products/Debug-iphonesimulator/FoodTracker.app"
DATA=$(xcrun simctl get_app_container "$UDID" "$BUNDLE_ID" data)
mkdir -p "$DATA/tmp"

for LANG_CODE in en ru nb; do
  mkdir -p "$OUT/$LANG_CODE"
  python3 - "$LANG_CODE" "$DATA/tmp/demo.json" <<'PY'
import datetime, json, sys, uuid
lang, out = sys.argv[1], sys.argv[2]
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
    "id": str(uuid.uuid4()), "name": names[i], "brand": None, "barcode": None, "imageUrl": None,
    "expiryDate": day(offset), "addedDate": day(-(i % 5) - 1), "storageLocation": location,
    "category": category, "quantity": quantity, "notes": None, "essential": True,
} for i, (location, category, offset, quantity) in enumerate(pantry)]
entries = [{
    "id": str(uuid.uuid4()), "productName": names[len(pantry) + i], "barcode": None,
    "category": category, "quantity": quantity, "addedDate": day(0), "sortOrder": float(i),
} for i, (category, quantity) in enumerate(shopping)]
document = {
    "version": 1, "exportedAtEpochMillis": 0, "items": items, "shoppingList": entries,
    "categoryPreferences": [], "products": [], "knownProductNames": [],
    "settings": {
        "notifications": {"enabled": True, "leadDays": 1, "hour": 9, "minute": 0},
        "expiringSoonDays": 3, "storageLocations": ["Fridge", "Freezer", "Pantry"],
        "defaultStorageLocation": "Pantry", "rememberLastStorageLocation": True,
        "lastUsedStorageLocation": None, "theme": "SYSTEM", "sortOption": "EXPIRY_DATE",
        "allergens": [], "stores": [], "storeRemindersEnabled": False,
    },
}
json.dump(document, open(out, "w"), ensure_ascii=False)
PY
  N=1
  for TAB in home calendar shopping settings; do
    xcrun simctl launch --terminate-running-process "$UDID" "$BUNDLE_ID" \
      -demoBackup "$DATA/tmp/demo.json" -demoTab "$TAB" \
      -AppleLanguages "($LANG_CODE)" -AppleLocale "$LANG_CODE" >/dev/null
    sleep 4
    xcrun simctl io "$UDID" screenshot "$OUT/$LANG_CODE/$N-$TAB.png" >/dev/null 2>&1
    N=$((N + 1))
  done
  echo "$LANG_CODE: $(ls "$OUT/$LANG_CODE" | wc -l | tr -d ' ') screenshots"
done

xcrun simctl status_bar "$UDID" clear
echo "Saved to $OUT"
