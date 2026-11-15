# Press pitch, English (draft)

Draft only: nothing here has been sent. Same timing and rules as `press-pitch-no.md`: send once the
version with the Recipe tab is live on the App Store, one email per outlet, to the address the
outlet gives on its own tips page. For Norwegian outlets use the Norwegian version; this one is
for English-language desks, international food-waste organisations, or a Norwegian contact who
prefers English. The list of Norwegian outlets and their contact pages is in `press-pitch-no.md`.

## Email

**Subject:** A developer in Norway made a free, private app that helps households use food before it goes off, and now writes recipes from it

> Hello,
>
> I'm Daniil Pastuhov, a developer living in Norway, and I built BestBefore on my own: a free app
> for iPhone (Android is in testing) that helps households use their food before it goes off.
>
> You scan a barcode and the product is filled in from the open Open Food Facts database. The
> phone reads the printed date, whether it is a best-before or a use-by date, and reminds you
> before something expires. In the new version, the Recipe tab writes a recipe from whatever
> expires first: free by sharing a ready-made prompt to an AI app you already use, free on the
> phone itself where it supports it, or from optional recipe packs with no subscription, which
> can also plan a menu set of one to four days. Packs start at USD 2.99 for 10 recipes, converted
> by the stores.
>
> It needs no account, has no ads and no tracking, and the pantry is stored only on the phone. The
> source code is open.
>
> In Norway, households threw away an average of 35 kg of edible food per inhabitant in 2023,
> according to NORSUS's waste-sorting analyses for Matvett, and Norwegian labels make the same distinction as elsewhere: "best
> før" is about quality, while food marked "siste forbruksdag" should not be eaten after the date
> (Mattilsynet, the Norwegian Food Safety Authority). BestBefore is made for that everyday
> problem: knowing what is in the fridge, and when to use it.
>
> More about the app, with screenshots: https://daniil-pastuhov.github.io/bestbefore-privacy/app.html
> App Store: https://apps.apple.com/app/id6818647200
> Source code: https://github.com/daniil-pastuhov/FoodTrack
>
> I'd be glad to talk or show the app.
>
> Best regards,
> Daniil Pastuhov
> [phone number]

Before sending: open the sources below and check the figure and links still hold. Do not add
figures on how much food or money the app saves.

## Fact sheet (one page)

**BestBefore** (Norwegian: Matvakt, Russian: Срокоед)
A free app for keeping track of the dates on the food at home.

- **Who:** Daniil Pastuhov, independent developer based in Norway.
- **Platforms:** iPhone and iPad (App Store, live). Android: Google Play, in closed testing, not
  public yet.
- **Languages:** English, Russian, Norwegian, Spanish, German, French, Swedish and Danish (app);
  recipes can be written in eleven languages.
- **Price:** free, no ads, no subscription. Optional recipe packs as one-off in-app purchases:
  10, 50 or 100 recipes, from USD 2.99 for 10 (other currencies as converted by the App Store
  and Google Play).
- **Privacy:** no account; the pantry is stored only on the phone; no tracking (the app sends crash
  reports, and barcodes are looked up on Open Food Facts). A recipe from a pack sends the items
  ticked, the recipe settings and the allergens (only after consent) to the server. The free ways
  send nothing to the developer.
- **Open source:** Apache-2.0, https://github.com/daniil-pastuhov/FoodTrack

**What it does**

- Scan a barcode: the product is filled in from Open Food Facts.
- The phone reads the printed date (on-device text recognition).
- Food is sorted by where it is kept: fridge, freezer, cupboard.
- A reminder before something expires; home screen widgets show what to use first.
- Essentials you use up go onto the shopping list.
- Backup export.

**New: the Recipe tab**

- Ticks the items that expire first and writes a recipe from them.
- Free: share a ready-made prompt to an AI app you already use.
- Free on the phone itself where supported: Apple Intelligence on recent iPhones (iOS 26 or later),
  Gemini Nano on some Android phones (English recipes for now).
- Optional: recipe packs written by the app's server, one-off purchases. Menu sets come from a
  pack: 1 to 4 days, one to three courses a day; a recipe takes one from the pack, a menu set
  takes more by its size (the app shows how many first).
- A recipe's missing ingredients go onto the shopping list in one tap; a recipe that uses an item
  past its date warns about it.

**Background (sources)**

- Norwegian households: 35.0 kg of food waste per inhabitant in 2023 (193,200 tonnes), down 18 %
  per inhabitant from 42.6 kg in 2016 (40.3 kg in 2020). "Food waste" (matsvinn) here is the
  edible part only ("Matsvinnet er den nyttbare delen av matavfallet"); all household food waste,
  inedible parts included, was 70.6 kg. Measured by waste-sorting analyses (plukkanalyser) from
  municipalities covering 42 % of the population, scaled with Statistics Norway's waste and
  population figures; not a questionnaire survey. NORSUS (author Aina Stensgård) for Matvett,
  "Faktaark om matsvinn i husholdningene 2023", report OR.16.24, 2024 (checked 2026-11-15):
  https://www.matvett.no/uploads/documents/Rapporter/OR.16.24-Faktaark-om-matsvinn-i-husholdningene.pdf
  (linked from https://www.matvett.no/rapporter); repeated as "35 kg mat per person i norske
  husholdninger i 2023" in Matvett's "Slik var matsvinnåret 2024":
  https://matvett.no/uploads/documents/Slik-var-matsvinnaret-2024.pdf
- "Best før" and "siste forbruksdag": Mattilsynet, "Holdbarhetsmerking på matvarer", published
  31 March 2022, checked 2026-11-15: "Du bør altså ikke spise varer merket med «siste
  forbruksdag» etter utløpsdato" (you should not eat them after the date); "best før" is guidance
  on how long the quality can be expected to last, and "«Best før» betyr ofte god etter":
  https://mattilsynet.no/mat-og-drikke/merking-av-mat/holdbarhetsmerking-pa-matvarer
- Norway's target of halving food waste by 2030 (the industry agreement, bransjeavtalen):
  regjeringen.no, "Matsvinn":
  https://www.regjeringen.no/no/tema/mat-fiske-og-landbruk/mat-og-ernaring/matsvinn/id2891197/

**Images:** screenshots in `store-listing/app-store/en-GB/screenshots/` (1–6) and the icon
`store-listing/google-play/icon-512.png` in the app repository; send on request.
