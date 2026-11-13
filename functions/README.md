# Recipe backend (Cloud Functions)

Paid server-generated recipes: credits bought as consumables on Google Play and the App Store, one
credit per recipe, three per 3-day menu. TypeScript, Cloud Functions 2nd gen, Node.js 24, Firebase
project `bestbefore-f9ee1`, region `europe-west1`.

Nothing here is used by anyone who never opens the paid path: the apps create no Auth user and make
no call until a purchase starts. The client wire contract is in [CONTRACT.md](CONTRACT.md).

## Layout

| path | what |
|---|---|
| `src/contract.ts` | Wire types, request validation, LLM-output validation |
| `src/core/purchases.ts` | `verifyPurchase`, the grant transaction, Play consume, `applyRefund` |
| `src/core/generation.ts` | `generateRecipe`: reserve, call the LLM, settle |
| `src/core/sweeper.ts` | Stale reservations and Play consume retries |
| `src/core/notifications.ts` | Play RTDN, Voided Purchases poll, App Store Server Notifications v2, budget kill switch |
| `src/core/account.ts` | `getBalance`, `mergeBalance`, `deleteAccount` |
| `src/ports.ts` | `PlayVerifier`, `AppStoreVerifier`, `RecipeGenerator`, `AppleTokenRevoker` interfaces |
| `src/adapters/` | Production implementations (Play Developer API, App Store Server Library, Gemini on Vertex AI, Sign in with Apple) and the prompt |
| `src/emulatorFakes.ts` | Fake stores and LLM, only inside the Functions emulator with `EMULATOR_FAKES=true` |
| `src/index.ts` | Triggers, params, secrets |
| `certs/AppleRootCA-G3.cer` | Apple's public root CA (SHA-256 `63:34:3A:BF:…:91:79`, checked by a test) |
| `../firestore.rules` | Denies every client read and write; only the Admin SDK touches Firestore |
| `../firestore.indexes.json` | Sweeper index and the TTL policy on `generations.expiresAt` |

### Functions

| name | trigger | does |
|---|---|---|
| `verifyPurchase`, `generateRecipe`, `getBalance`, `mergeBalance`, `deleteAccount` | callable, App Check enforced | See CONTRACT.md |
| `sweepReservations` | every 5 min | Refunds reservations still pending 60 s after their deadline; retries Play consumes |
| `pollPlayVoidedPurchases` | daily 04:00 UTC | Voided Purchases API, last 3 days, for RTDNs that never arrived |
| `playNotifications` | Pub/Sub topic `PLAY_RTDN_TOPIC` | Play voided-purchase RTDNs (full and quantity-based partial refunds) |
| `appStoreNotifications` | HTTPS (public) | App Store Server Notifications v2: `REFUND`, `REVOKE`, `REFUND_REVERSED`, `CONSUMPTION_REQUEST`; signature verified |
| `budgetKillSwitch` | Pub/Sub topic `BUDGET_ALERT_TOPIC` | Sets `config/runtime.generationEnabled = false` when a budget notification reports cost ≥ budget × `budgetKillFraction` |

## How the money works

- The only way a balance goes up is a store-verified purchase whose `purchases/{id}` doc does not
  exist yet (`play_<sha256(token)>`, `appstore_<env>_<transactionId>`). Those docs are never
  deleted; account deletion only nulls their `uid`.
- `generateRecipe` reserves the cost (balance −1 or −3, `inFlight` +1, a `pending` generation doc
  with a deadline) in one transaction, calls the model, then settles in a second transaction
  conditioned on the doc still being `pending`. Success keeps the charge; failure refunds.
  Whoever loses the condition (a late live call vs. the sweeper) discards its result.
- Failures caused by the infrastructure (timeout, 5xx, quota) always refund. Failures caused by
  the input (safety block, unusable output) refund at most 3 times per user per UTC day.
- Refunds are state transitions on the purchase doc: `refundedCredits` moves to the store's value
  and the holder's balance moves by the difference, once. Events older than the last one applied
  are ignored. The balance may go negative. A refund for a purchase never submitted creates a
  `refunded` doc so it can't be redeemed later. Refunds follow `mergedInto` to the account that now
  holds the credits.

## Firestore

Server-only. Documents:

- `users/{uid}`: `balance`, `inFlight`, `purchasedCredits`, `consumedCredits`, `refundedCredits`,
  rate windows (`minuteWindow`, `dayWindow`, `verifyWindow`), `contentRefunds`, and tombstones
  `deletedAt` + `forfeitedBalance` or `mergedInto` + `mergedAt`.
- `users/{uid}/generations/{requestId}`: `state` (pending | done | failed), `kind`, `courses`,
  `cost`, `createdAt`, `deadlineAt`, `charged`, `failureClass`, `failureReason`, `recipe` / `menu`,
  `expiresAt` (terminal states only; TTL deletes the doc 7 days later).
- `purchases/{id}`: `store`, `uid`, `env`, `productId`, `quantity`, `credits`, `refundedCredits`,
  `state` (granted | consumed | refunded), `grantedAt`, `refundEventAt`, and for Play
  `purchaseToken` + `consumePending` until consumed, `consumedAt`, `orderId`.
- `stats/{yyyymmdd}`: `generations`, `generationUnits` (menus count 3), `sandboxGrants`.
- `config/runtime`: see below.

### `config/runtime`

Create it in the console (Firestore > `config` > `runtime`). Every field is optional; a missing or
wrongly typed field uses the default.

| field | type | default | meaning |
|---|---|---|---|
| `generationEnabled` | bool | `true` | Kill switch for new generations. The budget alert sets it to `false`; only you set it back. |
| `allowSandbox` | bool | `false` | Grant credits for Play licence-tester and App Store sandbox/TestFlight purchases. Turn on for App Review and your own testing, off otherwise. |
| `sandboxDailyCap` | number | `20` | Sandbox purchases that may grant credits per UTC day, across all users. Over it: `RETRY`. |
| `dailyCap` | number | `1000` | Credits' worth of generations per UTC day across all users (a menu counts 3). |
| `perUserPerMinute` | number | `3` | Generations a user may start per minute. |
| `perUserPerDay` | number | `40` | Generations a user may start per UTC day. |
| `verifyPerMinute` | number | `10` | `verifyPurchase` calls per user per minute. |
| `budgetKillFraction` | number | `1` | The budget alert flips the kill switch at cost ≥ budget × this. |

## Local development

Needs Node.js 24 and Java 21+ (the Firestore emulator). The `firebase` CLI is a dev dependency of
`functions/`, so run every `npx firebase` command in this README from `functions/` (after
`npm ci`); it finds `../firebase.json` and `../.firebaserc` from there, no global install needed.

```bash
cd functions
npm ci
npm run lint
npm run typecheck
npm test          # starts the Firestore + Auth emulators (project demo-foodtracker) and runs Vitest
```

The tests use fakes for both stores and the model (`test/helpers.ts`), except
`test/appstoreAdapter.test.ts`, which runs the real App Store verifier against a throwaway
certificate chain made with `openssl`. The `demo-` project id means the emulators never reach a
real Firebase project.

### Running the functions for the apps

```bash
cd functions
cat > .env.local <<'EOF'
EMULATOR_FAKES=true
APPLE_APP_ID=1
APPSTORE_KEY_ID=x
APPSTORE_ISSUER_ID=x
APPLE_TEAM_ID=x
SIWA_KEY_ID=x
EOF
printf 'APPSTORE_PRIVATE_KEY=x\nSIWA_PRIVATE_KEY=x\n' > .secret.local
npm run build
npx firebase emulators:start --only functions,auth,firestore --project demo-foodtracker
```

(`.env.local` and `.secret.local` are git-ignored. `npm run build` again after every change: the
emulator runs `lib/`.)

With `EMULATOR_FAKES=true` the emulator uses `src/emulatorFakes.ts`: any Play token starting with
`fake-` is a purchased `recipes_10` (`fake-recipes_50-…` for another pack), App Store JWS are
decoded without verification (Xcode StoreKit transactions count as sandbox), and the model returns
a canned recipe after 1.5 s; an ingredient named `fail infra` or `fail content` makes it fail with
that class. Without `EMULATOR_FAKES` the emulator runs the real adapters with your local Google
credentials, which do call the real Play Developer API and Vertex AI.

In the emulator App Check tokens are decoded but not verified, so any well-formed token works, and
a missing one is still rejected. Point the clients at the emulators
(`useEmulator("10.0.2.2", 5001)` on the Android emulator, `127.0.0.1` on the iOS simulator, plus
the Auth emulator on 9099) and use the App Check debug provider.

## Parameters and secrets

Asked for by `firebase deploy` the first time (and saved to `functions/.env.bestbefore-f9ee1`,
which holds no secrets and may be committed), or set there beforehand:

| param | default | what |
|---|---|---|
| `FUNCTIONS_SERVICE_ACCOUNT` | `recipe-functions@` | Service account the functions run as (`name@` = this project) |
| `GEMINI_MODEL` | `gemini-3.8-flash` | Vertex AI model id. Any Flash-tier Gemini; `gemini-2.x` models get thinking budget 0 |
| `GEMINI_THINKING_LEVEL` | `LOW` | Thinking level for Gemini 3+ (`MINIMAL` where the model supports it; 3.8 Flash does not) |
| `VERTEX_LOCATION` | `global` | Vertex AI location. The default is `global` by the owner's decision; set a region only if the model is not offered in `global` |
| `APPLE_APP_ID` | none | Numeric App Store app id (App Store Connect > App Information > Apple ID) |
| `APPSTORE_KEY_ID`, `APPSTORE_ISSUER_ID` | none | App Store Server API key id and issuer id |
| `APPLE_TEAM_ID`, `SIWA_KEY_ID` | none | Team id and Sign in with Apple key id, for token revocation |
| `PLAY_RTDN_TOPIC` | `play-rtdn` | Pub/Sub topic Play publishes RTDNs to |
| `BUDGET_ALERT_TOPIC` | `budget-alerts` | Pub/Sub topic the GCP budget publishes to |

Secrets (Secret Manager, never in the repo), from `functions/`:

```bash
cd functions
npx firebase functions:secrets:set APPSTORE_PRIVATE_KEY < AuthKey_XXXXXXXXXX.p8   # In-App Purchase key
npx firebase functions:secrets:set SIWA_PRIVATE_KEY < AuthKey_YYYYYYYYYY.p8       # Sign in with Apple key
```

## One-time setup (owner)

1. **Blaze plan** on `bestbefore-f9ee1`, and a **budget** (Billing > Budgets & alerts) with
   "Connect a Pub/Sub topic" set to `budget-alerts` (create the topic first). Without that topic
   connection the alert only emails you; the topic is what flips the kill switch.
2. **Firestore**: create the `(default)` database in Native mode (a European location, e.g.
   `eur3`), then `cd functions && npx firebase deploy --only firestore --project bestbefore-f9ee1`
   (rules, sweeper index and the TTL policy).
   Create `config/runtime` (above).
3. **Auth**: enable the Anonymous, Google and Apple providers. Leave Identity Platform's automatic
   clean-up of anonymous users **off**: it would delete paying anonymous buyers.
4. **App Check**: register the Android app with Play Integrity and the iOS app with App Attest
   (DeviceCheck as fallback if wanted). Do not turn on console enforcement for other products;
   the callables enforce it themselves. Debug tokens stay in the console and on your machine, never
   in the repo or CI logs.
5. **APIs**: enable Vertex AI API, Google Play Android Developer API, Cloud Pub/Sub, Cloud
   Scheduler, Secret Manager, Cloud Build, Artifact Registry, Eventarc, Cloud Run.
6. **Service account** `recipe-functions@bestbefore-f9ee1.iam.gserviceaccount.com` with only:
   Cloud Datastore User, Vertex AI User, Firebase Authentication Admin, Secret Manager Secret
   Accessor (on the two secrets), Logs Writer, and, because the scheduled and Pub/Sub triggers
   invoke as this account, Cloud Run Invoker and Eventarc Event Receiver. Add "Firebase App Check Token Verifier" if `CONSUME_APP_CHECK_TOKENS` is turned on. Let the
   deploying account act as it (Service Account User).
7. **Play Console**: create consumables `recipes_10`, `recipes_50`, `recipes_100` (multi-quantity
   allowed if you want it). Users and permissions > invite the service account's email with
   "View financial data" and "Manage orders and subscriptions" for the app (can take up to a day
   to work). Monetization setup > Real-time developer notifications: topic
   `projects/bestbefore-f9ee1/topics/play-rtdn`, notification content including **one-time
   products / voided purchases**; grant `google-play-developer-notifications@system.gserviceaccount.com`
   Pub/Sub Publisher on that topic. Add licence testers for sandbox testing.
8. **App Store Connect**: create the three consumables; sign the Paid Apps agreement and banking /
   tax forms. Users and Access > Integrations > In-App Purchase: generate a key (`.p8`, its key id,
   the issuer id) and store it as `APPSTORE_PRIVATE_KEY`. App Information > App Store Server
   Notifications: Version 2, production and sandbox URL
   `https://europe-west1-bestbefore-f9ee1.cloudfunctions.net/appStoreNotifications` (after the first
   deploy; check the URL `firebase deploy` prints). Use "Request a Test Notification" to check it.
9. **Sign in with Apple key** (Certificates, Identifiers & Profiles > Keys, Sign in with Apple
   enabled for `com.foodbest.ios`) as `SIWA_PRIVATE_KEY`, with its key id and the team id.
10. Deploy: `cd functions && npm ci && npx firebase deploy --only functions,firestore --project bestbefore-f9ee1`
   (lint and build run first as predeploy steps).
11. Update the privacy policy, App Privacy label and Play Data safety form: purchases, a user id,
    ingredient names sent for generation, email when signed in.

## Deploying from CI (not set up)

CI only lints and tests. A deploy job on pushes to `main` that touch `functions/**` would need:
Workload Identity Federation from GitHub to a deployer service account (no JSON key in secrets),
with Cloud Functions Admin, Cloud Run Admin, Service Account User on `recipe-functions@`, Secret
Manager Viewer, Firebase Rules Admin, Cloud Datastore Index Admin, Cloud Scheduler Admin, Pub/Sub
Editor and Artifact Registry Writer; the committed `functions/.env.bestbefore-f9ee1` so deploy
does not prompt; and, from `functions/` after `npm ci`, `npx firebase deploy --only functions,firestore --project bestbefore-f9ee1 --non-interactive --force`
(`--force` because a removed function would otherwise block on a prompt).
