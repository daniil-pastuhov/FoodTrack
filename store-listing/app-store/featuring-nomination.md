# Featuring nomination: the Recipe tab

The answers for App Store Connect > Apps > BestBefore > Featuring > Nominations > + > Create
Nomination, ready to paste. Fill in the two placeholders in square brackets first (the release
window and, once it exists, the in-app event's ID), and check the facts marked "check" against the
build that ships.

**Which version it is for (2026-11-15).** 2.1.0, the version that brings the Recipe tab, was
submitted with release type "after approval", so it goes live as soon as Apple approves it,
before any nomination could have three weeks' notice. The nomination is therefore for the
launch day chosen in `docs/marketing/launch-plan.md`, with the version released by hand that day
(repository variable `APP_STORE_RELEASE_TYPE` = `MANUAL` for that release). The description says
"new Recipe tab" rather than "this update adds", so it holds either way; if the owner instead
pulls 2.1.0 from review to release it on the launch day, it fits that too.

Sources (read 2026-11-15):

- Getting featured on the App Store, <https://developer.apple.com/app-store/getting-featured/>:
  "please give our team a minimum of two weeks notice. For wider featuring consideration, we
  recommend submitting a nomination up to three months in advance." What the editors weigh: user
  experience, UI design, innovation, uniqueness, accessibility, localization, the product page.
- App Store Connect Help, Nominate your app for featuring,
  <https://developer.apple.com/help/app-store-connect/manage-featuring-nominations/nominate-your-app-for-featuring>:
  the three types, the fields below, and "we recommend submitting plans as early as possible, with
  a minimum lead time of 3 weeks."
- App Store Connect Help, Nominations template,
  <https://developer.apple.com/help/app-store-connect/reference/nominations/nominations-template>:
  name at most 60 characters, description at most 1,000, helpful details at most 500, up to 5
  supplemental URLs, up to 25 in-app event IDs; "It's recommended you provide this date at least 3
  weeks before your launch."

The two pages disagree on the minimum (two weeks against three), so plan on **at least three weeks
before the release**, and earlier is better. A nomination is saved as a draft until you submit it;
after submitting, the type and the related apps can't be edited.

## Fields

**Type**: App Enhancements ("new features, functionality, or other significant updates"). The app
is already on the App Store, so it is not an App Launch.

**Related apps**: BestBefore only, Apple ID `6818647200`.

**Name** (42 of 60; only you see it):

```text
BestBefore: Recipe tab (cook what expires)
```

**Description** (paste as is; 985 of 1,000 characters):

```text
BestBefore is an expiry-date tracker for the fridge, freezer and pantry. Its new Recipe tab turns the food closest to its date into a recipe, so less of it is thrown away.

The tab ticks the items that expire first; people can change the selection, servings, time, diet and more. There are three ways to get the recipe. Free: the app writes a ready-made prompt to copy or share to any AI app. Free and private: on supported iPhones with Apple Intelligence it is written on the device with the Foundation Models framework, and nothing leaves the phone. Optional recipe packs (in-app purchases) have our server write recipes and menu sets of one to four days, up to three courses a day.

The rest stays as it was: scanning a barcode, reading the printed date with Vision, reminders and Home Screen widgets. No account, ads or tracking; the pantry stays on the iPhone. An in-app event will announce it. It ships in English, Russian, Norwegian, Spanish, German, French, Swedish and Danish.
```

**Publish date**: a custom range, `[YYYY-MM-DD]` to `[YYYY-MM-DD]`: the week the version is
planned to go live (release day R, see the README's timeline). Apple takes the date in your
device's time zone.

**Platforms**: iOS (iPhone), iOS (iPad). In-app events show on both.

**Relevant countries or regions**: leave Apple's preselection (every storefront the app is in).

**Do you plan to launch in certain markets first?** No.

**Do you intend to submit a new In-App Event for this nomination?** Yes. Once
`scripts/testflight.py event --apply` has created it, attach it here (In-App Events: `[event
ID]`); Apple recommends attaching it "as early as possible".

**Localizations**: leave Apple's preselection; it should show English (U.K.), English (U.S.),
English (Australia), English (Canada), Russian, Norwegian, Spanish (Spain), Spanish (Mexico),
German, French, French (Canada), Swedish and Danish.

**Supplemental materials** (up to 5 URLs):

```text
https://github.com/daniil-pastuhov/FoodTrack
https://daniil-pastuhov.github.io/bestbefore-privacy/
```

Add a public TestFlight link here if you open one for the release build.

**Does this app include a pre-order?** No.

**Helpful details** (paste as is; 497 of 500 characters):

```text
One developer, open source (Apache-2.0), so anyone can check what the app sends. A new app with few ratings, built Apple-first: Foundation Models on device, Vision for reading dates, WidgetKit, layouts for iPhone Duo and iPad, App Attest for the recipe server, Sign in with Apple. The free routes need no account and send nothing to us; a pack recipe sends the ticked items (all items only if the person turns that on) and allergens only after consent. Localised in English, Russian, Norwegian, Spanish, German, French, Swedish and Danish.
```

## Before you submit, check

- The release window above matches the plan, and is at least three weeks away.
- Every claim matches the build that ships: the Recipe tab's three routes, "on supported iPhones with Apple
  Intelligence" for the on-device route (Apple's model doesn't take every language, which the app
  checks before offering it), menu sets (1 to 4 days, up to three courses a day) only from
  packs, and the allergen consent step.
- No prices, competitor names or ratings figures in the text. The paid unit is a "recipe", never a
  credit or token.
- Apple asks the description to "clearly state the purpose and priority level": if this is the
  update you most want featured this year, say so in one sentence (there is room for about 70
  characters).
- "One developer" is true of the repository's history; drop it if that changes.
