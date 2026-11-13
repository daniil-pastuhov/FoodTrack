# Recipe backend (Cloud Functions)

Paid server-generated recipes: credits bought as consumables on Google Play and the App Store. By
default one credit per recipe, four per 3-day 2-course menu, five per 3-day 3-course menu
(`DEFAULT_COSTS`, overridable in `config/runtime.costs`). TypeScript, Cloud Functions 2nd gen, Node.js 24, Firebase
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
| `verifyPurchase`, `generateRecipe` | callable, App Check enforced and limited-use tokens consumed (replays refused) | See CONTRACT.md |
| `getBalance`, `mergeBalance`, `deleteAccount` | callable, App Check enforced | See CONTRACT.md |
| `sweepReservations` | every 5 min | Refunds reservations still pending 60 s after their deadline; retries Play consumes |
| `pollPlayVoidedPurchases` | daily 04:00 UTC | Voided Purchases API, last 3 days, for RTDNs that never arrived |
| `playNotifications` | Pub/Sub topic `PLAY_RTDN_TOPIC` | Play voided-purchase RTDNs (full and quantity-based partial refunds) |
| `appStoreNotifications` | HTTPS (public) | App Store Server Notifications v2: `REFUND`, `REVOKE`, `REFUND_REVERSED`, `CONSUMPTION_REQUEST`; signature verified |
| `budgetKillSwitch` | Pub/Sub topic `BUDGET_ALERT_TOPIC` | Sets `config/runtime.generationEnabled = false` when a budget notification reports cost ≥ budget × `budgetKillFraction` |

## How the money works

- The only way a balance goes up is a store-verified purchase whose `purchases/{id}` doc does not
  exist yet (`play_<sha256(token)>`, `appstore_<env>_<transactionId>`). Those docs are never
  deleted; account deletion only nulls their `uid`.
- `generateRecipe` reserves the cost (balance − cost, `inFlight` +1, a `pending` generation doc
  with a deadline, the cost and a hash of the inputs) and the call's worst-case LLM cost (in
  `stats/{day}.reservedMicroUsd` and the user's `spendWindow`) in one transaction, calls the model,
  then settles in a second transaction conditioned on the doc still being `pending`. Success keeps
  the charge; failure refunds by the rules below. Settle replaces the worst case by the measured
  cost. Whoever loses the condition (a late live call vs. the sweeper) discards its result.
- A `requestId` is bound to its inputs: a retry with other inputs is `REQUEST_ID_REUSED`.
- Failures caused by the infrastructure (timeout, 5xx, quota, token-ceiling abort) always refund,
  but an account with `infraRefundsPerDay` of them today is refused (`INFRA_FAILURE_LIMIT`) before
  any LLM call until the next UTC day. Failures caused by the input (safety block, MAX_TOKENS,
  unusable output) refund once per 10 credits the account has paid for over its life, net of
  store refunds (`contentRefundsUsed` counts them; minimum one once anything was paid); past that
  they are charged. Both counters move with the credits on `mergeBalance`.

### Spend control

Every LLM call is priced before it is made and paid for by a credit:

- **Worst case reserved up front.** `reservedMicroUsd = input bound × input price + 2 ×
  maxOutputTokens × output price`. The input bound is the UTF-8 byte length of the system
  instruction, prompt and schema (every token covers at least one byte). The factor 2
  (`THINKING_FACTOR`) is there because thinking tokens are reported to exceed `maxOutputTokens`
  and Gemini 3+ has no enforceable thinking-token limit: `thinking_level` is a tier, and
  `thinking_budget` is deprecated for Gemini 3+ (and was only a soft limit before).
- **Token ceiling.** The adapter streams the response and aborts once visible output plus any
  thinking count reported in a chunk's `usageMetadata` exceeds 2 × `maxOutputTokens` (an
  infrastructure failure: refunded, booked at the worst case). Thinking is not streamed, so
  thinking that happens before the first chunk is bounded only by the LLM abort time (40 s
  recipe, 120 s menu). Whether Vertex bills tokens generated after a client abort is not
  documented.
- **Real-time breaker.** Reservation refuses (`GENERATION_DISABLED`, the kill switch's reason)
  when `spentMicroUsd + reservedMicroUsd + this call's worst case` for today would exceed
  `dailySpendCapMicroUsd`, and (`USER_DAILY_LIMIT`) when the user's spend window would exceed
  `perUserDailySpendMicroUsd`. Because worst cases are reserved inside the transaction, parallel
  calls cannot overshoot. The billing-budget kill switch stays as the slow backstop.
- **Output caps** (`MAX_OUTPUT_TOKENS`): recipe 4,096; 2-course menu 14,000; 3-course menu 20,000.
  Sized from the schema: one dish at `stepByStep` (up to 15 steps), 12 servings and a language
  that tokenises badly is about 1,800 tokens of JSON; a menu is 3 days × courses dishes plus
  ~3,000 for the day wrappers and slack.
- Worst cases at the default prices: recipe ≈ USD 0.064, 2-course menu ≈ USD 0.22, 3-course
  menu ≈ USD 0.31. Typical measured costs are a fraction of that (see `stats/{day}`).
- **Usage logs.** One `llm_call` log line per call: `uidHash` (sha256 of the uid with a fixed
  prefix, not the uid), kind, courses, model, input / output / thinking tokens, finish reason,
  duration, outcome, cost, charged / refunded, reserved and estimated micro-USD. Never ingredient
  names, prompts or outputs. In Logs Explorer: `jsonPayload.message="llm_call"`.

### Function limits

- `generateRecipe`: 5 instances × concurrency 20, 1 vCPU, 512 MiB, 180 s. The other callables:
  2–3 instances, 256 MiB. `appStoreNotifications`: 2 instances, bodies over 64 KB or not JSON are
  refused before parsing, rejected-notification log lines are sampled (one a minute).
- Callables have public invoker (Firebase callables need it; Auth and App Check are checked in
  the function) and no CORS (only the mobile apps call them).
- Before any Firestore access, every callable is throttled per instance in memory: 30 calls a
  minute per uid and 120 per client IP (the last `X-Forwarded-For` entry). That bound is per
  instance, so `maxInstances` multiplies it.

### Price table

`DEFAULT_PRICES` in `src/constants.ts`: USD per 1M tokens, thinking billed as output. The default
is `gemini-3.8-flash` at the standard rate from 2027-01-01 (1.50 input / 7.50 output; the
introductory 0.75 / 3.75 until then makes the estimates conservative). When the model
(`GEMINI_MODEL`) or Google's price changes, set `config/runtime.prices` to
`{ inputUsdPerMTok, outputUsdPerMTok }` (numbers from 0.01 to 1000) from the Vertex AI pricing page,
and update `DEFAULT_PRICES` in the next deploy. A price set too low makes the breaker too lenient.
- Refunds are state transitions on the purchase doc: `refundedCredits` moves to the store's value
  and the holder's balance moves by the difference, once. Events older than the last one applied
  are ignored. The balance may go negative. A refund for a purchase never submitted creates a
  `refunded` doc so it can't be redeemed later. Refunds follow `mergedInto` to the account that now
  holds the credits.

## Firestore

Server-only. Documents:

- `users/{uid}`: `balance`, `inFlight`, `purchasedCredits`, `consumedCredits`, `refundedCredits`,
  rate windows (`minuteWindow`, `dayWindow`, `verifyWindow`), `spendWindow` (`{day, microUsd}`),
  `infraRefunds` (`{day, count}`), `contentRefundsUsed` (lifetime), and tombstones
  `deletedAt` + `forfeitedBalance` or `mergedInto` + `mergedAt`.
- `users/{uid}/generations/{requestId}`: `state` (pending | done | failed), `kind`, `courses`,
  `cost`, `inputHash`, `reservedMicroUsd`, `statsDay`, `createdAt`, `deadlineAt`, `charged`,
  `failureClass`, `failureReason`, `usage`, `actualMicroUsd`, `recipe` / `menu`, `expiresAt`
  (terminal states only; TTL deletes the doc 7 days later).
- `purchases/{id}`: `store`, `uid`, `env`, `productId`, `quantity`, `credits`, `refundedCredits`,
  `state` (granted | consumed | refunded), `grantedAt`, `refundEventAt`, and for Play
  `purchaseToken` + `consumePending` until consumed, `consumedAt`, `orderId`.
- `stats/{yyyymmdd}` (UTC): `generations`, `generationUnits` (credits reserved), `reservedMicroUsd`
  (worst cases of calls in flight), `spentMicroUsd` (estimated, measured where known), `llmCalls`,
  `inputTokens`, `outputTokens`, `thinkingTokens`, `succeeded`, `contentFailures`,
  `infraFailures`, `refundedCredits`, `unknownUsageCalls` (booked at the worst case),
  `sandboxGrants`, `sandboxCredits`. A call is booked on the day it was reserved.
- `config/runtime`: see below.

### `config/runtime`

Create it in the console (Firestore > `config` > `runtime`). Every field is optional; a missing
field uses the default. A present but invalid field never fails open: `generationEnabled` and
`allowSandbox` then read as `false`, every other field uses its default, and a warning
"config/runtime has invalid fields" names them.

| field | type | default | meaning |
|---|---|---|---|
| `generationEnabled` | bool | `true` | Kill switch for new generations. The budget alert sets it to `false`; only you set it back. |
| `allowSandbox` | bool | `false` | Grant credits for Play licence-tester and App Store sandbox/TestFlight purchases. Turn on for App Review and your own testing, off otherwise. Anyone with a TestFlight build can then get free credits, up to the two caps below. |
| `sandboxDailyCap` | integer ≥ 0 | `20` | Sandbox purchases that may grant credits per UTC day, across all users. Over it: `RETRY`. |
| `sandboxDailyCredits` | integer ≥ 0 | `200` | Credits sandbox purchases may grant per UTC day, across all users. Over it: `RETRY`; one purchase worth more than this: `REJECTED_PERMANENT`. |
| `dailyCap` | integer ≥ 0 | `1000` | Credits' worth of generations per UTC day across all users (each counts its cost). |
| `perUserPerMinute` | integer ≥ 0 | `3` | Generations a user may start per minute (`RATE_LIMITED`). |
| `perUserPerDay` | integer ≥ 0 | `40` | Generations a user may start per UTC day (`USER_DAILY_LIMIT`). |
| `verifyPerMinute` | integer ≥ 0 | `10` | `verifyPurchase` calls per user per minute. |
| `infraRefundsPerDay` | integer ≥ 0 | `3` | Refunded infrastructure failures per user per UTC day; at it, generation is refused (`INFRA_FAILURE_LIMIT`). |
| `dailySpendCapMicroUsd` | integer ≥ 0 | `2000000` | Estimated LLM spend per UTC day across all users, in micro-USD (USD 2; ~USD 60 a month, under the 1000 NOK budget). At it: `GENERATION_DISABLED`. |
| `perUserDailySpendMicroUsd` | integer ≥ 0 | `1000000` | Estimated LLM spend per user per UTC day, in micro-USD (USD 1). At it: `USER_DAILY_LIMIT`. |
| `costs` | map | `{recipe: 1, menu2: 4, menu3: 5}` | Credits per request. All three keys, integers 1–100, `recipe ≤ menu2 ≤ menu3`; otherwise the whole default table is used. Sent to clients in every response with `balance`. |
| `prices` | map | `{inputUsdPerMTok: 1.5, outputUsdPerMTok: 7.5}` | LLM price table for the estimates (see "Price table"). |
| `budgetKillFraction` | number in (0, 2] | `1` | The budget alert flips the kill switch at cost ≥ budget × this. |

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
   invoke as this account, Cloud Run Invoker and Eventarc Event Receiver; and **Firebase App Check Token Verifier** (`roles/firebaseappcheck.tokenVerifier`), which `generateRecipe` and `verifyPurchase` need to consume limited-use App Check tokens (`CONSUME_APP_CHECK_TOKENS`); without it every call to them fails. Let the
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
