/**
 * The owner's board (src/admin/board*.ts, README "The board"): aggregation over stats and
 * purchases, the ceiling per day from the server's own function, the rendered page (no
 * identifiers, escaping) and the command end to end against the emulators.
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
} from "../src/admin/boardData";
import { boardSummary, esc, niceScale, renderBoardHtml, usd } from "../src/admin/boardHtml";
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
