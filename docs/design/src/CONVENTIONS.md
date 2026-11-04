# Conventions for feature pages

Read `../brief.md`, `pages/system.html`, `pages/shell.html`, `components.css`, and `~/.claude/skills/design/references/{error-framework,states,accessibility}.md` plus `build.md` for the markup contract.

## Files
- Write only `pages/<page>.html` and `css/<page>.css`. Do not edit `components.css`, `system.html`, `shell.html`, `index.html` or `tokens.json`. If a piece should be shared, keep it in your css and report it.
- Do not run git. Do not build `src/index.html`: other agents are writing at the same time. Build your own preview (below).

## Screen skeleton
Each state's root is `.app`. Add `has-nav` on tab screens. Content scrolls inside `.scroll` (add `has-fab` when Scan is shown). Copy the shell from `pages/shell.html`: `.shell` > `.pane`, then `.fab` and `.bottom-nav`, shown on every viewport. Hidden overlays: `data-overlay hidden`; static: `data-overlay data-static`.
Pushed screens (Add item, Categories) use `.topbar` with a back icon and no nav. On tablet use `.col` to centre them, or `.pane.list-pane` + `.pane` for two panes.

## Components to use (see system.html)
`.st .st-expired|today|soon|fresh`, `.item` row with `.thumb`, `.nm`, `.meta`, `.where`, `.qty`; `.group`, `.sec`, `.hd`, `.bar`, `.brand`, `.lede`, `.chips`, `.chip`, `.btn`, `.field`, `.tick`, `.switch`, `.stepper`, `.segmented`, `.card`, `.callout`, `.snackbar` in `.snackbar-host`, `.overlay` + `.dialog`, `.overlay.sheet-overlay` + `.sheet`, `.empty` with `<svg class="art"><use href="#art-shelf|art-basket|art-clear"/></svg>`, `.screen-error`.
Icons: `<i data-icon="name">`, Lucide 0.460 names only, listed on the system page.

## Rules
- Tokens only, no hard-coded colours. New feature CSS is scoped under a page-specific class.
- Every state has `data-trigger`; errors have `data-note` naming the tier. First tab is the most common state. Order: loaded, empty, errors, stress, overlays.
- Include one state with Russian strings on each main screen, since Russian runs long. Use the real wording from `androidApp/src/main/res/values-ru/strings.xml` and `values/strings.xml`.
- Product photos: no external images. Use `.thumb` with a category icon, and say in a note that real photos replace it.
- Pantry data is local: no loading, refresh or network error states on lists. Reversible actions use T3 with Undo.
- Status wording is factual and calm: "1 day over", "Today", "Tomorrow", "In 2 days". Never "Expired!" or blame. Fresh has no tint.
- Copy is British English, plain, no exclamation marks, no "Oops". Today is Wed 4 Nov 2026 in all samples. Sample pantry: Sourdough bread (3 Nov), Baby spinach (4 Nov), Greek yoghurt ×2 (5 Nov), Semi-skimmed milk (6 Nov), Salmon fillet ×2 (7 Nov), Strawberries (8 Nov), Free-range eggs ×6 (19 Nov).
- Tablet: every tab screen and form must look right at viewport=tablet (820 wide), not stretched.
- Check the real feature in `androidApp/.../ui/screens/` and `iosApp/FoodTracker/Views/` so nothing that exists is dropped. If you change behaviour, say so in a `data-note` and in your report.

## Preview
```bash
T=~/.claude/skills/design/scripts/design_tools.py

```
Make `src/preview-<page>.html` by copying `index.html` and removing the other pages' `include` lines, then `python3 $T build src/preview-<page>.html <scratch>/<page>.html` and `python3 $T shot <scratch>/<page>.html '#<page>/<screen>/<state>?theme=dark&viewport=phone' <scratch>/x.png --height 900`. Delete the preview file when done. Screenshot every state in light, spot-check dark and tablet, and fix clipping, overflow and text behind Scan.

## Shared decisions
- Home has one primary action, the Scan FAB, and no plus button. Add by hand is "Type it in" in the scanner.
- Tab links in every nav use these targets, so flows connect across pages. Keep these ids exactly:
  `pantry/home/loaded`, `pantry/home/empty`, `calendar/agenda/loaded`, `shopping/list/loaded`, `settings/main/default`, `add/item/new`, `add/item/prefilled`, `scanner/barcode/ready`, `scanner/barcode/found`, `scanner/date/ready`, `scanner/batch/queue`, `onboarding/first-run/welcome`.
- Scan buttons go to `scanner/barcode/ready`. The scanner is full-screen, with no tab bar.
