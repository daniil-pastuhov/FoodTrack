# Recruiting Google Play testers

Google needs at least 12 testers opted in to the closed test (`alpha`) for 14 days in a row
before the app can go public ([../../play-production.md](../../play-production.md)). This folder
has what to send them:

- [en.md](en.md), [ru.md](ru.md), [no.md](no.md): three invitations each (friends and family, a
  tech community, a food-waste / zero-waste community) and a one-page "how to join".

Fill in the placeholders before sending:

- `{{OPT_IN_URL}}`: the closed test's shareable link (Closed testing > Manage track > Testers
  tab). Google's pattern for it is `https://play.google.com/apps/testing/com.foodtracker.android`.
- `{{GROUP_URL}}`: the Google Group's page, `https://groups.google.com/g/<group-name>`.
- `{{FEEDBACK}}`: where feedback goes (the feedback URL or address set on the Testers tab, the
  group, or a chat). Don't put a personal email address in a public post.
- `{{NAME}}`: how you sign.

## What every tester must know

1. Join with the **Google account their phone's Play Store uses**. Another account joins
   nothing they can install.
2. Join the group, then open the opt-in link and press the button to become a tester. Joining
   the group alone doesn't count.
3. Install from Google Play (the link on the opt-in page) and open the app once.
4. **Stay in for at least 14 days** (ask for three weeks, or "until I say", to leave a margin).
   Leaving the test and joining again starts their 14 days over.
5. Feedback is optional but helps: Google's application asks what testers said and what changed.
6. Anyone already in the **internal** test must leave it first (internal opt-in page > Leave the
   program): Google says internal testers "aren't eligible for open and closed testing".

## Purchases: ordinary testers vs licence testers

The Recipe tab sells recipe packs (in-app purchases). Be clear about this, because it is easy to
get wrong:

- An **ordinary closed tester** buying a pack pays **real money**, exactly like after launch. The
  invitations say: don't buy unless you want to; everything else is free.
- A **licence tester** is a Google account you add under Play Console > Settings > Licence
  testing (account-wide, email lists or Google Groups). Google: "authorized users can also
  purchase one-time products and subscriptions without charging their accounts". The Play
  purchase sheet marks it as a test purchase.
- On this app's server, licence-test purchases grant recipes **only while `allowSandbox` is on**
  in `config/runtime` (functions/README.md, "`config/runtime`"; capped by `sandboxDailyCap` and
  `sandboxDailyCredits`). With it off, a licence tester's test purchase gives no recipes. Turn it
  on for a planned test window, tell those testers, and turn it off afterwards.
- Make licence testers a **separate small email list** of people you trust, not the whole tester
  group: with the group as licence testers and `allowSandbox` on, every member could get free
  recipes, which the server pays for (up to the caps).

## Email list or Google Group

A closed track takes one or the other (Testers tab: "select your preferred tester access
method"), so pick one for `alpha`.

| | Email list | Google Group |
|---|---|---|
| Joining | You add each Gmail address in Play Console, then they open the opt-in link | They join the group themselves, then open the opt-in link |
| Your work | Collect and type every address; each new person is a console edit | Create the group once; nothing per person |
| Tester's work | One step (opt-in link), but they must send you their address first | Two steps (group, then opt-in link) |
| Limits | 2,000 per list, 50 lists per track | "no size limits" |
| Public posts | Awkward: people must send their address to a stranger | Fits: one link to post |
| Privacy | Only you see addresses | Members may see each other unless you hide the member list |
| Reuse | Lists are reusable across apps and tracks | Same group can be reused; can also be a licence-testing group (don't, see above) |

**Recommendation: a Google Group.** For 12 to 20 people from several communities it is the
least work, and friends and family can use the same link. Settings (groups.google.com > Create
group): "Who can search for group": anyone on the web, or only members if you share the link
yourself; "Who can join group": **Anyone can join** (no approval step to forget); "Who can view
members": **Group managers**; "Who can post": managers only (so the group is not a forum you need
to moderate), and use it to send announcements such as "a new build is out" or "test purchases
are on until Sunday".

## Posting in communities

Every community has its own rules on self-promotion and on asking for testers; many forbid it or
allow it only in a weekly thread. **Read each community's rules (and pinned posts) before
posting, and ask a moderator if unsure.** Don't rely on someone else's summary of those rules.
Also:

- Post once, in the right thread; answer questions; don't cross-post the same text everywhere.
- Say plainly that it is your app, that it is a Google Play test, and that it is free.
- "Tester exchange" groups, where developers test each other's apps, exist; they get the number
  up but the application asks whether testers used the app like real users. People who actually
  buy groceries give feedback you can use.
- Keep a list: who joined, the date they opted in, what they reported.
