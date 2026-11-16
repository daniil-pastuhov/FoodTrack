# Recipe backend wire contract

What the Android and iOS clients send to and get from the Cloud Functions in this directory. The
source of truth is `src/contract.ts`; this file must change with it.

## Calling

- All ten are Firebase **callable** functions in region **`europe-west1`**
  (`Firebase.functions("europe-west1")` / `Functions.functions(region: "europe-west1")`).
- Every call needs a signed-in Firebase Auth user (anonymous counts) and a valid **App Check**
  token. A call without either fails with `unauthenticated`. The one exception is
  `getWelcomeOffer`: App Check only, no sign-in. It is the one call the apps make to the server
  before a sign-in or a purchase, and it carries nothing about the caller (no input; any Auth
  token sent with it is not looked at).
- App Check replay protection is **on** for `generateRecipe`, `verifyPurchase`, `mergeBalance`,
  `deleteAccount`, `registerAppleSignIn` and `claimWelcome` (`CONSUME_APP_CHECK_TOKENS` in `src/constants.ts`): each call must carry a
  fresh **limited-use** token (`limitedUseAppCheckTokens = true` in the Kotlin `getHttpsCallable`
  options, `HTTPSCallableOptions(requireLimitedUseAppCheckTokens: true)` in Swift). A reused token
  fails with `unauthenticated` / `APP_CHECK_REPLAYED`; the SDKs fetch a new limited-use token per
  call, so a plain retry works.
- `getGeneration`, `getBalance`, `getTasteQuiz` and `getWelcomeOffer` enforce App Check but do **not** consume the token: call them
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
`getGeneration`, `mergeBalance`, `claimWelcome`) also carries the price table in force:

```json
"costs": {
  "recipe": 1, "menu2": 4, "menu3": 5,
  "menuSets": {
    "1": { "1": 1, "2": 2, "3": 3 },
    "2": { "1": 2, "2": 3, "3": 4 },
    "3": { "1": 3, "2": 4, "3": 5 },
    "4": { "1": 4, "2": 5, "3": 6 }
  }
}
```

| key | what | default |
|---|---|---|
| `recipe` | one recipe | 1 credit |
| `menu2` | 3-day menu, 2 courses (`kind: "menu", courses: 2`, no `days` or `days: 3`) | 4 credits |
| `menu3` | 3-day menu, 3 courses (`kind: "menu", courses: 3`, no `days` or `days: 3`) | 5 credits |
| `menuSets` | a menu set: `menuSets["<days>"]["<courses>"]`, days `"1"` to `"4"`, courses `"1"` to `"3"`, as strings (see "Capability `acceptsMenuDays`") | days + courses − 1 credits |

`menu2` and `menu3` are always exactly `menuSets["3"]["2"]` and `menuSets["3"]["3"]`: they stay for
the clients from before menu sets, which read only them (there is no flat key for one course,
which those clients never ask for). Every response carries the whole table, all 12 cells. More
days or more courses never cost less, and no menu costs less than a recipe.

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
| `failed-precondition` | `REQUEST_ID_REUSED` | This `requestId` was already used for a request with different inputs (ingredients, settings, kind, courses, days, allergens or `today`). A client bug: a changed request needs a new `requestId`. Nothing was charged. |
| `unauthenticated` | `APP_CHECK_REPLAYED` | The App Check token was already used (`generateRecipe`, `verifyPurchase`, `mergeBalance`, `deleteAccount`, `registerAppleSignIn`, `claimWelcome`). Retry; the SDK fetches a new limited-use token. |
| `failed-precondition` | `SIGN_IN_REQUIRED` | `claimWelcome`: the account has no Google or Apple sign-in (an anonymous install). Nothing was granted or recorded; sign in first. |
| `failed-precondition` | `RECENT_SIGN_IN_REQUIRED` | `deleteAccount` needs a sign-in in the last 5 minutes. Re-authenticate, retry. |
| `failed-precondition` / `permission-denied` | `MERGE_NOT_ALLOWED` | `mergeBalance` refused (see below). |
| `not-found` | `UNKNOWN_REQUEST` | `getGeneration`: no generation with this `requestId` for this account (never started, or older than the idempotency window below). Re-send `generateRecipe` with the same `requestId` and the same inputs. |
| `permission-denied` | `STORE_MISMATCH` | `verifyPurchase`: `store: "play"` from an app other than the Android app, or `store: "appstore"` from an app other than the iOS app (decided by the App Check app id). A client bug; nothing was recorded. |
| `failed-precondition` | `PURCHASE_ACCOUNT_MISMATCH` | `verifyPurchase`: the store reports a purchase account token (see "Purchase account token") that is neither this account's nor that of an account merged into it. Nothing was recorded: do **not** finish the transaction; it can still be redeemed by the account that bought it. |
| `failed-precondition` | `ALLERGEN_CONSENT_REQUIRED` | `generateRecipe`: a non-empty `allergens` without `"allergenConsent": true` (see "Allergen consent"). Checked before anything else about the allergens; nothing was read, stored or charged. Show the consent step, then send a **new** request. |
| `failed-precondition` | `NOT_ENOUGH_ITEMS` | `generateRecipe`: the ticked items cannot make what was asked, decided before anything is reserved; nothing was charged and no model was called. Either no item is left once the allergens and every selected diet are applied (recipe or menu), or, for a menu, some day of the menu (1 to 4 days) has no item that may be planned on it (an item may go on any day when it has no date or was already past its date on `today`; otherwise only up to its date). Show "Tick a few more items" (for a menu: "...or ask for a single recipe"), keep the form; a changed request needs a new `requestId`. Clients that do not know this reason show their generic "couldn't write it, nothing was taken" message. |
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

## Capability `acceptsOptionalIngredients`

Every response that carries `balance` (`getBalance`, `verifyPurchase`, `generateRecipe`,
`getGeneration`, `mergeBalance`) from a server that reads `optionalIngredients` carries
`"acceptsOptionalIngredients": true`; a server deployed before the field existed sends nothing.
Clients send `optionalIngredients` only when the latest such response said `true` (keep the flag
with the cached balance; absent in a later response withdraws it, as after a rollback). Unknown
request fields are ignored, so an old server would accept the field and silently not use it; the
flag keeps a new client from sending pantry data a server would not read. Old clients ignore the
flag and never send the field.

## Capability `acceptsMultiChoiceConfig`

The same pattern for the multi-choice `RecipeConfig` below (a time range, several meals, several
diets): every response that carries `balance` from a server that reads it carries
`"acceptsMultiChoiceConfig": true`. Clients send the multi-choice shape only when the latest such
response said `true`, and only when the legacy shape can't say the same thing; otherwise they send
the legacy shape with the closest values (see "Legacy mapping"). A multi-choice config has no
`mealType` or `diet`, which a server from before it requires, so a rolled-back server refuses it
(`INVALID_REQUEST`, nothing charged) rather than reading part of it; the apps then stop sending it
until a response says `true` again. Old clients ignore the flag.

## Capability `acceptsDanish`

The same pattern for `config.language: "da"` (Danish, added in November 2026 with the app's Danish
translation): every response that carries `balance` from a server that writes Danish recipes
carries `"acceptsDanish": true`. Clients send `da` only when the latest such response said `true`.
A server from before it refuses `da` (`INVALID_REQUEST`, nothing charged), so without the flag the
clients send nothing and tell the person to choose another recipe language or use a free route;
they never send another language in its place. After a `da` request refused as `INVALID_REQUEST`
(a rolled-back server) they stop sending `da` until a response says `true` again. Old clients
ignore the flag and never send `da`.

## Capability `acceptsMenuDays`

Menu sets (November 2026): a menu of **1 to 4 days** (`days`) and **1 to 3 courses** (`courses`;
1 is the main course alone), priced from `costs.menuSets`. Every response that carries `balance`
from a server that reads `days` and takes `courses: 1` carries `"acceptsMenuDays": true`. Clients
send `days` only when the latest such response said `true`, and only when it is not 3 (a request
without `days` is the 3-day menu, as from every client before menu sets: same price, same answer,
the same `requestId` binding), and send `courses: 1` only after the flag too. A server from before
it ignores `days` (unknown fields are ignored), so it would write a 3-day menu at the 3-day price:
the flag keeps a new client from asking it for something else. It refuses `courses: 1`
(`INVALID_REQUEST`, nothing charged). Without the flag the apps offer only the 3-day menu of 2 or
3 courses, priced `costs.menu2` / `costs.menu3`; after a menu-set request refused as
`INVALID_REQUEST` (a rolled-back server) they stop sending `days` and `courses: 1` until a
response says `true` again. Old clients ignore the flag and `menuSets`.

## Capability `acceptsTaste`

The taste profile (November 2026, "Taste profile" below): every response that carries `balance`
from a server that has `getTasteQuiz` and reads `taste` on `generateRecipe` as a map of quiz step
ids to option ids carries `"acceptsTaste": true`. The apps hold no list of questions or options:
they fetch the quiz with `getTasteQuiz`, and do so only after a response said `true`; without the
flag they hide the Taste profile row. Clients send `taste` only when the latest such response
said `true`, and only when what they send is not empty (keep the flag with the cached balance;
absent in a later response withdraws it). Without the flag the request goes **without** `taste`:
no error, nothing blocked. A server from before it ignores the field (unknown fields are
ignored), so the flag keeps a client from saying "Uses your taste profile" when it would not be
used. Answers to steps or options the current quiz no longer has (removed, renamed to a new id,
hidden) are dropped by the server, never refused, and do not withdraw the flag: a client must
not clear it for that. A request with `taste` refused as `INVALID_REQUEST` (only a malformed
shape is) makes the apps stop sending it until a response says `true` again. Old clients ignore
the flag. A taste profile costs nothing: the price, the limits and every check are the same with
and without one.

Beside it, every response that carries `balance` also carries **`"tasteQuizVersion":
"<version>"`**: the quiz's current `version`, exactly what `getTasteQuiz` would return as
`version` now (it reflects the catalogue and `config/runtime.tasteQuiz.hidden`, and is the same
in every language). The apps compare it with the version of the quiz they cached to show that
there are new questions (the dot on Recipe settings) without fetching the quiz; when it differs
they fetch the quiz quietly in the background, at most once per version and language and only for
an account that already exists (never for someone who would see the promo), so a new question can
be noted before the quiz is opened; an open quiz is never swapped. It is opaque:
compare for equality only. A server from before it sends nothing; treat that as "unknown", not as
a change.

## Capability `acceptsWelcome`

The welcome grant (November 2026): free recipes for signing in, once per Google or Apple account
(`claimWelcome` below). Every response that carries `balance` from a server that has
`claimWelcome` carries `"acceptsWelcome": true` and, beside it, **`"welcomeRecipes": n`**: how
many free recipes a claim grants now (`config/runtime.welcome.recipes`), and `0` when the grant
is off, generation is paused or today's grants are used up. It is the same for every account (it
does not say whether this account has claimed: the answer of `claimWelcome` does). A signed-in
client that has not claimed uses it to name the number ("Get 3 free recipes"); at `0` it shows
nothing. A server from before it sends neither; treat that as no grant. Before any account
exists, an install learns the number from `getWelcomeOffer` instead; an old server answers that
call with `not-found` / unimplemented, which the apps read as `0`.

Free recipes behave exactly like recipes from a pack: same costs, limits and failure refunds,
spent from the same `balance`. They are **not purchases**: they raise no allowance of refunded
content failures, are never reported to Apple as bought or consumed (free recipes count as spent
before bought ones), never count in refund-after-use, and are lost with the account like any
unused recipes.

## Types

### RecipeConfig

Two shapes. **Legacy** (every client before November 2026, and new clients talking to a server
without `acceptsMultiChoiceConfig`):

| field | type | values |
|---|---|---|
| `difficulty` | string | `easy` \| `medium` \| `ambitious` |
| `detail` | string | `brief` \| `standard` \| `stepByStep` |
| `servings` | int | 1–12 |
| `maxMinutes` | int or null | `15` \| `30` \| `60` \| `null` (no limit) |
| `mealType` | string | `any` \| `breakfast` \| `lunch` \| `dinner` \| `snack` \| `dessert` |
| `diet` | string | `none` \| `vegetarian` \| `vegan` \| `pescatarian` \| `glutenFree` \| `dairyFree` |
| `language` | string | `en` \| `nb` \| `ru` \| `de` \| `es` \| `fr` \| `it` \| `pl` \| `sv` \| `uk` \| `da` (`da` only to a server that says `acceptsDanish`) |

`minMinutes`, `mealTypes` and `diets` are absent or `null` there.

**Multi-choice** (a config with `mealTypes` or `diets`): `difficulty`, `detail`, `servings` and
`language` as above, and

| field | type | values |
|---|---|---|
| `minMinutes` | int or null | a multiple of 5 from 10 to 120, or `null` (no lower bound) |
| `maxMinutes` | int or null | a multiple of 5 from 10 to 120, or `null` (no limit); not below `minMinutes` |
| `mealTypes` | list of strings, required | distinct values of `breakfast`, `lunch`, `dinner`, `snack`, `dessert`; `[]` is any meal |
| `diets` | list of strings, required | distinct values of `vegetarian`, `vegan`, `pescatarian`, `glutenFree`, `dairyFree`; `[]` is none |
| `mealType`, `diet` | absent or `null` | refused otherwise |

Meaning: `maxMinutes` is a limit (the response schema's `minutes.maximum` and the output check);
`minMinutes` is only a guide ("about that long or more"): nothing is refused for being quicker,
and it is not in the schema. The dish suits **any one** of `mealTypes`; for a menu only the main
courses follow it (starters and desserts ignore it), while time and diets hold for every dish.
**All** of `diets` hold at once: vegetarian and gluten-free is both; the strictest combination wins
by itself (pescatarian with vegetarian forbids fish; vegan with vegetarian is vegan), so no
combination is refused. The prompt names only the diets another selected one does not already
imply, and the answer is checked against every selected diet's word lists (`DIET`). Order in the
lists does not matter. The price does not change.

A request is bound (`REQUEST_ID_REUSED`, the stored hash) to the config's meaning, not its shape:
a legacy request hashes exactly as before the multi-choice shape existed, and a multi-choice one
that the legacy fields could say (no `minMinutes`, `maxMinutes` 15, 30, 60 or null, at most one
meal and one diet) hashes as that legacy request.

#### Legacy mapping (clients, for a server without the capability)

| selection | sent as |
|---|---|
| upper time bound | the largest of 15, 30, 60 not above it (10 → 15); none → `null` |
| lower time bound | dropped (it is a guide) |
| one meal / several or none | that meal / `any` |
| one diet / none | that diet / `none` |
| several diets, one implying the rest | that one (pescatarian + vegetarian → `vegetarian`; vegan + anything but gluten-free → `vegan`) |
| vegetarian or pescatarian with dairy-free | `vegan` (it forbids everything they forbid, and also eggs and honey) |
| gluten-free with any other diet | **not sent**: the app says the combination needs a server update and offers a single diet or the free routes; a diet is never dropped |

### Ingredient (request)

| field | type | rule |
|---|---|---|
| `name` | string | A food name under the "Name rules" below. |
| `expiresOn` | string or null | `YYYY-MM-DD`, or `null` for an item without a date |
| `quantity` | int or null | 0–9999, or `null` |

`allergens` is a list of at most 20 strings under the same "Name rules" as `name`.

#### Optional ingredients

`optionalIngredients` (`generateRecipe`, optional) is the rest of the pantry, which the recipe
**may** use in addition to `ingredients` ("Send all products" in the apps): absent or `null` is
none. Same entry shape as `ingredients`, and the list itself (at most 30 entries) and each entry's
`expiresOn` and `quantity` are checked as strictly (`INVALID_REQUEST`). Names go through the same
"Name rules", but a name the rules refuse, or one already in the request (ignoring case), is
**left out**, never refusing the request, and is not counted against the daily rejection limit. Of
what is left, entries are kept in the order sent until `ingredients` plus the kept optional items
would exceed **30 names** or **1,050 code points of names** together; an entry that does not fit
is skipped and a later, shorter one may still be kept. Clients send them soonest date first and
already capped (the shared rule in the apps), so the server's cut only matters for other clients.
The caps make the worst-case input of a request with optional items smaller than that of the
largest request without them (README "Spend ceilings"). The price does not change.

The model gets them in a second data block, labelled optional and framed as untrusted data like
the first; it must build the dish around `ingredients` and may use optional items where they help.
An ingredient of the answer that is an optional item has `fromPantry: true`, and a menu day's
`uses` may name optional items. An answer that uses no item of `ingredients` is still refused
(`NO_PANTRY`). The kept optional items are part of the inputs a `requestId` is bound to; a request
without them is bound exactly as before the field existed.

#### Name rules

Names are accepted in **any script**: products scanned from Open Food Facts come back with their
local names (Greek, Hebrew, Arabic, Chinese, Japanese, Korean, Thai, Georgian, Devanagari...).
The eleven recipe languages are only the language the answer is written in.

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
  `system`, `assistant`, `json`, `forget`... in the eleven recipe languages only, also when spelled with
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

Allergen enforcement in the answer (see "Failures and refunds") matches the recipe languages'
word lists; an allergen written in another script can only be matched **literally** (on the
folded, normalised text; as a substring for Chinese, Japanese, Korean and Thai), so a translation
of it in the answer is not caught unless the word lists have it.

### Taste profile (request)

`taste` (`generateRecipe`, optional, a recipe or a menu alike) is the person's answers to the
taste quiz (`getTasteQuiz`): a map from **step id to the chosen option ids**. There is no free
text. Absent or `null` is none.

```json
"taste": { "cuisines": ["italian", "nordic"], "heat": ["medium"], "avoid": ["olives"] }
```

1. **Shape (strict, `INVALID_REQUEST`)**: `taste` is an object of at most **16** keys, each a
   string of at most **40** characters; each value is `null` (nothing chosen), one string (read
   as a list of one; a `single` step is normally sent as a list of one) or an array of at most
   **32** strings, every string at most **40** characters. A malformed value refuses the request,
   whatever the step is.
2. **Against the quiz (lenient, never an error)**: steps and options the server's catalogue does
   not have, or that `config/runtime.tasteQuiz.hidden` hides, are **dropped** (a newer or older
   client may know ids this server does not); repeats collapse.
3. **Caps and conflicts**: the `avoid`-tone steps are cut to their `max` in catalogue order;
   then for every pair of `conflicts` with both sides chosen, the side on a `like` step is
   dropped (the `avoid` tone wins: `favourites:mushrooms` against `avoid:mushrooms`,
   `flavours:garlic` against `avoid:garlic`); then every other step is cut to its `max` in
   catalogue order. So a `multi` step that became `single` keeps the first answer in catalogue
   order, and a dropped or hidden option never uses up a cap.
4. Every list is put in catalogue order, steps in catalogue order. Nothing left is **no
   profile**: the prompt is then exactly that of a request without `taste`.

**Binding**: `taste` **as sent** is part of the inputs a `requestId` is bound to, in canonical
form (keys sorted, each list without repeats and sorted, a bare string as a list of one, empty
lists and `null` left out), not the cleaned profile. So a retry with the same `requestId` and the
same answers after the quiz or the hidden list changed is the same request (recovered as usual,
never `REQUEST_ID_REUSED`), and the same answers in another order or with repeats are the same
inputs. Absent `taste`, `{}` or only empty values bind exactly as before the field existed. Any
other difference, including answers this server drops, is another request
(`REQUEST_ID_REUSED` for the same id).

What the model gets: one block of **fixed wording** in the system instruction, built only from
the catalogue's English prompt words (each step's fixed label line and each kept option's words;
never an id or any text as sent), and only for a non-empty profile. It states the order of
precedence: the pantry items stay the core of every dish and are used up first, the allergens and
the diet always win, the time limit and the meal type stand; within those, lean towards the
`like` answers; leave out the `avoid` answers unless a ticked item is one (then use it: the
person ticked it), and leave out an optional item that is one. On every `like` step (favourites,
flavours, heat) an option the request's diet or allergens rule out (by the answer checks' own
word lists: chicken for a vegetarian, cheese for a milk allergy, garlic as a flavour for a garlic
allergy) is left out of the block. **An `avoid` answer is a
preference, not an allergen**: it is never added to `allergens`, never checked in the answer, and
never refuses an answer; the allergen and diet checks, the quality gate and every other output
check are unchanged. The price is unchanged.

What the server keeps of it: **nothing but the request hash.** As for `config`, the profile is
not written to the generation record (`users/{uid}/generations/{requestId}` holds `inputHash`, a
sha256 of the validated request, and no inputs); it is not logged either (`llm_call` carries only
`taste: true | false`, whether the request had a non-empty cleaned profile).

### Recipe (response)

```json
{
  "title": "string",
  "summary": "string",
  "minutes": 25,
  "servings": 2,
  "difficulty": "easy | medium | ambitious",
  "ingredients": [{ "amount": "200 g", "name": "pasta", "fromPantry": true, "pantryIndex": 0 },
                  { "amount": "1", "name": "onion", "fromPantry": false },
                  { "amount": "", "name": "salt", "fromPantry": false, "staple": true }],
  "steps": ["string", "..."],
  "tip": "string (optional, absent when none)"
}
```

`amount` may be an empty string (e.g. "salt to taste" in `name`). `fromPantry` is decided by the
server (the model names which pantry item an ingredient is; the server checks it exists, and an
ingredient written as only a staple, such as "water", labelled as an item whose own name has no
staple word is delivered with `fromPantry: false` and `staple: true`).
`pantryIndex` (integer, optional) is present exactly when the ingredient is one of the request's
`ingredients`: its 0-based index there. `optionalIndex` (integer, optional) is present exactly
when it is one of the request's `optionalIngredients`: its 0-based index in that list **as
sent** (entries the server left out still count). With them a client can look up the item and
its `expiresOn` (for example to warn that a dish uses an item past its date). Both are absent on
answers generated before they existed; read them as optional. An ingredient with
`fromPantry: false` is something the cook supplies: a staple, or one of the few common extras the
model may add when the pantry items alone do not make a proper dish (it may need buying).
`staple` (optional, `true` or absent) marks such an ingredient whose name is only a staple (water,
ice, oil, salt, pepper, sugar, vinegar, spices; the server's `STAPLE_WORDS`): the apps leave plain
water out of "Add to shopping list" and list the other staples last. Absent on answers from before
it; then every `fromPantry: false` ingredient is listed. `minutes` is never above the request's
`maxMinutes`. Every
recipe, and every day of a menu, uses at least one item of `ingredients` (see "Failures and refunds"), and
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

`uses` is the model's own list of the items a day uses, mapped to their names; it can be
incomplete. To know exactly which items a dish uses, read the ingredients' `pantryIndex` and
`optionalIndex`.

Always exactly the requested number of days (`days`, 3 when absent): `today`, `today + 1`, ...,
`today + days − 1`, in order. `courses: 1` gives `main`; `courses: 2` gives `starter, main`;
`courses: 3` gives `starter, main, dessert`, always in that order. A 1-day 1-course menu is a
single dish, still sent as a `menu` with one day and one course. A model answer with a missing
or extra day or course is never returned; it is a failed generation (refunded under the rule in
"Failures and refunds"). For a menu of one course, the meal types (`mealTypes` / `mealType`)
apply to that course, as they apply to the main course of any menu. Two dishes of one menu never
have the same title (a 1-day 1-course menu has only one).

## Callables

### `generateRecipe`

Request:

```json
{
  "requestId": "UUID (v1-v8), new per attempt, reused for retries of that attempt",
  "kind": "recipe | menu",
  "courses": 2,
  "days": 4,
  "config": { "...RecipeConfig" },
  "ingredients": [{ "name": "Pasta", "expiresOn": "2026-11-20", "quantity": 1 }],
  "optionalIngredients": [{ "name": "Basil", "expiresOn": null, "quantity": 1 }],
  "allergens": ["peanut"],
  "allergenConsent": true,
  "taste": { "cuisines": ["italian"], "heat": ["medium"], "avoid": ["olives"] },
  "today": "YYYY-MM-DD (device local date; must be within a day of the server's UTC date)"
}
```

`allergenConsent` (boolean): **required, and `true`, whenever `allergens` is not empty**;
absent, `null` or anything with an empty list otherwise. See "Allergen consent" below.

`courses` (1, 2 or 3; 1 only to a server that says `acceptsMenuDays`) is required for `menu` and
must be absent or null for `recipe`. `days` (1, 2, 3 or 4, optional) is allowed only for `menu`:
absent or null is 3, anything else, or `days` on a recipe, is `INVALID_REQUEST`; send it only to a
server that says `acceptsMenuDays`, and only when it is not 3 (see that section). `days` is part
of the inputs a `requestId` is bound to; `days: 3` binds exactly as no `days`. 1 to 30 ingredients.
`optionalIngredients`: see "Optional ingredients"; send it only to a server that says
`acceptsOptionalIngredients` (see that section). `taste` (optional): the quiz answers, step id →
option ids (see "Taste profile"); send it only to a server that says `acceptsTaste`, and only when
it is not empty. It does not change the cost.
Cost: `costs.recipe` for a recipe, `costs.menuSets["<days>"]["<courses>"]` for a menu (see
"Costs"; by default 1 for a recipe and days + courses − 1 for a menu; a 3-day menu is
`costs.menu2` / `costs.menu3`, 4 and 5).

Response:

```json
{ "status": "DONE | IN_PROGRESS | FAILED", "recipe": {}, "menu": {}, "balance": 9, "charged": true,
  "costs": { "recipe": 1, "menu2": 4, "menu3": 5, "menuSets": { "...": "see Costs" } },
  "acceptsOptionalIngredients": true, "acceptsMultiChoiceConfig": true, "acceptsDanish": true,
  "acceptsMenuDays": true, "acceptsTaste": true, "tasteQuizVersion": "35244e855cd4327f" }
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

Three kinds of failure, decided by the server; `charged` tells the client which way it went.

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
- **Answers that are not what was paid for** (the quality check: a recipe, or a day of a menu,
  that uses none of the ticked items; a dish made only of water, oil, salt, pepper, sugar or
  spices; the same dish twice in a menu) are always refunded (`charged: false`) and do not use
  the allowance above. They count towards the same daily ceiling as technical failures (3 a UTC
  day, then `INFRA_FAILURE_LIMIT`). Requests that could never pass this check are refused
  before anything is reserved (`NOT_ENOUGH_ITEMS`).

What the apps show (user-facing copy never says "credit" or "charged"): refunded, a quality
failure included: "Couldn't write the recipe. No recipe was taken." (a menu: "Couldn't write the
menu. No recipes were taken."). Charged: "Couldn't make a recipe from these items. It used N
recipe(s), because the attempt still cost us to run. Try fewer or different items." (N = the
request's cost.) Do not promise refunds in the UI.

#### Limits

Per account: at most one generation at a time (`BUSY`), 3 per minute (`RATE_LIMITED`), 40 per
UTC day or about USD 0.45 of estimated model usage per UTC day (`USER_DAILY_LIMIT`; that is at
least two 3-day 3-course menus, or four 3-day 2-course menus, or about 20 recipes a day; the
largest set, 4 days of 3 courses, fits once a day, then smaller requests). For the service as a
whole a daily spending ceiling pauses generation (`GENERATION_DISABLED`). The server can change
every number; branch on the reason, not on counts.

### `getGeneration`

The polling call. App Check enforced, token **not** consumed; per-user throttled.

Request `{ "requestId": "<the id sent to generateRecipe>" }`.

Response: exactly `generateRecipe`'s response for that generation (`status`, `recipe` / `menu`,
`balance`, `charged`, `costs`, the capability flags, `tasteQuizVersion`, and `purchasesBlocked`
when set). It never charges, never starts a
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

Response: `{ "status": "GRANTED | ALREADY_GRANTED | REJECTED_PERMANENT | RETRY", "balance": 10, "costs": { ... } }` (the whole table, see "Costs"), plus `"purchasesBlocked": true` when set. Like every response that carries `balance`, it also carries the capability flags and `"tasteQuizVersion"` (see "Capability `acceptsTaste`").

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

### `getTasteQuiz`

The taste quiz's definition, so the apps hold no questions or options of their own (README
"Taste profile quiz"). A signed-in caller (anonymous included), App Check enforced with an
ordinary token (not consumed), rate-limited like `getBalance`; it reads no account, reserves
nothing, calls no model and charges nothing. Call it only after a response said `acceptsTaste`.

Request: `{ "language": "de" }`: an app language (`en`, `ru`, `nb`, `es`, `de`, `fr`, `sv`, `da`;
case and a region are ignored, `de-DE` is `de`, `no` / `nn` are `nb`). Anything else, or none,
answers in English. Never an error.

Response:

```json
{
  "version": "3f9c2a81d04b6e57",
  "language": "en",
  "steps": [
    {
      "id": "heat", "kind": "single", "tone": "like", "icon": "flame",
      "question": "How hot do you like it?", "note": null, "max": 1,
      "groups": [ { "title": null, "options": [
        { "id": "mild", "label": "Mild", "sub": "Little or no chilli", "level": 1 } ] } ]
    }
  ],
  "conflicts": [ ["favourites:mushrooms", "avoid:mushrooms"] ],
  "summary": ["cuisines", "flavours", "favourites", "methods"]
}
```

| field | type | meaning |
|---|---|---|
| `version` | string | Opaque (today 16 hex characters). Changes whenever anything of the quiz changes (structure, any text in any language, a prompt word) or `tasteQuiz.hidden` hides something else; the same in every language. Store it with the answers; never parse it. |
| `language` | string | The language the texts are in. |
| `steps[]` | array | Shown in order. A client skips a step whose `kind`, `tone` or any field it does not understand, and ignores unknown fields. |
| `steps[].id` | string | Step id, the key in `taste`. Lower-case ASCII, at most 40 characters. |
| `steps[].kind` | `"multi"` \| `"single"` | `multi`: chips, up to `max`. `single`: cards, one choice (tap the chosen one to clear it); sent as a list of one. |
| `steps[].tone` | `"like"` \| `"avoid"` | `avoid`: the person would rather skip these (chip drawn with a ban sign); wins every conflict. |
| `steps[].icon` | string | A Lucide icon name from the allow-list (README "Taste profile quiz"); a client draws `utensils` for one it does not have. |
| `steps[].question` | string | The question. |
| `steps[].note` | string or null | A line under the question. |
| `steps[].max` | int | At most this many choices (1 for `single`). |
| `steps[].groups[]` | array | At least one; each `{ "title": string or null, "options": [...] }`, never empty. |
| `options[].id` | string | Option id, the value in `taste`. Lower-case ASCII, at most 40 characters. |
| `options[].label` | string | The chip or card label. |
| `options[].sub` | string | Optional: a second line (on `single` cards). |
| `options[].level` | int | Optional: 1 to 3, drawn as that many flames on a `single` card. |
| `conflicts[]` | `[string, string]` | `"<step>:<option>"` pairs that cannot both be chosen: choosing one takes the other off (with Undo). The server resolves a request's conflicts towards the `avoid` side. |
| `summary[]` | string | The steps, in order, the settings row's summary names labels from. |

Hidden steps and options (`config/runtime.tasteQuiz.hidden`) are left out; a group or step left
with no option is left out; `conflicts` and `summary` name only what is shown. Ids are never
reused with another meaning: an id the quiz no longer has is simply gone, and answers to it are
dropped (see "Taste profile").

### `getWelcomeOffer`

How many free recipes a sign-in gets now, for an install that has no account yet. **The one call
the apps make before a sign-in or a purchase**, and it carries nothing about the caller: App
Check enforced with an ordinary token (not consumed), **no sign-in** (an Auth token, if sent, is
ignored), no input (any is ignored), and the server reads only its own settings and today's
grant count; it stores nothing and logs nothing per call. Rate-limited per client address and
per server instance (`RATE_LIMITED`). The apps ask it when the Recipe tab is shown, at most once
a day, only on installs where buying is possible, and only while signed out or not yet claimed.

Request `{}`. Response `{ "recipes": 3 }`: the number `claimWelcome` would grant now; `0` when
the grant is off, generation is paused or today's grants are used up. The count may lag the day's
limit by up to 30 seconds, so `claimWelcome` can still answer `OFF` after a positive offer. An old
server answers `not-found` / unimplemented: read it as `0`. Never show a number from an answer
older than a day.

### `claimWelcome`

Grants the free recipes once per sign-in identity, ever. Called as a user signed in with
**Google or Apple**, with a limited-use App Check token (consumed; a replay is
`APP_CHECK_REPLAYED`). Request `{}`.

Response:

```json
{ "status": "GRANTED", "granted": 3, "balance": 3, "costs": { ... }, "acceptsWelcome": true, "welcomeRecipes": 3, ... }
```

| `status` | meaning |
|---|---|
| `GRANTED` | `granted` free recipes were added to `balance`. |
| `ALREADY` | This account, or any Google or Apple account linked to it, has had its free recipes (also on every later call: it is idempotent). `granted` is 0. Say so plainly ("This account has already had its free recipes") and offer packs. |
| `OFF` | The grant is off, generation is paused, or today's grants are used up. `granted` is 0, nothing was recorded, and a later claim may still succeed. Say it isn't available right now. |

Plus everything a response with `balance` carries (`costs`, `purchasesBlocked` when set, the
capability flags, `tasteQuizVersion`, `welcomeRecipes`).

- "Identity" is the Google or Apple account itself (the provider's own user id, read from the
  Firebase Auth record), not the Firebase uid: deleting the account and signing in again with the
  same Google or Apple account grants nothing, and an account linked to both claims once. An
  identity linked later to an account that already claimed is recorded too, so it cannot claim
  from another account either.
- Anonymous, or no Google / Apple identity: `SIGN_IN_REQUIRED`, nothing granted. A deleted or
  merged account: `ACCOUNT_CLOSED`.
- A sign-in that fails or is cancelled must not call it. After `GRANTED` or `ALREADY` the client
  never calls it again for that account.

### `getBalance`

Request `{}`. Response `{ "balance": 0, "costs": { ... } }` (the whole table, see "Costs"),
plus `"purchasesBlocked": true` when set, the capability flags, `"tasteQuizVersion"` (see
"Capability `acceptsTaste`") and `"welcomeRecipes"` (see "Capability `acceptsWelcome`"). A new, deleted or merged account reads 0. The balance can
be negative after a store refund of already-spent credits.

### `mergeBalance`

For when a signed-in account replaces an anonymous install that holds credits (when linking the
anonymous user to the provider is not possible because the credential already has an account).

Request `{ "sourceIdToken": "<ID token of the anonymous user, fetched before switching users>" }`,
called **as the signed-in (non-anonymous) user**, with a limited-use App Check token. Response
`{ "balance": 12, "moved": 7, "costs": { ... } }`, plus
`"purchasesBlocked": true` when set (a block on the anonymous install's buyer follows the merge),
the capability flags and `"tasteQuizVersion"` (see "Capability `acceptsTaste`").
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
  `purchases/` document (`uid` null, `formerUid` set), `buyers/` and `welcomes/` documents. Deleted: the
  account's `generations` and its rate-limit windows. A purchase made by this uid and not yet
  verified can never be redeemed afterwards (`ACCOUNT_CLOSED` for the uid, `PURCHASE_ACCOUNT_MISMATCH`
  for any other), so clients hold deletion back until it is confirmed.
- `BUSY` while a generation is in flight on the account: wait for it to finish, then retry.
- The same deletion can also be made by the owner, without the app, for a request by email
  (README "Deletion requests by email"). Nothing new on the wire: the app then finds its account closed
  as after a merge (`ACCOUNT_CLOSED` from the mutating callables, `balance: 0` from
  `getBalance`, and the Auth SDK's token refresh fails once its ID token expires, within an hour).
- Free recipes are lost with the account like any unused recipes, and the record that the
  account's Google or Apple sign-in has had them (`welcomes/`) is kept: signing in again with the
  same account does not grant them twice.

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
