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
| `src/core/buyers.ts` | FIFO attribution of spent credits to purchases, buyer keys, the refund-after-use blocklist |
| `src/core/consumption.ts` | Answers Apple's `CONSUMPTION_REQUEST` for consenting buyers |
| `src/core/generation.ts` | `generateRecipe` (reserve, call the LLM, settle) and `getGeneration` (polling) |
| `src/core/sweeper.ts` | Stale reservations, Play consume retries, owed consumption answers, negative-balance watch |
| `src/core/notifications.ts` | Play RTDN, Voided Purchases poll, App Store Server Notifications v2, budget kill switch |
| `src/core/account.ts` | `getBalance`, `mergeBalance`, `deleteAccount` |
| `src/core/guard.ts` | Pre-Firestore throttles, client address, log sampling |
| `src/ports.ts` | `PlayVerifier`, `AppStoreVerifier`, `RecipeGenerator`, `AppleTokenRevoker` interfaces |
| `src/adapters/` | Production implementations (Play Developer API, App Store Server Library, Gemini on Vertex AI, Sign in with Apple) and the prompt |
| `src/emulatorFakes.ts` | Fake stores and LLM, only inside the Functions emulator with `EMULATOR_FAKES=true` and a `demo-` project |
| `src/index.ts` | Triggers, params, secrets, per-function limits |
| `certs/AppleRootCA-G3.cer` | Apple's public root CA (SHA-256 `63:34:3A:BF:…:91:79`, checked by a test) |
| `../firestore.rules` | Denies every client read and write; only the Admin SDK touches Firestore |
| `../firestore.indexes.json` | Sweeper indexes and the TTL policy on `generations.expiresAt` |

### Functions

| name | trigger | does |
|---|---|---|
| `verifyPurchase`, `generateRecipe`, `mergeBalance`, `deleteAccount` | callable, App Check enforced and limited-use tokens consumed (replays refused) | See CONTRACT.md |
| `getBalance`, `getGeneration` | callable, App Check enforced, token not consumed | See CONTRACT.md |
| `sweepReservations` | every 5 min | Refunds reservations still pending 60 s after their deadline; retries Play consumes and owed consumption answers; watches negative balances |
| `pollPlayVoidedPurchases` | daily 04:00 UTC | Voided Purchases API, last 3 days, for RTDNs that never arrived |
| `playNotifications` | Pub/Sub topic `PLAY_RTDN_TOPIC` | Play voided-purchase RTDNs (full and quantity-based partial refunds) |
| `appStoreNotifications` | HTTPS (public) | App Store Server Notifications v2: `REFUND`, `REVOKE`, `REFUND_REVERSED`, `CONSUMPTION_REQUEST`; signature verified |
| `budgetKillSwitch` | Pub/Sub topic `BUDGET_ALERT_TOPIC` | Sets `config/runtime.generationEnabled = false` when a budget notification reports cost ≥ budget × `budgetKillFraction` |

## How the money works

- The only way a balance goes up is a store-verified purchase whose `purchases/{id}` doc does not
  exist yet (`play_<sha256(token)>`, `appstore_<env>_<transactionId>`). Those docs are never
  deleted; account deletion only nulls their `uid`.
- `verifyPurchase` checks cheapest first: input, store vs. the caller's app (App Check app id:
  Play only from the Android app, App Store only from the iOS app), the per-uid throttle, the
  App Store JWS signature (local), the purchase account token, then the local purchase doc. A
  purchase already settled is answered from the doc with no store call. Only a new one reaches
  Google or Apple.
- Purchase account token: when the store reports Play's `obfuscatedExternalAccountId` or Apple's
  `appAccountToken`, it must equal `UUIDv5(PURCHASE_TOKEN_NAMESPACE, uid)` of the caller or of an
  account merged into it (`PURCHASE_ACCOUNT_MISMATCH` otherwise; nothing recorded). Absent: as
  before. It is recorded on the purchase (`accountToken`).
- Credits the store already reports refunded at submission are not granted (Play
  `refundableQuantity`, Apple `revocationPercentage`).
- `generateRecipe` reserves the cost (balance − cost, `inFlight` +1, a `pending` generation doc
  with a deadline, the cost and a hash of the inputs) and the call's worst-case LLM cost (in
  `stats/{day}.reservedMicroUsd` and the user's `spendWindow`) in one transaction, calls the model,
  then settles in a second transaction conditioned on the doc still being `pending`. Success keeps
  the charge; failure refunds by the rules below. Settle replaces the worst case by the measured
  cost whenever usage is known. Whoever loses the condition (a late live call vs. the sweeper)
  discards its result. The reservation transaction reads the caller's user and generation docs
  first, and the shared `stats` docs only when a new reservation is about to be made.
- `getGeneration` reads the caller's user and generation docs and nothing else: no reservation,
  no LLM, no `stats`.
- A `requestId` is bound to its inputs: a retry with other inputs is `REQUEST_ID_REUSED`. The
  idempotency window is the generation doc's life: at least 7 days after it finished
  (`GENERATION_TTL_MS`), then whenever Firestore TTL deletes it.
- Failures caused by the infrastructure: with known usage (an error before the model produced
  anything) always refunded; a dead invocation settled by the sweeper always refunded and counted
  against neither ceiling below (a client cannot cause it: abandoning a call does not stop it, so
  editing the form never locks a user out); with unknown usage (timeout, mid-stream error,
  token-ceiling abort; booked at the worst case) refunded once per 10 credits paid over the
  account's life (`timeoutRefundsUsed`, `CREDITS_PER_TIMEOUT_REFUND`), then charged. Refunded infrastructure
  failures also count towards `infraRefundsPerDay`, at which the account is refused
  (`INFRA_FAILURE_LIMIT`) before any LLM call until the next UTC day. Failures caused by the input
  (safety block, MAX_TOKENS, unusable output) refund once per 10 credits the account has paid for
  over its life, net of store refunds (`contentRefundsUsed`; minimum one once anything was paid);
  past that they are charged. All these counters move with the credits on `mergeBalance`.
- Refunds are state transitions on the purchase doc: `refundedCredits` moves to the store's value
  and the holder's balance moves by the difference, once. Events older than the last one applied
  are ignored. The balance may go negative. A refund for a purchase never submitted creates a
  `refunded` doc so it can't be redeemed later. Refunds follow `mergedInto` to the account that now
  holds the credits. Apple `REFUND_REVERSED` restores the credits, except that a partial refund the
  transaction still reports (`revocationDate` with a `revocationPercentage` below 100 %) stays
  refunded.

### FIFO attribution, refund after use, and Apple consumption requests

Credits are fungible, so spend is attributed to purchases first-in-first-out: a purchase counts
as consumed to the extent the account's lifetime `consumedCredits` (including accounts merged into
it) exceed the credits granted to it before that purchase (net of refunds of those), capped at the
purchase's credits. Order: `grantedAt`, then purchase id (`fifoConsumed` in `src/core/buyers.ts`).

- **Refund after use**: a store refund (Apple or Play, full or partial) applied to a purchase
  whose refunded credits exceed its FIFO-unused credits. A refund of unused credits never counts.
  It is recorded once per purchase (`purchases.refundAfterUse`, and an `events.{purchaseId}` entry
  on each of the purchase's `buyers/{key}` docs): repeated notifications change nothing, a
  reversal withdraws the event, a later refund restores it.
- **Buyer keys** (recorded on each purchase at grant time and collected on the user doc as
  `buyerKeys`): Apple `apple_<appTransactionId>` from the verified transaction (else
  `uid_<uid>`); Play `uid_<uid>` plus `prov_<hash>` of each linked sign-in provider's user id. A
  merge moves the source's keys to the target, so a block follows merges, and a new install is
  recognised as soon as its first purchase carries a known Apple buyer key.
- **Blocking**: a buyer with `refundAbuseThreshold` (default 2) active events is blocked
  (`blocked: true`). Every response that carries `balance` then has `purchasesBlocked: true` (the
  client hides buying). A blocked buyer's genuine, unrefunded purchase is still granted (they paid)
  and logged as `purchase_by_blocked_buyer`. Generation stays governed by the balance (a negative
  balance already blocks it). **Owner override**: set `blockedOverride: true` on the buyer doc;
  then `blocked` is your decision (`false` unblocks, `true` blocks) and the policy never rewrites
  it.
- **Consumption requests** (Apple `CONSUMPTION_REQUEST`): recorded on the purchase
  (`consumptionRequestedAt`) and logged as `apple_consumption_request` every time. If the buyer
  gave `consumptionConsent` (iOS sends it), the server calls Send Consumption Information (v2,
  `PUT /inApps/v2/transactions/consumption/{transactionId}`) with `customerConsented: true`,
  `deliveryStatus: DELIVERED`, `consumptionPercentage` = FIFO-consumed share in milliunits, and
  `sampleContentProvided` (`config/runtime.consumptionSampleContentProvided`, default `true`: the
  pack sheet explains what a credit buys before purchase). No `refundPreference` is sent. The v2
  body has no account-tenure, platform or play-time fields (those were in the deprecated v1).
  Apple's deadline is 12 hours after the notification; a failed send is retried by the sweeper
  every 5 minutes until then (`consumption.deadlineAt`), then given up
  (`apple_consumption_answer_expired`). Without consent nothing is sent, as Apple requires.
  Answering is idempotent per `notificationUUID`.

- **Debt follows the buyer**: when a purchase is granted, any other open account carrying one of
  its buyer keys (other than the caller's own `uid_` key) with a negative balance and nothing in
  flight has that debt moved onto the purchasing account (`debtMovedTo` on the old one, log
  `debt_followed_buyer`), as `mergeBalance` would have. This covers an anonymous install with a
  negative balance that a client abandoned at sign-in without merging: the debt follows through
  the Apple `appTransactionId` or a linked sign-in's provider key. A Play buyer who never signed
  in has no stable key, so such a debt stays behind. A later refund of a purchase redeemed on the
  old account still debits the old account.

### Profile data (names and photos)

The owner does not want users' names or photos stored. Google sign-in always supplies them and
Firebase Authentication stores them as the user's `displayName` and `photoURL`. On every callable
except `deleteAccount`, a non-anonymous caller whose ID token still carries a `name` or `picture`
claim gets both cleared with the Admin SDK (`updateUser(uid, {displayName: null, photoURL:
null})`), at most once per sign-in (`users/{uid}.profileScrubbedAt` vs. the token's `auth_time`,
plus a per-instance memo, so there is no Auth read on ordinary calls). `mergeBalance` always
clears them for its target. Neither value is ever logged; a failure logs `profile_scrub_failed`
with the error code only.

What Firebase Authentication still holds after this (for the privacy policy):

- Top level: uid, email and whether it is verified, creation and last sign-in times, the list of
  linked providers, disabled flag, token validity time. `displayName` / `photoURL` cleared.
- `providerData` (one entry per linked provider, e.g. `google.com`, `apple.com`): the provider's
  user id and email, **and the provider's display name and photo URL**. The Admin SDK cannot edit
  these entries: `UpdateRequest` has only `displayName` / `photoURL` (top level, `null` clears),
  `providerToLink` and `providersToUnlink`
  (<https://firebase.google.com/docs/reference/admin/node/firebase-admin.auth.updaterequest>,
  <https://firebase.google.com/docs/auth/admin/manage-users>). Unlinking would sign the user out of
  that provider. Google does not document whether a later sign-in rewrites the top-level fields;
  the server assumes it may, and clears them again after any new sign-in that brings the claims
  back. So: Firebase Auth keeps the Google/Apple name and photo URL inside the provider entry for as
  long as the account exists, and the server cannot remove them short of unlinking or deleting the
  account (`deleteAccount` deletes the Auth user, and with it all of this).
- Firebase Auth's own usage logs, if enabled in Cloud Logging, are outside this code.

### Spend control

Every LLM call is priced before it is made and paid for by a credit:

- **Worst case reserved up front.** `reservedMicroUsd = input bound × input price + 2 ×
  maxOutputTokens × output price`. The input bound is the UTF-8 byte length of the system
  instruction, prompt and schema (every token covers at least one byte). The factor 2
  (`THINKING_FACTOR`) is there because thinking tokens are reported to exceed `maxOutputTokens`
  and Gemini 3+ has no enforceable thinking-token limit: `thinking_level` is a tier, and
  `thinking_budget` is deprecated for Gemini 3+ (and was only a soft limit before).
- **Measured cost booked when known.** Settle books `input × input price + (output + thinking) ×
  output price` from the response's `usageMetadata`. When usage is unknown (no `usageMetadata` in
  the stream, a timeout, a mid-stream error, a dead invocation) the reserved worst case stays
  booked; a stream without `usageMetadata` is never booked as free.
- **Token ceiling.** The adapter streams the response and aborts once visible output plus any
  thinking count reported in a chunk's `usageMetadata` exceeds 2 × `maxOutputTokens` (an
  infrastructure failure with unknown usage, booked at the worst case). Thinking is not streamed,
  so thinking that happens before the first chunk is bounded only by the LLM abort time (40 s
  recipe, 120 s menu). Whether Vertex bills tokens generated after a client abort is not
  documented.
- **Real-time breaker.** Reservation refuses (`GENERATION_DISABLED`, the kill switch's reason)
  when `spentMicroUsd + reservedMicroUsd + this call's worst case` for today would exceed the daily
  ceiling, and (`USER_DAILY_LIMIT`) when the user's spend window would exceed
  `perUserDailySpendMicroUsd`. Because worst cases are reserved inside the transaction, parallel
  calls cannot overshoot. The billing-budget kill switch stays as the slow backstop.
- **Output caps** (`MAX_OUTPUT_TOKENS`): recipe 4,096; 2-course menu 14,000; 3-course menu 20,000.
- **Usage logs.** One `llm_call` log line per call: `uidHash` (sha256 of the uid with a fixed
  prefix, not the uid), kind, courses, model, input / output / thinking tokens, finish reason,
  duration, outcome, cost, charged / refunded, reserved and estimated micro-USD. Never ingredient
  names, prompts or outputs. In Logs Explorer: `jsonPayload.message="llm_call"`.

#### Spend ceilings (defaults and arithmetic)

Worst-case reservations at the default prices (1.50 / 7.50 USD per 1M tokens), from a small
request to the largest valid one: recipe USD 0.064–0.069, 2-course menu 0.214–0.218, 3-course menu
0.304–0.308. A typical measured 3-course menu (≈3,000 input, 12,000 output, 2,000 thinking tokens)
is 3,000 × 1.5 + 14,000 × 7.5 = USD 0.11; a 2-course menu ≈ USD 0.08; a recipe ≈ USD 0.02.

- **Per user: USD 0.45 a day** (`perUserDailySpendMicroUsd: 450000`). A call starts only if
  today's booked spend + its worst case ≤ 0.45. 3-course menus: start at 0 → 0.11 → refused at
  0.22 (0.22 + 0.31 > 0.45): **two 3-course menus**, then recipes still fit (0.22 + 0.07 ≤ 0.45).
  2-course menus: 0, 0.08, 0.16, 0.24 → refused at 0.32: four. Recipes: about 19. Even when every
  call is booked at the worst case (unknown usage) a user can do one 3-course menu, two 2-course
  menus or six recipes.
- **Global ceiling = min(max, base + fraction × net value of production credits granted in the
  last 7 UTC days)**:
  - `dailySpendBaseMicroUsd: 1200000` (USD 1.20/day without sales). That is 2.7 users at their
    own ceiling, so **two accounts cannot pause generation for everyone** (2 × 0.45 = 0.90 <
    1.20), and doing it with three needs ≥ USD 1.35 a day of real model use, i.e. paid credits.
  - `netMicroUsdPerCredit: 120000` (USD 0.12): the cheapest pack (100 for USD 18.99, USD 0.19 a
    credit) after a 15 % store fee and 25 % VAT: 0.19 × 0.85 / 1.25 ≈ 0.13, rounded down.
  - `dailySpendRevenueFraction: 0.25`: a quarter of the week's estimated net revenue may go to
    model spend on top of the base. Each credit sold adds 0.25 × 0.12 = USD 0.03 to every day's
    ceiling for 7 days; it reaches the maximum at (2.00 − 1.20) / 0.03 ≈ 27 credits sold in a
    week. Refunds subtract (`stats/{day}.netCreditsGranted`, production only; sandbox never
    counts).
  - `dailySpendMaxMicroUsd: 2000000` (USD 2.00/day): **at most USD 60 a month of model spend**
    (≈ 630 NOK at 10.5 NOK/USD), whatever the sales. The ceiling is an estimate against list
    prices; settle books measured costs, so real spend is usually lower.
- The billing-budget kill switch (`budgetKillFraction` of the GCP budget) remains the backstop.

### Function limits

Every function sets `maxInstances` and `concurrency` explicitly. The default for all is 1/6 vCPU
(`cpu: "gcf_gen1"` at 256 MiB, the 1st-gen ratio) with concurrency 1 (Cloud Run requires
concurrency 1 below 1 vCPU), 1 instance:

| function | instances | per-instance throttle (calls/min let through to Firestore) | timeout |
|---|---|---|---|
| `generateRecipe` | 2 (so at most 2 generations in flight service-wide) | 60 | 180 s |
| `getGeneration` | 1 | 120 | 10 s |
| `getBalance` | 1 | 120 | 10 s |
| `verifyPurchase` | 1 | 30 | 60 s |
| `mergeBalance` | 1 | 10 | 30 s |
| `deleteAccount` | 1 | 10 | 120 s |
| `appStoreNotifications` | 1 | n/a (no Firestore before Apple's signature checks out) | 30 s |
| `playNotifications`, `budgetKillSwitch`, `sweepReservations`, `pollPlayVoidedPurchases` | 1 | n/a (Pub/Sub or scheduler; not callable from the internet) | |

Before any Firestore access, every callable is throttled per instance in memory: 30 calls a minute
per uid, 120 per client address (IPv4 address, or IPv6 /64), and the per-instance ceiling above
whatever the keys. `config/runtime` is cached in memory for 30 s per instance (a failed read fails
the call; no stale copy is used), so a flood does not also read the config; the kill switch
therefore takes up to 30 s to reach running instances (the budget function reads it fresh).

Client address: the **last** `X-Forwarded-For` entry. Google's Cloud Functions header reference
(<https://cloud.google.com/functions/docs/reference/headers>) says the first entry is "generally"
the client but does not say a client-sent header is dropped, and Cloud Run (which runs 2nd-gen
functions) is observed to keep a client-sent `X-Forwarded-For` and append the address its front
end saw (<https://discuss.google.dev/t/cloud-run-which-client-ip-can-i-safely-use-for-rate-limiting/409611>).
So the first entry is caller-controlled and the last one is not. Google does not document this
explicitly for direct `cloudfunctions.net` / `run.app` traffic; if a load balancer is ever put in
front, the right entry moves one to the left.

`appStoreNotifications` (public, unauthenticated): POST only, `application/json`, body ≤ 25 KB
and `signedPayload` ≤ 24 KB (a consumable notification is ≈ 15 KB: a 3-certificate x5c header,
≈ 5 KB base64url, plus the transaction JWS, ≈ 6.5 KB, base64url-encoded again). Rejections are
logged with fixed reason codes only, through a sampler (one line a minute per instance with a
suppressed count); payload values are never logged. Unexpected errors in callables are logged at
most 5 a minute per instance (`callable_failed`).

#### Worst-case daily bill under a sustained flood

Prices assumed (europe-west1, Tier 1, request-based billing; Firestore multi-region; check the
pricing pages before relying on them): CPU USD 0.000024 per vCPU-s, memory USD 0.0000025 per
GiB-s, USD 0.40 per million requests; Firestore USD 0.06 per 100k reads, 0.18 per 100k writes.
Free tiers ignored. One instance at 1/6 vCPU and 256 MiB busy all day: 86,400 × (0.1667 ×
0.000024 + 0.25 × 0.0000025) = **USD 0.40 a day**.

Calls a flood can push into Firestore per day = instances × per-instance throttle × 1,440:

| endpoint | calls/day max | Firestore per call | Firestore USD/day | requests USD/day | instance time USD/day | total USD/day |
|---|---|---|---|---|---|---|
| `generateRecipe` (no credits) | 2 × 60 × 1,440 = 172,800 | 2 reads | 0.21 | 0.07 | 0.80 | 1.08 |
| `getGeneration` | 172,800 | 2 reads | 0.21 | 0.07 | 0.40 | 0.68 |
| `getBalance` | 172,800 | 1 read | 0.10 | 0.07 | 0.40 | 0.57 |
| `verifyPurchase` | 43,200 | 3 reads + 1 write | 0.16 | 0.02 | 0.40 | 0.58 |
| `mergeBalance` | 14,400 | 2 reads + 2 writes | 0.07 | 0.01 | 0.40 | 0.48 |
| `deleteAccount` | 14,400 | 3 reads + 1 write | 0.05 | 0.01 | 0.40 | 0.46 |
| `appStoreNotifications` | (forged bodies never reach Firestore) | 0 | 0 | see below | 0.40 | 0.40 |
| **infrastructure total** | | | **0.80** | **0.25** | **3.20** | **4.25** |
| model spend, absolute maximum | | | | | | **2.00** |

What this means against the 1000 NOK (≈ USD 95) monthly budget:

- **Model spend** is bounded by code at USD 2.00/day (USD 60/month) and above USD 1.20/day only
  when sales fund it. Without credits nobody can make the model run.
- **Firestore + requests** under a flood that passes Auth and App Check: ≤ USD 1.05/day.
- **Instance time** is the part the code cannot bound below `maxInstances`: ≤ USD 3.20/day only
  if every public function is kept busy around the clock. Together the infrastructure worst case
  is USD 4.25/day (≈ USD 128/month): **a flood sustained for a whole month on every endpoint would
  exceed the budget on its own**; any single endpoint's worst case is ≤ USD 1.08/day (≈ USD 32 a
  month).
- **Raw junk requests** (no valid token at all) are rejected by the framework but still billed
  per request: at concurrency 1 and, say, 20 ms each, one instance serves ≈ 50 req/s = 4.3 M/day
  = USD 1.73/day in request fees per instance. Nothing in the function can bound that; only
  Cloud Armor in front of a load balancer could (not set up: about USD 18/month fixed).
- So: set a GCP budget **email** alert at 25 % and 50 % (in addition to the kill-switch topic,
  which only pauses generation), and if a flood shows up, lower the flooded function's
  `maxInstances` to 0 in the console or delete it.

### Price table

`DEFAULT_PRICES` in `src/constants.ts`: USD per 1M tokens, thinking billed as output. The default
is `gemini-3.8-flash` at the standard rate from 2027-01-01 (1.50 input / 7.50 output; the
introductory 0.75 / 3.75 until then makes the estimates conservative). When the model
(`GEMINI_MODEL`) or Google's price changes, set `config/runtime.prices` to
`{ inputUsdPerMTok, outputUsdPerMTok }` (numbers from 0.01 to 1000) from the Vertex AI pricing page,
and update `DEFAULT_PRICES` in the next deploy. A price set too low makes the breaker too lenient.

### Logs to alert on

Create log-based alerts in Cloud Logging on `jsonPayload.message` (all are structured):

| message | level | meaning |
|---|---|---|
| `refund_after_use` | error | A store refund took credits that had been spent (FIFO). `purchaseId`, `store`, `spentCreditsTaken`, `refundAfterUseCount`. |
| `buyer_blocked` | error | A buyer reached `refundAbuseThreshold`; their accounts now get `purchasesBlocked`. |
| `purchase_by_blocked_buyer` | warning | A blocked buyer bought again; credited. |
| `negative_balance_total_grew` | error | The sum of negative balances got more negative since the last sweep. |
| `play_consume_gave_up` | error | A Play purchase could not be consumed after 50 attempts; the buyer cannot buy that product again until it is consumed (Play Console). |
| `apple_consumption_request` | info | Every Apple consumption request, with `outcome` (`consented`, `not consented`, …). |
| `apple_consumption_answer_failed` / `apple_consumption_answer_expired` | warning / error | An answer to Apple failed (`reason`: `APPLE_RETRYABLE`, `APPLE_REJECTED`, `LEDGER_UNKNOWN`) or missed the 12-hour window. |
| `debt_followed_buyer` | warning | A purchase picked up another account's negative balance through a shared buyer key. |
| `profile_scrub_failed` | warning | Clearing a user's Auth name/photo failed (error code only). |
| `sweep_item_failed` | error | One item of the sweeper's queues threw (the rest still ran). |
| `callable_failed` | error | Unexpected error in a callable (sampled, 5 a minute per instance). |
| `appstore_notification_rejected` | warning | Sampled: rejected bodies on the public endpoint (fixed `outcome` code + suppressed count). |
| `Budget kill switch flipped: generation disabled` | error | The budget alert paused generation. |

## Firestore

Server-only. Documents:

- `users/{uid}`: `balance`, `inFlight`, `purchasedCredits`, `consumedCredits`, `refundedCredits`,
  rate windows (`minuteWindow`, `dayWindow`, `verifyWindow`), `spendWindow` (`{day, microUsd}`),
  `infraRefunds` (`{day, count}`), `contentRefundsUsed` and `timeoutRefundsUsed` (lifetime),
  `buyerKeys`, `profileScrubbedAt`, `debtMovedTo` + `debtMovedAt`, and tombstones `deletedAt` +
  `forfeitedBalance` or `mergedInto` + `mergedAt`.
- `users/{uid}/generations/{requestId}`: `state` (pending | done | failed), `kind`, `courses`,
  `cost`, `inputHash`, `reservedMicroUsd`, `statsDay`, `createdAt`, `deadlineAt`, `charged`,
  `failureClass`, `failureReason`, `usage`, `actualMicroUsd`, `recipe` / `menu`, `expiresAt`
  (terminal states only; TTL deletes the doc some time after it, 7 days after it finished).
- `purchases/{id}`: `store`, `uid` (null after deleteAccount, which keeps `formerUid` for the ledger), `env`, `productId`, `quantity`, `credits`, `refundedCredits`,
  `state` (granted | consumed | refunded), `grantedAt`, `refundEventAt`, `buyerKeys`,
  `accountToken`, `consumptionConsent` + `consumptionConsentAt`, `consumptionRequestedAt`,
  `consumption` (`notificationUUID`, `requestedAt`, `deadlineAt`, `attempts`, `answeredAt`,
  `consumptionPercentage`, `lastError`, `gaveUp`) + `consumptionPending`, `refundAfterUse`, and for
  Play `purchaseToken` + `consumePending` until consumed, `consumeAttempts`, `consumeGaveUpAt`,
  `consumedAt`, `orderId`.
- `buyers/{key}` (never deleted; no personal data beyond the key, which is an Apple
  `appTransactionId`, a Firebase uid, or a hash of a sign-in provider's user id): `events`
  (`{purchaseId: {at, store, spentCreditsTaken, active}}`), `refundAfterUseCount`, `blocked`,
  `blockedAt`, `blockedOverride` (owner), `createdAt`.
- `stats/{yyyymmdd}` (UTC): `generations`, `generationUnits` (credits reserved), `reservedMicroUsd`
  (worst cases of calls in flight), `spentMicroUsd` (estimated, measured where known), `llmCalls`,
  `inputTokens`, `outputTokens`, `thinkingTokens`, `succeeded`, `contentFailures`,
  `infraFailures`, `refundedCredits`, `unknownUsageCalls` (booked at the worst case),
  `netCreditsGranted` (production credits granted minus store refunds that day),
  `sandboxGrants`, `sandboxCredits`. A call is booked on the day it was reserved.
  `stats/ledger`: `negativeBalanceTotal` (the sweeper's last sum of negative balances).
- `config/runtime`: see below.

### `config/runtime`

Create it in the console (Firestore > `config` > `runtime`). Every field is optional; a missing
field uses the default. A present but invalid field never fails open: `generationEnabled`,
`allowSandbox` and `consumptionSampleContentProvided` then read as `false`, every other field uses
its default, and a warning "config/runtime has invalid fields" names them. Instances cache it for
30 s.

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
| `dailySpendBaseMicroUsd` | integer ≥ 0 | `1200000` | Daily spend ceiling without sales (USD 1.20). See "Spend ceilings". |
| `dailySpendRevenueFraction` | number in [0, 1] | `0.25` | Share of the last 7 days' estimated net revenue added to the daily ceiling. |
| `netMicroUsdPerCredit` | integer ≥ 0 | `120000` | Conservative net revenue per production credit (USD 0.12). |
| `dailySpendMaxMicroUsd` | integer ≥ 0 | `2000000` | Absolute daily ceiling (USD 2.00, USD 60 a month). At the ceiling: `GENERATION_DISABLED`. |
| `perUserDailySpendMicroUsd` | integer ≥ 0 | `450000` | Estimated LLM spend per user per UTC day (USD 0.45). At it: `USER_DAILY_LIMIT`. |
| `refundAbuseThreshold` | integer ≥ 1 | `2` | Refund-after-use events that block a buyer (`purchasesBlocked`). |
| `consumptionSampleContentProvided` | bool | `true` | Sent to Apple as `sampleContentProvided` in consumption answers. |
| `costs` | map | `{recipe: 1, menu2: 4, menu3: 5}` | Credits per request. All three keys, integers 1–100, `recipe ≤ menu2 ≤ menu3`; otherwise the whole default table is used. Sent to clients in every response with `balance`. |
| `prices` | map | `{inputUsdPerMTok: 1.5, outputUsdPerMTok: 7.5}` | LLM price table for the estimates (see "Price table"). |
| `budgetKillFraction` | number in (0, 2] | `1` | The budget alert flips the kill switch at cost ≥ budget × this. |

`dailySpendCapMicroUsd` from earlier drafts is no longer read; use the four `dailySpend*` /
`netMicroUsdPerCredit` fields.

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

With `EMULATOR_FAKES=true` and a `demo-` project id (both required, besides the emulator itself) the emulator uses `src/emulatorFakes.ts`: any Play token starting with
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
   invoke as this account, Cloud Run Invoker and Eventarc Event Receiver; and **Firebase App Check Token Verifier** (`roles/firebaseappcheck.tokenVerifier`), which `generateRecipe`, `verifyPurchase`, `mergeBalance` and `deleteAccount` need to consume limited-use App Check tokens (`CONSUME_APP_CHECK_TOKENS`); without it every call to them fails. Let the
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
