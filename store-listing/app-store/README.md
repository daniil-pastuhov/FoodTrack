# App Store submission kit

Everything App Store Connect asks for. The release flow applies the listing texts, the
screenshots and the header art ("Changing the listing texts" below); the rest is ready to paste. One folder per
listing language: `en-GB/` (English, U.K., the primary language), `en-US/` (English, U.S.), `ru/` (Russian),
`no/` (Norwegian), `es-ES/` (Spanish, Spain), `es-MX/` (Spanish, Mexico), `de-DE/` (German),
`fr-FR/` (French), `fr-CA/` (French, Canada), `sv/` (Swedish), `da/` (Danish), and `en-AU/`
(English, Australia) and `en-CA/` (English, Canada) with `keywords.txt` alone ("Australia and
Canada" below). Each full folder has `name.txt`, `subtitle.txt`, `promotional_text.txt`,
`keywords.txt`, `description.txt` and `privacy_url.txt` (the localization's privacy policy URL,
"Privacy policy URL per language" below). A regional folder may leave any of them out and takes its
parent language's file instead: another English folder en-GB's, `es-MX/` es-ES's and `fr-CA/`
fr-FR's (`fallback()` in `scripts/testflight.py`, `fallback_name` in
`scripts/check_store_listing.py`). No other language falls back. `scripts/check_store_listing.py` checks them
(and the Google Play texts) against the limits: name and subtitle 30 characters, keywords 100
(comma-separated, no spaces after commas, no word that the same language's name or subtitle
already has, inherited ones included; en-AU also none of en-GB's keywords), promotional text 170,
description 4000, and that `privacy_url.txt` is the policy site's page for the folder's language.
Run it after every edit.

`en-US/`, `en-AU/` and `en-CA/` have texts only. Their screenshots and header art are en-GB's: App Store Connect shows the
primary language's screenshots in a localization that has none, so nothing needs uploading
there (the sync places en-GB's header art on it, see below). `es-MX/` and `fr-CA/` have no
screenshots either, but App Store Connect would show them the English ones, so the sync uploads
es-ES's and fr-FR's sets, Duo sets and header art to them (`screenshot_folder` in
`scripts/testflight.py`). The screenshot scripts make the en-GB, ru, no, es-ES, de-DE, fr-FR, sv
and da sets, and those folders have these screenshot sets (up to 10 images each; the two iPhone Duo folders share one
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
its printed date, 3 a written recipe (the three that search results show), 4 the Recipe form in
Menu set mode with Days and Courses chosen ("Choose the days and the courses"; on the iPhones
the form at its top with a three-item pantry, `DEMO_ITEMS` in `scripts/demo_backup.py`, so the
switch, "Cook with" and both choices are in view; on Play scrolled to its end, Generate menu with
its cost included; on the iPad the whole form), 5 the menu set of four days it writes ("Plan up to four days around
what you have": four is the most the form offers, so keep the demo menu in
`scripts/demo_recipes.py` at four days while the caption says so), 6 the shopping list. The two
iPhone Duo sets share one group of at most 10 images per language in App Store Connect, so they
stop at 5, without the Menu set form, which their outer display is too short to show whole
(`SHOT_PICKS` in `scripts/frame_screenshots.py`). Retake them
after UI changes: `scripts/ios_screenshots.sh <raw-dir>` captures a demo pantry
(`scripts/demo_backup.py`) in every language on one simulator (`LANGS="en de"` for some of
them; `SKIP_BUILD=1` reuses the Debug build the previous device's run made, so a capture run is
one device after another with every language on each), then `scripts/frame_screenshots.py <raw-dir>
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
- `duo-inner-screenshots/`: `DEVICE_NAME="iPhone Duo" SCREEN=3 KEEP_REGION=1`, with the
  simulator unfolded (`scripts/duo_pose.sh Open`).
- `duo-outer-screenshots/`: `DEVICE_NAME="iPhone Duo" SCREEN=1`. A simulator boots folded. The
  pose is set in Xcode's Device Hub (Xcode 27 has no Simulator app), with the Closed / Partially
  Open / Open buttons beside the device; simctl can't change it. `scripts/duo_pose.sh
  Open|Closed` clicks them through System Events (Device Hub open on the simulator, Accessibility
  access for the terminal). `xcrun simctl io <udid> enumerate` lists the screen IDs.
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

Two more images come from the same captures but have no script of their own. `docs/images/`
(the README's top) is the en-GB `screenshots/` 1, 2, 3 and 6 scaled to 460×1000 JPEG.
`iap-review/buy-recipes-sheet.png`, the in-app purchases' review screenshot, is the Buy recipes
sheet at US prices on the 6.9" simulator: it needs real StoreKit products, which only an Xcode
run or test gets, so it is taken by a throwaway UI test (not committed) that launches the Debug
app with `-demoBackup <file> -demoRecipeCredits 0 -demoRoute buyRecipes` under a scheme whose
test action uses `Recipes.storekit` switched to the USA storefront. Retake it when the sheet or
a price changes, and upload it to each pack by hand (`docs/recipe-setup.md`).

## App preview video

An optional app preview per language (en-GB, ru, no, es-ES, de-DE, fr-FR, sv, da; es-MX takes
es-ES's and fr-CA fr-FR's, the other English localizations en-GB's): about 28 seconds of the app
itself, no device frame or hands, in the order of the screenshots' story: a scan reads a pack's
barcode and then its printed date; Add item with the date filled in, saved; Home with "saved to
Fridge" and the new item in the list; the Recipe tab with what expires first ticked; a recipe
written from those items; the Recipe form in Menu set mode, scrolled from the Recipe / Menu set
switch down to Days (4), Courses (3) and Generate menu; the four-day menu it writes, scrolled
from today to the fourth day. The captions are screenshots 2, 1, 3, 4 and 5's, on a Larder green
band over the status bar. No sound: previews autoplay muted, in search results too, so the
first seconds (the scan) carry the story.

Apple's specs ([App preview specifications](https://developer.apple.com/help/app-store-connect/reference/app-preview-specifications),
[App previews](https://developer.apple.com/app-store/app-previews/)): one preview for "iPhone
with Dynamic Island (large display)" (6.9"/6.7") is enough, the smaller iPhones scale it down;
886×1920 portrait; 15 to 30 seconds; at most 30 fps; H.264 High Profile up to level 4.0, about
10–12 Mbps, `.mp4`; stereo AAC 256 kbps at 44.1 or 48 kHz ("All tracks should be enabled", so
the video carries a silent stereo track); at most 500 MB; up to three per size and language,
shown before the screenshots. Only footage of the app, text overlays allowed; purchases the
preview shows must be disclosed (the menu is written by the server, from recipe packs: see
below). The poster frame (shown when autoplay is off) defaults to 5 seconds in.

Making them (Debug build, simulator, nothing sent): `scripts/ios_preview.sh [raw-dir]` creates
its own 6.9" simulator, records the clips in every language with `xcrun simctl io recordVideo`,
driving the app with the demo launch arguments and Maestro taps and swipes, and deletes the
simulator; `scripts/ios_preview_assemble.py [raw-dir] [out-dir]` cuts them into
`<out-dir>/<locale>/preview.mp4`, `poster.png` and `poster_time.txt` with ffmpeg. The defaults
are `build/ios-preview` and `build/app-store-preview`: about 35 MB a video at Apple's 10 Mbps,
so they stay out of git (the screenshot sets, ~15 MB a language, are committed; eight videos
would add ~280 MB with every retake). Run the capture when the machine is quiet: a busy one drops
frames from the recording. Check the result by eye (`ffmpeg -ss <t> -i preview.mp4 -frames:v 1
x.png` at a few points) and with `ffprobe`.

Uploading: `python3 scripts/testflight.py previews` (with the `ASC_*` variables of section 2)
compares each localization of the version in preparation with
`build/app-store-preview/<locale>/preview.mp4` (its fallback's for es-MX, fr-CA and the other
English ones) and prints what differs; `--apply` replaces a preview only when the file's MD5
differs from the one App Store Connect keeps, and sets the poster frame from `poster_time.txt`
(5.5 s in: the scan card with the product and its date). It uses the classic route, `POST
/v1/appPreviewSets` (`previewType` `IPHONE_67`, the iPhone 6.7"/6.9" set) and `POST
/v1/appPreviews` (`fileName`, `fileSize`, `mimeType` `video/mp4`, `previewFrameTimeCode`), the
upload operations, then `PATCH` with `uploaded: true` and `sourceFileChecksum`, the same steps as
`upload_screenshot` (App Store Connect API OpenAPI spec 4.6). It doesn't wait for processing,
which takes up to a day, and writes only while the version is editable. The release flow doesn't
run it, and the videos are local (git-ignored), so it is run by hand from the machine that made
them. `previewFrameTimeCode` is sent as hours:minutes:seconds:frames at 30 fps ("00:00:05:15"),
the form App Store Connect reports for the previews uploaded on the website ("00:00:05:01"; the
spec itself gives no format). Uploads are named `preview-<locale>.mp4`, like those.
Poster frames can't change after approval without a new preview.

The disclosure: the menu set and menu shots show what only the server writes, from recipe packs,
and Apple asks for that to be said in the footage or the end frame. So their caption runs carry
a smaller second line in every language ("Needs a recipe pack", `PACK_NOTES` and `PAID_CAPTIONS`
in `ios_preview_assemble.py`); the screenshots' captions don't, as they describe what the routes
do rather than what they cost. The form's Generate menu button shows its cost in recipes (from
the pack), never a price.

## Changing the listing texts

Edit the files here; the release PR's merge applies them. `scripts/testflight.py appstore`
(run by CI's `release-app-store` job, with `--screenshots`) writes them to the new version
before it submits it for review, and only what differs from App Store Connect:

- **Name, subtitle, privacy policy URL** (`name.txt`, `subtitle.txt`, `privacy_url.txt`) on the
  app info in preparation, and **keywords, description, promotional text** on the version. App
  Store Connect lets the name, subtitle, privacy policy URL, keywords and description change only
  with a new version, so they go live when that version is approved. The privacy policy URL is
  set only once the page answers (below). A localization the repo has and App Store Connect lacks
  is added (en-US was added this way) with its own privacy policy URL, and the support, marketing
  and privacy choices URLs copied from en-GB; those are otherwise never touched. A regional folder without some of the files (en-AU,
  en-CA: en-GB's; es-MX: es-ES's; fr-CA: fr-FR's) takes its parent's for them, so a
  localization added from it has every field; the dry run says per localization which fields
  come from its folder and which are inherited.
- **Screenshots**: `screenshots/`, `medium-screenshots/` and `ipad-screenshots/` replace each
  localization's set of that display type, in file-number order. Unchanged images (same MD5)
  stay, changed or new ones are uploaded, and images no longer in the folder are deleted. At
  most 10 per folder. An English localization without these folders (en-US) shows en-GB's;
  es-MX and fr-CA without their own get es-ES's and fr-FR's uploaded.
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
  files (en-US, en-AU, en-CA) gets en-GB's placed, and es-MX and fr-CA es-ES's and fr-FR's,
  reusing the same library image, as Apple documents no fallback to the primary language for
  these. A localization whose folder has
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
  command (Play's locale codes; `ASC_LOCALES` in `scripts/testflight.py` maps es-419 to es-MX,
  sv-SE to sv, da-DK to da); an English localization without notes of its own gets the en-GB
  ones.

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

### Privacy policy URL per language

The policy site has a page per language, `https://daniil-pastuhov.github.io/bestbefore-privacy/<page>.html`
(en, ru, nb, es, de, fr, sv, da), and the language chooser at its root. App Store Connect keeps the
privacy policy URL per localization (`privacyPolicyUrl` on the app info localization, changed only
with a new version, like the name). `privacy_url.txt` holds it: en-GB `en.html` (en-US, en-AU and
en-CA inherit it), ru `ru.html`, no `nb.html`, es-ES `es.html` (es-MX inherits it), de-DE
`de.html`, fr-FR `fr.html` (fr-CA inherits it), sv `sv.html`, da `da.html`.
`scripts/check_store_listing.py` refuses any other URL (another language's page, another host,
http, the chooser); Apple publishes no length limit for it.

A page is set only when it answers 200 at sync time (`live_privacy_url` in
`scripts/testflight.py`). One that doesn't (not published yet) gets the language chooser instead,
with a `::warning::` in the job's log; the next release sets the page once it is live. If the site
doesn't answer at all, the URL is left as App Store Connect has it. So the dry run
(`testflight.py listing`) shows the chooser for a page that isn't live yet.

**Order of operations**: a language's page has to be live before the release PR whose job syncs
the listing is merged; otherwise that localization keeps the chooser until the release after. All
eight pages are live since 2026-11-15 (site pull request
[daniil-pastuhov/bestbefore-privacy#3](https://github.com/daniil-pastuhov/bestbefore-privacy/pull/3),
which also published the About page `app.html`).

Google Play has one privacy policy URL for the whole app (Play Console > App content), which
the Publishing API doesn't expose; it stays on the chooser root.

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

Everything else (name, subtitle, description, promotional text, privacy policy URL, What's New, screenshots, header
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
| Privacy Policy URL | Per localization, from `<locale>/privacy_url.txt`, set by the release flow ("Privacy policy URL per language"); the language chooser https://daniil-pastuhov.github.io/bestbefore-privacy/ until a language's page is live |
| Support URL | https://daniil-pastuhov.github.io/bestbefore-privacy/support.html |
| Marketing URL | https://daniil-pastuhov.github.io/bestbefore-privacy/app.html (live since 2026-11-15); set by hand (the listing sync doesn't set it) |
| Category | Primary: **Food & Drink**. Secondary: **Productivity** |
| Copyright | `2026 Daniil Pastuhov` |
| Content rights | Does not contain, show or access third-party content. (Product names and pictures come from Open Food Facts, an open database; if asked, answer yes and that you have the rights under its ODbL/CC BY-SA licences.) |
| Price | Free, with in-app purchases. Availability: all countries, or pick |
| In-app purchases | Three consumables, `recipes_10`, `recipes_50`, `recipes_100` (10, 50 and 100 recipes), USD 2.99 / 10.99 / 18.99 as the base price (United States the base territory; `recipes_10` was 3.99 until 2026-11-15), the other storefronts converted by Apple. The prices are `scripts/pack_prices.py`'s, set by `scripts/testflight.py iap-prices` (README.md, "Changing a pack's price"). The first ones have to be submitted for review together with an app version; the release job adds them to the version's review submission ("Changing the listing texts"), and says how to add them by hand if App Store Connect refuses. Each needs a display name, description and review screenshot (the "Buy recipes" sheet, `iap-review/buy-recipes-sheet.png`). Setup: `docs/recipe-setup.md` |
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
App Privacy can't be set through the API; the click-by-click guide, with the reasons and the
judgement calls, is [docs/app-privacy-apple.md](../../docs/app-privacy-apple.md). Play's form is
the file `store-listing/google-play/data-safety.csv`, sent by `scripts/play_data_safety.py`
([../google-play/README.md](../google-play/README.md), "Data safety").

- **Data collection: "Yes, we collect data from this app."**
- From everyone, release builds only: crash reports to Firebase Crashlytics. Each **not linked
  to the user**, **not used for tracking**, purpose **App Functionality**:
  - **Diagnostics > Crash Data**: the crash report itself.
  - **Diagnostics > Other Diagnostic Data**: device model, OS version, free disk and memory at
    the time of the crash, and the Firebase SDKs' user agent; purposes App Functionality and
    **Analytics**, as the FirebaseAuth and FirebaseInstallations manifests declare it.
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
    recipe may use), and the names of the person's staples (Settings > Staples), sent with every
    recipe or menu from a pack,
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
- Google Play's Data safety form needs the matching answers; they are in
  `store-listing/google-play/data-safety.csv` (`scripts/play_data_safety.py` prints them).
  Everything is collected, nothing shared: Google (Firebase, Vertex AI, Play) processes it on our
  behalf as a service provider, which Play's form does not count as sharing ("Transferring user
  data to a 'service provider' that processes it on behalf of the developer",
  <https://support.google.com/googleplay/android-developer/answer/10787469>). Apple is not
  involved on Android. All of it is encrypted in transit (HTTPS). None is processed ephemerally:
  the server keeps the recipe for 7 days, and Google's model caches inputs for up to 24 hours
  and may log prompts for abuse monitoring
  (<https://docs.cloud.google.com/vertex-ai/generative-ai/docs/data-governance>), which is not
  "only stored in memory".
  - **Crash logs**, **Diagnostics**, **Device or other IDs** (the Firebase installation ID and
    Crashlytics UUID, ML Kit's per-installation IDs, the Play Integrity token App Check sends):
    required (every release build, no opt-out), **Analytics**, which Play defines as "to monitor
    app health, to diagnose and fix bugs or crashes" (not App functionality, as an earlier version
    of this list said); Device or other IDs also **Fraud prevention, security, and compliance**
    (App Check). ML Kit (barcode, text, image labels, Gemini Nano) runs on the device but sends
    Google diagnostics with per-installation IDs (<https://developers.google.com/ml-kit/android-data-disclosure>),
    covered by the same two types.
  - **Purchase history**, **User IDs**: optional (only for buyers), **App functionality**,
    **Account management**, **Fraud prevention, security, and compliance** (the replay guard and
    the refund-after-use record, under the user ID and a hash of the sign-in provider's user ID).
  - **Email address**: optional (only when signed in), **App functionality**, **Account
    management**. **Name**: optional (only with Google sign-in, kept in Firebase's Google
    provider entry although the server clears the top-level name), **Account management** only:
    nothing in the app uses it.
  - **Other user-generated content** (the ticked items' names, dates and quantities, every pantry
    item's with "Send all products" on, and the names of the person's staples (Settings > Staples),
    sent with every recipe or menu from a pack, the recipe settings, the recipe returned): optional,
    **App functionality**.
  - **Health and fitness > Health info** (the allergen list, "Information about a user's health,
    such as medical records or symptoms"): optional (only with allergens set, and only after the
    consent step), **App functionality**; processed ephemerally: **No** (an earlier version said
    Yes; our server keeps it only inside the request hash, but Google's model cache above keeps
    it up to 24 hours).
  - Not declared: Location (shop reminders are on-device geofences; nothing is sent), Photos (on
    the device; image labelling is on-device ML Kit), App interactions and other usage (no
    analytics SDK), In-app review (the Play Store's own flow under Play's terms), and the
    barcode sent to Open Food Facts (a product code, without user or device ID; judgement call
    in `docs/recipe-setup.md` step 11).
  - Data deletion: users can delete their account in the app (Recipe tab > Recipe settings >
    Account > Delete account), and someone who bought without signing in deletes the anonymous
    account the same way (Recipe settings > Your recipes > Delete my data from our server). The
    Auth user (email, name; or the anonymous user), the server's recipes, the usage counters and
    the stored Sign in with Apple token (revoked at Apple first) are deleted; purchase records
    (with the old user ID, not linked to a name or email), a closed account record with totals,
    and any refund-after-use record stay. Both deletion URLs (account, and data) are
    https://daniil-pastuhov.github.io/bestbefore-privacy/delete.html, which also takes requests
    without the app.
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
redraws the images from the framed screenshots (`screenshots/` 2, 3 and 5: the scan, a recipe,
the menu) after those change. Apple's guidance on event media (In-App Events page, "Creating
effective metadata", checked 2026-11-15) is a recommendation, not an App Review rule: "When
possible, avoid using text or logos in your media, especially if they include your event name or
app name.", with "Avoid using text or logos in your media." under Don't, and "Don't add borders
or gradients to your media." It says nothing about screenshots, device frames or UI, so app
screens in phones are not ruled out; the risk is low and lies in words that look like an overlay
or repeat the event or app name. The images add no text of their own and show neither the app
name nor an icon; the only words are the app's own screens. One to watch: the ru event name is
«Новая вкладка «Рецепт»», and the recipe phone's title bar reads «Рецепт»; check the ru preview.
App Store Connect sets the event name and description over the bottom, which the layouts leave
to the phones' lower halves and plain green.

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
