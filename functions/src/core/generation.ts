/**
 * generateRecipe: reserve the credits and the worst-case LLM cost, call the LLM, then settle (keep
 * the charge on success, refund on failure, replace the worst-case cost by the measured one).
 * Reserve and settle are separate transactions; settle is conditioned on the generation still being
 * pending, so of a live call and the sweeper only one settles it. Nothing reaches the LLM without a
 * committed reservation.
 */
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import {
  fail,
  inputHashOf,
  parseMenu,
  parseRecipe,
  validateGenerateRequest,
  type GenerateRequest,
  type GenerateResponse,
  type Menu,
  type Recipe,
} from "../contract";
import {
  CREDITS_PER_CONTENT_REFUND,
  GENERATION_TTL_MS,
  MAX_OUTPUT_TOKENS,
  THINKING_FACTOR,
  TIMINGS,
  type CostKey,
  type Costs,
} from "../constants";
import { inputTokenBound } from "../adapters/prompt";
import {
  assertOpen,
  contentRefundAllowance,
  costKeyOf,
  generationRef,
  isClosed,
  loadRuntimeConfig,
  microUsd,
  statsRef,
  uidHash,
  userRef,
  utcDay,
  type GenerationDoc,
  type RuntimeConfig,
} from "../model";
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

export function planFor(req: GenerateRequest, config: RuntimeConfig): Plan {
  const costKey = costKeyOf(req.kind, req.courses);
  const maxOutputTokens = MAX_OUTPUT_TOKENS[costKey];
  const hardTokenCeiling = THINKING_FACTOR * maxOutputTokens;
  return {
    costKey,
    cost: config.costs[costKey],
    limits: { maxOutputTokens, hardTokenCeiling },
    reservedMicroUsd: microUsd(config.prices, inputTokenBound(req), hardTokenCeiling),
    inputHash: inputHashOf(req),
  };
}

const sameInputs = (doc: GenerationDoc, req: GenerateRequest, plan: Plan) =>
  doc.inputHash !== undefined
    ? doc.inputHash === plan.inputHash
    : doc.kind === req.kind && (doc.courses ?? null) === (req.courses ?? null);

type Reservation = { reserved: true; balance: number } | { reserved: false; response: GenerateResponse };

async function reserve(deps: Deps, uid: string, req: GenerateRequest, config: RuntimeConfig, plan: Plan): Promise<Reservation> {
  const { cost, reservedMicroUsd } = plan;
  return deps.db.runTransaction(async (tx) => {
    const now = deps.now();
    const day = utcDay(now);
    const uRef = userRef(deps.db, uid);
    const gRef = generationRef(deps.db, uid, req.requestId);
    const sRef = statsRef(deps.db, day);
    const [uSnap, gSnap, sSnap] = await Promise.all([tx.get(uRef), tx.get(gRef), tx.get(sRef)]);
    const user = uSnap.data();
    assertOpen(user);
    const existing = gSnap.data();
    if (existing) {
      // A retry with the same requestId never charges again and never calls the LLM again, and
      // only ever returns what was generated for these exact inputs.
      if (!sameInputs(existing, req, plan)) {
        fail("failed-precondition", "REQUEST_ID_REUSED", "This requestId was used for a different request");
      }
      return { reserved: false, response: fromDoc(existing, user?.balance ?? 0, config.costs) };
    }

    if (!config.generationEnabled) fail("unavailable", "GENERATION_DISABLED", "Recipe generation is paused");
    if (!user || user.balance < cost) fail("failed-precondition", "INSUFFICIENT_CREDITS", "Not enough recipes left");
    if (user.inFlight > 0) fail("failed-precondition", "BUSY", "Another recipe is being generated");

    const minute = Math.floor(now / 60_000);
    const mw = user.minuteWindow?.start === minute ? user.minuteWindow : { start: minute, count: 0 };
    const dw = user.dayWindow?.day === day ? user.dayWindow : { day, count: 0 };
    if (mw.count >= config.perUserPerMinute) fail("resource-exhausted", "RATE_LIMITED", "Too many recipes in a short time");
    const infra = user.infraRefunds?.day === day ? user.infraRefunds.count : 0;
    if (infra >= config.infraRefundsPerDay) {
      fail("resource-exhausted", "INFRA_FAILURE_LIMIT", "Generation failed too often today; try again tomorrow");
    }
    const userSpend = user.spendWindow?.day === day ? user.spendWindow.microUsd : 0;
    if (dw.count >= config.perUserPerDay || userSpend + reservedMicroUsd > config.perUserDailySpendMicroUsd) {
      fail("resource-exhausted", "USER_DAILY_LIMIT", "Daily limit for this account reached");
    }
    const stats = sSnap.data() ?? {};
    const units = (stats.generationUnits as number | undefined) ?? 0;
    if (units + cost > config.dailyCap) fail("resource-exhausted", "DAILY_CAP", "Daily generation limit reached");
    // The real-time spend breaker: today's estimated spend plus every in-flight worst case. The
    // worst case is reserved here, inside the transaction, so parallel calls cannot overshoot.
    const spent = ((stats.spentMicroUsd as number | undefined) ?? 0) + ((stats.reservedMicroUsd as number | undefined) ?? 0);
    if (spent + reservedMicroUsd > config.dailySpendCapMicroUsd) {
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
  | { ok: false; failureClass: FailureClass; reason: string; usage?: LlmUsage };

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
        refunded = !!user && used < contentRefundAllowance(user, CREDITS_PER_CONTENT_REFUND);
        if (refunded) userUpdate.contentRefundsUsed = used + 1;
      } else {
        // Infrastructure failures are always refunded; reserve refuses once the daily ceiling is hit.
        refunded = true;
        const count = user?.infraRefunds?.day === today ? user.infraRefunds.count : 0;
        userUpdate.infraRefunds = { day: today, count: count + 1 };
      }
      if (refunded) {
        userUpdate.balance = FieldValue.increment(gen.cost);
        balance += gen.cost;
      } else {
        userUpdate.consumedCredits = FieldValue.increment(gen.cost);
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
    };
  }, { maxAttempts: SETTLE_ATTEMPTS });
}

async function runGenerator(deps: Deps, req: GenerateRequest, limits: GenerationLimits): Promise<Outcome> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMINGS[req.kind].llmAbortMs);
  try {
    const { output, usage } = await deps.generator.generate(req, controller.signal, limits);
    if (controller.signal.aborted) return { ok: false, failureClass: "infra", reason: "timeout" };
    if (req.kind === "menu") {
      const menu = parseMenu(output, req.today, req.courses!);
      return menu ? { ok: true, menu, usage } : { ok: false, failureClass: "content", reason: "invalid menu output", usage };
    }
    const recipe = parseRecipe(output);
    return recipe ? { ok: true, recipe, usage } : { ok: false, failureClass: "content", reason: "invalid recipe output", usage };
  } catch (e) {
    const usage = e instanceof GenerationError ? e.usage : undefined;
    if (controller.signal.aborted) return { ok: false, failureClass: "infra", reason: "timeout", usage };
    if (e instanceof GenerationError) return { ok: false, failureClass: e.failureClass, reason: e.message, usage };
    deps.logger.error("Generator threw unexpectedly", { error: String(e).slice(0, 200) });
    return { ok: false, failureClass: "infra", reason: "generator error" };
  } finally {
    clearTimeout(timer);
  }
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
}

export async function generateRecipe(deps: Deps, caller: Caller, data: unknown): Promise<GenerateResponse> {
  const req = validateGenerateRequest(data, deps.now());
  const config = await loadRuntimeConfig(deps.db, deps.logger);
  const plan = planFor(req, config);
  const reservation = await reserve(deps, caller.uid, req, config, plan);
  if (!reservation.reserved) return reservation.response;
  const started = deps.now();
  const outcome = await runGenerator(deps, req, plan.limits);
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
  return settled.response;
}
