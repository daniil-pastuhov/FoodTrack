# Design brief: BestBefore

Status: iterating (full design published 2026-11-04)
Artifacts: options https://claude.ai/artifact/TeLu92BMaS1QDhDVj98fPw · design https://claude.ai/artifact/4PMj2X5GtKoCijR5SjktHY

## Product
- What it is: BestBefore (repo: FoodTracker; ru: Срокоед, no: Matvakt). Tracks what's in the fridge, freezer and cupboards and reminds people before food goes off. No account, no ads, data stays on the device.
- Users and context of use: households, in the kitchen or at the shops, usually one-handed and in short sessions
- Platforms / viewports: phones, iPad, Android tablets and foldables. Android (Compose, Material 3) and iOS (SwiftUI), two separate implementations.
- Themes: light + dark, default follows system (the app already offers System / Light / Dark)
- Accessibility target: WCAG 2.2 AA
- Implementation target (for platform conventions): Jetpack Compose + SwiftUI

## Mood and brand
- Feels like: warm and homely, calm and reassuring
- Must not feel like: guilt or alarm; clinical or a health app; childish or cartoonish; corporate or a spreadsheet
- Fixed brand assets: only the name. Palette, type, shape and icon direction are open. Today: teal #0D9488 / #14B8A6, white jar + barcode + amber clock app icon, system fonts, slate neutrals.
- References and what's liked about each: none given
- Density: balanced, about 6 to 7 pantry rows per phone screen
- Type: custom open-licence display face for headings and dates (Latin + Cyrillic), system fonts for body text

## Content
- Language / locale: en-GB, ru-RU, nb-NO. Type must cover Cyrillic and Norwegian letters; Russian and Norwegian strings run longer than English.
- Voice and tone: plain and helpful, from store copy ("reminds you before food goes off, not after")
- Sample data domain: groceries (Sourdough bread, Baby spinach, Greek yoghurt, Salmon fillet)

## Screen inventory (from the code, to confirm)
| Feature | Screen | Primary action / question | Notes (data, destructive actions, offline) |
|---|---|---|---|
| Pantry | Home dashboard | What do I need to use first? | Expired / Soon / Fresh metrics, search, filter + sort sheets, row: image, name, status, location, category, quantity; use one, swipe, multi-select, delete with undo |
| Pantry | Product detail sheet | Is it OK for me? | Nutri-Score, NOVA, allergens, ingredients |
| Add | Add / edit item | Save with the right date | Name, brand, date + quick dates and suggestions, location, category, quantity, notes, essential, per-item reminder; implausible date warning |
| Add | Scanner | Capture a product | Modes: Barcode, Date OCR, Photo, Batch, Receipt; lookup, not found, duplicate dialog, allergen warning, torch |
| Recipe | Recipe tab | What can I cook from what expires first? | Items ticked soonest-first, quick form, three routes: recipe from a pack (server), on this phone (free), prompt for another AI app (free). Menu for 3 days, server only |
| Recipe | Recipe and menu | Cook it | Saved automatically; menu is 3 days of 2 or 3 full recipes |
| Recipe | Saved recipes | Find it again | On this phone only; delete with Undo |
| Recipe | Buying recipes | Buy a pack, know where it is kept | Packs of 10, 50, 100; first purchase chooses no account or sign-in; store and server failures |
| Recipe | Recipe settings | Set how recipes are written | Difficulty, detail, servings, time, meal, diet, language; account, sign out, delete account (destructive); recipes on this phone: delete my data from our server (destructive) |
| Shopping | Shopping list | What do I need to buy? | Sections, did-you-mean, share text/QR, import, move to section |
| Settings | Settings | Change a preference | Notifications, threshold, locations, categories, allergens, appearance, stores, backup, clear data (destructive) |
| Settings | Categories | Organise categories | Reorder, rename, hide, shelf life |
| Onboarding | First run (new) | Get to a first item | Welcome, then notification and camera rationale; the app has no onboarding today |
| System | Widgets | Glance | Expiring, Shopping list, Overview |
| System | Notifications | Act on a reminder | Used it / Tomorrow / Remove / Recipe ideas |
| System | Background location disclosure | Consent | Store arrival reminders |

## State plan
Pantry data is local, so list screens have no loading, refresh or network error states. Barcode lookup, product images and shop location touch the outside world, and so does the Recipe tab when a recipe is written by our server, a pack is bought or someone signs in. Those have loading, offline and failure states.

| Screen | States (tier) |
|---|---|
| Onboarding | Welcome · Notifications rationale · Camera rationale · Ready to add first item · Permission declined, continues without it (T4 later on the screen that needs it) |
| Home | Loaded · Empty first use · All fresh · No search results · Search active · Filter sheet · Sort sheet · Multi-select · Item used, Undo (T3) · Item removed, Undo (T3) · Delete selected (T6) · Notifications off (T4) · Long names, 999+ items, missing images, 1 item |
| Product detail | Full info · Partial data, gaps closed · Allergen match (T4) · Long ingredients |
| Add / edit | New blank · Prefilled from scan · Suggested date · Name missing (T1) · Implausible date (T1 warning) · Date picker · Edit existing · Discard changes (T6) · Long content |
| Scanner | Barcode ready · Lookup in progress · Found · Found with date · Allergen match (T4) · Not found · Offline lookup (T4, continue by hand) · Date OCR ready · Date found · Photo ready · Photo captured, suggestions · Batch empty · Batch with queue, Check date · Receipt ready · Receipt reading · Nothing found on receipt (T3) · Already in pantry (T6) · Camera denied (T5 with path to settings) |
| Recipe tab | Ready with recipes · Phone can write too · No recipes (phone / prompt only) · Menu for 3 days · Menu, too few recipes (T4) · Nothing ticked · Writing (server, menu, phone) · Offline (T4) · Could not write, nothing taken (T3) · Refused, counted (T3) · Server paused (T4) · Phone model preparing (T4) · Empty pantry · Russian |
| Recipe and menu | Recipe from a pack · Just arrived (T3) · Written on the phone · Menu · Dish from a menu · Long recipe |
| Saved recipes | Loaded · Empty · Deleted, Undo (T3) |
| Buying recipes | Packs · Account or not · Adding · Added (T3) · Paid, not added yet (T4) · Payment pending (T4) · Store unavailable (T4) · Not accepted (T6) |
| Recipe settings | No account · Recipes on this phone · Signed in · Language · Sign out (T6) · Delete account (T6) · Delete this phone's data from our server (T6), Deleted (T3) · Deleting waits for a purchase being confirmed (inline, button off) · Sign-in already has recipes (T6) · Sign-in failed (T3) |
| Shopping | Loaded with sections · Empty · Add with did-you-mean · Item ticked · Section menu, rename, delete section (T6) · Move to section · Share · QR code · List too long for QR (T4) · Import · Import results (T3) · Long list |
| Settings | Default · Notifications blocked (T4) · Reminder styles · Locations editing · Allergens · Shops: permission disclosure (T6), location failed (T3) · Backup: export success or failure (T3), restore choice (T6), bad file (T3) · Clear all data (T6) |
| Categories | Loaded · Empty · Rename · Shelf life dialog, out of range (T1) · Reordering |
| Widgets | Expiring, Shopping, Overview, each with content and empty, small and medium, light and dark |
| Notifications | Per item · Summary · Shop arrival, each with actions |

Tablet and foldable: Home, Recipe and Shopping get list-detail or two-pane layouts with the same floating pill as phones. The rest use a centred column.

Removing one item, marking used or tossed, and removing from a queue are reversible, so they are T3 with Undo. Clear all data and Replace everything on restore are T6. Deleting several items is T3 with one Undo.

## Key flows to make clickable
- First run: onboarding to empty Home to first scan
- Scan: barcode to found to add to Home with confirmation
- Scan a date with Date OCR, returning to Add item
- Batch: scan several, then Add all
- Use one item and Undo
- Search with no results, then clear
- Shopping: add, tick, share as QR
- Clear all data dialog

## Decisions log
- 2026-11-04 · Scope is the system plus every screen and state, including scanner modes, widgets and notifications · user choice
- 2026-11-04 · One shared look with native behaviour per platform (Material 3 / HIG navigation, sheets, dialogs, pickers) · user choice
- 2026-11-04 · All tablets are in scope, Android tablets and foldables included, which goes beyond what iPad targets ship today · user choice
- 2026-11-04 · Add a short first-run flow, which is new to the app · user choice
- 2026-11-04 · Fresh items carry no colour, only soon, today and past-date items are tinted · supports the no-guilt, no-alarm mood
- 2026-11-04 · Display faces must have Cyrillic, which rules out Fraunces; candidates are Vollkorn, Nunito, Bitter · the app ships in Russian
- 2026-11-04 · Home keeps one action button, Scan. The separate plus button is removed altogether; Add by hand is reached through Type it in on the scanner · user decision
- 2026-11-04 · Feature pages are written by parallel subagents, one per feature · user choice
- 2026-11-04 · Calendar rows open product detail like Home, day marks use shape plus count, snackbars say used or tossed rather than removed · consistency and no reliance on colour (superseded: Calendar removed 2026-11-13)
- 2026-11-04 · Add item: Save stays enabled and shows a T1 error when the name is missing; Android asks before discarding changes; Save moves to a pinned bar on both platforms · error framework rules
- 2026-11-04 · Shopping rows get a visible tick as well as swipe, and the swipe reveal becomes green with a tick; ticking removes the row with Undo · no action depends on a gesture
- 2026-11-04 · Settings: phone is one long page in four groups, tablet is section list plus detail; drag handles also offer Move up and Move down (WCAG 2.5.7); out-of-range shelf life and duplicate locations show T1 errors instead of silent handling · error framework and accessibility
- 2026-11-04 · Camera declined stays T5 on the scanner, notifications declined is T4 on Home · nothing else to show on the scanner
- 2026-11-04 · Scanner: camera UI stays dark in both themes; modes move to a five-slot bottom bar; the duplicate dialog and allergen callout appear on the scanner; suggestions move to the capture panel; queue remove and Clear gain Undo · thumb reach and earlier feedback
- 2026-11-04 · Allergen matches use the error style on every page · a safety message; the no-alarm rule is about dates, and the app already uses error colours
- 2026-11-04 · Save, Add all and Add one more land on Home confirmation states; picked photo suggestions go to add/item/photo-picked so they show once
- 2026-11-04 · Delete selected uses one Undo with no dialog, overriding the state plan · user decision
- 2026-11-04 · Android per-item notifications become Used it, Tomorrow, Remove; Still good +3d goes on Android, iOS keeps all four · user decision
- 2026-11-04 · Calmer notification copy approved, ru and nb still need a translator · user decision
- 2026-11-04 · Onboarding wording "Your pantry stays on your device" approved · user decision
- 2026-11-04 · Nutri-Score uses its official colours on a dark plate, in both themes, so all five reach 3:1 · user decision
- 2026-11-04 · Main navigation keeps the app's floating pill bar, restyled in Larder colours and shapes, on both platforms; it replaces the docked Material bar and iOS tab bar in the design · user decision
- 2026-11-04 · No navigation rail or sidebar: tablets and foldables use the floating pill, with Scan at the bottom right in thumb reach · user decision; the rail took a column and put Scan in the least reachable corner
- 2026-11-05 · iOS Add item: swiping down on a changed form is blocked and Cancel asks first; a swipe can't raise the dialog on iOS 27 · nothing is lost
- 2026-11-04 · The user asked for a new design system, so the existing theme is replaced rather than extended · from the /design request
- 2026-11-13 · The Calendar tab is removed and Recipe takes its place; the recipe-ideas dialog on Home becomes one route of the new tab · owner decision
- 2026-11-13 · Recipes can be written three ways: by our server from a paid pack, by the phone's own model for free, or as a prompt shared to another AI app for free. A menu for 3 days (2 or 3 courses a day) is server only and takes 3 recipes · owner decision
- 2026-11-13 · The unit people buy is called a recipe, not a credit or token: 10 recipes buys ten dishes · plain words
- 2026-11-13 · The app gains a server, purchases and optional sign-in. "No account, data stays on the device" stays true for anyone who never buys: nothing reaches our server until a purchase starts · owner decision
- 2026-11-13 · The first purchase asks where the recipes should live, on this phone with no account or in an Apple or Google account, with the consequence of each spelled out · owner decision
- 2026-11-13 · On the Recipe tab the primary action is pinned above the nav where Scan sits on Home, and Scan is on Home only · the form is taller than a phone screen
- 2026-11-13 · A recipe is taken only when a result arrives; the count changes when the result is shown · trust
- 2026-11-13 · A menu for 3 days takes 4 recipes with two courses a day and 5 with three, not 3; a single recipe still takes 1. Supersedes the "takes 3 recipes" line above. The button, hint and too-few callout follow the selected course count · owner decision
- 2026-11-13 · What a recipe and a menu take comes from the server with every balance (`costs`); the apps keep the last value and never hard-code it, falling back to 1 / 4 / 5 only before the first answer · owner decision; prices can change without an app release
- 2026-11-13 · Failures on our side are always given back, but after several in one day the server stops writing until the next day. A recipe that fails because of the request itself (the model refuses it, or it comes back incomplete) is given back a limited number of times, one for every ten recipes bought; after that such failures count. Replaces "three a day" · owner decision; limits abuse without charging honest failures
- 2026-11-13 · Packs of 10, 50 and 100 recipes cost USD 3.99, 10.99 and 18.99, converted by each store; the page shows them as £3.99, £10.99 and £18.99 samples · owner decision

## Options history
- Round 1: A Larder, B Enamel, C Preserves → A Larder chosen by the user, the closest fit to warm and calm

## Open questions
- Android: after one camera refusal the scanner opens straight on T5 with no second system prompt; an expired "Only this time" grant might read as a refusal. Check in the final pass.
- Shopping: the row grip is 28dp wide as designed, below the 48dp and 44pt minimum.
- Android has no non-drag way to reorder Shopping sections (WCAG 2.5.7).
- Scanner: the Batch hint says "the list below", but on tablet the list is on the right.
- Pantry: To shopping list moves the item out of the pantry, as it does today, which may surprise someone who only wants to buy more.
- Reconcile at final build: scanner `found` shows "In your pantry: 0"; hide the line at zero, as the app does.
- Scanner: Check date has no way to fix the date from the queue, in the app or here.
- App: the current camera hint (white 85% on a 60% scrim) is about 4.05:1, below AA; the design uses a 72% scrim. Offline needs separating from Not found in the lookup API.
- App: camera refusal has no message on Android, and iOS likely has no handling.
- App bug found: a reminder snoozed past its date says "expires today" on both platforms (notificationCopy and expiryContent treat daysRemaining <= 0 as today).
- iOS widget tick is a bare checkmark with a hit area under 44pt, and VoiceOver reads "Bought" on every row; the design draws a ring.
- iOS Overview has no small size, so a labelled tile stands in. iOS large widgets are not drawn.
- Shop arrival notifications have no actions on either platform; drawn as tap only.
- Settings: restore asks for the mode before reading the file; validating on pick would be better. Today's order kept.
- Settings: declining the location disclosure means shop reminders only work with the app open; a T4 callout needs new copy.
- Settings: new Russian strings on the page are drafts, not reviewed copy.
- Widget tick: ic_widget_check.xml is a ring with a check, while `.tick` off is an empty ring. Align one to the other.
- Shopping: adding a name already on the list does nothing visible; a T3 message would help, not designed yet.
- Shopping: Save and Import stay disabled when blank, matching the app, though the error framework advises against disabling submit.
- Shopping: new headline strings ("N things to buy", "Купить: 24 товара") need plurals and a Russian review.
- Shopping: the New section icon uses `layers`, which also suggests Batch; `list-plus` would be clearer.
- Add item: iOS shows it as a modal with Cancel today, the mock-ups show a back arrow; iOS keeps Cancel unless decided otherwise.
- Add item: quantity limit differs (iOS 99, Android none) and the design sets none.
- Add item: "Add to Pantry" reads oddly above "Save to Fridge".
- Translation: discard dialog body has no ru or nb text yet.
- Russian past-date label "Просрочено 1 день назад" is long and sterner than "1 day over"; needs a new string. It now shows on the Recipe tab's rows.
- Recipe: every Russian string on the page is new and needs a translator's check; Norwegian has none yet.
- Recipe: pack prices on the page are samples; real ones come from the stores.
- Recipe: Apple and Google sign-in buttons use stand-in icons; the real ones must follow each company's button rules.
