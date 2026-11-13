# Recipe packs: owner setup checklist

Everything outside the code that recipe packs need before they work for real users, in the order
to do it. It merges the one-time setup in [functions/README.md](../functions/README.md) with the
app-side steps. The Firebase project is `bestbefore-f9ee1`, the region `europe-west1`, the Android
package `com.foodtracker.android` and the iOS bundle `com.foodbest.ios`.

Until these are done the free routes (shared prompt, the phone's own model) work as normal, and
the paid route either stays hidden (Android builds not installed from Play, iOS devices without
App Attest) or fails without charging anyone.

Items marked **Check** are ones the code can't confirm; verify them in the console or the
provider's documentation when you get there.

Items ticked with a date were done (and read back) through the Google Cloud, Firebase, App Store
Connect and Play Developer APIs on 2026-11-13. The ones still open after that date need the owner in a
console; "Still by hand" below lists them.

## 1. Google Cloud and Firebase basics

- [x] **Blaze plan** on `bestbefore-f9ee1` (Cloud Functions 2nd gen, Secret Manager and Vertex
      AI need it). Already in place (2026-11-13): billing account `012EF0-C3552F-30C63B` (NOK) is linked.
- [x] **Budget** (Billing > Budgets & alerts). Create the Pub/Sub topic `budget-alerts` first, then
      in the budget choose "Connect a Pub/Sub topic" and pick it. Without the topic the alert only
      emails you; the topic is what lets `budgetKillSwitch` turn generation off.
      Done 2026-11-13: topic `budget-alerts` created, and a second budget "Recipe kill switch
      bestbefore-f9ee1" (1000 NOK a month, about 100 USD, this project only, 50/90/100 % of actual
      spend) connected to it; Billing granted `billing-budget-alert@system.gserviceaccount.com`
      Publisher on the topic. The older budget "Firebase Project bestbefore-f9ee1" (same amount and
      thresholds, no topic) still exists and only emails; delete one of them if the duplicate
      emails bother you. Budgets publish to the topic several times a day whatever the
      thresholds; `budgetKillSwitch` ignores the 50 % and 90 % messages and flips only when
      `costAmount >= budgetAmount * budgetKillFraction` (default 1, so at 1000 NOK).
- [x] **APIs**: Vertex AI API, Google Play Android Developer API, Cloud Pub/Sub, Cloud Scheduler,
      Secret Manager, Cloud Build, Artifact Registry, Eventarc, Cloud Run, and for App Check the
      Play Integrity API. Done 2026-11-13, together with Cloud Functions, Firebase App Check, Cloud
      Firestore, IAM, Cloud Billing Budget and API Keys.
- [x] **Vertex AI model**: the default `GEMINI_MODEL` is `gemini-3.8-flash` in location `global`.
      Checked 2026-11-13: GA in the publisher catalogue, and `countTokens` on
      `projects/bestbefore-f9ee1/locations/global/publishers/google/models/gemini-3.8-flash`
      answers 200, so the default works.
- [ ] **Firestore**: create the `(default)` database in Native mode in `europe-west1` (the owner's
      choice; the location is permanent). None existed on 2026-11-13. Then deploy at least the rules
      straight away (`cd functions && npx firebase deploy --only firestore:rules --project
      bestbefore-f9ee1`) so the new database is never left open to clients; the sweeper index and
      the TTL policy on `generations.expiresAt` are deployed in step 10.
- [ ] **`config/runtime`** document (Firestore > `config` > `runtime`). Every field is optional;
      the table in functions/README.md lists them. Leave `allowSandbox` off except while testing
      or while a version is in App Review (section 8).

## 2. Firebase Authentication

Authentication had never been initialised on this project; on 2026-11-13 it was initialised through
the API (`identityPlatform:initializeAuth`), which makes it Firebase Authentication with Identity
Platform (free tier 50,000 monthly active users for these providers).

- [x] Enable the **Anonymous** provider. Done 2026-11-13.
- [ ] Enable **Google**. This creates the OAuth web client the Android app needs. **Console
      only**: the provider needs an OAuth web client id and secret, and the one public API that
      created OAuth clients (IAP OAuth Admin) was shut down on 2026-03-19. Firebase console >
      Authentication > Sign-in method > Add new provider > Google > Enable, pick a support email,
      Save; the console creates "Web client (auto created by Google Service)".
- [ ] Enable **Apple**. Partly done 2026-11-13: enabled with client id and bundle id `com.foodbest.ios`,
      which is all iOS's native sign-in needs. For Android, which signs
      in to Apple through Firebase's web flow (`OAuthProvider("apple.com")`), the provider also
      needs the **OAuth code flow** settings: an Apple **Services ID**, your **Team ID**, and a
      **Sign in with Apple key** (key id and `.p8`). On Apple's side (Certificates, Identifiers &
      Profiles):
  - [x] the App ID `com.foodbest.ios` has the Sign in with Apple capability (the app's
        entitlements already ask for it). Done 2026-11-13 through the App Store Connect API
        (capability type `APPLE_ID_AUTH`, primary App ID consent);
  - [ ] a Services ID (**not possible by API**: App Store Connect API 4.6 `bundleIds` only take
        the platforms IOS, MAC_OS and UNIVERSAL; Identifiers > + > Services IDs) (any identifier, for example `com.foodbest.ios.signin`) with Sign in with
        Apple enabled, primary App ID `com.foodbest.ios`, domain `bestbefore-f9ee1.firebaseapp.com`
        and return URL `https://bestbefore-f9ee1.firebaseapp.com/__/auth/handler` (**Check**: copy
        the exact URL the Firebase console shows on the Apple provider page);
  - [ ] a key with Sign in with Apple enabled (**not possible by API**: the App Store Connect API
        has no endpoint for developer keys; Keys > +). Then in the Firebase console's Apple
        provider set the Services ID as the client id and fill in the OAuth code flow fields. The same key can serve as `SIWA_PRIVATE_KEY` for
        the backend (section 9), or make a second one.
  - Android's account deletion revokes the Apple token on the phone
    (`FirebaseAuth.revokeAccessToken`), which **needs the OAuth code flow settings above**; iOS
    sends the authorization code to the server, which revokes it with `SIWA_PRIVATE_KEY`.
- [x] Leave Identity Platform's **automatic clean-up of anonymous users off** (checked 2026-11-13: the
      config has no `autodeleteAnonymousUsers`, which means off). It would delete
      anonymous buyers together with the recipes they paid for.
- [ ] Optional: set the authorised domains and the email templates. Nothing in the app uses email
      sign-in.

## 3. Android app in Firebase

- [x] Done 2026-11-13: SHA-1 and SHA-256 of the debug key (alias `debug`, not `androiddebugkey`), the
      upload key, and the Play app signing key. Play's `generatedApks` API gives the signing key's
      SHA-256 (`22:FA:25:…:CB:76`; its SHA-1 `5B:F6:9A:…:AF:46` was already on the API key); the
      Play-built APK also carries a rotated v3.2 "hybrid" signer for Android 17+ (SDK 37), whose
      classical SHA-1 `15:7D:08:…:59:D0` and SHA-256 `26:1D:B3:…:C5:89` were added too.
      Project settings > Your apps > `com.foodtracker.android`: add the **SHA-1 and SHA-256**
      fingerprints of every key that signs a build you want to sign in from or attest:
  - the **Play app signing key** (Play Console > Test and release > App integrity > App signing);
    this is the one installed users get;
  - the **upload key** (the `keystore.properties` keystore) if you install release builds locally;
  - the **debug keystore** (`keytool -list -v -keystore ~/.android/debug.keystore -alias debug
    -storepass android`) for debug builds with Google sign-in.
  Google sign-in through Credential Manager needs the SHA-1s; Play Integrity for App Check needs
  the SHA-256 of the app signing key.
- [ ] **Re-download `google-services.json`** after Google sign-in is enabled and the fingerprints
      are added, and commit it to `androidApp/`. The committed one has an empty `oauth_client`, so
      the app has no `default_web_client_id` and **hides "Sign in with Google"** until this is
      done (`FirebaseRecipeAccount.providers`).
- [x] **App Check**: register the Android app with **Play Integrity**. Done 2026-11-13 (App Check API,
      token TTL 1 h, no enforcement on any service). **Check**: Play Integrity may also need the
      Cloud project linked in Play Console (App integrity > Play Integrity API); no API does that.
      Do not turn on console enforcement for any product; the callables enforce App Check
      themselves.
- [ ] Debug builds: build with `-PrecipePaidRoute=true` to use the real backend. The App Check
      debug provider prints a debug token to logcat on first use; register it in the console
      (App Check > Apps > Manage debug tokens). Never commit it or paste it into CI logs.

## 4. iOS app in Firebase

- [x] Project settings > Your apps > `com.foodbest.ios`: make sure the **Team ID** is set (App
      Attest needs it). It was empty; set to `S8Q8KHX6G5` on 2026-11-13 (the App ID's seed id in App
      Store Connect and the Xcode project's `DEVELOPMENT_TEAM`).
- [ ] **Re-download `GoogleService-Info.plist`** after Google sign-in is enabled, and commit it to
      `iosApp/FoodTracker/`. The committed one has no `CLIENT_ID` / `REVERSED_CLIENT_ID`.
- [ ] Add the plist's **`REVERSED_CLIENT_ID`** as a URL scheme: `iosApp/FoodTracker/Info.plist` >
      `CFBundleURLTypes`, a second entry next to the existing `foodtracker` scheme. The app shows
      "Sign in with Google" only when the client id is present **and** its reversed form is a
      registered scheme (`GoogleSignInSupport.isConfigured`).
- [x] **App Check**: register the iOS app with **App Attest** (done 2026-11-13, no enforcement) (DeviceCheck as a fallback only if
      wanted; the app's provider factory uses App Attest in release builds and the debug provider
      in Debug). The App ID needs the App Attest capability; the entitlement is already set to
      `production`. **Not possible by API**: `APP_ATTEST` is not a capability type in the App Store
      Connect API. Automatic signing adds it when Xcode next signs with
      `-allowProvisioningUpdates`; otherwise tick App Attest on the App ID in Certificates,
      Identifiers & Profiles.
- [ ] Debug builds: the App Check debug provider logs a debug token in the Xcode console on first
      use; register it in the Firebase console. Not in the repo, not in CI logs.

## 5. Google Play

- [ ] Payments profile and merchant account (Play Console > Monetisation setup), if not already
      there; paid products need it.
- [ ] **Products** (Monetise with Play > Products > In-app products): consumables `recipes_10`,
      `recipes_50`, `recipes_100`, priced USD 3.99 / 10.99 / 18.99 (decided 2026-11-13), with
      Play converting the other countries from the US price. The server only reads the product id
      and the signed quantity. Multi-quantity purchases are allowed if you want them (the server
      multiplies by the quantity).
- [ ] **Service account access**: Users and permissions > invite
      `recipe-functions@bestbefore-f9ee1.iam.gserviceaccount.com` (created in section 9; you can
      create it first) with "View financial data" and "Manage orders and subscriptions" for this
      app. The Play Developer API can do this (`developers/{id}/users` then `.../grants` with
      `CAN_VIEW_FINANCIAL_DATA` and `CAN_MANAGE_ORDERS`), but it needs the numeric developer
      account id (the number after `/developers/` in the Play Console URL), which no API returns.
      It can take up to a day to start working; until then `verifyPurchase` answers `RETRY`
      and the app keeps the purchase to resubmit.
- [ ] **Real-time developer notifications** (Monetisation setup): topic
      `projects/bestbefore-f9ee1/topics/play-rtdn` (create the topic first), with notifications
      for **one-time products / voided purchases** turned on. Grant
      `google-play-developer-notifications@system.gserviceaccount.com` the Pub/Sub Publisher role
      on that topic. Use "Send test notification" to check. Topic and grant done 2026-11-13; setting the
      topic in Play Console is **console only** (no Play Developer API field for it).
- [ ] **Licence testers** (Settings > Licence testing) for your own test accounts (console only;
      the API's `testers` resource is per testing track, not licence testing). Their purchases
      grant recipes only while `allowSandbox` is on.
- [ ] The app must be installed from Play (any testing track) for the paid route to show in a
      release build.

## 6. App Store Connect

- [ ] Sign the **Paid Apps agreement** and fill in banking and tax forms (Business).
- [ ] **In-app purchases**: three **consumables** with product ids `recipes_10`, `recipes_50`,
      `recipes_100`, each with a price (USD 3.99 / 10.99 / 18.99 as the base price, the App Store
      converting the other storefronts), a display name and description in en-GB, ru and no, and
      a review screenshot. The first ones are submitted together with an app version.
- [ ] **In-App Purchase key** (Users and Access > Integrations > In-App Purchase): generate a key
      and note its key id and the issuer id. The `.p8` becomes the secret `APPSTORE_PRIVATE_KEY`.
- [x] Note the app's **Apple ID** (App Information), for the parameter `APPLE_APP_ID`:
      `6818647200` (read 2026-11-13).
- [ ] **App Store Server Notifications** (App Information): Version 2, the same URL for
      production and sandbox: `https://europe-west1-bestbefore-f9ee1.cloudfunctions.net/appStoreNotifications`.
      Do this after the first deploy and use the URL `firebase deploy` prints if it differs. Then
      "Request a Test Notification".
- [ ] **Sandbox testers** (Users and Access > Sandbox) for your own testing (console only; the API
      can list and edit sandbox testers but not create them). Sandbox and TestFlight
      purchases grant recipes only while `allowSandbox` is on; Xcode StoreKit-file purchases never
      do.

## 7. Service account and IAM

- [x] Done 2026-11-13, with Secret Manager Secret Accessor granted at project level for now because the
      two secrets don't exist yet; move it onto the secrets once they are set.
      Create `recipe-functions@bestbefore-f9ee1.iam.gserviceaccount.com` with only: Cloud
      Datastore User, Vertex AI User, Firebase Authentication Admin, Secret Manager Secret
      Accessor (on the two secrets), Logs Writer, Cloud Run Invoker and Eventarc Event Receiver.
      Add Firebase App Check Token Verifier only if `CONSUME_APP_CHECK_TOKENS` is ever turned on.
- [x] Let the account you deploy with act as it (Service Account User on that service account).
      Done 2026-11-13 for `daniil.pastuhov@gmail.com`.

## 8. App Review and testing switches

- [ ] Before submitting a version that sells packs, set `allowSandbox` to `true` in
      `config/runtime` and make sure `sandboxDailyCap` (default 20) leaves room for the reviewer.
      Turn it off again after approval; while on, anyone with a TestFlight or sandbox account can
      get free recipes up to that cap.
- [ ] The review notes in `store-listing/app-store/README.md` (section 6) describe the sandbox
      purchase path for the reviewer.

## 9. Secrets and parameters

- [ ] Secrets (Secret Manager, never in the repo). The `firebase` CLI is a dev dependency of
      `functions/`, so run it from there (it finds `../firebase.json`):
      ```bash
      cd functions
      npx firebase functions:secrets:set APPSTORE_PRIVATE_KEY < AuthKey_XXXXXXXXXX.p8   # In-App Purchase key
      npx firebase functions:secrets:set SIWA_PRIVATE_KEY < AuthKey_YYYYYYYYYY.p8       # Sign in with Apple key
      ```
- [ ] Parameters: `firebase deploy` asks for them the first time and saves them to
      `functions/.env.bestbefore-f9ee1` (no secrets in it; it may be committed and a later CI
      deploy would need it). Required: `APPLE_APP_ID`, `APPSTORE_KEY_ID`, `APPSTORE_ISSUER_ID`,
      `APPLE_TEAM_ID`, `SIWA_KEY_ID`. Defaults you may change: `FUNCTIONS_SERVICE_ACCOUNT`,
      `GEMINI_MODEL`, `GEMINI_THINKING_LEVEL`, `VERTEX_LOCATION`, `PLAY_RTDN_TOPIC`,
      `BUDGET_ALERT_TOPIC`.

## 10. Deploy

- [ ] `cd functions && npx firebase deploy --only functions,firestore --project bestbefore-f9ee1`
      (lint and build run first as predeploy steps). This deploys the functions, `firestore.rules`
      (deny all clients), the sweeper index and the TTL policy.
- [ ] Note the `appStoreNotifications` URL it prints and finish section 6.
- [ ] Smoke test with a licence tester and a sandbox account (with `allowSandbox` on): buy a pack,
      generate a recipe, see the balance drop by one; sign in on the other platform and see the
      same balance. Then turn `allowSandbox` off.
- Nothing deploys the backend from CI yet; functions/README.md, "Deploying from CI", lists what a
  deploy job would need.

## 11. Privacy and store forms

- [ ] Update the hosted privacy policy (outside this repo) from `docs/privacy-policy-changes.md`,
      in en, ru and nb.
- [ ] App Store Connect > App Privacy, and Play Console > Data safety: the answers are in
      `store-listing/app-store/README.md`, section 5. Decide the open question there about
      allergens and health data first.
- [ ] Store descriptions and promotional text in `store-listing/` already describe recipe packs;
      upload them with the release. Retake the screenshots (the Calendar shot is now a Recipe
      shot; see `scripts/ios_screenshots.sh` and `scripts/android_screenshots.sh`).
- [ ] Play Console > App content: answer the in-app purchases and (if asked) account deletion
      questions; the deletion path is Recipe tab > Recipe settings > Account > Delete account. A
      web page for deletion requests may also be required by Play for apps with accounts
      (**Check** the current Data safety "Data deletion" requirements).

## 12. Sign-in button artwork

- [ ] iOS: the purchase choice uses Apple's `SignInWithAppleButton` and Google's
      `GoogleSignInButton`, which follow the brands' rules. "Add Google sign-in" in Recipe
      settings is an app-styled outline button with a generic person icon; check it against
      Google's branding guidelines or switch it to Google's button.
- [ ] Android: both "Sign in with Apple" and "Sign in with Google" are app-styled outline buttons
      (`RecipeSignInButtons` in `RecipePaidViews.kt`; Apple's logo, and a generic person icon for
      Google). Replace them with the official artwork (Google's "Sign in with Google" button assets
      and Apple's Sign in with Apple button guidelines) before release.

## Still by hand (after 2026-11-13)

What no API could do, or what was not allowed in that session, roughly in order:

1. Firebase console > Authentication > Sign-in method > Google: enable it (creates the OAuth web
   client). Then download both config files again (Project settings > Your apps) and commit them,
   and add the iOS `REVERSED_CLIENT_ID` URL scheme (section 4).
2. Firestore: create `(default)` in Native mode, `europe-west1`, then deploy the rules at once
   (section 1).
3. Certificates, Identifiers & Profiles: a Services ID and a Sign in with Apple key, then the
   Apple provider's OAuth code flow fields in the Firebase console (section 2); tick App Attest on
   the App ID if automatic signing hasn't (section 4).
4. Play Console: invite `recipe-functions@` (or give the developer account id so it can be done
   by API), set the RTDN topic, link the Cloud project for Play Integrity, add licence testers
   (sections 3 and 5).
5. App Store Connect: Paid Apps agreement, In-App Purchase key, sandbox testers (section 6).
   The in-app purchases (prices now decided: USD 3.99 / 10.99 / 18.99) and the notification URL
   can be set by API once the backend is deployed.
