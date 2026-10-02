# App Store submission kit

Everything App Store Connect asks for, ready to paste. One folder per listing language:
`en-GB/` (English, U.K.), `ru/` (Russian), `no/` (Norwegian). Each folder has `name.txt`,
`subtitle.txt`, `promotional_text.txt`, `keywords.txt`, `description.txt` and
`screenshots/1-4.png` (1320×2868, the 6.9" iPhone size; App Store Connect scales them down for
smaller iPhones).

The app is iPhone-only. It still runs on iPad in iPhone mode, but no iPad screenshots are needed.

Retake the raw screenshots after UI changes with `scripts/ios_screenshots.sh`.

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
   +. Role: App Manager. Download the `.p8` once, and keep it outside the repo, e.g.
   `~/.config/bestbefore/`. Note the Key ID and the Issuer ID.

## 2. Uploading a build

From this Mac:

```sh
ASC_KEY_ID=XXXXXXXXXX ASC_ISSUER_ID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx \
ASC_KEY_PATH=~/.config/bestbefore/AuthKey_XXXXXXXXXX.p8 scripts/ios_release.sh
```

From CI, on every push to main:
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

- **Data collection: "No, we do not collect data from this app."**
  - Apple counts data as collected only when it is sent off the device to you or a partner.
  - BestBefore has no account, analytics or ads, and no server.
  - Barcode lookups go to Open Food Facts without any user or device identifier, and nothing
    else leaves the phone.
- **Tracking: No.**

## 6. App Review information

- Sign-in required: **No**.
- Contact: your name, phone and email. Apple only uses these to reach you about the review; they
  are not shown publicly.
- Notes (paste as is):

```
BestBefore has no account or login; all data stays on the device.

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
- Screenshots: drag `screenshots/1.png` to `4.png` into "iPhone 6.9" Display" for each language.
