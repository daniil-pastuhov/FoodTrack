# App Store submission kit

Everything App Store Connect asks for, ready to paste. One folder per listing language:
`en-GB/` (English, U.K.), `ru/` (Russian), `no/` (Norwegian). Each folder has `name.txt`,
`subtitle.txt`, `promotional_text.txt`, `keywords.txt`, `description.txt` and these
screenshot sets, four each, in App Store Connect's media sections of the same name:

| Folder | Size | App Store Connect display |
|---|---|---|
| `screenshots/` | 1320×2868 | iPhone with Dynamic Island (large display), 6.9" |
| `medium-screenshots/` | 1206×2622 | iPhone with Dynamic Island (medium display), 6.3"; at least one is required |
| `duo-inner-screenshots/` | 2853×2007 | iPhone Duo, inner display, held sideways (2007×2853 upright works too) |
| `duo-outer-screenshots/` | 1398×2034 | iPhone Duo, outer display |
| `ipad-screenshots/` | 2064×2752 | iPad 13" |

iPhone Duo screenshots put an "optimized for iPhone Duo" badge on the product page, and from
April 2027 they are required for apps built with the iOS 27.1 SDK.

Retake them after UI changes: `scripts/ios_screenshots.sh <raw-dir>` captures a demo pantry
(`scripts/demo_backup.py`) in every language, then `scripts/frame_screenshots.py <raw-dir>
<folder>` adds the captions and device frame in Larder's colours (the captions live in that
script). The Google Play set is made the same way: `ANDROID_SERIAL=<emulator>
scripts/android_screenshots.sh <raw-dir>`, then `scripts/frame_screenshots.py <raw-dir>
phone-screenshots`. On a slow machine, `SETTLE=<seconds>` waits longer before each capture. Pick the simulator with `DEVICE_NAME`:

- `screenshots/`: the default, iPhone 17 Pro Max.
- `medium-screenshots/`: `DEVICE_NAME="iPhone 17 Pro"`. Not an iOS 27.0 simulator: there the app
  hangs on its launch screen, waiting on the notification service, so every capture is blank.
- `duo-inner-screenshots/`: `DEVICE_NAME="iPhone Duo" SCREEN=3`, with the simulator unfolded.
- `duo-outer-screenshots/`: `DEVICE_NAME="iPhone Duo" SCREEN=1`. A simulator booted without a
  window comes up folded. The pose is set in Xcode's Device Hub (Xcode 27 has no Simulator app);
  simctl can't change it. `xcrun simctl io <udid> enumerate` lists the screen IDs.
- `ipad-screenshots/`: `DEVICE_NAME="iPad Pro 13-inch (M5)"`.

Each folder also has the two images for the version page's "Header and Search Results" section,
which the App Store shows on iOS 27 and iPadOS 27 and later. They are optional; without them,
search results show the screenshots.

| File | Size | Placement |
|---|---|---|
| `header.png` | 3840×1646 (21:9) | Product page header: three phones, no text |
| `search-result.png` | 3840×2560 (3:2) | Search results: a headline and the home screen |

`scripts/store_art.py <raw-dir>` makes them from the same raw captures as the iPhone sets (the
headlines live in that script); `scripts/store_art.py <raw-dir> <android-raw-dir>` also makes
Google Play's feature graphic (1024×500) from the Android captures. The layouts keep key artwork inside the safe areas of Apple's
templates, and the search headline stays readable when the image is scaled to 384 px wide, about
the width search shows it at on an iPhone. Neither image may show prices, URLs, a copyright
symbol, other platforms or Apple awards.

## 1. One-time setup

1. **Apple Developer Program**: paid membership ($99/year) for team `S8Q8KHX6G5`.
2. **App record**: App Store Connect → Apps → + → New App.
   - Platform: iOS. Name: `BestBefore: Expiry Tracker`.
   - Primary language: English (U.K.).
   - Bundle ID: `com.foodbest.ios`. If it isn't listed, register it under Certificates, IDs &
     Profiles → Identifiers, with the App Groups capability (`group.com.foodbest.ios`). Do the
     same for `com.foodbest.ios.FoodTrackerWidget`.
   - SKU: `bestbefore-ios`. User access: Full access.
3. **API key** for uploads: Users and Access → Integrations → App Store Connect API → Team Keys →
   +. Role: **Admin**. Automatic signing creates the App Store distribution certificate in the
   cloud, and Apple only lets Admin keys do that (an App Manager key fails with "Cloud signing
   permission error"). Download the `.p8` once, and keep it outside the repo, e.g.
   `~/.config/bestbefore/`. Note the Key ID and the Issuer ID.

## 2. Uploading a build

From this Mac:

```sh
ASC_KEY_ID=XXXXXXXXXX ASC_ISSUER_ID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx \
ASC_KEY_PATH=~/.config/bestbefore/AuthKey_XXXXXXXXXX.p8 scripts/ios_release.sh
```

From CI, on every push to a `release/v*` branch (see "Releasing" in the top-level README.md):
- Add the repository secrets `ASC_KEY_ID`, `ASC_ISSUER_ID` and `ASC_KEY_P8_BASE64`, the last one
  made with `base64 -i AuthKey_XXXX.p8`.
- Set the repository variable `IOS_PUBLISH` to `true`.

## 3. App information

| Field | Value |
|---|---|
| Privacy Policy URL | https://daniil-pastuhov.github.io/bestbefore-privacy/ |
| Support URL | https://daniil-pastuhov.github.io/bestbefore-privacy/support.html |
| Marketing URL | leave empty |
| Category | Primary: **Food & Drink**. Secondary: **Productivity** |
| Copyright | `2026 Daniil Pastuhov` |
| Content rights | Does not contain, show or access third-party content. (Product names and pictures come from Open Food Facts, an open database; if asked, answer yes and that you have the rights under its ODbL/CC BY-SA licences.) |
| Price | Free, with in-app purchases. Availability: all countries, or pick |
| In-app purchases | Three consumables, `recipes_10`, `recipes_50`, `recipes_100` (10, 50 and 100 recipes), USD 3.99 / 10.99 / 18.99 as the base price, the other storefronts converted by Apple. The first ones have to be submitted for review together with an app version (select them on the version page under "In-App Purchases and Subscriptions"). Each needs a display name, description and review screenshot (the "Buy recipes" sheet, `iap-review/buy-recipes-sheet.png`). Setup: `docs/recipe-setup.md` |
| Export compliance | Already answered in Info.plist (`ITSAppUsesNonExemptEncryption = NO`) |

## 4. Age rating

Answer **None / No** to every question: violence, sexual content, profanity, drugs, gambling,
horror, medical, unrestricted web access, user-generated content, chat, contests.
The result is **4+**.

## 5. App Privacy ("nutrition label")

What the app sends off the phone, and so what Apple counts as collected (data sent to you or a
partner). `iosApp/FoodTracker/PrivacyInfo.xcprivacy` declares the recipe items below (Purchase History,
User ID, Email Address, Name, Other User Content, Health); the Firebase SDKs ship their own privacy
manifests for crash reporting. The
privacy policy (URL above) has to say the same; it is updated in
<https://github.com/daniil-pastuhov/bestbefore-privacy/pull/1> (`docs/privacy-policy-changes.md`).

- **Data collection: "Yes, we collect data from this app."**
- From everyone, release builds only: crash reports to Firebase Crashlytics. Each **not linked
  to the user**, **not used for tracking**, purpose **App Functionality**:
  - **Diagnostics > Crash Data**: the crash report itself.
  - **Diagnostics > Other Diagnostic Data**: device model, OS version, free disk and memory at
    the time of the crash.
  - **Identifiers > Device ID**: the Firebase installation ID Crashlytics groups crashes by.
- Only from someone who starts a purchase of a recipe pack (nothing of this is sent by the free
  recipe routes or the rest of the app). Each **linked to the user** (it is stored under their
  Firebase Auth user ID), **not used for tracking**, purpose **App Functionality**:
  - **Purchases > Purchase History**: the packs bought, verified with the App Store and kept as
    the record that stops one purchase being redeemed twice; the buyer's refund-after-use record
    (`buyers/`, under Apple's app transaction ID), which turns buying off after repeated refunds
    of used recipes; and, only for purchases made after the Buy sheet showed the line "If you
    ask Apple for a refund, we tell Apple how many of these recipes you have used.", the share of
    the purchase used, sent to Apple when the buyer asks Apple for a refund (consumption
    information: delivered, percentage used, sample content provided; no account or play-time
    data).
  - **Identifiers > User ID**: the Firebase Auth user ID, anonymous or signed in, that holds the
    recipe balance, and the buyer identifiers kept with each purchase (Apple's app transaction
    ID; a code made from the user ID given to the App Store as `appAccountToken`).
  - **Contact Info > Email Address**: only when the buyer signs in with Apple (possibly Apple's
    relay address) or Google.
  - **Contact Info > Name**: only when the buyer signs in with Google. The server clears the
    name and photo from the Firebase Auth user after sign-in, but Firebase keeps a copy in the
    Google provider entry until the account is deleted, and the server cannot remove it. The
    apps never ask Apple for the name. Declaring it is the honest answer; `PrivacyInfo.xcprivacy`
    has the matching Name entry (linked, not tracking, App Functionality).
  - **User Content > Other User Content**: the ticked items' names, expiry dates and quantities
    and the recipe settings sent to write a recipe or menu, and the recipe written back (kept on
    the server for 7 days after it finished, for retries, then deleted). The request itself is
    not stored, only a hash of it; it is screened automatically for prompt injection, and
    refused attempts are counted per account per day, without their text.
  - **Health & Fitness > Health**: the **allergen list** from Settings, sent with a server-written
    recipe or menu. Apple defines Health as "Health and medical data, including ... any other
    user provided health or medical data"; a list of the person's food allergies is
    user-provided health data (and data concerning health under GDPR Art. 9), so it is declared
    here rather than under Sensitive Info (which Apple defines by other categories: racial or
    ethnic data, sexual orientation, pregnancy, disability, beliefs, union membership, political
    opinion, genetic or biometric data) or Other Data. Linked, not tracking, App Functionality.
    It is sent only after the person agreed in the app's consent step (recorded on the phone,
    withdrawable in Recipe settings; the server refuses allergens without the consent flag), it
    is used only to instruct the model and check the answer, and it is not stored after the
    request (only inside the request hash). Not sent at all by the free routes.
  - Not a separate data type: the **Sign in with Apple refresh token** the server keeps,
    encrypted with a Cloud KMS key, for accounts that signed in with Apple on an iPhone. It is a
    credential issued to the app, not data about the person beyond the account already declared
    (User ID, Email Address), and it is used only to revoke Apple's link when the account is
    deleted (on iPhone or Android). The privacy policy says so.
- Not separate data types, but the policy covers them: App Check (App Attest) on every call to
  the server; the IP address, used in memory for per-minute rate limiting and not stored by the
  server, but recorded with each request in Google Cloud's request logs (30 days); log lines with
  a hashed user ID, never names, items or recipes.
- Barcode lookups go to Open Food Facts without any user or device identifier, and the shared
  prompt goes only to the app the user picks, so neither counts as collection by us.
- **Tracking: No.**
- Google Play's Data safety form needs the matching answers. Collected, not shared (Google acts
  as our processor; Apple is not involved on Android), encrypted in transit:
  - **Crash logs**, **Diagnostics**, **Device or other IDs**: not optional, **App functionality**
    (unchanged); add **Fraud prevention, security and compliance** to Device or other IDs for
    App Check (Play Integrity) and rate limiting.
  - **Purchase history**, **User IDs**: optional (only for buyers), **App functionality**,
    **Account management**, **Fraud prevention, security and compliance** (the refund-after-use
    record, under the user ID and a hash of the sign-in provider's user ID).
  - **Email address**, **Name**: optional (only when signed in; the name only with Google),
    **App functionality**, **Account management**.
  - **Other user-generated content** (the ingredients and settings sent, the recipe returned):
    optional, **App functionality**.
  - **Health and fitness > Health info** (the allergen list, "Information about a user's health,
    such as medical records or symptoms"): collected, not shared, optional (only with allergens
    set, and only after the consent step), **App functionality**; processed ephemerally ("Is
    this data processed ephemerally?": **Yes**: it is used for the one request and not stored).
  - Data deletion: users can delete their account in the app (Recipe tab > Recipe settings >
    Account > Delete account). The Auth user (email, name), the server's recipes, the usage
    counters and the stored Sign in with Apple token (revoked at Apple first) are deleted;
    purchase records (with the old user ID, not linked to a name or email), a closed account
    record with totals, and any refund-after-use record stay. Give the policy URL.
  - Account creation: "Users can create an account" with Google sign-in (OAuth), and the app
    works without one. The Android app has no Sign in with Apple (only the iPhone app offers it);
    an account made with Apple on an iPhone is used on Android through Google added to it, and
    deleting it on Android removes Apple's link too (with the token the server stored at the
    iPhone sign-in; if that fails, the app says how to remove it in the Apple Account settings).

## 6. App Review information

- Sign-in required: **No**. Signing in is optional and only offered for recipe packs.
- Contact: your name, phone and email. Apple only uses these to reach you about the review; they
  are not shown publicly.
- Before submitting, set `allowSandbox` to `true` in Firestore `config/runtime` (see
  `functions/README.md`), or the server refuses the reviewer's sandbox purchase. Its caps apply to
  every sandbox and TestFlight user together: `sandboxDailyCap` (default 20 purchases a day) and
  `sandboxDailyCredits` (default 200 recipes a day); over either, the purchase waits (`RETRY`;
  the app keeps resubmitting it) until the next UTC day. Check that today's use leaves room, and turn it off after approval.
- Notes (paste as is):

```
BestBefore needs no account; the pantry stays on the device. Apart from barcode lookups on Open Food Facts and crash reports to Firebase Crashlytics, the app only contacts our server for recipe packs, and only after a purchase is started.

To try it quickly: tap the scan button and scan any food barcode (or use "+" to add an item by hand), then open the Recipe and Shopping List tabs.

RECIPE TAB AND IN-APP PURCHASES
The Recipe tab writes a recipe from the items closest to their date. Free routes: "Share prompt" / "Copy prompt" hand a text prompt to any app the user picks, and on devices with Apple Intelligence (iOS 26 or later) "Generate on this phone" uses Apple's on-device Foundation Models. Paid route: recipe packs, consumable in-app purchases of 10, 50 or 100 recipes; one recipe is one dish written by our server (Google Gemini through Firebase Cloud Functions). "Menu for 3 days" is server-only and takes several recipes from the pack (currently 4 with two courses a day, 5 with three; the app shows the number before you generate).
Sandbox purchases are accepted for this review, within a daily limit shared by all sandbox testers. To test the purchase with a sandbox account:
1. Add an item or two (scan, or "+").
2. Open the Recipe tab and tap "Buy recipes".
3. Choose "On this phone, no account", or sign in with Apple to keep the recipes in an account.
4. Buy the 10-recipe pack with the sandbox account. The sheet notes that if you ask Apple for a refund, we tell Apple how many of the recipes were used. The balance shows 10. If it says the purchase is being confirmed, tap "Try now".
5. Tap "Generate recipe". The recipe appears after a few seconds and the balance drops to 9. A recipe is taken only when it arrives; a failed attempt normally takes nothing.
Account deletion: Recipe tab > Recipe settings > Account > Delete account (shown when signed in).

Location is optional and only used for "Shop reminders": Settings → Shopping reminders → turn on "Remind me near a shop", name a shop and tap the location button to save your current position. With "Always" location access, the app shows the shopping list when you arrive at that shop (iOS region monitoring); with "While Using" it only works while the app is open. The app never sends the location anywhere.

The camera is used for barcode scanning, reading printed expiry dates (Vision, on device) and product photos, which stay on the device.
```

## 7. Version page

- What's New (first version): leave empty, or use `First release.`
- Pick the build uploaded in step 2.
- Screenshots, for each language: drag `screenshots/1.png` to `4.png` into "iPhone 6.9" Display",
  and `ipad-screenshots/1.png` to `4.png` into "iPad 13" Display".
