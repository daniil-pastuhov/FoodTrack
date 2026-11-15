# Product Hunt launch kit (draft)

Draft only: nothing here has been submitted. Use it once the version with the Recipe tab is live on
the App Store, and preferably once Android is public on Google Play (see `README.md` in this folder).

## Field limits (checked 2026-11-15)

From Product Hunt's help centre, "4. Build your product page"
(<https://help.producthunt.com/en/articles/17834537-4-build-your-product-page>, the page the old
launch guide URL <https://www.producthunt.com/launch/preparing-for-launch> now redirects to; it
says only "Updated over a week ago"):

| Field | Limit / guidance on that page |
| --- | --- |
| Name | up to 40 characters, the product's name only, no tagline or emoji |
| Tagline | up to 60 characters, plainly what the product does |
| Description | up to 500 characters, value first, then features |
| Launch tags (topics) | required, up to 3 |
| Thumbnail | required, 240 × 240 px recommended, JPG/PNG/GIF up to 2 MB |
| Gallery | at least 1 image, 1270 × 760 px recommended; the pre-schedule checklist asks for 3 or more |
| Video | optional |
| Maker's first comment | up to 5,000 characters, pinned at the top of the discussion |
| Launch day | starts at midnight Pacific Time and runs the whole day; can be scheduled up to 30 days ahead |
| Rule | "Ask for feedback, never for upvotes"; "Please upvote!" breaks the rules |

Some third-party guides and older submission forms quote 260 characters for the description; the
description below is 252 characters so it fits either. Check the live form on the day: limits can
change.

## Fields

**Name** (10): `BestBefore`

**Tagline** (56): `Track food expiry dates and cook what is about to go off`

Alternatives, all under 60:

- `Scan groceries, get reminded, cook what expires first` (53)
- `Use food before it goes off: scan, get reminded, cook` (53)

**Description** (252):

> BestBefore keeps track of the food in your fridge, freezer and cupboard. Scan a barcode, let the
> phone read the printed date, and get a reminder before food goes off. The Recipe tab turns what
> expires first into a recipe. Free, no account, open source.

**Launch tags** (pick 3 from Product Hunt's own list; names there may differ slightly):
Food & Drink, Open Source, and either Artificial Intelligence (if leading with the on-device
recipes) or iOS (if Android is still not public). Other fitting tags if available: Sustainability,
Android, Privacy.

**Links**

- App Store: <https://apps.apple.com/app/id6818647200>
- Google Play: only once the listing is public (it is in closed testing now); otherwise leave it out
  and say so in the first comment
- Source: <https://github.com/daniil-pastuhov/FoodTrack>
- About page: <https://daniil-pastuhov.github.io/bestbefore-privacy/app.html> (once the
  `landing-page` pull request in `bestbefore-privacy` is merged)

**Thumbnail**: `store-listing/google-play/icon-512.png`, scaled to 240 × 240.

## Gallery plan

Product Hunt recommends 1270 × 760 (landscape); the store screenshots are portrait (1320 × 2868), so
each gallery image is a new canvas in the Larder palette (the dark green of the App Store frames)
with two or three framed screenshots side by side and their existing captions. Order:

1. **Cover**: `store-listing/app-store/en-GB/header.png` (3840 × 1646) cropped or padded to
   1270 × 760, or `store-listing/google-play/en-GB/feature-graphic.png` (1024 × 500) on a padded
   canvas.
2. **Know what's about to expire + Scan the pack, the date is read**:
   `store-listing/app-store/en-GB/screenshots/1.png` and `2.png`.
3. **Cook what needs using first + A three-day menu around what you have**:
   `store-listing/app-store/en-GB/screenshots/3.png` and `4.png`. Note the menu is from a recipe
   pack (server only); the caption should not suggest it is free.
4. **Used it up? It's on your list + Reminders when it suits you**:
   `store-listing/app-store/en-GB/screenshots/5.png` and `6.png`.
5. Optional, for Android: two of `store-listing/google-play/en-GB/phone-screenshots/1-6.png`, only
   once Play is public.

No new claims on the images: reuse the captions already on the screenshots.

## Maker's first comment (draft, about 2,600 characters)

Lines in [brackets] are for the owner to fill in or delete; the reason for building it must be your
own.

> Hi Product Hunt, I'm Daniil, a developer living in Norway, and I built BestBefore on my own.
>
> [Your reason in one or two sentences, e.g. what kept ending up in the bin at home and why the
> existing ways of keeping track did not stick for you.]
>
> **What it does**
> - Scan a barcode and the product is filled in from Open Food Facts.
> - Point the camera at the printed date and the phone reads it (on-device text recognition, the
>   photo is not uploaded).
> - Your food is sorted by where you keep it, and a reminder comes before something expires. Home
>   screen widgets show what to use first.
> - Essentials you use up go onto the shopping list.
> - Export a backup whenever you like.
>
> **New in this version: the Recipe tab.** It ticks the items closest to their date and writes a
> recipe from them, in one of three ways:
> 1. **Free**: share a ready-made prompt to any AI app you already use.
> 2. **Free, on the phone itself**, where the phone supports it: Apple Intelligence on recent
>    iPhones (iOS 26 or later), Gemini Nano on some Android phones. Nothing leaves the phone.
> 3. **Optional recipe packs**: recipes written by the app's server, bought once in the app, no
>    subscription. The 3-day menu comes from a pack too.
>
> Why a paid option at all: every recipe written on the server costs me money to run, so the server
> route is paid per pack, and the two free ways stay free.
>
> **Privacy**: no account, no ads, no tracking. The app sends crash reports, and barcodes are looked
> up on Open Food Facts; your pantry itself is stored on your phone only. A recipe from a pack sends the items you tick,
> your recipe settings and your allergens (only after you agree to it) to the server; the free ways
> send nothing to me.
>
> **Open source**: the whole thing, apps and backend, is on GitHub under Apache-2.0:
> https://github.com/daniil-pastuhov/FoodTrack. The iPhone app is SwiftUI, the Android app Jetpack
> Compose.
>
> It is on the App Store now: https://apps.apple.com/app/id6818647200. [Android: "Google Play is
> in closed testing; it will be public soon." or the Play link once it is public.] It speaks
> English, Russian, Norwegian, Spanish, German, French, Swedish and Danish.
>
> **What I would love feedback on**
> - Does the date reading work on your packaging? Which date formats or labels does it miss?
> - In the Recipe tab, which of the three ways would you actually use, and why?
> - What would keep you using a food tracker after the first week, or what made you stop using one
>   before?
>
> Thanks for reading, I'll be here all day to answer questions.

## Launch-day checklist

Before scheduling:

- [ ] The version with the Recipe tab is live on the App Store (and on Google Play, if linked).
- [ ] The `landing-page` pull request in `bestbefore-privacy` is merged and `app.html` loads.
- [ ] Gallery images made at 1270 × 760; thumbnail at 240 × 240.
- [ ] Every field re-checked against the live form's limits.
- [ ] The first comment's bracketed parts filled in or removed; it says nothing the app can't do.
- [ ] Launch scheduled (up to 30 days ahead) for a day you can be online from 09:00 Norway time,
      which is midnight Pacific Time, through the evening.

On the day:

- [ ] Post the first comment as soon as the launch is live.
- [ ] Reply to every comment, including critical ones; note bug reports as GitHub issues.
- [ ] Share the launch with people who know the app, asking for feedback, never for upvotes.
- [ ] Keep the App Store / Play pages and Crashlytics open for a spike in crashes.

After:

- [ ] Thank commenters; collect feature requests in one GitHub issue.
- [ ] Note what the feedback said about the three recipe routes for the next release.
