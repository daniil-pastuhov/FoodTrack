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
headlines live in that script). The layouts keep key artwork inside the safe areas of Apple's
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
| Price | Free. Availability: all countries, or pick |
| Export compliance | Already answered in Info.plist (`ITSAppUsesNonExemptEncryption = NO`) |

## 4. Age rating

Answer **None / No** to every question: violence, sexual content, profanity, drugs, gambling,
horror, medical, unrestricted web access, user-generated content, chat, contests.
The result is **4+**.

## 5. App Privacy ("nutrition label")

- **Data collection: "Yes, we collect data from this app."** The only collection is crash
  reports, sent to Firebase Crashlytics from release builds.
  - Apple counts data as collected only when it is sent off the device to you or a partner.
  - BestBefore has no account, analytics or ads, and no server of its own.
  - Barcode lookups go to Open Food Facts without any user or device identifier.
  - Declare these types, each **not linked to the user** and **not used for tracking**, with
    the purpose **App Functionality**:
    - **Diagnostics > Crash Data**: the crash report itself.
    - **Diagnostics > Other Diagnostic Data**: device model, OS version, free disk and memory
      at the time of the crash.
    - **Identifiers > Device ID**: the Firebase installation ID Crashlytics groups crashes by.
- **Tracking: No.**
- The privacy policy (URL above) has to say the same: crash reports go to Google's Firebase
  Crashlytics.
- Google Play's Data safety form needs the matching answers: **Crash logs**, **Diagnostics**
  and **Device or other IDs** are collected, are not shared, are encrypted in transit, are
  not optional, and are used for **App functionality**.

## 6. App Review information

- Sign-in required: **No**.
- Contact: your name, phone and email. Apple only uses these to reach you about the review; they
  are not shown publicly.
- Notes (paste as is):

```
BestBefore has no account or login; the pantry stays on the device. Apart from barcode lookups on Open Food Facts, the app sends only crash reports, to Firebase Crashlytics.

To try it quickly: tap the scan button and scan any food barcode (or use "+" to add an item by
hand), then open the Calendar and Shopping List tabs.

Location is optional and only used for "Shop reminders": Settings → Shopping reminders →
turn on "Remind me near a shop", name a shop and tap the location button to save your current
position. With "Always" location access, the app shows the shopping list when you arrive at
that shop (iOS region monitoring); with "While Using" it only works while the app is open. The
app never sends the location anywhere.

The camera is used for barcode scanning, reading printed expiry dates (Vision, on device) and
product photos, which stay on the device.
```

## 7. Version page

- What's New (first version): leave empty, or use `First release.`
- Pick the build uploaded in step 2.
- Screenshots, for each language: drag `screenshots/1.png` to `4.png` into "iPhone 6.9" Display",
  and `ipad-screenshots/1.png` to `4.png` into "iPad 13" Display".
