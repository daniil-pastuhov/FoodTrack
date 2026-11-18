/**
 * The weekly app report (README "Weekly app report"): last week's money and usage in one place, for
 * the owner. Made by the same scheduled function as the weekly prompt review (index.ts
 * weeklyPromptReview, Mondays 03:41 UTC; no scheduler job of its own), and on demand by
 * `npm run admin -- report weekly --run` (a dry run unless --apply).
 *
 * **No model call**: arithmetic over what the server already stores, so it costs Firestore reads
 * only. The figures are the owner board's (board/boardData.ts buildBoard over board/boardReader.ts
 * readBoardInput, the same code and the same caveats: revenue is the config's conservative
 * estimate per recipe, never a store payout; spend is the server's own estimate from token counts,
 * never the Cloud bill), for the ISO week (UTC) before the run, plus three small reads the board
 * does not make: the purchases whose refund the store reported in the week, the week's judge answers'
 * token counts (the second check's share of the spend, at today's judge prices) and the week before's
 * report (or the same figures worked out for it when it has none).
 *
 * One report per week (`weeklyReports/{YYYY-Www}`, created once: a week already reported is left
 * alone). Kept: a report is a few KB of totals with no uid, no purchase id and nothing about a person.
 *
 * It runs beside the prompt review, never depending on it (runWeeklyJobs): with the review off, with
 * too few judge answers, or with the review failing; and its own failure never stops the review.
 * Off with config/runtime `weeklyReportEnabled` (default on): nothing is read or written.
 *
 * **Sending it**: a real email to the owner (core/reportEmail.ts, README "Report emails"), sent by
 * the Monday job once both this report and the prompt review are stored, exactly once per week. A
 * report that could not be made logs `weekly_report_failed` (alerted on:
 * functions/alerts/policies/weekly-report-failed.json); a stored one logs `weekly_report_ready` (not
 * alerted on).
 */
import { Timestamp, type Firestore } from "firebase-admin/firestore";
import { PACKS, STORES, buildBoard, ownerConsoleUrl, type Board, type Pack, type Store } from "../board/boardData";
import { readBoardInput } from "../board/boardReader";
import { JUDGE, JUDGE_LOG, WEEKLY_REPORT } from "../constants";
import { judgePricesOf, microUsd, type RuntimeConfig } from "../model";
import type { Logger } from "../ports";
import { emailRecordOf, emailStatusText } from "./emailState";
import { lastCompleteWeek, previousWeek, weekRange } from "./promptReview";

const DAY_MS = 86_400_000;

// --- The figures ---------------------------------------------------------------------------------

export interface Count {
  purchases: number;
  recipes: number;
}

/** One week's figures, as stored (`figures`). Money in micro-USD. Nothing about a person. */
export interface WeeklyFigures {
  week: string;
  /** Production purchases granted in the week (the board's rule: by `grantedAt`). */
  sales: {
    purchases: number;
    /** Recipes those purchases granted. */
    recipes: number;
    /** Of those recipes, refunded since (as at the run). */
    refundedSince: number;
    byPack: Record<Pack, Count>;
    byStore: Record<Store, Count>;
    /** Sandbox, TestFlight and licence-tester purchases (never revenue). */
    sandboxPurchases: number;
    /** The board's: (recipes - refundedSince) x netMicroUsdPerCredit. An estimate, not a payout. */
    estimatedNetMicroUsd: number;
    netMicroUsdPerCredit: number;
    /** Recipes sold net of refunds as booked day by day (`stats/{day}.netCreditsGranted`, the spend ceiling's base). */
    netRecipesBooked: number;
    /** More purchases than the board reads in one run: the figures above miss the oldest. */
    capped: boolean;
  };
  /** Production purchases whose refund (or partial refund) the store reported in the week, whenever bought. */
  refunds: Count & { capped: boolean };
  spend: {
    /** Everything booked in the week's `stats/{day}` (settled + still reserved), the board's period spend. */
    totalMicroUsd: number;
    /** Recipe and menu text: the total minus the parts below. */
    textMicroUsd: number;
    picturesMicroUsd: number;
    /** The second check (output judge), estimated from its answers' tokens at today's judge prices; inside the text calls' booked spend. */
    judgeMicroUsd: number;
    /** The Monday prompt review run in the week (its own call). */
    promptReviewMicroUsd: number;
    /** Judge answers counted for the estimate, and whether the read stopped at WEEKLY_REPORT.judgeReadMax. */
    judgeAnswers: number;
    judgeTruncated: boolean;
    /** The board's: settled spend / recipes kept (charged and not refunded). */
    perRecipeMicroUsd: number | null;
  };
  /** The board's estimate: net revenue minus all model spend in the week (free recipes' included). */
  estimatedMarginMicroUsd: number;
  generations: {
    /** Requests to the server that reserved (recipes and menus). */
    requests: number;
    /** Of them menus, from the days that count them (`stats/{day}.menuGenerations`); null when no day of the week does. */
    menus: number | null;
    /** Days of the week with requests but no menu count (before the field existed). */
    menusUnknownDays: number;
    succeeded: number;
    contentFailures: number;
    infraFailures: number;
    qualityFailures: number;
    /** Recipes from a pack charged (reserved) and given back: refunded failures and missing pictures. */
    refundedRecipes: number;
    /** Recipes kept: charged and not given back. */
    recipesCharged: number;
    /** Of those, bought ones (recipesCharged - free used); null when some day's free figure is unknown. */
    boughtUsed: number | null;
    pictures: { requests: number; delivered: number; failures: number };
  };
  free: {
    /** Sign-ins that got the welcome grant, and the free recipes granted. */
    claims: number;
    granted: number;
    used: number;
    costMicroUsd: number;
    unknownDays: number;
  };
  problems: {
    /** Days (YYYY-MM-DD) a reservation was refused at the spend ceiling, or at dailyCap. */
    ceilingDays: string[];
    dailyCapDays: string[];
    /** Days whose booked spend passed 80 % of the ceiling (logged, not refused). */
    over80Days: string[];
    reports: number;
    reportReasons: Record<string, number>;
  };
  /** Recipes in balances and the accounts holding them, at the time of the run (not the week's). */
  unspent: { recipes: number; accounts: number };
  /** Firestore reads the figures took. */
  reads: number;
}

const num = (v: unknown) => (typeof v === "number" && Number.isFinite(v) ? v : 0);

const zeroCounts = <K extends string>(keys: readonly K[]) => Object.fromEntries(keys.map((k) => [k, { purchases: 0, recipes: 0 }])) as Record<K, Count>;

/** The week's extras the board does not read (pure inputs to weeklyFiguresOf). */
export interface WeekExtras {
  refunds: Count & { capped: boolean };
  judge: { microUsd: number; answers: number; truncated: boolean };
  reads: number;
}

/**
 * Pure: the week's figures from the board built over the week (its period exactly the week's seven
 * days) and the extras. The raw `stats/{day}` documents are the board input's own (`stats`).
 */
export function weeklyFiguresOf(week: string, board: Board, raw: ReadonlyMap<string, Record<string, unknown> | undefined>, extras: WeekExtras): WeeklyFigures {
  const p = board.period;
  const s = board.sales;
  const days = board.days;
  const rawOf = (d: string) => raw.get(d);
  let pictures = 0;
  let review = 0;
  let menus = 0;
  let menusKnown = 0;
  let menusUnknownDays = 0;
  const reportReasons: Record<string, number> = {};
  for (const d of days) {
    const r = rawOf(d.day);
    pictures += num(r?.imageMicroUsd);
    review += num(r?.promptReviewMicroUsd);
    if (typeof r?.menuGenerations === "number") {
      menus += r.menuGenerations;
      menusKnown++;
    } else if (d.generations > 0) menusUnknownDays++;
    const reasons = r?.reportReasons;
    if (reasons && typeof reasons === "object") {
      for (const [k, v] of Object.entries(reasons as Record<string, unknown>)) {
        // Reasons are the app's fixed codes; anything else is counted as "other".
        const key = /^[a-z_]{1,40}$/.test(k) ? k : "other";
        reportReasons[key] = (reportReasons[key] ?? 0) + num(v);
      }
    }
  }
  const total = p.spentMicroUsd + p.reservedMicroUsd;
  const judge = Math.min(extras.judge.microUsd, Math.max(0, total - pictures - review));
  const kept = p.generationUnits - p.refundedCredits;
  const byPack = zeroCounts(PACKS);
  const byStore = zeroCounts(STORES);
  for (const k of PACKS) byPack[k] = { purchases: s.byPack[k].purchases, recipes: s.byPack[k].credits };
  for (const k of STORES) byStore[k] = { purchases: s.byStore[k].purchases, recipes: s.byStore[k].credits };
  return {
    week,
    sales: {
      purchases: s.total.purchases,
      recipes: s.total.credits,
      refundedSince: s.total.refunded,
      byPack,
      byStore,
      sandboxPurchases: s.sandbox.purchases,
      estimatedNetMicroUsd: s.estimatedNetMicroUsd,
      netMicroUsdPerCredit: board.netMicroUsdPerCredit,
      netRecipesBooked: p.netCreditsGranted,
      capped: board.purchasesCapped !== null,
    },
    refunds: extras.refunds,
    spend: {
      totalMicroUsd: total,
      textMicroUsd: Math.max(0, total - pictures - review - judge),
      picturesMicroUsd: pictures,
      judgeMicroUsd: judge,
      promptReviewMicroUsd: review,
      judgeAnswers: extras.judge.answers,
      judgeTruncated: extras.judge.truncated,
      perRecipeMicroUsd: board.avgPerRecipeMicroUsd === null ? null : Math.round(board.avgPerRecipeMicroUsd),
    },
    estimatedMarginMicroUsd: s.estimatedMarginMicroUsd,
    generations: {
      requests: p.generations,
      menus: menusKnown > 0 ? menus : null,
      menusUnknownDays,
      succeeded: p.succeeded,
      contentFailures: p.contentFailures,
      infraFailures: p.infraFailures,
      qualityFailures: p.qualityFailures,
      refundedRecipes: p.refundedCredits,
      recipesCharged: kept,
      boughtUsed: board.free.unknownDays > 0 ? null : Math.max(0, kept - board.free.used),
      pictures: { requests: p.imageRequests, delivered: p.imagesDelivered, failures: p.imageFailures },
    },
    free: {
      claims: p.welcomeGrants,
      granted: board.free.granted,
      used: board.free.used,
      costMicroUsd: board.free.costMicroUsd,
      unknownDays: board.free.unknownDays,
    },
    problems: {
      ceilingDays: days.filter((d) => d.ceilingReached).map((d) => d.day),
      dailyCapDays: days.filter((d) => d.dailyCapReached).map((d) => d.day),
      over80Days: days.filter((d) => d.spendThresholds.includes(80)).map((d) => d.day),
      reports: p.reports,
      reportReasons,
    },
    unspent: { recipes: board.holders.recipes, accounts: board.holders.accounts },
    reads: extras.reads,
  };
}

// --- Reading a week ------------------------------------------------------------------------------

/** The judge's share of the week's spend: its answers' tokens at today's judge prices, per judge model. */
async function readJudgeSpend(db: Firestore, config: RuntimeConfig, fromMs: number, toMs: number): Promise<WeekExtras["judge"] & { reads: number }> {
  const byModel = new Map<string, { input: number; output: number }>();
  let answers = 0;
  let reads = 0;
  let q = db.collection(JUDGE_LOG.collection)
    .where("at", ">=", Timestamp.fromMillis(fromMs))
    .where("at", "<", Timestamp.fromMillis(toMs))
    .orderBy("at")
    .select("usage", "judgeModel")
    .limit(500);
  for (;;) {
    const snap = await q.get();
    reads += Math.max(1, snap.size);
    for (const d of snap.docs) {
      const u = d.get("usage") as { inputTokens?: unknown; outputTokens?: unknown; thinkingTokens?: unknown } | null | undefined;
      const model = typeof d.get("judgeModel") === "string" ? (d.get("judgeModel") as string) : JUDGE.model;
      const m = byModel.get(model) ?? { input: 0, output: 0 };
      m.input += num(u?.inputTokens);
      m.output += num(u?.outputTokens) + num(u?.thinkingTokens);
      byModel.set(model, m);
      answers++;
      if (answers >= WEEKLY_REPORT.judgeReadMax) return { ...sumJudge(config, byModel), answers, truncated: true, reads };
    }
    if (snap.size < 500) return { ...sumJudge(config, byModel), answers, truncated: false, reads };
    q = q.startAfter(snap.docs[snap.docs.length - 1]);
  }
}

function sumJudge(config: RuntimeConfig, byModel: ReadonlyMap<string, { input: number; output: number }>): { microUsd: number } {
  let micro = 0;
  for (const [model, t] of byModel) micro += microUsd(judgePricesOf(config, model), t.input, t.output);
  return { microUsd: micro };
}

/** Production purchases whose last refund event (the store's time) is in the week, with recipes still refunded. */
async function readRefunds(db: Firestore, fromMs: number, toMs: number): Promise<WeekExtras["refunds"] & { reads: number }> {
  const cap = WEEKLY_REPORT.refundReadMax;
  const snap = await db.collection("purchases")
    .where("refundEventAt", ">=", fromMs)
    .where("refundEventAt", "<", toMs)
    .select("env", "refundedCredits")
    .limit(cap + 1)
    .get();
  let purchases = 0;
  let recipes = 0;
  for (const d of snap.docs.slice(0, cap)) {
    if (d.get("env") !== "production") continue;
    const n = num(d.get("refundedCredits"));
    if (n <= 0) continue;
    purchases++;
    recipes += n;
  }
  return { purchases, recipes, capped: snap.size > cap, reads: Math.max(1, snap.size) };
}

/** Reads and works out one week's figures (read-only). */
export async function readWeeklyFigures(db: Firestore, week: string, project: string, logger?: Logger): Promise<WeeklyFigures> {
  const range = weekRange(week);
  if (!range) throw new Error("not an ISO week: " + week);
  // The board's period is the 7 days ending on the day of nowMs: Monday to Sunday of the week.
  const input = await readBoardInput(db, { project, days: 7, nowMs: range.toMs - 1, logger });
  const board = buildBoard(input);
  const [refunds, judge] = await Promise.all([
    readRefunds(db, range.fromMs, range.toMs),
    readJudgeSpend(db, input.config, range.fromMs, range.toMs),
  ]);
  return weeklyFiguresOf(week, board, input.stats, {
    refunds: { purchases: refunds.purchases, recipes: refunds.recipes, capped: refunds.capped },
    judge: { microUsd: judge.microUsd, answers: judge.answers, truncated: judge.truncated },
    reads: input.reads + refunds.reads + judge.reads,
  });
}

export const weeklyReportRef = (db: Firestore, week: string) => db.collection(WEEKLY_REPORT.collection).doc(week);

// --- Wording (the email's fields, the console's rows, the command's lines) ------------------------

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const WEEKDAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const int = (n: number) => Math.round(n).toLocaleString("en-GB");
export const usdOf = (micro: number, digits = 2) => (micro < 0 ? "−" : "") + "USD " + (Math.abs(micro) / 1e6).toFixed(digits);
const plural = (n: number, one: string, many: string) => int(n) + " " + (n === 1 ? one : many);

/** "9–15 Nov 2026", "30 Nov – 6 Dec 2026", "29 Dec 2025 – 4 Jan 2026". */
export function weekSpanOf(week: string): string {
  const r = weekRange(week);
  if (!r) return week;
  const a = new Date(r.fromMs);
  const b = new Date(r.toMs - DAY_MS);
  const dm = (d: Date) => d.getUTCDate() + " " + MONTHS[d.getUTCMonth()];
  if (a.getUTCFullYear() !== b.getUTCFullYear()) return dm(a) + " " + a.getUTCFullYear() + " – " + dm(b) + " " + b.getUTCFullYear();
  if (a.getUTCMonth() !== b.getUTCMonth()) return dm(a) + " – " + dm(b) + " " + b.getUTCFullYear();
  return a.getUTCDate() + "–" + dm(b) + " " + b.getUTCFullYear();
}

const dayName = (day: string) => {
  const d = new Date(day + "T00:00:00Z");
  return WEEKDAYS[d.getUTCDay()] + " " + d.getUTCDate() + " " + MONTHS[d.getUTCMonth()];
};

/** A change against the week before: "+5", "−2", "±0" (USD with its digits); "" without a week before. */
function change(now: number, before: number | null | undefined, usdDigits?: number): string {
  if (before === null || before === undefined) return "";
  const d = now - before;
  const shown = usdDigits === undefined ? int(Math.abs(d)) : (Math.abs(d) / 1e6).toFixed(usdDigits);
  if (Number(shown.replace(/,/g, "")) === 0) return "±0";
  return (d > 0 ? "+" : "−") + shown;
}
const inBrackets = (c: string) => (c ? " (" + c + ")" : "");

const PACK_LABEL: Record<Pack, string> = { recipes_10: "10", recipes_50: "50", recipes_100: "100", other: "other" };
const STORE_LABEL: Record<Store, string> = { appstore: "App Store", play: "Google Play", other: "other" };

/**
 * The report in short lines (WEEKLY_REPORT_LABELS), stored as `lines` and printed by the admin
 * command's dry run: changes against the week before in brackets. Pure. `prev` null: the first report.
 */
export function reportLines(f: WeeklyFigures, prev: WeeklyFigures | null, consoleUrl: string): Record<WeeklyReportLabel, string> {
  const g = f.generations;
  const packs = PACKS.filter((k) => f.sales.byPack[k].purchases > 0).map((k) => int(f.sales.byPack[k].purchases) + " × " + PACK_LABEL[k]).join(", ");
  const stores = STORES.filter((k) => f.sales.byStore[k].purchases > 0).map((k) => STORE_LABEL[k] + " " + int(f.sales.byStore[k].purchases)).join(", ");
  const kinds = g.menus === null ? "" : ": " + plural(g.requests - g.menus, "recipe", "recipes") + ", " + plural(g.menus, "menu", "menus") + (g.menusUnknownDays ? " (" + plural(g.menusUnknownDays, "day", "days") + " not split)" : "");
  const failed = g.contentFailures + g.infraFailures + g.qualityFailures;
  const problems: string[] = [];
  if (f.problems.ceilingDays.length) problems.push("spend ceiling reached " + f.problems.ceilingDays.map(dayName).join(", "));
  if (f.problems.dailyCapDays.length) problems.push("daily cap reached " + f.problems.dailyCapDays.map(dayName).join(", "));
  if (!f.problems.ceilingDays.length && f.problems.over80Days.length) problems.push("spend passed 80 % of the ceiling " + f.problems.over80Days.map(dayName).join(", "));
  if (f.problems.reports) {
    const why = Object.entries(f.problems.reportReasons).filter(([, n]) => n > 0).sort((a, b) => b[1] - a[1]).map(([k, n]) => int(n) + " " + k.replaceAll("_", " ")).join(", ");
    problems.push(plural(f.problems.reports, "recipe report", "recipe reports") + inBrackets(change(f.problems.reports, prev?.problems.reports)) + (why ? ": " + why : ""));
  }
  if (failed) problems.push(plural(failed, "failed answer", "failed answers") + inBrackets(change(failed, prev ? prev.generations.contentFailures + prev.generations.infraFailures + prev.generations.qualityFailures : null)) + " (" + int(g.contentFailures) + " content, " + int(g.infraFailures) + " infrastructure, " + int(g.qualityFailures) + " quality gate)");
  if (g.pictures.failures) problems.push(plural(g.pictures.failures, "picture", "pictures") + " not delivered");
  if (f.sales.capped || f.refunds.capped || f.spend.judgeTruncated) problems.push("some figures incomplete (read caps; see the console)");
  return {
    period: f.week + " (" + weekSpanOf(f.week) + ", UTC)" + (prev ? "" : ", the first report"),
    revenue: usdOf(f.sales.estimatedNetMicroUsd) + inBrackets(change(f.sales.estimatedNetMicroUsd, prev?.sales.estimatedNetMicroUsd, 2)) + " from " + plural(f.sales.recipes - f.sales.refundedSince, "recipe", "recipes") + " sold net of refunds, at " + usdOf(f.sales.netMicroUsdPerCredit, 3) + " a recipe (not a store payout)",
    purchases: int(f.sales.purchases) + inBrackets(change(f.sales.purchases, prev?.sales.purchases)) + (packs ? ": " + packs + " · " + stores : "") + (f.sales.sandboxPurchases ? " · " + int(f.sales.sandboxPurchases) + " sandbox" : ""),
    spend: usdOf(f.spend.totalMicroUsd) + inBrackets(change(f.spend.totalMicroUsd, prev?.spend.totalMicroUsd, 2)) + ": text " + usdOf(f.spend.textMicroUsd).slice(4) + ", pictures " + usdOf(f.spend.picturesMicroUsd).slice(4) + ", second check " + usdOf(f.spend.judgeMicroUsd).slice(4) + ", prompt review " + usdOf(f.spend.promptReviewMicroUsd).slice(4) + (f.spend.perRecipeMicroUsd === null ? "" : " · " + usdOf(f.spend.perRecipeMicroUsd, 3) + " a recipe" + inBrackets(change(f.spend.perRecipeMicroUsd, prev?.spend.perRecipeMicroUsd, 3))),
    margin: usdOf(f.estimatedMarginMicroUsd) + inBrackets(change(f.estimatedMarginMicroUsd, prev?.estimatedMarginMicroUsd, 2)) + ": net revenue minus all model spend",
    recipes: plural(g.requests, "request", "requests") + inBrackets(change(g.requests, prev?.generations.requests)) + kinds + " · " + int(g.recipesCharged) + " recipes charged, " + (g.boughtUsed === null ? "bought ones unknown" : int(g.boughtUsed) + " of them bought") + (g.refundedRecipes ? " · " + int(g.refundedRecipes) + " given back" : ""),
    free: int(f.free.granted) + " granted" + inBrackets(change(f.free.granted, prev?.free.granted)) + " to " + plural(f.free.claims, "sign-in", "sign-ins") + " · " + int(f.free.used) + " used" + inBrackets(change(f.free.used, prev?.free.used)) + ", model cost " + usdOf(f.free.costMicroUsd) + (f.free.unknownDays ? " (" + plural(f.free.unknownDays, "day", "days") + " not counted)" : ""),
    refunds: plural(f.refunds.purchases, "purchase", "purchases") + inBrackets(change(f.refunds.purchases, prev?.refunds.purchases)) + " refunded by the stores, " + plural(f.refunds.recipes, "recipe", "recipes"),
    problems: problems.length ? problems.join(" · ") : "none",
    console: consoleUrl,
  };
}

/** The short lines' keys (`lines` on the stored report). */
export const WEEKLY_REPORT_LABELS = ["period", "revenue", "purchases", "spend", "margin", "recipes", "free", "refunds", "problems", "console"] as const;
export type WeeklyReportLabel = (typeof WEEKLY_REPORT_LABELS)[number];

/** Every line cut to WEEKLY_REPORT.labelChars code points. */
function capped(lines: Record<WeeklyReportLabel, string>): Record<WeeklyReportLabel, string> {
  const out = {} as Record<WeeklyReportLabel, string>;
  for (const k of WEEKLY_REPORT_LABELS) {
    const cps = Array.from(lines[k]);
    out[k] = cps.length > WEEKLY_REPORT.labelChars ? cps.slice(0, WEEKLY_REPORT.labelChars - 1).join("") + "…" : lines[k];
  }
  return out;
}


// --- The run -------------------------------------------------------------------------------------

export interface WeeklyReportEnv {
  db: Firestore;
  logger: Logger;
  now(): number;
  config(): Promise<RuntimeConfig>;
  project: string;
}

export type WeeklyReportOutcome =
  | { status: "off" }
  | { status: "already"; week: string; doc: Record<string, unknown> }
  | { status: "done"; week: string; doc: Record<string, unknown>; lines: Record<WeeklyReportLabel, string> }
  | { status: "failed"; week: string; reason: string };

export const WEEKLY_REPORT_VERSION = 1;

/**
 * One report of `week` (default: the last complete ISO week). Never throws: a failure is a
 * `weekly_report_failed` line (alerted on, with the email's fields). Off: reads nothing else and
 * writes nothing. `write: false` (the command's dry run): works it out, writes and logs nothing.
 */
export async function runWeeklyReport(env: WeeklyReportEnv, opts: { week?: string; trigger: "schedule" | "cli"; write?: boolean }): Promise<WeeklyReportOutcome> {
  const nowMs = env.now();
  const week = opts.week ?? lastCompleteWeek(nowMs);
  const write = opts.write !== false;
  const consoleUrl = ownerConsoleUrl(env.project);
  const base = { week, trigger: opts.trigger };
  const fail = (reason: string): WeeklyReportOutcome => {
    if (write) env.logger.error("weekly_report_failed", { ...base, reason });
    return { status: "failed", week, reason };
  };
  let config: RuntimeConfig;
  try {
    config = await env.config();
  } catch {
    return fail("CONFIG_UNAVAILABLE");
  }
  if (!config.weeklyReportEnabled && write) {
    env.logger.info("weekly_report_skipped", { ...base, reason: "OFF" });
    return { status: "off" };
  }
  const range = weekRange(week);
  if (!range || range.toMs > nowMs) return fail("BAD_WEEK");
  const ref = weeklyReportRef(env.db, week);
  let existing: Record<string, unknown> | undefined;
  try {
    existing = (await ref.get()).data();
  } catch {
    return fail("READ_FAILED");
  }
  if (existing && write) {
    env.logger.info("weekly_report_skipped", { ...base, reason: "ALREADY_DONE" });
    return { status: "already", week, doc: existing };
  }

  let figures: WeeklyFigures;
  let previous: { week: string; source: "stored" | "computed"; figures: WeeklyFigures };
  try {
    const prevWeek = previousWeek(week)!;
    const [f, prevSnap] = await Promise.all([readWeeklyFigures(env.db, week, env.project, env.logger), weeklyReportRef(env.db, prevWeek).get()]);
    figures = f;
    const stored = prevSnap.data()?.figures as WeeklyFigures | undefined;
    if (stored && typeof stored === "object" && stored.sales) previous = { week: prevWeek, source: "stored", figures: stored };
    else previous = { week: prevWeek, source: "computed", figures: await readWeeklyFigures(env.db, prevWeek, env.project, env.logger) };
  } catch {
    return fail("READ_FAILED");
  }
  const lines = capped(reportLines(figures, previous.figures, consoleUrl));
  const doc: Record<string, unknown> = {
    week,
    from: new Date(range.fromMs).toISOString(),
    to: new Date(range.toMs).toISOString(),
    trigger: opts.trigger,
    version: WEEKLY_REPORT_VERSION,
    generatedAt: Timestamp.fromMillis(env.now()),
    figures,
    previous,
    lines,
  };
  if (!write) return { status: "done", week, doc, lines };
  try {
    // create(): a run that raced this one and wrote first wins; this one changes nothing.
    await ref.create(doc);
  } catch (e) {
    if ((e as { code?: unknown }).code === 6) {
      env.logger.info("weekly_report_skipped", { ...base, reason: "ALREADY_DONE" });
      return { status: "already", week, doc: (await ref.get()).data() ?? doc };
    }
    return fail("WRITE_FAILED");
  }
  env.logger.info("weekly_report_ready", base);
  return { status: "done", week, doc, lines };
}

/**
 * The Monday job: the weekly report and the prompt review side by side, neither waiting on or
 * stopped by the other; then, once both are done (whatever came of them), their emails
 * (core/reportEmail.ts sendWeeklyEmails), so an empty week's review note can go in the report's
 * email. A job that throws (none should) is logged as its own failure line.
 */
export async function runWeeklyJobs(
  jobs: { report(): Promise<unknown>; review(): Promise<unknown>; emails?(): Promise<unknown> },
  logger: Logger,
  ctx: { week: string; project: string },
): Promise<void> {
  const { week } = ctx;
  const [report, review] = await Promise.allSettled([
    Promise.resolve().then(() => jobs.report()),
    Promise.resolve().then(() => jobs.review()),
  ]);
  if (report.status === "rejected") logger.error("weekly_report_failed", { week, trigger: "schedule", reason: "ERROR" });
  if (review.status === "rejected") logger.error("prompt_review_failed", { week, trigger: "schedule", reason: "ERROR", costMicroUsd: 0 });
  if (!jobs.emails) return;
  try {
    await jobs.emails();
  } catch {
    logger.error("report_email_failed", { report: "weekly_jobs", week, trigger: "schedule", reason: "ERROR", maybeSent: true, recorded: false });
  }
}

// --- The console's view --------------------------------------------------------------------------

export interface WeeklyReportRow {
  label: string;
  value: string;
  /** Against the week before ("" without one). */
  change: string;
  note: string;
}

export interface WeeklyReportView {
  week: string;
  span: string;
  generatedAt: string | null;
  previousWeek: string | null;
  rows: WeeklyReportRow[];
  /** What the figures could not count fully (read caps, days before a counter existed). */
  caveats: string[];
  /** Its email: sent, not sent and why, or null before one was tried (core/emailState.ts). */
  email: string | null;
}

/** The last few weeks in one line each, newest first. */
export interface WeeklyHistoryRow {
  week: string;
  revenue: string;
  spend: string;
  margin: string;
  purchases: string;
  requests: string;
  freeUsed: string;
}

function rowsOf(f: WeeklyFigures, p: WeeklyFigures | null): WeeklyReportRow[] {
  const g = f.generations;
  const pg = p?.generations;
  const row = (label: string, value: string, ch: string, note = ""): WeeklyReportRow => ({ label, value, change: ch, note });
  const failed = (x: WeeklyFigures["generations"]) => x.contentFailures + x.infraFailures + x.qualityFailures;
  const packs = PACKS.filter((k) => f.sales.byPack[k].purchases > 0).map((k) => int(f.sales.byPack[k].purchases) + " × " + PACK_LABEL[k]).join(", ");
  const stores = STORES.filter((k) => f.sales.byStore[k].purchases > 0).map((k) => STORE_LABEL[k] + " " + int(f.sales.byStore[k].purchases)).join(", ");
  return [
    row("Estimated net revenue", usdOf(f.sales.estimatedNetMicroUsd), change(f.sales.estimatedNetMicroUsd, p?.sales.estimatedNetMicroUsd, 2),
      plural(f.sales.recipes - f.sales.refundedSince, "recipe", "recipes") + " sold net of refunds × " + usdOf(f.sales.netMicroUsdPerCredit, 3) + " (the config's estimate after store fees and VAT; not a payout)"),
    row("Purchases", int(f.sales.purchases), change(f.sales.purchases, p?.sales.purchases), [packs ? "packs " + packs : "", stores, f.sales.sandboxPurchases ? int(f.sales.sandboxPurchases) + " sandbox (not counted)" : ""].filter(Boolean).join(" · ")),
    row("Recipes sold", int(f.sales.recipes), change(f.sales.recipes, p?.sales.recipes), int(f.sales.refundedSince) + " of them refunded since"),
    row("Refunded by the stores", plural(f.refunds.purchases, "purchase", "purchases"), change(f.refunds.purchases, p?.refunds.purchases), plural(f.refunds.recipes, "recipe", "recipes") + ", refund reported in the week, whenever bought"),
    row("Model spend", usdOf(f.spend.totalMicroUsd), change(f.spend.totalMicroUsd, p?.spend.totalMicroUsd, 2),
      "text " + usdOf(f.spend.textMicroUsd) + ", pictures " + usdOf(f.spend.picturesMicroUsd) + ", second check " + usdOf(f.spend.judgeMicroUsd) + " (estimated), prompt review " + usdOf(f.spend.promptReviewMicroUsd)),
    row("Spend per recipe kept", f.spend.perRecipeMicroUsd === null ? "–" : usdOf(f.spend.perRecipeMicroUsd, 3), f.spend.perRecipeMicroUsd === null ? "" : change(f.spend.perRecipeMicroUsd, p?.spend.perRecipeMicroUsd, 3)),
    row("Estimated margin", usdOf(f.estimatedMarginMicroUsd), change(f.estimatedMarginMicroUsd, p?.estimatedMarginMicroUsd, 2), "net revenue minus all model spend, free recipes' included"),
    row("Requests", int(g.requests), change(g.requests, pg?.requests),
      (g.menus === null ? "recipes and menus not split yet" : plural(g.requests - g.menus, "recipe", "recipes") + ", " + plural(g.menus, "menu", "menus")) + " · " + int(g.succeeded) + " written"),
    row("Recipes charged", int(g.recipesCharged), change(g.recipesCharged, pg?.recipesCharged), g.boughtUsed === null ? "bought and free not split for every day" : int(g.boughtUsed) + " bought, " + int(f.free.used) + " free"),
    row("Failed answers", int(failed(g)), pg ? change(failed(g), failed(pg)) : "", int(g.contentFailures) + " content, " + int(g.infraFailures) + " infrastructure, " + int(g.qualityFailures) + " quality gate · " + int(g.refundedRecipes) + " recipes given back"),
    row("Free recipes granted", int(f.free.granted), change(f.free.granted, p?.free.granted), plural(f.free.claims, "sign-in", "sign-ins")),
    row("Free recipes used", int(f.free.used), change(f.free.used, p?.free.used), "model cost " + usdOf(f.free.costMicroUsd)),
    row("Pictures", int(g.pictures.delivered) + " delivered", change(g.pictures.delivered, pg?.pictures.delivered), plural(g.pictures.requests, "request", "requests") + " with pictures, " + int(g.pictures.failures) + " not delivered"),
    row("Recipe reports", int(f.problems.reports), change(f.problems.reports, p?.problems.reports),
      Object.entries(f.problems.reportReasons).filter(([, n]) => n > 0).sort((a, b) => b[1] - a[1]).map(([k, n]) => int(n) + " " + k.replaceAll("_", " ")).join(", ")),
    row("Spend ceiling reached", f.problems.ceilingDays.length ? f.problems.ceilingDays.map(dayName).join(", ") : "never", "",
      f.problems.over80Days.length ? "over 80 % on " + f.problems.over80Days.map(dayName).join(", ") : ""),
    row("Daily cap reached", f.problems.dailyCapDays.length ? f.problems.dailyCapDays.map(dayName).join(", ") : "never", ""),
    row("Unspent recipes", int(f.unspent.recipes), change(f.unspent.recipes, p?.unspent.recipes), "in " + plural(f.unspent.accounts, "account", "accounts") + ", at the time of the run"),
  ];
}

function caveatsOf(f: WeeklyFigures): string[] {
  const out: string[] = [];
  if (f.sales.capped) out.push("More purchases than one run reads: the oldest of the week are missing from the sales figures.");
  if (f.refunds.capped) out.push("More refunds than one run reads: the refund count is a lower bound.");
  if (f.spend.judgeTruncated) out.push("The second check's spend was estimated from the first " + int(f.spend.judgeAnswers) + " answers only.");
  if (f.generations.menusUnknownDays) out.push(plural(f.generations.menusUnknownDays, "day", "days") + " from before menus were counted apart.");
  if (f.free.unknownDays) out.push(plural(f.free.unknownDays, "day", "days") + " from before free recipes used were counted.");
  return out;
}

export function weeklyReportViewOf(d: Record<string, unknown>): WeeklyReportView | null {
  const f = d.figures as WeeklyFigures | undefined;
  if (!f || typeof f !== "object" || !f.sales) return null;
  const prev = (d.previous as { week?: unknown; figures?: WeeklyFigures } | null | undefined) ?? null;
  const gen = d.generatedAt instanceof Timestamp ? d.generatedAt.toDate().toISOString() : null;
  return {
    week: String(d.week ?? f.week),
    span: weekSpanOf(String(d.week ?? f.week)),
    generatedAt: gen,
    previousWeek: typeof prev?.week === "string" ? prev.week : null,
    rows: rowsOf(f, prev?.figures?.sales ? prev.figures : null),
    caveats: caveatsOf(f),
    email: emailStatusText(emailRecordOf(d), "weekly_report"),
  };
}

export function historyOf(d: Record<string, unknown>): WeeklyHistoryRow | null {
  const f = d.figures as WeeklyFigures | undefined;
  if (!f?.sales) return null;
  return {
    week: String(d.week ?? f.week),
    revenue: usdOf(f.sales.estimatedNetMicroUsd),
    spend: usdOf(f.spend.totalMicroUsd),
    margin: usdOf(f.estimatedMarginMicroUsd),
    purchases: int(f.sales.purchases),
    requests: int(f.generations.requests),
    freeUsed: int(f.free.used),
  };
}

/** The latest report and the last few weeks (WEEKLY_REPORT.historyWeeks reads at most). */
export async function latestWeeklyReports(db: Firestore): Promise<{ latest: WeeklyReportView | null; history: WeeklyHistoryRow[] }> {
  const snap = await db.collection(WEEKLY_REPORT.collection).orderBy("week", "desc").limit(WEEKLY_REPORT.historyWeeks).get();
  const docs = snap.docs.map((x) => x.data());
  return {
    latest: docs.length ? weeklyReportViewOf(docs[0]) : null,
    history: docs.map(historyOf).filter((x): x is WeeklyHistoryRow => x !== null),
  };
}

/** The command's text: the view's rows, then the caveats. */
export function renderWeeklyReport(v: WeeklyReportView): string[] {
  const out = ["Weekly app report " + v.week + " (" + v.span + ", UTC)" + (v.previousWeek ? ", changes against " + v.previousWeek : ", the first report") + (v.generatedAt ? ", made " + v.generatedAt : "")];
  const w = Math.max(...v.rows.map((r) => r.label.length));
  for (const r of v.rows) out.push("  " + r.label.padEnd(w) + "  " + r.value + (r.change ? " (" + r.change + ")" : "") + (r.note ? "   " + r.note : ""));
  for (const c of v.caveats) out.push("  Note: " + c);
  if (v.email) out.push("  " + v.email);
  out.push("Estimates: revenue is the config's net per recipe, not a store payout; spend is the server's token estimate, not the Cloud bill.");
  return out;
}

