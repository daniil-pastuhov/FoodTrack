# App Store submission kit

Everything App Store Connect asks for. The release flow applies the listing texts, the
screenshots and the header art ("Changing the listing texts" below); the rest is ready to paste. One folder per
listing language: `en-GB/` (English, U.K., the primary language), `en-US/` (English, U.S.), `ru/` (Russian),
`no/` (Norwegian), and `en-AU/` (English, Australia) and `en-CA/` (English, Canada) with
`keywords.txt` alone ("Australia and Canada" below). Each full folder has `name.txt`,
`subtitle.txt`, `promotional_text.txt`, `keywords.txt` and `description.txt`; another English
folder may leave any of them out and takes en-GB's. `scripts/check_store_listing.py` checks them
(and the Google Play texts) against the limits: name and subtitle 30 characters, keywords 100
(comma-separated, no spaces after commas, no word that the same language's name or subtitle
already has, inherited ones included; en-AU also none of en-GB's keywords), promotional text 170,
description 4000. Run it after every edit.

`en-US/`, `en-AU/` and `en-CA/` have texts only. Its screenshots and header art are en-GB's: App Store Connect shows the
primary language's screenshots in a localization that has none, so nothing needs uploading
there (the sync places en-GB's header art on it, see below), and the screenshot scripts only
make the en-GB, ru and no sets. The en-GB, ru and no
folders have these screenshot sets (up to 10 images each; the two iPhone Duo folders share one
set of 10), in App Store Connect's media sections of the same name:

| Folder | Size | App Store Connect display |
|---|---|---|
| `screenshots/` | 1320×2868 | iPhone with Dynamic Island (large display), 6.9" |
| `medium-screenshots/` | 1206×2622 | iPhone with Dynamic Island (medium display), 6.3"; at least one is required |
| `duo-inner-screenshots/` | 2853×2007 | iPhone Duo, inner display, held sideways (2007×2853 upright works too) |
| `duo-outer-screenshots/` | 1398×2034 | iPhone Duo, outer display (2034×1398 sideways works too) |
| `ipad-screenshots/` | 2064×2752 | iPad 13" |

iPhone Duo screenshots put an "optimized for iPhone Duo" badge on the product page, and from
April 2027 they are required for apps built with the iOS 27.1 SDK.

Every set tells the same story, Google Play's too: 1 Home, 2 a scan that has read a product and
its printed date, 3 a written recipe, 4 a three-day menu, 5 the shopping list, 6 Settings with the
reminders. The two iPhone Duo sets share one group of at most 10 images per language in App
Store Connect, so they stop at 5 (`SHOT_LIMITS` in `scripts/frame_screenshots.py`). Retake them
after UI changes: `scripts/ios_screenshots.sh <raw-dir>` captures a demo pantry
(`scripts/demo_backup.py`) in every language, then `scripts/frame_screenshots.py <raw-dir>
<folder>` adds the captions and device frame in Larder's colours (the captions live in that
script). The Google Play set is made the same way: `ANDROID_SERIAL=<emulator>
scripts/android_screenshots.sh <raw-dir>`, then `scripts/frame_screenshots.py <raw-dir>
phone-screenshots`. The scan shows a picture `scripts/demo_scan_image.py` draws (a made-up carton,
no real product, brand or photograph) in place of the camera, and the recipe and the menu come
from `scripts/demo_recipes.py`; the debug-only demo routes that show them are described in those
scripts. On a slow machine, `SETTLE=<seconds>` waits longer before each capture. Pick the
simulator with `DEVICE_NAME`:

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
| `header.png` | 3840×1646 (21:9) | Product page header: three phones (scan, Home, recipe), no text |
| `search-result.png` | 3840×2560 (3:2) | Search results: a headline (not screenshot 1's caption) and the home screen |

`scripts/store_art.py <raw-dir>` makes them from the same raw captures as the iPhone sets (the
headlines live in that script); `scripts/store_art.py <raw-dir> <android-raw-dir>` also makes
Google Play's feature graphic (1024×500) from the Android captures. The layouts keep key artwork inside the safe areas of Apple's
templates, and the search headline stays readable when the image is scaled to 384 px wide, about
the width search shows it at on an iPhone. Neither image may show prices, URLs, a copyright
symbol, other platforms or Apple awards.

## Changing the listing texts

Edit the files here; the release PR's merge applies them. `scripts/testflight.py appstore`
(run by CI's `release-app-store` job, with `--screenshots`) writes them to the new version
before it submits it for review, and only what differs from App Store Connect:

- **Name, subtitle** (`name.txt`, `subtitle.txt`) on the app info in preparation, and
  **keywords, description, promotional text** on the version. App Store Connect lets the name,
  subtitle, keywords and description change only with a new version, so they go live when that
  version is approved. A localization the repo has and App Store Connect lacks is added (en-US
  was added this way), with the support, marketing and privacy policy URLs copied from en-GB;
  those URLs are otherwise never touched. An English folder without some of the files (en-AU,
  en-CA) takes en-GB's for them, so a localization added from it has every field; the dry run
  says per localization which fields come from its folder and which are inherited from en-GB.
- **Screenshots**: `screenshots/`, `medium-screenshots/` and `ipad-screenshots/` replace each
  localization's set of that display type, in file-number order. Unchanged images (same MD5)
  stay, changed or new ones are uploaded, and images no longer in the folder are deleted. At
  most 10 per folder. A localization without these folders (en-US) shows en-GB's.
- **iPhone Duo screenshots**: `duo-inner-screenshots/` and `duo-outer-screenshots/` go through
  the App Asset Library, as the set-based API has no Duo display type. Both displays share the
  version's one `IPHONE_DUO_PROFILE` group, at most 10 images in all, shown inner first and each
  folder in file-number order; the sync tells the two apart by image size and stops on an image
  whose size doesn't fit its folder. The library keeps no checksum, so each image the sync
  uploads carries its MD5 in its reference name (`en-GB/duo-outer-screenshots/1.png
  md5:…`): unchanged images stay, an image already in the library is placed again without
  uploading, and images uploaded on the website (which have no MD5) are replaced once. Removed
  images lose their placement but stay in the library, where older versions may still use them.
- **Header and search-result images** (`header.png`, `search-result.png`; iOS 27): also
  through the App Asset Library, the same way, as creative assets placed with the types
  `PRODUCT_PAGE_HEADER_ASSET` and `APP_STORE_SEARCH_RESULTS_ASSET` (one each per localization).
  The size is checked against the specs in Apple's reference data first (header 3840×1646,
  search result 3:2 from 1920×1280 to 3840×2560). Another English localization without the
  files (en-US, en-AU, en-CA) gets en-GB's placed, reusing the same library image, as Apple
  documents no fallback to the primary language for these. A localization whose folder has
  neither file is left as it is.
- **App Review notes** (`review-notes.txt`): written to the version's App Review notes; only
  the notes, never the contact or demo-account fields. A version without a review detail gets
  one with the notes alone.
- **In-app purchases**: before submitting, `appstore` puts every in-app purchase still to be
  reviewed (`READY_TO_SUBMIT`, read from App Store Connect, none hard-coded) into the same
  review submission as the version: the version's item first, then one item per purchase's
  draft In-App Purchase version. Apple requires the first purchase of each type to be reviewed
  with a new app version. Its API article says that first submission goes through the website,
  so App Store Connect may refuse the items; then nothing is submitted and the job says what to
  do: on each purchase's page, Add for Review into the draft submission that holds the version
  (without clicking Submit for Review there), and re-run the job, which finds them in the
  submission and submits everything together. Purchases already in the submission or in review
  are left alone. If App Store Connect shows them with the version but the job can't see them,
  run `appstore` with `--iaps-attached`, which submits without adding them.
- If any of it fails (a text over a limit, a refused upload), the version is not submitted; fix
  it and re-run the job, which skips what is already applied.
- **What's New**: written once per language in `release-notes/vX.Y.Z/` and set by the same
  command; every English localization gets the en-GB notes.

Still by hand, as the sync doesn't write it:

- **Promotional text between releases**: it can change without a new version, but the sync only
  writes to a version in preparation; paste it on the live version page.

To see the difference without writing anything (with the `ASC_*` variables of section 2):

```sh
python3 scripts/testflight.py listing        # texts and App Review notes; --apply writes
python3 scripts/testflight.py iaps           # read-only: the in-app purchases and what
                                             # `appstore` would do with them
python3 scripts/testflight.py screenshots    # screenshot sets; --apply writes
python3 scripts/testflight.py screenshots --inspect  # read-only: the raw sets, library images
                                                     # and placements, as App Store Connect has them
```

Both compare with the version in preparation. While none is (after a release is submitted and
before the next merge), they say so and compare with the newest version, which is what the next
version starts from.

### Australia and Canada

Apple's [App Store localizations](https://developer.apple.com/help/app-store-connect/reference/app-store-localizations)
table gives Australia the default language English (Australia), with English (U.K.) as an
additional one, and Canada English (Canada), with French (Canada). Without an en-AU or en-CA
localization both showed en-GB, the primary language. Apple says only that the language shown
"could be impacted by" the storefront's language, the device's settings, the languages added and
the primary language; which localizations' keywords a storefront searches is not documented. The
folders follow the usual assumption that a storefront searches the localizations in its row:

- `en-AU/keywords.txt` adds words en-GB has no room for (Australia is assumed to search en-GB's
  too), so the checker refuses any word of en-GB's keywords, name or subtitle there.
- `en-CA/keywords.txt` stands on its own (en-GB is not in Canada's row), so it carries the core
  words again, with "expiration" beside the name's "expiry" and "best before", the Canadian
  date label.

Everything else (name, subtitle, description, promotional text, What's New, screenshots, header
and search-result art) is en-GB's, so they need no upkeep beyond their keywords. To give one its
own text, add that file to its folder. French (Canada) has no listing.

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
| In-app purchases | Three consumables, `recipes_10`, `recipes_50`, `recipes_100` (10, 50 and 100 recipes), USD 3.99 / 10.99 / 18.99 as the base price, the other storefronts converted by Apple. The first ones have to be submitted for review together with an app version; the release job adds them to the version's review submission ("Changing the listing texts"), and says how to add them by hand if App Store Connect refuses. Each needs a display name, description and review screenshot (the "Buy recipes" sheet, `iap-review/buy-recipes-sheet.png`). Setup: `docs/recipe-setup.md` |
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
    (with "Send all products" on in Recipe settings, every pantry item's, the others as items the
    recipe may use)
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
    Account > Delete account), and someone who bought without signing in deletes the anonymous
    account the same way (Recipe settings > Your recipes > Delete my data from our server). The
    Auth user (email, name; or the anonymous user), the server's recipes, the usage counters and
    the stored Sign in with Apple token (revoked at Apple first) are deleted; purchase records
    (with the old user ID, not linked to a name or email), a closed account record with totals,
    and any refund-after-use record stay. Give the policy URL.
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
- Notes: `review-notes.txt` in this folder, the reviewer's walkthrough of the app and the
  recipe-pack purchase. The release flow writes it to the version's App Review notes
  (`scripts/testflight.py appstore`, and `testflight.py listing` shows the difference); only
  the notes are written, never the contact or demo-account fields. At most 4000 characters
  (`scripts/check_store_listing.py`).

## 7. Version page

- What's New (first version): leave empty, or use `First release.`
- Pick the build uploaded in step 2.
- Screenshots and the header and search-result images: set by the release flow
  (`scripts/testflight.py appstore --screenshots`; see "Changing the listing texts"). English
  (U.S.) has no screenshots of its own and shows en-GB's; it gets en-GB's header art.

## 8. Launch promotion: in-app event and featuring nomination

For the release that brings the Recipe tab: an in-app event (badge Major Update) and a featuring
nomination. The release flow starts neither; both begin by hand, in the order of the timeline
below.

**The in-app event** lives in `in-app-event/`: `event.json` (reference name, badge, purpose,
priority, "no in-app purchase required", deep link, primary language, territories, schedule), one
folder per language with `name.txt`, `short_description.txt` and `long_description.txt`, and the
images `event-card.png` (3840×2160) and `event-details.png` (2160×3840) in en-GB, ru and no (en-US
has texts only and takes en-GB's images). Apple's limits, which `scripts/check_store_listing.py`
checks: reference name 64 characters, event name 30, short description 50, long description 120,
no prices; an event lasts at most 31 days and is published at most 14 days before it starts; the
card is 16:9 from 1920×1080 to 3840×2160, the details page 9:16 from 1080×1920 to 2160×3840 (App
Store Connect Help, [Offer In-App Events](https://developer.apple.com/help/app-store-connect/offer-in-app-events/offer-in-app-events)
and [media specifications](https://developer.apple.com/help/app-store-connect/reference/in-app-event-media-and-audio-specifications);
[In-App Events](https://developer.apple.com/app-store/in-app-events/)). `scripts/in_app_event_art.py`
redraws the images from the framed screenshots (`screenshots/` 2, 3 and 4: the scan, a recipe,
the menu) after those change. Apple wants no text or logos in event media, so the only words are
the app's own screens; it sets the event name and description over the bottom, which the layouts
leave to the phones' lower halves and plain green.

- **Schedule**: the event starts on `releaseDay` (null until you set it, as `YYYY-MM-DD`) at
  `startTimeUtc`, runs `durationDays` (30) and is published `publishDaysBefore` (0) days before
  it starts: on release day, because before the version is live the event's Open button would
  lead to a version without the Recipe tab. `"territories": "app"` means every territory the app
  is in; a list of 3-letter codes narrows it.
- **Deep link** `foodtracker://recipe`. The app already handles `foodtracker://` (the widgets use
  it), but `AppRoute(externalName:)` in `iosApp/FoodTracker/AppRouter.swift` has no `recipe` name
  yet, so until it has, the link only opens the app on the tab it was on. The smallest addition,
  to ship in the same version: `case "recipe": self = .tab(.recipe)`. A fresh install shows
  onboarding first and drops the route (`RootTabView` applies routes only after onboarding),
  which is acceptable: the person lands in the app either way.
- **The command**: `python3 scripts/testflight.py event` (with the `ASC_*` variables of section
  2) compares the folder with App Store Connect and prints what differs; `--apply` creates the
  event or updates it, uploads the images to the App Asset Library and places them on each event
  localization (images are reused by MD5, as for the screenshots). It writes only while the event
  is a draft or rejected, and a rerun changes nothing. It never submits the event. `--apply
  --add-to-review` puts it into a draft review submission (the one holding the version in
  preparation, else an open draft, else a new one) without sending it; the release job's
  `testflight.py appstore` then adds the version to that same draft and submits both, so the
  event is reviewed with the version that has the Recipe tab. Without `--add-to-review`, do it on
  the website: In-App Events > the event > Add for Review, into the draft that holds the version.
- **By hand**: preview the card and the details page in App Store Connect (Apple publishes no
  safe areas and recommends checking the preview).

**The featuring nomination** is `featuring-nomination.md`: the answers for App Store Connect >
Featuring > Nominations, ready to paste, with two placeholders (the release window, the event's
ID). [Getting featured](https://developer.apple.com/app-store/getting-featured/) asks for at least
two weeks' notice, App Store Connect Help for at least three; plan on three or more. It is pasted
by hand; nothing here automates it.

**Timeline**, counted from release day R, the day the version goes live. With the default
`APP_STORE_RELEASE_TYPE` (AFTER_APPROVAL), R is whenever Apple approves; set that repository
variable to `MANUAL` for this release to choose R, and release by hand on R.

| When | What | Who |
|---|---|---|
| R − 6 to R − 3 weeks | Submit the featuring nomination (App Enhancements, publish window around R) | You |
| By R − 3 weeks | Merge the deep-link line; set `releaseDay`; `testflight.py event`, then `event --apply` | You |
| Same day | Preview the event media in App Store Connect; attach the event's ID to the nomination | You |
| Before the release PR merges | `testflight.py event --apply --add-to-review` | You |
| Release PR merge (about R − 3 days) | `appstore` submits the version, the in-app purchases and the event together | CI |
| R | Release the version (with MANUAL); the event is published and starts at `startTimeUtc` | You, Apple |
| R + 30 days | The event ends | Apple |

If approval is late, the event's start can pass before it is approved: move `releaseDay` and
rerun `event --apply` while the event can still be edited, as Apple wants a start in the future.
