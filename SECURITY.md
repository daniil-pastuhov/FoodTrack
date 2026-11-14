# Security Policy

## Reporting a vulnerability

Please don't open a public issue for a security problem. Report it privately through
[GitHub's private vulnerability reporting](https://github.com/daniil-pastuhov/FoodTrack/security/advisories/new)
instead. Include what you found, how to reproduce it, and which app version and platform it affects.

You should get a reply within a week. Fixes ship in the next release of both apps.

## Scope

Pantry data stays in a local database on the device. The one backend is for recipes from paid
packs: Cloud Functions in `functions/`, with Firestore, Firebase Auth (anonymous, Google, and Apple
on iOS only) and App Check, in the maintainer's Firebase project. Nobody reaches it until they start a
purchase. Reports about the apps themselves, the backend (purchase verification, the recipe
balance, account deletion, `firestore.rules`), the backup file format, the CI workflow and the
release scripts in `scripts/` are all in scope.

Of particular interest: any way to raise a recipe balance without a store-verified purchase, to
redeem one purchase twice, to get a recipe without being charged, to make the model run without a
purchase or past the daily spending limits, to get past the block on buyers who refund used
recipes, to make the model write something harmful or unrelated to cooking that the server's
checks let through (the prompt and every check are in `functions/src`, so say what got past which
check), or to read or change another person's data. App Check is treated as rate reduction, not as the guard on money (the code is
public, and a genuine build on a jailbroken iPhone, or on a rooted Android phone that gets past
Play Integrity's device check, can still attest), so a report that
only shows App Check can be passed is less useful than one that also gets past the server's
checks.

## Abuse and billing problems

- **Abuse of the backend** (a way to run up its costs, flood it, or use it as a general-purpose
  model): report it privately as above, like a vulnerability.
- **A purchase that was not added, or a refund**: this is not a security matter. The apps keep
  trying to add a purchase (at launch, when the app comes back to the foreground, and on "Try
  now"); refunds are requested from Google Play or the App Store. See the
  [support page](https://daniil-pastuhov.github.io/bestbefore-privacy/support.html). Please don't
  post order numbers or other personal details in a public issue.

## Configuration files

The Firebase configuration files (`androidApp/google-services.json`,
`iosApp/FoodTracker/GoogleService-Info.plist`) are committed on purpose. Their API keys are client
identifiers that every installed copy of the app contains anyway. They are not secrets. The real
secrets (the App Store Server API key and the Sign in with Apple key) live in Google Secret
Manager and are never in the repository.
