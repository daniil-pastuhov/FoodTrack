# Privacy policy for recipe packs

The policy is hosted outside this repo, at https://daniil-pastuhov.github.io/bestbefore-privacy/
(English, Russian, Norwegian), from the repository `daniil-pastuhov/bestbefore-privacy`, where
`build.py` holds the text of all three languages and the support page.

The text for recipe packs is in its pull request,
<https://github.com/daniil-pastuhov/bestbefore-privacy/pull/1> (branch `recipe-purchases`),
checked against the code on this branch. That pull request is the only description of the
policy; this file does not repeat it. Merge it when the version that sells packs is released, not
before, and change it together with any change to what the backend stores, sends or keeps
(`functions/src`, `functions/README.md` "Firestore" and "Profile data").

## Store forms that must say the same

The answers, ready to paste, are in [store-listing/app-store/README.md](../store-listing/app-store/README.md),
section 5. In short, for someone who starts a purchase (nothing of this for anyone else), all
linked to the user, none used for tracking, purpose App Functionality:

| What | App Store App Privacy | Play Data safety |
|---|---|---|
| Packs bought, the refund record, consumption shared with Apple on consent | Purchases > Purchase History | Financial info > Purchase history |
| Firebase user id; buyer identifiers | Identifiers > User ID | Personal info > User IDs |
| Email, when signed in | Contact Info > Email Address | Personal info > Email address |
| Google account name (and photo link), Google sign-in only | Contact Info > Name | Personal info > Name |
| Ticked items, settings, allergens, the recipe returned | User Content > Other User Content | App activity > Other user-generated content |
| App Check, IP-based rate limiting, request logs | no extra type (**Check**): the server stores no device identifier or IP address; Google's request logs keep the IP for 30 days | Device or other IDs (already declared): add Fraud prevention, security and compliance |
| Crash reports (everyone, release builds) | Diagnostics > Crash Data, Other Diagnostic Data; Identifiers > Device ID; not linked | Crash logs, Diagnostics, Device or other IDs |

The apps ask Apple for the email only, and the server clears the name and photo Google supplies
from the account's main record, but Firebase Authentication keeps a copy in the Google provider
entry until the account is deleted, so the name is declared for Google sign-ins. The allergen
list is declared as other user content, not health data; whether it should also be Health is still
the owner's decision (store-listing/app-store/README.md, section 5).
