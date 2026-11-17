/**
 * Follow-ups of the provider refactor's review: the out-of-credit default of a hand-written
 * picture setup (reserved and asked, README "Fallback"), booking a dearer text provider whose
 * usage is partly unknown, balance thresholds nothing can probe, the logged balance unit, and
 * adapters declaring one param with two defaults.
 */
import { randomUUID } from "node:crypto";
import { validateGenerateRequest } from "../src/contract";
import { bookedMicroUsd, generateRecipe, planFor, spendMicroUsd, withCosts, type CallLedger, type Outcome } from "../src/core/generation";
import {
  DEFAULT_RUNTIME_CONFIG,
  emptyUser,
  imageOutOfCreditTargetOf,
  imageReservedMicroUsdOf,
  microUsd,
  parseRuntimeConfig,
  textReservePricesOf,
  utcDay,
} from "../src/model";
import type { Deps, LlmUsage, RecipeGenerator } from "../src/ports";
import { balanceUnit, checkProviderBalances } from "../src/providers/balance";
import { providerRegistryProblems, registerProviderForTests, textProviders } from "../src/providers/registry";
import { TextRouter } from "../src/providers/router";
import type { ProviderContext, ProviderDescriptor } from "../src/providers/types";
import { fakeImage, fakeText } from "./fakeProvider";
import { callerFor, db, makeDeps, resetEmulators, sampleRecipe, setConfig, type TestDeps } from "./helpers";

const UID = "review-user";
const NOW = Date.now();

function request(over: Record<string, unknown> = {}) {
  return {
    requestId: randomUUID(),
    kind: "recipe",
    config: { difficulty: "easy", detail: "standard", servings: 2, maxMinutes: 30, mealType: "dinner", diet: "none", language: "en" },
    ingredients: [{ name: "Pasta", expiresOn: "2030-01-01", quantity: 1 }],
    allergens: [],
    today: utcDay(NOW),
    ...over,
  };
}

function contextFor(d: ProviderDescriptor, f: typeof fetch): ProviderContext {
  return { project: "demo", param: (n) => d.params?.find((p) => p.name === n)?.default ?? "", secret: () => Promise.resolve("sk-review"), fetch: f };
}

let unregister: (() => void)[] = [];
beforeAll(() => {
  unregister = [registerProviderForTests(fakeText), registerProviderForTests(fakeImage)];
});
afterAll(() => unregister.forEach((u) => u()));
beforeEach(async () => {
  await resetEmulators();
});

describe("a hand-written picture setup at another provider with no fallback", () => {
  // The deploy default is vertex / gemini-3.1-flash-lite-image (USD 0.04); openai's gpt-image-1-mini is USD 0.02.
  const openai = parseRuntimeConfig({ image: { provider: "openai", model: "gpt-image-1-mini", fallbackModel: "" } });

  test("falls back to the default provider on out of credit, and reserves its price too", () => {
    expect(openai.image).toMatchObject({ provider: "openai", model: "gpt-image-1-mini", fallbackModel: "" });
    expect(imageOutOfCreditTargetOf(openai)).toEqual({ provider: "vertex", model: "gemini-3.1-flash-lite-image" });
    expect(imageReservedMicroUsdOf(openai)).toBe(40_000);
    expect(planFor(validateGenerateRequest(request({ image: "dish" }), NOW), openai).image).toMatchObject({ reservedMicroUsd: 40_000 });
  });

  test("a named fallback replaces the default; the same provider as the default, or pictures off by default, add nothing", () => {
    const named = parseRuntimeConfig({ image: { provider: "openai", model: "gpt-image-1-mini", fallbackProvider: "vertex", fallbackModel: "gemini-nano-banana-2.1" } });
    expect(imageOutOfCreditTargetOf(named)).toEqual({ provider: "vertex", model: "gemini-nano-banana-2.1" });
    expect(imageReservedMicroUsdOf(named)).toBe(60_000);
    const sameProvider = parseRuntimeConfig({ image: { provider: "vertex", model: "gemini-nano-banana-2.1", fallbackModel: "" } });
    expect(imageOutOfCreditTargetOf(sameProvider)).toBeNull();
    expect(imageReservedMicroUsdOf(sameProvider)).toBe(60_000);
    const offByDefault = parseRuntimeConfig({ image: { provider: "openai", model: "gpt-image-1-mini", fallbackModel: "" } }, undefined, { provider: "off", model: "x", fallbackModel: "", maxStepImages: 2 });
    expect(imageOutOfCreditTargetOf(offByDefault)).toBeNull();
    expect(imageReservedMicroUsdOf(offByDefault)).toBe(20_000);
  });
});

describe("booking a text provider dearer than Gemini", () => {
  const usage = (input: number, output: number): LlmUsage => ({ inputTokens: input, outputTokens: output, thinkingTokens: 0, model: "m", finishReason: "STOP" });
  // Parsed in each test: the fake provider is registered only in beforeAll.
  const dearConfig = () => parseRuntimeConfig({ text: { provider: "fakeeu", model: "fake-large" } });
  const large = { inputUsdPerMTok: 2.2, outputUsdPerMTok: 33 };
  const gemini = { inputUsdPerMTok: DEFAULT_RUNTIME_CONFIG.prices.inputUsdPerMTok, outputUsdPerMTok: DEFAULT_RUNTIME_CONFIG.prices.outputUsdPerMTok };
  const done = (u: LlmUsage | undefined): Outcome => ({ ok: false, failureClass: "infra", reason: "x", usage: u });

  test("a call of unknown usage beside it books the reservation, never the known part at Gemini's prices", () => {
    const dear = dearConfig();
    expect(dear.text).toMatchObject({ provider: "fakeeu", model: "fake-large" });
    const ledger: CallLedger = { parts: [{ prices: large, usage: usage(900, 400) }, { prices: gemini, usage: undefined }], answered: { provider: "fakeeu", model: "fake-large" }, fallback: false };
    // A measured usage with an unknown call beside it (defensive: addUsage makes the total unknown today).
    const o = withCosts(done(usage(900, 400)), ledger, dear);
    expect(o.bookReserved).toBe(true);
    expect(o.spend).toBeUndefined();
    expect(bookedMicroUsd(o, dear, 777_777, 0)).toBe(777_777);
    // Everything known: each call at its own price.
    const known = withCosts(done(usage(1000, 450)), { ...ledger, parts: [ledger.parts[0], { prices: gemini, usage: usage(100, 50) }] }, dear);
    expect(known.bookReserved).toBeUndefined();
    expect(bookedMicroUsd(known, dear, 777_777, 5)).toBe(spendMicroUsd([
      { ...large, inputTokens: 900, outputTokens: 400 },
      { ...gemini, inputTokens: 100, outputTokens: 50 },
    ]) + 5);
  });

  test("the default Google-only path books exactly as before", () => {
    const ledger: CallLedger = { parts: [{ prices: gemini, usage: usage(900, 400) }, { prices: gemini, usage: undefined }], answered: { provider: "google", model: "gemini-3.8-flash" }, fallback: false };
    const o = withCosts(done(usage(900, 400)), ledger, DEFAULT_RUNTIME_CONFIG);
    expect(o).toEqual(done(usage(900, 400)));
    expect(bookedMicroUsd(o, DEFAULT_RUNTIME_CONFIG, 777_777, 5)).toBe(microUsd(DEFAULT_RUNTIME_CONFIG.prices, 900, 400) + 5);
    expect(bookedMicroUsd(done(undefined), DEFAULT_RUNTIME_CONFIG, 777_777, 5)).toBe(777_777);
  });

  test("end to end: an answer without usage keeps the reservation, at the dearer provider's prices", async () => {
    const dear = dearConfig();
    await db.collection("users").doc(UID).set({ ...emptyUser(), balance: 5, purchasedCredits: 5 });
    const text = JSON.stringify(sampleRecipe());
    // Streamed without a usage chunk: the provider's usage is unknown.
    const body = [
      { model: "fake-large", choices: [{ delta: { content: text } }] },
      { choices: [{ delta: { content: "" }, finish_reason: "stop" }] },
    ].map((c) => `data: ${JSON.stringify(c)}`).join("\n\n") + "\n\ndata: [DONE]\n";
    const f = (async () => new Response(body, { status: 200 })) as unknown as typeof fetch;
    const deps: TestDeps = makeDeps();
    const google = deps.generator;
    const factories = Object.fromEntries(textProviders().map((d) => [d.id, () => (d.id === "google" ? google : d.create(contextFor(d, f)))]));
    (deps as Deps).generator = new TextRouter(factories, { provider: "google", model: "gemini-3.8-flash" }) as RecipeGenerator;
    await setConfig({ text: { provider: "fakeeu", model: "fake-large" } });
    const r = request();
    expect(await generateRecipe(deps, callerFor(UID), r)).toMatchObject({ status: "DONE" });
    const g = (await db.collection("users").doc(UID).collection("generations").doc(r.requestId).get()).data()!;
    expect(g.actualMicroUsd).toBe(g.reservedMicroUsd);
    expect(textReservePricesOf(dear)).toEqual(large);
    expect(g.reservedMicroUsd).toBe(planFor(validateGenerateRequest(r, NOW), dear).reservedMicroUsd);
  });
});

describe("balance thresholds and units", () => {
  const logger = () => {
    const logs: { level: string; message: string; data?: Record<string, unknown> }[] = [];
    const rec = (level: string) => (message: string, data?: Record<string, unknown>) => logs.push({ level, message, data });
    return { logs, logger: { info: rec("info"), warn: rec("warn"), error: rec("error") } };
  };
  const never = (async () => { throw new Error("nothing should be fetched"); }) as unknown as typeof fetch;

  test("a threshold nothing can probe is logged once per run, with no call; none set, nothing logged", async () => {
    const { logs, logger: l } = logger();
    const out = await checkProviderBalances({ providers: [fakeText, fakeImage], thresholds: { fakeeu: 5, nosuch: 1 }, context: (d) => contextFor(d, never), logger: l });
    expect(out).toEqual([]);
    expect(logs).toEqual([
      { level: "warn", message: "provider_balance_unprobed", data: { provider: "fakeeu", reason: "NO_PROBE" } },
      { level: "warn", message: "provider_balance_unprobed", data: { provider: "nosuch", reason: "UNKNOWN_PROVIDER" } },
    ]);
    logs.length = 0;
    expect(await checkProviderBalances({ providers: [fakeText, fakeImage], thresholds: {}, context: (d) => contextFor(d, never), logger: l })).toEqual([]);
    expect(logs).toEqual([]);
  });

  test("the logged unit is a short lower-case word, else unknown", async () => {
    expect(["usd", "credits", "eur_cents"].map(balanceUnit)).toEqual(["usd", "credits", "eur_cents"]);
    expect(["USD", "", "a b", "x".repeat(17), "<script>", 5, null].map(balanceUnit)).toEqual(Array(7).fill("unknown"));
    const { logs, logger: l } = logger();
    const odd: ProviderDescriptor = { ...fakeImage, id: "oddunit", balance: async () => ({ amount: 1, unit: "Top up at https://example" }) };
    const out = await checkProviderBalances({ providers: [odd], thresholds: { oddunit: 10 }, context: (d) => contextFor(d, never), logger: l });
    expect(out).toEqual([{ provider: "oddunit", outcome: "low", amount: 1, unit: "unknown", threshold: 10 }]);
    expect(JSON.stringify(logs)).not.toContain("example");
  });
});

describe("the registry's params", () => {
  test("two adapters declaring one param with different defaults is a problem; the same default is not", () => {
    const one: ProviderDescriptor = { ...fakeText, id: "dupone", secret: undefined, params: [{ name: "SHARED_REGION", default: "eu", description: "x" }] };
    const same: ProviderDescriptor = { ...fakeText, id: "duptwo", secret: undefined, params: [{ name: "SHARED_REGION", default: "eu", description: "y" }] };
    const other: ProviderDescriptor = { ...fakeImage, id: "dupthree", secret: undefined, params: [{ name: "SHARED_REGION", default: "us", description: "z" }] };
    const undo = [registerProviderForTests(one), registerProviderForTests(same)];
    try {
      expect(providerRegistryProblems()).toEqual([]);
      undo.push(registerProviderForTests(other));
      expect(providerRegistryProblems()).toEqual(["param SHARED_REGION: declared twice with different defaults"]);
    } finally {
      undo.forEach((u) => u());
    }
    expect(providerRegistryProblems()).toEqual([]);
  });
});
