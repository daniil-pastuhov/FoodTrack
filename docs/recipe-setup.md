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
- [ ] Only to use OpenAI instead (functions/README.md "Switching provider and model"): create the
  secret `npx firebase functions:secrets:set OPENAI_API_KEY`, grant
  `recipe-functions@` **Secret Manager Secret Accessor on that secret only**
  (`gcloud secrets add-iam-policy-binding OPENAI_API_KEY --member serviceAccount:recipe-functions@bestbefore-f9ee1.iam.gserviceaccount.com --role roles/secretmanager.secretAccessor --project bestbefore-f9ee1`),
  then set `config/runtime.image` to `{provider: "openai", model: "gpt-image-1-mini"}`. OpenAI then
  becomes a data processor of the picture prompts (update the privacy policy first).

### 13. Owner console: switching the picture provider (functions/README.md "The owner console")

A web page with a drop-down of the provider profiles, on Firebase Hosting
(`https://bestbefore-f9ee1.firebaseapp.com/`). Until these are done the switch works from a shell
only (`npm run admin -- providers list | use <profile> --apply --project bestbefore-f9ee1`, "Provider
profiles"), and the board shows the setup in force with a link that leads nowhere yet.

- [ ] **Register a Web app** in the Firebase project (Project settings > General > Your apps > Add
  app > Web; a nickname such as "Owner console"; Hosting can be left unticked). The page reads its
  configuration from Hosting's `/__/firebase/init.json`, which needs one. Its browser API key may be
  restricted to the HTTP referrers `https://bestbefore-f9ee1.firebaseapp.com/*` and
  `https://bestbefore-f9ee1.web.app/*` (Cloud console > APIs & Services > Credentials), keeping
  the Identity Toolkit and Token Service APIs allowed.
- [ ] **Check** Authentication > Sign-in method > Google is enabled with its Web client (it is, for
  Android), and Authentication > Settings > Authorized domains lists `bestbefore-f9ee1.firebaseapp.com`
  (there by default). Nothing to add for the page itself.
- [ ] **Have an app account with that Google account first**: sign in with it in the app (Recipe
  settings > Account, Android or iOS). The page refuses a Google account that has no Firebase
  user yet: such a sign-in would make one, which would then stop that Google account being linked
  in the app ("credential already in use"), so the page deletes it at once and says the account
  has no account with the app (if it can't, delete it in Authentication > Users).
- [ ] **Set the allowlist**: Firestore > `config` > add document `owner` with one of these
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
- [ ] **Check what the default Hosting site serves now**, since deploying Hosting replaces it:
  `npx firebase hosting:sites:list --project bestbefore-f9ee1` and
  `npx firebase hosting:channel:list --project bestbefore-f9ee1` (a release on `live` means
  something is deployed), and open `https://bestbefore-f9ee1.web.app/` and
  `https://bestbefore-f9ee1.firebaseapp.com/` (Firebase's "Site Not Found" page means unused). If
  it serves anything, stop and say so: the page then needs its own site.
- [ ] Deploy the two callables:
  `cd functions && npm ci && npx firebase deploy --only functions:getProviderProfiles,functions:setProviderProfile --project bestbefore-f9ee1`
  (the next full deploy, `--only functions,firestore`, also updates `redrawBoard`'s board with the
  "Recipe pictures" tile). Always name what to deploy with `--only`: `firebase.json` has a
  `hosting` block, so a bare `firebase deploy` would deploy Hosting too.
- [ ] **Try Hosting on a preview channel first**, which leaves the live site alone:
  `npx firebase hosting:channel:deploy owner-check --expires 1d --project bestbefore-f9ee1`, then
  on the URL it prints:
  - `curl -sI <url>/` shows the `Content-Security-Policy` (with `frame-ancestors 'none'`) and
    `Cross-Origin-Opener-Policy` headers;
  - `curl -sI <url>/__/auth/handler` and `curl -sI <url>/__/auth/iframe` show **neither** header
    (Hosting's own sign-in pages: with them the page's sign-in, and the apps' Sign in with Apple
    return to the handler, could break). If either shows them, stop and say so;
  - the page opens and its "Sign in with Google" pop-up works there (the preview channel's host
    may first need adding to Authentication > Settings > Authorized domains; remove it after).
    The profiles won't load there: the callables' CORS answers only the live site's two hosts.

  Then deploy the live site: `npx firebase deploy --only hosting --project bestbefore-f9ee1`, and
  run the same two `curl -sI` checks on `https://bestbefore-f9ee1.firebaseapp.com`.
- [ ] Apply the new alert, "Recipe: provider profile switched"
  (`functions/alerts/policies/provider-profile-changed.json`):
  `python3 functions/alerts/alerts.py --project bestbefore-f9ee1` (dry run), then with `--apply`.
- [ ] **Check**: open the page signed out of everything (a private window) and see only the
  sign-in button; sign in with another Google account and get "may not use the owner console";
  sign in with yours and see the profiles, `flash-lite` in force and `openai` not ready. Switch to
  `nano-banana` and back to `flash-lite` once, and see the alert email and the two
  `providerProfileChanges/` records. On an iPhone, Safari may ask to allow the sign-in pop-up.
- [ ] Decide whether OpenAI may ever be switched on: it stays "not ready" until the code's
  `approved` flag for it is turned on (after the privacy policy names OpenAI) and its key is set
  (step 12, last item).

### 13a. A new provider's API key (Mistral, Black Forest Labs) **(owner; not done)**

The keys are in Secret Manager already (`MISTRAL_API_KEY`, `BFL_API_KEY`) with no access granted.
The functions read a provider's key at run time, only while a provider using it is selected
(functions/README.md "Adding a provider", "Secrets"); a profile using it shows "not ready" until
the service account can read it. When the adapter is merged and before switching to it:

- [ ] Grant `recipe-functions@` **Secret Manager Secret Accessor on each secret only**:
  `gcloud secrets add-iam-policy-binding MISTRAL_API_KEY --member serviceAccount:recipe-functions@bestbefore-f9ee1.iam.gserviceaccount.com --role roles/secretmanager.secretAccessor --project bestbefore-f9ee1`
  and the same for `BFL_API_KEY`.
- [ ] The privacy policy's processor list names the provider (and where it processes: Mistral's EU
  endpoint, Black Forest Labs' region), then the adapter's `privacy.approved` is set to `true` in
  code. Until both, its profiles are listed but not switchable.
- [ ] Optional, for a prepaid account: `config/runtime.providerBalanceAlerts`, e.g. `{bfl: 500}`
  (its own unit), so `provider_balance_low` warns before calls fail as out of credit. BFL's unit is
  credits, 1 credit = USD 0.01: 500 warns under USD 5, about 550 FLUX 3 Image Klein pictures. Set
  it below what BFL's own auto top-up keeps (its dashboard, API > Credits; minimum trigger USD 5)
  if that is on. The probe reads the key, so it needs the grant above.
- [ ] Apply the new alert policies (`python3 functions/alerts/alerts.py --project bestbefore-f9ee1 --apply`,
  step 9): out of credit, balance low, pictures failing broadly.
- [ ] **Black Forest Labs (FLUX pictures, profile `flux-eu`)**: the adapter is in
  (`functions/src/adapters/bfl.ts`, functions/README.md "FLUX by Black Forest Labs"). Before the
  approval flag can be turned on: (1) the grant on `BFL_API_KEY` above, nothing else (no role on
  the project, no other secret); (2) the privacy policy names Black Forest Labs as a processor of
  the picture prompts, its EU processing, and that its terms let it keep and train on prompts and
  pictures (docs/privacy-policy-changes.md, "Pending"), merged in all eight languages; (3) only then
  `privacy.approved: true` in `bflImage` (a code change and a deploy). Also decide on its latency:
  in the test of 2026-11-17 about half of the pictures took longer than the 30 s an attempt gets,
  and each of those would be made again by Flash-Lite Image after BFL had charged for it.

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
