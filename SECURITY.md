# Security Policy

## Reporting a vulnerability

Please don't open a public issue for a security problem. Report it privately through
[GitHub's private vulnerability reporting](https://github.com/daniil-pastuhov/FoodTrack/security/advisories/new)
instead. Include what you found, how to reproduce it, and which app version and platform it affects.

You should get a reply within a week. Fixes ship in the next release of both apps.

## Scope

Pantry data stays in a local database on the device. The one backend is for recipes from paid
packs: Cloud Functions in `functions/`, with Firestore, Firebase Auth (anonymous, Apple, Google)
and App Check, in the maintainer's Firebase project. Nobody reaches it until they start a
purchase. Reports about the apps themselves, the backend (purchase verification, the recipe
balance, account deletion, `firestore.rules`), the backup file format, the CI workflow and the
release scripts in `scripts/` are all in scope.

Of particular interest: any way to raise a recipe balance without a store-verified purchase, to
redeem one purchase twice, to get a recipe without being charged, or to read or change another
person's data. App Check is treated as rate reduction, not as the guard on money (the code is
public and a genuine build on a rooted or jailbroken device can still attest), so a report that
only shows App Check can be passed is less useful than one that also gets past the server's
checks.

The Firebase configuration files (`androidApp/google-services.json`,
`iosApp/FoodTracker/GoogleService-Info.plist`) are committed on purpose. Their API keys are client
identifiers that every installed copy of the app contains anyway. They are not secrets. The real
secrets (the App Store Server API key and the Sign in with Apple key) live in Google Secret
Manager and are never in the repository.
