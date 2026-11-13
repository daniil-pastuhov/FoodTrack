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

## 1. Google Cloud and Firebase basics

- [ ] **Blaze plan** on `bestbefore-f9ee1` (Cloud Functions 2nd gen, Secret Manager and Vertex
      AI need it).
- [ ] **Budget** (Billing > Budgets & alerts). Create the Pub/Sub topic `budget-alerts` first, then
      in the budget choose "Connect a Pub/Sub topic" and pick it. Without the topic the alert only
      emails you; the topic is what lets `budgetKillSwitch` turn generation off.
- [ ] **APIs**: Vertex AI API, Google Play Android Developer API, Cloud Pub/Sub, Cloud Scheduler,
      Secret Manager, Cloud Build, Artifact Registry, Eventarc, Cloud Run. For App Check also the
      Play Integrity API (**Check**: the Firebase console may enable it for you when you register
      the provider).
- [ ] **Vertex AI model**: the default `GEMINI_MODEL` is `gemini-3.8-flash` in location `global`.
      **Check** it is offered to this project in that location before deploying; otherwise set
      another Flash-tier model id (step 9).
- [ ] **Firestore**: create the `(default)` database in Native mode in a European location (for
      example `eur3`). The rules, the sweeper index and the TTL policy on
      `generations.expiresAt` are deployed in step 10.
- [ ] **`config/runtime`** document (Firestore > `config` > `runtime`). Every field is optional;
      the table in functions/README.md lists them. Leave `allowSandbox` off except while testing
      or while a version is in App Review (section 8).

## 2. Firebase Authentication

- [ ] Enable the **Anonymous** provider.
- [ ] Enable **Google**. This creates the OAuth web client the Android app needs.
- [ ] Enable **Apple**. For iOS's native sign-in the bundle id is enough. For Android, which signs
      in to Apple through Firebase's web flow (`OAuthProvider("apple.com")`), the provider also
      needs the **OAuth code flow** settings: an Apple **Services ID**, your **Team ID**, and a
      **Sign in with Apple key** (key id and `.p8`). On Apple's side (Certificates, Identifiers &
      Profiles):
  - [ ] the App ID `com.foodbest.ios` has the Sign in with Apple capability (the app's
        entitlements already ask for it);
  - [ ] a Services ID (any identifier, for example `com.foodbest.ios.signin`) with Sign in with
        Apple enabled, primary App ID `com.foodbest.ios`, domain `bestbefore-f9ee1.firebaseapp.com`
        and return URL `https://bestbefore-f9ee1.firebaseapp.com/__/auth/handler` (**Check**: copy
        the exact URL the Firebase console shows on the Apple provider page);
  - [ ] a key with Sign in with Apple enabled. The same key can serve as `SIWA_PRIVATE_KEY` for
        the backend (section 9), or make a second one.
  - Android's account deletion revokes the Apple token on the phone
    (`FirebaseAuth.revokeAccessToken`), which **needs the OAuth code flow settings above**; iOS
    sends the authorization code to the server, which revokes it with `SIWA_PRIVATE_KEY`.
- [ ] Leave Identity Platform's **automatic clean-up of anonymous users off**. It would delete
      anonymous buyers together with the recipes they paid for.
- [ ] Optional: set the authorised domains and the email templates. Nothing in the app uses email
      sign-in.

## 3. Android app in Firebase

- [ ] Project settings > Your apps > `com.foodtracker.android`: add the **SHA-1 and SHA-256**
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
- [ ] **App Check**: register the Android app with **Play Integrity**. **Check**: Play Integrity
      may also need the Cloud project linked in Play Console (App integrity > Play Integrity API).
      Do not turn on console enforcement for any product; the callables enforce App Check
      themselves.
- [ ] Debug builds: build with `-PrecipePaidRoute=true` to use the real backend. The App Check
      debug provider prints a debug token to logcat on first use; register it in the console
      (App Check > Apps > Manage debug tokens). Never commit it or paste it into CI logs.

## 4. iOS app in Firebase

- [ ] Project settings > Your apps > `com.foodbest.ios`: make sure the **Team ID** is set (App
      Attest needs it).
- [ ] **Re-download `GoogleService-Info.plist`** after Google sign-in is enabled, and commit it to
      `iosApp/FoodTracker/`. The committed one has no `CLIENT_ID` / `REVERSED_CLIENT_ID`.
- [ ] Add the plist's **`REVERSED_CLIENT_ID`** as a URL scheme: `iosApp/FoodTracker/Info.plist` >
      `CFBundleURLTypes`, a second entry next to the existing `foodtracker` scheme. The app shows
      "Sign in with Google" only when the client id is present **and** its reversed form is a
      registered scheme (`GoogleSignInSupport.isConfigured`).
- [ ] **App Check**: register the iOS app with **App Attest** (DeviceCheck as a fallback only if
      wanted; the app's provider factory uses App Attest in release builds and the debug provider
      in Debug). The App ID needs the App Attest capability; the entitlement is already set to
      `production`.
- [ ] Debug builds: the App Check debug provider logs a debug token in the Xcode console on first
      use; register it in the Firebase console. Not in the repo, not in CI logs.

## 5. Google Play

- [ ] Payments profile and merchant account (Play Console > Monetisation setup), if not already
      there; paid products need it.
- [ ] **Products** (Monetise with Play > Products > In-app products): consumables `recipes_10`,
      `recipes_50`, `recipes_100`. Set the prices yourself; the server only reads the product id
      and the signed quantity. Multi-quantity purchases are allowed if you want them (the server
      multiplies by the quantity).
- [ ] **Service account access**: Users and permissions > invite
      `recipe-functions@bestbefore-f9ee1.iam.gserviceaccount.com` (created in section 9; you can
      create it first) with "View financial data" and "Manage orders and subscriptions" for this
      app. It can take up to a day to start working; until then `verifyPurchase` answers `RETRY`
      and the app keeps the purchase to resubmit.
- [ ] **Real-time developer notifications** (Monetisation setup): topic
      `projects/bestbefore-f9ee1/topics/play-rtdn` (create the topic first), with notifications
      for **one-time products / voided purchases** turned on. Grant
      `google-play-developer-notifications@system.gserviceaccount.com` the Pub/Sub Publisher role
      on that topic. Use "Send test notification" to check.
- [ ] **Licence testers** (Settings > Licence testing) for your own test accounts. Their purchases
      grant recipes only while `allowSandbox` is on.
- [ ] The app must be installed from Play (any testing track) for the paid route to show in a
      release build.

## 6. App Store Connect

- [ ] Sign the **Paid Apps agreement** and fill in banking and tax forms (Business).
- [ ] **In-app purchases**: three **consumables** with product ids `recipes_10`, `recipes_50`,
      `recipes_100`, each with a price, a display name and description in en-GB, ru and no, and a
      review screenshot. The first ones are submitted together with an app version.
- [ ] **In-App Purchase key** (Users and Access > Integrations > In-App Purchase): generate a key
      and note its key id and the issuer id. The `.p8` becomes the secret `APPSTORE_PRIVATE_KEY`.
- [ ] Note the app's **Apple ID** (App Information), for the parameter `APPLE_APP_ID`.
- [ ] **App Store Server Notifications** (App Information): Version 2, the same URL for
      production and sandbox: `https://europe-west1-bestbefore-f9ee1.cloudfunctions.net/appStoreNotifications`.
      Do this after the first deploy and use the URL `firebase deploy` prints if it differs. Then
      "Request a Test Notification".
- [ ] **Sandbox testers** (Users and Access > Sandbox) for your own testing. Sandbox and TestFlight
      purchases grant recipes only while `allowSandbox` is on; Xcode StoreKit-file purchases never
      do.

## 7. Service account and IAM

- [ ] Create `recipe-functions@bestbefore-f9ee1.iam.gserviceaccount.com` with only: Cloud
      Datastore User, Vertex AI User, Firebase Authentication Admin, Secret Manager Secret
      Accessor (on the two secrets), Logs Writer, Cloud Run Invoker and Eventarc Event Receiver.
      Add Firebase App Check Token Verifier only if `CONSUME_APP_CHECK_TOKENS` is ever turned on.
- [ ] Let the account you deploy with act as it (Service Account User on that service account).

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
