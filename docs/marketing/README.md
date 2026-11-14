# Launch communication (drafts)

Drafts for announcing BestBefore (Срокоед, Matvakt) and its Recipe tab. Nothing in this folder has
been posted, submitted or sent; the owner does all of that by hand. Other files in this folder
(for example `play-testers/` and `open-food-facts-reuse.md`) are separate work and are not indexed
here.

| File | What it is |
| --- | --- |
| [`product-hunt.md`](product-hunt.md) | Product Hunt fields within the current limits (cited), maker's first comment, gallery plan, launch-day checklist |
| [`community-posts.md`](community-posts.md) | Three English posts (food waste, meal prep, apps), a Norwegian and a Russian one, and a checklist to read each community's rules first |
| [`press-pitch-no.md`](press-pitch-no.md) | Norwegian email pitch, one-page fact sheet with sourced figures, and Norwegian outlets with their public contact pages |
| [`press-pitch-en.md`](press-pitch-en.md) | The same pitch and fact sheet in English |

The about page itself (`app.html`, trilingual, with screenshots) lives on the GitHub Pages site in
the `daniil-pastuhov/bestbefore-privacy` repository, as a pull request from the `landing-page`
branch. Once merged it is at <https://daniil-pastuhov.github.io/bestbefore-privacy/app.html> and
linked from the site's index; the drafts here link to it.

## Must be true before anything is used

- The version with the Recipe tab is **live on the App Store** (not only in review or TestFlight).
  Every draft describes it. Until then, cut the Recipe parts or say "in the next update".
- The wording still matches the app: the 3-day menu comes from a paid pack; on-device recipes need
  Apple Intelligence (iOS 26 or later) or a supported Android phone with Gemini Nano; recipe packs
  are optional one-off purchases; the paid unit is called a "recipe".
- No user counts, savings figures, superlatives or competitor names have been added.

## Suggested order and timing

Day 0 is the day the Recipe version is live on the App Store.

| When | What | Also needs |
| --- | --- | --- |
| Day 0 | Merge the `landing-page` pull request in `bestbefore-privacy`; check `app.html`, `index.html`, `en.html`, `support.html` and `delete.html` load | Recipe version live |
| Day 0 to 2 | Norwegian post (`community-posts.md` §4) in one or two Norwegian groups; Russian post (§5) in one or two Russian-speaking groups | Each community's rules read; Russian: no promise of packs where purchases do not work |
| Day 1 to 7 | Norwegian press pitch (`press-pitch-no.md`), Tuesday to Thursday morning; start with Matvett, Framtiden i våre hender, Tek.no and Kode24, then the national papers, one email each | `app.html` live; figures and links rechecked |
| Week 1 to 2 | English zero-waste (§1) and meal-prep (§2) posts, one community each, a few days apart | iOS-only is fine; say Android is in testing |
| When Google Play is public | iOS/Android apps post (§3); update every "Android in testing" line in all drafts and on `app.html` (in `build.py`) | Play listing public |
| When Google Play is public, a weekday you can stay online | Product Hunt launch (`product-hunt.md`), scheduled up to 30 days ahead | Gallery images made; first comment's [bracketed] parts filled in |
| Any time after | `press-pitch-en.md` for English-language contacts | Recipe version live |

If Google Play stays in closed testing for long, Product Hunt can go ahead iOS-only: leave the Play
link out and say Android is in testing in the first comment.

## Read before using

- Product Hunt's first comment has a [bracketed] reason for building the app: it must be the
  owner's own story.
- The Russian guidance says packs are effectively not purchasable in Russia now; check that it
  still holds, and in which countries the app is offered (App Store Connect, Play Console).
- The press figures were read on Matvett's and the government's sites on 2026-11-15; open the
  links again before sending.
