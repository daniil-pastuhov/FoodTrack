# Google Play: from closed testing to a public listing

Written 2026-11-15. The path from where the Android app is today to a listing anyone can find
and install on Google Play, with dates, who does each step, and the declarations the Recipe
release changes. Google's rules were read on 2026-11-15 from the Play Console Help pages linked
below; quotes are verbatim. Anything marked **(inferred)** or **(unverified)** is not stated by
Google and should be checked in Play Console before relying on it.

The tester recruitment kit (invitations in English, Russian and Norwegian, a "how to join" page,
and how to manage testers) is in [marketing/play-testers/](marketing/play-testers/README.md).

## Where things stand (2026-11-15)

- Package `com.foodtracker.android`, on a **personal** developer account registered in
  October 2026.
- Internal testing: "Recipe preview 2.0.0 (1063)". Closed testing (`alpha`): 2.0.0 (1061).
  No production release, so the app has no public listing: nobody can find it in search or
  install it without being a tester.
- The Recipe release (Recipe tab, recipe packs `recipes_10` / `recipes_50` / `recipes_100` as
  Play one-time products, created 2026-11-14) is still to be cut with `scripts/release`.

## What Google requires (read 2026-11-15)

Source: "App testing requirements for new personal developer accounts",
<https://support.google.com/googleplay/android-developer/answer/14151465>.

- **Who**: "Google Play requires personal developer accounts created after November 13, 2023, to
  test their apps before those apps are eligible for distribution on Google Play." This account
  is one of them.
- **What stays locked until then**: "Certain features in Play Console, such as Production (Test
  and release > Production) and Pre-registration (Test and release > Testing > Pre-registration),
  remain disabled until developers meet these testing requirements."
- **The rule**: "Developers with personal accounts created after November 13, 2023, must run a
  closed test for their app with a minimum of 12 testers who have been opted in continuously for
  at least 14 days."
- **At the moment of applying**: "At least 12 testers must be opted in to your closed test when
  you apply for production access, and they must have been opted in continuously for the
  preceding 14 days."
- **Continuous**: "Testers who opt in, test for fewer than 14 days, and then opt out do not count
  toward the requirement. If a tester opts out and opts back in later, the 14 days must be
  consecutive to count toward the minimum requirement of 12 continuous opted-in testers."
- **Which track**: closed testing. Internal testing is described as "optional, but recommended
  as a starting point" and is never counted. Open testing "becomes available after you gain
  production access", so it can't be used either. Testers must be on the closed track:
  "Users who opt into internal testing aren't eligible for open and closed testing, even if
  included as testers on those tracks. These users receive only the version code published on
  the Internal testing track." and "To access an open or closed test, the user must first opt
  out of the internal test and then opt in to the open or closed test."
  (<https://support.google.com/googleplay/android-developer/answer/9845334>). So **someone who is
  an internal tester today must leave the internal test first, or they don't count**.
- **"Opted in"**: the tester opens the test's opt-in link and accepts. "After clicking the opt-in
  link, testers receive an explanation of tester responsibilities and a link to opt in. Each
  tester needs to opt in using the link." With a Google Group, "users must join the group before
  opting into your test." (answer/9845334). Google's text counts opt-in, not installs; Google
  still asks: "Inform your testers that they need to remain opted in to your closed test
  continuously for at least 14 days." Whether Play also weighs installs or usage is not stated,
  but the application asks about engagement and a rejection can name "insufficient tester
  engagement" (below), so ask testers to install and open the app.
- **Applying**: "Open the Dashboard in Play Console. Click Apply for production." The form has
  three sections, and these are its questions as the article lists them:
  1. *About your closed test*: "Select an option indicating how easy it was to recruit testers
     for your app." "Provide details about tester engagement during your closed test, including:
     Whether testers used all available app features; Whether tester usage matched expected
     production user behavior, including details on any observed differences." "Summarize the
     feedback received from testers and describe how feedback was collected."
  2. *About your app/game*: "Specify the target audience for your app or game. Be as specific as
     possible." "Describe how your app provides value to users." "Select an estimated install
     range for your app or game during its first year." (These answers "are not displayed
     publicly".)
  3. *About your production readiness*: "Describe any changes made to your app or game based on
     what you learned from your closed test." "Describe how you determined that your app or game
     was ready for production."
  "If you click Discard or leave the page without clicking Next, your changes are not saved":
  write the answers in a text file first (draft below).
- **Review time**: "Review usually takes seven days or less, but can occasionally take longer."
  The result is emailed to the account owner.
- **What sends you back**: "If your app requires additional testing, you may need to continue
  running your closed test. Reasons for required continued testing include having fewer than 12
  opted-in testers or insufficient tester engagement during the testing period."
- **What resets the clock**: Google names only opting out ("the 14 days must be consecutive").
  Checked again 2026-11-15 in this article and in "Set up an open, closed, or internal test"
  (<https://support.google.com/googleplay/android-developer/answer/9845334>): **Google is silent**
  on whether a new closed-testing release, a paused track or a rejected application resets it.
  What Google does say points the other way: under "Are there additional testing best
  practices?" the article says "Continue running closed tests while resolving user-reported
  issues and bugs. Updating your app in closed testing before releasing to production helps
  minimize low ratings and negative reviews.", and the application asks you to "Describe any
  changes made to your app or game based on what you learned from your closed test." So updates
  during the test are expected, and testers on the track get them automatically ("their app
  automatically updates to the test version", answer 9845334). The third-party guides that say a
  new build resets the count cite no Google source. It remains **(inferred, not stated by
  Google)** that promoting a new build to `alpha` during the 14 days does not reset it, as long
  as nobody is removed from the tester list. Pausing the track ("Pause track", which ends a test)
  is the one action to avoid: Google describes it as how you "remove users from your app's test
  and end a testing track". Removing the Google Group or email list from the track would also
  take its testers out.
- **Policy before applying**: "review is not a troubleshooting step. Submitting non-compliant
  apps expecting reviewers to identify issues leads to rejections, review delays, and lengthier
  appeal processes." Google names: app content and monetisation, target age and content rating,
  stability, and "Test credentials: If your app requires user authentication, provide valid,
  working login credentials". The app needs no login, so App access says so.
- **After approval**: "you can access your app's Production page (Test and release > Production)
  and distribute your app to users on Google Play. You can also access Open testing".

Related account checks, same help centre:

- **Device verification**: "developers with new personal accounts will be required to verify
  that they have access to a real Android mobile device using the Play Console mobile app before
  they can make their app available on Google Play." Home page task "Verify that you have access
  to an Android mobile device" > View details, scan the QR code, verify in the Play Console app
  (<https://support.google.com/googleplay/android-developer/answer/14316361>). If the task is no
  longer on the Home page, it is done.
- **Android developer verification**: "Effective September 30, 2026, all Play packages must be
  registered to meet Android developer verification requirements." Google "will attempt to
  auto-register" Play apps (<https://support.google.com/googleplay/android-developer/answer/16984799>).
  Check that `com.foodtracker.android` shows as registered on the Android developer verification
  page in Play Console.
- **EU trader status (DSA)** **(unverified: not read on a Google page)**: third-party guides say
  every developer must declare trader or non-trader status in Play Console (Account details) and
  that a trader's address and phone can be shown on the listing. Someone selling recipe packs is
  likely a trader. Check the Play Console Home page for this task and read Google's own article
  it links to before answering.

## Testers: how to get 12, how to count them

- **Aim for 15 to 20** opted in, not 12: one person switching phones or leaving the test on day
  10 costs another 14 days.
- **Use one Google Group for the `alpha` track** (kit: [marketing/play-testers/](marketing/play-testers/README.md),
  "Email list or Google Group"). The track takes either email lists or Google Groups
  (Closed testing > Manage track > Testers > "select your preferred tester access method"); a
  group lets people add themselves, so you don't collect addresses by hand.
- **Feedback channel**: Google asks for "a feedback URL or email address" on the Testers tab;
  it is shown on the opt-in page. Use the repository's GitHub issues or a dedicated address, not
  a personal one if you don't want it on that page. Testers can also send private feedback
  through Google Play; it lands in Monitor and improve > Ratings and reviews > Testing feedback.
- **Keep a feedback log** from day one (date, tester's first name, what they said, what you
  changed): the application's first and third sections ask exactly for that.

### Checking the tester count

- **Play Console, Dashboard**: the "Apply for production" area is where the application starts;
  **(unverified)** it is reported to show progress towards 12 testers and 14 days. Google's
  article doesn't describe what the Dashboard shows.
- **Closed testing > Manage track > Testers tab**: the group or lists attached to the track.
- **Google Groups (groups.google.com > the group > Members)**: who has joined the group. Joining
  the group is not opting in; someone who joined but never pressed "Become a tester" does not
  count.
- **`scripts/play_status.py`** (read-only, "Status command" below) prints each track's releases, availability and
  the Google Groups attached to closed tracks. The Play Developer API does not expose the
  opted-in count, email-list members, the 14-day status or the application, so the console
  stays the place to check those.
- Ask each tester to send a screenshot of the opt-in page saying they are a tester, and tick them
  off with the date. That date + 14 is when they start counting.

## Calendar

Day X is the day the 12th tester (better: the 15th) opts in. The earliest application is X + 14;
**(inferred)** apply on X + 15 so every tester has 14 full days, because Google doesn't say how
it counts a partial first day. Review takes "seven days or less" (Google); the first production
release then goes through Play's review as well (no figure from Google; the managed-publishing
article mentions "apps submitted by new developer accounts" among those "subject to extended
review times", <https://support.google.com/googleplay/android-developer/answer/9859654>).

| If 12 are opted in by | Earliest application (X + 14) | Safer (X + 15) | Access by (+7 days) | Public, roughly |
|---|---|---|---|---|
| Wed 2026-11-18 | Wed 2026-12-02 | Thu 2026-12-03 | Thu 2026-12-10 | mid-December |
| Sun 2026-11-22 | Sun 2026-12-06 | Mon 2026-12-07 | Mon 2026-12-14 | before Christmas |
| Sun 2026-11-29 | Sun 2026-12-13 | Mon 2026-12-14 | Mon 2026-12-21 | late December or early January |

The "public" column assumes the first production release is reviewed within a few days
**(unverified)**; reviews around the holidays may be slower.

Suggested weeks:

- **Sun 15 – Mon 16 Nov (owner)**: account checks, tester group, track settings (steps 1–5
  below). Send the friends-and-family invitations the same day.
- **Mon 16 – Sun 22 Nov (owner)**: community posts (after checking each community's rules);
  chase people who joined the group but haven't opted in. Goal: 15+ by Sun 22 Nov.
- **During the 14 days (owner + repo)**: cut the Recipe release (step 6) so testers try what will
  go to production, and the application can say testers used all features. Keep the feedback
  log. Do not pause the track or detach the group.
- **X + 15 (owner)**: apply (step 8). Keep the closed test running while Google reviews it.
- **After approval (owner + repo)**: production release (step 9).

## Steps, in order

Who: **owner** = you in Play Console (screen > button); **repo** = a script or CI job in this
repository.

1. **Owner: account tasks.** Play Console Home: finish any open task ("Verify that you have
   access to an Android mobile device", Android developer verification, trader status if shown).
2. **Owner: check what the closed track has.** Test and release > Testing > Closed testing >
   *Closed testing* track (`alpha`) > Manage track. On the Releases tab, 2.0.0 (1061) should be
   "Available to testers" (the opt-in link is shown only when the release is published: "The
   opt-in link displays only when an app status is 'Published'"). Repo alternative:
   `scripts/play_status.py`.
3. **Owner: countries.** Same track > Countries / regions tab. Testing availability "syncs with
   production by default"; without a production release, make sure every country the testers'
   Play accounts are registered in is on the list (a tester registered elsewhere can't install).
   Country targeting is "based on where the user's
   Google Play account is registered, not their physical location"
   (<https://support.google.com/googleplay/android-developer/answer/9874837>).
4. **Owner: testers.** Create the Google Group at groups.google.com (settings in the kit), then
   Closed testing > Manage track > Testers tab > select **Google Groups** > enter
   `<group>@googlegroups.com` > feedback URL or email > **Save changes**. Copy the shareable
   link ("Copy the shareable link to share the release with testers"); for this app it has the form
   `https://play.google.com/apps/testing/com.foodtracker.android` (Google's pattern,
   answer/9859047).
5. **Owner: invite.** Send the invitations from the kit. Anyone who is an internal tester
   (Internal testing > Testers tab) and should count: they open the internal test's opt-in link,
   choose "Leave the program", then join the closed test.
6. **Repo: the Recipe release.** `scripts/release` > release branch > CI job
   `publish-play` uploads every push to **internal** (closed testers don't get these builds) >
   merging the release PR runs `promote-play` (`scripts/play_upload.py --promote-from internal
   --track alpha`), which replaces the `alpha` release with the newest internal build of that
   version, then `play-listing` applies `store-listing/google-play/`. Testers on `alpha` get the
   Recipe build as an ordinary update. The update changes neither the track's testers nor its
   countries (`play_upload.py` writes only the track's `releases`). Before merging, do the
   declarations in the checklist below, because a closed-track release is reviewed with them.
7. **Owner: during the test.** Watch Testing feedback; reply; note changes. Optional licence
   testers for trusted people (kit). If you want them to try real purchases end to end, turn
   `allowSandbox` on in `config/runtime` for that window only (docs/recipe-setup.md, step 10;
   functions/README.md, "`config/runtime`"): with it off, licence-test purchases give no recipes.
8. **Owner: apply.** Dashboard > **Apply for production** > three pages (draft below) > Next >
   Next > **Apply**. Wait for the email to the account owner.
9. **Owner + repo: production.** After approval:
   - Production > Countries / regions tab > **Add countries / regions** (all you want to sell in).
   - The release: either in the console (Production > Create new release > Add from library:
     the `alpha` build), or from this repo by hand:
     `PLAY_SERVICE_ACCOUNT_JSON="$(cat key.json)" python3 scripts/play_upload.py --promote-from alpha --version X.Y.Z --track production --status draft --release-notes-dir release-notes/vX.Y.Z`
     then roll the draft out in Production. `--status completed` would roll out to 100 % at once;
     `play_upload.py` has no staged rollout (`userFraction`), see "Proposed workflow changes".
   - Publishing overview > **Send for review** if the console holds the changes there.
10. **After launch (owner)**: closed testers keep the highest version code across production and
    `alpha`, so they get production updates too. Optional: store listing experiments and custom
    store listings (below).

## Draft answers for the application

Adapt with real numbers from the feedback log; don't overstate.

- *How easy to recruit*: (pick honestly).
- *Engagement*: testers added their own groceries by barcode and date scanning, used the
  reminders and the shopping list, and tried the Recipe tab's free routes (shared prompt, the
  phone's own model where supported); N licence testers bought test packs and generated recipes
  and menus. Differences from expected production use: friends and family, mostly in Norway and
  Russia-speaking households, ...
- *Feedback and how collected*: Play's private testing feedback, the Google Group, messages;
  summary of themes.
- *Target audience*: adults who buy groceries and want to waste less food (households, people
  who batch-cook, zero-waste minded users); English, Norwegian and Russian speakers first.
- *Value*: scan a barcode and the date, get reminded before food expires, see what to use first,
  and turn what's expiring into a recipe; pantry stays on the phone, no account, no ads.
- *Install range*: the lowest honest range.
- *Changes from the test*: list fixes and changes with versions (release notes in
  `release-notes/`).
- *Readiness*: crash-free sessions in Crashlytics / Android vitals, the pre-launch report,
  unit tests in CI, the test purchases with licence testers.

## Play Console declarations the Recipe release changes

All under Monitor and improve > Policy and programs > **App content** unless noted
(<https://support.google.com/googleplay/android-developer/answer/9859455>). Do them before the
Recipe build reaches `alpha` (the closed track is reviewed: Data safety applies to "apps on
closed, open, or production testing tracks", answer/10787469; only "Apps that are active on
internal testing tracks are exempt").

- [x] **Data safety**: the answers are in `store-listing/app-store/README.md`, section 5 (the
      "Google Play's Data safety form needs the matching answers" list), referenced from
      [recipe-setup.md](recipe-setup.md) step 11; not repeated here. The whole form is
      `store-listing/google-play/data-safety.csv`, sent with `scripts/play_data_safety.py --apply`
      (store-listing/google-play/README.md, "Data safety").
- [x] **Data deletion** (inside Data safety): account creation is possible (Google sign-in), so
      Google requires "a web link resource where users can request app account deletion"
      (<https://support.google.com/googleplay/android-developer/answer/13327111>); it "must be
      functional ..., relevant in scope ... and reference the app or developer name (that is, as
      it appears on your store listing in Google Play)" and must not require reinstalling the
      app. Use https://daniil-pastuhov.github.io/bestbefore-privacy/delete.html. **Check** it
      names "BestBefore" as on the listing.
- [x] **Privacy policy**: https://daniil-pastuhov.github.io/bestbefore-privacy/ (must cover the
      recipe pack data; see [privacy-policy-changes.md](privacy-policy-changes.md)).
- [x] **Ads**: No (the app has no ads; in-app purchase offers are not ads: the label "isn't meant
      to cover ... offers to make in-app purchases").
- [x] **App access** (Sign-in details): all functionality is available without special access;
      add a note that recipe packs are in-app purchases and need no login.
- [x] **Target audience and content**: adults (18+ or 16+; not children, so the Families policy
      doesn't apply). Unchanged by Recipe unless you change it.
- [x] **Content rating**: re-take the questionnaire if it asks about digital purchases
      **(unverified)**; Play's ratings show "In-app purchases" as an interactive element.
- [x] **Health apps** declaration, if App content lists it **(unverified)**: the allergen list is
      health data; answer as the app is (food tracking with an allergen filter, no medical
      features).
- [x] **Monetisation** (Manage account > Play apps settings > Monetization settings > Payments
      profile, <https://support.google.com/googleplay/android-developer/answer/7161426>): the
      payments profile exists if the one-time products could be created (2026-11-14), but
      **check** that the merchant tax information and bank account are complete and verified,
      otherwise payouts stop. Recipe-setup step 4's "Payments profile and merchant account" box
      and the 15 % service fee tier are still open.
- [x] **Licence testers** (Settings > Licence testing): done 2026-11-14 (recipe-setup step 4).
- [x] **Store listing**: applied by the release (`play-listing` job); no console step.

## Pre-registration, experiments, custom listings

- **Pre-registration is not available until production access**: it is listed among the
  features that "remain disabled until developers meet these testing requirements", and personal
  accounts "must meet specific testing requirements before they can make their app available on
  Google Play and, by extension, before they can use pre-registration"
  (<https://support.google.com/googleplay/android-developer/answer/9859047>). So it can't run
  alongside the closed test. Once unlocked: "Pre-registration campaigns can only last 90 days,
  after which you need to launch your app to production"; "The 90-day window for a country starts
  when you make pre-registration available in that country for the first time"; "If you haven't
  launched within 90 days in any of these countries, your pre-registration campaign will be
  terminated in all countries". Countries are where "the user is registered on Google Play, not
  their physical location". At launch "all pre-registered users receive a push notification ...
  Eligible devices also have the app or game auto installed" (auto install needs the ads
  declaration and, with one-time products, the billing permission in the bundle). Testers:
  "Users who already have a test version of your app installed won't receive a push
  notification" and "Users who have already joined your tests will not be able to pre-register
  until they uninstall and opt out". **Recommendation (inferred)**: skip pre-registration; once
  production is unlocked, publishing directly is faster than a campaign.
- **Pre-registration reward caveat**: a reward is a one-time product given free; "After you
  create a pre-registration reward, it can't be edited or deleted", it must be a new product made
  for the purpose, the app "must be technically able to handle when developerPayload and orderId
  aren't present", and "failure to deliver pre-registration rewards to users may result in
  suspension of your app". The recipe server grants credits only for store-verified purchases it
  redeems itself (functions/README.md, "How the money works"), so a reward would need backend
  work. Don't offer one.
- **Store listing experiments** (A/B tests): "For published apps, you can test variants against
  your current version"; "one default graphics experiment or up to five localized experiments at
  the same time", up to 2 variants, and experiments "stop automatically after running for 6
  months" (<https://support.google.com/googleplay/android-developer/answer/6227309>). After
  launch only. Note: `scripts/play_listing.py` writes the main listing; an experiment's variants
  live beside it in the console and the release job does not touch them **(inferred)**.
- **Custom store listings**: "up to 50 custom store listing pages", targeted by country,
  pre-registered users, search keywords, inactive users, a unique URL or a Google Ads campaign
  (<https://support.google.com/googleplay/android-developer/answer/9867158>); a country is
  unavailable while the app is not released there. After launch, a custom listing with its own
  URL for a zero-waste community post could say more about food waste; not needed for launch.
  The repo's listing sync handles the main listing only.

## Status command

```sh
pip install google-api-python-client google-auth-httplib2
PLAY_SERVICE_ACCOUNT_JSON="$(cat key.json)" python3 scripts/play_status.py
python3 -m unittest discover -s scripts/tests   # its tests, offline
```

Read-only: it opens a Play edit to read, prints each track's releases (name, version codes,
status, rollout, release-note languages), the track's countries / rest of world / sync with
production, and the Google Groups set as testers, and deletes the edit without committing. It
authenticates like `scripts/play_upload.py` and prints no contact details. It cannot show the
opted-in count, email-list members, the 14-day status, the application or pre-registration.

## Proposed workflow changes (not made)

`.github/workflows/ci.yml` is left as it is; these are suggestions.

- A `workflow_dispatch` job **"Play status"** running `python scripts/play_status.py` with the
  `PLAY_SERVICE_ACCOUNT_JSON` secret, so the state can be read from the Actions tab.
- After production access: a production step. Either a manual `workflow_dispatch` job that runs
  `play_upload.py --promote-from alpha --version X.Y.Z --track production --status draft`, or an
  option `--user-fraction` in `play_upload.py` (Play's `TrackRelease.userFraction` with status
  `inProgress`) for staged rollouts. Keep merges promoting to `alpha` only, so closed testers
  stay a step ahead of production.

## What could not be verified

- What the Dashboard shows of the tester count and days (not described by Google's article).
- Whether a new closed-testing release, a country change or a failed application affects the
  14 days (Google names only opting out).
- Whether the account owner's own account counts as one of the 12 (don't count it).
- How Google counts the first partial day (hence X + 15).
- Review time of the first production release for a new personal account.
- Trader status (DSA) wording and display, the Health apps declaration, and whether the content
  rating questionnaire needs re-taking for in-app purchases.
- Help-centre wording differs on discoverability of a closed test: "Your app is discoverable on
  Google Play but only selected testers can install and use it" (answer/9859751) versus "testers
  cannot find your app by searching Google Play. You must share the app's Play Store URL with
  testers" (answer/9845334). Always send the link.
