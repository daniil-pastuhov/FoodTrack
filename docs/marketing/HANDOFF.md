# Store visibility work: state on 2026-11-15

A hand-over for continuing this workstream in a separate session. It records what was produced
for store visibility and downloads, where each piece lives, what is still open and who has to
act. Everything is on branch `feat/recipe` (PR #30) unless a line says otherwise. Nothing here
has been posted, submitted or uploaded to a store.

## Where things stand in the stores

- **App Store**: version 2.0.0 is live. It has no Recipe tab. Every storefront still shows
  "not enough ratings". The three recipe packs (`recipes_10`, `recipes_50`, `recipes_100`) are
  ready to submit and must go to review together with the next version.
- **Google Play**: no public listing. Closed testing (`alpha`) holds 2.0.0 (1061); internal
  testing holds "Recipe preview 2.0.0 (1064)". Production access needs 12 testers opted in on
  the closed track for 14 days in a row (details in `docs/play-production.md`).
- **Listing changes are not live.** The new texts, screenshots and art describe the Recipe tab,
  so they go out with the Recipe release: merging the release PR runs the listing sync.

## What was produced

| Piece | Where | State |
|---|---|---|
| Store analysis (audit, competitors, priorities) | summarised below; the full report was not saved as a file | done |
| Listing texts: en-GB, en-US (new), ru, no | `store-listing/app-store/<locale>/`, `store-listing/google-play/<locale>/` | in the repo, not in the stores |
| en-AU and en-CA App Store listings (keywords only, the rest inherited from en-GB) | `store-listing/app-store/en-AU/`, `en-CA/` | in the repo; created by the next release |
| Screenshots, six per set (Home, scan, recipe, 3-day menu, shopping, reminders); Duo sets five each | `store-listing/app-store/<locale>/*-screenshots/`, `store-listing/google-play/<locale>/phone-screenshots/` | in the repo |
| Header and search-result art | `store-listing/app-store/<locale>/header.png`, `search-result.png` | in the repo |
| Release automation: texts, screenshots, Duo sets, header art, review notes, in-app purchases | `scripts/testflight.py` (`listing`, `screenshots`, `iaps`, `appstore`), `scripts/play_listing.py`, `scripts/check_store_listing.py` | dry runs pass against the live accounts; no write path has run for real |
| Rating prompt in both apps | CLAUDE.md, "Rating prompt" | merged; never seen on a device |
| In-app event "Major Update" for the Recipe launch, with artwork | `store-listing/app-store/in-app-event/`, `scripts/in_app_event_art.py`, `testflight.py event` | dry run passes; `releaseDay` in `event.json` is unset |
| Featuring nomination pitch | `store-listing/app-store/featuring-nomination.md` | ready to paste; two placeholders (publish window, event id) |
| Play path to production, tester recruitment kit (en, ru, nb), read-only status command | `docs/play-production.md`, `docs/marketing/play-testers/`, `scripts/play_status.py` | written; the status command has not been run |
| Open Food Facts showcase submission | `docs/marketing/open-food-facts-reuse.md` | ready to paste; not posted |
| README top with screenshots and store links | `README.md`, `docs/images/` | merged |
| Product Hunt kit, community posts (en, nb, ru), press pitch (nb, en) | `docs/marketing/product-hunt.md`, `community-posts.md`, `press-pitch-no.md`, `press-pitch-en.md`, index in `docs/marketing/README.md` | drafts |
| Landing page "About the app" | pull request `daniil-pastuhov/bestbefore-privacy#3`; URL after merge: `https://daniil-pastuhov.github.io/bestbefore-privacy/app.html` | open, not merged |
| App Store preview video, 21 s, per language (en-GB, ru, no) | scripts `scripts/ios_preview.sh`, `scripts/ios_preview_assemble.py`; output `build/app-store-preview/<locale>/preview.mp4` and `poster.png` (git-ignored, 26 MB each; regenerate with the two scripts) | made and checked frame by frame; not uploaded |

## Findings worth keeping from the analysis

- No storefront shows ratings, and neither app asked for one. That was the first priority; the
  rating prompt now exists.
- The US storefront does not use the en-GB listing as an additional language, hence en-US
  ("BestBefore: Expiration Tracker"). Apple documents which localisation is displayed per
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
- One Russian keyword that is also another app's name was dropped; two-letter Norwegian
  keywords were dropped (Apple wants keywords longer than two characters).
- App Store Connect counts keyword characters, not bytes: the live Russian field is 90
  characters and 170 bytes.
- No Play listings for en-AU or en-CA: their text would equal en-GB's.
- Pre-registration is skipped: it only unlocks after production access, and a reward cannot be
  changed once created.

## Open, in the order it matters

1. **Pick the release day.** The featuring nomination wants at least three weeks' notice
   (Apple's pages say two in one place and three in two others). Play needs its 14 tester days
   anyway. The event's `releaseDay` and the nomination's publish window follow from this date.
2. **Recruit 12 Play testers** into one Google Group on the closed track, using
   `docs/marketing/play-testers/`. People in the internal test must leave it first or they do
   not count. If twelve are opted in by 22 November, the earliest application is 6 December.
3. **Submit the featuring nomination** once the date is set (App Store Connect > Featuring
   Nominations; by hand, there is no API in use).
4. **Create the in-app event**: set `releaseDay`, run `python3 scripts/testflight.py event`
   (dry run), then `--apply`, preview the artwork in App Store Connect, later
   `--apply --add-to-review`. The event's link `foodtracker://recipe` needs the app to route it
   to the Recipe tab; that fix was in progress when this file was written.
5. **Post the Open Food Facts submission** (the owner posts it under their own GitHub name):
   `https://github.com/openfoodfacts/openfoodfacts-web/issues/new?template=new-reuse.yml`.
6. **Merge the landing page pull request** only when the Recipe version is live, then put its
   URL into App Store Connect's Marketing URL.
7. **Product Hunt, community posts, press**: after the Recipe version is live; Android-focused
   posts after Play is public. Read each community's self-promotion rules first.
8. **Preview video**: watch it, then decide the purchase disclosure. The last shot is the 3-day
   menu, which needs a recipe pack, and Apple asks a preview that shows something needing a
   purchase to say so. Either add a short line to the last caption (`SHOTS` in
   `scripts/ios_preview_assemble.py`) or end on the recipe, which the phone writes for free.
   Upload is by hand: version page > each language > iPhone > drag the file in, poster frame at
   4.6 s. The videos were recorded before "Copy prompt" was removed and the pill resized; neither
   appears in the footage.

## Not decided

- **More app languages.** Spanish has the widest reach and gives a second keyword field in the
  US storefront (Spanish (Mexico) is an additional language there). German and French were named
  for their food-waste audiences. Swedish and Danish are cheap because they are close to
  Norwegian, but small. Each language means translating both apps and the store texts, with no
  native reader available. Not started.
- **French (Canada)** listing: Canada's storefront offers it; none exists.
- **An in-app About / acknowledgements screen** linking to Open Food Facts and showing the
  bundled licences. The product sheet now credits Open Food Facts with its licences; a fuller
  screen was described but not built.

## Things to verify before use

- The Norwegian household food-waste figure in the press pitch (35.0 kg per person in 2023,
  NORSUS for Matvett): the source PDF could not be fetched on the day; open both links in the
  pitch and confirm.
- The paraphrase of Mattilsynet on "best før" and "siste forbruksdag" in the press pitch.
- Apple's rule that event artwork carries no text: the event card has no added text, but the
  screenshots inside the phones show the app's own text. Check App Store Connect's preview.
- The Norwegian and Russian wording of the listing texts, event texts, tester invitations and
  community posts has not had a native read.
- The landing page was read as built HTML only, never in a browser.
- Whether a new closed-track build resets Play's 14-day clock: Google names only opting out as
  a reset; this was inferred, not read.

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
