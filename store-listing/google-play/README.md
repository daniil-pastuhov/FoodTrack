# Google Play store listing

The texts and graphics for the Play Console's "Main store listing", ready to paste. One folder
per listing language, under Play's language codes: `en-GB/` (English (United Kingdom), the
default language), `en-US/` (English (United States)), `no-NO/` (Norwegian), `ru-RU/` (Russian).
Each has `title.txt` (App name, at most 30 characters), `short-description.txt` (at most 80) and
`full-description.txt` (at most 4000; Play renders the `<b>` headings). Play has no keyword
field: it searches the title and both descriptions, so those carry the search words.
`scripts/check_store_listing.py` checks the limits and that no title says "free", "best", "#1"
or "top" or has an emoji, which Play's metadata policy forbids. Run it after every edit.

The graphics: `icon-512.png` (all languages), and in each of en-GB, no-NO and ru-RU a
`feature-graphic.png` (1024×500, made by `scripts/store_art.py`) and `phone-screenshots/` (made by
`scripts/android_screenshots.sh` and `scripts/frame_screenshots.py`; see
[../app-store/README.md](../app-store/README.md)). `en-US/` has texts only: a translation without
its own graphics shows the default language's.

## Manual steps in the Play Console

Nothing in this folder is uploaded by a script; `scripts/play_upload.py` only uploads builds and
their release notes.

- **Changing the texts**: Grow users > Store presence > Store listings > Main store listing,
  pick the language, paste, save, then send the change for review under Publishing overview.
  Unlike the App Store, the title and descriptions change without a new app version; each change
  goes through Play's review.
- **Adding English (United States)** (once): on the same page, Manage translations > Add your
  own translations > English (United States) – en-US, then paste the three `en-US/` texts. Leave
  its graphics empty to use en-GB's.
- **Release notes**: written per language in `release-notes/vX.Y.Z/` (en-GB, ru-RU, no-NO).
  A language without notes of its own, such as en-US, shows the default language's.
