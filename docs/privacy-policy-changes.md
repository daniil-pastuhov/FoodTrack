# Privacy policy for recipe packs

The policy is hosted outside this repo, at https://daniil-pastuhov.github.io/bestbefore-privacy/
(a language chooser and one page per app language: en, ru, nb, es, de, fr, sv, da), from the
repository `daniil-pastuhov/bestbefore-privacy`, where `build.py` holds the text of every
language, the support, delete and About pages.

That repository is the only description of the policy; this file does not repeat it. Change it
together with any change to what the backend stores, sends or keeps (`functions/src`,
`functions/README.md` "Firestore" and "Profile data"), in all eight languages. Merged so far:

| PR | Merged | What it added |
|---|---|---|
| [#1](https://github.com/daniil-pastuhov/bestbefore-privacy/pull/1) | 2026-11-14 | Paid server recipes |
| [#2](https://github.com/daniil-pastuhov/bestbefore-privacy/pull/2) | 2026-11-14 | The opt-in "Send all products" setting |
| [#3](https://github.com/daniil-pastuhov/bestbefore-privacy/pull/3) | 2026-11-15 | Eight languages, support and delete pages, About page |
| [#4](https://github.com/daniil-pastuhov/bestbefore-privacy/pull/4) | 2026-11-15 | Google may log recipe requests to detect abuse |
| [#5](https://github.com/daniil-pastuhov/bestbefore-privacy/pull/5) | 2026-11-16 | Taste profile, free recipes for signing in, menu sets |
| [#6](https://github.com/daniil-pastuhov/bestbefore-privacy/pull/6) | 2026-11-17 | Staples (2.1.1) |

## Pending: to publish with the release that ships them

Not in the policy yet. Open a pull request in `bestbefore-privacy` with these, in all eight
languages, and merge it when the release that offers them goes out, not before (the live apps
don't offer them). Checked against `functions/README.md` "Recipe pictures" and "Reports" and
`functions/CONTRACT.md` `reportRecipe`.

- **Recipe pictures.** If you ask for pictures with a recipe from a pack, our server has an image
  model make them (Google's, on Vertex AI, by default) from a short description of the dish that
  our recipe model writes and our server checks. Our server copies nothing you typed into the
  picture request, and it contains no allergens, no taste answers and no identifier of you or
  your device; because the recipe model saw your items, the description may name a food from
  them (for example "a bowl of tomato soup"). The
  pictures are sent to your device with the recipe and kept on our server with it for 7 days for
  retries, then deleted; on your device they stay with the saved recipe until you delete it (they
  are not in the app's backup file, nor in Android's cloud backup). (If the provider is ever switched, for example to OpenAI,
  the policy must name it before the switch: functions/README.md "What a new provider means for
  privacy".)
- **Reports.** If you report a recipe or menu from a pack ("Report this recipe"), we receive the
  reason you chose, any note you write (up to 500 characters), your app's platform, version and
  language, and keep it under your account together with a copy of the reported recipe or menu
  (and its pictures) taken from our server, for 90 days, then delete it. Reports are read by us,
  never sent to an AI model, and change nothing in your balance. If you delete your account, your
  reports (the reason, your note and the copy of the reported recipe or menu) are kept until they
  expire, up to 90 days, but no longer linked to it. Recipes written on your device
  can't be reported and never leave it.

## Store forms that must say the same

The answers, ready to paste, are in [store-listing/app-store/README.md](../store-listing/app-store/README.md),
section 5; App Privacy step by step in [app-privacy-apple.md](app-privacy-apple.md), Play's form as
`store-listing/google-play/data-safety.csv`. In short, for someone who starts a purchase (nothing of this for anyone else), all
linked to the user, none used for tracking, purpose App Functionality:

| What | App Store App Privacy | Play Data safety |
|---|---|---|
| Packs bought, the refund record, consumption shared with Apple on consent | Purchases > Purchase History | Financial info > Purchase history |
| Firebase user id; buyer identifiers | Identifiers > User ID | Personal info > User IDs |
| Email, when signed in | Contact Info > Email Address | Personal info > Email address |
| Google account name (and photo link), Google sign-in only | Contact Info > Name | Personal info > Name |
| Ticked items (all pantry items with "Send all products" on), the staples' names, settings, the recipe returned (and its pictures, once shipped) | User Content > Other User Content | App activity > Other user-generated content |
| A report's reason and note, with a copy of the reported result (once shipped) | User Content > Other User Content | App activity > Other user-generated content |
| Allergens, only after the consent step | Health & Fitness > Health | Health and fitness > Health info (not ephemeral) |
| App Check, IP-based rate limiting, request logs | no extra type (**Check**): the server stores no device identifier or IP address; Google's request logs keep the IP for 30 days | Device or other IDs (already declared): add Fraud prevention, security and compliance |
| Crash reports (everyone, release builds) | Diagnostics > Crash Data, Other Diagnostic Data; Identifiers > Device ID; not linked | Crash logs, Diagnostics, Device or other IDs; required, purpose Analytics |

The apps ask Apple for the email only, and the server clears the name and photo Google supplies
from the account's main record, but Firebase Authentication keeps a copy in the Google provider
entry until the account is deleted, so the name is declared for Google sign-ins. The allergen
list is declared as health data on both stores (and in `iosApp/FoodTracker/PrivacyInfo.xcprivacy`).
