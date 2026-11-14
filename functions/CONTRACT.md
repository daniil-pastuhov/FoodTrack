# Recipe backend wire contract

What the Android and iOS clients send to and get from the Cloud Functions in this directory. The
source of truth is `src/contract.ts`; this file must change with it.

## Calling

- All seven are Firebase **callable** functions in region **`europe-west1`**
  (`Firebase.functions("europe-west1")` / `Functions.functions(region: "europe-west1")`).
- Every call needs a signed-in Firebase Auth user (anonymous counts) and a valid **App Check**
  token. A call without either fails with `unauthenticated`.
- App Check replay protection is **on** for `generateRecipe`, `verifyPurchase`, `mergeBalance`,
  `deleteAccount` and `registerAppleSignIn` (`CONSUME_APP_CHECK_TOKENS` in `src/constants.ts`): each call must carry a
  fresh **limited-use** token (`limitedUseAppCheckTokens = true` in the Kotlin `getHttpsCallable`
  options, `HTTPSCallableOptions(requireLimitedUseAppCheckTokens: true)` in Swift). A reused token
  fails with `unauthenticated` / `APP_CHECK_REPLAYED`; the SDKs fetch a new limited-use token per
  call, so a plain retry works.
- `getGeneration` and `getBalance` enforce App Check but do **not** consume the token: call them
  with an ordinary (not limited-use) token, so polling does not spend attestations (Play
  Integrity has a daily quota). **Poll with `getGeneration`, not by re-sending `generateRecipe`.**
- Every callable is also throttled per user, per IP (IPv6: per /64) and per server instance
  before it touches the database (`RATE_LIMITED`); normal use never comes near it. Poll no more
  often than every 3 seconds.
- Each function runs a small, fixed number of server instances. When they are all busy, a call
  can fail with `resource-exhausted` (HTTP 429) **without** a `details.reason`: nothing was
  charged; retry after a few seconds (for `generateRecipe`, with the same `requestId`). For
  `generateRecipe` that means five generations (from five different accounts) are already
  running service-wide; a sixth caller gets the 429 until one finishes (up to ~40 s for a recipe,
  ~2 min for a menu). The other callables run one instance each.
- JSON field names are camelCase, exactly as below. Unknown request fields are ignored.

## Costs

Every response that carries `balance` (`getBalance`, `verifyPurchase`, `generateRecipe`,
`mergeBalance`) also carries the price table in force:

```json
"costs": { "recipe": 1, "menu2": 4, "menu3": 5 }
```

| key | what | default |
|---|---|---|
| `recipe` | one recipe | 1 credit |
| `menu2` | 3-day menu, 2 courses (`kind: "menu", courses: 2`) | 4 credits |
| `menu3` | 3-day menu, 3 courses (`kind: "menu", courses: 3`) | 5 credits |

The server alone decides the cost and may change the table without a client release. Clients must
not hard-code prices: show the latest `costs` they received (cache it with the balance; with none
cached yet, call `getBalance` first). A cached table is for display only; the server's own check
(`INSUFFICIENT_CREDITS`) is what decides. The server charges what the table said when the request
was reserved, even if the table changes while it runs.

## Errors

Failures are `HttpsError`s. `details.reason` is a stable machine-readable string; branch on it,
not on `message` (English, for logs).

| code | details.reason | meaning / client action |
|---|---|---|
| `invalid-argument` | `INVALID_REQUEST` | Bad input. A client bug; do not retry unchanged. When a pantry or allergen name was refused, `details` also has `field` (e.g. `"ingredients[3].name"`, `"allergens[0]"`) and `rule` (see "Name rules"); nothing was charged. |
| `failed-precondition` | `INSUFFICIENT_CREDITS` | Balance below the cost (see `costs`). Show the pack sheet. |
| `failed-precondition` | `BUSY` | This account already has a generation in flight (`generateRecipe`, `mergeBalance` of that source, `deleteAccount`). Wait for it; retry later. |
| `unavailable` | `GENERATION_DISABLED` | Generation is paused: the kill switch is on, or today's spending ceiling for the whole service is reached. The ceiling counts generations still running at their worst case, so this can also last only until other users' menus finish (a minute or two). Show "temporarily unavailable, try later"; nothing was charged. |
| `failed-precondition` | `ACCOUNT_CLOSED` | Account deleted or merged away. Sign out / start anonymous again. |
| `resource-exhausted` | `RATE_LIMITED` | Too many calls in a short time (per minute). Back off and retry after a minute. |
| `resource-exhausted` | `USER_DAILY_LIMIT` | This account reached its daily generation limit (number of generations, estimated usage, or too many requests refused today as prompt injection: see "Name rules"). Try again tomorrow (UTC); nothing was charged. |
| `resource-exhausted` | `INFRA_FAILURE_LIMIT` | Generation failed for technical reasons too many times today for this account (each of those was refunded). Further generation is refused until the next UTC day; nothing was charged. Show "having trouble generating right now, try again tomorrow". |
| `resource-exhausted` | `DAILY_CAP` | Global daily limit reached. Try tomorrow; nothing was charged. |
| `failed-precondition` | `REQUEST_ID_REUSED` | This `requestId` was already used for a request with different inputs (ingredients, settings, kind, courses, allergens or `today`). A client bug: a changed request needs a new `requestId`. Nothing was charged. |
| `unauthenticated` | `APP_CHECK_REPLAYED` | The App Check token was already used (`generateRecipe`, `verifyPurchase`, `mergeBalance`, `deleteAccount`, `registerAppleSignIn`). Retry; the SDK fetches a new limited-use token. |
| `failed-precondition` | `RECENT_SIGN_IN_REQUIRED` | `deleteAccount` needs a sign-in in the last 5 minutes. Re-authenticate, retry. |
| `failed-precondition` / `permission-denied` | `MERGE_NOT_ALLOWED` | `mergeBalance` refused (see below). |
| `not-found` | `UNKNOWN_REQUEST` | `getGeneration`: no generation with this `requestId` for this account (never started, or older than the idempotency window below). Re-send `generateRecipe` with the same `requestId` and the same inputs. |
| `permission-denied` | `STORE_MISMATCH` | `verifyPurchase`: `store: "play"` from an app other than the Android app, or `store: "appstore"` from an app other than the iOS app (decided by the App Check app id). A client bug; nothing was recorded. |
| `failed-precondition` | `PURCHASE_ACCOUNT_MISMATCH` | `verifyPurchase`: the store reports a purchase account token (see "Purchase account token") that is neither this account's nor that of an account merged into it. Nothing was recorded: do **not** finish the transaction; it can still be redeemed by the account that bought it. |
| `failed-precondition` | `ALLERGEN_CONSENT_REQUIRED` | `generateRecipe`: a non-empty `allergens` without `"allergenConsent": true` (see "Allergen consent"). Checked before anything else about the allergens; nothing was read, stored or charged. Show the consent step, then send a **new** request. |
| `failed-precondition` | `APPLE_NOT_LINKED` | `registerAppleSignIn`: the account has no Sign in with Apple linked. Ignore. |
| `failed-precondition` | `APPLE_CODE_REJECTED` | `registerAppleSignIn`: Apple refused the code (expired after five minutes, already used, or not from this app). Ignore; the next Apple sign-in tries again. |
| `unavailable` | `APPLE_UNAVAILABLE` | `registerAppleSignIn`: Apple or the key service could not be reached; nothing was stored. Ignore; the next Apple sign-in tries again. |
| `resource-exhausted` | (none) | All server instances busy (HTTP 429). Nothing was charged; retry after a few seconds. |
| `internal` | (none) | Server bug or outage. Safe to retry with the same `requestId`. |
| `unauthenticated` | (none) | No Auth user or no valid App Check token. Builds that cannot attest hide the paid route. |

Network errors and `internal` on `generateRecipe` are safe to retry **with the same `requestId`
and the same request body** while the generation document exists: the server never charges twice
or calls the model twice for one id, and a `requestId` is bound to the inputs it was first used
with (`REQUEST_ID_REUSED` otherwise).

**Idempotency window.** A finished generation is kept for 7 days after it finished
(`GENERATION_TTL_MS`), then Firestore's TTL deletes it at some point after that (Google says
typically within a day; there is no upper bound guarantee). Within 7 days of finishing, a retry
returns the stored result. After that it may be gone: a retry with the same id is then a **new**
generation and is charged again, and `getGeneration` answers `UNKNOWN_REQUEST`. A pending
generation is kept until it settles (at most its deadline plus the sweeper's 5-minute period).
Never reuse a `requestId` for a new attempt.

## Account flag `purchasesBlocked`

Every response that carries `balance` (`getBalance`, `verifyPurchase`, `generateRecipe`,
`getGeneration`, `mergeBalance`) may also carry `"purchasesBlocked": true`. It is **absent**
otherwise (never `false`). When present: hide buying (the pack sheet and buy buttons); keep
everything else working, including spending the remaining balance. It is set for accounts whose
buyer got store refunds of credits they had already used, more than the server allows. Purchases
that still go through are credited normally.

## Types

### RecipeConfig

| field | type | values |
|---|---|---|
| `difficulty` | string | `easy` \| `medium` \| `ambitious` |
| `detail` | string | `brief` \| `standard` \| `stepByStep` |
| `servings` | int | 1–12 |
| `maxMinutes` | int or null | `15` \| `30` \| `60` \| `null` (no limit) |
| `mealType` | string | `any` \| `breakfast` \| `lunch` \| `dinner` \| `snack` \| `dessert` |
| `diet` | string | `none` \| `vegetarian` \| `vegan` \| `pescatarian` \| `glutenFree` \| `dairyFree` |
| `language` | string | `en` \| `nb` \| `ru` \| `de` \| `es` \| `fr` \| `it` \| `pl` \| `sv` \| `uk` |

### Ingredient (request)

| field | type | rule |
|---|---|---|
| `name` | string | A food name under the "Name rules" below. |
| `expiresOn` | string or null | `YYYY-MM-DD`, or `null` for an item without a date |
| `quantity` | int or null | 0–9999, or `null` |

`allergens` is a list of at most 20 strings under the same "Name rules" as `name`.

#### Name rules

Names are accepted in **any script**: products scanned from Open Food Facts come back with their
local names (Greek, Hebrew, Arabic, Chinese, Japanese, Korean, Thai, Georgian, Devanagari...).
The ten recipe languages are only the language the answer is written in.

The server normalises every name and then checks it (`src/core/inputSafety.ts`). Normalisation:
Unicode NFKC (fullwidth letters and digits become plain ones, `½` becomes `1/2`, ligatures split;
note that NFKC also splits a few letters such as Thai `ำ` into two code points that render the
same); `’ ‘ ʼ ´` and the backtick become `'`; dashes and minus signs become `-`; the Arabic percent
sign `٪` and decimal separator `٫` become `%` and `.`; invisible characters (zero-width spaces and
joiners, bidirectional controls such as RLO and isolates, soft hyphen, BOM, tag characters,
variation selectors) are removed; tabs, line breaks and every other space become one space; runs
of spaces and of hyphens collapse to one; leading and trailing spaces are trimmed. Right-to-left
letters are ordinary letters: only the direction *controls* are removed. Then the name must be:

- 1 to 40 **code points** (Unicode scalars, not UTF-16 units: `𠮷` counts once), at most **8
  words** (split on spaces), and every hyphen-separated part of a word at most **28 code points**,
  except a part containing a letter of a script written without spaces (Han, Hiragana, Katakana,
  Thai, Lao, Khmer, Myanmar), which is limited only by the 40;
- made only of: letters (`\p{L}`) and combining marks (`\p{M}`) of any script, decimal digits
  (`\p{Nd}`) of any script (`٣`, `५`, `১২` stay as they are; mixed digit scripts are allowed),
  space, `-`, and these in these positions only: `'` after a letter or mark and before a letter
  (`Jerry's`, `צ'יפס`), `%` after a digit or a digit and a space (`15%`, `18 %`), `,` `.` `/`
  between two digits (`0,5`, `1.5`, `1/2`), `.` after a letter or mark when a space or digit
  follows (`Dr. Oetker`, `n.5`), `&` between letters or spaces, never first or last (`M&S`,
  `Ben & Jerry's`). Nothing else: no emoji or symbols, no other numbers (`௰`), no quotes,
  brackets, braces, `:`, `#`, `<`, `>`, backslash, middle dots (`・`, `·`) or other punctuation.
- combining marks only on a letter: at most **4** on one letter (Indic, Thai, Hebrew with niqqud
  and Arabic with harakat stack them), at most **1** on a Latin, Cyrillic or Greek letter (NFKC
  composes what those languages write), never the same mark twice in a row, and marks at most
  **60 %** of the letters and marks together; anything else is "Zalgo" text (`MARKS`).
- not "suspicious": no hyphen part with letters of more than one of the look-alike scripts
  **Latin, Cyrillic and Greek** (`Pаsta` with a Cyrillic `а`, `Mιlk` with a Greek iota); other
  mixing is fine (`可口可乐Zero`, `牛奶 1L`, Japanese kanji with kana, Korean with Hanja, Hebrew or
  Arabic next to Latin units); no encoded-looking word (a hex run of 12+, three or more capitals
  inside a mixed-case word, many letter/digit switches); no word repeated three times, no
  character five times in a row, no four single letters in a row (`i g n o r e`; a single
  Chinese, Japanese, Korean or Thai character is a word, not a letter, and does not count); none
  of a short list of words about the model rather than food (`ignore`, `instructions`, `prompt`,
  `system`, `assistant`, `json`, `forget`... in the ten app languages only, also when spelled with
  Cyrillic or Greek look-alike letters or digits).

A refused name fails the whole request with `INVALID_REQUEST`, `details.field` and `details.rule`
(`TYPE`, `EMPTY`, `TOO_LONG`, `CHARACTERS`, `WORDS`, `WORD_LENGTH`, or a suspicious rule: `MARKS`,
`MIXED_SCRIPT`, `ENCODED`, `REPEATED`, `SPACED_LETTERS`, `INSTRUCTION`; `SCRIPT` is no longer
produced). Nothing is charged. Suspicious refusals, and answers the server refuses as suspicious
(see "Failures and refunds"), count per account per UTC day; at
`config/runtime.rejectedPromptsPerDay` (default 5) further generation fails with
`USER_DAILY_LIMIT` until the next UTC day.

**Client cleaning.** Both apps clean names to these rules before sending, so an honest name is
never refused for its characters or shape (Android `shared/.../RecipeWireName.kt`, iOS
`RecipeWire.cleanName`): normalise as above; keep letters, marks (within the limits above),
digits, space and hyphen, and the punctuation where it is allowed; every other character becomes a
space; a part mixing Latin, Cyrillic and Greek is folded to the script most of its letters are in
when every other letter has a look-alike there (`Мilk` → `Milk`), otherwise dropped; drop a part
over 28 code points (outside the unspaced scripts), keep the first 8 words, cut to 40 code points
at a word end when there is one; send nothing when nothing is left. The table
`test/fixtures/names.json` (real product names in 25 languages and adversarial inputs, with the
cleaned output and the server's verdicts) is what the server, Android and iOS are all tested
against; a change to the rule changes all three and the table together.

Cleaning does not rewrite names the server refuses as suspicious for meta words, encoding or
repetition. If the server still refuses an ingredient (`details.field` names it) the client leaves
that item out and retries with a **new** `requestId`, telling the user. An allergen is never
shortened or dropped: if cleaning would remove any letter or digit of it, or the server refuses
it, the request is not sent and the user is told.

Allergen enforcement in the answer (see "Failures and refunds") matches the ten app languages'
word lists; an allergen written in another script can only be matched **literally** (on the
folded, normalised text; as a substring for Chinese, Japanese, Korean and Thai), so a translation
of it in the answer is not caught unless the word lists have it.

### Recipe (response)

```json
{
  "title": "string",
  "summary": "string",
  "minutes": 25,
  "servings": 2,
  "difficulty": "easy | medium | ambitious",
  "ingredients": [{ "amount": "200 g", "name": "pasta", "fromPantry": true }],
  "steps": ["string", "..."],
  "tip": "string (optional, absent when none)"
}
```

`amount` may be an empty string (e.g. "salt to taste" in `name`). `fromPantry` is decided by the
server (the model names which pantry item an ingredient is; the server checks it exists), and
`servings` and `difficulty` are always the requested values. Every string is plain text, at most:
title 100, summary 400, amount 40, ingredient name 80, step 600, tip 300 characters; 1–40
ingredients; steps 1–7 (`brief`), 2–10 (`standard`), 2–18 (`stepByStep`). No line breaks, control
or bidirectional characters, links, e-mail addresses, phone numbers, HTML or markdown. Clients
still render every field as plain text (never as markdown or HTML).

### Menu (response)

```json
{
  "days": [
    {
      "date": "YYYY-MM-DD",
      "uses": ["pantry item names used that day, exactly as sent in the request"],
      "courses": [{ "course": "starter | main | dessert", "recipe": { "...Recipe" } }]
    }
  ]
}
```

Always exactly 3 days: `today`, `today + 1`, `today + 2`, in order. `courses: 2` gives
`starter, main`; `courses: 3` gives `starter, main, dessert`, always in that order. A model
answer with a missing day or course is never returned; it is a failed generation (refunded under
the rule in "Failures and refunds").

## Callables

### `generateRecipe`

Request:

```json
{
  "requestId": "UUID (v1-v8), new per attempt, reused for retries of that attempt",
  "kind": "recipe | menu",
  "courses": 2,
  "config": { "...RecipeConfig" },
  "ingredients": [{ "name": "Pasta", "expiresOn": "2026-11-20", "quantity": 1 }],
  "allergens": ["peanut"],
  "allergenConsent": true,
  "today": "YYYY-MM-DD (device local date; must be within a day of the server's UTC date)"
}
```

`allergenConsent` (boolean): **required, and `true`, whenever `allergens` is not empty**;
absent, `null` or anything with an empty list otherwise. See "Allergen consent" below.

`courses` is required for `menu` and must be absent or null for `recipe`. 1 to 30 ingredients.
Cost: `costs.recipe`, `costs.menu2` or `costs.menu3` (see "Costs"; by default 1, 4, 5).

Response:

```json
{ "status": "DONE | IN_PROGRESS | FAILED", "recipe": {}, "menu": {}, "balance": 9, "charged": true,
  "costs": { "recipe": 1, "menu2": 4, "menu3": 5 } }
```

| status | meaning | `charged` | client action |
|---|---|---|---|
| `DONE` | `recipe` (kind recipe) or `menu` (kind menu) is present | `true` | Show and save it. |
| `IN_PROGRESS` | Same `requestId` is still generating (another call is running) | `true` (held; if it then fails, refunded or not by "Failures and refunds") | Poll `getGeneration` with the same `requestId` every few seconds (at least 3 s apart). |
| `FAILED` | Generation failed | `false` when refunded; `true` when the failure was charged (see below) | `charged` false: "failed, not charged". `charged` true: "failed; this request could not be completed and was charged" (see below for the wording). A new attempt needs a **new** `requestId`. |

**Abandoning a request does not cancel it.** If the user changes the form while a request is held,
the server keeps generating: the old request finishes and is charged if it succeeds (its result
stays retrievable with `getGeneration` under the old `requestId` for the idempotency window), and
a new request from the same account gets `BUSY` until it does (at most ~40 s for a recipe, ~2 min
for a menu). Clients should either wait for the held request or show its result. Abandoning never
counts as a technical failure. Only a server invocation that dies before answering is settled by
the server's sweeper, and that is always refunded and counts against neither the daily
technical-failure ceiling nor the timeout allowance below, so editing the form can never lock a
user out for the day.

`balance` is the balance after this call. A recipe takes up to ~40 s and a menu up to ~2 min;
set the callable timeout to at least 70 s (recipe) / 190 s (menu). If the call is cut off
(network, app backgrounded) or answers `IN_PROGRESS`, poll `getGeneration` with the same
`requestId` until it is `DONE` or `FAILED`; re-sending `generateRecipe` with the same id and body
is also accepted (it never charges twice within the idempotency window) but spends a limited-use App Check token per call.

#### Allergen consent

The allergen list is data concerning health (GDPR Art. 9) and is sent only with the person's
explicit consent (Art. 9(2)(a)). Both apps show one consent step before the first server request
that would carry allergens: what is sent (the allergen list, with the ticked items and the
preferences), why (so the recipe avoids them), to whom (our server and Google's model, which may
process it outside the EU), that it is not stored after the request, and two choices (agree and
continue, or cancel, with a hint that the free routes send nothing to us). The answer is recorded
on the phone with its time and the wording's version (Android `AllergenConsent.kt`, iOS
`AllergenConsent` in `RecipeWire.swift`); the step is shown again only when the wording's version
changes, or after a refusal once the allergen list was emptied and set again. Recipe settings
has a switch to withdraw it. Without consent (refused or withdrawn) the server route is blocked
while allergens are set: allergens are never left out instead, since a recipe that ignores one
could be unsafe. No allergens: no step and no flag.

The server cannot see that consent. It refuses a request with a non-empty `allergens` unless it
carries `"allergenConsent": true` (`failed-precondition` / `ALLERGEN_CONSENT_REQUIRED`), so an
old or modified client cannot skip the step silently. The check runs before the allergen names
are validated, and before anything is reserved, stored or charged. The flag is not one of the
inputs a `requestId` is bound to (`REQUEST_ID_REUSED` ignores it). The allergens are not stored
after the request: only a hash of the whole request is kept with the generation, for retries.

The phone's own model and the shared prompt are unaffected: nothing goes to our server (the
shared prompt includes the allergens in text the person sends elsewhere themselves).

#### Failures and refunds

Two kinds of failure, decided by the server; `charged` tells the client which way it went.

- **Technical failures** are refunded (`charged: false`), with one limit. Errors before the model
  produced anything are always refunded, and so is a request whose server invocation died before
  answering (settled by the sweeper; it counts against nothing below). Timeouts and failures in
  the middle of an answer (the model service broke off, the answer was stopped for running far too
  long) still cost the full worst case, so they are refunded **once for every 10 credits
  purchased** over the account's life (at least once when anything was bought). Past that they are
  charged (`charged: true`). Separately, an account that has 3 refunded technical failures in one
  UTC day (`config/runtime.infraRefundsPerDay`) cannot generate again until the next UTC day.
  Further calls then fail with `INFRA_FAILURE_LIMIT` before anything is charged.
- **Failures caused by the request** (the model refused the ingredients on safety grounds, ran out
  of room, or wrote an answer the server's checks refuse: malformed, too long, a link or contact
  details, markup, an excluded allergen or diet violation, something that is not food, text
  outside cooking, letters outside Latin and Cyrillic that are not a quote of one of the request's own
  names, less than 60 % of the letters in the requested language's script, or a leak of its
  instructions) are refunded within an allowance tied to what the account
  has bought: **one refunded failure for every 10 credits purchased** over the account's life
  (at least one once anything was bought; credits the store refunded do not count). The
  allowance does not reset daily, and it moves with the credits on `mergeBalance`. Past it, such
  a failure is charged (`charged: true`), because the model's work was still paid for.

Suggested wording: refunded: "Couldn't create it. You weren't charged." Charged: "Couldn't create
a recipe from these ingredients. It used N credit(s), because the attempt still cost us to run.
Try fewer or different ingredients." (N = the request's cost.) Do not promise refunds in the UI.

#### Limits

Per account: at most one generation at a time (`BUSY`), 3 per minute (`RATE_LIMITED`), 40 per
UTC day or about USD 0.45 of estimated model usage per UTC day (`USER_DAILY_LIMIT`; that is at
least two 3-course menus, or four 2-course menus, or about 20 recipes a day). For the service as a
whole a daily spending ceiling pauses generation (`GENERATION_DISABLED`). The server can change
every number; branch on the reason, not on counts.

### `getGeneration`

The polling call. App Check enforced, token **not** consumed; per-user throttled.

Request `{ "requestId": "<the id sent to generateRecipe>" }`.

Response: exactly `generateRecipe`'s response for that generation (`status`, `recipe` / `menu`,
`balance`, `charged`, `costs`, and `purchasesBlocked` when set). It never charges, never starts a
generation and never calls the model. Unknown id (never sent, another account's, or past the
idempotency window): `not-found` / `UNKNOWN_REQUEST`; then re-send `generateRecipe` with the same
`requestId` and the same inputs.

### `verifyPurchase`

Request, one of:

```json
{ "store": "play", "purchaseToken": "<Purchase.getPurchaseToken()>" }
{ "store": "appstore", "signedTransaction": "<VerificationResult<Transaction>.jwsRepresentation>", "consumptionConsent": true }
```

`consumptionConsent` (optional boolean, either store, meaningful for the App Store): `true` when
the buyer was shown, before buying, a line saying that if they ask Apple for a refund we tell Apple
how many of the recipes were used, and continued. The server records it (with the time) on that
purchase, and only for such purchases answers Apple's consumption request with the share of the
purchase's credits already used. Absent or `null`: nothing changes. `false` on a later
resubmission withdraws it. Only the account that redeemed the purchase can set it.

Play purchases are accepted only from the Android app and App Store purchases only from the iOS
app (`STORE_MISMATCH` otherwise).

Response: `{ "status": "GRANTED | ALREADY_GRANTED | REJECTED_PERMANENT | RETRY", "balance": 10, "costs": { "recipe": 1, "menu2": 4, "menu3": 5 } }`, plus `"purchasesBlocked": true` when set.

| status | client action |
|---|---|
| `GRANTED` | Credits added. Finish the transaction (iOS `transaction.finish()`). |
| `ALREADY_GRANTED` | This purchase was already counted (possibly for another account). Finish it. |
| `REJECTED_PERMANENT` | Not a valid, unrefunded purchase of ours (or a sandbox purchase while sandbox is off, or one worth more credits than the whole daily sandbox allowance). Finish it. |
| `RETRY` | Store unreachable, purchase pending, or sandbox daily cap (purchases or credits) hit. Do **not** finish; resubmit at next launch / from `Transaction.updates` / `queryPurchasesAsync`. |

- **Play**: the product id and quantity come from Google, not the client. The **server consumes**
  the purchase; the Android client must neither consume nor acknowledge it. Resubmitting a
  `GRANTED` purchase is harmless and retries a failed server-side consume.
- **App Store**: the server verifies the JWS against Apple's root, then re-fetches the transaction
  from the App Store Server API. Transactions from Xcode StoreKit configuration files (environment
  `Xcode`) are always `REJECTED_PERMANENT`; use the sandbox. Only consumables of bundle
  `com.foodbest.ios` count.
- Products: `recipes_10`, `recipes_50`, `recipes_100` = 10 / 50 / 100 credits per unit.
- Units or shares the store already reports refunded when the purchase is submitted are not
  credited (Play `refundableQuantity`, Apple `revocationPercentage`); a fully refunded one is
  `REJECTED_PERMANENT`.
- A purchase the server already settled is answered from its record without asking the store.

#### Purchase account token

Contract addition: set this at purchase so the server can tie the purchase to the account that
made it. It is **optional** (purchases without it are accepted as before), but recommended for
every new purchase.

- Value: the RFC 9562 **UUID version 5** of the signed-in Firebase uid (UTF-8 bytes of
  `FirebaseAuth.currentUser.uid`) in the namespace **`6f2b3c1e-8d4a-4b7e-9c35-2a1f0e7d5b48`**,
  written lower-case with hyphens; the same as Python's
  `uuid.uuid5(uuid.UUID("6f2b3c1e-8d4a-4b7e-9c35-2a1f0e7d5b48"), uid)`. Worked example: uid
  `test-uid-123` gives `e0612f8d-d882-5109-b5f4-6e979f3b69ce` (asserted in
  `test/hardening.test.ts`). Both stores carry the same string (Apple as a UUID, Play as text).
- Android: `BillingFlowParams.newBuilder().setObfuscatedAccountId(token)`.
- iOS: `product.purchase(options: [.appAccountToken(UUID(uuidString: token)!)])`.
- Compute it from the uid that will call `verifyPurchase` for this purchase (anonymous installs:
  the anonymous uid; it stays valid after `mergeBalance` into a signed-in account).
- The server reads the value from the store (never from the request). When present it must be the
  caller's or that of an account merged into the caller; otherwise `PURCHASE_ACCOUNT_MISMATCH`.

### `getBalance`

Request `{}`. Response `{ "balance": 0, "costs": { "recipe": 1, "menu2": 4, "menu3": 5 } }`,
plus `"purchasesBlocked": true` when set. A new, deleted or merged account reads 0. The balance can
be negative after a store refund of already-spent credits.

### `mergeBalance`

For when a signed-in account replaces an anonymous install that holds credits (when linking the
anonymous user to the provider is not possible because the credential already has an account).

Request `{ "sourceIdToken": "<ID token of the anonymous user, fetched before switching users>" }`,
called **as the signed-in (non-anonymous) user**, with a limited-use App Check token. Response
`{ "balance": 12, "moved": 7, "costs": { "recipe": 1, "menu2": 4, "menu3": 5 } }`, plus
`"purchasesBlocked": true` when set (a block on the anonymous install's buyer follows the merge).
`moved` can be negative when a store refund left the anonymous balance below zero: the debt moves
too.

Refused (`MERGE_NOT_ALLOWED`) when the caller is anonymous, the source is not anonymous, the
source is the caller, the token is invalid/expired/revoked, or the source was already merged or
deleted. `BUSY` while the source has a generation in flight. On success the anonymous Auth user is
deleted; sign it out locally.

Prefer `linkWithCredential` on the anonymous user when it succeeds: the uid, and so the balance,
stays the same and no merge is needed. Call `mergeBalance` whenever the anonymous balance is not
zero, **including when it is negative** (a store refund of used credits): the debt then moves with
the person. If a client drops an anonymous install with a negative balance without merging, the
server moves that debt to the next account that buys with the same Apple account (Apple's
`appTransactionId`) or the same linked sign-in (Play); for a Play buyer who never signed in there is
no stable buyer id, and the debt stays behind.

The signed-in account's display name and photo are removed from Firebase Authentication by the
server (see "Profile data" below).

### `deleteAccount`

Request `{ "appleAuthorizationCode": "<optional, from a fresh Sign in with Apple>" }`, with a
limited-use App Check token. Response `{ "deleted": true, "balance": 0 }`, plus
`"appleLinkRemoved": true | false` **only for an account that carried Apple** (Apple in the ID
token's linked providers, a stored Apple token, or a code sent).

- A non-anonymous user must have signed in within the last 5 minutes (`RECENT_SIGN_IN_REQUIRED`
  otherwise): re-authenticate first. For an Apple-linked account on iOS, the re-authentication's
  `ASAuthorizationAppleIDCredential.authorizationCode` (UTF-8 string) is what to send. Android has
  no Sign in with Apple, so it re-authenticates with Google and sends no code.
- Apple's link is removed from either platform, as Apple requires: the server revokes a fresh
  `appleAuthorizationCode` when one is sent (exchanged at Apple's `/auth/token`, then
  `/auth/revoke`), and otherwise, or when that fails, the refresh token it stored at the
  account's last Apple sign-in (`registerAppleSignIn`). The stored token is removed from the
  account record in the same transaction that tombstones it, whatever Apple answers.
  `appleLinkRemoved: true`: Apple confirmed a revocation. `false`: neither way worked (no stored
  token, as for an account whose last Apple sign-in predates `registerAppleSignIn`, or Apple
  refused or could not be reached). The deletion goes ahead either way, never blocked by Apple.
  Only on `false` should the client tell the person how to remove the link themselves (turn off
  Sign in with Apple for the app in the Apple Account settings); absent or `true`: say nothing
  extra.
- An anonymous user (an install that bought without signing in) deletes itself the same way,
  without re-authenticating and with no code; the apps offer it in that state too.
- Remaining credits are lost; warn first. The Auth user is deleted; sign out locally afterwards.
  Kept: the `users/{uid}` tombstone (balance 0, `forfeitedBalance`, totals, `buyerKeys`), every
  `purchases/` document (`uid` null, `formerUid` set) and `buyers/` documents. Deleted: the
  account's `generations` and its rate-limit windows. A purchase made by this uid and not yet
  verified can never be redeemed afterwards (`ACCOUNT_CLOSED` for the uid, `PURCHASE_ACCOUNT_MISMATCH`
  for any other), so clients hold deletion back until it is confirmed.
- `BUSY` while a generation is in flight on the account: wait for it to finish, then retry.

### `registerAppleSignIn`

iOS only. After every Sign in with Apple that signs in or links Apple (including the account
that takes over an install after a merge), send its authorization code, with a limited-use App
Check token:

Request `{ "appleAuthorizationCode": "<ASAuthorizationAppleIDCredential.authorizationCode, UTF-8>" }`
(1 to 4096 characters), called **as the user that now has Apple linked**. Response
`{ "registered": true }`.

- The caller must have the `apple.com` provider (from the ID token's `firebase.identities`, or
  the Auth record when the token predates the link): `APPLE_NOT_LINKED` otherwise.
- The server exchanges the code at Apple (`client_id` = the bundle id `com.foodbest.ios`) for a
  refresh token and stores it on the account, replacing an earlier one (which is not revoked).
  A code is single use and valid for five minutes: send it straight after the sign-in.
- Failures (`APPLE_CODE_REJECTED`, `APPLE_UNAVAILABLE`, offline, anything else) change nothing
  and must not be shown: the sign-in has already succeeded. The next Apple sign-in sends a new
  code. The re-authentication before `deleteAccount` sends its code with `deleteAccount` itself,
  not here.
- Never log the code.

**What is stored.** `users/{uid}.appleToken`: the refresh token, envelope-encrypted (a fresh
AES-256-GCM data key per value, wrapped by the Cloud KMS key
`projects/<project>/locations/europe-west1/keyRings/recipe/cryptoKeys/apple-refresh-token`, which
only the functions' service account may use; the uid is bound to both layers as additional
authenticated data), and the time it was stored. It is never returned to a client and never
logged. It is removed (and revoked at Apple): when the account is deleted; when Apple is unlinked
from the account (noticed on the next `getBalance`, confirmed against the Auth record); from a
merged-away anonymous source's tombstone; and when it cannot be decrypted at deletion. A
tombstoned (deleted or merged) record never holds one.

## Profile data

The server does not keep users' names or photos. On any callable (except `deleteAccount`) from a
signed-in, non-anonymous user whose ID token still carries a `name` or `picture` claim, it clears
the Auth user's `displayName` and `photoURL` (once per sign-in), and it always clears them for the
target of `mergeBalance`. Clients need do nothing; they should not rely on `displayName` /
`photoURL` (they may be present until the next token refresh, at most an hour). The iOS client may
keep blanking the name locally; it is harmless.
