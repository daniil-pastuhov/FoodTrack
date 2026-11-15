# App Store Connect: App Privacy, click by click

The answers for the App Store's privacy label ("App Privacy" in App Store Connect) for the version
that sells recipe packs, checked against the code on `feat/recipe` (November 2026). Follow them in
App Store Connect; nothing in this repo can set them (below). The reasons and the Play equivalent
are in [store-listing/app-store/README.md](../store-listing/app-store/README.md), section 5; if
the two ever disagree, this file is the newer one.

## Why by hand

The public App Store Connect API (key-based, which is all this repo has) cannot read or write App
Privacy. Checked 2026-11-15:

- The OpenAPI spec 4.6 (<https://developer.apple.com/sample-code/app-store-connect/app-store-connect-openapi-specification.zip>,
  4 Nov 2026) has no data-usage or privacy-label resource; "privacy" appears only as the
  `privacyPolicyUrl` / `privacyChoicesUrl` / `privacyPolicyText` attributes. None of the release
  notes 1.0 to 4.6 adds one; the 2.0 notes removed the IDFA declaration APIs and point to the web
  form (<https://developer.apple.com/documentation/appstoreconnectapi/app-store-connect-api-2-0-release-notes>).
- fastlane's `upload_app_privacy_details_to_app_store` logs in with an Apple Account
  (`Spaceship::ConnectAPI.login(params[:username], ..., use_tunes: true)`, no API-key option) and
  calls `appDataUsages` on `https://appstoreconnect.apple.com/iris/`, App Store Connect's private
  web-session API (<https://docs.fastlane.tools/actions/upload_app_privacy_details_to_app_store/>).
  The same paths on `api.appstoreconnect.apple.com` answer 404 "The resource 'v1/appDataUsages'
  does not exist". We don't use an Apple Account session from scripts, so `scripts/testflight.py`
  has no command for this.

## Before you start

- Who: Account Holder, Admin or App Manager.
- The answers are per app, not per version, and go live when you click Publish ("You may update
  your answers at any time, and you do not need to submit an app update",
  <https://developer.apple.com/app-store/app-privacy-details/>). So publish them **when the
  version that sells packs is released**, not long before: until then the live app collects only
  crash reports. If it has to happen earlier (App Review wants the label to match the build under
  review), publishing early over-declares for a few days, which is the safe direction.
- Optional check: Xcode > Product > Archive, then in the Organizer Control-click the archive >
  Generate Privacy Report. It merges `iosApp/FoodTracker/PrivacyInfo.xcprivacy` with the Firebase
  SDKs' own manifests. Every type in it should be in the answers below (the answers have more,
  because the Firebase SDKs don't declare everything; see "Checklist").

## The rules applied

From <https://developer.apple.com/app-store/app-privacy-details/>:

- **Collect**: "transmitting data off the device and storing it in a readable form for longer than
  the time it takes you and/or your third-party partners to service the request." Data "processed
  only on device is not 'collected'". Third-party partners include the SDKs in the app (Firebase)
  and, for our server's requests, Google's model on Vertex AI.
- **Optional disclosure** needs *all four* conditions, among them "Collection of the data occurs
  only in infrequent cases that are not part of your app's primary functionality, and which are
  optional for the user" and "the user's name or account name is prominently displayed in the
  submission form ... and the user affirmatively chooses to provide the data for collection each
  time". The recipe pack route fails the last one (the recipe request shows no account name, and
  consent to send allergens is given once, not per request), and crash reports fail the third and
  fourth (automatic, every release build). **So nothing below may be left out as optional.**
- **Linked**: "Data collected from an app is often linked to the user's identity, unless specific
  privacy protections are put in place before collection to de-identify or anonymize it". Everything
  the recipe server keeps is under the Firebase Auth user ID, so it is linked. Crash reports carry
  no user ID (the app never calls `setUserID`, custom keys or logs on Crashlytics).
- **Tracking**: linking our data with other companies' data for advertising, or sharing it with a
  data broker. The app does neither; it has no ads and no analytics SDK. Every answer is **No**.

## Step by step

App Store Connect > Apps > BestBefore > App Privacy (sidebar). The first time: **Get Started**.
If answers already exist: **Edit** next to Data Types. (Help page:
<https://developer.apple.com/help/app-store-connect/manage-app-information/manage-app-privacy/>.)

### 1. "Do you or your third-party partners collect data from this app?"

**Yes, we collect data from this app** > **Next**.
Why: crash reports go to Firebase Crashlytics from every release build, and the recipe pack route
sends and keeps data on our server.

### 2. Data types: tick these nine, then **Save**

| Category | Tick | From whom |
|---|---|---|
| Contact Info | **Name** | buyers who sign in with Google |
| Contact Info | **Email Address** | buyers who sign in (Apple or Google) |
| Health & Fitness | **Health** | buyers who set allergens and consent to sending them |
| Purchases | **Purchase History** | buyers |
| User Content | **Other User Content** | buyers |
| Identifiers | **User ID** | buyers, signed in or not |
| Identifiers | **Device ID** | everyone (crash reports) |
| Diagnostics | **Crash Data** | everyone (crash reports) |
| Diagnostics | **Other Diagnostic Data** | everyone (crash reports, Firebase SDK metadata) |

Leave everything else unticked; see "Not collected" below for why.

### 3. For each data type: purposes, linked, tracking

Click each type under Data Types. Apple asks three things in this order (WWDC22 "Create your
Privacy Nutrition Label", <https://developer.apple.com/wwdc22/10167/>): how the data is used
(tick all that apply), whether it is linked to the user's identity, and whether it is used for
tracking. The exact wording on screen is roughly "Indicate how [type] collected from this app is
being used by you or your third-party partners", "Is [type] collected from this app linked to the
user's identity?" and "Do you or your third-party partners use [type] for tracking purposes?"
(not quoted from an Apple page: the help page only says "answer the questions that follow").
Click **Save** after each.

| Data type | Purposes | Linked to the user's identity? | Used for tracking? | Why |
|---|---|---|---|---|
| Name | App Functionality | **Yes** | No | Google passes the account's name to Firebase Authentication at sign-in. The server clears the top-level `displayName`/`photoURL` (`functions/src/core/account.ts`), but Firebase keeps a copy in the Google provider entry until the account is deleted and the Admin SDK cannot remove it (`functions/README.md`, "Profile data"). Apple sign-in asks for email only (`requestedScopes = [.email]`, `Recipe/RecipeAccount.swift`). |
| Email Address | App Functionality | **Yes** | No | Firebase Authentication keeps the sign-in's email (Apple's relay address included); it is the account the recipes are kept under and how a deletion request by email is found. |
| Health | App Functionality | **Yes** | No | The allergen list, sent with a server recipe only after the consent step (`Recipe/RecipeWire.swift` `AllergenConsent`; the server refuses allergens without `allergenConsent: true`), so the recipe avoids them. Our server does not store it (only a hash of the whole request), but it goes to Google's model, which caches inputs for up to 24 hours by default and may log prompts for abuse monitoring (<https://docs.cloud.google.com/vertex-ai/generative-ai/docs/data-governance>), so it is kept by a partner longer than the request: collected. Apple's Health includes "any other user provided health or medical data". Sent with the user's request under their account: linked. |
| Purchase History | App Functionality | **Yes** | No | Each pack bought (`purchases/{id}`, verified with the App Store, kept as the replay guard, also after account deletion), the refund-after-use record (`buyers/{key}`), and, for purchases made after the Buy sheet showed the consumption line, the share used, sent to Apple when the buyer asks Apple for a refund. Preventing a purchase from being redeemed twice and refund abuse are fraud prevention, which Apple counts under App Functionality. |
| Other User Content | App Functionality | **Yes** | No | The ticked items' names, dates and quantities (every pantry item's with "Send all products" on), the recipe settings, and the recipe or menu written back, which the server keeps for 7 days for retries (`users/{uid}/generations`, `functions/README.md` "Firestore"). |
| User ID | App Functionality | **Yes** | No | The Firebase Auth user ID (anonymous or signed in) that holds the recipe balance; with each purchase, Apple's `appTransactionId` and the `appAccountToken` made from the user ID. |
| Device ID | App Functionality | **No** | No | Crashlytics groups crash reports by a per-installation ID (Firebase installation ID / Crashlytics install UUID). Judgement call, see below. |
| Crash Data | App Functionality | **No** | No | The crash report: stack trace and app state. Apple's App Functionality includes "minimize app crashes". No user ID is attached. |
| Other Diagnostic Data | App Functionality, **Analytics** | **No** | No | Device model, OS version, free disk and memory at the crash (Crashlytics); the Firebase SDKs' user agent (device, OS, bundle ID, SDK versions), which Google uses for "platform and version adoption". Firebase's own manifests declare this type with purpose Analytics for FirebaseAuth and FirebaseInstallations, so the label should too. Judgement call, see below. |

### 4. Check and publish

Look at **Product Page Preview** (See Details), then **Publish** (top right) and confirm. If the
product page isn't live yet, the answers appear when it goes live.

### 5. Privacy Policy URL

Same page: next to **Privacy Policy**, **Edit**, enter
`https://daniil-pastuhov.github.io/bestbefore-privacy/` and **Save**. (User Privacy Choices URL,
optional: `https://daniil-pastuhov.github.io/bestbefore-privacy/delete.html`.) Unlike the answers,
URL changes are released "with your next app version", and only while a version is editable.

## Not collected, and why

| Tempting type | Answer | Why |
|---|---|---|
| Location (Precise, Coarse) | not collected | Shop reminders use on-device region monitoring (`Location/StoreGeofenceManager.swift`); the shops' positions stay in the app's local store and are never sent. Our server does not use the IP address for location (see judgement calls). |
| Photos or Videos | not collected | Product photos and scans stay on the phone; text recognition (Apple Vision) runs on the device. Open Food Facts product images are downloaded, never uploaded. |
| Name from Apple | not asked | Sign in with Apple requests `.email` only. (Name is still ticked, for Google sign-ins.) |
| Payment Info | not collected | Apple takes the payment; the app and server see only the signed transaction. |
| Product Interaction, Other Usage Data, Advertising Data | not collected | No analytics or ads SDK; Crashlytics breadcrumbs need Google Analytics, which the app doesn't link. |
| Performance Data | not collected | No Firebase Performance Monitoring; Crashlytics reports crashes, not launch time or hangs. |
| Customer Support | not collected | Support is by GitHub issues or email, outside the app. |
| Sensitive Info, Other Data | not collected | Allergens are declared as Health (Apple's Sensitive Info lists other categories). |
| Barcode lookups | not collected by us | The barcode number goes to Open Food Facts with no user or device ID, and we receive nothing back about the person; the privacy policy says so. |
| Shared prompt, phone's own model | not collected | The prompt goes only to the app the user picks; Apple Foundation Models run on the device. |

## What the label will show

| Data Used to Track You | Data Linked to You | Data Not Linked to You |
|---|---|---|
| none | Purchases (Purchase History); Contact Info (Name, Email Address); Health & Fitness (Health); User Content (Other User Content); Identifiers (User ID) | Identifiers (Device ID); Diagnostics (Crash Data, Other Diagnostic Data) |

## Judgement calls for the owner

1. **Device ID** for the Crashlytics installation ID. Apple's Device ID is "the device's
   advertising identifier, or other device-level ID"; a per-installation ID is arguably not
   device-level, and Firebase's own manifests don't declare it. Ticking it over-declares
   slightly; leaving it out is defensible. Recommended: keep it.
2. **Analytics** on Other Diagnostic Data. True of the Firebase SDKs (their manifests say so),
   not of anything we do with the data. Leaving only App Functionality would contradict the Xcode
   privacy report. Recommended: tick it.
3. **Crash data "not linked"**. The installation ID is pseudonymous, and Apple says personal data
   under privacy laws counts as linked. We follow Firebase's own declaration (not linked) because
   we never join crash reports with an account. If you prefer the stricter reading, set Crash
   Data, Other Diagnostic Data and Device ID to linked.
4. **Health**. Our server does not keep the allergen list, so without Google's cache it would
   arguably not be "collected". With the default Vertex AI caching and abuse logging it is, and
   declaring it is the honest choice either way. Turning caching off for the project
   (data-governance page above) would not remove the abuse-monitoring logging.
5. **IP address.** Our server uses the IP in memory for rate limiting only, but Google Cloud's
   request logs keep it 30 days. Apple has no IP data type, and it is not used for location, so
   nothing is ticked for it; the privacy policy mentions it.

## Checklist against `PrivacyInfo.xcprivacy`

`iosApp/FoodTracker/PrivacyInfo.xcprivacy` covers the app's own collection (the recipe route); the
Firebase SDKs ship their own manifests (Crashlytics: Crash Data, Other Diagnostic Data;
FirebaseAuth: User ID, Other Diagnostic Data; FirebaseInstallations: Other Diagnostic Data), and
Apple says the app's manifest "doesn't need to cover data collected by third-party SDKs"
(<https://developer.apple.com/documentation/bundleresources/describing-data-use-in-privacy-manifests>).

- [x] `NSPrivacyTracking` false, no tracking domains: matches "Tracking: No" everywhere.
- [x] PurchaseHistory, UserID, EmailAddress, Name, OtherUserContent, Health: each linked, not
      tracking, App Functionality. Matches the table above.
- [x] Crash Data, Other Diagnostic Data: in the Firebase manifests, not the app's. Matches.
- [x] Device ID: in neither manifest, but in the answers (judgement call 1). Not a contradiction:
      the label may declare more than the manifests.
- [x] Analytics on Other Diagnostic Data: from the Firebase manifests (judgement call 2).

No disagreement that makes the manifest wrong was found, so it is unchanged.
