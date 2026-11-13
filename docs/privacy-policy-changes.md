# Privacy policy: changes for recipe packs (draft)

The policy is hosted outside this repo (https://daniil-pastuhov.github.io/bestbefore-privacy/, en,
ru and nb). This is a draft of what has to change now that the app has recipe packs, written
from what the code does on 2026-11-13. Retention figures come from `functions/src`; where a
figure depends on Google's or Apple's own terms, it is marked **Check**. It is not legal advice.

## What stays the same

- The pantry, shopping list, settings, saved recipes and recipe settings are stored only on the
  device. Backups are files the user exports and keeps.
- Barcode lookups send only the barcode number to Open Food Facts.
- Crash reports from release builds go to Firebase Crashlytics (existing "Crash reports"
  section), not linked to a person.
- Location (shop reminders) and camera images stay on the device.
- No ads, no analytics, no tracking, no selling of data.

## New: the three ways to get a recipe

Suggested text to add:

> **Recipes.** The Recipe tab can write a recipe from the items you choose, in three ways.
>
> - **Share or copy a prompt (free).** The app builds a text and hands it to the app you choose,
>   or copies it. We receive nothing. What happens next is up to that app and its own policy.
> - **On this phone (free).** On supported phones the recipe is written by the phone's own model
>   (Gemini Nano on Android, Apple Foundation Models on iPhone). Nothing is sent to us.
> - **Recipe packs (paid).** You buy a pack of recipes in the App Store or Google Play. Each recipe
>   from a pack is written by our server. Nothing in this section applies to you unless you start
>   a purchase.

## New: what is collected for recipe packs, when, and by whom

Nothing below happens for someone who never starts a purchase. Once someone does:

| What | When | Who processes it | Why |
|---|---|---|---|
| A random user ID (Firebase Authentication, anonymous) | When a purchase starts, unless the buyer signs in | Google (Firebase), as our processor | To hold the buyer's recipe balance |
| Email address; with Google sign-in also the name on the Google account, and possibly its profile photo address (**Check** the Firebase Auth user record); with Sign in with Apple only the email, which may be Apple's relay address (the Android app asks Apple for the name too: see "Found in the code") | Only if the buyer chooses to sign in with Apple or Google | Google (Firebase Authentication); Apple or Google as the sign-in provider | So the recipes can be used on other devices |
| An App Check token proving the request comes from a genuine copy of the app | With each request to our server | Google (Firebase App Check, Play Integrity) on Android; Apple (App Attest) and Google (Firebase App Check) on iPhone | To limit abuse |
| The purchase: store, product, quantity, number of recipes, environment (production or sandbox), dates, state (granted / consumed / refunded), the store's transaction or order ID | When the purchase is verified, and when the store reports a refund | Google Play (Google Play Developer API) or Apple (App Store Server API), and our server on Google Cloud | To add the recipes once and only once, and to handle refunds |
| The recipe request: the names, expiry dates and quantities of the items ticked for the recipe (at most 30), the recipe settings (difficulty, detail, servings, time, meal, diet, language), the allergen list from Settings, and the date | Each time a recipe or menu from a pack is requested | Our server on Google Cloud (Cloud Functions), and **Google Vertex AI** (the Gemini model), which writes the recipe | To write the recipe |
| The recipe or menu written back | Each successful request | Our server (Firestore) | So a retry of the same request returns the same recipe and is not charged twice |
| Counters: balance, recipes bought, used and refunded, requests per minute and per day | While the account exists | Our server (Firestore) | Balance and abuse limits |
| Technical logs: the user ID and the kind of request when a request fails, and errors | When something fails | Google Cloud Logging | To find faults |

No name, email or device identifier is sent to Vertex AI with a recipe request; the request
carries only the items, settings, allergens and date above (`functions/src/adapters/prompt.ts`).

The server lives in the Google Cloud region `europe-west1` (Belgium); Firestore in the location
chosen when it is created (planned: a European multi-region). Vertex AI is called in the location
`global` by default, which means Google may process the request outside the EU. **Check** whether
to set `VERTEX_LOCATION` to a European region and say so.

## Retention, as the code implements it

- **Generated recipes and menus** on the server (`users/{uid}/generations/{requestId}`): kept
  7 days after the request finishes, then deleted by a Firestore TTL policy
  (`GENERATION_TTL_MS`, `firestore.indexes.json`). Firestore deletes expired documents usually
  within about a day of expiry (**Check** Google's current TTL documentation for the wording).
  These documents hold the recipe, not the request; the ticked items and the allergen list are
  not stored in Firestore.
- **Requests sent to Vertex AI**: not stored by us. Google's retention and use of prompts under
  Vertex AI's data governance terms (no training on customer data; possible short-term caching and
  abuse logging) need to be described from Google's current terms. **Check**.
- **Account (user) record**: kept while the account exists. There is no automatic clean-up, so an
  anonymous buyer who uninstalls the app leaves an inactive record with their balance (there is no
  way for us to tell who they are).
- **Account deletion** (Recipe settings > Delete account, which requires a recent sign-in):
  - deleted: the Firebase Authentication user (with the email and name), all generated recipes
    and menus, and the rate-limit counters; a Sign in with Apple token is revoked;
  - kept: a closed record under the old user ID with the deletion date, the forfeited balance and
    the totals of recipes bought, used and refunded, so a deleted account can't be used again
    during the hour its sign-in token stays valid, and for accounting;
  - kept: every purchase record, with the link to the user removed (`uid` set to null). It is the
    only way to stop one store purchase being redeemed twice, so it is never deleted. It holds no
    name or email, but does keep the store's order or transaction ID, which Apple or Google can
    link to the buyer;
  - unused recipes are lost and are not refunded.
- **Merging an anonymous install into a signed-in account**: the anonymous Firebase user is
  deleted; a closed record pointing to the account that received the recipes is kept, so later
  store refunds reach the right account.
- **Play purchase tokens**: stored only until the purchase is consumed, then deleted; the hashed
  token stays as the record's ID.
- **Daily statistics** (`stats/{date}`): totals only, no user data. Kept indefinitely.
- **Technical logs**: Google Cloud Logging's default retention (30 days for the `_Default`
  bucket; **Check** the project's setting).
- **On the phone**: saved recipes stay until the user deletes them or the app. Signing out leaves
  the recipes in the account.

## Who the processors and providers are

- **Google (Firebase and Google Cloud)**: Authentication, App Check, Cloud Functions, Firestore,
  Cloud Logging, Crashlytics, and Vertex AI (Gemini), as processors for us.
- **Google Play**: purchases and refunds on Android; Play Integrity for App Check.
- **Apple**: purchases and refunds on iPhone (App Store, App Store Server API), App Attest, Sign in
  with Apple.
- The sign-in provider the buyer picks (Apple or Google) under its own policy.

## Your choices (suggested text)

> You can use BestBefore, including two of the three ways to get a recipe, without any of the
> above. If you buy a pack, you can buy it without signing in; the recipes then stay with that
> install of the app. You can delete your account in Recipe settings at any time.

## Open question: are allergens health data?

The allergen list (for example "peanut", "milk") is chosen by the user in Settings and is sent
with every recipe request from a pack, as ingredients to leave out. The server does not store
it.

- Apple's App Privacy categories include **Health & Fitness > Health**, and Google Play's Data
  safety form has **Health info**. A food allergy can reveal a health condition, and under the
  GDPR data concerning health is a special category (Article 9), which would need explicit
  consent.
- Against: the list is a cooking preference the user types, it could equally be a dislike, it is
  not linked to a diagnosis, and it is used only to write that recipe.
- Options: (a) declare it as health data in both stores and ask for explicit consent before the
  first paid request that includes allergens; (b) keep it under "other user content" and say in
  the policy that it is used only as an instruction to the recipe writer; (c) let the user choose,
  per request, whether allergens are sent. Needs a decision by the owner, ideally with advice.

## Found in the code, worth fixing before the policy goes live

- The delete-account confirmation in both apps says "Your account ID and email address are deleted
  from our server". The email is deleted, but a closed record under the account ID is kept (see
  above). Suggested text: "Your sign-in and email address are deleted from our server; a record of
  your purchases is kept without them." (en; ru and nb to follow).
- On Android, Sign in with Apple asks Apple for the name as well as the email
  (`FirebaseRecipeAccount.appleProvider`), while iOS asks for the email only. Either drop the name
  scope on Android or declare the name for Apple sign-ins too.
