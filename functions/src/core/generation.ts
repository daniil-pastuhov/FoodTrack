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
  CAPABILITIES,
  fail,
  inputHashOf,
  isNameRejection,
  isSuspiciousRejection,
  validateGenerateRequest,
  validateRequestId,
  type GenerateRequest,
  type GenerateResponse,
  type Menu,
  type Recipe,
} from "../contract";
import {
  CREDITS_PER_CONTENT_REFUND,
  CREDITS_PER_TIMEOUT_REFUND,
  GENERATION_TTL_MS,
  JUDGE,
  MAX_OUTPUT_TOKENS,
  REVENUE_WINDOW_DAYS,
  THINKING_FACTOR,
  TIMINGS,
  type CostKey,
  type Costs,
} from "../constants";
import { buildPrompt, inputTokenBound, newCanary } from "../adapters/prompt";
import { JUDGE_INSTRUCTION } from "../adapters/gemini";
import { SUSPICIOUS_OUTPUT_RULES, checkOutput } from "./outputSafety";
import {
  assertOpen,
  costKeyOf,
  dailySpendCeiling,
  generationRef,
  isClosed,
  microUsd,
  paidAllowance,
  statsRef,
  uidHash,
  userRef,
  utcDay,
  type GenerationDoc,
  type RuntimeConfig,
  type UserDoc,
} from "../model";
import { blockFlag } from "./buyers";
import {
  GenerationError,
  type Caller,
  type Deps,
  type FailureClass,
  type GenerationLimits,
  type LlmUsage,
} from "../ports";

function fromDoc(doc: GenerationDoc, balance: number, costs: Costs): GenerateResponse {
  switch (doc.state) {
    case "done":
      return {
        status: "DONE",
        ...(doc.recipe ? { recipe: doc.recipe } : {}),
        ...(doc.menu ? { menu: doc.menu } : {}),
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
  cost: number;
  limits: GenerationLimits;
  reservedMicroUsd: number;
  inputHash: string;
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
  const costKey = costKeyOf(req.kind, req.courses);
  const maxOutputTokens = MAX_OUTPUT_TOKENS[costKey];
  const hardTokenCeiling = THINKING_FACTOR * maxOutputTokens;
  const judge = config.outputJudge === "off" ? 0 : judgeWorstCaseMicroUsd(config, maxOutputTokens);
  return {
    costKey,
    cost: config.costs[costKey],
    limits: { maxOutputTokens, hardTokenCeiling },
    reservedMicroUsd: microUsd(config.prices, inputTokenBound(req), hardTokenCeiling) + judge,
    inputHash: inputHashOf(req),
  };
}

const sameInputs = (doc: GenerationDoc, req: GenerateRequest, plan: Plan) =>
  doc.inputHash !== undefined
    ? doc.inputHash === plan.inputHash
    : doc.kind === req.kind && (doc.courses ?? null) === (req.courses ?? null);

type Reservation =
  | { reserved: true; balance: number }
  | { reserved: false; response: GenerateResponse; user: UserDoc | undefined };

/**
 * The per-user and existing-generation checks read only the caller's own documents; the shared
 * stats docs (today's, and the trailing days for the revenue-aware ceiling) are read only when a new
 * reservation is about to be made, so refused calls never contend on them.
 */
async function reserve(deps: Deps, uid: string, req: GenerateRequest, config: RuntimeConfig, plan: Plan): Promise<Reservation> {
  const { cost, reservedMicroUsd } = plan;
  return deps.db.runTransaction(async (tx): Promise<Reservation> => {
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
      return { reserved: false, response: fromDoc(existing, user?.balance ?? 0, config.costs), user };
    }

    if (!config.generationEnabled) fail("unavailable", "GENERATION_DISABLED", "Recipe generation is paused");
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
      fail("resource-exhausted", "INFRA_FAILURE_LIMIT", "Generation failed too often today; try again tomorrow");
    }
    const userSpend = user.spendWindow?.day === day ? user.spendWindow.microUsd : 0;
    if (dw.count >= config.perUserPerDay || userSpend + reservedMicroUsd > config.perUserDailySpendMicroUsd) {
      fail("resource-exhausted", "USER_DAILY_LIMIT", "Daily limit for this account reached");
    }
    const pastRefs = Array.from({ length: REVENUE_WINDOW_DAYS - 1 }, (_, i) => statsRef(deps.db, utcDay(now - (i + 1) * 86_400_000)));
    const [sSnap, ...pastSnaps] = await tx.getAll(sRef, ...pastRefs);
    const stats = sSnap.data() ?? {};
    const units = (stats.generationUnits as number | undefined) ?? 0;
    if (units + cost > config.dailyCap) fail("resource-exhausted", "DAILY_CAP", "Daily generation limit reached");
    // The real-time spend breaker: today's estimated spend plus every in-flight worst case, against
    // the revenue-aware ceiling. The worst case is reserved here, inside the transaction, so
    // parallel calls cannot overshoot.
    const netCredits7d = [sSnap, ...pastSnaps].reduce((sum, s) => sum + ((s.data()?.netCreditsGranted as number | undefined) ?? 0), 0);
    const spent = ((stats.spentMicroUsd as number | undefined) ?? 0) + ((stats.reservedMicroUsd as number | undefined) ?? 0);
    if (spent + reservedMicroUsd > dailySpendCeiling(config, netCredits7d)) {
      fail("unavailable", "GENERATION_DISABLED", "Recipe generation is paused");
    }

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
      },
      { merge: true },
    );
    const doc: GenerationDoc = {
      state: "pending",
      kind: req.kind,
      ...(req.courses ? { courses: req.courses } : {}),
      cost,
      inputHash: plan.inputHash,
      reservedMicroUsd,
      statsDay: day,
      createdAt: Timestamp.fromMillis(now),
      deadlineAt: Timestamp.fromMillis(now + TIMINGS[req.kind].reservationMs),
    };
    tx.create(gRef, doc);
    return { reserved: true, balance: user.balance - cost };
  });
}

export type Outcome =
  | { ok: true; recipe?: Recipe; menu?: Menu; usage?: LlmUsage }
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
    };

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
    const actual = usage
      ? microUsd(config.prices, usage.inputTokens, usage.outputTokens + usage.thinkingTokens)
      : reserved;
    const statsDay = gen.statsDay ?? utcDay(gen.createdAt.toMillis());

    const expiresAt = Timestamp.fromMillis(now + GENERATION_TTL_MS);
    const closed = !user || isClosed(user);
    const userUpdate: Record<string, unknown> = { inFlight: FieldValue.increment(-1) };
    let balance = user?.balance ?? 0;
    let finalDoc: GenerationDoc;
    let refunded = false;
    let rejectionsToday: number | null = null;

    if (outcome.ok) {
      userUpdate.consumedCredits = FieldValue.increment(gen.cost);
      finalDoc = {
        ...gen,
        state: "done",
        charged: true,
        expiresAt,
        ...(outcome.recipe ? { recipe: outcome.recipe } : {}),
        ...(outcome.menu ? { menu: outcome.menu } : {}),
      };
    } else {
      if (outcome.failureClass === "content") {
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
      if (refunded && outcome.failureClass === "infra" && !outcome.swept) {
        const count = user?.infraRefunds?.day === today ? user.infraRefunds.count : 0;
        userUpdate.infraRefunds = { day: today, count: count + 1 };
      }
      if (refunded) {
        userUpdate.balance = FieldValue.increment(gen.cost);
        balance += gen.cost;
      } else {
        userUpdate.consumedCredits = FieldValue.increment(gen.cost);
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
      [outcome.ok ? "succeeded" : outcome.failureClass === "content" ? "contentFailures" : "infraFailures"]:
        FieldValue.increment(1),
    };
    if (refunded) statsUpdate.refundedCredits = FieldValue.increment(gen.cost);
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

async function runGenerator(deps: Deps, uid: string, req: GenerateRequest, limits: GenerationLimits, config: RuntimeConfig): Promise<Outcome> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMINGS[req.kind].llmAbortMs);
  const canary = newCanary();
  const prompt = buildPrompt(req, canary);
  let checked: { recipe?: Recipe; menu?: Menu };
  let usage: LlmUsage | undefined;
  try {
    const result = await deps.generator.generate(req, prompt, controller.signal, limits);
    usage = result.usage;
    // The answer arrived too late to use, but its usage is known and is what gets booked.
    if (controller.signal.aborted) return { ok: false, failureClass: "infra", reason: "timeout", usage };
    const check = checkOutput(result.output, { req, canary, systemInstruction: prompt.systemInstruction });
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
  return runJudge(deps, uid, req, config, checked, usage);
}

/**
 * The second check (config/runtime `outputJudge`). "enforce": a verdict other than ok is a content
 * failure and a prompt rejection; a judge that fails is an infrastructure failure (the answer is
 * never delivered unchecked). "shadow": the verdict is only logged and the answer is delivered.
 */
async function runJudge(
  deps: Deps,
  uid: string,
  req: GenerateRequest,
  config: RuntimeConfig,
  checked: { recipe?: Recipe; menu?: Menu },
  genUsage: LlmUsage | undefined,
): Promise<Outcome> {
  const enforce = config.outputJudge === "enforce";
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), JUDGE.abortMs[req.kind]);
  try {
    const { verdict, usage } = await deps.judge.judge(checked, controller.signal);
    const total = addUsage(genUsage, usage);
    deps.logger.info("prompt_judge_verdict", { uidHash: uidHash(uid), kind: req.kind, verdict, mode: config.outputJudge });
    if (verdict !== "ok" && enforce) {
      return { ok: false, failureClass: "content", reason: `judge ${verdict}`, usage: total, rejection: `JUDGE_${verdict.toUpperCase()}` };
    }
    return { ok: true, ...checked, usage: total };
  } catch (e) {
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

/** One structured line per LLM call. Never ingredient names, prompts or outputs; the uid is hashed. */
export function logLlmCall(
  deps: Deps,
  uid: string,
  gen: Pick<GenerationDoc, "kind" | "courses" | "cost" | "reservedMicroUsd">,
  outcome: Outcome,
  settled: Settled,
  durationMs: number | null,
): void {
  const u = outcome.usage;
  deps.logger.info("llm_call", {
    uidHash: uidHash(uid),
    kind: gen.kind,
    courses: gen.courses ?? null,
    model: u?.model ?? null,
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
  let req: GenerateRequest;
  try {
    req = validateGenerateRequest(data, deps.now());
  } catch (e) {
    if (e instanceof HttpsError && isNameRejection(e.details)) {
      await recordInputRejection(deps, caller.uid, e.details, isSuspiciousRejection(e.details));
    }
    throw e;
  }
  const config = await deps.config();
  const plan = planFor(req, config);
  const reservation = await reserve(deps, caller.uid, req, config, plan);
  if (!reservation.reserved) return { ...reservation.response, ...CAPABILITIES, ...(await blockFlag(deps.db, reservation.user, config)) };
  const started = deps.now();
  const outcome = await runGenerator(deps, caller.uid, req, plan.limits, config);
  const durationMs = deps.now() - started;
  const settled = await settle(deps, caller.uid, req.requestId, outcome, config);
  logLlmCall(
    deps,
    caller.uid,
    { kind: req.kind, courses: req.courses, cost: plan.cost, reservedMicroUsd: plan.reservedMicroUsd },
    outcome,
    settled,
    durationMs,
  );
  return { ...settled.response, ...CAPABILITIES, ...(await blockFlag(deps.db, settled.user, config)) };
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
  return { ...fromDoc(gen, isClosed(user) ? 0 : (user?.balance ?? 0), config.costs), ...CAPABILITIES, ...(await blockFlag(deps.db, user, config)) };
}
