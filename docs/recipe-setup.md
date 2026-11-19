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
  other storefronts converted; `recipes_10` lowered to USD 2.99 on 2026-11-15 with
  `scripts/testflight.py iap-prices --apply`, README.md "Changing a pack's price"), with names, descriptions and a review screenshot (per records). An
  updated screenshot is in `store-listing/app-store/iap-review/buy-recipes-sheet.png` (step 3).

## Left to do, in order

### 1. Apple Developer: Services ID, key, App Attest

- [x] **Services ID** (Certificates, Identifiers & Profiles > Identifiers > + > Services IDs; no
      API): any identifier, for example `com.foodbest.ios.signin`, with Sign in with Apple, primary
      App ID `com.foodbest.ios`, domain `bestbefore-f9ee1.firebaseapp.com` and return URL
      `https://bestbefore-f9ee1.firebaseapp.com/__/auth/handler` (**Check**: copy the exact URL the
      Firebase console shows on the Apple provider page).
- [x] **Sign in with Apple key** (Keys > +; no API): note its key id. The `.p8` is
      `SIWA_PRIVATE_KEY` (step 5) and also goes into the Firebase Apple provider (step 2).
      Done 2026-11-14: key id `A8C76L3W6R`. Keep the `.p8` in safe storage (Apple lets it be
      downloaded only once); step 2 still needs it.
- [x] **App Attest** on the App ID: not a capability type in the App Store Connect API. Automatic
      signing adds it when Xcode next signs with `-allowProvisioningUpdates`; otherwise tick it on
      the App ID. The entitlement is already `production`.

### 2. Firebase Apple provider: code flow

- [x] Authentication > Sign-in method > Apple: Services ID as client id, Team ID `S8Q8KHX6G5`, key
      id and the `.p8`. **(agent)**, given the key: Identity Toolkit
      `defaultSupportedIdpConfigs/apple.com` `appleSignInConfig.codeFlowConfig`. Only Android's
      Apple web flow needed this, and Android no longer signs in with Apple (decision in step 3);
      iOS signs in natively with the bundle id.

### 3. App Store Connect

- [x] **Paid Apps agreement**, banking and tax forms (Business; owner only).
- [x] **In-App Purchase key** (Users and Access > Integrations > In-App Purchase; no API): note key
      id and issuer id. The `.p8` is `APPSTORE_PRIVATE_KEY` (step 5). Done 2026-11-14: key id
      `QU395A4DS3`, issuer `6a34ffeb-1221-45c4-964b-e017e0d01ced`; a production App Store Server API
      lookup of a non-existent transaction id authenticated (answered "not found", no 401).
- [x] **Sandbox testers** (Users and Access > Sandbox; the API can't create them).
- [x] Upload the updated **review screenshot** from `store-listing/app-store/iap-review/` to each
      of the three in-app purchases. **(agent)**: App Store Connect API
      `inAppPurchaseAppStoreReviewScreenshots`. Done 2026-11-14 (replaced on all
      three, checksum read back).
- [x] Enrol in the **App Store Small Business Program** (15 % commission; web form, owner only).
      The spend ceilings assume a 15 % store fee (`netMicroUsdPerCredit`).

### 4. Google Play

The app has no production release on Play yet: getting there (a 14-day closed test with at least
12 testers, then the production-access application) and the App content declarations the Recipe
release changes are in [play-production.md](play-production.md); tester invitations are in
[marketing/play-testers/](marketing/play-testers/README.md).

- [x] Payments profile and merchant account (Monetisation setup), if not there already.
- [x] (Done by the owner 2026-11-14; Play's test notification reached `playNotifications` and was
      logged as `Play RTDN` / `test`.) **Real-time developer notifications** (Monetisation setup > Real-time developer
      notifications; no API): topic `projects/bestbefore-f9ee1/topics/play-rtdn`, with one-time
      product (voided purchase) notifications on. "Send test notification".
- [x] (Linked by the owner 2026-11-14; not yet confirmed by a verified App Check request from a
      Play-installed build.) **Play Integrity**: link the Cloud project (Test and release > App integrity > Play
      Integrity API; no API).
- [x] **Licence testers** (Settings > Licence testing; no API). Added by the owner 2026-11-14.
- [x] Enrol in Play's **15 % service fee tier** (Play Console > account's developer page / account
      group; owner only).
- [x] Upload a build that contains Play Billing to a testing track: Play refuses to create in-app
      products until it has seen one. **(agent)**: a `release/v*` push through CI, or
      `scripts/play_upload.py`. Done 2026-11-14: "Recipe preview 2.0.0 (1062)" on the internal
      track, built from `feat/recipe` and uploaded by hand.
- [x] Then create the products `recipes_10`, `recipes_50`, `recipes_100` (consumable; created and
      read back 2026-11-14, purchase option `buy` active;
      USD 3.99 / 10.99 / 18.99, other regions converted by Play (`recipes_10` lowered to USD
      2.99 on 2026-11-15: `scripts/play_products.py --reprice --apply`, README.md "Changing a pack's
      price"), multi-quantity off, listings in
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
- [x] Rotation: a rotation adds a key version and makes it primary; earlier versions must stay
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
- To follow estimated model spend and pack sales: `cd functions && npm run admin -- board
      --project bestbefore-f9ee1` (read-only, writes a local HTML board; functions/README.md, "The board").
- [x] (Done 2026-11-15: bucket `bestbefore-f9ee1-owner-board` with public access prevention and
      uniform access, the `ownerBoardWriter` role, both bucket bindings, `redrawBoard` deployed;
      first redraw at 13:08 UTC, the page opens for the owner and an anonymous read answers 403.)
      Always-on board at <https://storage.cloud.google.com/bestbefore-f9ee1-owner-board/board.html>
      (functions/README.md, "The board", "Always on").
- [x] (Done 2026-11-15: a fifth policy, "Recipe: owner board", same email channel and one email
      an hour at most.) Log-based alert on `board_redraw_failed` and `board_bucket_public`. It
      does not notice a redraw that silently stops running; the board's own "Updated" line does.

- [ ] Alerts as code (functions/README.md, "Alerts"): the five policies above plus four new ones
      (model spend at 50 % / 80 % of the daily ceiling, ceiling or daily cap reached, the welcome
      grant's day limit, a spike of per-account limit refusals) and the log-based metric
      `recipe_generation_refused`, all in `functions/alerts/`, on the existing email channel
      "Recipe backend alerts (owner)". Deploy the functions first (the new log lines come with
      them), then from the repository root:
      `python3 functions/alerts/alerts.py --project bestbefore-f9ee1` (dry run: it should plan 1
      metric and 4 policies to create and the 5 existing policies unchanged), then the same with
      `--apply`, then the dry run again (everything "unchanged"). Needs Monitoring Editor and Logs
      Configuration Writer; no address is ever typed into a file. Before release, also set
      `config/runtime.welcome.perDay` to the recommended launch value (functions/README.md,
      "Welcome grant").

### 10. Test

- [x] Smoke test with `allowSandbox: true`: a licence tester on Android and a sandbox account on
      iOS buy a pack, generate a recipe and a menu and see the balance drop by what `costs` says;
      sign in on the other platform and see the same balance; delete a test account.
- [x] Run the prompt-injection red-team checklist,
      [functions/test/injection-prompts.md](../functions/test/injection-prompts.md): its cases are
      in `functions/test/redteam/cases.json` and run with one command against the live model
      (section "Running it" there): `cd functions && npm run redteam -- plan` prints the plan and
      the cost estimate offline, then
      `VERTEX_TOKEN=$(gcloud auth print-access-token --account=<your account>) npm run redteam -- run --yes --repeat 3`
      runs it (about USD 1 at list prices, stopped by `--max-usd`) and writes a report to
      `functions/test/redteam/results/`. Items 25 (the judge in shadow mode) and 32 (the
      3-a-day refund cap) stay manual against the deployed function. If anything harmful or
      off-topic gets through, follow functions/README.md, "The output judge" (`shadow` for a
      week, then `enforce`).
- [x] Add to that checklist, and run on a real device, cases for item names in non-Latin scripts
      (Chinese, Japanese, Arabic, Hebrew, Hindi, Greek, Thai...) and right-to-left names (Hebrew,
      Arabic, Persian, also mixed with Latin units such as `חלב 3% 1L`): an instruction written in
      such a script as an item name (it passes the input rule; see functions/README.md, "Residual
      risks"), an answer asked to switch to that script, an allergen written in it, and how the
      recipe and the item list render (direction, quoting of the name in the answer). The model
      side of this is in the red-team file (cases 43–47: Greek, Japanese and Hebrew names, an
      instruction in Greek and in Chinese, an allergen in Chinese); what is left here is the
      rendering on a device.
- [x] First deploy: check that every `llm_call` log line carries real token counts from
      `usageMetadata` (input, output and thinking tokens, not booked at the worst case;
      `stats/{day}.unknownUsageCalls` stays at 0), and compare the measured cost of the first
      recipes and menus with the assumptions in functions/README.md, "Spend ceilings" (≈ USD 0.02
      a recipe, 0.08 a 2-course menu, 0.11 a 3-course menu) and the prices in "Price table".
- [ ] Set `allowSandbox` back to `false`, except while a version is in App Review (below).
      **2026-11-15: stays `true` for now** (owner's decision: 2.1.0, the first version that
      sells packs, is going to App Review); turn it off after approval.

### 11. Privacy, store forms, artwork

- [x] (Merged 2026-11-14; live, with the deletion page at
      https://daniil-pastuhov.github.io/bestbefore-privacy/delete.html, the URL for Play's
      account-deletion field.) Merge the privacy policy pull request
      (<https://github.com/daniil-pastuhov/bestbefore-privacy/pull/1>) when the release goes out;
      see [privacy-policy-changes.md](privacy-policy-changes.md).
- [x] The policy site's pull request
      [daniil-pastuhov/bestbefore-privacy#3](https://github.com/daniil-pastuhov/bestbefore-privacy/pull/3)
      was merged on 2026-11-15: the policy in eight languages, support and delete in eight
      languages and the About page `app.html` are live, so the release's listing sync sets each
      App Store localization's privacy policy URL to its language's page
      (`store-listing/app-store/<locale>/privacy_url.txt`, `store-listing/app-store/README.md`
      "Privacy policy URL per language"). Google Play keeps the one app-wide URL (Play Console >
      App content), the chooser root https://daniil-pastuhov.github.io/bestbefore-privacy/, which
      needs no change.
- [ ] Put https://daniil-pastuhov.github.io/bestbefore-privacy/app.html into App Store Connect's
      Marketing URL by hand (the listing sync doesn't set it).
- [x] App Store Connect > App Privacy and Play Console > Data safety, when the release that
      sells packs goes out (reasons in `store-listing/app-store/README.md`, section 5):
  - App Privacy: by hand, following [app-privacy-apple.md](app-privacy-apple.md) (the public
    App Store Connect API has no App Privacy resources; fastlane's action uses an Apple Account
    web session, which we don't use).
  - Data safety: the whole form is `store-listing/google-play/data-safety.csv` **(agent,
    2026-11-15)**. `python3 scripts/play_data_safety.py` validates it and prints what it
    declares; with `PLAY_SERVICE_ACCOUNT_JSON` set, `--apply` replaces the form on Play (the API
    can't read it back, so compare in the Console first). Account deletion and data deletion
    both point to https://daniil-pastuhov.github.io/bestbefore-privacy/delete.html (Play's
    required web link; in the app: Recipe tab > Recipe settings > Account > Delete account, or
    Your recipes > Delete my data from our server for the anonymous account). Also in App
    content: in-app purchases.
  - Corrected 2026-11-15 against the code: on Play, crash logs, diagnostics and device IDs are
    for **Analytics** (Play's definition: "to diagnose and fix bugs or crashes"), not App
    functionality; Health info (allergens) is **not** processed ephemerally (Vertex AI's 24-hour caching of
    inputs was turned OFF for this project on 2026-11-15, see "Vertex AI data caching" below; Google may
    still log prompts for abuse monitoring, which has no opt-out, so the answer stays "not ephemeral"); Name is for Account
    management only. On Apple, Other Diagnostic Data also has purpose Analytics (the Firebase
    SDKs' own manifests).
  - Judgement calls left to you: whether the barcode sent to Open Food Facts must be declared
    (not declared: a product code without user or device ID, sent straight to Open Food Facts;
    the strict reading would be App activity > In-app search history, collected, optional);
    pantry names and recipe settings as Other user-generated content rather than Other actions;
    Health info at all, given consent and no storage on our server (declared, since Google's
    cache keeps it); the anonymous Firebase uid as User IDs (declared: Play counts
    pseudonymous data); Apple's Device ID, Analytics and "not linked" for crash data
    (app-privacy-apple.md, "Judgement calls").
- [x] "Send all products" (Recipe settings, off by default): with it on, a recipe from a pack
      also sends the names, dates and quantities of every other pantry item, as items the recipe
      may use. The store answers do not change (still User Content > Other User Content / App
      activity > Other user-generated content, App Functionality, linked, not tracking, and in
      data-safety.csv; only the
      description in store-listing/app-store/README.md section 5 grew), but the hosted privacy
      policy's sentence about what is sent must name it before the release that ships it
      (privacy-policy-changes.md). The server reads the new field only after the next backend
      deploy (`acceptsOptionalIngredients`, functions/CONTRACT.md); until then the apps send the
      ticked items only.
- [ ] Staples (Settings > Staples, 2026-11-16): a recipe from a pack also sends the names of the
      person's staples (names only, those on the shopping list left out), whatever "Send all
      products" says, and a shared prompt lists them. The store answers do not change (still User
      Content > Other User Content / App activity > Other user-generated content, App
      Functionality, linked, not tracking, and in data-safety.csv; only the descriptions in
      store-listing/app-store/README.md section 5 and app-privacy-apple.md grew; confirm when
      submitting), but the hosted privacy policy's sentence about what a recipe from a pack sends
      (`daniil-pastuhov/bestbefore-privacy`, `build.py`, every language) must name staples before
      the release that ships them (privacy-policy-changes.md). The server reads the new field only
      after the next backend deploy (`cd functions && npm ci && npx firebase deploy --only
      functions,firestore --project bestbefore-f9ee1`; `acceptsStaples`, functions/CONTRACT.md);
      until then the apps send no staples to it.
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

### 12. Recipe pictures (before the app release that offers them)

A recipe from a pack can come with a generated picture of the dish (functions/README.md "Recipe
pictures"). Nothing here is needed for the backend to deploy; until these are done, leave
pictures off with `config/runtime.costs.recipeImage: 0` (the apps then offer no picture), or test
them with `allowSandbox` only.

- [ ] **Check** that both picture models answer: Vertex AI `gemini-3.1-flash-lite-image` (the
  default `IMAGE_MODEL`) and its fallback `gemini-nano-banana-2.1` (`IMAGE_FALLBACK_MODEL`), both on
  location `global` (`VERTEX_IMAGE_LOCATION`), each with one 4:3 1K JPEG, from Vertex AI Studio or
  one `generateContent` call as the service account (a size check on 2026-11-17 got 1200 × 896
  from both, in about 3 s and 15 s). It uses the Vertex AI API and the
  service account's Vertex AI User role already in place; no new API, key or vendor. If Model
  Garden asks to accept the model's terms, accept them there. The model page lists `global` only:
  the picture prompt (no personal data) is processed wherever Google serves it, like the recipe
  text today.
- [ ] Deploy the functions and `firestore` (`firestore.indexes.json` adds the TTL policies on
  `recipeImages.expiresAt`, the stored pictures, deleted with their generation, on
  `reports.expiresAt` and `reportPictures.expiresAt`, the "Report this recipe" reports, deleted
  after `config/runtime.reports.keepDays`, 90 days, and on `reportable.expiresAt`, the year-long
  record of each delivered result), then check `gcloud firestore fields ttls list` shows all
  four ACTIVE.
- [ ] **Output judge answers** (functions/README.md "Prompt injection defences" item 5; 2026-11-18):
  the deploy that ships the lite judge also adds the TTL policy on `judgeAnswers.expiresAt` (90
  days) through `firestore.indexes.json`: check `gcloud firestore fields ttls list` shows it
  ACTIVE before turning `outputJudge` on. To collect answers without blocking anything, set
  `outputJudge: "shadow"` (console or `npm run admin -- config set outputJudge shadow --apply
  --confirm outputJudge`), and read them with `npm run admin -- judge summary --project
  bestbefore-f9ee1`. Leave `outputJudgeKeepText` off unless the privacy policy first says that
  generated recipes, without any link to the person, are kept 90 days to improve the prompts.
- [x] (Done 2026-11-17: made in the console, then kept as code in `functions/alerts/policies/`,
  `recipe-reported.json` and `report-day-limit.json`, and applied with `alerts.py`.) **Alert on
  reports** (functions/README.md "Reports", "Alerts"): "Recipe: recipe reported" on
  `jsonPayload.message="recipe_reported"` (any) and "Recipe: report day limit reached" on
  `report_day_limit`, to the owner's email channel like the other "Recipe: …" policies; read the
  report in the Firestore console (`reports/`).
- [ ] Decide the prices (`config/runtime.costs.recipeImage`, default 2 = twice a recipe, and
  `recipeImageSteps`, default 3, for the dish and up to `image.maxStepImages` = 2 step pictures) and leave
  `prices.imageUsdPerImage` at its defaults unless Google's price changed (USD 0.04 Flash-Lite
  Image, 0.06 Nano Banana 2.1; a picture is reserved at the dearer of the two and booked at its
  real cost from the token counts Vertex reports).
- [ ] Watch `image_retry` and `image_fallback` in the logs after the first days (functions/README.md
  "Logs to alert on"): many retries mean the shared Vertex capacity is short for the picture model
  (ask for more quota or Provisioned Throughput); frequent fallbacks mean the primary is failing
  and each picture costs more.
- [ ] Privacy policy and store forms: "Report this recipe" stores the reason, the optional note,
  the account id and a copy of the reported recipe or menu (and its pictures) for 90 days; it is
  offered only on results from a pack.
- [ ] Privacy policy and store forms: a recipe from a pack may come with generated pictures,
  kept on our server only as long as the generation (about 7 days) and then on the phone with the
  saved recipe; the picture model receives only a short English description of the dish written
  by the recipe model, never the person's items, allergens or taste answers. No new data type is
  collected (App Privacy / Data safety unchanged); re-read the age-rating answers for
  AI-generated images.
- [ ] Only to use OpenAI (pictures, recipe text or both; functions/README.md "Switching provider
  and model", "OpenAI for the recipe text"): create the secret
  `npx firebase functions:secrets:set OPENAI_API_KEY` (one key for both), grant
  `recipe-functions@` **Secret Manager Secret Accessor on that secret only**
  (`gcloud secrets add-iam-policy-binding OPENAI_API_KEY --member serviceAccount:recipe-functions@bestbefore-f9ee1.iam.gserviceaccount.com --role roles/secretmanager.secretAccessor --project bestbefore-f9ee1`),
  then switch the picture model or the text model to an OpenAI option (step 13). OpenAI then
  becomes a data processor of the picture prompts, and, for the text, of the recipe requests
  themselves (update the privacy policy first; step 13g).

### 13. Owner console: switching the providers (functions/README.md "The owner console")

A dashboard page (what is in force now, the four provider switches and the runtime settings in cards), on Firebase Hosting
(`https://bestbefore-f9ee1.firebaseapp.com/`). Until these are done the switches work from a shell
only (`npm run admin -- providers list | set <switch> <value> --apply --project bestbefore-f9ee1`, "Provider
switches"), and the board shows the setup in force with a link that leads nowhere yet. (The page
first shipped with named profiles, 2026-11-18; step 13g replaces them with the four switches.)

- [x] **(done 2026-11-18, agent)** **Register a Web app** in the Firebase project (Project settings > General > Your apps > Add
  app > Web; a nickname such as "Owner console"; Hosting can be left unticked). The page reads its
  configuration from Hosting's `/__/firebase/init.json`, which needs one. Its browser API key may be
  restricted to the HTTP referrers `https://bestbefore-f9ee1.firebaseapp.com/*` and
  `https://bestbefore-f9ee1.web.app/*` (Cloud console > APIs & Services > Credentials), keeping
  the Identity Toolkit and Token Service APIs allowed.
- [x] **(done 2026-11-18, agent)** **Check** Authentication > Sign-in method > Google is enabled with its Web client (it is, for
  Android), and Authentication > Settings > Authorized domains lists `bestbefore-f9ee1.firebaseapp.com`
  (there by default). Nothing to add for the page itself.
- [x] **(done 2026-11-18, agent)** **Have an app account with that Google account first**: sign in with it in the app (Recipe
  settings > Account, Android or iOS). The page refuses a Google account that has no Firebase
  user yet: such a sign-in would make one, which would then stop that Google account being linked
  in the app ("credential already in use"), so the page deletes it at once and says the account
  has no account with the app (if it can't, delete it in Authentication > Users).
- [x] **(done 2026-11-18, agent)**, by `uids` (one uid) **Set the allowlist**: Firestore > `config` > add document `owner` with one of these
  (both may be set; either lets you in):
  - `uids` (array of strings): the account's **User UID** from Authentication > Users (the row
    whose Providers column shows the Google icon). Preferred: it can't be confused with another
    sign-in's address.
  - `emails` (array of strings): the address of the **Google account** you will sign in with, as
    Google shows it. It is matched against the Google identity linked to that Firebase user (its
    `google.com` provider entry), not the address in the Users table's Identifier column: an
    account made with Apple shows the Apple address there, and one whose Google account was
    replaced still shows the old one. Case and surrounding spaces don't matter.

  An empty or misspelt field, or anything not an array of strings, lets nobody in. Type it in the
  console only: never in the repo, a script or a message.
- [x] **(done 2026-11-18, agent)**: "Site Not Found" on both hosts, no release on `live`. **Check what the default Hosting site serves now**, since deploying Hosting replaces it:
  `npx firebase hosting:sites:list --project bestbefore-f9ee1` and
  `npx firebase hosting:channel:list --project bestbefore-f9ee1` (a release on `live` means
  something is deployed), and open `https://bestbefore-f9ee1.web.app/` and
  `https://bestbefore-f9ee1.firebaseapp.com/` (Firebase's "Site Not Found" page means unused). If
  it serves anything, stop and say so: the page then needs its own site.
- [x] **(done 2026-11-18, agent)** (full `--only functions,firestore` deploy) Deploy the two callables:
  `cd functions && npm ci && npx firebase deploy --only functions:getProviderProfiles,functions:setProviderProfile --project bestbefore-f9ee1`
  (the next full deploy, `--only functions,firestore`, also updates `redrawBoard`'s board with the
  "Recipe pictures" tile). Always name what to deploy with `--only`: `firebase.json` has a
  `hosting` block, so a bare `firebase deploy` would deploy Hosting too.
- [x] **(done 2026-11-18, agent)**, the `curl` checks only (the pop-up on the preview host was not tried) **Try Hosting on a preview channel first**, which leaves the live site alone:
  `npx firebase hosting:channel:deploy owner-check --expires 1d --project bestbefore-f9ee1`, then
  on the URL it prints:
  - `curl -sI <url>/` shows the `Content-Security-Policy` (with `frame-ancestors 'none'`) and
    `Cross-Origin-Opener-Policy` headers;
  - `curl -sI <url>/__/auth/handler` and `curl -sI <url>/__/auth/iframe` show **neither** header
    (Hosting's own sign-in pages: with them the page's sign-in, and the apps' Sign in with Apple
    return to the handler, could break). If either shows them, stop and say so;
  - the page opens and its "Sign in with Google" pop-up works there (the preview channel's host
    may first need adding to Authentication > Settings > Authorized domains; remove it after).
    The switches won't load there: the callables' CORS answers only the live site's two hosts.

  Then deploy the live site: `npx firebase deploy --only hosting --project bestbefore-f9ee1`, and
  run the same two `curl -sI` checks on `https://bestbefore-f9ee1.firebaseapp.com`.
- [x] **(done 2026-11-18, agent)** Apply the new alert, "Recipe: provider profile switched"
  (`functions/alerts/policies/provider-profile-changed.json`):
  `python3 functions/alerts/alerts.py --project bestbefore-f9ee1` (dry run), then with `--apply`.
- [ ] **Check**: step 13g, once the four switches are deployed.
- [ ] Decide whether OpenAI may ever be switched on: its options stay "not ready" until the code's
  `approved` flags (`openaiImage` for pictures, `openaiText` for the recipe text) are turned on
  (after the privacy policy names OpenAI) and its key is set (step 12, last item).

### 13g. The four provider switches (2026-11-19; functions/README.md "Provider switches") **(owner)**

The named profiles are replaced by four switches, each set on its own: the text model, the text
fallback, the picture model, the picture fallback. Two callables are new and two are gone, so:

- [x] **(done 2026-11-19, agent; full `--only functions,firestore` deploy)** **Deploy the two new callables**:
  `cd functions && npm ci && npx firebase deploy --only functions:getProviderSwitches,functions:setProviderSwitch --project bestbefore-f9ee1`
  (or the next full `--only functions,firestore` deploy, which also carries the OpenAI text adapter
  and the other changed functions).
- [x] **(done 2026-11-19, agent)** **Deploy the page**: `npx firebase deploy --only hosting --project bestbefore-f9ee1` (the
  headers are unchanged; the two `curl -sI` checks of step 13 still apply). The functions first:
  the new page calls the new names.
- [x] **(done 2026-11-19, agent; by the full deploy, with `--force`)** **Remove the two old callables**, which nothing calls any more:
  `npx firebase functions:delete getProviderProfiles setProviderProfile --region europe-west1 --project bestbefore-f9ee1`
  (a full deploy offers to delete them too).
- [x] **(done 2026-11-19, agent; six descriptions updated)** **Re-apply the alerts** whose documentation changed (the filters did not:
  `provider_profile_changed` keeps its name and now carries `slot`, `from`, `to`):
  `python3 functions/alerts/alerts.py --project bestbefore-f9ee1` (dry run), then with `--apply`.
- [ ] **Check**: open the page signed out of everything (a private window) and see only the
  sign-in button; sign in with another Google account and get "may not use the owner console";
  sign in with yours and see four switches: Text model `google/gemini-3.8-flash`, Text fallback
  none, Picture model `vertex/gemini-3.1-flash-lite-image`, Picture fallback
  `vertex/gemini-nano-banana-2.1`, the OpenAI options listed but not ready. Switch the picture
  fallback to "No fallback" and back once, and see the alert emails and the two
  `providerProfileChanges/` records (each names its switch). On an iPhone, Safari may ask to allow
  the sign-in pop-up.
- [ ] **OpenAI for the recipe text** (new adapter, not tried live; functions/README.md "OpenAI for
  the recipe text"): unlike pictures, the text request carries user data (the ticked items, the
  allergens, which are health data, the diets and the taste answers), so the privacy policy and
  processor list must name OpenAI for it, with where it processes (the United States by default),
  and the App Privacy / Data safety answers be re-read (docs/privacy-policy-changes.md, "Pending")
  before `openaiText`'s `privacy.approved` is set to `true` in code. Then the key (step 12), a
  deploy, the red-team run against it (functions/README.md "The red-team run") and a few real
  recipes on the emulators and live before it is chosen as the text model; as a fallback first is
  the cautious order.

### 13b. Owner console: the other runtime settings (functions/README.md "The owner console", "Settings")

The console's settings section and `npm run admin -- config`. Nothing new to set up in the
console; until these are done, `config/runtime` is edited by hand (or with the command, which
works as soon as it is merged).

- [x] **(done 2026-11-18, agent)** (full `--only functions,firestore` deploy) Deploy the two new callables:
  `cd functions && npm ci && npx firebase deploy --only functions:getOwnerSettings,functions:setOwnerSettings --project bestbefore-f9ee1`
  (or the next full `--only functions,firestore` deploy). Firestore needs no new index (the audit
  collection is ordered by one field).
- [x] **(done 2026-11-18, agent)**, after the `owner-check` preview channel, with the `curl` checks on both hosts **Deploy the page**: `npx firebase deploy --only hosting --project bestbefore-f9ee1`, then the
  two `curl -sI` checks of step 13 on `https://bestbefore-f9ee1.firebaseapp.com` (the headers are
  unchanged).
- [x] **(done 2026-11-18, agent)** Apply the two new alerts, "Recipe: owner settings changed" and "Recipe: owner console
  refusals" (`functions/alerts/policies/owner-settings-changed.json`, `owner-refusals.json`):
  `python3 functions/alerts/alerts.py --project bestbefore-f9ee1` (dry run), then with `--apply`.
- [ ] **Check**: sign in on the page, see the sections with `config/runtime`'s values and, under
  each, what the page may change ("On this page: …") and the command for more; save one ordinary
  change (Taste quiz: hide one option) and undo it, then one sensitive change within the page's
  range (e.g. Reports > Days a report is kept, 60; it asks for `CHANGE` and a sign-in from the
  last 5 minutes) and reset it; try one outside it (e.g. Recipes a day, 5000) and see `WEB_LIMIT`
  and the refusal alert; see the alert emails and the `ownerSettingChanges/` records. On a phone,
  check a section with the menu table scrolls sideways.
- [ ] Check the page's envelope fits how you work (functions/README.md "The console's envelope":
  e.g. the spend ceilings at most 2.5 × their defaults on the page; the live base ceiling of USD 5
  can only be lowered there), and whether any of the read-only fields (the raw prices) should
  become editable with the command.

### 13a. A new provider's API key (Mistral, Black Forest Labs) **(owner; Black Forest Labs' grant done, Mistral's not)**

The keys are in Secret Manager already (`MISTRAL_API_KEY`, `BFL_API_KEY`); access is granted on
`BFL_API_KEY` only (2026-11-18).
The functions read a provider's key at run time, only while a provider using it is selected
(functions/README.md "Adding a provider", "Secrets"); a switch option using it shows "not ready"
until the service account can read it. When the adapter is merged and before switching to it:

- [x] **(done 2026-11-18, agent)** Grant `recipe-functions@bestbefore-f9ee1.iam.gserviceaccount.com`
  **Secret Manager Secret Accessor on `BFL_API_KEY` only** (no role on the project, no other secret):
  `gcloud secrets add-iam-policy-binding BFL_API_KEY --member serviceAccount:recipe-functions@bestbefore-f9ee1.iam.gserviceaccount.com --role roles/secretmanager.secretAccessor --project bestbefore-f9ee1`.
- [ ] The same grant on `MISTRAL_API_KEY` only, when the Mistral adapter is merged:
  `gcloud secrets add-iam-policy-binding MISTRAL_API_KEY --member serviceAccount:recipe-functions@bestbefore-f9ee1.iam.gserviceaccount.com --role roles/secretmanager.secretAccessor --project bestbefore-f9ee1`.
- [ ] Mistral: the privacy policy's processor list names it (and its EU endpoint), then the
  adapter's `privacy.approved` is set to `true` in code. Until both, its options are listed but
  not switchable.
- [x] **(done 2026-11-18, owner's decision)** Black Forest Labs: `privacy.approved: true` in
  `bflImage` without waiting for the policy text, because no user data goes to the picture model:
  it gets the fixed template around the brief of a generated recipe, which is not user data
  (functions/README.md "What a new provider means for privacy"). The drafted policy wording stays
  in docs/privacy-policy-changes.md, "Pending".
- [ ] Optional, for a prepaid account: `config/runtime.providerBalanceAlerts`, e.g. `{bfl: 500}`
  (its own unit), so `provider_balance_low` warns before calls fail as out of credit. BFL's unit is
  credits, 1 credit = USD 0.01: 500 warns under USD 5, about 550 FLUX 3 Image Klein pictures. Set
  it below what BFL's own auto top-up keeps (its dashboard, API > Credits; minimum trigger USD 5)
  if that is on. The probe reads the key, so it needs the grant above.
- [x] **(done 2026-11-18, agent)** Apply the new alert policies (`python3 functions/alerts/alerts.py --project bestbefore-f9ee1 --apply`,
  step 9): out of credit, balance low, pictures failing broadly.
- [x] **(done 2026-11-18, agent)** **Black Forest Labs (FLUX pictures, option `bfl/flux-3-image-klein`)**: the
  adapter is in (`functions/src/adapters/bfl.ts`, functions/README.md "FLUX by Black Forest Labs"),
  approved (above), its key readable (above), and while it is the selected model each attempt gets
  up to 45 s and the dish's picture 65 s (the defaults' 30 s first attempt would have abandoned
  about half of the 2026-11-17 test pictures, already charged, to Flash-Lite Image).
- [ ] **Deploy the functions** (the approval and the longer times are code):
  `cd functions && npm ci && npx firebase deploy --only functions,firestore --project bestbefore-f9ee1`.
- [ ] **Switch the pictures to FLUX** (what the `flux-eu` profile was): in the owner console
  (step 13g, the Picture model switch: `bfl/flux-3-image-klein`; the Picture fallback,
  `vertex/gemini-nano-banana-2.1` by default, may stay or be switched to Flash-Lite Image) or from a
  shell: `npm run admin -- providers set image bfl/flux-3-image-klein` (dry run), then the same
  with `--apply --project bestbefore-f9ee1`. `providers list` should show it ready first. Then
  watch `image_call` `durationMs` and `image_fallback` with reason `TIMEOUT` (functions/README.md
  "FLUX by Black Forest Labs"); the way back is `providers set image vertex/gemini-3.1-flash-lite-image`.

### 13c. Model lifecycle alerts (functions/README.md "Model lifecycle") **(owner, after the next deploy)**

The scheduled `checkModelLifecycle` (daily 06:23 UTC) warns before a model in use is retired. It
needs no new role: its Vertex AI probe is `countTokens` (free, within Vertex AI User), the OpenAI
probe reads the key only once that grant exists, and its marks are in Firestore (`modelLifecycle/`).

- [ ] Deploy the functions (the deploy creates the Cloud Scheduler job
  `firebase-schedule-checkModelLifecycle-europe-west1`); run it once with
  `gcloud scheduler jobs run firebase-schedule-checkModelLifecycle-europe-west1 --project=bestbefore-f9ee1 --location=europe-west1`
  and look for `model_lifecycle_checked` in the logs (`probeFailed` should be 0 for the Vertex models;
  the OpenAI one fails quietly until its key is granted).
- [ ] Apply the three new alert policies, "Recipe: model retirement near", "Recipe: model not
  served", "Recipe: model lifecycle table stale" (`functions/alerts/policies/model-retirement.json`,
  `model-unavailable.json`, `model-lifecycle-stale.json`):
  `python3 functions/alerts/alerts.py --project bestbefore-f9ee1` (dry run), then
  `python3 functions/alerts/alerts.py --project bestbefore-f9ee1 --apply`. (A first `--apply` on
  2026-11-18 created none of the three: Cloud Monitoring refused `model-lifecycle-stale`'s severity
  `INFO`, which it doesn't have, and the script then stopped. The policy is `WARNING` now, and an
  apply goes on past a failure; re-run it.)
- [x] **(done 2026-11-18, agent, owner's request "openai should use newer model")** `gpt-image-1-mini`
  (then profile `openai`, listed only) is shut down by OpenAI on 2026-12-01: OpenAI's picture
  option is now `gpt-image-2.6-flare`, one of the two replacements OpenAI names, with no retirement
  announced (functions/README.md "Recipe pictures", "OpenAI's model"; re-read 2026-11-19). Not
  tried live: OpenAI stays unapproved and its key ungranted, so the option stays listed only.
- [ ] Monthly (and when "model lifecycle table stale" arrives): the review in functions/README.md
  "Model lifecycle": re-read each entry's source page, update `retiresOn` / `checkedOn` in
  `functions/src/providers/lifecycle.ts`, deploy.


### 13d. Weekly prompt review (functions/README.md "Weekly prompt review") **(owner, after the next deploy)**

The scheduled `weeklyPromptReview` (Mondays 03:41 UTC) reads the past week's output judge answers
and stores advice on the prompts in `promptReviews/{week}`, shown on the owner console's card
"Weekly prompt review" and by `npm run admin -- judge review`. **Advice only: it never changes a
prompt.** On by default (`config/runtime.promptReviewEnabled`); it does nothing useful until
`outputJudge` is `shadow` or `enforce` (fewer than 20 answers in a week: no model call, cost 0).
No new role, API or vendor: Vertex AI (Vertex AI User, as the judge) and Firestore.

- [ ] Deploy the functions and `firestore` (`functions/.env.bestbefore-f9ee1` already has
  `PROMPT_REVIEW_MODEL=gemini-3.8-flash`): the deploy creates the Cloud Scheduler job
  `firebase-schedule-weeklyPromptReview-europe-west1` (the project's sixth: USD 0.10 a month beyond
  the three free per billing account) and the TTL policy on `promptReviews.expiresAt` (365 days);
  check `gcloud firestore fields ttls list` shows it ACTIVE.
- [ ] Apply the new alert policy "Recipe: weekly prompt review failed"
  (`functions/alerts/policies/prompt-review-failed.json`, severity WARNING):
  `python3 functions/alerts/alerts.py --project bestbefore-f9ee1` (dry run), then `--apply`.
- [ ] Optional, to see one now: `npm run admin -- judge review --run --project bestbefore-f9ee1` (dry
  run: the counts and the worst case), then with `--apply` and
  `VERTEX_TOKEN=$(gcloud auth print-access-token)`; or
  `gcloud scheduler jobs run firebase-schedule-weeklyPromptReview-europe-west1 --project=bestbefore-f9ee1 --location=europe-west1`.
- Decide: whether to keep the judged text (`outputJudgeKeepText`, off; the privacy policy first):
  without it the review sees the judge's codes and notes only, which is cheaper and less specific.


### 13e. Weekly app report by email (functions/README.md "Weekly app report") **(owner, after the next deploy)**

The same Monday job (`weeklyPromptReview`, no new scheduler job) also stores last week's figures
in `weeklyReports/{week}` (sales, estimated net revenue and margin, model spend split text /
pictures / second check / prompt review, recipes and menus, free recipes and their cost, refunds,
ceiling and cap days, reports) with the changes against the week before. It is emailed to the owner
as a real email (step 13f). No model call; Firestore reads only (well under USD 0.001 a week). On by
default (`config/runtime.weeklyReportEnabled`). Independent of the prompt review: it runs with the
review off or failing, and the other way round.

- [ ] Optional, to see one now: `npm run admin -- report weekly --run --project bestbefore-f9ee1`
  (dry run: prints the report), then with `--apply` to store it (storing sends no email; step 13f
  sends it).
- To stop the report (and its email): the toggle "Make and email the weekly report" on the owner
  console's card "Weekly report".
- Decide: whether `netMicroUsdPerCredit` (USD 0.12 a recipe, the conservative net after store
  fees and VAT) is still right: the report's revenue and margin are that figure times recipes sold.

### 13f. Report emails through your Gmail (functions/README.md "Report emails") **(owner, before the next deploy)**

Both weekly reports (the app report and the prompt review) are sent by the Monday job itself as
real emails, from your Gmail address to the same address, over SMTP with a Gmail **app password**.
Each is sent once. Alerts stay Cloud Monitoring alerts. The function `weeklyPromptReview` now
declares two Secret Manager secrets, and **a function that declares a secret cannot be deployed
until the secret exists**, so do steps 1 to 3 before the next `firebase deploy`. The address is a
secret too (the repository is public): never put it in a file, a commit or a command line.

1. [ ] **An app password** (needs 2-Step Verification on the Google account): Google Account >
   Security > 2-Step Verification (turn it on if it is off), then
   <https://myaccount.google.com/apppasswords>, name it "BestBefore reports", Create. Google shows
   16 letters once; keep the window open for step 2. (Changing the account's password, or turning
   2-Step Verification off, revokes it: the next Monday then alerts `AUTH_FAILED`.)
2. [ ] **The two secrets**, typed at a prompt that does not echo, so the values are neither on the
   screen nor in the shell's history (`read` and `printf` are shell built-ins, so the value is not
   in the process list either):

   ```bash
   read -rs REPORT_SECRET_VALUE   # type your Gmail address, then Enter (nothing is shown)
   printf '%s' "$REPORT_SECRET_VALUE" | gcloud secrets create REPORT_EMAIL --project=bestbefore-f9ee1 --replication-policy=automatic --data-file=-
   read -rs REPORT_SECRET_VALUE   # paste the 16-letter app password (spaces or not), then Enter
   printf '%s' "$REPORT_SECRET_VALUE" | gcloud secrets create REPORT_SMTP_PASSWORD --project=bestbefore-f9ee1 --replication-policy=automatic --data-file=-
   unset REPORT_SECRET_VALUE
   ```

   (`npx firebase functions:secrets:set REPORT_EMAIL --project bestbefore-f9ee1` asks for the value
   without echoing it, and works as well.) A new app password later:
   `gcloud secrets versions add REPORT_SMTP_PASSWORD --project=bestbefore-f9ee1 --data-file=-` the same
   way, then redeploy `weeklyPromptReview` (Firebase pins the version at deploy).
3. [ ] **The grant**: the scheduled function runs as `recipe-functions@bestbefore-f9ee1.iam.gserviceaccount.com`
   (`FUNCTIONS_SERVICE_ACCOUNT`, set for every function by `setGlobalOptions` in `functions/src/index.ts`).
   `firebase deploy` usually grants it access to the secrets a function declares; grant it anyway, on
   these two secrets only:

   ```bash
   for s in REPORT_EMAIL REPORT_SMTP_PASSWORD; do
     gcloud secrets add-iam-policy-binding "$s" --member=serviceAccount:recipe-functions@bestbefore-f9ee1.iam.gserviceaccount.com --role=roles/secretmanager.secretAccessor --project=bestbefore-f9ee1
   done
   ```
4. [ ] **Deploy** (`cd functions && npm ci && npx firebase deploy --only functions,firestore --project bestbefore-f9ee1`).
5. [ ] **Retire the old report alert** and add the two new ones, after the deploy (until then the
   old function still mails the report through the old policy):
   `python3 functions/alerts/alerts.py --project bestbefore-f9ee1` (dry run: it should plan
   `create policy 'Recipe: weekly app report failed'` and `create policy 'Recipe: report email not
   sent'`, and, if the old policy was ever applied (step 13e of before), `delete policy 'Recipe:
   weekly app report' (retired)`), then the same with `--apply`. The old policy is deleted by name (`RETIRED_POLICIES` in `alerts.py`); deleting
   its file alone would have left it emailing. Afterwards Monitoring > Alerting > Policies should
   list no "Recipe: weekly app report" (the two new names end in "failed" and "not sent").
6. [ ] **A test email with made-up figures** (reads and writes no report), from `functions/`:

   ```bash
   npm run admin -- report email-test --to-self /tmp/bestbefore-test.html   # preview only: the two emails as files
   REPORT_EMAIL=$(gcloud secrets versions access latest --secret=REPORT_EMAIL --project=bestbefore-f9ee1) \
   REPORT_SMTP_PASSWORD=$(gcloud secrets versions access latest --secret=REPORT_SMTP_PASSWORD --project=bestbefore-f9ee1) \
     npm run admin -- report email-test --apply --project bestbefore-f9ee1   # sends two "[test] …" emails
   ```

   This checks the app password and the look (open them on the phone, in dark mode too), but runs on
   your machine, not in Cloud Functions. The first real send from Cloud Functions is the next
   Monday's run; to have it now, run the job by hand:
   `gcloud scheduler jobs run firebase-schedule-weeklyPromptReview-europe-west1 --project=bestbefore-f9ee1 --location=europe-west1`.
   That leaves the stored report and review alone (both are done for last week), and emails last
   week's reports if they were not emailed yet, once each (it writes their `email` field; a second
   run sends nothing).
- If an email was not sent ("Recipe: report email not sent"): fix the reason it names, then
  `npm run admin -- report weekly --send --apply --week <week> --project bestbefore-f9ee1` (or
  `judge review --send --apply`) with the two variables set as in step 6. Without `--apply` it
  only shows the state; `--to-self <file.html>` writes the email to a file.

### A model is being retired (runbook)

When "Recipe: model retirement near" or "Recipe: model not served" names a model in use (`npm run
admin -- models --probe --project bestbefore-f9ee1` shows them all, with roles and days left):

1. **Pick the replacement**: the provider's lifecycle page usually names one (the "Replacement"
   column on Vertex AI, "Recommended replacement" at OpenAI). Prefer a GA model with a fixed date
   far out over a short-term one.
2. **Price it**: add its price to the adapter's table (`prices` for text, `usdPerImage` and
   `tokenPrices` for pictures) from the provider's pricing page, and its entry to
   `MODEL_LIFECYCLE` (`retiresOn`, `source`, `checkedOn`): the tests refuse a model without either.
3. **Try it**: priced in its adapter, it is offered by the switches by itself; the red-team run and a few real recipes
   or pictures on the emulators and then live (functions/README.md "Replaying a prompt against the
   live model", "The red-team run").
4. **Switch**: deploy, then `npm run admin -- providers set <switch> <provider/model> --apply --project bestbefore-f9ee1`
   or the owner console (the fallback first, if the new model is to be tried there); for the recipe text's default, `GEMINI_MODEL` in `functions/.env.bestbefore-f9ee1`
   (and `GEMINI_JUDGE_MODEL` if it names the old model) and a deploy.
5. **Update the table**: the old model's entry stays while any price table or default names it; remove
   it with the last one. Past the date, `model_unavailable` follows within a day if anything still
   calls it.

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

### Vertex AI data caching **(off since 2026-11-15)**

By default Google's Gemini models cache request inputs and outputs for up to 24 hours (in memory,
and at rest when memory is full). That would make "used only for this request and not stored
afterwards" in the allergen consent text and the privacy policy untrue, so caching is disabled for
the whole project (the setting applies to every region):

```bash
TOKEN=$(gcloud auth print-access-token --account=<owner> )
URL=https://us-central1-aiplatform.googleapis.com/v1/projects/bestbefore-f9ee1/cacheConfig
curl -s -H "Authorization: Bearer $TOKEN" "$URL"            # read: expect "disableCache": true
curl -s -X PATCH -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" "$URL" \
  -d '{"name":"projects/bestbefore-f9ee1/cacheConfig","disableCache":true}'
```

Needs `roles/aiplatform.admin`. Source: <https://docs.cloud.google.com/vertex-ai/generative-ai/docs/data-governance>.
Check it again after any change of project, and before relying on the "not stored" wording. What
it does not cover: Google "may log prompts to detect potential abuse" under the Cloud terms, with
no opt-out for Google's own models. Never turn on request-response logging, Grounding with Google
Search or Maps, or session resumption for this project without revisiting the consent text.
