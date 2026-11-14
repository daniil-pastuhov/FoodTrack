/** Spend accounting and abuse guards: stats, the spend breakers, requestId binding, usage logs, merge carry-over. */
import { randomUUID } from "node:crypto";
import { HttpsError } from "firebase-functions/v2/https";
import { deleteAccount, mergeBalance } from "../src/core/account";
import { generateRecipe, planFor } from "../src/core/generation";
import { sweep } from "../src/core/sweeper";
import { validateGenerateRequest } from "../src/contract";
import { emptyUser, parseRuntimeConfig, statsRef, utcDay } from "../src/model";
import { GenerationError } from "../src/ports";
import {
  anonymousUser,
  callerFor,
  db,
  fakeUsage,
  makeDeps,
  passwordUser,
  resetEmulators,
  sampleRecipe,
  setConfig,
  type TestDeps,
} from "./helpers";

const UID = "user-spend";
const caller = callerFor(UID);

function request(over: Record<string, unknown> = {}) {
  return {
    requestId: randomUUID(),
    kind: "recipe",
    config: {
      difficulty: "easy", detail: "standard", servings: 2, maxMinutes: 30,
      mealType: "dinner", diet: "none", language: "en",
    },
    ingredients: [{ name: "Secretingredient", expiresOn: "2030-01-01", quantity: 1 }],
    allergens: ["peanut"],
    today: new Date().toISOString().slice(0, 10),
    ...over,
  };
}

async function seed(uid: string, balance: number, extra: Record<string, unknown> = {}) {
  await db.collection("users").doc(uid).set({ ...emptyUser(), balance, purchasedCredits: balance, ...extra });
}

async function reason(p: Promise<unknown>): Promise<string> {
  try {
    await p;
  } catch (e) {
    if (e instanceof HttpsError) return (e.details as { reason: string }).reason;
    throw e;
  }
  return "no error";
}

const user = async (uid = UID) => (await db.collection("users").doc(uid).get()).data()!;
const stats = async (d: TestDeps) => (await statsRef(db, utcDay(d.now())).get()).data() ?? {};
/** The worst case the server reserves for `request()` (same inputs, any requestId). */
const worstCase = (over: Record<string, unknown> = {}) =>
  planFor(validateGenerateRequest(request(over), Date.now()), parseRuntimeConfig({})).reservedMicroUsd;

let deps: TestDeps;
beforeEach(async () => {
  await resetEmulators();
  deps = makeDeps();
});

describe("stats accounting", () => {
  test("a call books its tokens and measured cost, and releases its reservation", async () => {
    await seed(UID, 3);
    await generateRecipe(deps, caller, request());
    // 1000 input x 1.5 + (1500 output + 500 thinking) x 7.5 micro-USD per token.
    expect(await stats(deps)).toMatchObject({
      llmCalls: 1, succeeded: 1, inputTokens: 1000, outputTokens: 1500, thinkingTokens: 500,
      reservedMicroUsd: 0, spentMicroUsd: 16_500, generations: 1, generationUnits: 1,
    });
    expect(await user()).toMatchObject({ spendWindow: { microUsd: 16_500 } });
    const gen = (await db.collection("users").doc(UID).collection("generations").get()).docs[0].data();
    expect(gen).toMatchObject({ actualMicroUsd: 16_500, reservedMicroUsd: worstCase(), usage: fakeUsage() });
  });

  test("the price table comes from config/runtime", async () => {
    await seed(UID, 3);
    await setConfig({ prices: { inputUsdPerMTok: 3, outputUsdPerMTok: 10 } });
    await generateRecipe(deps, caller, request());
    expect((await stats(deps)).spentMicroUsd).toBe(3000 + 20_000);
  });

  test("a failure with unknown usage, and a sweeper settle, book the reserved worst case", async () => {
    await seed(UID, 3);
    deps.generator.failWith("infra");
    await generateRecipe(deps, caller, request());
    expect(await stats(deps)).toMatchObject({ infraFailures: 1, unknownUsageCalls: 1, spentMicroUsd: worstCase(), reservedMicroUsd: 0 });

    const slow = deps.generator.blockNext();
    const live = generateRecipe(deps, caller, request());
    await slow.started;
    expect((await stats(deps)).reservedMicroUsd).toBe(worstCase());
    deps.clock.advance(60_000 + 61_000);
    await sweep(deps);
    slow.release();
    await live;
    // The late live result is discarded and books nothing more.
    expect(await stats(deps)).toMatchObject({ llmCalls: 2, spentMicroUsd: 2 * worstCase(), reservedMicroUsd: 0 });
  });

  test("an aborted over-ceiling stream is an infrastructure failure, refunded, booked at the worst case", async () => {
    await seed(UID, 1);
    deps.generator.next.push(async () => { throw new GenerationError("infra", "token ceiling exceeded"); });
    expect(await generateRecipe(deps, caller, request())).toMatchObject({ status: "FAILED", charged: false, balance: 1 });
    expect(await stats(deps)).toMatchObject({ spentMicroUsd: worstCase(), infraFailures: 1 });
    expect(await user()).toMatchObject({ infraRefunds: { count: 1 } });
  });
});

describe("spend breakers", () => {
  test("parallel calls cannot overshoot the daily spend cap: worst cases are reserved up front", async () => {
    const users = Array.from({ length: 8 }, (_, i) => `spender-${i}`);
    for (const u of users) await seed(u, 3);
    await setConfig({ dailySpendBaseMicroUsd: 3 * worstCase() });
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    deps.generator.fallback = async () => {
      await gate;
      return sampleRecipe();
    };
    let finished = 0;
    const calls = users.map((u) => {
      const p = generateRecipe(deps, callerFor(u), request());
      p.then(() => finished++, () => finished++);
      return p;
    });
    // Hold the admitted calls in the LLM until every other call has been decided.
    for (let i = 0; i < 200 && finished < 5; i++) await new Promise((r) => setTimeout(r, 100));
    expect(finished).toBe(5);
    expect(deps.generator.calls).toBe(3);
    release();
    const results = await Promise.allSettled(calls);
    const done = results.filter((r) => r.status === "fulfilled" && r.value.status === "DONE");
    const paused = results.filter(
      (r) => r.status === "rejected" && ((r.reason as HttpsError).details as { reason: string }).reason === "GENERATION_DISABLED",
    );
    expect(done).toHaveLength(3);
    expect(paused).toHaveLength(5);
    expect(deps.generator.calls).toBe(3);
    // Settling replaced the worst cases by the measured cost, which frees room for more calls.
    expect(await stats(deps)).toMatchObject({ reservedMicroUsd: 0, spentMicroUsd: 3 * 16_500 });
    expect((await generateRecipe(deps, callerFor(users[7]), request())).status).toBe("DONE");
  });

  test("at the daily spend cap generation is paused before any LLM call, and nothing is charged", async () => {
    await seed(UID, 3);
    await statsRef(db, utcDay(deps.now())).set({ spentMicroUsd: 1_200_000 - worstCase() + 1 });
    expect(await reason(generateRecipe(deps, caller, request()))).toBe("GENERATION_DISABLED");
    expect(deps.generator.calls).toBe(0);
    expect(await user()).toMatchObject({ balance: 3, inFlight: 0 });
  });

  test("a per-user daily spend ceiling refuses before the LLM call", async () => {
    await seed(UID, 10);
    await setConfig({ perUserDailySpendMicroUsd: worstCase() + 10_000 });
    expect((await generateRecipe(deps, caller, request())).status).toBe("DONE");
    // 16,500 already spent + another worst case > the ceiling.
    expect(await reason(generateRecipe(deps, caller, request()))).toBe("USER_DAILY_LIMIT");
    expect(deps.generator.calls).toBe(1);
    expect(await user()).toMatchObject({ balance: 9 });
  });

  test("the per-user daily call count is USER_DAILY_LIMIT", async () => {
    await seed(UID, 10);
    await setConfig({ perUserPerDay: 1 });
    await generateRecipe(deps, caller, request());
    expect(await reason(generateRecipe(deps, caller, request()))).toBe("USER_DAILY_LIMIT");
  });
});

describe("config/runtime fails closed", () => {
  test("a kill switch of the wrong type pauses generation", async () => {
    await seed(UID, 3);
    await setConfig({ generationEnabled: "yes" });
    expect(await reason(generateRecipe(deps, caller, request()))).toBe("GENERATION_DISABLED");
  });

  test("invalid limits fall back to the defaults and are logged", () => {
    const logs: unknown[] = [];
    const c = parseRuntimeConfig(
      { dailySpendBaseMicroUsd: -1, perUserPerDay: 1.5, dailyCap: Number.NaN, allowSandbox: "true", budgetKillFraction: 0, prices: { inputUsdPerMTok: 0 } },
      { info: () => undefined, error: () => undefined, warn: (_m, d) => logs.push(d) },
    );
    expect(c).toMatchObject({
      dailySpendBaseMicroUsd: 1_200_000, perUserPerDay: 40, dailyCap: 1000, allowSandbox: false, budgetKillFraction: 1,
      prices: { inputUsdPerMTok: 1.5, outputUsdPerMTok: 7.5 },
    });
    expect(logs).toHaveLength(1);
  });

  test("valid overrides are taken", () => {
    expect(parseRuntimeConfig({ dailySpendBaseMicroUsd: 0, infraRefundsPerDay: 1, costs: { recipe: 1, menu2: 1, menu3: 1 } }))
      .toMatchObject({ dailySpendBaseMicroUsd: 0, infraRefundsPerDay: 1, costs: { recipe: 1, menu2: 1, menu3: 1 } });
  });
});

describe("requestId binding", () => {
  test("a done requestId cannot be replayed with other inputs or another kind", async () => {
    await seed(UID, 10);
    const req = request();
    await generateRecipe(deps, caller, req);
    const other = { ...req, ingredients: [{ name: "Caviar", expiresOn: null, quantity: 1 }] };
    expect(await reason(generateRecipe(deps, caller, other))).toBe("REQUEST_ID_REUSED");
    expect(await reason(generateRecipe(deps, caller, { ...req, kind: "menu", courses: 3 }))).toBe("REQUEST_ID_REUSED");
    expect(await reason(generateRecipe(deps, caller, { ...req, config: { ...req.config, servings: 3 } }))).toBe("REQUEST_ID_REUSED");
    expect(deps.generator.calls).toBe(1);
    expect(await user()).toMatchObject({ balance: 9 });
    // The same inputs (the id is case-insensitive) still get the stored recipe.
    expect((await generateRecipe(deps, caller, { ...req, requestId: req.requestId.toUpperCase() })).status).toBe("DONE");
  });

  test("a pending requestId cannot be polled with other inputs", async () => {
    await seed(UID, 10);
    const req = request();
    const slow = deps.generator.blockNext();
    const first = generateRecipe(deps, caller, req);
    await slow.started;
    expect(await reason(generateRecipe(deps, caller, { ...req, allergens: [] }))).toBe("REQUEST_ID_REUSED");
    slow.release();
    await first;
  });
});

describe("usage logs", () => {
  test("one structured line per call, with a hashed uid and no ingredient names", async () => {
    await seed(UID, 3);
    await generateRecipe(deps, caller, request());
    deps.generator.failWith("content");
    await generateRecipe(deps, caller, request());
    const lines = deps.logs.filter((l) => l.message === "llm_call");
    expect(lines).toHaveLength(2);
    expect(lines[0].data).toMatchObject({
      kind: "recipe", model: "fake-model", inputTokens: 1000, outputTokens: 1500, thinkingTokens: 500,
      finishReason: "STOP", outcome: "done", charged: true, refunded: false, estimatedMicroUsd: 16_500,
    });
    expect(lines[1].data).toMatchObject({ outcome: "content", charged: false, refunded: true });
    expect(typeof lines[0].data?.durationMs).toBe("number");
    const all = JSON.stringify(deps.logs);
    expect(all).not.toContain(UID);
    expect(all).not.toContain("Secretingredient");
    expect(all).not.toContain("peanut");
    expect(all).not.toContain("Pantry pasta");
  });
});

describe("merge and delete", () => {
  test("the content-refund allowance and its use move with the purchases on merge", async () => {
    const anon = await anonymousUser();
    const signed = await passwordUser();
    // The anonymous install bought 10 and used its one refund; the signed-in account bought nothing.
    await seed(anon.uid, 9, { purchasedCredits: 10, contentRefundsUsed: 1 });
    await seed(signed.uid, 0, { purchasedCredits: 0 });
    const res = await mergeBalance(deps, callerFor(signed.uid), { sourceIdToken: anon.idToken });
    expect(res).toMatchObject({ balance: 9, moved: 9, costs: { recipe: 1, menu2: 4, menu3: 5 } });
    expect(await user(signed.uid)).toMatchObject({ purchasedCredits: 10, contentRefundsUsed: 1 });
    deps.generator.failWith("content");
    expect(await generateRecipe(deps, callerFor(signed.uid), request())).toMatchObject({ charged: true, balance: 8 });
  });

  test("a merge cannot reset today's infrastructure-refund count", async () => {
    const anon = await anonymousUser();
    const signed = await passwordUser();
    const day = utcDay(deps.now());
    await seed(anon.uid, 5, { infraRefunds: { day, count: 2 } });
    await seed(signed.uid, 5, { infraRefunds: { day, count: 1 } });
    await mergeBalance(deps, callerFor(signed.uid), { sourceIdToken: anon.idToken });
    expect(await reason(generateRecipe(deps, callerFor(signed.uid), request()))).toBe("INFRA_FAILURE_LIMIT");
  });

  test("an account cannot be deleted while it has a generation in flight", async () => {
    await seed(UID, 3);
    const slow = deps.generator.blockNext();
    const live = generateRecipe(deps, caller, request());
    await slow.started;
    expect(await reason(deleteAccount(deps, callerFor(UID, "anonymous"), {}))).toBe("BUSY");
    slow.release();
    await live;
    expect((await stats(deps)).reservedMicroUsd).toBe(0);
  });
});

describe("worst case", () => {
  test("the reservation covers twice the output cap and an input bound from the prompt bytes", () => {
    const c = parseRuntimeConfig({});
    const plan = planFor(validateGenerateRequest(request({ kind: "menu", courses: 3 }), Date.now()), c);
    expect(plan.limits).toEqual({ maxOutputTokens: 20_000, hardTokenCeiling: 40_000 });
    expect(plan.reservedMicroUsd).toBeGreaterThan(40_000 * 7.5);
    // 30 ingredients and 20 allergens of 40 characters, 36 of them two-byte Cyrillic letters (the
    // heaviest the name rules allow).
    const name = (i: number) => `абвгдежзийкл мнопрстуфхцч шщъыьэюяабвг${String(i).padStart(2, "0")}`;
    const heavy = validateGenerateRequest(request({
      ingredients: Array.from({ length: 30 }, (_, i) => ({ name: name(i), expiresOn: null, quantity: 9999 })),
      allergens: Array.from({ length: 20 }, (_, i) => name(i)),
    }), Date.now());
    const p = planFor(heavy, c);
    expect(p.reservedMicroUsd - 8192 * 7.5).toBeGreaterThan(50 * 36 * 2 * 1.5);
  });
});
