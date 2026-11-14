# Recipe packs: owner setup checklist

Everything outside the code that recipe packs need before they work for real users. The Firebase
project is `bestbefore-f9ee1`, the region `europe-west1`, the Android package
`com.foodtracker.android` and the iOS bundle `com.foodbest.ios`. The technical detail behind each
step (money rules, `config/runtime` fields, parameters, logs) is in
[functions/README.md](../functions/README.md); this file is the order to do things in.

Until the steps below are done, the free routes (shared prompt, the phone's own model) work as
normal, and the paid route either stays hidden (Android release builds not installed from Play,
iPhones without App Attest) or fails without charging anyone. The functions are deployed
(2026-11-14) with generation switched off in `config/runtime` (step 7).

Items marked **Check** are ones the code can't confirm. Steps marked **(agent)** can be done by an
agent through an API or CLI once the owner has handed over what the step needs; the rest need the
owner in a console.

## Done on the live project

Read back with read-only `gcloud` and REST calls on 2026-11-14 unless marked "per records" (done on
2026-11-13 through the App Store Connect and Play APIs, not re-read since).

- Blaze plan: billing account `012EF0-C3552F-30C63B` (NOK) linked.
- APIs enabled: Vertex AI, Google Play Android Developer, Pub/Sub, Cloud Scheduler, Secret
  Manager, Cloud Build, Artifact Registry, Eventarc, Cloud Run, Cloud Functions, Firebase App
  Check, Play Integrity, Firestore, IAM, Cloud Billing Budget, API Keys.
- Vertex AI: `gemini-3.8-flash` answers in location `global` (the default `GEMINI_MODEL` and
  `VERTEX_LOCATION`), checked 2026-11-13.
- Pub/Sub topics `budget-alerts` (Publisher: `billing-budget-alert@system.gserviceaccount.com`)
  and `play-rtdn` (Publisher: `google-play-developer-notifications@system.gserviceaccount.com`).
- Budget "Recipe kill switch bestbefore-f9ee1": 1000 NOK a month, this project, 50 / 90 / 100 %,
  connected to `budget-alerts`. The older budget "Firebase Project bestbefore-f9ee1" (same amount,
  no topic) still exists and only emails; delete one if the duplicate emails bother you.
  `budgetKillSwitch` flips only at `costAmount >= budgetAmount * budgetKillFraction` (default 1).
- Service account `recipe-functions@bestbefore-f9ee1.iam.gserviceaccount.com` with Cloud
  Datastore User, Vertex AI User, Firebase Authentication Admin, Secret Manager Secret Accessor,
  Logs Writer, Cloud Run Invoker, Eventarc Event Receiver and Firebase App Check Token Verifier
  (needed, because `CONSUME_APP_CHECK_TOKENS` is on). The deploying account
  (`daniil.pastuhov@gmail.com`) has Service Account User on it. Secret Accessor is granted on the
  two secrets only (the project-level grant was removed on 2026-11-14, step 5).
- Firebase Authentication (with Identity Platform): Anonymous, Google and Apple enabled. Apple has
  the bundle id `com.foodbest.ios` (enough for iOS's native sign-in) but no OAuth code-flow
  settings yet (step 2). Automatic clean-up of anonymous users is off (it would delete paying
  anonymous buyers); leave it off.
- Android app: four SHA-1 and four SHA-256 fingerprints registered (debug key with alias
  `debug`, upload key, Play app signing key, and Play's rotated signer for Android 17+).
- iOS app: Team ID `S8Q8KHX6G5` set.
- Config files re-downloaded and committed: `androidApp/google-services.json` has the web OAuth
  client, `iosApp/FoodTracker/GoogleService-Info.plist` has `CLIENT_ID` / `REVERSED_CLIENT_ID`,
  and the reversed client id is a URL scheme in `iosApp/FoodTracker/Info.plist`.
- App Check: Android registered with Play Integrity, iOS with App Attest, token TTL 1 hour, no
  console enforcement (the callables enforce App Check themselves; leave console enforcement
  off). Play Integrity's minimum device recognition level raised from "no integrity" to **device
  integrity** (`MEETS_DEVICE_INTEGRITY`) on 2026-11-14, by the owner's decision: Android devices
  that fail Play's device check (most rooted ones) get no App Check token and can't use the paid
  route. App Check is still rate reduction, not the money guard.
- Sign in with Apple capability on the App ID `com.foodbest.ios` (per records).
- Firestore `(default)`: Native mode, `europe-west1`, delete protection on, and the deny-all
  `firestore.rules` released (2026-11-13). The three composite indexes are built (READY) and the
  TTL policy on `generations.expiresAt` is ACTIVE (2026-11-14, step 6).
- Play Console: `recipe-functions@` invited with "View financial data" and "Manage orders and
  subscriptions" (per records).
- App Store Connect: app Apple ID `6818647200` (for `APPLE_APP_ID`); the three consumables
  `recipes_10`, `recipes_50`, `recipes_100` created and priced (USD 3.99 / 10.99 / 18.99 base,
  other storefronts converted), with names, descriptions and a review screenshot (per records). An
  updated screenshot is in `store-listing/app-store/iap-review/buy-recipes-sheet.png` (step 3).

## Left to do, in order

### 1. Apple Developer: Services ID, key, App Attest

- [ ] **Services ID** (Certificates, Identifiers & Profiles > Identifiers > + > Services IDs; no
      API): any identifier, for example `com.foodbest.ios.signin`, with Sign in with Apple, primary
      App ID `com.foodbest.ios`, domain `bestbefore-f9ee1.firebaseapp.com` and return URL
      `https://bestbefore-f9ee1.firebaseapp.com/__/auth/handler` (**Check**: copy the exact URL the
      Firebase console shows on the Apple provider page).
- [x] **Sign in with Apple key** (Keys > +; no API): note its key id. The `.p8` is
      `SIWA_PRIVATE_KEY` (step 5) and also goes into the Firebase Apple provider (step 2).
      Done 2026-11-14: key id `A8C76L3W6R`. Keep the `.p8` in safe storage (Apple lets it be
      downloaded only once); step 2 still needs it.
- [ ] **App Attest** on the App ID: not a capability type in the App Store Connect API. Automatic
      signing adds it when Xcode next signs with `-allowProvisioningUpdates`; otherwise tick it on
      the App ID. The entitlement is already `production`.

### 2. Firebase Apple provider: code flow

- [ ] Authentication > Sign-in method > Apple: Services ID as client id, Team ID `S8Q8KHX6G5`, key
      id and the `.p8`. **(agent)**, given the key: Identity Toolkit
      `defaultSupportedIdpConfigs/apple.com` `appleSignInConfig.codeFlowConfig`. Only Android's
      Apple web flow needed this, and Android no longer signs in with Apple (decision in step 3);
      iOS signs in natively with the bundle id.

### 3. App Store Connect

- [ ] **Paid Apps agreement**, banking and tax forms (Business; owner only).
- [x] **In-App Purchase key** (Users and Access > Integrations > In-App Purchase; no API): note key
      id and issuer id. The `.p8` is `APPSTORE_PRIVATE_KEY` (step 5). Done 2026-11-14: key id
      `QU395A4DS3`, issuer `6a34ffeb-1221-45c4-964b-e017e0d01ced`; a production App Store Server API
      lookup of a non-existent transaction id authenticated (answered "not found", no 401).
- [ ] **Sandbox testers** (Users and Access > Sandbox; the API can't create them).
- [x] Upload the updated **review screenshot** from `store-listing/app-store/iap-review/` to each
      of the three in-app purchases. **(agent)**: App Store Connect API
      `inAppPurchaseAppStoreReviewScreenshots`. Done 2026-11-14 (replaced on all
      three, checksum read back).
- [ ] Enrol in the **App Store Small Business Program** (15 % commission; web form, owner only).
      The spend ceilings assume a 15 % store fee (`netMicroUsdPerCredit`).

### 4. Google Play

- [ ] Payments profile and merchant account (Monetisation setup), if not there already.
- [x] (Done by the owner 2026-11-14; Play's test notification reached `playNotifications` and was
      logged as `Play RTDN` / `test`.) **Real-time developer notifications** (Monetisation setup > Real-time developer
      notifications; no API): topic `projects/bestbefore-f9ee1/topics/play-rtdn`, with one-time
      product (voided purchase) notifications on. "Send test notification".
- [x] (Linked by the owner 2026-11-14; not yet confirmed by a verified App Check request from a
      Play-installed build.) **Play Integrity**: link the Cloud project (Test and release > App integrity > Play
      Integrity API; no API).
- [ ] **Licence testers** (Settings > Licence testing; no API).
- [ ] Enrol in Play's **15 % service fee tier** (Play Console > account's developer page / account
      group; owner only).
- [x] Upload a build that contains Play Billing to a testing track: Play refuses to create in-app
      products until it has seen one. **(agent)**: a `release/v*` push through CI, or
      `scripts/play_upload.py`. Done 2026-11-14: "Recipe preview 2.0.0 (1062)" on the internal
      track, built from `feat/recipe` and uploaded by hand.
- [x] Then create the products `recipes_10`, `recipes_50`, `recipes_100` (consumable; created and
      read back 2026-11-14, purchase option `buy` active;
      USD 3.99 / 10.99 / 18.99, other regions converted by Play, multi-quantity off, listings in
      en-GB, nb and ru). **(agent)**: `scripts/play_products.py` (dry run by default; `--apply`
      writes). For each product it asks Play to convert the base price into every region's price
      (`convertRegionPrices`), creates the one-time product through
      `monetization.onetimeproducts.patch` with `allowMissing`, with one purchase option `buy`
      (legacy-compatible, no multi-quantity), activates the option and reads it back; it skips a
      product that already exists. It authenticates like `scripts/play_upload.py`.

### 5. Secrets and parameters

- [x] Secrets (Secret Manager, never in the repo), from `functions/`:
      ```bash
      cd functions
      npx firebase functions:secrets:set APPSTORE_PRIVATE_KEY < AuthKey_XXXXXXXXXX.p8   # In-App Purchase key
      npx firebase functions:secrets:set SIWA_PRIVATE_KEY < AuthKey_YYYYYYYYYY.p8       # Sign in with Apple key
      ```
      Then move Secret Manager Secret Accessor for `recipe-functions@` from the project onto these
      two secrets. **(agent)** for the move.
      Done 2026-11-14: both secrets have one enabled version (1), set with `--data-file`. The deploy
      granted `recipe-functions@` Secret Accessor on each; the project-level grant was then
      removed. Rotating a key: `functions:secrets:set` again, then redeploy (the functions pin
      version 1).
- [x] Parameters: `firebase deploy` asks for them the first time and saves them to
      `functions/.env.bestbefore-f9ee1` (no secrets; may be committed). Required:
      `APPLE_APP_ID=6818647200`, `APPSTORE_KEY_ID`, `APPSTORE_ISSUER_ID`, `APPLE_TEAM_ID=S8Q8KHX6G5`,
      `SIWA_KEY_ID`. Defaults: `FUNCTIONS_SERVICE_ACCOUNT`, `GEMINI_MODEL`, `GEMINI_THINKING_LEVEL`,
      `VERTEX_LOCATION`, `GEMINI_JUDGE_MODEL`, `GEMINI_JAILBREAK_FILTER`, `PLAY_RTDN_TOPIC`,
      `BUDGET_ALERT_TOPIC`. Done 2026-11-14: all set in `functions/.env.bestbefore-f9ee1`
      (`APPSTORE_KEY_ID=QU395A4DS3`, `SIWA_KEY_ID=A8C76L3W6R`).

### 5a. Cloud KMS key for stored Apple tokens **(done 2026-11-14)**

`registerAppleSignIn` keeps each account's Sign in with Apple refresh token, envelope-encrypted
with a Cloud KMS key, so `deleteAccount` can remove Apple's link also when the account is deleted
on Android (functions/README.md, "Stored Sign in with Apple tokens"). The key must exist, and only
the functions' service account may use it, before the functions that use it are deployed. Without
it nothing breaks for users: `registerAppleSignIn` answers `APPLE_UNAVAILABLE` (the iPhone app
ignores it), no token is stored, and a deletion from Android answers `appleLinkRemoved: false`
(the app then shows the manual instruction), as before this change. The key ring, key and the one
binding exist on the live project (created and read back 2026-11-14).

- [x] Enable the API, create the key ring and the key, and grant the service account
      encrypt/decrypt on that one key (not on the project). Run as the owner:
      ```bash
      gcloud services enable cloudkms.googleapis.com --project bestbefore-f9ee1
      gcloud kms keyrings create recipe --location europe-west1 --project bestbefore-f9ee1
      gcloud kms keys create apple-refresh-token --keyring recipe --location europe-west1 \
        --purpose encryption --protection-level software \
        --rotation-period 365d --next-rotation-time 2027-11-14T00:00:00Z \
        --project bestbefore-f9ee1
      gcloud kms keys add-iam-policy-binding apple-refresh-token --keyring recipe --location europe-west1 \
        --member serviceAccount:recipe-functions@bestbefore-f9ee1.iam.gserviceaccount.com \
        --role roles/cloudkms.cryptoKeyEncrypterDecrypter --project bestbefore-f9ee1
      ```
      The names are fixed in `functions/src/constants.ts` (`KMS_LOCATION`, `KMS_KEY_RING`,
      `KMS_APPLE_TOKEN_KEY`). Key rings and keys can't be deleted (only their versions destroyed),
      so check the names before running. Nobody else needs a role on the key: Firestore data, an
      export or a backup alone does not reveal the tokens, only a principal that can also use this
      key. A project Owner can still grant itself that, but only through an IAM change, which
      the Admin Activity audit log records; turn on Data Access audit logs for Cloud KMS
      (`DATA_READ`) if every decrypt should be logged too.
- [x] Read it back: `gcloud kms keys get-iam-policy apple-refresh-token --keyring recipe
      --location europe-west1 --project bestbefore-f9ee1` shows exactly the one binding.
- [ ] Rotation: a rotation adds a key version and makes it primary; earlier versions must stay
      **enabled** (never disable or destroy one), because tokens wrapped with them are unwrapped
      with them at deletion. Destroying the key would make every stored token unusable
      (deletions then answer `appleLinkRemoved: false`).
- Cost (Cloud KMS price list, 2026-11-14): USD 0.06 a month per active software key version
  (USD 0.000082192 an hour), so about USD 0.06 a month now and USD 0.06 more per yearly rotation,
  plus USD 0.03 per 10,000 cryptographic operations (one encrypt per Apple sign-in on an iPhone,
  one decrypt per deletion): negligible.
- The next deploy also binds `SIWA_PRIVATE_KEY` to `getBalance`, `mergeBalance` and the new
  `registerAppleSignIn` (they can revoke a stored token); the deploy grants the service account
  Secret Accessor on that secret as before, no extra step.

### 6. First deploy **(agent)**

- [x] `cd functions && npm ci && npx firebase deploy --only functions,firestore --project bestbefore-f9ee1`
      (lint and build run first). This deploys the functions, the rules again,
      `firestore.indexes.json` (the sweeper's indexes) and the **TTL policy** on
      `generations.expiresAt`. Afterwards check that `gcloud firestore fields ttls list` shows
      `expiresAt` as active (TTL can take a while to become active), and the indexes are built.
      Done 2026-11-14 from commit `323b298`: all eleven functions created in `europe-west1`,
      indexes READY, TTL ACTIVE. The deploy also set a 1-day image clean-up policy on the
      functions' Artifact Registry repository.
- [x] Note the `appStoreNotifications` URL it prints:
      `https://europe-west1-bestbefore-f9ee1.cloudfunctions.net/appStoreNotifications`.

### 7. `config/runtime` **(agent)**

- [x] Create `config/runtime` in Firestore. Every field is optional and the defaults are the
      intended production values (functions/README.md, "`config/runtime`"), so an empty document
      is fine; leave `outputJudge` at `off`. Set `allowSandbox: true` only for steps 9 and 10.
      Done 2026-11-14 with `generationEnabled: false` and `allowSandbox: false`, so nothing can
      be generated yet. Set `generationEnabled` to `true` (or delete the field) only when the
      steps before the smoke test are done.
      **2026-11-14: the owner turned `generationEnabled` on** (`allowSandbox` stays `false`), so a
      real purchase now gets recipes written by the real model. To pause again, set it to `false`.

### 8. App Store Server Notifications **(agent)**

- [x] (Done 2026-11-14 through the App Store Connect API; Apple's test notification was
      delivered in production and in sandbox and logged as `appstore_notification` / `test`.)
      Version 2, the same URL for production and sandbox:
      `https://europe-west1-bestbefore-f9ee1.cloudfunctions.net/appStoreNotifications` (or the URL
      the deploy printed). App Store Connect API: the app's `subscriptionStatusUrl*` attributes (**Check** the current API);
      or App Information in the console. Then "Request a Test Notification" (App Store Server API)
      and see it logged.

### 9. Alerts **(agent)**

- [x] (Done 2026-11-14: four policies named "Recipe: …" that email the owner, at most one
      email an hour each.) Log-based alerts in Cloud Monitoring on `jsonPayload.message` for the names in
      functions/README.md, both "Logs to alert on" tables. At least: `refund_after_use`,
      `buyer_blocked`, `negative_balance_total_grew`, `play_consume_gave_up`,
      `apple_consumption_answer_expired`, `sweep_item_failed`, `callable_failed`,
      `Budget kill switch flipped: generation disabled`, `prompt_rejection_limit`, and
      `prompt_output_rejected` with `code` `OUTPUT_ALLERGEN` or `OUTPUT_LEAK`.
- [x] (Done 2026-11-14: thresholds are now 25, 50, 90 and 100 %; the emails go to the billing
      account's admins.) Add email alerts at 25 % and 50 % to the kill-switch budget (the topic only pauses
      generation; a flood of function calls can cost money on its own, see functions/README.md,
      "Worst-case daily bill").

### 10. Test

- [ ] Smoke test with `allowSandbox: true`: a licence tester on Android and a sandbox account on
      iOS buy a pack, generate a recipe and a menu and see the balance drop by what `costs` says;
      sign in on the other platform and see the same balance; delete a test account.
- [ ] Run the manual prompt-injection red-team checklist,
      [functions/test/injection-prompts.md](../functions/test/injection-prompts.md), against the
      deployed function (about 25 attempts, USD 1–2). If anything harmful or off-topic gets
      through, follow functions/README.md, "The output judge" (`shadow` for a week, then
      `enforce`).
- [ ] Add to that checklist, and run on a real device, cases for item names in non-Latin scripts
      (Chinese, Japanese, Arabic, Hebrew, Hindi, Greek, Thai...) and right-to-left names (Hebrew,
      Arabic, Persian, also mixed with Latin units such as `חלב 3% 1L`): an instruction written in
      such a script as an item name (it passes the input rule; see functions/README.md, "Residual
      risks"), an answer asked to switch to that script, an allergen written in it, and how the
      recipe and the item list render (direction, quoting of the name in the answer).
- [ ] First deploy: check that every `llm_call` log line carries real token counts from
      `usageMetadata` (input, output and thinking tokens, not booked at the worst case;
      `stats/{day}.unknownUsageCalls` stays at 0), and compare the measured cost of the first
      recipes and menus with the assumptions in functions/README.md, "Spend ceilings" (≈ USD 0.02
      a recipe, 0.08 a 2-course menu, 0.11 a 3-course menu) and the prices in "Price table".
- [ ] Set `allowSandbox` back to `false`, except while a version is in App Review (below).

### 11. Privacy, store forms, artwork

- [ ] Merge the privacy policy pull request
      (<https://github.com/daniil-pastuhov/bestbefore-privacy/pull/1>) when the release goes out;
      see [privacy-policy-changes.md](privacy-policy-changes.md).
- [ ] App Store Connect > App Privacy and Play Console > Data safety: the answers are in
      `store-listing/app-store/README.md`, section 5. App Privacy has no API; Play's Data safety
      can be uploaded as CSV through the Play Developer API **(agent)**. Play Console > App
      content: in-app purchases, and account deletion (Recipe tab > Recipe settings > Account >
      Delete account; for recipes bought without an account, Recipe settings > Your recipes >
      Delete my data from our server, which deletes the anonymous account). **Check** whether
      Play also wants a web page for deletion requests.
- [x] Retake the store screenshots (the Recipe tab replaced Calendar) **(agent)**:
      `scripts/ios_screenshots.sh`, `scripts/android_screenshots.sh`, then
      `scripts/frame_screenshots.py` (store-listing/app-store/README.md). Done 2026-11-14 for
      every set, iPhone Duo inner included; none is uploaded to the stores yet. The next release's
      merge uploads them with the new listing texts (`scripts/testflight.py appstore --screenshots`,
      `scripts/play_listing.py --images --apply`), the iPhone Duo sets and the App Store header
      and search-result images included (store-listing/app-store/README.md).
- [x] Android "Sign in with Apple": **dropped** (owner's decision, 2026-11-14). Apple's guidelines
      allow a custom button on Android only with Apple's logo artwork, which can't be
      redistributed in this public repository, and allow no button without a logo, so Android
      offers Google sign-in only; iOS keeps Apple and Google. An account made with Apple on an
      iPhone reaches Android once Google is added to it on the iPhone (the apps say so). The
      Firebase Apple provider's code-flow settings and the Services ID `com.foodbest.ios.signin`
      exist on the live project but Android no longer uses them; they can stay. The Sign in with
      Apple key (`SIWA_PRIVATE_KEY`, `SIWA_KEY_ID`) is still needed: the backend uses it to exchange
      the code of each Apple sign-in on an iPhone for a refresh token (`registerAppleSignIn`, kept
      encrypted, step 5a) and to revoke Apple's token when the account is deleted, on the iPhone
      (with a fresh code) or on Android (with the stored token). Only when neither works does the
      app, after deleting, tell the person to turn off Sign in with Apple for the app in their
      Apple Account settings.
      The Google button (Android and iOS) is done: Google's own button on iOS, and on Android a
      button drawn to Google's branding guidelines with the "G" from Google's asset bundle
      (`GoogleSignInButton.kt`).

### App Review switch

Before submitting a version that sells packs, set `allowSandbox: true` and make sure the caps
leave room for the reviewer: `sandboxDailyCap` (default 20 sandbox purchases a day, all users) and
`sandboxDailyCredits` (default 200 recipes a day). Over a cap the purchase answers `RETRY` and the
app keeps it to resubmit. While it is on, anyone with a TestFlight build or a sandbox account can
get recipes free, up to those caps. Turn it off after approval. The review notes,
`store-listing/app-store/review-notes.txt`, describe the reviewer's path; the release job writes
them to the version and submits the three packs with it (`scripts/testflight.py appstore`; Apple
requires the first in-app purchases to be reviewed with a new version). If App Store Connect
refuses to add the packs through the API, the job stops before submitting and says how to add
them by hand (each pack's page > Add for Review, into the version's draft submission); re-run it
afterwards. `scripts/testflight.py iaps` shows, read-only, what it would do.

### Debug builds against the real project

The App Check debug provider prints a debug token on first use (logcat on Android, the Xcode
console on iOS); register it under App Check > Apps > Manage debug tokens. Never commit it or paste
it into CI logs. Against the emulators no token is needed (functions/README.md, "Running the
functions for the apps"; `-PrecipeEmulatorHost=10.0.2.2` on Android, `-recipeEmulatorHost
127.0.0.1` on iOS).

Nothing deploys the backend from CI; functions/README.md, "Deploying from CI", lists what a deploy
job would need.
