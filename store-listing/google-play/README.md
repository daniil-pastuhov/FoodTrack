# Google Play store listing

The texts and graphics for the Play Console's "Main store listing", applied by the release flow
(below). One folder per listing language, under Play's language codes: `en-GB/` (English
(United Kingdom), the default language), `en-US/` (English (United States)), `no-NO/` (Norwegian), `ru-RU/` (Russian).
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

## How it reaches Play

`scripts/play_listing.py` applies this folder; CI runs it with `--images --apply` when a release
PR merges, right after the build is promoted to closed testing (job `play-listing`), so the
listing changes with the release that has what it describes. Nothing needs pasting in the Play
Console.

- **Texts**: each locale folder's title and descriptions. A listing Play doesn't have yet is
  created (en-US was added this way); an existing one is patched with only the fields that
  differ, so the promo video and anything else without a file here is kept.
- **Images**: `phone-screenshots/` (in file-number order, at most 8), `feature-graphic.png`, and
  `icon-512.png` for the default language. Images are compared with Play's SHA-256, so unchanged
  ones are not uploaded again. Play can't reorder images, so from the first screenshot that
  differs on, the rest are deleted and uploaded again in order. A language without images of its
  own (en-US) shows the default language's.
- `scripts/check_store_listing.py` runs first; text over a limit stops the run before Play is
  contacted. Every commit goes through Play's review, like a change made in the Play Console.
  Re-running the job is safe: when nothing differs it commits nothing.

To see the difference without writing anything (the edit it opens is discarded):

```sh
pip install google-api-python-client google-auth-httplib2
PLAY_SERVICE_ACCOUNT_JSON="$(cat key.json)" python3 scripts/play_listing.py --images
PLAY_SERVICE_ACCOUNT_JSON="$(cat key.json)" python3 scripts/play_listing.py --images --validate-only  # Play checks the edit
PLAY_SERVICE_ACCOUNT_JSON="$(cat key.json)" python3 scripts/play_listing.py --images --apply          # between releases
```

- **Release notes**: written per language in `release-notes/vX.Y.Z/` (en-GB, ru-RU, no-NO) and
  set by `scripts/play_upload.py`. A language without notes of its own, such as en-US, shows the
  default language's.
