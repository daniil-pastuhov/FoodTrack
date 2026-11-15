# Launch plan (recommendation, 2026-11-15)

The owner decides the day; this page gives one, the reasons, and the dated checklist that works
back from it. State of the stores and every open item in detail: [`HANDOFF.md`](HANDOFF.md).

## Recommended launch day: Tuesday 12 January 2027 (R)

The launch is the day the public announcements start and the in-app event runs, on a version
released by hand. It is not the day 2.1.0 goes live: 2.1.0 was submitted with release type "after
approval", so Apple's approval (usually within a day or two) puts the
Recipe tab live in November, quietly. Three dates decide R:

1. **Apple's featuring lead time, at least three weeks** (HANDOFF.md, "Things to verify": Apple
   says two weeks on one page and three on two others; it also suggests up to three months).
   A nomination submitted on Tue 1 December leaves six weeks before R; the last safe day is
   Tue 22 December. 2.1.0 itself can't be nominated with that notice, because it goes live on
   approval.
2. **Play's 14 tester days.** With 12 testers opted in by Sun 22 November, the earliest
   production application is Sun 6 December (safer Mon 7 December) and access comes by about
   14 December; with 12 only by Sun 29 November, the application is Sun 13 December (safer
   Mon 14) and access by about 21 December (`docs/play-production.md`, "Calendar"). Play's first
   production review has no published duration and may be slower over the holidays, so a public
   Play listing is likely only in late December or early January. 12 January leaves room for
   the slower case, so both stores can be announced on the same day.
3. **App Store review of the launch version**: about one to two days. Submitting it about a week
   before R (release PR merged Tue 5 January, release type `MANUAL`) leaves time for one rejection
   and a resubmission, after the holidays, when reviews may be slower.

Other reasons: the second week of January is a natural moment for "use up what's in the fridge"
and saving money; Tuesday to Thursday mornings suit the press pitch (`README.md` in this folder);
the in-app event's 30 days cover the rest of January; and the gap gives the five new languages a
native read and the next release its refreshed screenshots. If Play turns out to be public by
mid-December and the owner wants an earlier start, Tue 8 December works iOS-first only if the
nomination goes in by Tue 17 November; the trade-off is a launch without Android and without the
native read.

## Checklist, working back from R

| Date | What | Who |
|---|---|---|
| Now, before Apple approves 2.1.0 | Decide whether 2.1.0 goes live on approval (recommended) or is pulled from review to be held for R (HANDOFF.md, open item 1) | Owner |
| Sun 15 – Sun 22 Nov | Tester recruitment: Google Group on the closed track, invitations from `play-testers/`, chase people who joined but didn't opt in. Goal 15 by Sun 22 Nov | Owner |
| Day 2.1.0 is approved | `allowSandbox` = `false` in Firestore `config/runtime`; look at the live product page in a few languages | Owner |
| 2.1.0 live + 1 day | Post the Open Food Facts reuse submission (`open-food-facts-reuse.md`) | Owner |
| By Fri 27 Nov | Decide the event's framing (question 3 below); set `releaseDay` to `2027-01-12` in `store-listing/app-store/in-app-event/event.json` (texts changed if the framing changes) | Owner; agent edits the files |
| By Tue 1 Dec | `python3 scripts/testflight.py event` (dry run), then `event --apply`: the event is created as a draft with its images; preview card and details page in App Store Connect | Owner |
| Tue 1 Dec (last safe day Tue 22 Dec) | Submit the featuring nomination (`store-listing/app-store/featuring-nomination.md`): App Enhancements, publish window 11–17 Jan 2027, the event's ID attached | Owner |
| Sun 6 / Mon 7 Dec | Apply for Play production access, if 12 testers were in by 22 Nov (`docs/play-production.md`, step 8) | Owner |
| By Fri 18 Dec | Native read of es, de, fr, sv, da: store texts (start with `git diff v2.1.0 -- store-listing/`), onboarding, release notes; fixes into the repo | Owner finds readers; agent applies fixes |
| By Fri 18 Dec | Merge `chore/store-screenshots-2.1` (screenshots and art regenerated for menu sets; the preview videos too, if that branch remakes them) | Agent prepares, owner merges |
| Mon 4 Jan | Recheck every draft (posts, pitches, Product Hunt) against the launch version; open the press sources again | Agent |
| Mon 4 Jan | `APP_STORE_RELEASE_TYPE` = `MANUAL` (repository variable); `allowSandbox` = `true`; `testflight.py event --apply --add-to-review` | Owner |
| Tue 5 Jan (R − 7) | Merge the launch version's release PR (cut with `scripts/release`): CI submits it with the event; Play closed testing gets it | Owner, CI |
| On approval | `allowSandbox` = `false` again; Play production release of the same version, timed to go public on or before R (`docs/play-production.md`, step 9) | Owner |
| **Tue 12 Jan (R)** | Release the version by hand in App Store Connect; the event goes live; Norwegian and Russian community posts (`community-posts.md` §4, §5) | Owner |
| Tue 12 – Thu 14 Jan | Norwegian press pitch, one outlet a day, mornings (Matvett, Framtiden i våre hender, Tek.no, Kode24 first) | Owner |
| Week of 18 Jan | English zero-waste and meal-prep posts (§1, §2), a few days apart; the apps post (§3) once Play is public | Owner |
| Tue 19 or Wed 20 Jan | Product Hunt launch (`product-hunt.md`), scheduled ahead, a day the owner can stay online from 09:00 Norway time | Owner |
| Any time after | English press pitch (`press-pitch-en.md`) | Owner |
| Thu 11 Feb | The event ends (30 days) | Apple |

## Questions only the owner can answer

1. Let 2.1.0 go live on approval, or pull it to hold the Recipe tab for the launch day?
2. Is 12 January the day, or an earlier iOS-first start (8 December, nomination by 17 November)?
3. The event's badge: "Major Update" for a Recipe tab that will have been live for about eight
   weeks, or a January challenge ("use up what's in the fridge") with the same deep link.
   Apple asks events to be timely; the second framing is the safer fit if 2.1.0 goes live now.
4. Who reads the five new languages, and is a launch in those storefronts conditional on it?
