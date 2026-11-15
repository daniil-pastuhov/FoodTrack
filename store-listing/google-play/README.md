# Google Play store listing

The texts and graphics for the Play Console's "Main store listing", applied by the release flow
(below). One folder per listing language, under Play's language codes: `en-GB/` (English
(United Kingdom), the default language), `en-US/` (English (United States)), `no-NO/` (Norwegian), `ru-RU/` (Russian),
`es-ES/` (Spanish (Spain)), `es-419/` (Spanish (Latin America)), `de-DE/` (German), `fr-FR/`
(French (France)), `fr-CA/` (French (Canada)), `sv-SE/` (Swedish), `da-DK/` (Danish).
Each has `title.txt` (App name, at most 30 characters), `short-description.txt` (at most 80) and
`full-description.txt` (at most 4000; Play renders the `<b>` headings). Play has no keyword
field: it searches the title and both descriptions, so those carry the search words.
`scripts/check_store_listing.py` checks the limits and that no title says "free", "best", "#1"
or "top" or has an emoji, which Play's metadata policy forbids. Run it after every edit.

The graphics: `icon-512.png` (all languages), and in each of en-GB, no-NO, ru-RU, es-ES, de-DE,
fr-FR, sv-SE and da-DK a `feature-graphic.png` (1024×500, made by `scripts/store_art.py`) and
`phone-screenshots/` (made by `scripts/android_screenshots.sh` and `scripts/frame_screenshots.py`;
see [../app-store/README.md](../app-store/README.md)). `en-US/` has texts only: a translation
without its own graphics shows the default language's. `es-419/` and `fr-CA/` have texts only
too, but `play_listing.py` uploads es-ES's and fr-FR's graphics to them (`IMAGE_FALLBACKS`), so
they don't show the English ones; texts never fall back.

There is deliberately no `en-AU/` or `en-CA/` (both are Play listing languages, see Play's
[list of languages](https://support.google.com/googleplay/android-developer/answer/9844778)).
Unlike the App Store, Play has no keyword field, so a listing there could only add something
with its own title and descriptions; Australian and Canadian copies would be the en-GB texts
word for word, and users whose language has no listing see the default language (en-GB) anyway.
`play_listing.py` needs every file in a folder (it has no fallback), so add one only together
with texts that really differ.

The privacy policy URL is not here: Play has one for the whole app (Play Console > Policy >
App content), with no per-language field and nothing in the Publishing API's listings or app
details for it, so it stays the policy site's language chooser,
https://daniil-pastuhov.github.io/bestbefore-privacy/, set by hand. (The App Store's is per
language, `../app-store/README.md`, "Privacy policy URL per language".)

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
  differs on, the rest are deleted and uploaded again in order. es-419 and fr-CA get es-ES's and
  fr-FR's; any other language without images of its own (en-US) shows the default language's.
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

- **Release notes**: written per language in `release-notes/vX.Y.Z/` (en-GB, ru-RU, no-NO,
  es-ES, es-419, de-DE, fr-FR, fr-CA, sv-SE, da-DK; `scripts/release` writes them all and
  `scripts/check_release_version.sh` requires them) and set by `scripts/play_upload.py`. Not
  verified yet: whether Play accepts notes in a language its listing doesn't have, which is the
  case for the first release with the new languages until `play-listing` runs after the merge.
  If the internal upload refuses them, apply the listing first (`play_listing.py --apply`). A language without notes of its own, such as en-US, shows the
  default language's.

## Data safety

`data-safety.csv` is the whole Data safety form (App content > Data safety) in Play Console's
CSV format: every question and choice of a current export, answered for the version that sells
recipe packs. What it declares, and why, is in [../app-store/README.md](../app-store/README.md),
section 5 ("Google Play's Data safety form"). Nothing sends it automatically:

```sh
python3 scripts/play_data_safety.py            # offline: validates the file, prints what it declares
PLAY_SERVICE_ACCOUNT_JSON="$(cat key.json)" python3 scripts/play_data_safety.py --apply
```

`--apply` posts it to the Play Developer API (`applications.dataSafety`), which replaces every
answer, like "Import from CSV" in the Console. The API cannot read the form back, so the dry run
can't show what Play has now: compare in the Console first. If Play changes the form (new
questions), export it there (Export to CSV), bring the new rows into the file and into
`GENERAL` / `DATA_TYPES` in the script, and run the dry run again. Send it when the release that
sells packs goes to closed testing (Data safety applies to the closed track; docs/play-production.md).
