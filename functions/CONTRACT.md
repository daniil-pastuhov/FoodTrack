# Recipe backend wire contract

What the Android and iOS clients send to and get from the Cloud Functions in this directory. The
source of truth is `src/contract.ts`; this file must change with it.

## Calling

- All five are Firebase **callable** functions in region **`europe-west1`**
  (`Firebase.functions("europe-west1")` / `Functions.functions(region: "europe-west1")`).
- Every call needs a signed-in Firebase Auth user (anonymous counts) and a valid **App Check**
  token. A call without either fails with `unauthenticated`.
- App Check replay protection is **off** for now (`CONSUME_APP_CHECK_TOKENS` in
  `src/constants.ts`). When it is turned on, `generateRecipe` and `verifyPurchase` must be called
  with limited-use tokens: `limitedUseAppCheckTokens = true` in the Kotlin `getHttpsCallable`
  options, `HTTPSCallableOptions(requireLimitedUseAppCheckTokens: true)` in Swift. Clients can
  already do this; it does no harm while the server does not consume them.
- JSON field names are camelCase, exactly as below. Unknown request fields are ignored.

## Errors

Failures are `HttpsError`s. `details.reason` is a stable machine-readable string; branch on it,
not on `message` (English, for logs).

| code | details.reason | meaning / client action |
|---|---|---|
| `invalid-argument` | `INVALID_REQUEST` | Bad input. A client bug; do not retry unchanged. |
| `failed-precondition` | `INSUFFICIENT_CREDITS` | Balance below the cost (1 recipe, 3 menu). Show the pack sheet. |
| `failed-precondition` | `BUSY` | This account already has a generation in flight. Wait for it; retry later. |
| `unavailable` | `GENERATION_DISABLED` | Kill switch is on. Show "temporarily unavailable"; nothing was charged. |
| `failed-precondition` | `ACCOUNT_CLOSED` | Account deleted or merged away. Sign out / start anonymous again. |
| `resource-exhausted` | `RATE_LIMITED` | Too many calls in a short time. Back off. |
| `resource-exhausted` | `DAILY_CAP` | Global daily limit reached. Try tomorrow; nothing was charged. |
| `failed-precondition` | `RECENT_SIGN_IN_REQUIRED` | `deleteAccount` needs a sign-in in the last 5 minutes. Re-authenticate, retry. |
| `failed-precondition` / `permission-denied` | `MERGE_NOT_ALLOWED` | `mergeBalance` refused (see below). |
| `internal` | (none) | Server bug or outage. Safe to retry with the same `requestId`. |
| `unauthenticated` | (none) | No Auth user or no valid App Check token. Builds that cannot attest hide the paid route. |

Network errors and `internal` on `generateRecipe` are always safe to retry **with the same
`requestId`**: the server never charges twice or calls the model twice for one id.

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
| `name` | string | 1–40 characters after trimming: letters (any script), digits, space, hyphen. Nothing else (no `'`, `,`, `.`, `&`, `/`, emoji). Clients should drop or clean names that fail. |
| `expiresOn` | string or null | `YYYY-MM-DD`, or `null` for an item without a date |
| `quantity` | int or null | 0–9999, or `null` |

`allergens` is a list of at most 20 strings with the same character rules as `name`.

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

`amount` may be an empty string (e.g. "salt to taste" in `name`).

### Menu (response)

```json
{
  "days": [
    {
      "date": "YYYY-MM-DD",
      "uses": ["pantry item names used that day"],
      "courses": [{ "course": "starter | main | dessert", "recipe": { "...Recipe" } }]
    }
  ]
}
```

Always exactly 3 days: `today`, `today + 1`, `today + 2`, in order. `courses: 2` gives
`starter, main`; `courses: 3` gives `starter, main, dessert`, always in that order. A model
answer with a missing day or course is never returned; it is a failed, refunded generation.

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
  "today": "YYYY-MM-DD (device local date; must be within a day of the server's UTC date)"
}
```

`courses` is required for `menu` and must be absent or null for `recipe`. 1 to 30 ingredients.
Cost: `recipe` 1 credit, `menu` 3 credits (the server decides; the client only shows it).

Response:

```json
{ "status": "DONE | IN_PROGRESS | FAILED", "recipe": {}, "menu": {}, "balance": 9, "charged": true }
```

| status | meaning | `charged` | client action |
|---|---|---|---|
| `DONE` | `recipe` (kind recipe) or `menu` (kind menu) is present | `true` | Show and save it. |
| `IN_PROGRESS` | Same `requestId` is still generating (another call is running) | `true` (held; refunded if it fails) | Poll again with the same `requestId` after a few seconds. |
| `FAILED` | Generation failed | `false` normally; `true` only after the 3rd input-caused failure of the day | Snackbar "failed, not charged" when `charged` is false. A new attempt needs a **new** `requestId`. |

`balance` is the balance after this call. A recipe takes up to ~40 s and a menu up to ~2 min;
set the callable timeout to at least 70 s (recipe) / 190 s (menu).

### `verifyPurchase`

Request, one of:

```json
{ "store": "play", "purchaseToken": "<Purchase.getPurchaseToken()>" }
{ "store": "appstore", "signedTransaction": "<VerificationResult<Transaction>.jwsRepresentation>" }
```

Response: `{ "status": "GRANTED | ALREADY_GRANTED | REJECTED_PERMANENT | RETRY", "balance": 10 }`

| status | client action |
|---|---|
| `GRANTED` | Credits added. Finish the transaction (iOS `transaction.finish()`). |
| `ALREADY_GRANTED` | This purchase was already counted (possibly for another account). Finish it. |
| `REJECTED_PERMANENT` | Not a valid, unrefunded purchase of ours (or a sandbox purchase while sandbox is off). Finish it. |
| `RETRY` | Store unreachable, purchase pending, or sandbox daily cap hit. Do **not** finish; resubmit at next launch / from `Transaction.updates` / `queryPurchasesAsync`. |

- **Play**: the product id and quantity come from Google, not the client. The **server consumes**
  the purchase; the Android client must neither consume nor acknowledge it. Resubmitting a
  `GRANTED` purchase is harmless and retries a failed server-side consume.
- **App Store**: the server verifies the JWS against Apple's root, then re-fetches the transaction
  from the App Store Server API. Transactions from Xcode StoreKit configuration files (environment
  `Xcode`) are always `REJECTED_PERMANENT`; use the sandbox. Only consumables of bundle
  `com.foodbest.ios` count.
- Products: `recipes_10`, `recipes_50`, `recipes_100` = 10 / 50 / 100 credits per unit.

### `getBalance`

Request `{}`. Response `{ "balance": 0 }`. A new, deleted or merged account reads 0. The balance can
be negative after a store refund of already-spent credits.

### `mergeBalance`

For when a signed-in account replaces an anonymous install that holds credits (when linking the
anonymous user to the provider is not possible because the credential already has an account).

Request `{ "sourceIdToken": "<ID token of the anonymous user, fetched before switching users>" }`,
called **as the signed-in (non-anonymous) user**. Response `{ "balance": 12, "moved": 7 }`.

Refused (`MERGE_NOT_ALLOWED`) when the caller is anonymous, the source is not anonymous, the
source is the caller, the token is invalid/expired/revoked, or the source was already merged or
deleted. `BUSY` while the source has a generation in flight. On success the anonymous Auth user is
deleted; sign it out locally.

Prefer `linkWithCredential` on the anonymous user when it succeeds: the uid, and so the balance,
stays the same and no merge is needed.

### `deleteAccount`

Request `{ "appleAuthorizationCode": "<optional, from a fresh Sign in with Apple>" }`.
Response `{ "deleted": true, "balance": 0 }`.

- A non-anonymous user must have signed in within the last 5 minutes (`RECENT_SIGN_IN_REQUIRED`
  otherwise): re-authenticate first. For an Apple-linked account, the re-authentication's
  `ASAuthorizationAppleIDCredential.authorizationCode` (UTF-8 string) is what to send; the server
  exchanges and revokes it, as Apple requires. (Alternatively the iOS client may call Firebase's
  `Auth.auth().revokeToken(withAuthorizationCode:)` itself and omit the field.)
- Remaining credits are lost; warn first. The Auth user is deleted; sign out locally afterwards.
