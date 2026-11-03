# Security Policy

## Reporting a vulnerability

Please don't open a public issue for a security problem. Report it privately through
[GitHub's private vulnerability reporting](https://github.com/daniil-pastuhov/FoodTrack/security/advisories/new)
instead. Include what you found, how to reproduce it, and which app version and platform it affects.

You should get a reply within a week. Fixes ship in the next release of both apps.

## Scope

The app has no backend or accounts. Pantry data stays in a local database on the device. Reports
about the apps themselves, the backup file format, the CI workflow and the release scripts in
`scripts/` are all in scope.

The Firebase configuration files (`androidApp/google-services.json`,
`iosApp/FoodTracker/GoogleService-Info.plist`) are committed on purpose. Their API keys are client
identifiers that every installed copy of the app contains anyway. They are not secrets.
