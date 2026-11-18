/**
 * Made-up report documents for the report emails (README "Report emails"): the admin command's
 * `report email-test` renders and sends these, so a test email needs no stored report and touches
 * none; the tests and the preview pictures use them too. Every figure and text here is invented.
 */
import { Timestamp } from "firebase-admin/firestore";
import { previousWeek, weekRange } from "./promptReview";
import type { WeeklyFigures } from "./weeklyReport";

const c = (purchases: number, recipes: number) => ({ purchases, recipes });

/** One made-up week's figures; `k` scales the activity (1 = the sample week). */
export function sampleFigures(week: string, k = 1): WeeklyFigures {
  const r = (n: number) => Math.round(n * k);
  const p10 = r(16), p50 = r(12), p100 = r(4);
  const recipes = p10 * 10 + p50 * 50 + p100 * 100;
  const refundedSince = r(0.4) * 10;
  const net = 120_000;
  const text = r(6_750_000), pictures = r(7_970_000), judge = r(300_000), review = 60_000;
  const total = text + pictures + judge + review;
  const kept = r(611);
  return {
    week,
    sales: {
      purchases: p10 + p50 + p100, recipes, refundedSince,
      byPack: { recipes_10: c(p10, p10 * 10), recipes_50: c(p50, p50 * 50), recipes_100: c(p100, p100 * 100), other: c(0, 0) },
      byStore: { appstore: c(r(14), 0), play: c(p10 + p50 + p100 - r(14), 0), other: c(0, 0) },
      sandboxPurchases: 3, estimatedNetMicroUsd: (recipes - refundedSince) * net, netMicroUsdPerCredit: net,
      netRecipesBooked: recipes - refundedSince, capped: false,
    },
    refunds: { purchases: r(0.6), recipes: r(0.6) * 10, capped: false },
    spend: { totalMicroUsd: total, textMicroUsd: text, picturesMicroUsd: pictures, judgeMicroUsd: judge, promptReviewMicroUsd: review, judgeAnswers: r(410), judgeTruncated: false, perRecipeMicroUsd: Math.round(total / kept) },
    estimatedMarginMicroUsd: (recipes - refundedSince) * net - total,
    generations: {
      requests: r(426), menus: r(65), menusUnknownDays: 0, succeeded: r(409), contentFailures: r(7), infraFailures: r(10), qualityFailures: 0,
      refundedRecipes: r(10), recipesCharged: kept, boughtUsed: kept - r(43), pictures: { requests: r(220), delivered: r(218), failures: 2 },
    },
    free: { claims: r(20), granted: r(60), used: r(43), costMicroUsd: r(1_060_000), unknownDays: 0 },
    problems: { ceilingDays: [], dailyCapDays: [], over80Days: k === 1 ? [weekDay(week, 3)] : [], reports: 2, reportReasons: { unsafe: 1, inaccurate: 1 } },
    unspent: { recipes: r(2_310), accounts: r(140) },
    reads: 512,
  };
}

const weekDay = (week: string, i: number) => new Date(weekRange(week)!.fromMs + i * 86_400_000).toISOString().slice(0, 10);

/** `weeklyReports/{week}` as the run stores it, with the week before. */
export function sampleWeeklyDoc(week: string, k = 1, prevK = 0.8): Record<string, unknown> {
  const prev = previousWeek(week)!;
  const r = weekRange(week)!;
  return {
    week,
    from: new Date(r.fromMs).toISOString(),
    to: new Date(r.toMs).toISOString(),
    trigger: "schedule",
    version: 1,
    generatedAt: Timestamp.fromMillis(r.toMs + (3 * 60 + 41) * 60_000),
    figures: sampleFigures(week, k),
    previous: { week: prev, source: "stored", figures: sampleFigures(prev, prevK) },
  };
}

/** Six weeks of made-up reports, newest first (the trend table). */
export function sampleHistory(week: string): Record<string, unknown>[] {
  const scales = [1, 0.8, 0.85, 0.7, 0.55, 0.4];
  const out: Record<string, unknown>[] = [];
  let w: string | null = week;
  for (let i = 0; i < scales.length && w; i++) {
    out.push(sampleWeeklyDoc(w, scales[i], scales[i + 1] ?? scales[i]));
    w = previousWeek(w);
  }
  return out;
}

/** `promptReviews/{week}` as a done run stores it, with made-up advice. */
export function sampleReviewDoc(week: string): Record<string, unknown> {
  const r = weekRange(week)!;
  return {
    week, from: new Date(r.fromMs).toISOString(), to: new Date(r.toMs).toISOString(), trigger: "schedule", status: "done", reason: null,
    model: "gemini-3.8-flash", answers: 412,
    aggregates: { total: 412, notOk: 23, failed: 4, lowQuality: 31, qualityAvg: 4.31, judgeSafetyBlocks: 3, issues: { vague_amount: 14, missing_step: 6, wrong_time: 4 } },
    previous: { week: previousWeek(week), total: 371, notOk: 27, lowQuality: 35, qualityAvg: 4.22, judgeSafetyBlocks: 5, verdicts: {}, issues: { vague_amount: 17, missing_step: 4 } },
    sample: { count: 30, ids: [], withText: 0, groups: {} },
    usage: { inputTokens: 19_840, outputTokens: 2_310, thinkingTokens: 1_620, finishReason: "STOP" },
    costMicroUsd: 59_250,
    finishedAt: Timestamp.fromMillis(r.toMs + (3 * 60 + 42) * 60_000),
    report: {
      summary: "Most low scores this week are vague amounts in menus (\"a handful\", \"some\") and steps that skip resting times. The judge's own safety filter refused three ordinary recipes (a tartare and two flambés).",
      promptProblems: [
        {
          title: "Menus give vague amounts for side dishes", issue: "vague_amount", count: 14, exampleIds: ["Jx81kq2MvA", "p0QzT7hw1e", "aa91LmZ0qe"],
          cause: "The menu instruction asks for amounts only for the main course; sides are left to the model.", target: "menu",
          before: "Give amounts for the main course's ingredients.", after: "Give an amount with a unit for every ingredient of every dish, sides included (e.g. \"150 g\", \"2 tbsp\"); never \"some\" or \"a handful\".",
          beforeFound: true, confidence: "high",
        },
        {
          title: "Resting and marinating times are left out of the steps", issue: "missing_step", count: 6, exampleIds: ["Qe3n8RrT2u"],
          cause: "The time limit wording makes the model drop passive time.", target: "recipe",
          before: "Keep the total time within the limit.", after: "Keep the total time within the limit, passive time (resting, marinating, chilling) included, and name it as its own step.",
          beforeFound: false, confidence: "medium",
        },
      ],
      judgeProblems: [
        {
          title: "Ordinary flambé and tartare refused by the judge's filter", issue: "judge_safety_block", count: 3, exampleIds: ["Lk2w9PoQ0z", "Vb7c1XsD4f"],
          cause: "The filter reads \"set alight\" and raw beef as harm.", target: "judge",
          before: "Mark as harmful any recipe that could hurt the cook.", after: "Mark as harmful only a recipe that is unsafe when followed as written; flambéing with care and dishes served raw by tradition (tartare, carpaccio) are not harmful.",
          beforeFound: true, confidence: "medium",
        },
      ],
      dropped: 0,
    },
  };
}

/** `promptReviews/{week}` for a week with too few answers. */
export function sampleEmptyReviewDoc(week: string, answers = 12): Record<string, unknown> {
  const r = weekRange(week)!;
  return {
    week, from: new Date(r.fromMs).toISOString(), to: new Date(r.toMs).toISOString(), trigger: "schedule", status: "not_enough_data", reason: "NOT_ENOUGH_DATA",
    model: "gemini-3.8-flash", answers, minAnswers: 20, aggregates: { total: answers }, previous: null, report: null, usage: null, costMicroUsd: 0,
  };
}
