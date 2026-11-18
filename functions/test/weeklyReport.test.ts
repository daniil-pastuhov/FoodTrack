/**
 * The weekly app report (src/core/weeklyReport.ts, README "Weekly app report"): the arithmetic over
 * a seeded week, its boundaries (UTC, Monday to Sunday), the changes against the week before (stored
 * or worked out), one report per week (idempotent, also when two runs race), the off switch, an
 * empty week, its log lines and stored short lines, independence
 * from the prompt review in both directions, the console's view, the admin command, and the
 * menu counter a reservation now writes.
 */
import { randomUUID } from "node:crypto";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { Timestamp } from "firebase-admin/firestore";
import { DEFAULT_IMAGE_SELECTION, DEFAULT_TEXT_SELECTION, JUDGE_LOG, PROMPT_REVIEW, WEEKLY_REPORT } from "../src/constants";
import { runPromptReview, type PromptReviewEnv } from "../src/core/promptReview";
import {
  WEEKLY_REPORT_LABELS,
  reportLines,
  runWeeklyJobs,
  runWeeklyReport,
  weekSpanOf,
  weeklyReportRef,
  type WeeklyFigures,
  type WeeklyReportEnv,
} from "../src/core/weeklyReport";
import { listSettings, SETTINGS, WEB_UNRESTRICTED } from "../src/core/ownerSettings";
import { generateRecipe } from "../src/core/generation";
import { parseWeeklyArgs, runWeeklyCommand } from "../src/admin/weeklyReportCli";
import { AdminRefusal } from "../src/admin/accountRequests";
import { RecordingMailer } from "../src/emulatorFakes";
import { emptyUser, judgePricesOf, loadRuntimeConfig, microUsd, parseRuntimeConfig, statsRef, utcDay } from "../src/model";
import type { LlmUsage, Logger, PromptReviewer } from "../src/ports";
import { callerFor, db, makeDeps, resetEmulators, setConfig, type LogLine } from "./helpers";

/** Monday 2026-11-16 03:41 UTC: the run reports 2026-W46 (9 to 15 November). */
const NOW = Date.parse("2026-11-16T03:41:00Z");
const WEEK = "2026-W46";
const PREV = "2026-W45";
const PROJECT = "demo-foodtracker";
const CONSOLE = "https://demo-foodtracker.firebaseapp.com/";
const at = (iso: string) => Date.parse(iso);

let logs: LogLine[];
let clock: number;
const logger: Logger = {
  info: (message, data) => logs.push({ level: "info", message, data }),
  warn: (message, data) => logs.push({ level: "warn", message, data }),
  error: (message, data) => logs.push({ level: "error", message, data }),
};
const env = (over: Partial<WeeklyReportEnv> = {}): WeeklyReportEnv => ({ db, logger, now: () => clock, config: () => loadRuntimeConfig(db), project: PROJECT, ...over });
const lines = (m: string) => logs.filter((l) => l.message === m);
const stored = async (week = WEEK) => (await weeklyReportRef(db, week).get()).data();

beforeEach(async () => {
  await resetEmulators();
  logs = [];
  clock = NOW;
});

const JUDGE_USAGE = { inputTokens: 1_000, outputTokens: 100, thinkingTokens: 50, finishReason: "STOP" };

async function judgeAnswer(atMs: number, over: Record<string, unknown> = {}) {
  await db.collection(JUDGE_LOG.collection).doc().set({
    kind: "recipe", language: "en", textProvider: "google", textModel: "gemini-3.8-flash", judgeModel: "gemini-3.5-flash-lite", judgeVersion: "j1",
    promptHash: "p1", mode: "shadow", gateRule: null, verdict: "ok", failure: null, where: "none", issues: [], quality: 5, note: null, latencyMs: 900,
    usage: JUDGE_USAGE, at: Timestamp.fromMillis(atMs), expiresAt: Timestamp.fromMillis(atMs + 90 * 86_400_000), ...over,
  });
}

async function purchase(id: string, p: { store?: string; env?: string; productId: string; credits: number; refundedCredits?: number; grantedAt: number | null; refundEventAt?: number; uid?: string }) {
  await db.collection("purchases").doc(id).set({
    store: p.store ?? "play", env: p.env ?? "production", productId: p.productId, quantity: 1, credits: p.credits, refundedCredits: p.refundedCredits ?? 0,
    state: (p.refundedCredits ?? 0) > 0 ? "refunded" : "granted", grantedAt: p.grantedAt === null ? null : Timestamp.fromMillis(p.grantedAt),
    uid: p.uid ?? "uid-" + id, purchaseToken: "secret-token-" + id, orderId: "GPA.0000-" + id, buyerKeys: ["uid_uid-" + id],
    ...(p.refundEventAt !== undefined ? { refundEventAt: p.refundEventAt } : {}),
  });
}

/** 2026-W46 with sales, refunds, spend, failures, free recipes, reports and a ceiling day; W45 and W47 around it. */
async function seedWeek() {
  const day = (d: string, v: Record<string, unknown>) => statsRef(db, d).set(v);
  await day("2026-11-09", {
    spentMicroUsd: 1_000_000, reservedMicroUsd: 0, generations: 10, generationUnits: 12, succeeded: 9, contentFailures: 1, refundedCredits: 1, llmCalls: 10,
    welcomeUsedCredits: 2, welcomeUsedMicroUsd: 150_000, menuGenerations: 1, imageMicroUsd: 200_000, imageRequests: 3, imagesDelivered: 3,
    promptReviewMicroUsd: 100_000, netCreditsGranted: 60, welcomeGrants: 2, welcomeCredits: 6, reports: 2, reportReasons: { inaccurate: 1, unsafe: 1 },
  });
  await day("2026-11-11", {
    spentMicroUsd: 500_000, reservedMicroUsd: 20_000, generations: 4, generationUnits: 8, succeeded: 3, infraFailures: 1, refundedCredits: 4, llmCalls: 4,
    welcomeUsedCredits: 0, welcomeUsedMicroUsd: 0, menuGenerations: 1, imageFailures: 1, ceilingMicroUsd: 1_200_000,
    spendAlerts: { "80": at("2026-11-11T09:00:00Z"), "100": at("2026-11-11T10:00:00Z") }, dailyCapReachedAt: at("2026-11-11T11:00:00Z"),
  });
  // The weeks around it: never counted in W46.
  await day("2026-11-08", { spentMicroUsd: 400_000, generations: 5, generationUnits: 5, succeeded: 5, llmCalls: 5, welcomeUsedCredits: 1, welcomeUsedMicroUsd: 10_000, menuGenerations: 0, netCreditsGranted: 10 });
  await day("2026-11-16", { spentMicroUsd: 9_000_000, generations: 99, generationUnits: 99, promptReviewMicroUsd: 50_000, reports: 9 });

  await purchase("a", { productId: "recipes_10", credits: 10, grantedAt: at("2026-11-09T08:00:00Z") });
  await purchase("b", { store: "appstore", productId: "recipes_50", credits: 50, refundedCredits: 50, grantedAt: at("2026-11-12T08:00:00Z"), refundEventAt: at("2026-11-13T08:00:00Z") });
  await purchase("c", { env: "sandbox", productId: "recipes_10", credits: 10, grantedAt: at("2026-11-10T08:00:00Z") });
  await purchase("d", { productId: "recipes_100", credits: 100, grantedAt: at("2026-11-16T00:00:00Z") });
  // Bought in W45, refunded in W46: a W45 sale and a W46 refund.
  await purchase("e", { productId: "recipes_10", credits: 10, refundedCredits: 10, grantedAt: at("2026-11-08T23:59:59Z"), refundEventAt: at("2026-11-14T08:00:00Z") });
  await purchase("f", { env: "sandbox", productId: "recipes_10", credits: 10, refundedCredits: 10, grantedAt: at("2026-11-10T09:00:00Z"), refundEventAt: at("2026-11-12T08:00:00Z") });
  // A refund reversed in the week: nothing refunded any more.
  await purchase("g", { productId: "recipes_10", credits: 10, refundedCredits: 0, grantedAt: at("2026-10-01T08:00:00Z"), refundEventAt: at("2026-11-12T09:00:00Z") });

  await judgeAnswer(at("2026-11-09T00:00:00Z"));
  await judgeAnswer(at("2026-11-12T12:00:00Z"), { kind: "menu" });
  await judgeAnswer(at("2026-11-15T23:59:59Z"));
  await judgeAnswer(at("2026-11-16T00:00:00Z"));
  await judgeAnswer(at("2026-11-08T23:59:59Z"));

  await db.collection("users").doc("holder-1").set({ ...emptyUser(), balance: 5 });
  await db.collection("users").doc("holder-2").set({ ...emptyUser(), balance: 7 });
}

const judgeMicro = (answers: number) => microUsd(judgePricesOf(parseRuntimeConfig({}), "gemini-3.5-flash-lite"), answers * 1_000, answers * 150);

describe("the figures", () => {
  test("a seeded week: the board's arithmetic over Monday to Sunday (UTC), the refunds, the spend split and the problems", async () => {
    await seedWeek();
    const out = await runWeeklyReport(env(), { trigger: "schedule" });
    expect(out.status).toBe("done");
    const f = (await stored())!.figures as WeeklyFigures;
    expect(f.sales).toEqual({
      purchases: 2, recipes: 60, refundedSince: 50,
      byPack: { recipes_10: { purchases: 1, recipes: 10 }, recipes_50: { purchases: 1, recipes: 50 }, recipes_100: { purchases: 0, recipes: 0 }, other: { purchases: 0, recipes: 0 } },
      byStore: { appstore: { purchases: 1, recipes: 50 }, play: { purchases: 1, recipes: 10 }, other: { purchases: 0, recipes: 0 } },
      // c and f (f refunded since): sandbox purchases are never revenue.
      sandboxPurchases: 2,
      estimatedNetMicroUsd: 10 * 120_000,
      netMicroUsdPerCredit: 120_000,
      netRecipesBooked: 60,
      capped: false,
    });
    // Reported in the week, whenever bought; production only; a reversed refund is not one.
    expect(f.refunds).toEqual({ purchases: 2, recipes: 60, capped: false });
    const judge = judgeMicro(3);
    expect(judge).toBeGreaterThan(0);
    expect(f.spend).toEqual({
      totalMicroUsd: 1_520_000,
      textMicroUsd: 1_520_000 - 200_000 - 100_000 - judge,
      picturesMicroUsd: 200_000,
      judgeMicroUsd: judge,
      promptReviewMicroUsd: 100_000,
      judgeAnswers: 3,
      judgeTruncated: false,
      perRecipeMicroUsd: 100_000,
    });
    expect(f.estimatedMarginMicroUsd).toBe(1_200_000 - 1_520_000);
    expect(f.generations).toEqual({
      requests: 14, menus: 2, menusUnknownDays: 0, succeeded: 12, contentFailures: 1, infraFailures: 1, qualityFailures: 0,
      refundedRecipes: 5, recipesCharged: 15, boughtUsed: 13, pictures: { requests: 3, delivered: 3, failures: 1 },
    });
    expect(f.free).toEqual({ claims: 2, granted: 6, used: 2, costMicroUsd: 150_000, unknownDays: 0 });
    expect(f.problems).toEqual({ ceilingDays: ["2026-11-11"], dailyCapDays: ["2026-11-11"], over80Days: ["2026-11-11"], reports: 2, reportReasons: { inaccurate: 1, unsafe: 1 } });
    expect(f.unspent).toEqual({ recipes: 12, accounts: 2 });
    expect(f.reads).toBeGreaterThan(30);
    expect(f.reads).toBeLessThan(80);
  });

  test("nothing about a person is stored: no uid, token, order id or purchase id", async () => {
    await seedWeek();
    await runWeeklyReport(env(), { trigger: "schedule" });
    const text = JSON.stringify(await stored());
    for (const s of ["uid-", "secret-token", "GPA.", "holder-", "\"a\"", "uid_"]) expect(text).not.toContain(s);
  });

  test("the week before: worked out when it has no report, its stored report when it has one", async () => {
    await seedWeek();
    await runWeeklyReport(env(), { trigger: "schedule" });
    const d = (await stored())!;
    const prev = d.previous as { week: string; source: string; figures: WeeklyFigures };
    expect(prev).toMatchObject({ week: PREV, source: "computed" });
    expect(prev.figures.sales).toMatchObject({ purchases: 1, recipes: 10, refundedSince: 10, estimatedNetMicroUsd: 0 });
    expect(prev.figures.spend.totalMicroUsd).toBe(400_000);
    expect(prev.figures.spend.judgeAnswers).toBe(1);
    expect(prev.figures.generations).toMatchObject({ requests: 5, menus: 0 });
    expect(prev.figures.refunds).toEqual({ purchases: 0, recipes: 0, capped: false });
    expect(d.lines.revenue).toBe("USD 1.20 (+1.20) from 10 recipes sold net of refunds, at USD 0.120 a recipe (not a store payout)");
    expect(d.lines.purchases).toBe("2 (+1): 1 × 10, 1 × 50 · App Store 1, Google Play 1 · 2 sandbox");
    expect(d.lines.margin).toBe("−USD 0.32 (+0.08): net revenue minus all model spend");
    expect(d.lines.recipes).toBe("14 requests (+9): 12 recipes, 2 menus · 15 recipes charged, 13 of them bought · 5 given back");
    expect(d.lines.free).toBe("6 granted (+6) to 2 sign-ins · 2 used (+1), model cost USD 0.15");
    expect(d.lines.refunds).toBe("2 purchases (+2) refunded by the stores, 60 recipes");
    expect(d.lines.problems).toBe("spend ceiling reached Wed 11 Nov · daily cap reached Wed 11 Nov · 2 recipe reports (+2): 1 inaccurate, 1 unsafe · 2 failed answers (+2) (1 content, 1 infrastructure, 0 quality gate) · 1 picture not delivered");
    expect(d.lines.period).toBe("2026-W46 (9–15 Nov 2026, UTC)");

    // W47, the week after: W46's stored report is its week before, as stored.
    await weeklyReportRef(db, WEEK).update({ "figures.sales.purchases": 40 });
    clock = Date.parse("2026-11-23T03:41:00Z");
    const next = await runWeeklyReport(env(), { trigger: "schedule" });
    expect(next.status).toBe("done");
    const n = (await stored("2026-W47"))!;
    expect(n.previous).toMatchObject({ week: WEEK, source: "stored" });
    expect((n.previous as { figures: WeeklyFigures }).figures.sales.purchases).toBe(40);
    expect(n.lines.purchases).toMatch(/^1 \(−39\)/);
  });

  test("an empty week: a report of zeros, nothing to flag", async () => {
    clock = Date.parse("2026-10-05T03:41:00Z");
    const out = await runWeeklyReport(env(), { trigger: "schedule" });
    expect(out.status).toBe("done");
    const d = (await stored("2026-W40"))!;
    const f = d.figures as WeeklyFigures;
    expect(f.sales.purchases).toBe(0);
    expect(f.spend).toMatchObject({ totalMicroUsd: 0, textMicroUsd: 0, judgeMicroUsd: 0, perRecipeMicroUsd: null });
    expect(f.generations).toMatchObject({ requests: 0, menus: null, menusUnknownDays: 0, boughtUsed: 0 });
    expect(d.lines).toMatchObject({
      period: "2026-W40 (28 Sep – 4 Oct 2026, UTC)",
      revenue: "USD 0.00 (±0) from 0 recipes sold net of refunds, at USD 0.120 a recipe (not a store payout)",
      purchases: "0 (±0)",
      spend: "USD 0.00 (±0): text 0.00, pictures 0.00, second check 0.00, prompt review 0.00",
      problems: "none",
    });
  });

  test("days from before menus were counted apart: the split covers the days that have it", async () => {
    await statsRef(db, "2026-11-10").set({ generations: 3, generationUnits: 3, llmCalls: 3, succeeded: 3, welcomeUsedCredits: 0, welcomeUsedMicroUsd: 0 });
    await runWeeklyReport(env(), { trigger: "schedule" });
    const f = (await stored())!.figures as WeeklyFigures;
    expect(f.generations).toMatchObject({ requests: 3, menus: null, menusUnknownDays: 1 });
    await statsRef(db, "2026-11-11").set({ generations: 2, generationUnits: 5, menuGenerations: 1 });
    await weeklyReportRef(db, WEEK).delete();
    await runWeeklyReport(env(), { trigger: "schedule" });
    const g = (await stored())!;
    expect((g.figures as WeeklyFigures).generations).toMatchObject({ requests: 5, menus: 1, menusUnknownDays: 1 });
    expect(g.lines.recipes).toContain("4 recipes, 1 menu (1 day not split)");
  });

  test("week boundaries: a Sunday-night run, a year's turn and the span wording", async () => {
    expect(weekSpanOf("2026-W01")).toBe("29 Dec 2025 – 4 Jan 2026");
    expect(weekSpanOf("2026-W49")).toBe("30 Nov – 6 Dec 2026");
    expect(weekSpanOf("2026-W46")).toBe("9–15 Nov 2026");
    // A run in the week itself is refused: the week is not over.
    const early = await runWeeklyReport(env(), { week: "2026-W47", trigger: "cli" });
    expect(early).toEqual({ status: "failed", week: "2026-W47", reason: "BAD_WEEK" });
    // 2026-01-05 is the Monday of W02: the run reports W01, which began in 2025.
    clock = Date.parse("2026-01-05T03:41:00Z");
    await statsRef(db, "2025-12-29").set({ generations: 1, generationUnits: 1, llmCalls: 1, succeeded: 1, spentMicroUsd: 1_000, welcomeUsedCredits: 0, welcomeUsedMicroUsd: 0, menuGenerations: 0 });
    await statsRef(db, "2025-12-28").set({ generations: 50 });
    expect((await runWeeklyReport(env(), { trigger: "schedule" })).status).toBe("done");
    const f = (await stored("2026-W01"))!.figures as WeeklyFigures;
    expect(f.generations.requests).toBe(1);
    expect(f.spend.totalMicroUsd).toBe(1_000);
  });
});

describe("one report per week, and the off switch", () => {
  test("a second run leaves the stored report alone", async () => {
    await seedWeek();
    await runWeeklyReport(env(), { trigger: "schedule" });
    const first = await stored();
    await statsRef(db, "2026-11-09").update({ generations: 1000 });
    const again = await runWeeklyReport(env(), { trigger: "cli" });
    expect(again.status).toBe("already");
    expect(await stored()).toEqual(first);
    expect(lines("weekly_report_ready")).toHaveLength(1);
    expect(lines("weekly_report_skipped").map((l) => l.data?.reason)).toEqual(["ALREADY_DONE"]);
  });

  test("two runs at once: one report", async () => {
    await seedWeek();
    const outs = await Promise.all([runWeeklyReport(env(), { trigger: "schedule" }), runWeeklyReport(env(), { trigger: "cli" })]);
    expect(outs.map((o) => o.status).sort()).toEqual(["already", "done"]);
    expect(lines("weekly_report_ready")).toHaveLength(1);
  });

  test("off: nothing read, written or logged but the skip", async () => {
    await seedWeek();
    await setConfig({ weeklyReportEnabled: false });
    expect(await runWeeklyReport(env(), { trigger: "schedule" })).toEqual({ status: "off" });
    expect(await stored()).toBeUndefined();
    expect(logs.map((l) => [l.message, l.data?.reason])).toEqual([["weekly_report_skipped", "OFF"]]);
    expect(parseRuntimeConfig({}).weeklyReportEnabled).toBe(true);
    expect(parseRuntimeConfig({ weeklyReportEnabled: "yes" }).weeklyReportEnabled).toBe(false);
  });
});

describe("its log lines (the report is emailed by the job itself: test/reportEmail.test.ts)", () => {
  test("weekly_report_ready names the week only; the short lines are stored, each cut to its cap", async () => {
    await seedWeek();
    await runWeeklyReport(env(), { trigger: "schedule" });
    const [line] = lines("weekly_report_ready");
    expect(line).toEqual({ level: "info", message: "weekly_report_ready", data: { week: WEEK, trigger: "schedule" } });
    const stored_ = (await stored())!.lines as Record<string, string>;
    expect(Object.keys(stored_)).toEqual([...WEEKLY_REPORT_LABELS]);
    for (const k of WEEKLY_REPORT_LABELS) expect(Array.from(stored_[k]).length, k).toBeLessThanOrEqual(WEEKLY_REPORT.labelChars);
    expect(stored_.console).toBe(CONSOLE);
  });

  test("a failed run logs weekly_report_failed with its reason (alerted on)", async () => {
    const out = await runWeeklyReport(env({ config: () => Promise.reject(new Error("down")) }), { trigger: "schedule" });
    expect(out).toEqual({ status: "failed", week: WEEK, reason: "CONFIG_UNAVAILABLE" });
    expect(lines("weekly_report_failed")).toEqual([{ level: "error", message: "weekly_report_failed", data: { week: WEEK, trigger: "schedule", reason: "CONFIG_UNAVAILABLE" } }]);
  });

  test("a line too long is cut, never dropped", async () => {
    await statsRef(db, "2026-11-10").set({ reports: 40, reportReasons: Object.fromEntries(Array.from({ length: 40 }, (_, i) => ["reason_" + String.fromCharCode(97 + (i % 26)) + String.fromCharCode(97 + Math.floor(i / 26)), 1])) });
    await runWeeklyReport(env(), { trigger: "schedule" });
    const p = ((await stored())!.lines as Record<string, string>).problems;
    expect(Array.from(p).length).toBe(WEEKLY_REPORT.labelChars);
    expect(p.endsWith("…")).toBe(true);
    expect(Array.from(reportLines((await stored())!.figures as WeeklyFigures, null, CONSOLE).problems).length).toBeGreaterThan(WEEKLY_REPORT.labelChars);
  });

  test("the alert policies: the report's failure is alerted on, the report itself no longer is", () => {
    const failed = JSON.parse(readFileSync(join(__dirname, "../alerts/policies/weekly-report-failed.json"), "utf8"));
    expect(failed.conditions[0].conditionMatchedLog.filter).toContain('"weekly_report_failed"');
    expect(failed.severity).toBe("WARNING");
    const all = readdirSync(join(__dirname, "../alerts/policies")).map((f) => readFileSync(join(__dirname, "../alerts/policies", f), "utf8")).join("\n");
    expect(all).not.toContain("weekly_report_ready");
    expect(all).not.toContain("extracted_label");
  });
});

function emptyFigures(): WeeklyFigures {
  const c = { purchases: 0, recipes: 0 };
  return {
    week: WEEK,
    sales: { purchases: 0, recipes: 0, refundedSince: 0, byPack: { recipes_10: c, recipes_50: c, recipes_100: c, other: c }, byStore: { appstore: c, play: c, other: c }, sandboxPurchases: 0, estimatedNetMicroUsd: 0, netMicroUsdPerCredit: 120_000, netRecipesBooked: 0, capped: false },
    refunds: { purchases: 0, recipes: 0, capped: false },
    spend: { totalMicroUsd: 0, textMicroUsd: 0, picturesMicroUsd: 0, judgeMicroUsd: 0, promptReviewMicroUsd: 0, judgeAnswers: 0, judgeTruncated: false, perRecipeMicroUsd: null },
    estimatedMarginMicroUsd: 0,
    generations: { requests: 0, menus: null, menusUnknownDays: 0, succeeded: 0, contentFailures: 0, infraFailures: 0, qualityFailures: 0, refundedRecipes: 0, recipesCharged: 0, boughtUsed: 0, pictures: { requests: 0, delivered: 0, failures: 0 } },
    free: { claims: 0, granted: 0, used: 0, costMicroUsd: 0, unknownDays: 0 },
    problems: { ceilingDays: [], dailyCapDays: [], over80Days: [], reports: 0, reportReasons: {} },
    unspent: { recipes: 0, accounts: 0 },
    reads: 0,
  };
}

describe("independent of the prompt review", () => {
  const USAGE: LlmUsage = { inputTokens: 15_000, outputTokens: 2_000, thinkingTokens: 1_500, model: "gemini-3.8-flash-001", finishReason: "STOP" };
  class Reviewer implements PromptReviewer {
    readonly model = PROMPT_REVIEW.model;
    calls = 0;
    constructor(private readonly text: string) {}
    async review() {
      this.calls++;
      return { text: this.text, usage: USAGE };
    }
  }
  const reviewEnv = (reviewer: PromptReviewer): PromptReviewEnv => ({ db, reviewer, logger, now: () => clock, config: () => loadRuntimeConfig(db, undefined, DEFAULT_IMAGE_SELECTION, DEFAULT_TEXT_SELECTION) });
  const jobs = (reviewer: PromptReviewer, reportEnv = env()) => runWeeklyJobs(
    { report: () => runWeeklyReport(reportEnv, { trigger: "schedule" }), review: () => runPromptReview(reviewEnv(reviewer), { trigger: "schedule" }) },
    logger,
    { week: WEEK, project: PROJECT },
  );
  const reviewDoc = async () => (await db.collection(PROMPT_REVIEW.collection).doc(WEEK).get()).data();

  test("the review off: the report is still made", async () => {
    await seedWeek();
    await setConfig({ promptReviewEnabled: false });
    const r = new Reviewer("{}");
    await jobs(r);
    expect(r.calls).toBe(0);
    expect(await reviewDoc()).toBeUndefined();
    expect(lines("weekly_report_ready")).toHaveLength(1);
  });

  test("too few judge answers for the review: the report is still made", async () => {
    await seedWeek();
    await jobs(new Reviewer("{}"));
    expect(await reviewDoc()).toMatchObject({ status: "not_enough_data" });
    expect(lines("weekly_report_ready")).toHaveLength(1);
  });

  test("the review fails: the report is still made", async () => {
    await seedWeek();
    for (let i = 0; i < 25; i++) await judgeAnswer(at("2026-11-13T10:00:00Z") + i, { quality: 2, issues: ["vague_amount"], where: "ingredients" });
    // Today's spend clear, so the review's reservation passes and its answer is what fails.
    await statsRef(db, "2026-11-16").set({});
    await jobs(new Reviewer("not json at all"));
    expect(await reviewDoc()).toMatchObject({ status: "failed", reason: "MALFORMED_ANSWER" });
    expect(lines("prompt_review_failed")).toHaveLength(1);
    expect(lines("weekly_report_ready")).toHaveLength(1);
  });

  test("the review's job throws: the report is still made, and the review's failure is logged as its own", async () => {
    await seedWeek();
    await runWeeklyJobs({ report: () => runWeeklyReport(env(), { trigger: "schedule" }), review: () => { throw new Error("no Vertex client"); } }, logger, { week: WEEK, project: PROJECT });
    expect(lines("weekly_report_ready")).toHaveLength(1);
    expect(lines("prompt_review_failed")).toEqual([{ level: "error", message: "prompt_review_failed", data: { week: WEEK, trigger: "schedule", reason: "ERROR", costMicroUsd: 0 } }]);
  });

  test("the report fails, throws, or is off: the review still runs", async () => {
    await seedWeek();
    const r = new Reviewer("{}");
    await jobs(r, env({ config: () => Promise.reject(new Error("down")) }));
    expect(lines("weekly_report_failed")).toHaveLength(1);
    expect(await reviewDoc()).toMatchObject({ status: "not_enough_data" });

    await db.collection(PROMPT_REVIEW.collection).doc(WEEK).delete();
    await runWeeklyJobs({ report: () => { throw new Error("boom"); }, review: () => runPromptReview(reviewEnv(r), { trigger: "schedule" }) }, logger, { week: WEEK, project: PROJECT });
    expect(lines("weekly_report_failed")).toHaveLength(2);
    expect(lines("weekly_report_failed")[1].data).toEqual({ week: WEEK, trigger: "schedule", reason: "ERROR" });
    expect(await reviewDoc()).toMatchObject({ status: "not_enough_data" });

    await db.collection(PROMPT_REVIEW.collection).doc(WEEK).delete();
    await setConfig({ weeklyReportEnabled: false });
    await jobs(r);
    expect(await reviewDoc()).toMatchObject({ status: "not_enough_data" });
    expect(await stored()).toBeUndefined();
  });
});

describe("where it shows", () => {
  test("the console: its own card with the toggle, the latest report with its changes, and the last weeks", async () => {
    await seedWeek();
    await runWeeklyReport(env(), { trigger: "schedule" });
    await weeklyReportRef(db, PREV).set({ week: PREV, figures: emptyFigures(), previous: null });
    const v = await listSettings({ db, now: () => NOW, imageDefaults: DEFAULT_IMAGE_SELECTION, textDefaults: DEFAULT_TEXT_SELECTION });
    const sec = v.sections.find((s) => s.id === "weeklyReport")!;
    expect(sec.title).toBe("Weekly report");
    expect(sec.settings.map((s) => s.id)).toEqual(["weeklyReportEnabled"]);
    expect(sec.settings[0]).toMatchObject({ value: true, sensitive: false, web: null });
    const latest = sec.weekly!.latest!;
    expect(latest).toMatchObject({ week: WEEK, span: "9–15 Nov 2026", previousWeek: PREV, caveats: [] });
    expect(latest.rows.find((r) => r.label === "Estimated net revenue")).toMatchObject({ value: "USD 1.20", change: "+1.20" });
    expect(latest.rows.find((r) => r.label === "Spend ceiling reached")).toMatchObject({ value: "Wed 11 Nov" });
    expect(sec.weekly!.history.map((h) => [h.week, h.purchases])).toEqual([[WEEK, "2"], [PREV, "0"]]);
    expect(v.sections.filter((s) => "weekly" in s).map((s) => s.id)).toEqual(["weeklyReport"]);
    expect(SETTINGS.find((s) => s.id === "weeklyReportEnabled")?.sensitive).toBeUndefined();
    expect(WEB_UNRESTRICTED.weeklyReportEnabled).toBeTruthy();
  });

  test("the console with no report yet, and one it cannot read", async () => {
    const v = await listSettings({ db, now: () => NOW, imageDefaults: DEFAULT_IMAGE_SELECTION, textDefaults: DEFAULT_TEXT_SELECTION });
    expect(v.sections.find((s) => s.id === "weeklyReport")!.weekly).toEqual({ latest: null, history: [] });
    await weeklyReportRef(db, WEEK).set({ week: WEEK, figures: "garbage" });
    const w = await listSettings({ db, now: () => NOW, imageDefaults: DEFAULT_IMAGE_SELECTION, textDefaults: DEFAULT_TEXT_SELECTION });
    expect(w.sections.find((s) => s.id === "weeklyReport")!.weekly).toEqual({ latest: null, history: [] });
  });

  test("the admin command: arguments, the dry run writes nothing, --apply stores once, and the stored one prints", async () => {
    const parse = (a: string) => parseWeeklyArgs(a.split(" "), PROJECT);
    expect(parse("report weekly")).toEqual({ project: PROJECT, run: false, apply: false });
    expect(parse("report weekly --run --apply --week 2026-W46 --project p1")).toEqual({ project: "p1", week: WEEK, run: true, apply: true });
    expect(() => parse("report weekly --apply")).toThrow(AdminRefusal);
    expect(() => parse("report weekly --week 2026-46")).toThrow(AdminRefusal);
    expect(() => parse("report monthly")).toThrow(AdminRefusal);

    await seedWeek();
    const out: string[] = [];
    const cmdEnv = { db, now: () => clock, print: (l: string) => out.push(l), logger, config: () => loadRuntimeConfig(db), mailer: () => new RecordingMailer() };
    await runWeeklyCommand(parse("report weekly --run"), cmdEnv);
    expect(await stored()).toBeUndefined();
    expect(lines("weekly_report_ready")).toHaveLength(0);
    expect(out[0]).toBe("Dry run for 2026-W46, nothing written.");
    expect(out.join("\n")).toContain("The report in short lines (stored as `lines`):");
    expect(out.join("\n")).toContain("  Estimated net revenue   USD 1.20 (+1.20)");

    out.length = 0;
    expect((await runWeeklyCommand(parse("report weekly --run --apply"), cmdEnv))?.status).toBe("done");
    expect((await stored())!.trigger).toBe("cli");
    expect((await runWeeklyCommand(parse("report weekly --run --apply"), cmdEnv))?.status).toBe("already");
    out.length = 0;
    await runWeeklyCommand(parse("report weekly"), cmdEnv);
    expect(out[0]).toBe("Weekly app report 2026-W46 (9–15 Nov 2026, UTC), changes against 2026-W45, made " + new Date(NOW).toISOString());
    out.length = 0;
    await runWeeklyCommand(parse("report weekly --week 2026-W30"), cmdEnv);
    expect(out).toEqual(["No weekly report stored for 2026-W30."]);
  });
});

describe("the menu counter a reservation writes", () => {
  test("every reservation adds to menuGenerations, 0 for a recipe, so a day with the field is a counted day", async () => {
    const deps = makeDeps();
    await db.collection("users").doc("u-menu").set({ ...emptyUser(), balance: 20, purchasedCredits: 20 });
    await setConfig({ perUserPerMinute: 100 });
    const today = utcDay(deps.now());
    const config = { difficulty: "easy", detail: "standard", servings: 2, maxMinutes: 30, mealType: "any", diet: "none", language: "en" };
    const ingredients = [{ name: "Pasta", expiresOn: null, quantity: 1 }, { name: "Tomatoes", expiresOn: null, quantity: 3 }];
    await generateRecipe(deps, callerFor("u-menu"), { requestId: randomUUID(), kind: "recipe", config, ingredients, allergens: [], today });
    let s = (await statsRef(db, today).get()).data()!;
    expect(s).toMatchObject({ generations: 1, menuGenerations: 0 });
    await generateRecipe(deps, callerFor("u-menu"), { requestId: randomUUID(), kind: "menu", courses: 2, config, ingredients, allergens: [], today });
    s = (await statsRef(db, today).get()).data()!;
    expect(s).toMatchObject({ generations: 2, menuGenerations: 1 });
  });
});
