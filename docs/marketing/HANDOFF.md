# Store visibility work: state on 2026-11-15, after the 2.1.0 release

A hand-over for continuing this workstream in a separate session. It records where the stores
stand, what was produced and where it lives, what is open, and who has to act. The launch day and
the dated checklist are in [`launch-plan.md`](launch-plan.md). Nothing in `docs/marketing/` has
been posted, submitted or sent.

## Where things stand in the stores

- **2.1.0 is out of the repository.** Release PR #31 merged on 2026-11-15 and was tagged
  `v2.1.0`. It brings the Recipe tab (shared prompt, the phone's own model, recipe packs and menu
  sets), "Add to shopping list" from a recipe, past-date warnings, an unread badge on Saved
  recipes, the fixed product sheet buttons, the full Open Food Facts credit and User-Agent, and
  the five new app languages (Spanish, German, French, Swedish, Danish).
- **App Store**: 2.1.0 is **Waiting for Review**, submitted together with the three recipe packs
  (`recipes_10`, `recipes_50`, `recipes_100`, also Waiting for Review). Release type is
  **AFTER_APPROVAL** (read from App Store Connect on 2026-11-15): it goes live as soon as Apple
  approves it, typically within a day or two. 2.0.0 (no Recipe tab) is live until then. No
  in-app event exists yet. Every storefront still shows "not enough ratings".
- **Google Play**: 2.1.0 (1921) is on internal testing and was promoted to closed testing
  (`alpha`, 177 countries). No production release, so still no public listing. Production access
  needs 12 testers opted in on the closed track for 14 days in a row (`docs/play-production.md`);
  how many are opted in is visible only in Play Console (the API's tester read answered 503 on
  2026-11-15).
- **What the release job applied** (2026-11-15): the repository's listing texts as they were at
  the merge, to App Store Connect's 13 localizations (en-GB, en-US, en-AU, en-CA, ru, no, es-ES,
  es-MX, de-DE, fr-FR, fr-CA, sv, da: names, subtitles, keywords, descriptions, promotional texts,
  per-language privacy URLs), the screenshots, Duo sets and header art then in the repo, the App
  Review notes, What's New; and on Play the texts and images of all 11 listings. Those texts still
  said "3-day meal plan ("Menu for 3 days")"; the repository now says menu sets (below), which
  reaches the stores with the next release.
- **App Store Marketing URL**: set on all 13 localizations of 2.1.0, ru and no included
  (https://daniil-pastuhov.github.io/bestbefore-privacy/app.html; read back through the API on
  2026-11-15). Nothing is left to do; the field can next be edited on the next version while it
  is in preparation.
- **Preview videos**: uploaded through the API on 2026-11-15 to all 13 localizations (one iPhone
  6.9" preview each, processing complete; es-MX and fr-CA carry es-ES's and fr-FR's, the extra
  English ones en-GB's). Their last shot is captioned as a three-day menu, which is one valid
  menu set, so they are not wrong; refresh them when the screenshots are.
- **Backend**: deployed. A live trial of menu sets against the model passed on 2026-11-15 (a
  4-day, 3-course menu in about 18 seconds). `allowSandbox` is on in `config/runtime` for the
  review and must be turned off after approval.

## What changed in the texts after the release (in the repo, not in the stores)

- Store descriptions, all 13 App Store and 11 Play: the "3-day meal plan" bullet now describes
  menu sets (1 to 4 days, up to three courses a day), and a new bullet says a recipe's missing
  ingredients go onto the shopping list in one tap.
- Accuracy of the free on-device route: Play's non-English descriptions now say the phone's
  model writes English only for now (`GeminiNanoRecipeModel.SUPPORTED_LANGUAGES`); the App Store
  ru description says Apple's model writes no Russian; no, sv and da say their language needs
  iOS 26.1.
- App Review notes and `review-reply.md`: menu set instead of "Menu for 3 days", no "Copy prompt"
  on iOS (the share sheet copies), "On this iPhone, no account", "Generate on this iPhone".
- Featuring nomination, Product Hunt kit, community posts, press pitches, the Open Food Facts
  submission, the tester kit and the README: menu sets, eight languages, the USD 2.99 entry price
  where a price fits, and what 2.1.0 changed for Open Food Facts.
- They reach the App Store with the next version (descriptions change only with a version). Play
  could take them earlier with `scripts/play_listing.py --apply` between releases (owner's call;
  the Play listing is not public yet).

## What was produced

| Piece | Where | State |
|---|---|---|
| Store analysis (audit, competitors, priorities) | summarised below; the full report was not saved as a file | done |
| Listing texts, 8 languages: App Store en-GB, en-US, en-AU and en-CA (keywords only), ru, no, es-ES, es-MX, de-DE, fr-FR, fr-CA, sv, da; Play en-GB, en-US, ru-RU, no-NO, es-ES, es-419, de-DE, fr-FR, fr-CA, sv-SE, da-DK | `store-listing/app-store/<locale>/`, `store-listing/google-play/<locale>/` | live as of the 2.1.0 merge; the menu-set revision is in the repo for the next release |
| Screenshots, six per set (Home, scan, recipe, menu, shopping, reminders); Duo sets five each | `store-listing/app-store/<locale>/*-screenshots/`, `store-listing/google-play/<locale>/phone-screenshots/` | the 2026-11-15 sets are in the stores (applied by the release job); being regenerated on branch `chore/store-screenshots-2.1` (another agent) for the next release |
| Header and search-result art, Play feature graphic, in-app event art | `store-listing/app-store/<locale>/header.png`, `search-result.png`, `store-listing/google-play/<locale>/feature-graphic.png`, `store-listing/app-store/in-app-event/<locale>/` | header art and feature graphics in the stores; event art in the repo (no event yet); regenerated with the screenshots |
| App Store preview video, about 21 s, per language | made by `scripts/ios_preview.sh` and `scripts/ios_preview_assemble.py` (output git-ignored, `build/app-store-preview/<locale>/`) | uploaded through the API on 2026-11-15 to all 13 localizations |
| Pack price tooling: one USD table, Play repricing, App Store price schedules | `scripts/pack_prices.py`, `scripts/play_products.py --reprice`, `scripts/testflight.py iap-prices` | used on 2026-11-15 to lower the 10-pack to USD 2.99 on both stores |
| Screenshot visual regression | `scripts/screenshot_check.py` | new on 2026-11-15 |
| Release automation: texts, screenshots, Duo sets, header art, review notes, in-app purchases | `scripts/testflight.py` (`listing`, `screenshots`, `iaps`, `appstore`), `scripts/play_listing.py`, `scripts/check_store_listing.py` | ran for real on the 2.1.0 merge |
| Rating prompt in both apps | CLAUDE.md, "Rating prompt" | shipped in 2.1.0; never seen on a device |
| In-app event "Major Update" for the Recipe launch, with artwork | `store-listing/app-store/in-app-event/`, `scripts/in_app_event_art.py`, `testflight.py event` | dry run passes; `releaseDay` in `event.json` is unset; not created |
| Featuring nomination pitch | `store-listing/app-store/featuring-nomination.md` | ready to paste; two placeholders (publish window, event id) |
| Launch plan | `docs/marketing/launch-plan.md` | recommendation; the owner picks the day |
| Play path to production, tester recruitment kit (en, ru, nb), read-only status command | `docs/play-production.md`, `docs/marketing/play-testers/`, `scripts/play_status.py` | written; the status command ran on 2026-11-15 |
| Open Food Facts showcase submission | `docs/marketing/open-food-facts-reuse.md` | ready to paste once 2.1.0 is live; not posted |
| README top with screenshots and store links | `README.md`, `docs/images/` | merged |
| Product Hunt kit, community posts (en, nb, ru), press pitch (nb, en) | `docs/marketing/product-hunt.md`, `community-posts.md`, `press-pitch-no.md`, `press-pitch-en.md`, index in `docs/marketing/README.md` | drafts, updated for 2.1.0 |
| Landing page "About the app" and the policy in eight languages | `daniil-pastuhov/bestbefore-privacy#3`, https://daniil-pastuhov.github.io/bestbefore-privacy/app.html | live since 2026-11-15 |
| Privacy policy page per App Store language | `store-listing/app-store/<locale>/privacy_url.txt` | set on all localizations by the 2.1.0 release job; Play stays on the chooser root |

## Findings worth keeping from the analysis

- No storefront shows ratings, and neither app asked for one. That was the first priority; the
  rating prompt now exists.
- The US storefront does not use the en-GB listing as an additional language, hence en-US
  ("BestBefore: Expiration Dates"; App Store Connect refused "BestBefore: Expiration Tracker" on
  2026-11-15 as a name another developer already uses, and Play keeps it). Apple documents which localisation is displayed per
  storefront, not which keywords it searches; the keyword benefit is the usual assumption.
- Established competitors sell Pro subscriptions; "no subscription, no account" is a real
  contrast. No strong Norwegian-language competitor was found (judgement, not data).
- Recipe packs cannot effectively be bought in Russia (Play billing paused since 2022; Apple
  closed the main payment routes for Russian accounts in April 2026). Russian texts mention
  recipes generally and do not push packs.
- F-Droid is not realistic without a separate build that drops Firebase, Play Billing and
  ML Kit.
- No search-volume data was available. Every ranking of search terms is an estimate.

## Decisions already taken

- The paid unit is called a "recipe" everywhere; never credit or token.
- The paid menu is a **menu set**: 1 to 4 days, 1 to 3 courses a day (one course is the main),
  priced in recipes by the server (default days + courses - 1, so 1 to 6). Never "3-day menu".
- **Pack prices** (2026-11-15, both stores): 10 recipes USD 2.99 (lowered from 3.99), 50 for
  10.99, 100 for 18.99; other currencies follow each store's conversion. The Buy sheet shows what
  a larger pack saves per recipe. Listing texts state no price (store rules); posts and pitches
  may say "from USD 2.99 for 10 recipes, converted by the stores".
- One Russian keyword that is also another app's name was dropped; two-letter Norwegian
  keywords were dropped (Apple wants keywords longer than two characters).
- App Store Connect counts keyword characters, not bytes: the live Russian field is 90
  characters and 170 bytes.
- No Play listings for en-AU or en-CA: their text would equal en-GB's.
- Pre-registration is skipped: it only unlocks after production access, and a reward cannot be
  changed once created.
- **Five more app languages** (decided 2026-11-15): Spanish, German, French, Swedish and Danish,
  in both apps and both stores, beside English, Russian and Norwegian. They shipped in 2.1.0. Spanish has the widest
  reach and gives a second keyword field in the US storefront (Spanish (Mexico) is an additional
  language there); German and French for their food-waste audiences; Swedish and Danish because
  they are close to Norwegian. Store folders: App Store es-ES, es-MX (partial, takes es-ES's
  files), de-DE, fr-FR, fr-CA (partial, takes fr-FR's), sv, da; Play es-ES, es-419, de-DE, fr-FR,
  fr-CA, sv-SE, da-DK. **No native reader has checked any of the five**: the app strings, store
  texts, captions, demo content and release notes were all machine-written. Have a native
  speaker read at least the store texts and the onboarding before relying on them. Danish
  recipes from packs need a server that says `acceptsDanish` (functions/CONTRACT.md); the
  live server does (deployed 2026-11-15).

## Open, in the order it matters

Dates refer to the recommended plan in `launch-plan.md` (launch day R = Tuesday 2027-01-12); move
them with R.

1. **Decide now whether 2.1.0 may go live on approval** (owner, App Store Connect; before Apple
   approves, which may be within hours). It is set to AFTER_APPROVAL, so it will. That is the
   recommendation: let it go live as a quiet release, and make the public launch a later version
   released by hand. The alternative, keeping the Recipe tab for a launch day, means Remove from
   Review on the version page (the packs too), setting "Manually release this version", and
   submitting again; whether the release option can be changed without removing it is not
   documented. Depends on nothing; everything below depends on it.
2. **Recruit 12 (better 15) Play testers by Sun 2026-11-22** (owner): one Google Group on the
   closed track, invitations in `docs/marketing/play-testers/`; anyone in the internal test leaves
   it first. Count them in Play Console > Closed testing > Testers. 12 by 22 November gives the
   earliest production application on Sun 6 December (safer Mon 7 December), access by about 14
   December (`docs/play-production.md`, "Calendar").
3. **After Apple approves 2.1.0 and the packs** (owner): set `allowSandbox` to `false` in
   Firestore `config/runtime` (Firebase console, project `bestbefore-f9ee1`; licence testers then
   stop getting recipes, see `play-testers/README.md`); open the live product page in two or three
   languages; buy nothing.
4. **Post the Open Food Facts submission** once 2.1.0 is live (owner, under their GitHub name):
   https://github.com/openfoodfacts/openfoodfacts-web/issues/new?template=new-reuse.yml with the
   fields in `open-food-facts-reuse.md`. It needs 2.1.0 live because that build carries the
   licence credit.
5. **Create the in-app event as a draft** (owner, by Tue 2026-12-01): decide its framing first
   (question 3 in `launch-plan.md`: "Major Update" two months after the Recipe tab shipped, or a
   January challenge), set `releaseDay` in `store-listing/app-store/in-app-event/event.json` to R,
   run `python3 scripts/testflight.py event` (dry run), then `--apply`, then check the card and
   details preview in App Store Connect (ru: the «Рецепт» title bar). Depends on 1 and the day.
6. **Submit the featuring nomination** (owner, Tue 2026-12-01, by Tue 2026-12-22 at the latest):
   App Store Connect > Featuring > Nominations, text in
   `store-listing/app-store/featuring-nomination.md`, publish window the week of R, the event's ID
   from 5. Depends on 5 for the ID.
7. **A native read of the five new languages** (owner finds readers, by Fri 2026-12-18): at least
   the store descriptions, subtitles, keywords, promotional texts, onboarding and the release
   notes in es, de, fr, sv, da. What changed after the release is `git diff v2.1.0 --
   store-listing/` (the menu-set and shopping-list bullets, and on Play the "English only for now"
   note). Fixes go in before the next release PR (an agent can apply them).
8. **The launch version** (owner + agent, release PR merged about Tue 2027-01-05): merge
   `chore/store-screenshots-2.1` (refreshed screenshots and art) and the language
   fixes, cut `scripts/release` (2.1.1 or 2.2.0), set the repository variable
   `APP_STORE_RELEASE_TYPE` to `MANUAL` for it, `allowSandbox` back on for its review, and
   `testflight.py event --apply --add-to-review` before the merge so the event is reviewed with
   it. Check the ru and no Marketing URLs on the new version while it is in preparation. Release
   by hand on R.
9. **Play production** (owner): apply on X + 15 (`docs/play-production.md`, step 8), then the
   first production release (step 9) of the launch version, timed for R. If Play is late, launch
   iOS-first and keep the Android posts for later.
10. **Product Hunt, community posts, press** (owner posts; agent rechecks texts and figures the
    week before): the order in `launch-plan.md`; read each community's self-promotion rules first.

## Not decided

- **An in-app About / acknowledgements screen** linking to Open Food Facts and showing the
  bundled licences. The product sheet now credits Open Food Facts with its licences; a fuller
  screen was described but not built.

## Things to verify before use

- Verified and corrected 2026-11-15: the Norwegian household food-waste figure. NORSUS (Aina
  Stensgård) for Matvett, "Faktaark om matsvinn i husholdningene 2023", OR.16.24, 2024, read at
  https://www.matvett.no/uploads/documents/Rapporter/OR.16.24-Faktaark-om-matsvinn-i-husholdningene.pdf
  (the pitch's old link without `/Rapporter/` answers 404; found from matvett.no/rapporter): 35.0 kg
  per inhabitant in 2023, 193,200 tonnes, 42.6 kg in 2016, -18 %. It is edible food waste
  ("den nyttbare delen av matavfallet"; all food waste 70.6 kg), measured by waste-sorting
  analyses (plukkanalyser), not a survey. Matvett's "Slik var matsvinnåret 2024" repeats "35 kg mat
  per person i norske husholdninger i 2023". Both pitches now link the working URL and say
  "edible" / "plukkanalyser" instead of "survey" / "kartlegging".
- Verified and corrected 2026-11-15: Mattilsynet, "Holdbarhetsmerking på matvarer" (published
  31.03.2022), https://mattilsynet.no/mat-og-drikke/merking-av-mat/holdbarhetsmerking-pa-matvarer:
  "Du bør altså ikke spise varer merket med «siste forbruksdag» etter utløpsdato"; "best før" is
  about quality ("«Best før» betyr ofte god etter"). The Norwegian pitch said "ikke skal spises",
  now "ikke bør spises" as Mattilsynet words it; the English "should not be eaten" already matched.
- Verified 2026-11-15, still worth a look in the preview: Apple's event-media guidance
  (https://developer.apple.com/app-store/in-app-events/) is "When possible, avoid using text or
  logos in your media, especially if they include your event name or app name." It is advice,
  says nothing about screenshots or device frames, and the cards add no text of their own, so the
  risk is low. The ru card's title bar «Рецепт» echoes the ru event name «Новая вкладка «Рецепт»»;
  check App Store Connect's preview. Recorded in `store-listing/app-store/README.md`, section 8.
- Verified 2026-11-15, no change: Apple's featuring lead time. Getting featured
  (https://developer.apple.com/app-store/getting-featured/): "a minimum of two weeks notice";
  Nominate your app for featuring
  (https://developer.apple.com/help/app-store-connect/manage-featuring-nominations/nominate-your-app-for-featuring):
  "a minimum lead time of 3 weeks"; Nominations template
  (https://developer.apple.com/help/app-store-connect/reference/nominations/nominations-template):
  "at least three weeks in advance" and "at least 3 weeks before your launch". The documents'
  "plan on three weeks or more" stands.
- The Norwegian and Russian wording of the listing texts, event texts, tester invitations and
  community posts has not had a native read.
- The landing page was checked in Chrome on 2026-11-15 (phone and desktop width, light and dark)
  and fixed; Safari was not tried.
- Still open, Google is silent (checked 2026-11-15): whether a new closed-track build resets
  Play's 14-day clock. Neither https://support.google.com/googleplay/android-developer/answer/14151465
  nor answer/9845334 says; Google names only opting out as a reset, and recommends "Updating your
  app in closed testing before releasing to production". Not resetting stays an inference; quotes
  in `docs/play-production.md`, "What resets the clock".

## Commands

All are dry runs unless `--apply` is given. They read credentials from the environment
(`ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_PATH` for App Store Connect;
`PLAY_SERVICE_ACCOUNT_JSON` for Play); no key is in the repository.

```
python3 scripts/check_store_listing.py          # limits of every listing text, offline
python3 scripts/testflight.py listing           # App Store texts and review notes: repo vs store
python3 scripts/testflight.py screenshots       # App Store screenshots, Duo sets, header art
python3 scripts/testflight.py iaps              # the three packs and what a submission would do
python3 scripts/testflight.py event             # the in-app event
python3 scripts/play_listing.py --images        # Play texts and images
python3 scripts/play_status.py                  # Play tracks, countries, tester groups (read-only)
```

## Related documents

- `docs/recipe-setup.md`: the owner's checklist for the Recipe backend and store forms.
- `docs/play-production.md`: Google's tester rules with sources, the calendar, the declarations.
- `store-listing/app-store/README.md`: listing sync, the in-app event and nomination timeline.
- `docs/marketing/README.md`: the order and timing of the launch drafts.
