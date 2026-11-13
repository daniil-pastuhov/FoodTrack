/**
 * generateRecipe: reserve the credits, call the LLM, then settle (keep the charge on success,
 * refund on failure). Reserve and settle are separate transactions; settle is conditioned on the
 * generation still being pending, so of a live call and the sweeper only one settles it.
 */
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import {
  fail,
  parseMenu,
  parseRecipe,
  validateGenerateRequest,
  type GenerateRequest,
  type GenerateResponse,
  type Menu,
  type Recipe,
} from "../contract";
import { CONTENT_REFUNDS_PER_DAY, COST, GENERATION_TTL_MS, TIMINGS } from "../constants";
import {
  assertOpen,
  generationRef,
  isClosed,
  loadRuntimeConfig,
  statsRef,
  userRef,
  utcDay,
  type GenerationDoc,
} from "../model";
import { GenerationError, type Caller, type Deps, type FailureClass } from "../ports";

function fromDoc(doc: GenerationDoc, balance: number): GenerateResponse {
  switch (doc.state) {
    case "done":
      return {
        status: "DONE",
        ...(doc.recipe ? { recipe: doc.recipe } : {}),
        ...(doc.menu ? { menu: doc.menu } : {}),
        balance,
        charged: true,
      };
    case "pending":
      return { status: "IN_PROGRESS", balance, charged: true };
    case "failed":
      return { status: "FAILED", balance, charged: doc.charged === true };
  }
}

type Reservation = { reserved: true; balance: number } | { reserved: false; response: GenerateResponse };

async function reserve(deps: Deps, uid: string, req: GenerateRequest): Promise<Reservation> {
  const config = await loadRuntimeConfig(deps.db);
  const cost = COST[req.kind];
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
    // A retry with the same requestId never charges again and never calls the LLM again.
    if (existing) return { reserved: false, response: fromDoc(existing, user?.balance ?? 0) };

    if (!config.generationEnabled) fail("unavailable", "GENERATION_DISABLED", "Recipe generation is paused");
    if (!user || user.balance < cost) fail("failed-precondition", "INSUFFICIENT_CREDITS", "Not enough recipes left");
    if (user.inFlight > 0) fail("failed-precondition", "BUSY", "Another recipe is being generated");

    const minute = Math.floor(now / 60_000);
    const mw = user.minuteWindow?.start === minute ? user.minuteWindow : { start: minute, count: 0 };
    const dw = user.dayWindow?.day === day ? user.dayWindow : { day, count: 0 };
    if (mw.count >= config.perUserPerMinute || dw.count >= config.perUserPerDay) {
      fail("resource-exhausted", "RATE_LIMITED", "Too many recipes in a short time");
    }
    const units = (sSnap.data()?.generationUnits as number | undefined) ?? 0;
    if (units + cost > config.dailyCap) fail("resource-exhausted", "DAILY_CAP", "Daily generation limit reached");

    tx.update(uRef, {
      balance: FieldValue.increment(-cost),
      inFlight: FieldValue.increment(1),
      minuteWindow: { start: minute, count: mw.count + 1 },
      dayWindow: { day, count: dw.count + 1 },
    });
    tx.set(sRef, { generationUnits: FieldValue.increment(cost), generations: FieldValue.increment(1) }, { merge: true });
    const doc: GenerationDoc = {
      state: "pending",
      kind: req.kind,
      ...(req.courses ? { courses: req.courses } : {}),
      cost,
      createdAt: Timestamp.fromMillis(now),
      deadlineAt: Timestamp.fromMillis(now + TIMINGS[req.kind].reservationMs),
    };
    tx.create(gRef, doc);
    return { reserved: true, balance: user.balance - cost };
  });
}

export type Outcome =
  | { ok: true; recipe?: Recipe; menu?: Menu }
  | { ok: false; failureClass: FailureClass; reason: string };

/**
 * Settles a pending generation. Returns the final response; if the generation was no longer
 * pending (the sweeper or another settle got there first), the given outcome is discarded.
 */
export async function settle(deps: Deps, uid: string, requestId: string, outcome: Outcome): Promise<GenerateResponse> {
  return deps.db.runTransaction(async (tx) => {
    const now = deps.now();
    const day = utcDay(now);
    const uRef = userRef(deps.db, uid);
    const gRef = generationRef(deps.db, uid, requestId);
    const [uSnap, gSnap] = await Promise.all([tx.get(uRef), tx.get(gRef)]);
    const user = uSnap.data();
    const gen = gSnap.data();
    if (!gen) throw new Error(`generation ${requestId} vanished`);
    if (gen.state !== "pending") return fromDoc(gen, user?.balance ?? 0);

    const expiresAt = Timestamp.fromMillis(now + GENERATION_TTL_MS);
    const closed = !user || isClosed(user);
    const userUpdate: Record<string, unknown> = { inFlight: FieldValue.increment(-1) };
    let balance = user?.balance ?? 0;
    let finalDoc: GenerationDoc;

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
      let refund = true;
      if (outcome.failureClass === "content" && user) {
        const cr = user.contentRefunds?.day === day ? user.contentRefunds : { day, count: 0 };
        refund = cr.count < CONTENT_REFUNDS_PER_DAY;
        if (refund) userUpdate.contentRefunds = { day, count: cr.count + 1 };
      }
      if (refund) {
        userUpdate.balance = FieldValue.increment(gen.cost);
        balance += gen.cost;
      } else {
        userUpdate.consumedCredits = FieldValue.increment(gen.cost);
      }
      finalDoc = {
        ...gen,
        state: "failed",
        charged: !refund,
        failureClass: outcome.failureClass,
        failureReason: outcome.reason.slice(0, 200),
        expiresAt,
      };
    }
    // A deleted account's balance is gone; only the generation doc is settled.
    if (user && !closed) tx.update(uRef, userUpdate);
    else if (user) tx.update(uRef, { inFlight: FieldValue.increment(-1) });
    tx.set(gRef, finalDoc);
    return fromDoc(finalDoc, closed ? 0 : balance);
  });
}

async function runGenerator(deps: Deps, req: GenerateRequest): Promise<Outcome> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMINGS[req.kind].llmAbortMs);
  try {
    const raw = await deps.generator.generate(req, controller.signal);
    if (controller.signal.aborted) return { ok: false, failureClass: "infra", reason: "timeout" };
    if (req.kind === "menu") {
      const menu = parseMenu(raw, req.today, req.courses!);
      return menu ? { ok: true, menu } : { ok: false, failureClass: "content", reason: "invalid menu output" };
    }
    const recipe = parseRecipe(raw);
    return recipe ? { ok: true, recipe } : { ok: false, failureClass: "content", reason: "invalid recipe output" };
  } catch (e) {
    if (controller.signal.aborted) return { ok: false, failureClass: "infra", reason: "timeout" };
    if (e instanceof GenerationError) return { ok: false, failureClass: e.failureClass, reason: e.message };
    deps.logger.error("Generator threw unexpectedly", { error: String(e) });
    return { ok: false, failureClass: "infra", reason: "generator error" };
  } finally {
    clearTimeout(timer);
  }
}

export async function generateRecipe(deps: Deps, caller: Caller, data: unknown): Promise<GenerateResponse> {
  const req = validateGenerateRequest(data, deps.now());
  const reservation = await reserve(deps, caller.uid, req);
  if (!reservation.reserved) return reservation.response;
  const outcome = await runGenerator(deps, req);
  if (!outcome.ok) deps.logger.warn("Generation failed", { uid: caller.uid, kind: req.kind, ...outcome });
  return settle(deps, caller.uid, req.requestId, outcome);
}

