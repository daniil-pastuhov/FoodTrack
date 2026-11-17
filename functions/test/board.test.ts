/**
 * The owner's board (src/admin/board*.ts, README "The board"): aggregation over stats and
 * purchases, the ceiling per day from the server's own function, the rendered page (no
 * identifiers, escaping), the command end to end against the emulators, and the scheduled
 * redraw (src/board/hosted.ts) against an in-memory bucket (no Storage emulator).
 */
import { copyFileSync, mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Timestamp } from "firebase-admin/firestore";
import { AdminRefusal } from "../src/admin/accountRequests";
import { parseBoardArgs, runBoard } from "../src/admin/cli";
import {
  boardStatsDays,
  buildBoard,
  dayStats,
  periodDays,
  purchaseRow,
  type BoardInput,
  type PurchaseRow,
} from "../src/board/boardData";
import { runInNewContext } from "node:vm";
import { STALE_SCRIPT, boardJson, boardSummary, esc, niceScale, renderBoardHtml, usd } from "../src/board/boardHtml";
import { readBoardInput } from "../src/board/boardReader";
import {
  BOARD_HTML_OBJECT,
  BOARD_JSON_OBJECT,
  HOSTED_BOARD,
  judgeBucket,
  redrawBoard,
  type BoardBucket,
  type BoardObject,
  type BucketFacts,
} from "../src/board/hosted";
import type { Logger } from "../src/ports";
import { DEFAULT_RUNTIME_CONFIG, dailySpendCeiling, type RuntimeConfig } from "../src/model";
import { db, resetEmulators } from "./helpers";
import { seedBoardData } from "./boardSeed";

const DAY = 86_400_000;
const NOW = Date.parse("2026-11-15T13:45:00Z");

function input(over: Partial<BoardInput> & { statsByDay?: Record<string, Record<string, unknown>> } = {}): BoardInput {
  const { statsByDay = {}, ...rest } = over;
  const days = rest.days ?? 30;
  const stats = new Map<string, Record<string, unknown> | undefined>();
  for (const d of boardStatsDays(rest.generatedAtMs ?? NOW, days)) stats.set(d, statsByDay[d]);
  return {
    project: "demo-foodtracker",
    generatedAtMs: NOW,
    days,
    config: { ...DEFAULT_RUNTIME_CONFIG },
    stats,
    purchases: [],
    ledger: { negativeBalanceTotal: 0, checkedAtMs: null },
    holders: { accounts: 0, recipes: 0 },
    reads: 40,
    ...rest,
  };
}

const row = (over: Partial<PurchaseRow>): PurchaseRow => ({
  store: "play", env: "production", pack: "recipes_10", credits: 10, refundedCredits: 0, grantedAtMs: NOW, ...over,
});

describe("aggregation", () => {
  test("a missing stats doc or field reads as zero, and a non-number too", () => {
    expect(dayStats(undefined).spentMicroUsd).toBe(0);
    expect(dayStats({ succeeded: 3, spentMicroUsd: "lots" }).succeeded).toBe(3);
    expect(dayStats({ succeeded: 3, spentMicroUsd: "lots" }).spentMicroUsd).toBe(0);
    const b = buildBoard(input({ statsByDay: { "2026-11-10": { succeeded: 4, spentMicroUsd: 90_000 } } }));
    expect(b.days).toHaveLength(30);
    expect(b.days[0].day).toBe("2026-10-17");
    expect(b.days.at(-1)!.day).toBe("2026-11-15");
    expect(b.days.find((d) => d.day === "2026-11-09")).toMatchObject({ succeeded: 0, spentMicroUsd: 0, salesCount: 0 });
    expect(b.period.succeeded).toBe(4);
    expect(b.period.spentMicroUsd).toBe(90_000);
  });

  test("welcome grants: today and the period, apart from sales, with the config's setting", () => {
    const b = buildBoard(input({ statsByDay: {
      "2026-11-15": { welcomeGrants: 4, welcomeCredits: 12 },
      "2026-11-10": { welcomeGrants: 1, welcomeCredits: 3 },
    } }));
    expect(b.todayRow).toMatchObject({ welcomeGrants: 4, welcomeCredits: 12, salesCount: 0, salesCredits: 0 });
    expect(b.period).toMatchObject({ welcomeGrants: 5, welcomeCredits: 15, netCreditsGranted: 0 });
    expect(b.sales.total.credits).toBe(0);
    expect(b.welcome).toEqual({ recipes: 3, perDay: 200 });
    expect(boardSummary(b).join("\n")).toContain("Free recipes (welcome grant, not sales): 4 grants today (12 recipes) of at most 200; 5 in the period (15 recipes); 3 per sign-in.");
    expect(boardJson(b).welcome).toEqual({ recipesPerClaim: 3, perDay: 200, grantsToday: 4, recipesToday: 12, grantsInPeriod: 5, recipesInPeriod: 15 });
    expect(renderBoardHtml(b)).toContain("Free recipes (welcome grant)");
  });

  test("spend per day: settled and reserved apart, the recorded ceiling for past days, marks for days that reached it", () => {
    const b = buildBoard(input({ statsByDay: {
      "2026-11-12": { spentMicroUsd: 1_150_000, reservedMicroUsd: 0, ceilingMicroUsd: 1_230_000, spendAlerts: { "50": 1, "80": 2, "100": 3 }, dailyCapReachedAt: 4 },
      "2026-11-13": { spentMicroUsd: 700_000, ceilingMicroUsd: 1_500_000, spendAlerts: { "50": 1 } },
      "2026-11-15": { spentMicroUsd: 300_000, reservedMicroUsd: 82_000, ceilingMicroUsd: 999 },
    } }));
    const day = (d: string) => b.days.find((x) => x.day === d)!;
    expect(day("2026-11-12")).toMatchObject({ ceilingMicroUsd: 1_230_000, ceilingSource: "recorded", ceilingReached: true, spendThresholds: [50, 80], dailyCapReached: true });
    expect(day("2026-11-13")).toMatchObject({ ceilingMicroUsd: 1_500_000, ceilingReached: false, spendThresholds: [50], dailyCapReached: false });
    // No record: today's config (1.20 base, no sales). Today is always recomputed, never the record.
    expect(day("2026-11-14")).toMatchObject({ ceilingMicroUsd: 1_200_000, ceilingSource: "computed", ceilingReached: false });
    expect(b.todayRow).toMatchObject({ ceilingMicroUsd: 1_200_000, ceilingSource: "computed", spentMicroUsd: 300_000, reservedMicroUsd: 82_000 });

    const html = renderBoardHtml(b);
    expect(html).toContain("<figcaption>Model spend per day</figcaption>");
    expect(html).toContain("Reserved for calls in flight");
    expect(html).toContain("Ceiling reached: generation refused");
    expect((html.match(/class="mark"/g) ?? []).length).toBe(3); // one day, three widths
    expect(html).toContain("Ceiling reached (a reservation refused at the spend ceiling, <code>GENERATION_DISABLED</code>): 12 Nov.");
    expect(html).toContain("Daily recipe cap (<code>dailyCap</code>) reached: 12 Nov.");
    expect(html).toContain("<figcaption>Free recipes: welcome grants per day</figcaption>");
    expect(boardSummary(b).join("\n")).toContain("Spend per day against the ceiling: 1 days reached the ceiling (2026-11-12); 1 days reached dailyCap; highest day $1.15.");
    const json = boardJson(b) as { spendPerDay: Record<string, unknown>[] };
    expect(json.spendPerDay).toHaveLength(30);
    expect(json.spendPerDay.find((d) => d.day === "2026-11-12")).toEqual({
      day: "2026-11-12", spentMicroUsd: 1_150_000, reservedMicroUsd: 0, ceilingMicroUsd: 1_230_000, ceilingSource: "recorded",
      ceilingReached: true, thresholdsLogged: [50, 80], dailyCapReached: true, welcomeGrants: 0,
    });
  });

  test("the welcome chart marks days at the limit", () => {
    const b = buildBoard(input({ config: { ...DEFAULT_RUNTIME_CONFIG, welcome: { recipes: 3, perDay: 10 } }, statsByDay: { "2026-11-14": { welcomeGrants: 10 }, "2026-11-15": { welcomeGrants: 4 } } }));
    const html = renderBoardHtml(b);
    expect(html).toContain("Daily limit now (welcome.perDay 10)");
    expect(html).toContain("Day&#39;s limit reached");
  });

  test("the stats read covers the period, the month to date and the revenue window before both", () => {
    const days = boardStatsDays(NOW, 30);
    expect(days[0]).toBe("2026-10-11");
    expect(days.at(-1)).toBe("2026-11-15");
    expect(days).toHaveLength(36);
    // A 3-day period still reads from six days before the 1st of the month.
    const short = boardStatsDays(NOW, 3);
    expect(short[0]).toBe("2026-10-26");
    expect(short).toContain("2026-11-01");
    expect(periodDays(NOW, 3)).toEqual(["2026-11-13", "2026-11-14", "2026-11-15"]);
  });

  test("purchases fall on their UTC day: 23:59:59.999 is that day, midnight the next", () => {
    const midnight = Date.parse("2026-11-14T00:00:00Z");
    const b = buildBoard(input({
      purchases: [
        row({ grantedAtMs: midnight - 1 }),
        row({ grantedAtMs: midnight, pack: "recipes_50", credits: 50 }),
        // Before the period: ignored even if handed over.
        row({ grantedAtMs: Date.parse("2026-10-17T00:00:00Z") - 1 }),
        row({ grantedAtMs: Date.parse("2026-10-17T00:00:00Z"), store: "appstore" }),
      ],
    }));
    expect(b.days.find((d) => d.day === "2026-11-13")!.sales.recipes_10.count).toBe(1);
    expect(b.days.find((d) => d.day === "2026-11-14")!.sales.recipes_50).toEqual({ count: 1, credits: 50 });
    expect(b.days[0].salesCount).toBe(1);
    expect(b.sales.total.purchases).toBe(3);
    expect(b.sales.byStore.appstore.purchases).toBe(1);
  });

  test("sandbox purchases are never sales; refunds are netted; revenue uses netMicroUsdPerCredit", () => {
    const config: RuntimeConfig = { ...DEFAULT_RUNTIME_CONFIG, netMicroUsdPerCredit: 100_000 };
    const b = buildBoard(input({
      config,
      purchases: [
        row({ pack: "recipes_100", credits: 100, refundedCredits: 100 }),
        row({ pack: "recipes_50", credits: 50, refundedCredits: 25, store: "appstore" }),
        row({ pack: "recipes_10", credits: 10 }),
        row({ env: "sandbox", pack: "recipes_100", credits: 100 }),
        row({ env: "sandbox", pack: "recipes_10", credits: 10, store: "appstore" }),
      ],
    }));
    expect(b.sales.total).toEqual({ purchases: 3, credits: 160, refunded: 125, net: 35 });
    expect(b.sales.byPack.recipes_100).toEqual({ purchases: 1, credits: 100, refunded: 100, net: 0 });
    expect(b.sales.byStore.appstore).toEqual({ purchases: 1, credits: 50, refunded: 25, net: 25 });
    expect(b.sales.byStore.play).toEqual({ purchases: 2, credits: 110, refunded: 100, net: 10 });
    expect(b.sales.estimatedNetMicroUsd).toBe(3_500_000);
    expect(b.sales.sandbox).toEqual({ purchases: 2, credits: 110 });
    expect(b.todayRow).toMatchObject({ salesCount: 3, salesCredits: 160, sandboxPurchases: 2, sandboxPurchaseCredits: 110 });
  });

  test("purchases per day and totals by store (Android = Google Play, iOS = App Store), and by store and pack", () => {
    const yesterday = NOW - DAY;
    const b = buildBoard(input({
      purchases: [
        row({ store: "play", pack: "recipes_10", credits: 10 }),
        row({ store: "play", pack: "recipes_50", credits: 50, refundedCredits: 50 }),
        row({ store: "appstore", pack: "recipes_100", credits: 100, grantedAtMs: yesterday }),
        row({ store: "appstore", pack: "recipes_10", credits: 10 }),
        // Sandbox stays out of every store figure, as it stays out of sales.
        row({ store: "appstore", env: "sandbox", pack: "recipes_100", credits: 100 }),
      ],
    }));
    expect(b.todayRow.salesByStore).toEqual({ play: { count: 2, credits: 60 }, appstore: { count: 1, credits: 10 }, other: { count: 0, credits: 0 } });
    expect(b.days.at(-2)!.salesByStore.appstore).toEqual({ count: 1, credits: 100 });
    expect(b.days[0].salesByStore.play).toEqual({ count: 0, credits: 0 });
    expect(b.sales.byStoreAndPack.play.recipes_50).toEqual({ purchases: 1, credits: 50, refunded: 50, net: 0 });
    expect(b.sales.byStoreAndPack.appstore.recipes_100).toEqual({ purchases: 1, credits: 100, refunded: 0, net: 100 });
    expect(b.sales.byStoreAndPack.appstore.recipes_10.purchases).toBe(1);
    expect(b.sales.byStoreAndPack.play.recipes_100.purchases).toBe(0);
    // The splits add up to the store and pack totals.
    for (const store of ["play", "appstore", "other"] as const) {
      const packs = Object.values(b.sales.byStoreAndPack[store]);
      expect(packs.reduce((n, t) => n + t.purchases, 0)).toBe(b.sales.byStore[store].purchases);
      expect(packs.reduce((n, t) => n + t.credits, 0)).toBe(b.sales.byStore[store].credits);
      expect(b.days.reduce((n, d) => n + d.salesByStore[store].count, 0)).toBe(b.sales.byStore[store].purchases);
    }
    const json = boardJson(b) as { sales: { byStoreAndPack: Record<string, Record<string, { packs: number; recipes: number }>> } };
    expect(json.sales.byStoreAndPack.play.recipes_50).toMatchObject({ packs: 1, recipes: 50 });
    expect(json.sales.byStoreAndPack.appstore.recipes_100).toMatchObject({ packs: 1, recipes: 100 });
    expect(boardSummary(b).join("\n")).toContain("iOS (App Store) 2 packs, 110 recipes; Android (Google Play) 2 packs, 60 recipes.");
  });

  test("a deleted account's purchase (uid null) counts; identifiers and unknown strings are dropped", () => {
    const r = purchaseRow({
      store: "play", uid: null, formerUid: "uid-former", env: "production", productId: "recipes_50", credits: 50,
      refundedCredits: 0, state: "consumed", grantedAt: Timestamp.fromMillis(NOW), orderId: "GPA.1", purchaseToken: "tok",
    });
    expect(r).toEqual({ store: "play", env: "production", pack: "recipes_50", credits: 50, refundedCredits: 0, grantedAtMs: NOW });
    expect(purchaseRow({ store: "<b>", env: "weird", productId: "<img src=x>", credits: 5, grantedAt: Timestamp.fromMillis(NOW) }))
      .toMatchObject({ store: "other", env: "sandbox", pack: "other" });
    expect(purchaseRow({ store: "play", env: "production", productId: "recipes_10", credits: 10, grantedAt: null })).toBeNull();
  });

  test("each day's ceiling is the server's dailySpendCeiling over that day and the six before", () => {
    const config: RuntimeConfig = {
      ...DEFAULT_RUNTIME_CONFIG,
      dailySpendBaseMicroUsd: 1_000_000,
      dailySpendRevenueFraction: 0.5,
      netMicroUsdPerCredit: 110_000,
      dailySpendMaxMicroUsd: 3_000_000,
    };
    const net: Record<string, number> = { "2026-10-12": 10, "2026-10-20": 30, "2026-11-01": -5, "2026-11-03": 50, "2026-11-15": 20 };
    const statsByDay = Object.fromEntries(Object.entries(net).map(([d, n]) => [d, { netCreditsGranted: n }]));
    const b = buildBoard(input({ config, statsByDay }));
    for (const d of b.days) {
      let sum = 0;
      for (let i = 0; i < 7; i++) sum += net[new Date(Date.parse(`${d.day}T00:00:00Z`) - i * DAY).toISOString().slice(0, 10)] ?? 0;
      expect(d.netCredits7d).toBe(sum);
      expect(d.ceilingMicroUsd).toBe(dailySpendCeiling(config, sum));
    }
    // 10 granted on 12 Oct still counts on 17 and 18 Oct (inside the 7-day window), not on 19 Oct.
    expect(b.days[0].netCredits7d).toBe(10);
    expect(b.days[2].netCredits7d).toBe(0);
    expect(b.days.find((d) => d.day === "2026-11-07")!.ceilingMicroUsd).toBe(dailySpendCeiling(config, 45));
  });

  test("month to date and the month-end worst case", () => {
    const b = buildBoard(input({
      statsByDay: {
        "2026-10-31": { spentMicroUsd: 999_000 },
        "2026-11-01": { spentMicroUsd: 100_000 },
        "2026-11-15": { spentMicroUsd: 200_000, reservedMicroUsd: 50_000 },
      },
    }));
    expect(b.month.label).toBe("November 2026");
    expect(b.month.toDateMicroUsd).toBe(350_000);
    expect(b.month.remainingDays).toBe(15);
    expect(b.todayBookedMicroUsd).toBe(250_000);
    expect(b.month.worstCaseMicroUsd).toBe(350_000 + (1_200_000 - 250_000) + 15 * 2_000_000);
  });

  test("averages: per written answer and per recipe kept, none without data", () => {
    const empty = buildBoard(input());
    expect(empty.avgPerAnswerMicroUsd).toBeNull();
    expect(empty.avgPerRecipeMicroUsd).toBeNull();
    const b = buildBoard(input({ statsByDay: { "2026-11-15": { spentMicroUsd: 300_000, succeeded: 10, generationUnits: 14, refundedCredits: 2 } } }));
    expect(b.avgPerAnswerMicroUsd).toBe(30_000);
    expect(b.avgPerRecipeMicroUsd).toBe(25_000);
  });
});

describe("rendering", () => {
  test("escapes every text it is given", () => {
    expect(esc(`<a href="x">'&'</a>`)).toBe("&lt;a href=&quot;x&quot;&gt;&#39;&amp;&#39;&lt;/a&gt;");
    const html = renderBoardHtml(buildBoard(input({ project: `demo-<script>alert("x")</script>` })));
    expect(html).not.toContain("<script>alert");
    expect(html).toContain("demo-&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt;");
    // The one script on the page is the hover readout.
    expect(html.match(/<script>/g)).toHaveLength(1);
  });

  test("self-contained: no external requests, both themes, the bill links", () => {
    const html = renderBoardHtml(buildBoard(input()));
    const urls = [...html.matchAll(/(?:src|href)="([^"]+)"/g)].map((m) => m[1]);
    expect(urls.sort()).toEqual([
      "https://console.cloud.google.com/billing",
      "https://console.firebase.google.com/project/demo-foodtracker/usage",
      // A link (not a request) to the owner console, where the picture setup is switched.
      "https://demo-foodtracker.firebaseapp.com/",
    ]);
    expect(html).not.toMatch(/<link|<img|@import|url\(/);
    expect(html).toContain("prefers-color-scheme:dark");
    expect(html).toContain("not the Google Cloud bill");
    expect(html).not.toMatch(/credit/i);
  });

  test("a paused kill switch and sandbox on are said in words, not colour alone", () => {
    const b = buildBoard(input({ config: { ...DEFAULT_RUNTIME_CONFIG, generationEnabled: false, allowSandbox: true } }));
    const html = renderBoardHtml(b);
    expect(html).toContain("Paused");
    expect(html).toMatch(/Sandbox purchases<\/div><div class="t-value"><span class="status"><span class="s-icon warn"[^>]*>!<\/span>On/);
    expect(boardSummary(b).join("\n")).toContain("PAUSED");
  });

  test("purchases by platform: a chart with a legend, a split with text labels, a table by platform and pack", () => {
    const html = renderBoardHtml(buildBoard(input({
      purchases: [
        row({ store: "play", pack: "recipes_10", credits: 10 }),
        row({ store: "appstore", pack: "recipes_50", credits: 50 }),
        row({ store: "appstore", pack: "recipes_10", credits: 10, grantedAtMs: NOW - DAY }),
      ],
    })));
    expect(html).toContain("Purchases per day by platform (production)");
    expect(html).toContain('<li><span class="key box s1"></span>Android (Google Play)</li><li><span class="key box s2"></span>iOS (App Store)</li>');
    expect(html).toMatch(/class="bar s1"/);
    expect(html).toMatch(/class="bar s2"/);
    // The split says every figure in words, not by colour alone.
    expect(html).toContain("Android (Google Play) 1 (33 %) · iOS (App Store) 2 (67 %)");
    expect(html).toContain("Android (Google Play) 10 (14 %) · iOS (App Store) 60 (86 %)");
    expect(html).toContain('aria-label="10-packs: Android (Google Play) 1 (50 %) · iOS (App Store) 1 (50 %)"');
    expect(html).toContain("By platform and pack");
    expect(html).toContain('<th scope="row">iOS, 50-pack</th><td>1</td><td>50</td>');
    // An unknown store is not shown when it has nothing.
    expect(html).not.toContain("Other store");
  });

  test("no purchases yet: the platform chart and split say so instead of drawing nothing", () => {
    const html = renderBoardHtml(buildBoard(input()));
    expect(html).toContain("Purchases per day by platform (production)");
    expect(html.match(/No production purchases in this period/g)!.length).toBeGreaterThanOrEqual(3 * 2 + 3);
    expect(html).toContain('<div class="sp-bar" role="img" aria-label="Packs: No production purchases in this period"></div>');
    expect(html).not.toContain("NaN");
  });

  test("clean axis steps", () => {
    expect(niceScale(1_870_000)).toEqual({ max: 2_000_000, step: 500_000 });
    expect(niceScale(7, 4, true)).toEqual({ max: 8, step: 2 });
    expect(niceScale(0, 4, true)).toEqual({ max: 4, step: 1 });
    expect(usd(1_234_567)).toBe("$1.23");
    expect(usd(-500_000)).toBe("−$0.50");
  });
});

describe("parseBoardArgs", () => {
  test("defaults, --days bounds, --out", () => {
    expect(parseBoardArgs(["board"])).toEqual({ days: 30, project: "demo-foodtracker" });
    expect(parseBoardArgs(["board", "--days", "7", "--project", "bestbefore-f9ee1"])).toEqual({ days: 7, project: "bestbefore-f9ee1" });
    expect(parseBoardArgs(["board", "--out", "x/b.html"]).out).toMatch(/\/x\/b\.html$/);
    for (const bad of [["board", "--days", "0"], ["board", "--days", "91"], ["board", "--days", "2.5"], ["board", "--out", "b.txt"], ["board", "extra"], ["board", "--email", "a"]]) {
      expect(() => parseBoardArgs(bad)).toThrow(AdminRefusal);
    }
  });
});

describe("the command against the emulators", () => {
  beforeEach(resetEmulators);

  test("reads seeded data, writes the board, writes nothing to Firestore and shows no identifier", async () => {
    const now = Date.now();
    const seeded = await seedBoardData(db, now);
    const dump = async () => {
      const out: string[] = [];
      for (const c of ["stats", "purchases", "users", "config"]) {
        for (const d of (await db.collection(c).get()).docs) out.push(`${c}/${d.id}:${JSON.stringify(d.data())}`);
      }
      return out.sort().join("\n");
    };
    const before = await dump();
    const dir = mkdtempSync(join(tmpdir(), "board-"));
    const out = join(dir, "board.html");
    const lines: string[] = [];
    const { html, path } = await runBoard({ days: 30, out, project: "demo-foodtracker" }, { db, now: () => now, print: (l) => lines.push(l) });
    expect(path).toBe(out);
    expect(readFileSync(out, "utf8")).toBe(html);
    expect(await dump()).toBe(before);

    const text = lines.join("\n");
    for (const id of seeded.identifiers) {
      expect(html).not.toContain(id);
      expect(text).not.toContain(id);
    }
    expect(text).toContain(`Sales (production): ${seeded.productionPurchases} packs`);
    expect(text).toContain(`Sandbox: ${seeded.sandboxPurchases} purchases`);
    expect(text).toContain("Unspent recipes:");
    expect(text).toContain("Negative balances: −7.");
    expect(text).toContain("sandbox purchases ON");
    // config/runtime + 36 stats docs + the ledger + one per purchase + one aggregation read.
    expect(text).toContain(`Firestore reads: ${1 + 36 + 1 + seeded.productionPurchases + seeded.sandboxPurchases + 1}.`);
    expect(lines.at(-1)).toBe(`Board written to ${out}`);
    expect(html).toContain("Recipe packs board");
    if (process.env.BOARD_DEMO_OUT) copyFileSync(out, process.env.BOARD_DEMO_OUT);
  });
});

// --- Always on: the scheduled redraw ------------------------------------------------------------

const PRIVATE_FACTS: BucketFacts = {
  publicAccessPrevention: "enforced",
  uniformBucketLevelAccess: true,
  bindings: [
    { role: "roles/storage.legacyBucketOwner", members: ["projectOwner:demo-foodtracker", "projectEditor:demo-foodtracker"] },
    { role: "roles/storage.objectViewer", members: ["user:owner@example.com"] },
    { role: "projects/demo-foodtracker/roles/ownerBoardWriter", members: ["serviceAccount:recipe-functions@demo-foodtracker.iam.gserviceaccount.com"] },
  ],
};

class FakeBucket implements BoardBucket {
  readonly objects = new Map<string, BoardObject>();
  factsCalls = 0;
  constructor(
    readonly name = "demo-foodtracker-owner-board",
    /** Facts per call (the last one repeats); null is a missing bucket. */
    private readonly sequence: (BucketFacts | null)[] = [PRIVATE_FACTS],
    private readonly failWrite = false,
  ) {}
  async facts() {
    const f = this.sequence[Math.min(this.factsCalls, this.sequence.length - 1)];
    this.factsCalls++;
    return f;
  }
  async write(object: string, content: BoardObject) {
    if (this.failWrite) throw Object.assign(new Error("storage.objects.create denied"), { code: 403 });
    this.objects.set(object, content);
  }
}

function captureLogger() {
  const lines: { level: string; message: string; data?: Record<string, unknown> }[] = [];
  const logger: Logger = {
    info: (message, data) => lines.push({ level: "info", message, data }),
    warn: (message, data) => lines.push({ level: "warn", message, data }),
    error: (message, data) => lines.push({ level: "error", message, data }),
  };
  return { lines, logger };
}

describe("judgeBucket", () => {
  test("private only: enforced prevention, uniform access, no broad member", () => {
    expect(judgeBucket(PRIVATE_FACTS)).toEqual({ ok: true });
    expect(judgeBucket(null)).toEqual({ ok: false, reason: "missing" });
    expect(judgeBucket({ ...PRIVATE_FACTS, publicAccessPrevention: "inherited" })).toMatchObject({ reason: "public_access_prevention_not_enforced" });
    expect(judgeBucket({ ...PRIVATE_FACTS, publicAccessPrevention: null })).toMatchObject({ reason: "public_access_prevention_not_enforced" });
    expect(judgeBucket({ ...PRIVATE_FACTS, uniformBucketLevelAccess: false })).toMatchObject({ reason: "uniform_access_off" });
    for (const m of ["allUsers", "allAuthenticatedUsers", "domain:example.com"]) {
      expect(judgeBucket({ ...PRIVATE_FACTS, bindings: [...PRIVATE_FACTS.bindings, { role: "roles/storage.objectViewer", members: [m] }] }))
        .toEqual({ ok: false, reason: "public_binding", role: "roles/storage.objectViewer" });
    }
  });
});

describe("the scheduled redraw against the emulators", () => {
  beforeEach(resetEmulators);

  test("writes board.html and board.json with the right metadata, no identifier, nothing to Firestore", async () => {
    const now = Date.now();
    const seeded = await seedBoardData(db, now);
    const bucket = new FakeBucket();
    const { lines, logger } = captureLogger();
    const out = await redrawBoard({ db, bucket, project: "demo-foodtracker", now: () => now, logger });
    expect([...bucket.objects.keys()].sort()).toEqual([BOARD_HTML_OBJECT, BOARD_JSON_OBJECT]);
    const html = bucket.objects.get(BOARD_HTML_OBJECT)!;
    const json = bucket.objects.get(BOARD_JSON_OBJECT)!;
    expect(html).toMatchObject({ contentType: "text/html; charset=utf-8", cacheControl: "no-store, private", contentDisposition: "inline" });
    expect(json).toMatchObject({ contentType: "application/json; charset=utf-8", cacheControl: "no-store, private", contentDisposition: "inline" });
    // Checked before the read and again just before writing.
    expect(bucket.factsCalls).toBe(2);
    for (const id of seeded.identifiers) {
      expect(html.body).not.toContain(id);
      expect(json.body).not.toContain(id);
    }
    expect(html.body).toContain(`Updated ${new Date(now).toISOString().slice(0, 16).replace("T", " ")} UTC, redrawn hourly.`);
    expect(html.body).toContain(`data-generated="${now}" data-stale-hours="3" hidden`);
    // Refreshing re-requests Google's expired download address, so the page links to its own.
    expect(html.body).toContain(`<a id="reload" href="https://storage.cloud.google.com/${bucket.name}/board.html">Reload the board</a>`);
    expect(html.body).toContain(STALE_SCRIPT);
    expect(html.body.match(/<script>/g)).toHaveLength(1);
    expect(html.body).toContain("private Cloud Storage bucket");
    expect(html.body).not.toContain("not uploaded");
    expect(html.body).not.toMatch(/credit/i);
    const parsed = JSON.parse(json.body);
    expect(parsed.sales.packs).toBe(seeded.productionPurchases);
    expect(parsed.sales.sandbox.purchases).toBe(seeded.sandboxPurchases);
    expect(parsed.negativeBalanceTotal).toBe(-7);
    expect(parsed.sales.capped).toBeNull();
    expect(parsed.firestoreReads).toBe(out.reads);
    expect(json.body).not.toMatch(/credit|uid|token|order|transaction/i);
    expect(lines.find((l) => l.message === "board_redrawn")).toMatchObject({ level: "info", data: { bucket: bucket.name, capped: false } });
    expect(lines.some((l) => l.level === "error")).toBe(false);
  });

  test.each([
    ["public access prevention not enforced", { ...PRIVATE_FACTS, publicAccessPrevention: "inherited" }, "public_access_prevention_not_enforced"],
    ["uniform access off", { ...PRIVATE_FACTS, uniformBucketLevelAccess: false }, "uniform_access_off"],
    ["an allUsers binding", { ...PRIVATE_FACTS, bindings: [{ role: "roles/storage.objectViewer", members: ["allUsers", "user:owner@example.com"] }] }, "public_binding"],
    ["missing", null, "missing"],
  ] as [string, BucketFacts | null, string][])("refuses a bucket with %s: writes nothing, logs board_bucket_public", async (_, facts, reason) => {
    const bucket = new FakeBucket("demo-foodtracker-owner-board", [facts]);
    const { lines, logger } = captureLogger();
    await expect(redrawBoard({ db, bucket, project: "demo-foodtracker", now: () => Date.now(), logger })).rejects.toThrow(reason);
    expect(bucket.objects.size).toBe(0);
    const err = lines.filter((l) => l.level === "error");
    expect(err).toHaveLength(1);
    expect(err[0]).toMatchObject({ message: "board_bucket_public", data: { bucket: bucket.name, reason } });
    // Never a member: it may be an email address.
    expect(JSON.stringify(lines)).not.toContain("owner@example.com");
  });

  test("a bucket that turns public during the read still gets nothing", async () => {
    const bucket = new FakeBucket("demo-foodtracker-owner-board", [PRIVATE_FACTS, { ...PRIVATE_FACTS, publicAccessPrevention: "inherited" }]);
    const { lines, logger } = captureLogger();
    await expect(redrawBoard({ db, bucket, project: "demo-foodtracker", now: () => Date.now(), logger })).rejects.toThrow();
    expect(bucket.objects.size).toBe(0);
    expect(lines.find((l) => l.message === "board_bucket_public")).toBeTruthy();
  });

  test("never the default Firebase Storage bucket, whatever its settings", async () => {
    for (const name of ["demo-foodtracker.appspot.com", "demo-foodtracker.firebasestorage.app"]) {
      const bucket = new FakeBucket(name);
      const { lines, logger } = captureLogger();
      await expect(redrawBoard({ db, bucket, project: "demo-foodtracker", now: () => Date.now(), logger })).rejects.toThrow("default_firebase_bucket");
      expect(bucket.objects.size).toBe(0);
      expect(bucket.factsCalls).toBe(0);
      expect(lines[0]).toMatchObject({ message: "board_bucket_public", data: { reason: "default_firebase_bucket" } });
    }
  });

  test("a failed write logs board_redraw_failed and throws", async () => {
    const bucket = new FakeBucket("demo-foodtracker-owner-board", [PRIVATE_FACTS], true);
    const { lines, logger } = captureLogger();
    await expect(redrawBoard({ db, bucket, project: "demo-foodtracker", now: () => Date.now(), logger })).rejects.toThrow("denied");
    expect(lines.find((l) => l.message === "board_redraw_failed")).toMatchObject({ level: "error", data: { code: 403 } });
  });

  test("the purchases cap: newest first, counted, said on the board and in the JSON", async () => {
    const now = Date.now();
    const seeded = await seedBoardData(db, now);
    const total = seeded.productionPurchases + seeded.sandboxPurchases;
    expect(total).toBeGreaterThan(10);
    const { lines, logger } = captureLogger();
    const capped = await readBoardInput(db, { project: "demo-foodtracker", days: 30, nowMs: now, logger, purchasesCap: 10 });
    expect(capped.purchases).toHaveLength(10);
    expect(capped.purchasesCapped).toMatchObject({ total, read: 10 });
    // The newest ten: none older than the oldest one read.
    const all = await readBoardInput(db, { project: "demo-foodtracker", days: 30, nowMs: now });
    expect(all.purchasesCapped).toBeNull();
    const newest = all.purchases.map((p) => p.grantedAtMs).sort((a, b) => b - a).slice(0, 10);
    expect(capped.purchases.map((p) => p.grantedAtMs).sort((a, b) => b - a)).toEqual(newest);
    expect(capped.purchasesCapped!.oldestReadMs).toBe(newest[9]);
    // config + 36 stats + ledger + the 11 purchase reads + the balance aggregation + the count.
    expect(capped.reads).toBe(1 + 36 + 1 + 11 + 1 + 1);
    expect(lines.find((l) => l.message === "board_purchases_capped")).toMatchObject({ data: { total, read: 10 } });

    const bucket = new FakeBucket();
    await redrawBoard({ db, bucket, project: "demo-foodtracker", now: () => now, logger, purchasesCap: 10 });
    const html = bucket.objects.get(BOARD_HTML_OBJECT)!.body;
    expect(html).toContain(`This period has ${total} purchases`);
    expect(html).toContain("only the newest 10 were counted");
    expect(JSON.parse(bucket.objects.get(BOARD_JSON_OBJECT)!.body).sales.capped).toEqual({ total, read: 10 });
    const b = buildBoard(capped);
    expect(boardSummary(b).join("\n")).toContain("PURCHASES CAPPED");
    expect(boardJson(b).sales).toMatchObject({ capped: { total, read: 10 } });
  });
});

describe("the stale warning", () => {
  function runStale(generatedAtMs: number, nowMs: number) {
    const el = {
      attrs: { "data-generated": String(generatedAtMs), "data-stale-hours": String(HOSTED_BOARD.staleAfterHours) } as Record<string, string>,
      hidden: true,
      textContent: "",
      getAttribute(n: string) {
        return this.attrs[n] ?? null;
      },
    };
    const document = { getElementById: (id: string) => (id === "stale" ? el : null) };
    const FakeDate = { now: () => nowMs };
    runInNewContext(STALE_SCRIPT, { document, Date: FakeDate, Number, Math, setInterval: () => 0 });
    return el;
  }

  test("hidden on a fresh page, shown with the age past 3 hours", () => {
    const at = Date.parse("2026-11-15T10:17:00Z");
    expect(runStale(at, at + 2.9 * 3_600_000).hidden).toBe(true);
    const old = runStale(at, at + 5.5 * 3_600_000);
    expect(old.hidden).toBe(false);
    expect(old.textContent).toContain("This board is 5 hours old");
    expect(old.textContent).toContain("board_redraw_failed");
  });

  test("the local board has neither the updated line nor the warning", () => {
    const html = renderBoardHtml(buildBoard(input()));
    expect(html).not.toContain('id="stale"');
    expect(html).not.toContain("redrawn hourly");
    expect(html).toContain("written to a local file, not uploaded");
  });
});
