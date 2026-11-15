# Open Food Facts reuse showcase: submission for BestBefore

Prepared, not posted. The owner posts it under their own GitHub account.

**Where to post:** open a new issue with the "Propose a new reuse or application" template:
<https://github.com/openfoodfacts/openfoodfacts-web/issues/new?template=new-reuse.yml>.
The showcase is <https://world.openfoodfacts.org/reuses>; its "Submit a Reuse" button leads to the
same template (there is also a Google Form linked from that page, which asks for the same things).
Filling in the issue form starts Open Food Facts' workflow, which opens a pull request adding the
entry to the showcase.

The fields below follow the template as it was on 2026-11-15
(`.github/ISSUE_TEMPLATE/new-reuse.yml` in `openfoodfacts/openfoodfacts-web`). The issue title is
pre-filled as `[Reuse]: `; add the name after it.

---

**Issue title:** `[Reuse]: BestBefore`

**Application / Reuse Name**

BestBefore

**Tagline / One-line Summary**

Open-source expiry date tracker for the fridge, freezer and pantry: scan a barcode, read the date off the pack, get a reminder before food goes off.

**Detailed Description**

BestBefore helps people use food before it expires. You scan a product's barcode, and the app fills in the product's name, brand, picture, category and, where Open Food Facts has them, its ingredients, allergens, Nutri-Score and nutrition facts. The camera then reads the printed best-before or use-by date (on-device text recognition), and the item goes into the fridge, freezer or pantry list, sorted by what to use first. Reminders arrive a few days before something expires. Items you use up can go onto a shopping list, and a Recipe tab suggests a recipe from what expires soonest.

How it uses Open Food Facts: read-only product lookups with the v2 product API (`GET https://world.openfoodfacts.org/api/v2/product/{barcode}.json`), one request per scanned barcode, sent from the user's phone with a custom User-Agent. Only the barcode is sent: no account, location or other user data. Each result is cached on the phone, so a product scanned before resolves without a new request. The product picture is loaded from the `image_front_url` Open Food Facts returns. The product sheet credits Open Food Facts and the licences (Open Database License, CC BY-SA). The app doesn't write anything back to Open Food Facts.

Native apps for iPhone/iPad (SwiftUI) and Android (Jetpack Compose with Kotlin Multiplatform), in English, Russian, Norwegian, Spanish, German, French, Swedish and Danish. The pantry is stored only on the device and no account is needed. Free; recipe packs are an optional in-app purchase, and Open Food Facts data is not part of what is sold.

**Primary Theme**

`tools (Tools, Fridge & Pantry Management)`

**Secondary Themes (optional)**

environment, allergies

**Open Food Facts Project**

`openfoodfacts (Open Food Facts - Food & Beverages)`

**Website URL**

https://github.com/daniil-pastuhov/FoodTrack

**Google Play Store URL (optional)**

_(leave empty: the Android app is in closed testing on Google Play and not public yet; the source and build instructions are at https://github.com/daniil-pastuhov/FoodTrack)_

**Apple App Store URL (optional)**

https://apps.apple.com/app/id6818647200

**F-Droid URL (optional)**

_(leave empty)_

**Source Code / GitHub URL (optional)**

https://github.com/daniil-pastuhov/FoodTrack

**Country Code (ISO 3-letter)**

global

**Country / Territory Label**

Worldwide

**Is this project Open Source?**

`Yes (Open Source)`

**License Name**

Apache-2.0

**Does it contribute data back to Open Food Facts?**

`No`

**Does it contribute product photos back?**

`No`

**Is it ODbL compliant?**

`Yes (Compliant with ODbL)`

**App Icon or Logo URL (optional)**

https://raw.githubusercontent.com/daniil-pastuhov/FoodTrack/main/iosApp/FoodTracker/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png

**Keywords / Search Tags (optional)**

expiry date, best before, pantry, fridge, food waste, barcode scanner, shopping list, reminders, recipes, open source

**Contribution Checklist**

- [x] I confirm this application or project is active and accurately described.
- [x] I confirm this project respects Open Food Facts attribution requirements or uses the Open Food Facts API / data.
- [x] I agree to have this metadata published in the public Open Food Facts reuses catalog under CC-BY-SA / ODbL.

---

## Notes for the owner (not part of the issue)

**Screenshots.** The template has no screenshot field. If the maintainers ask for some, or for the
Google Form, use (raw URLs work once on `main`):

- `store-listing/app-store/en-GB/screenshots/2.png` (scanning a barcode, the date read from the pack)
- `store-listing/app-store/en-GB/screenshots/1.png` (the pantry list, what to use first)

Smaller copies are in `docs/images/` (`screenshot-scan.jpg`, `screenshot-pantry.jpg`). The icon is
`iosApp/FoodTracker/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png` (1024×1024 PNG). The
name and icon are not covered by the code's Apache licence (README, "License"), so they are offered
for the showcase entry only.

**Maintainer note** (optional, paste as a comment under the issue):

> Hi, I maintain BestBefore. It only reads from Open Food Facts (one product lookup per scanned
> barcode, cached on the phone), and I'd be glad to hear if you'd like the User-Agent or the
> attribution done differently. Contributing missing products and photos back from the app is
> something I'd like to look at later. Thanks for the database!

**Before posting, check:**

- The attribution change in `detail_credit` (product sheet: "Product data and pictures from Open
  Food Facts (openfoodfacts.org), under the Open Database License and CC BY-SA") is in a released
  build. Until then the shipped wording is "Product data from Open Food Facts", without the licence
  names; ODbL and the terms of use ask for the licence to be mentioned.
- The open points in "API usage" below, in particular the User-Agent.

### Attribution, against Open Food Facts' terms

Open Food Facts' terms of use (<https://world.openfoodfacts.org/terms-of-use>): the database is
under the Open Database License, individual contents under the Database Contents License, and
"Products images are available under the Creative Commons Attribution ShareAlike licence".
Re-users "have to mention the licence and to attribute the authorship to Open Food Facts", with a
link to openfoodfacts.org (or the local version) or to the product page, and contributors are
credited "by a link to the product to which they contribute".

What the app does:

- Product sheet: `ProductDetailSheet.kt:278` (Android) and `ProductDetailView.swift:38` (iOS) show
  `detail_credit`. Now "Product data and pictures from Open Food Facts (openfoodfacts.org), under the
  Open Database License and CC BY-SA" (en/ru/nb on both platforms); before this change "Product data
  from Open Food Facts", with no licence and no mention of pictures.
- The credit only appears when the product has nutrition data (`if (!nutrition.isEmpty)`). A product
  that came from Open Food Facts with only a name and picture shows no credit.
- There is no About, licences or acknowledgements screen on either platform. The licence texts for
  Vollkorn, Google Sans and Lucide are bundled (`androidApp/src/main/assets/licenses/`,
  `iosApp/FoodTracker/Resources/Licenses/`) but nothing in the UI shows them.
- While a lookup runs, the Russian and Norwegian scanner text names Open Food Facts (`looking_up_product`); the English one ("Looking up this product…") does not.
- The text isn't a link, and there is no link to the product page on openfoodfacts.org.
- README "License" credits Open Food Facts, ODbL and CC BY-SA; the App Store description says
  "(via Open Food Facts)".

Still missing (larger UI work, not done here):

1. Show the credit for every item whose data came from Open Food Facts (it has a barcode and a
   cached Open Food Facts product), not only when there is nutrition data.
2. A permanent place: an "About" / "Acknowledgements" row in Settings (the "Data" section is the
   natural spot on both platforms) naming Open Food Facts, ODbL and CC BY-SA with a link to
   https://world.openfoodfacts.org, and showing the bundled font and icon licences too.
3. Optionally a "View on Open Food Facts" link on the product sheet
   (`https://world.openfoodfacts.org/product/{barcode}`), which is the terms' way of crediting the
   contributors.

### API usage, against Open Food Facts' guidance

Guidance: <https://openfoodfacts.github.io/openfoodfacts-server/api/>.

| Guidance | The app | Gap |
| --- | --- | --- |
| "always use a custom User-Agent", in the form `AppName/Version (ContactEmail)` | `FoodTracker-Android/1.0 (https://github.com/daniil-pastuhov/FoodTrack)` (`shared/src/commonMain/kotlin/com/foodtracker/api/OpenFoodFactsApi.kt:116`); `FoodTracker-iOS/1.0 (…same URL)` (`iosApp/FoodTracker/ViewModels/NativePantryViewModel.swift:1041`, and for pictures `iosApp/FoodTracker/Views/ProductImageView.swift:102`) | Name is the code name, not "BestBefore"; version is hard-coded "1.0" while the app is 2.0.0 (`Version.xcconfig`); a URL instead of an e-mail. Suggest `BestBefore-Android/<versionName> (https://github.com/daniil-pastuhov/FoodTrack)`, reading the version from the build. Whether a URL is acceptable in place of an e-mail is Open Food Facts' call; the owner has chosen not to publish an address. |
| Product reads: 15 requests/min per IP | One request per scanned barcode, from the user's phone; cached products are not requested again | None in normal use. Nothing throttles a fast batch scan, and an HTTP 429 is shown as "offline" (the user can retry). |
| Use the staging server while testing | Unit tests use a mock HTTP engine (`OpenFoodFactsLookupTest.kt`), so they make no requests | None. Debug builds use the production server. |
| `fields` parameter (not required) | Full product requested | Optional: `?fields=product_name,brands,image_front_url,categories,quantity,…` would cut response size. |
| Images | Android's image loader (Coil 2.7) sends its default User-Agent for pictures | Minor: iOS sets the custom one for pictures, Android doesn't. |
