/**
 * generateRecipe: reserve the credits and the worst-case LLM cost, call the LLM, then settle (keep
 * the charge on success, refund on failure, replace the worst-case cost by the measured one).
 * Reserve and settle are separate transactions; settle is conditioned on the generation still being
 * pending, so of a live call and the sweeper only one settles it. Nothing reaches the LLM without a
 * committed reservation.
 */
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import {
  capabilitiesOf,
  fail,
  inputHashOf,
  isNameRejection,
  menuDaysOf,
  isSuspiciousRejection,
  validateGenerateRequest,
  validateRequestId,
  type GenerateRequest,
  type GenerateResponse,
  type Menu,
  type Recipe,
  type RecipeImageWire,
} from "../contract";
import {
  CREDITS_PER_CONTENT_REFUND,
  CREDITS_PER_TIMEOUT_REFUND,
  GENERATION_TTL_MS,
  IMAGE,
  JUDGE,
  MAX_OUTPUT_TOKENS,
  REVENUE_WINDOW_DAYS,
  THINKING_FACTOR,
  TIMINGS,
  costOf,
  type CostKey,
  type Costs,
} from "../constants";
import { buildPrompt, inputTokenBound, newCanary } from "../adapters/prompt";
import { JUDGE_INSTRUCTION } from "../adapters/gemini";
import { QUALITY_OUTPUT_RULES, SUSPICIOUS_OUTPUT_RULES, checkImageBrief, checkOutput, checkStepBriefs, hasEnoughItems, usableStaples } from "./outputSafety";
import { briefRefused, logOutOfCredit, makeRecipeImage, makeStepImages, type ImageOutcome } from "./recipeImage";
import {
  assertOpen,
  costKeyOf,
  dailySpendCeiling,
  REPORTABLE_DAYS,
  generationImageRef,
  generationRef,
  reportableRef,
  imageReservedMicroUsdOf,
  isClosed,
  microUsd,
  paidAllowance,
  statsRef,
  textFallbackOf,
  textOutOfCreditTargetOf,
  textPricesOf,
  textReservePricesOf,
  textSelectionOf,
  uidHash,
  userRef,
  utcDay,
  type GenerationDoc,
  type GenerationImageDoc,
  type RuntimeConfig,
  type UserDoc,
} from "../model";
import { blockFlag } from "./buyers";
import { LogSampler } from "./guard";
import { welcomeFlag } from "./welcome";
import {
  GenerationError,
  type Caller,
  type Deps,
  type FailureClass,
  type GenerationLimits,
  type GenerationResult,
  type LlmUsage,
  type TextTarget,
} from "../ports";
import type { Prompt } from "../adapters/prompt";
import type { TokenPrices } from "../providers/types";

/**
 * A generation's response without its picture, which withImage adds (it is in its own document).
 * `imageFailed` is set on a DONE recipe that was asked with a picture and delivered without one.
 */
function fromDoc(doc: GenerationDoc, balance: number, costs: Costs): GenerateResponse {
  switch (doc.state) {
    case "done":
      return {
        status: "DONE",
        ...(doc.recipe ? { recipe: doc.recipe } : {}),
        ...(doc.menu ? { menu: doc.menu } : {}),
        ...(doc.imageFailed ? { imageFailed: true as const } : {}),
        ...(doc.stepImagesFailed ? { stepImagesFailed: true as const } : {}),
        balance,
        charged: true,
        costs,
      };
    case "pending":
      return { status: "IN_PROGRESS", balance, charged: true, costs };
    case "failed":
      return { status: "FAILED", balance, charged: doc.charged === true, costs };
  }
}

/** What one request costs in credits, may spend in tokens, and reserves in estimated money. */
export interface Plan {
  costKey: CostKey;
  /** With a picture: the table's `recipeImage`. */
  cost: number;
  limits: GenerationLimits;
  /** With a picture: the picture's price included. */
  reservedMicroUsd: number;
  inputHash: string;
  /**
   * A request with `image` ("dish" or "steps"): the dish and steps shares of `cost` and the reserved price of every picture, or
   * `unavailable` when pictures are off (no `recipeImage` in the table, no price for the selected
   * provider and model): then the request is refused before anything is reserved.
   */
  image?: { share: number; stepsShare: number; stepsMax: number; reservedMicroUsd: number } | { unavailable: true };
}

/**
 * The judge's worst case: its input is the validated answer, a subset of the generator's own
 * visible output (at most maxOutputTokens tokens) re-serialised with a little structure, plus its
 * fixed instruction; its output plus thinking is cut at JUDGE.tokenCeiling. Booked at the
 * generator's prices (conservative when the judge model is cheaper).
 */
export function judgeWorstCaseMicroUsd(config: RuntimeConfig, maxOutputTokens: number): number {
  const inputTokens = Buffer.byteLength(JUDGE_INSTRUCTION, "utf8") + Math.ceil(maxOutputTokens * 1.1) + 200;
  return microUsd(config.prices, inputTokens, JUDGE.tokenCeiling);
}

export function planFor(req: GenerateRequest, config: RuntimeConfig): Plan {
  const costKey = costKeyOf(req);
  const maxOutputTokens = MAX_OUTPUT_TOKENS[costKey];
  const hardTokenCeiling = THINKING_FACTOR * maxOutputTokens;
  const judge = config.outputJudge === "off" ? 0 : judgeWorstCaseMicroUsd(config, maxOutputTokens);
  const text = costOf(config.costs, costKey);
  let image: Plan["image"];
  if (req.image) {
    // Every picture at the dearer of the model and its fallback: either may make it.
    const price = imageReservedMicroUsdOf(config);
    const dish = config.costs.recipeImage;
    const steps = config.costs.recipeImageSteps;
    const max = req.image === "steps" ? (req.stepImagesMax ?? 0) : 0;
    if (price === null || dish === undefined || (req.image === "steps" && (steps === undefined || max === 0))) {
      image = { unavailable: true };
    } else {
      image = {
        share: dish - text,
        stepsShare: req.image === "steps" ? steps! - dish : 0,
        stepsMax: max,
        // The worst case: the dish's picture and every step picture asked, each at its price.
        reservedMicroUsd: price * (1 + max),
      };
    }
  }
  const picture = image && "share" in image ? image : undefined;
  return {
    costKey,
    cost: text + (picture ? picture.share + picture.stepsShare : 0),
    limits: { maxOutputTokens, hardTokenCeiling },
    // The text at the dearer of its provider and every one it may fall back to (with the deploy
    // default and no fallback: config.prices, as before providers were switchable).
    reservedMicroUsd: microUsd(textReservePricesOf(config), inputTokenBound(req), hardTokenCeiling) + judge + (picture?.reservedMicroUsd ?? 0),
    inputHash: inputHashOf(req),
    ...(image ? { image } : {}),
  };
}

const sameInputs = (doc: GenerationDoc, req: GenerateRequest, plan: Plan) =>
  doc.inputHash !== undefined
    ? doc.inputHash === plan.inputHash
    : doc.kind === req.kind && (doc.courses ?? null) === (req.courses ?? null)
      && (doc.kind !== "menu" || (doc.days ?? 3) === menuDaysOf(req));

type Reservation =
  | { reserved: true; balance: number }
  | { reserved: false; response: GenerateResponse; user: UserDoc | undefined; generation: GenerationDoc };

/**
 * The model-spend thresholds that are logged (`spend_threshold`), each at most once per UTC day:
 * the first reservation that takes the day's booked spend (spent + reserved worst cases, what the
 * breaker compares) to 50 % or 80 % of the day's ceiling, and the first refusal at the ceiling
 * (100 %, GENERATION_DISABLED). The marks are `stats/{day}.spendAlerts`, written in the same
 * transaction, so the "once" holds across instances and retries.
 */
export const SPEND_THRESHOLDS = [50, 80] as const;

/** A refusal decided inside the reservation transaction after it recorded a once-a-day mark. */
type Refusal = { refused: { code: "unavailable" | "resource-exhausted"; reason: "GENERATION_DISABLED" | "DAILY_CAP"; message: string } };

/** Error log lines a reservation writes once it committed (never twice for a retried transaction). */
type ReserveLogs = [level: "warn" | "error", message: string, data: Record<string, unknown>][];

/**
 * Per-account daily refusals (USER_DAILY_LIMIT, INFRA_FAILURE_LIMIT) as `generation_refused` lines,
 * for the spike alert (README "Alerts"): at most 30 a minute per instance, with a suppressed count.
 */
const refusalLog = new LogSampler(60_000, 30);

/**
 * A charge (settle keeping `cost` spent) takes the free recipes still unspent first
 * (`welcomeUnspent` to `welcomeSpent`). Only charges after the grant can: one before it found
 * nothing unspent. fifoConsumed leaves `welcomeSpent` out, so free recipes never count as bought
 * use, and bought use before the grant is never hidden by them. Writes nothing for an account
 * without free recipes.
 */
function chargeFreeFirst(user: UserDoc | undefined, cost: number, userUpdate: Record<string, unknown>): void {
  const unspent = user?.welcomeUnspent ?? 0;
  if (unspent <= 0) return;
  const take = Math.min(unspent, cost);
  userUpdate.welcomeUnspent = FieldValue.increment(-take);
  userUpdate.welcomeSpent = FieldValue.increment(take);
}

/**
 * Queues the refusal's line on the current attempt's logs; reserve writes it once the transaction
 * has ended (a retried attempt starts a fresh list, so a line is never written twice).
 */
function logRefusal(logs: ReserveLogs, uid: string, reason: "USER_DAILY_LIMIT" | "INFRA_FAILURE_LIMIT", why: string): void {
  logs.push(["warn", "generation_refused", { reason, why, uidHash: uidHash(uid) }]);
}

function flushReserveLogs(deps: Deps, logs: ReserveLogs): void {
  for (const [level, message, data] of logs) {
    if (message === "generation_refused") {
      const sample = refusalLog.take(deps.now());
      if (sample.log) deps.logger[level](message, { ...data, suppressedSinceLast: sample.suppressed });
    } else {
      deps.logger[level](message, data);
    }
  }
}

/**
 * The per-user and existing-generation checks read only the caller's own documents; the shared
 * stats docs (today's, and the trailing days for the revenue-aware ceiling) are read only when a new
 * reservation is about to be made, so refused calls never contend on them. The one exception is
 * the first refusal of a UTC day at the spend ceiling or at `dailyCap`, which records that once
 * (`spendAlerts["100"]`, `dailyCapReachedAt`) and logs it for the alerts (README "Alerts").
 */
async function reserve(deps: Deps, uid: string, req: GenerateRequest, config: RuntimeConfig, plan: Plan): Promise<Reservation> {
  let logs: ReserveLogs = [];
  let result: Reservation | Refusal;
  try {
    result = await reserveTx(deps, uid, req, config, plan, (l) => (logs = l));
  } finally {
    // The last attempt's lines only, whether it committed or refused.
    flushReserveLogs(deps, logs);
  }
  if ("refused" in result) fail(result.refused.code, result.refused.reason, result.refused.message);
  return result;
}

async function reserveTx(
  deps: Deps,
  uid: string,
  req: GenerateRequest,
  config: RuntimeConfig,
  plan: Plan,
  setLogs: (logs: ReserveLogs) => void,
): Promise<Reservation | Refusal> {
  const { cost, reservedMicroUsd } = plan;
  return deps.db.runTransaction(async (tx): Promise<Reservation | Refusal> => {
    const logs: ReserveLogs = [];
    setLogs(logs);
    const now = deps.now();
    const day = utcDay(now);
    const uRef = userRef(deps.db, uid);
    const gRef = generationRef(deps.db, uid, req.requestId);
    const sRef = statsRef(deps.db, day);
    const [uSnap, gSnap] = await Promise.all([tx.get(uRef), tx.get(gRef)]);
    const user = uSnap.data();
    assertOpen(user);
    const existing = gSnap.data();
    if (existing) {
      // A retry with the same requestId never charges again and never calls the LLM again, and
      // only ever returns what was generated for these exact inputs.
      if (!sameInputs(existing, req, plan)) {
        fail("failed-precondition", "REQUEST_ID_REUSED", "This requestId was used for a different request");
      }
      return { reserved: false, response: fromDoc(existing, user?.balance ?? 0, config.costs), user, generation: existing };
    }

    if (!config.generationEnabled) fail("unavailable", "GENERATION_DISABLED", "Recipe generation is paused");
    // Pictures off (or switched to an unpriced model) since the client last saw the table.
    if (plan.image && "unavailable" in plan.image) fail("unavailable", "IMAGE_UNAVAILABLE", "Recipe pictures are not available");
    if (!user || user.balance < cost) fail("failed-precondition", "INSUFFICIENT_CREDITS", "Not enough recipes left");
    if (user.inFlight > 0) fail("failed-precondition", "BUSY", "Another recipe is being generated");

    const minute = Math.floor(now / 60_000);
    const mw = user.minuteWindow?.start === minute ? user.minuteWindow : { start: minute, count: 0 };
    const dw = user.dayWindow?.day === day ? user.dayWindow : { day, count: 0 };
    if (mw.count >= config.perUserPerMinute) fail("resource-exhausted", "RATE_LIMITED", "Too many recipes in a short time");
    const rejections = user.promptRejections?.day === day ? user.promptRejections.count : 0;
    if (rejections >= config.rejectedPromptsPerDay) {
      deps.logger.warn("prompt_rejection_limit", { uidHash: uidHash(uid), rejectionsToday: rejections, limit: config.rejectedPromptsPerDay });
      fail("resource-exhausted", "USER_DAILY_LIMIT", "Daily limit for this account reached");
    }
    const infra = user.infraRefunds?.day === day ? user.infraRefunds.count : 0;
    if (infra >= config.infraRefundsPerDay) {
      logRefusal(logs, uid, "INFRA_FAILURE_LIMIT", "infra_refunds");
      fail("resource-exhausted", "INFRA_FAILURE_LIMIT", "Generation failed too often today; try again tomorrow");
    }
    const userSpend = user.spendWindow?.day === day ? user.spendWindow.microUsd : 0;
    if (dw.count >= config.perUserPerDay || userSpend + reservedMicroUsd > config.perUserDailySpendMicroUsd) {
      logRefusal(logs, uid, "USER_DAILY_LIMIT", dw.count >= config.perUserPerDay ? "generations" : "spend");
      fail("resource-exhausted", "USER_DAILY_LIMIT", "Daily limit for this account reached");
    }
    const pastRefs = Array.from({ length: REVENUE_WINDOW_DAYS - 1 }, (_, i) => statsRef(deps.db, utcDay(now - (i + 1) * 86_400_000)));
    const [sSnap, ...pastSnaps] = await tx.getAll(sRef, ...pastRefs);
    const stats = sSnap.data() ?? {};
    const units = (stats.generationUnits as number | undefined) ?? 0;
    const marks = (stats.spendAlerts ?? {}) as Record<string, unknown>;
    if (units + cost > config.dailyCap) {
      // The first refusal of the day records it (stats/{day}.dailyCapReachedAt) and is logged once.
      if (stats.dailyCapReachedAt !== undefined) fail("resource-exhausted", "DAILY_CAP", "Daily generation limit reached");
      tx.set(sRef, { dailyCapReachedAt: now }, { merge: true });
      logs.push(["error", "daily_cap_reached", { day, dailyCap: config.dailyCap, generationUnits: units }]);
      return { refused: { code: "resource-exhausted", reason: "DAILY_CAP", message: "Daily generation limit reached" } };
    }
    // The real-time spend breaker: today's estimated spend plus every in-flight worst case, against
    // the revenue-aware ceiling. The worst case is reserved here, inside the transaction, so
    // parallel calls cannot overshoot.
    const netCredits7d = [sSnap, ...pastSnaps].reduce((sum, s) => sum + ((s.data()?.netCreditsGranted as number | undefined) ?? 0), 0);
    const spent = ((stats.spentMicroUsd as number | undefined) ?? 0) + ((stats.reservedMicroUsd as number | undefined) ?? 0);
    const ceiling = dailySpendCeiling(config, netCredits7d);
    const booked = spent + reservedMicroUsd;
    const thresholdLine = (percent: number, spentMicroUsd: number) =>
      ({ percent, spentMicroUsd, ceilingMicroUsd: ceiling, day, netCredits7d });
    if (booked > ceiling) {
      // The first refusal of the day at the ceiling is recorded and logged once (100 %).
      if (marks["100"] !== undefined) fail("unavailable", "GENERATION_DISABLED", "Recipe generation is paused");
      tx.set(sRef, { spendAlerts: { "100": now }, ceilingMicroUsd: ceiling }, { merge: true });
      logs.push(["error", "spend_threshold", thresholdLine(100, spent)]);
      return { refused: { code: "unavailable", reason: "GENERATION_DISABLED", message: "Recipe generation is paused" } };
    }
    const crossed = SPEND_THRESHOLDS.filter((p) => marks[`${p}`] === undefined && booked * 100 >= p * ceiling);
    for (const p of crossed) logs.push(["warn", "spend_threshold", thresholdLine(p, booked)]);

    tx.update(uRef, {
      balance: FieldValue.increment(-cost),
      inFlight: FieldValue.increment(1),
      minuteWindow: { start: minute, count: mw.count + 1 },
      dayWindow: { day, count: dw.count + 1 },
      spendWindow: { day, microUsd: userSpend + reservedMicroUsd },
    });
    tx.set(
      sRef,
      {
        generationUnits: FieldValue.increment(cost),
        generations: FieldValue.increment(1),
        reservedMicroUsd: FieldValue.increment(reservedMicroUsd),
        // The ceiling this reservation was checked against, for the owner's board (a day's
        // history: config/runtime itself keeps none).
        ceilingMicroUsd: ceiling,
        ...(crossed.length ? { spendAlerts: Object.fromEntries(crossed.map((p) => [`${p}`, now])) } : {}),
      },
      { merge: true },
    );
    const doc: GenerationDoc = {
      state: "pending",
      kind: req.kind,
      ...(req.courses ? { courses: req.courses } : {}),
      ...(req.kind === "menu" ? { days: menuDaysOf(req) } : {}),
      cost,
      inputHash: plan.inputHash,
      reservedMicroUsd,
      statsDay: day,
      createdAt: Timestamp.fromMillis(now),
      deadlineAt: Timestamp.fromMillis(now + TIMINGS[req.kind].reservationMs + (req.image ? IMAGE.reservationExtraMs[req.image] : 0)),
      ...(plan.image && "share" in plan.image
        ? {
            imageLevel: req.image!,
            imageCost: plan.image.share,
            ...(req.image === "steps" ? { stepImagesCost: plan.image.stepsShare, stepImagesMax: plan.image.stepsMax } : {}),
            imageReservedMicroUsd: plan.image.reservedMicroUsd,
            imageProvider: config.image.provider,
            imageModel: config.image.model,
          }
        : {}),
    };
    tx.create(gRef, doc);
    return { reserved: true, balance: user.balance - cost };
  });
}

/** The pictures of one recipe: the dish's, and each marked step's. */
export interface PictureOutcome {
  dish: ImageOutcome;
  steps: { step: number; outcome: ImageOutcome }[];
}

/** Tokens booked at one price pair (Outcome.spend). */
export interface SpendPart extends TokenPrices {
  inputTokens: number;
  /** Visible output plus thinking. */
  outputTokens: number;
}

/** The text provider that answered, when it is not the deploy default or a fallback was asked (logs). */
export interface TextAnswered {
  provider: string;
  model: string;
  fallback: boolean;
}

/** What every Outcome may carry beside its result: how its measured usage is priced, and who answered. */
interface OutcomeCosts {
  /**
   * Set only when some call of this outcome is priced otherwise than `config.prices` (another text
   * provider): its usage by price, booked at settle instead of `usage` x `config.prices`.
   */
  spend?: SpendPart[];
  /**
   * Set when some call is priced otherwise than `config.prices` and the usage of some call is
   * unknown: settle then books the reservation (bookedMicroUsd), never the known part at Gemini's.
   */
  bookReserved?: true;
  text?: TextAnswered;
}

export type Outcome = OutcomeCosts & (
  | {
      ok: true;
      recipe?: Recipe;
      menu?: Menu;
      usage?: LlmUsage;
      /** A recipe asked with a picture: the checked brief, or why it was refused. */
      imageBrief?: string;
      briefRule?: string;
      /** Level "steps": the kept step briefs (0-based step), and the rules of those refused. */
      stepBriefs?: { step: number; brief: string }[];
      stepBriefsRefused?: string[];
      /** A recipe asked with pictures: what came of them (core/recipeImage.ts). */
      picture?: PictureOutcome;
    }
  | {
      ok: false;
      failureClass: FailureClass;
      reason: string;
      usage?: LlmUsage;
      /**
       * Settled by the sweeper: the invocation died before it could answer (a server fault the client
       * cannot cause; abandoning a call does not stop it). Always refunded, and counted against
       * neither the daily infrastructure ceiling nor the timeout allowance, so it never locks a user
       * out. Its cost stays booked at the worst case.
       */
      swept?: boolean;
      /**
       * A fixed code when the failure points at prompt injection (a suspicious output rule, a model
       * safety block, a judge rejection): counted in the user's `promptRejections` at settle.
       */
      rejection?: string;
    }
);

/**
 * Settle and reserve both write stats/{day}, a single hot document. A settle lost to contention
 * would leave the reservation to the sweeper (refunded, the output discarded), so it retries longer.
 */
const SETTLE_ATTEMPTS = 10;

export interface Settled {
  response: GenerateResponse;
  /** False when the generation was no longer pending and this outcome was discarded. */
  settledHere: boolean;
  /** Estimated cost booked for the call (the reserved worst case when usage was unknown). */
  actualMicroUsd: number;
  generation: GenerationDoc;
  user: UserDoc | undefined;
  /** The account's prompt rejections today after this settle, when it counted one. */
  rejectionsToday?: number | null;
}

/**
 * Settles a pending generation and books its measured cost against the reservation's stats day
 * and the user's spend window. If the generation was no longer pending (the sweeper or another
 * settle got there first), the given outcome is discarded.
 */
export async function settle(
  deps: Deps,
  uid: string,
  requestId: string,
  outcome: Outcome,
  config: RuntimeConfig,
): Promise<Settled> {
  return deps.db.runTransaction(async (tx) => {
    const now = deps.now();
    const today = utcDay(now);
    const uRef = userRef(deps.db, uid);
    const gRef = generationRef(deps.db, uid, requestId);
    const [uSnap, gSnap] = await Promise.all([tx.get(uRef), tx.get(gRef)]);
    const user = uSnap.data();
    const gen = gSnap.data();
    if (!gen) throw new Error("generation vanished before settle");
    if (gen.state !== "pending") {
      return {
        response: fromDoc(gen, isClosed(user) ? 0 : (user?.balance ?? 0), config.costs),
        settledHere: false,
        actualMicroUsd: 0,
        generation: gen,
        user,
      };
    }

    const reserved = gen.reservedMicroUsd ?? 0;
    const usage = outcome.usage;
    // A picture is booked at its price when the provider may have charged for it, else at 0;
    // with the text's usage unknown, the whole reservation stays booked.
    const missing: ImageOutcome = { ok: false, failure: "MISSING", microUsd: 0, model: null };
    const picture: PictureOutcome | undefined = outcome.ok && gen.imageLevel ? outcome.picture ?? { dish: missing, steps: [] } : undefined;
    const pictures = picture ? [picture.dish, ...picture.steps.map((s) => s.outcome)] : [];
    const pictureMicroUsd = pictures.reduce((sum, p) => sum + p.microUsd, 0);
    const actual = bookedMicroUsd(outcome, config, reserved, pictureMicroUsd);
    const statsDay = gen.statsDay ?? utcDay(gen.createdAt.toMillis());

    const expiresAt = Timestamp.fromMillis(now + GENERATION_TTL_MS);
    const closed = !user || isClosed(user);
    const userUpdate: Record<string, unknown> = { inFlight: FieldValue.increment(-1) };
    let balance = user?.balance ?? 0;
    let finalDoc: GenerationDoc;
    let refunded = false;
    let rejectionsToday: number | null = null;
    /** Credits of the picture's share given back (a recipe delivered without its picture). */
    let givenBack = 0;

    if (outcome.ok) {
      // The text was delivered. Without its picture, the picture's share as stored at
      // reservation is always given back: outside the content and timeout allowances, not an
      // infrastructure refund (infraRefundsPerDay), whatever the reason.
      // The rule: no dish picture: the whole picture share back (dish and steps), whatever step
      // pictures came. Dish picture, level "steps" and not one step picture delivered (none marked,
      // or every one failed): the steps' share back. At least one step picture, even fewer than
      // the maximum: kept.
      const dishMissing = !!picture && !picture.dish.ok;
      const stepsMissing = gen.imageLevel === "steps" && !!picture && !picture.steps.some((s) => s.outcome.ok);
      givenBack = Math.max(0, Math.min(gen.cost, dishMissing
        ? (gen.imageCost ?? 0) + (gen.stepImagesCost ?? 0)
        : stepsMissing ? gen.stepImagesCost ?? 0 : 0));
      const kept = gen.cost - givenBack;
      const deliveredSteps = picture ? picture.steps.filter((s) => s.outcome.ok) : [];
      userUpdate.consumedCredits = FieldValue.increment(kept);
      chargeFreeFirst(user, kept, userUpdate);
      if (givenBack > 0) {
        userUpdate.balance = FieldValue.increment(givenBack);
        balance += givenBack;
      }
      finalDoc = {
        ...gen,
        state: "done",
        charged: true,
        expiresAt,
        ...(outcome.recipe ? { recipe: outcome.recipe } : {}),
        ...(outcome.menu ? { menu: outcome.menu } : {}),
        ...(picture?.dish.ok ? { imageStored: true as const } : {}),
        ...(picture && !picture.dish.ok ? { imageFailed: true as const, imageFailure: picture.dish.failure.slice(0, 40) } : {}),
        ...(gen.imageLevel === "steps" && picture
          ? {
              stepImagesMarked: picture.steps.length,
              stepImagesStored: deliveredSteps.map((s) => s.step),
              // Some step picture asked was not delivered (the share follows stepsMissing).
              ...(picture.steps.some((s) => !s.outcome.ok) ? { stepImagesFailed: true as const } : {}),
            }
          : {}),
      };
      // The lasting trace that this account had this result, for reportRecipe after the generation expires.
      tx.set(reportableRef(deps.db, uid, requestId), {
        kind: gen.kind,
        at: Timestamp.fromMillis(now),
        expiresAt: Timestamp.fromMillis(now + REPORTABLE_DAYS * 86_400_000),
      });
      // One document per picture: each stays far below Firestore's 1 MiB limit.
      const stored: { step?: number; out: ImageOutcome }[] = picture ? [{ out: picture.dish }, ...deliveredSteps.map((s) => ({ step: s.step, out: s.outcome }))] : [];
      for (const { step, out } of stored) {
        if (!out.ok) continue;
        tx.set(generationImageRef(deps.db, uid, requestId, step), {
          mimeType: "image/jpeg",
          data: out.image.data,
          width: out.image.width,
          height: out.image.height,
          expiresAt,
        });
      }
    } else {
      if (outcome.failureClass === "quality") {
        // The quality gate refused a clean answer: the service did not deliver what was paid
        // for. Always refunded, like an infrastructure failure with known usage, and counted in
        // infraRefunds below, so it cannot be repeated more than infraRefundsPerDay times a day.
        refunded = true;
      } else if (outcome.failureClass === "content") {
        // Refunded within an allowance tied to what the account has paid for, never per day.
        const used = user?.contentRefundsUsed ?? 0;
        refunded = !!user && used < paidAllowance(user, CREDITS_PER_CONTENT_REFUND);
        if (refunded) userUpdate.contentRefundsUsed = used + 1;
      } else if (outcome.swept) {
        refunded = true;
      } else if (usage) {
        // An infrastructure failure with known usage (nothing generated, or measured) is always
        // refunded; reserve refuses once the daily ceiling is hit.
        refunded = true;
      } else {
        // Unknown usage (a timeout, mid-stream error, token-ceiling abort or dead invocation) is
        // booked at the worst case, so its refund is rationed like content failures: timing out on
        // purpose must not be free for ever.
        const used = user?.timeoutRefundsUsed ?? 0;
        refunded = !!user && used < paidAllowance(user, CREDITS_PER_TIMEOUT_REFUND);
        if (refunded) userUpdate.timeoutRefundsUsed = used + 1;
      }
      if (refunded && outcome.failureClass !== "content" && !outcome.swept) {
        const count = user?.infraRefunds?.day === today ? user.infraRefunds.count : 0;
        userUpdate.infraRefunds = { day: today, count: count + 1 };
      }
      if (refunded) {
        userUpdate.balance = FieldValue.increment(gen.cost);
        balance += gen.cost;
      } else {
        // A charged text failure keeps only the text's share: no picture was attempted, so the
        // picture shares stored at reservation always come back.
        givenBack = Math.max(0, Math.min(gen.cost, (gen.imageCost ?? 0) + (gen.stepImagesCost ?? 0)));
        const kept = gen.cost - givenBack;
        userUpdate.consumedCredits = FieldValue.increment(kept);
        chargeFreeFirst(user, kept, userUpdate);
        if (givenBack > 0) {
          userUpdate.balance = FieldValue.increment(givenBack);
          balance += givenBack;
        }
      }
      if (outcome.rejection) {
        rejectionsToday = (user?.promptRejections?.day === today ? user.promptRejections.count : 0) + 1;
        userUpdate.promptRejections = { day: today, count: rejectionsToday };
      }
      finalDoc = {
        ...gen,
        state: "failed",
        charged: !refunded,
        failureClass: outcome.failureClass,
        failureReason: outcome.reason.slice(0, 200),
        expiresAt,
      };
    }
    finalDoc.actualMicroUsd = actual;
    if (usage) finalDoc.usage = usage;
    if (outcome.text) finalDoc.textProvider = outcome.text.provider;

    if (user?.spendWindow?.day === statsDay) {
      userUpdate["spendWindow.microUsd"] = FieldValue.increment(actual - reserved);
    }
    // A deleted or merged account's balance is gone; only its in-flight count is released.
    if (user && !closed) tx.update(uRef, userUpdate);
    else if (user) tx.update(uRef, { inFlight: FieldValue.increment(-1) });

    const statsUpdate: Record<string, unknown> = {
      reservedMicroUsd: FieldValue.increment(-reserved),
      spentMicroUsd: FieldValue.increment(actual),
      llmCalls: FieldValue.increment(1),
      [outcome.ok ? "succeeded" : `${outcome.failureClass}Failures`]: FieldValue.increment(1),
    };
    if (refunded) statsUpdate.refundedCredits = FieldValue.increment(gen.cost);
    if (gen.imageLevel) {
      statsUpdate.imageRequests = FieldValue.increment(1);
      if (picture) {
        const delivered = pictures.filter((p) => p.ok).length;
        if (delivered) statsUpdate.imagesDelivered = FieldValue.increment(delivered);
        if (pictures.length - delivered) statsUpdate.imageFailures = FieldValue.increment(pictures.length - delivered);
        statsUpdate.imageMicroUsd = FieldValue.increment(pictureMicroUsd);
      }
      if (givenBack > 0) {
        statsUpdate.refundedCredits = FieldValue.increment(givenBack);
        statsUpdate.imageGivenBackCredits = FieldValue.increment(givenBack);
      }
    }
    if (usage) {
      statsUpdate.inputTokens = FieldValue.increment(usage.inputTokens);
      statsUpdate.outputTokens = FieldValue.increment(usage.outputTokens);
      statsUpdate.thinkingTokens = FieldValue.increment(usage.thinkingTokens);
    } else {
      statsUpdate.unknownUsageCalls = FieldValue.increment(1);
    }
    tx.set(statsRef(deps.db, statsDay), statsUpdate, { merge: true });
    tx.set(gRef, finalDoc);
    return {
      response: fromDoc(finalDoc, closed ? 0 : balance, config.costs),
      settledHere: true,
      actualMicroUsd: actual,
      generation: finalDoc,
      user,
      rejectionsToday,
    };
  }, { maxAttempts: SETTLE_ATTEMPTS });
}

/** A checked answer, with the picture briefs when pictures were asked. */
type CheckedAnswer = {
  recipe?: Recipe;
  menu?: Menu;
  imageBrief?: string;
  briefRule?: string;
  stepBriefs?: { step: number; brief: string }[];
  stepBriefsRefused?: string[];
};

/** Sum of two calls' usage; unknown when either is unknown (then the worst case stays booked). */
function addUsage(a: LlmUsage | undefined, b: LlmUsage | undefined): LlmUsage | undefined {
  if (!a || !b) return undefined;
  return {
    inputTokens: a.inputTokens + b.inputTokens,
    outputTokens: a.outputTokens + b.outputTokens,
    thinkingTokens: a.thinkingTokens + b.thinkingTokens,
    model: a.model,
    finishReason: a.finishReason,
  };
}

/**
 * What settle books for an outcome: its measured usage (at `spend`'s prices when set, else at
 * `config.prices`) plus its pictures; the whole reservation when the usage is unknown, or when
 * some call priced otherwise than `config.prices` has unknown usage (`bookReserved`): the
 * reservation is at the dearest provider the request could reach (textReservePricesOf), so a
 * dearer provider is never booked at Gemini's prices.
 */
export function bookedMicroUsd(outcome: Outcome, config: RuntimeConfig, reservedMicroUsd: number, pictureMicroUsd: number): number {
  const usage = outcome.usage;
  if (!usage || outcome.bookReserved) return reservedMicroUsd;
  return (outcome.spend ? spendMicroUsd(outcome.spend) : microUsd(config.prices, usage.inputTokens, usage.outputTokens + usage.thinkingTokens)) + pictureMicroUsd;
}

/** Micro-USD of usage booked at several price pairs: one rounding up over the sum, as microUsd does. */
export function spendMicroUsd(parts: readonly SpendPart[]): number {
  const byPrice = new Map<string, SpendPart>();
  for (const p of parts) {
    const key = `${p.inputUsdPerMTok}/${p.outputUsdPerMTok}`;
    const s = byPrice.get(key);
    if (s) {
      s.inputTokens += p.inputTokens;
      s.outputTokens += p.outputTokens;
    } else byPrice.set(key, { ...p });
  }
  let total = 0;
  for (const s of byPrice.values()) total += s.inputTokens * s.inputUsdPerMTok + s.outputTokens * s.outputUsdPerMTok;
  return Math.ceil(total);
}

/** Every model call of one generation, with the prices each is booked at (Outcome.spend). */
export interface CallLedger {
  parts: { prices: TokenPrices; usage: LlmUsage | undefined }[];
  answered?: TextTarget;
  fallback: boolean;
}

/**
 * The text call and its fallback, as an interface-level rule for every provider (README "Adding a
 * provider"): the selected provider first; when it failed before generating anything (its usage
 * known, no output or thinking tokens) for a reason about the provider, one more call to the
 * fallback, in the same time budget:
 * - out of credit (`provider_out_of_credit` logged): the named fallback, else the deploy default
 *   provider when it is another one;
 * - capacity or a configuration failure (no adapter, a refused key): the named fallback only;
 * - never after a safety refusal, a content failure or anything mid-stream.
 * The person's recipes follow the outcome's failure class as before (a known-usage infra failure
 * is refunded); the reservation already covers the dearer provider (planFor).
 */
async function generateText(
  deps: Pick<Deps, "generator" | "logger">,
  uid: string,
  req: GenerateRequest,
  prompt: Prompt,
  signal: AbortSignal,
  limits: GenerationLimits,
  config: RuntimeConfig,
  ledger: CallLedger,
): Promise<GenerationResult> {
  const sel = textSelectionOf(config);
  const primary: TextTarget = { provider: sel.provider, model: sel.model };
  const priced = (t: TextTarget) => textPricesOf(config, t) ?? textReservePricesOf(config);
  ledger.answered = primary;
  let first: GenerationError;
  try {
    const result = await deps.generator.generate(req, prompt, signal, limits, primary);
    ledger.parts.push({ prices: priced(primary), usage: result.usage });
    return result;
  } catch (e) {
    if (!(e instanceof GenerationError)) throw e;
    ledger.parts.push({ prices: priced(primary), usage: e.usage });
    first = e;
  }
  const kind = first.providerKind;
  if (kind === "out_of_credit") logOutOfCredit(deps, primary, "text");
  const nothingMade = first.usage !== undefined && first.usage.outputTokens + first.usage.thinkingTokens === 0;
  const next = kind === "out_of_credit"
    ? textOutOfCreditTargetOf(config)
    : kind === "capacity" || kind === "config" ? textFallbackOf(config) : null;
  if (!next || !nothingMade || signal.aborted) throw first;
  deps.logger.info("text_fallback", {
    uidHash: uidHash(uid),
    provider: primary.provider,
    model: primary.model,
    fallbackProvider: next.provider,
    fallbackModel: next.model,
    reason: kind.toUpperCase(),
  });
  ledger.answered = next;
  ledger.fallback = true;
  try {
    const result = await deps.generator.generate(req, prompt, signal, limits, next);
    ledger.parts.push({ prices: priced(next), usage: result.usage });
    // The first call's (known, unbilled-output) usage is part of the outcome's.
    return { ...result, usage: addUsage(first.usage, result.usage) };
  } catch (e) {
    if (!(e instanceof GenerationError)) throw e;
    ledger.parts.push({ prices: priced(next), usage: e.usage });
    if (e.providerKind === "out_of_credit") logOutOfCredit(deps, next, "text");
    throw new GenerationError(e.failureClass, e.message, addUsage(first.usage, e.usage), e.safety, e.kind);
  }
}

/**
 * Adds the ledger's pricing and the answering provider to an outcome (nothing for the default
 * path). Exported for the tests.
 */
export function withCosts(outcome: Outcome, ledger: CallLedger, config: RuntimeConfig): Outcome {
  const base = config.prices;
  const otherPrice = ledger.parts.some((p) => p.prices.inputUsdPerMTok !== base.inputUsdPerMTok || p.prices.outputUsdPerMTok !== base.outputUsdPerMTok);
  const known = ledger.parts.every((p) => p.usage !== undefined);
  const spend = outcome.usage && otherPrice && known
    ? ledger.parts.map((p) => ({
        inputUsdPerMTok: p.prices.inputUsdPerMTok,
        outputUsdPerMTok: p.prices.outputUsdPerMTok,
        inputTokens: p.usage!.inputTokens,
        outputTokens: p.usage!.outputTokens + p.usage!.thinkingTokens,
      }))
    : undefined;
  const a = ledger.answered;
  const defaultProvider = config.providerDefaults?.text.provider ?? textSelectionOf(config).provider;
  const text = a && (ledger.fallback || a.provider !== defaultProvider) ? { provider: a.provider, model: a.model, fallback: ledger.fallback } : undefined;
  // Another price with a call of unknown usage: nothing measured can be priced honestly.
  const bookReserved = outcome.usage && otherPrice && !known ? { bookReserved: true as const } : {};
  return { ...outcome, ...(spend ? { spend } : {}), ...bookReserved, ...(text ? { text } : {}) };
}

/**
 * The model call and every check on its answer (output rules, quality gate, judge), without
 * reservation or settle: uses only `deps.generator`, `deps.judge` and `deps.logger`. Exported for
 * the red-team runner (admin/redteam), which runs exactly this against the live model.
 */
export async function runGenerator(
  deps: Pick<Deps, "generator" | "judge" | "logger">,
  uid: string,
  req: GenerateRequest,
  limits: GenerationLimits,
  config: RuntimeConfig,
): Promise<Outcome> {
  const ledger: CallLedger = { parts: [], fallback: false };
  return withCosts(await runChecked(deps, uid, req, limits, config, ledger), ledger, config);
}

async function runChecked(
  deps: Pick<Deps, "generator" | "judge" | "logger">,
  uid: string,
  req: GenerateRequest,
  limits: GenerationLimits,
  config: RuntimeConfig,
  ledger: CallLedger,
): Promise<Outcome> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMINGS[req.kind].llmAbortMs);
  const canary = newCanary();
  const prompt = buildPrompt(req, canary);
  let checked: CheckedAnswer;
  let usage: LlmUsage | undefined;
  try {
    const result = await generateText(deps, uid, req, prompt, controller.signal, limits, config, ledger);
    usage = result.usage;
    // The answer arrived too late to use, but its usage is known and is what gets booked.
    if (controller.signal.aborted) return { ok: false, failureClass: "infra", reason: "timeout", usage };
    const check = checkOutput(result.output, { req, canary, systemInstruction: prompt.systemInstruction });
    if (!check.ok && QUALITY_OUTPUT_RULES.has(check.rule) && check.value) {
      const quality: Outcome = { ok: false, failureClass: "quality", reason: `output rule ${check.rule}`, usage };
      if (config.outputJudge === "off") return quality;
      // The judge still sees the answer: one it refuses stays a charged content failure and a
      // prompt rejection, so skipping the pantry cannot turn a hostile answer into a refund.
      const judged = await runJudge(deps, uid, req, config, check.value, usage, ledger);
      return judged.ok ? { ...quality, usage: judged.usage } : judged.failureClass === "content" ? judged : { ...quality, usage: judged.usage };
    }
    if (!check.ok) {
      return {
        ok: false,
        failureClass: "content",
        reason: `output rule ${check.rule}`,
        usage,
        ...(SUSPICIOUS_OUTPUT_RULES.has(check.rule) ? { rejection: `OUTPUT_${check.rule}` } : {}),
      };
    }
    checked = check.value;
    if (req.image) {
      // Only now, on an answer whose text passed: the picture's brief, checked on its own. A brief
      // refused never makes the recipe fail; the picture is then not made.
      const ctx = { req, canary, systemInstruction: prompt.systemInstruction };
      const brief = checkImageBrief(result.output, ctx);
      checked = brief.ok ? { ...checked, imageBrief: brief.brief } : { ...checked, briefRule: brief.rule };
      if (req.image === "steps" && checked.recipe) {
        const steps = checkStepBriefs(result.output, ctx, checked.recipe.steps.length, req.stepImagesMax ?? 0);
        checked = { ...checked, stepBriefs: steps.steps, stepBriefsRefused: steps.refused };
      }
    }
  } catch (e) {
    const u = e instanceof GenerationError ? e.usage : undefined;
    if (controller.signal.aborted) return { ok: false, failureClass: "infra", reason: "timeout", usage: u };
    if (e instanceof GenerationError) {
      return { ok: false, failureClass: e.failureClass, reason: e.message, usage: u, ...(e.safety ? { rejection: e.safety } : {}) };
    }
    deps.logger.error("Generator threw unexpectedly", { error: String(e).slice(0, 200) });
    return { ok: false, failureClass: "infra", reason: "generator error" };
  } finally {
    clearTimeout(timer);
  }
  if (config.outputJudge === "off") return { ok: true, ...checked, usage };
  return runJudge(deps, uid, req, config, checked, usage, ledger);
}

/**
 * The second check (config/runtime `outputJudge`). "enforce": a verdict other than ok is a content
 * failure and a prompt rejection; a judge that fails is an infrastructure failure (the answer is
 * never delivered unchecked). "shadow": the verdict is only logged and the answer is delivered.
 */
async function runJudge(
  deps: Pick<Deps, "judge" | "logger">,
  uid: string,
  req: GenerateRequest,
  config: RuntimeConfig,
  checked: CheckedAnswer,
  genUsage: LlmUsage | undefined,
  ledger: CallLedger,
): Promise<Outcome> {
  const enforce = config.outputJudge === "enforce";
  // The judge always runs on the "google" provider, at config.prices (adapters/gemini.ts).
  const judgePrices = { inputUsdPerMTok: config.prices.inputUsdPerMTok, outputUsdPerMTok: config.prices.outputUsdPerMTok };
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), JUDGE.abortMs[req.kind]);
  try {
    // The judge sees the answer and, when a picture was asked, the brief that would draw it.
    const judged = {
      ...(checked.recipe ? { recipe: checked.recipe } : {}),
      ...(checked.menu ? { menu: checked.menu } : {}),
      ...(checked.imageBrief ? { imageBrief: checked.imageBrief } : {}),
      ...(checked.stepBriefs?.length ? { stepBriefs: checked.stepBriefs.map((s) => s.brief) } : {}),
    };
    const { verdict, usage } = await deps.judge.judge(judged, controller.signal);
    ledger.parts.push({ prices: judgePrices, usage });
    const total = addUsage(genUsage, usage);
    deps.logger.info("prompt_judge_verdict", { uidHash: uidHash(uid), kind: req.kind, verdict, mode: config.outputJudge });
    if (verdict !== "ok" && enforce) {
      return { ok: false, failureClass: "content", reason: `judge ${verdict}`, usage: total, rejection: `JUDGE_${verdict.toUpperCase()}` };
    }
    return { ok: true, ...checked, usage: total };
  } catch (e) {
    ledger.parts.push({ prices: judgePrices, usage: e instanceof GenerationError ? e.usage : undefined });
    const usage = addUsage(genUsage, e instanceof GenerationError ? e.usage : undefined);
    const reason = controller.signal.aborted ? "judge timeout" : e instanceof GenerationError ? e.message : "judge error";
    deps.logger.warn("prompt_judge_failed", { uidHash: uidHash(uid), kind: req.kind, reason: reason.slice(0, 60), mode: config.outputJudge });
    if (!enforce) return { ok: true, ...checked, usage };
    return { ok: false, failureClass: "infra", reason, usage };
  } finally {
    clearTimeout(timer);
  }
}

/**
 * A request refused for a name: logged with the fixed rule code (never the name), and, for a
 * suspicious rule, counted in the account's `promptRejections` for today. Only an existing, open
 * account is updated: an account without a document has no credits and cannot generate anyway.
 */
async function recordInputRejection(deps: Deps, uid: string, details: { field: string; rule: string }, suspicious: boolean): Promise<void> {
  let rejectionsToday: number | null = null;
  if (suspicious) {
    try {
      rejectionsToday = await deps.db.runTransaction(async (tx) => {
        const ref = userRef(deps.db, uid);
        const user = (await tx.get(ref)).data();
        if (!user || isClosed(user)) return null;
        const day = utcDay(deps.now());
        const count = (user.promptRejections?.day === day ? user.promptRejections.count : 0) + 1;
        tx.update(ref, { promptRejections: { day, count } });
        return count;
      });
    } catch {
      // The refusal itself must still reach the caller; the log line below shows the count is missing.
    }
  }
  deps.logger.warn("prompt_input_rejected", {
    uidHash: uidHash(uid),
    field: details.field.startsWith("allergens") ? "allergen" : "ingredient",
    rule: details.rule,
    suspicious,
    rejectionsToday,
  });
}

/**
 * One structured line per LLM call. Never ingredient names, prompts or outputs; the uid is hashed.
 * `taste` is whether the request carried a taste profile (never which ids), and `staples` how many
 * of the person's staples the prompt held (usableStaples; never their names); each is null when
 * the line is written from a stored generation (sweeper, deletion), which does not keep it.
 */
export function logLlmCall(
  deps: Deps,
  uid: string,
  gen: Pick<GenerationDoc, "kind" | "courses" | "days" | "cost" | "reservedMicroUsd"> & { imageLevel?: string; taste?: boolean; staples?: number },
  outcome: Outcome,
  settled: Settled,
  durationMs: number | null,
): void {
  const u = outcome.usage;
  deps.logger.info("llm_call", {
    uidHash: uidHash(uid),
    kind: gen.kind,
    courses: gen.courses ?? null,
    days: gen.kind === "menu" ? (gen.days ?? 3) : null,
    taste: gen.taste ?? null,
    staples: gen.staples ?? null,
    // Whether a picture was asked, and what came of it (the image_call line has the rest).
    image: gen.imageLevel ?? null,
    imageOutcome: outcome.ok && gen.imageLevel && outcome.picture ? (outcome.picture.dish.ok ? "done" : outcome.picture.dish.failure) : null,
    stepImages: outcome.ok && gen.imageLevel === "steps" && outcome.picture
      ? `${outcome.picture.steps.filter((s) => s.outcome.ok).length}/${outcome.picture.steps.length}`
      : null,
    model: u?.model ?? null,
    // Only when another text provider than the deploy default answered, or a fallback was asked.
    ...(outcome.text ? { provider: outcome.text.provider, textFallback: outcome.text.fallback } : {}),
    inputTokens: u?.inputTokens ?? null,
    outputTokens: u?.outputTokens ?? null,
    thinkingTokens: u?.thinkingTokens ?? null,
    finishReason: u?.finishReason ?? null,
    durationMs,
    outcome: outcome.ok ? "done" : outcome.failureClass,
    reason: outcome.ok ? null : outcome.reason.slice(0, 80),
    cost: gen.cost,
    charged: settled.settledHere ? settled.response.charged : null,
    refunded: settled.settledHere ? !settled.response.charged : null,
    settledHere: settled.settledHere,
    reservedMicroUsd: gen.reservedMicroUsd ?? null,
    estimatedMicroUsd: settled.settledHere ? settled.actualMicroUsd : null,
  });
  if (!outcome.ok && outcome.rejection) {
    deps.logger.warn("prompt_output_rejected", {
      uidHash: uidHash(uid),
      kind: gen.kind,
      code: outcome.rejection,
      charged: settled.settledHere ? settled.response.charged : null,
      rejectionsToday: settled.rejectionsToday ?? null,
    });
  }
}

export async function generateRecipe(deps: Deps, caller: Caller, data: unknown): Promise<GenerateResponse> {
  // Read first (cached in production): the taste quiz's hidden list is part of reading `taste`.
  const config = await deps.config();
  let req: GenerateRequest;
  try {
    req = validateGenerateRequest(data, deps.now(), config.tasteQuiz.hidden, config.image.maxStepImages);
  } catch (e) {
    if (e instanceof HttpsError && isNameRejection(e.details)) {
      await recordInputRejection(deps, caller.uid, e.details, isSuspiciousRejection(e.details));
    }
    throw e;
  }
  // A request the quality gate would refuse whatever the model wrote (no item it may use, or a
  // menu day with no item it may be planned on) is refused here, before anything is reserved.
  if (!hasEnoughItems(req)) {
    deps.logger.info("generation_not_enough_items", { uidHash: uidHash(caller.uid), kind: req.kind, courses: req.courses ?? null, days: req.kind === "menu" ? menuDaysOf(req) : null, items: req.ingredients.length });
    fail("failed-precondition", "NOT_ENOUGH_ITEMS", "The ticked items cannot make this request");
  }
  const plan = planFor(req, config);
  const reservation = await reserve(deps, caller.uid, req, config, plan);
  if (!reservation.reserved) {
    const response = await withImage(deps, caller.uid, req.requestId, reservation.generation, reservation.response);
    return { ...response, ...capabilitiesOf(config), ...(await blockFlag(deps.db, reservation.user, config)), ...(await welcomeFlag(deps, config)) };
  }
  const started = deps.now();
  let outcome = await runGenerator(deps, caller.uid, req, plan.limits, config);
  const durationMs = deps.now() - started;
  if (outcome.ok && req.image) {
    // The text passed every check: now its picture, from the checked brief only.
    if (outcome.imageBrief === undefined) {
      deps.logger.warn("image_brief_rejected", { uidHash: uidHash(caller.uid), rule: outcome.briefRule ?? "BRIEF_MISSING" });
    }
    for (const rule of outcome.stepBriefsRefused ?? []) {
      deps.logger.warn("image_brief_rejected", { uidHash: uidHash(caller.uid), rule, step: true });
    }
    // A generation the sweeper settled meanwhile gets no picture: it would be paid for and discarded.
    const stillPending = (await generationRef(deps.db, caller.uid, req.requestId).get()).data()?.state === "pending";
    const settledAway: ImageOutcome = { ok: false, failure: "SETTLED", microUsd: 0, model: null };
    // No dish picture, no step pictures: without a dish brief none is asked at all. The dish's
    // picture first, within IMAGE.budgetMs.dish; only when it was made, the step pictures, at most
    // IMAGE.maxParallel at once, within IMAGE.budgetMs.steps from the same start (constants.ts IMAGE
    // has the arithmetic). When the dish's fails, no step picture is asked (NO_DISH, nothing spent).
    const withDish = stillPending && outcome.imageBrief !== undefined;
    const stepBriefs = withDish ? (outcome.stepBriefs ?? []) : [];
    const picturesFrom = deps.now();
    const dish = !stillPending
      ? settledAway
      : outcome.imageBrief !== undefined
        ? await makeRecipeImage(deps, caller.uid, outcome.imageBrief, config, picturesFrom + IMAGE.budgetMs.dish)
        : briefRefused(outcome.briefRule ?? "MISSING");
    const noDish: ImageOutcome = { ok: false, failure: "NO_DISH", microUsd: 0, model: null };
    const steps = dish.ok && stepBriefs.length > 0
      ? await makeStepImages(deps, caller.uid, stepBriefs, config, picturesFrom + IMAGE.budgetMs.steps)
      : stepBriefs.map(() => noDish);
    outcome = { ...outcome, picture: { dish, steps: stepBriefs.map((s, i) => ({ step: s.step, outcome: steps[i] })) } };
  }
  const settled = await settle(deps, caller.uid, req.requestId, outcome, config);
  logLlmCall(
    deps,
    caller.uid,
    { kind: req.kind, courses: req.courses, days: req.days, cost: plan.cost, reservedMicroUsd: plan.reservedMicroUsd, taste: req.taste !== undefined, staples: usableStaples(req).length, ...(req.image ? { imageLevel: req.image } : {}) },
    outcome,
    settled,
    durationMs,
  );
  // The pictures just made are sent from memory; a generation settled elsewhere is read back.
  const response = settled.settledHere && outcome.ok && outcome.picture
    ? withPictures(settled.response, settled.generation, outcome.picture)
    : await withImage(deps, caller.uid, req.requestId, settled.generation, settled.response);
  return { ...response, ...capabilitiesOf(config), ...(await blockFlag(deps.db, settled.user, config)), ...(await welcomeFlag(deps, config)) };
}

function wireImage(image: { data: Buffer; width: number; height: number }): RecipeImageWire {
  return { mimeType: "image/jpeg", data: Buffer.from(image.data).toString("base64"), width: image.width, height: image.height };
}

/** A response with the pictures made in this call (only on a generation settled here as DONE). */
function withPictures(response: GenerateResponse, gen: GenerationDoc, picture: PictureOutcome): GenerateResponse {
  if (gen.state !== "done") return response;
  const steps = picture.steps.flatMap((s) => (s.outcome.ok ? [{ step: s.step, image: wireImage(s.outcome.image) }] : []));
  return {
    ...response,
    ...(picture.dish.ok ? { image: wireImage(picture.dish.image) } : {}),
    ...(gen.imageLevel === "steps" && picture.steps.length > 0 ? { stepImages: steps } : {}),
  };
}

/**
 * A DONE generation's response with its stored pictures (recipeImages/main and step-<i>), for a
 * retry with the same requestId and for getGeneration. A picture whose document is gone
 * (expired) is left out.
 */
async function withImage(deps: Deps, uid: string, requestId: string, gen: GenerationDoc, response: GenerateResponse): Promise<GenerateResponse> {
  if (gen.state !== "done" || !gen.imageLevel) return response;
  const stepIndices = gen.stepImagesStored ?? [];
  const refs = [...(gen.imageStored ? [generationImageRef(deps.db, uid, requestId)] : []), ...stepIndices.map((i) => generationImageRef(deps.db, uid, requestId, i))];
  const snaps = refs.length ? await deps.db.getAll(...refs) : [];
  const dish = gen.imageStored ? snaps[0]?.data() : undefined;
  const stepSnaps = gen.imageStored ? snaps.slice(1) : snaps;
  const steps = stepIndices.flatMap((step, i) => {
    const d = stepSnaps[i]?.data() as GenerationImageDoc | undefined;
    return d ? [{ step, image: wireImage(d) }] : [];
  });
  return {
    ...response,
    ...(dish ? { image: wireImage(dish as GenerationImageDoc) } : {}),
    ...(gen.imageLevel === "steps" && (gen.stepImagesMarked ?? 0) > 0 ? { stepImages: steps } : {}),
  };
}

/**
 * The polling callable: the state of one of the caller's generations, in generateRecipe's response
 * shape. Never reserves, never calls the LLM, never reads the shared stats. An unknown (or expired
 * and deleted) id is `not-found` / UNKNOWN_REQUEST: the client re-sends generateRecipe with the same
 * id and inputs.
 */
export async function getGeneration(deps: Deps, caller: Caller, data: unknown): Promise<GenerateResponse> {
  const requestId = validateRequestId((data as { requestId?: unknown } | null)?.requestId);
  const [uSnap, gSnap, config] = await Promise.all([
    userRef(deps.db, caller.uid).get(),
    generationRef(deps.db, caller.uid, requestId).get(),
    deps.config(),
  ]);
  const gen = gSnap.data();
  if (!gen) fail("not-found", "UNKNOWN_REQUEST", "No generation with this requestId");
  const user = uSnap.data();
  const response = await withImage(deps, caller.uid, requestId, gen, fromDoc(gen, isClosed(user) ? 0 : (user?.balance ?? 0), config.costs));
  return { ...response, ...capabilitiesOf(config), ...(await blockFlag(deps.db, user, config)), ...(await welcomeFlag(deps, config)) };
}
