/**
 * The common provider interface (README "Adding a provider"): a provider that exists only in the
 * tests (test/fakeProvider.ts, one adapter file) registered as one registry entry is selectable in
 * config/runtime and by profile, priced, reserved, routed, booked and logged with no other change;
 * the shared failure taxonomy; fallback as an interface-level rule (out of credit to the named
 * fallback or the default provider, capacity and config to the named fallback only, never after a
 * safety refusal or once something was generated); the balance probe; and that the default path is
 * the very call it was (the pinned hashes, through the router as src/index.ts wires it).
 */
import { createHash, randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import type { GenerateContentParameters, GenerateContentResponse } from "@google/genai";
import { GeminiRecipeGenerator, type StreamingModels } from "../src/adapters/gemini";
import { JUDGE, JUDGE_PRICES } from "../src/constants";
import { buildPrompt } from "../src/adapters/prompt";
import { validateGenerateRequest } from "../src/contract";
import { generateRecipe, planFor, spendMicroUsd } from "../src/core/generation";
import { planProfile } from "../src/core/providerSwitch";
import { DEFAULT_RUNTIME_CONFIG, emptyUser, microUsd, parseRuntimeConfig, textReservePricesOf, utcDay } from "../src/model";
import { GenerationError, ImageGenerationError, type Deps, type RecipeGenerator } from "../src/ports";
import { checkProviderBalances } from "../src/providers/balance";
import { httpFailureKind, retryAfterMs } from "../src/providers/failure";
import { PROVIDER_PROFILES, readinessOf, registryProblems, withProviderRequirements } from "../src/providers/profiles";
import { imageProviderIds, providerRegistryProblems, providerSecrets, registerProviderForTests, textProviders } from "../src/providers/registry";
import { ImageRouter, TextRouter } from "../src/providers/router";
import type { ProviderContext, ProviderDescriptor } from "../src/providers/types";
import { FAKE_BALANCE_URL, FAKE_TEXT_URL, deltaText, fakeImage, fakeText, fakeTextBody } from "./fakeProvider";
import { callerFor, db, makeDeps, resetEmulators, sampleProviderPng, sampleRecipe, setConfig, type TestDeps } from "./helpers";

const UID = "adapter-user";
const KEY = "sk-fake-never-logged-123";
const NOW = Date.now();

type Reply = { status: number; body: unknown; headers?: Record<string, string> };

/** A fetch answering queued replies; a string body is sent as is (an SSE stream). */
function fetchQueue(replies: Reply[]): { fetch: typeof fetch; calls: { url: string; init: RequestInit }[] } {
  const calls: { url: string; init: RequestInit }[] = [];
  const f = (async (url: string, init: RequestInit) => {
    calls.push({ url: String(url), init });
    const r = replies.shift();
    if (!r) throw new Error("no reply queued");
    const body = typeof r.body === "string" ? r.body : JSON.stringify(r.body);
    return new Response(body, { status: r.status, headers: r.headers });
  }) as unknown as typeof fetch;
  return { fetch: f, calls };
}

/** A streamed answer: the JSON split over deltas, a list-shaped delta among them, then usage. */
function sse(output: unknown, opts: { finish?: string; prompt?: number; completion?: number } = {}): string {
  const text = JSON.stringify(output);
  const half = Math.floor(text.length / 2);
  const chunks = [
    { model: "fake-small-2611", choices: [{ delta: { content: text.slice(0, half) } }] },
    { choices: [{ delta: { content: [{ type: "text", text: text.slice(half) }] } }] },
    { choices: [{ delta: { content: "" }, finish_reason: opts.finish ?? "stop" }], usage: { prompt_tokens: opts.prompt ?? 900, completion_tokens: opts.completion ?? 400 } },
  ];
  return chunks.map((c) => `data: ${JSON.stringify(c)}`).join("\n\n") + "\n\ndata: [DONE]\n";
}

function contextFor(d: ProviderDescriptor, f: typeof fetch, key: string | null = KEY): ProviderContext {
  return {
    project: "demo",
    param: (n) => d.params?.find((p) => p.name === n)?.default ?? "",
    secret: () => (key ? Promise.resolve(key) : Promise.reject(new Error("secret unavailable"))),
    fetch: f,
  };
}

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

let unregister: (() => void)[] = [];
beforeAll(() => {
  unregister = [registerProviderForTests(fakeText), registerProviderForTests(fakeImage)];
});
afterAll(() => unregister.forEach((u) => u()));

/** Deps whose generator is a TextRouter over the registry, as src/index.ts builds it (Vertex is the FakeGenerator). */
function routed(f: typeof fetch, key: string | null = KEY): TestDeps {
  const deps = makeDeps();
  const google = deps.generator;
  const factories = Object.fromEntries(textProviders().map((d) => [d.id, () => (d.id === "google" ? google : d.create(contextFor(d, f, key)))]));
  (deps as Deps).generator = new TextRouter(factories, { provider: "google", model: "gemini-3.8-flash" }) as RecipeGenerator;
  return deps;
}

async function seed(balance: number) {
  await db.collection("users").doc(UID).set({ ...emptyUser(), balance, purchasedCredits: balance });
}
const gen = async (requestId: string) => (await db.collection("users").doc(UID).collection("generations").doc(requestId).get()).data()!;
const noKeyInLogs = (deps: TestDeps) => expect(JSON.stringify(deps.logs)).not.toContain(KEY);

beforeEach(async () => {
  await resetEmulators();
});

describe("the shared failure taxonomy", () => {
  test("HTTP statuses map the same way for every provider", () => {
    expect([401, 403, 402, 429, 500, 502, 503, 504, 400, 404, 418].map((s) => httpFailureKind(s))).toEqual([
      "config", "config", "out_of_credit", "capacity", "capacity", "capacity", "capacity", "capacity", "infra", "infra", "infra",
    ]);
    expect(httpFailureKind(429, { exhausted: true })).toBe("out_of_credit");
    expect(httpFailureKind(404, { modelNotFound: true })).toBe("config");
  });

  test("Retry-After in seconds or as a date, capped; nothing when absent or unreadable", () => {
    expect(retryAfterMs("7", 0)).toBe(7000);
    expect(retryAfterMs("600", 0)).toBe(60_000);
    expect(retryAfterMs(new Date(10_000).toUTCString(), 4_000)).toBe(6000);
    expect(retryAfterMs(null, 0)).toBeUndefined();
    expect(retryAfterMs("soon", 0)).toBeUndefined();
  });

  test("the existing errors read as the taxonomy without saying so", () => {
    expect(new GenerationError("infra", "x").providerKind).toBe("infra");
    expect(new GenerationError("content", "x").providerKind).toBe("content");
    expect(new GenerationError("content", "x", undefined, "FINISH_SAFETY").providerKind).toBe("safety");
    expect(new GenerationError("infra", "x", undefined, undefined, "out_of_credit").providerKind).toBe("out_of_credit");
    expect(new ImageGenerationError("infra", "x", false, true).providerKind).toBe("capacity");
    expect(new ImageGenerationError("infra", "x", true).providerKind).toBe("infra");
    expect(new ImageGenerationError("out_of_credit", "x", false).providerKind).toBe("out_of_credit");
  });

  test("Vertex: a 429 before any chunk is capacity, a refused credential config, mid-stream nothing", async () => {
    const { ApiError } = await import("@google/genai");
    const failing = (status: number, afterChunk = false): StreamingModels => ({
      async generateContentStream() {
        if (!afterChunk) throw new ApiError({ message: "quoted prompt text", status });
        return (async function* () {
          yield { candidates: [{ content: { parts: [{ text: "{" }] } }] } as unknown as GenerateContentResponse;
          throw new ApiError({ message: "x", status });
        })();
      },
    });
    const req = validateGenerateRequest(request(), NOW);
    const call = (m: StreamingModels) =>
      new GeminiRecipeGenerator({ project: "p", location: "global", model: "gemini-3.8-flash", thinkingLevel: "LOW" }, m)
        .generate(req, buildPrompt(req, "LRD-0000000000000000"), new AbortController().signal, { maxOutputTokens: 4096, hardTokenCeiling: 8192 })
        .then(() => { throw new Error("expected a failure"); }, (e: GenerationError) => e);
    const e429 = await call(failing(429));
    expect(e429).toMatchObject({ failureClass: "infra", message: "Vertex AI 429", kind: "capacity" });
    expect((await call(failing(403))).kind).toBe("config");
    const mid = await call(failing(503, true));
    expect(mid).toMatchObject({ failureClass: "infra", usage: undefined, kind: undefined });
  });
});

describe("the registry", () => {
  test("is sound, and the picture ids and secrets come from it", () => {
    expect(providerRegistryProblems()).toEqual([]);
    expect(imageProviderIds()).toEqual(expect.arrayContaining(["vertex", "openai", "off", "fakepix"]));
    expect(providerSecrets().map((s) => s.id)).toEqual(expect.arrayContaining(["openai", "fakeeu", "fakepix"]));
  });

  test("the default path through the router sends exactly the pinned Vertex call", async () => {
    const pin = JSON.parse(readFileSync(join(__dirname, "fixtures/provider-pin-2026-11-17.json"), "utf8")) as { requests: Record<string, { sent: string[] }> };
    const raw = JSON.parse(readFileSync(join(__dirname, "fixtures/config-recipe-2026-11-14.json"), "utf8"));
    const req = validateGenerateRequest({ ...raw, requestId: "7d0b6a8e-4c1f-4f7a-9d2e-1a2b3c4d5e6f", today: "2026-11-14" }, Date.parse("2026-11-14T12:00:00Z"), [], 2);
    const sent: string[] = [];
    const models: StreamingModels = {
      async generateContentStream(params: GenerateContentParameters) {
        const config = { ...((params.config ?? {}) as Record<string, unknown>) };
        delete config.abortSignal;
        sent.push(createHash("sha256").update(JSON.stringify({ ...params, config })).digest("hex"));
        return (async function* () {})();
      },
    };
    const router = new TextRouter({ google: () => new GeminiRecipeGenerator({ project: "p", location: "global", model: "gemini-3.8-flash", thinkingLevel: "LOW" }, models) }, { provider: "google", model: "gemini-3.8-flash" });
    const plan = planFor(req, DEFAULT_RUNTIME_CONFIG);
    const target = { provider: DEFAULT_RUNTIME_CONFIG.text.provider, model: DEFAULT_RUNTIME_CONFIG.text.model };
    await router.generate(req, buildPrompt(req, "LRD-0123456789ABCDEF"), new AbortController().signal, plan.limits, target).catch(() => undefined);
    expect(sent).toEqual(pin.requests["config-recipe-2026-11-14/plain"].sent);
  });

  test("an unknown provider is a config failure with nothing sent", async () => {
    const router = new TextRouter({}, { provider: "google", model: "m" });
    const req = validateGenerateRequest(request(), NOW);
    const e = await router.generate(req, buildPrompt(req, "LRD-0000000000000000"), new AbortController().signal, { maxOutputTokens: 1, hardTokenCeiling: 2 }).catch((x) => x);
    expect(e).toMatchObject({ failureClass: "infra", kind: "config", usage: { outputTokens: 0, thinkingTokens: 0 } });
    await expect(new ImageRouter({}).generate({ text: "x", aspectRatio: "4:3" }, { provider: "fakepix", model: "m" }, new AbortController().signal))
      .rejects.toMatchObject({ kind: "config", billed: false });
  });
});

describe("selecting a new provider: config, profile, readiness", () => {
  test("config/runtime.text selects it, its default model and price; unknown or unpriced is the default", () => {
    const c = parseRuntimeConfig({ text: { provider: "fakeeu" } });
    expect(c.text).toEqual({ provider: "fakeeu", model: "fake-small", fallbackProvider: "", fallbackModel: "" });
    const warn = vi.fn();
    expect(parseRuntimeConfig({ text: { provider: "nope" } }, { info: vi.fn(), warn, error: vi.fn() }).text.provider).toBe("google");
    expect(warn).toHaveBeenCalledWith(expect.any(String), { fields: ["text"] });
    expect(parseRuntimeConfig({ text: { provider: "fakeeu", model: "unpriced" } }).text.provider).toBe("google");
    // A price override in config/runtime makes a model usable without a deploy.
    const priced = parseRuntimeConfig({ text: { provider: "fakeeu", model: "fake-xl" }, prices: { inputUsdPerMTok: 1.5, outputUsdPerMTok: 7.5, textUsdPerMTok: { "fakeeu/fake-xl": { inputUsdPerMTok: 3, outputUsdPerMTok: 9 } } } });
    expect(priced.text.model).toBe("fake-xl");
    // An unpriced fallback is dropped; one at another provider is kept.
    expect(parseRuntimeConfig({ text: { provider: "fakeeu", fallbackModel: "nope" } }).text.fallbackModel).toBe("");
    expect(parseRuntimeConfig({ text: { provider: "fakeeu", fallbackProvider: "google", fallbackModel: "gemini-3.8-flash" } }).text)
      .toEqual({ provider: "fakeeu", model: "fake-small", fallbackProvider: "google", fallbackModel: "gemini-3.8-flash" });
  });

  test("the reservation covers the dearer of the provider and its fallbacks, and is unchanged for the default", () => {
    const req = validateGenerateRequest(request(), NOW);
    const base = planFor(req, DEFAULT_RUNTIME_CONFIG).reservedMicroUsd;
    expect(textReservePricesOf(DEFAULT_RUNTIME_CONFIG)).toEqual({ inputUsdPerMTok: 1.5, outputUsdPerMTok: 7.5 });
    // The cheap EU model: out of credit goes to the Gemini default, so Gemini's prices are reserved.
    expect(planFor(req, parseRuntimeConfig({ text: { provider: "fakeeu" } })).reservedMicroUsd).toBe(base);
    // A dearer model reserves its own price.
    const dear = parseRuntimeConfig({ text: { provider: "fakeeu", model: "fake-large" } });
    expect(textReservePricesOf(dear)).toEqual({ inputUsdPerMTok: 2.2, outputUsdPerMTok: 33 });
    expect(planFor(req, dear).reservedMicroUsd).toBeGreaterThan(base * 4);
  });

  test("a profile with a text part: sound, readiness from its providers, the exact writes", () => {
    const eu = withProviderRequirements({
      id: "eu-pair",
      label: "Fake EU pair",
      summary: "Text and pictures at the fake providers; falls back to Google.",
      text: { provider: "fakeeu", model: "fake-small", fallbackProvider: "google", fallbackModel: "gemini-3.8-flash" },
      image: { provider: "fakepix", model: "fake-draw", fallbackProvider: "vertex", fallbackModel: "gemini-3.1-flash-lite-image", usdPerImage: { "fakepix/fake-draw": 0.03, "vertex/gemini-3.1-flash-lite-image": 0.04 } },
    });
    expect(registryProblems([...PROVIDER_PROFILES, eu])).toEqual([]);
    expect(eu.requires.map((r) => r.missing)).toEqual([
      "The fake key is not readable",
      "The fake picture provider is not in the privacy policy",
      "The fake picture key is not readable",
    ]);
    const plan = planProfile({ prices: { inputUsdPerMTok: 1.5, outputUsdPerMTok: 7.5 } }, eu);
    expect(plan.writes.map((w) => [w.path.join("."), w.value])).toEqual([
      ["image.provider", "fakepix"],
      ["image.model", "fake-draw"],
      ["image.fallbackModel", "gemini-3.1-flash-lite-image"],
      ["image.fallbackProvider", "vertex"],
      // No text map yet: written whole. No price: the adapters' own prices are already in force.
      ["text", { provider: "fakeeu", model: "fake-small", fallbackProvider: "google", fallbackModel: "gemini-3.8-flash" }],
    ]);
    // Over an existing text map: field paths, only what differs.
    expect(planProfile({ text: { provider: "fakeeu", model: "fake-large" } }, eu).writes.filter((w) => w.path[0] === "text").map((w) => [w.path.join("."), w.value])).toEqual([
      ["text.model", "fake-small"],
      ["text.fallbackProvider", "google"],
      ["text.fallbackModel", "gemini-3.8-flash"],
    ]);
    expect(plan.to).toMatchObject({ provider: "fakepix", fallbackProvider: "vertex", textProvider: "fakeeu", textFallbackProvider: "google" });
    // Back to a profile without text: the text's deploy default again (text: {}).
    const back = planProfile({ text: { provider: "fakeeu" }, image: { provider: "fakepix", model: "fake-draw", fallbackProvider: "vertex", fallbackModel: "gemini-3.1-flash-lite-image" } }, PROVIDER_PROFILES[0]);
    expect(back.writes.map((w) => [w.path.join("."), w.value])).toEqual(expect.arrayContaining([["text", {}], ["image.fallbackProvider", ""]]));
    expect(back.to).toMatchObject({ profile: "flash-lite", textProvider: "google" });
  });

  test("readiness checks each provider's key and privacy approval, never returning a secret", async () => {
    const eu = withProviderRequirements({ id: "eu-text", label: "x", summary: "y", text: { provider: "fakeeu", model: "fake-small" }, image: { provider: "off", usdPerImage: {} } });
    expect(await readinessOf(eu, async (id) => id === "fakeeu")).toEqual({ ready: true, missing: [] });
    expect(await readinessOf(eu, async () => false)).toEqual({ ready: false, missing: ["The fake key is not readable"] });
  });
});

describe("generateRecipe through the new provider", () => {
  test("its answer is delivered, booked at its own price (the judge at its own model's) and logged by provider", async () => {
    await seed(5);
    const q = fetchQueue([{ status: 200, body: sse(sampleRecipe()) }]);
    const deps = routed(q.fetch);
    await setConfig({ text: { provider: "fakeeu" }, outputJudge: "shadow" });
    const r = request();
    expect(await generateRecipe(deps, callerFor(UID), r)).toMatchObject({ status: "DONE", balance: 4 });
    // What was sent: the provider-neutral prompt in the provider's own dialect, with its key.
    const body = JSON.parse(String(q.calls[0].init.body));
    expect(q.calls[0].url).toBe(FAKE_TEXT_URL);
    expect(body).toMatchObject({ model: "fake-small", reasoning_effort: "none", response_format: { type: "json_schema", json_schema: { strict: true } }, stream: true });
    expect(body).not.toHaveProperty("safety_settings");
    const g = await gen(r.requestId);
    const judge = deps.judge.usage!;
    expect(g.actualMicroUsd).toBe(spendMicroUsd([
      { inputUsdPerMTok: 0.11, outputUsdPerMTok: 0.33, inputTokens: 900, outputTokens: 400 },
      { ...JUDGE_PRICES[JUDGE.model], inputTokens: judge.inputTokens, outputTokens: judge.outputTokens + judge.thinkingTokens },
    ]));
    expect(g.actualMicroUsd).toBeLessThan(microUsd(DEFAULT_RUNTIME_CONFIG.prices, 900 + judge.inputTokens, 400 + judge.outputTokens + judge.thinkingTokens));
    expect(g).toMatchObject({ textProvider: "fakeeu", usage: { model: "fake-small-2611", inputTokens: 900 + judge.inputTokens } });
    expect(deps.logs.find((l) => l.message === "llm_call")?.data).toMatchObject({ provider: "fakeeu", textFallback: false, outcome: "done" });
    noKeyInLogs(deps);
  });

  test("out of credit: logged without the provider's text, then the default provider writes it", async () => {
    await seed(5);
    const q = fetchQueue([{ status: 402, body: { message: "Your balance is empty, top up at example" } }]);
    const deps = routed(q.fetch);
    await setConfig({ text: { provider: "fakeeu" } });
    const r = request();
    expect(await generateRecipe(deps, callerFor(UID), r)).toMatchObject({ status: "DONE", balance: 4 });
    const out = deps.logs.filter((l) => l.message === "provider_out_of_credit");
    expect(out).toEqual([{ level: "error", message: "provider_out_of_credit", data: { provider: "fakeeu", model: "fake-small", surface: "text" } }]);
    expect(JSON.stringify(deps.logs)).not.toContain("top up");
    expect(deps.logs.find((l) => l.message === "text_fallback")?.data).toMatchObject({ provider: "fakeeu", fallbackProvider: "google", fallbackModel: "gemini-3.8-flash", reason: "OUT_OF_CREDIT" });
    expect(deps.logs.find((l) => l.message === "llm_call")?.data).toMatchObject({ provider: "google", textFallback: true });
    noKeyInLogs(deps);
  });

  test("out of credit with nowhere to go: refunded by the existing rules (known usage, nothing made)", async () => {
    await seed(5);
    const q = fetchQueue([{ status: 402, body: {} }, { status: 402, body: {} }]);
    const deps = routed(q.fetch);
    // Its fallback is the same provider's other model, out of credit too.
    await setConfig({ text: { provider: "fakeeu", fallbackModel: "fake-large" } });
    const r = request();
    expect(await generateRecipe(deps, callerFor(UID), r)).toMatchObject({ status: "FAILED", charged: false, balance: 5 });
    expect(deps.logs.filter((l) => l.message === "provider_out_of_credit").map((l) => l.data?.model)).toEqual(["fake-small", "fake-large"]);
    expect((await gen(r.requestId)).failureClass).toBe("infra");
  });

  test("capacity goes to the named fallback only; a safety refusal or a cut-off answer never", async () => {
    await seed(10);
    // Capacity without a named fallback: no fallback (the default is not asked for capacity).
    let q = fetchQueue([{ status: 429, body: {}, headers: { "retry-after": "3" } }]);
    let deps = routed(q.fetch);
    await setConfig({ text: { provider: "fakeeu" } });
    expect(await generateRecipe(deps, callerFor(UID), request())).toMatchObject({ status: "FAILED", charged: false });
    expect(deps.logs.some((l) => l.message === "text_fallback")).toBe(false);
    // With one: asked once.
    q = fetchQueue([{ status: 503, body: {} }]);
    deps = routed(q.fetch);
    await setConfig({ text: { provider: "fakeeu", fallbackProvider: "google", fallbackModel: "gemini-3.8-flash" } });
    expect(await generateRecipe(deps, callerFor(UID), request())).toMatchObject({ status: "DONE" });
    expect(deps.logs.find((l) => l.message === "text_fallback")?.data?.reason).toBe("CAPACITY");
    // finish_reason length: a content failure, never sent on.
    q = fetchQueue([{ status: 200, body: sse(sampleRecipe(), { finish: "length" }) }]);
    deps = routed(q.fetch);
    expect(await generateRecipe(deps, callerFor(UID), request())).toMatchObject({ status: "FAILED" });
    expect(deps.logs.some((l) => l.message === "text_fallback")).toBe(false);
    expect(deps.logs.find((l) => l.message === "llm_call")?.data).toMatchObject({ outcome: "content", reason: "finish length" });
  });

  test("a missing key is a config failure: nothing sent, and the named fallback writes it", async () => {
    await seed(5);
    const q = fetchQueue([]);
    const deps = routed(q.fetch, null);
    await setConfig({ text: { provider: "fakeeu", fallbackProvider: "google", fallbackModel: "gemini-3.8-flash" } });
    expect(await generateRecipe(deps, callerFor(UID), request())).toMatchObject({ status: "DONE" });
    expect(q.calls).toHaveLength(0);
    expect(deps.logs.find((l) => l.message === "text_fallback")?.data?.reason).toBe("CONFIG");
  });

  test("list-shaped deltas are read as text", () => {
    expect(deltaText({ content: [{ type: "text", text: "a" }, { type: "image_url" }, { type: "text", text: "b" }] })).toBe("ab");
    expect(deltaText({ content: null })).toBe("");
    expect(fakeTextBody({ systemInstruction: "s", userText: "u", schema: { type: "object" } }, "m", { maxOutputTokens: 10, hardTokenCeiling: 20 }))
      .toMatchObject({ max_tokens: 10, messages: [{ role: "system" }, { role: "user" }] });
  });
});

describe("pictures through the new provider", () => {
  function pictureDeps(replies: Reply[]) {
    const q = fetchQueue(replies);
    const deps = makeDeps();
    const vertex = deps.imageGenerator;
    (deps as Deps).imageGenerator = new ImageRouter({ vertex: () => vertex, fakepix: () => fakeImage.create(contextFor(fakeImage, q.fetch)) });
    return { deps, q, vertex };
  }

  test("out of credit: no retry, logged, and the deploy default (Vertex) makes it", async () => {
    await seed(5);
    const { deps, q, vertex } = pictureDeps([{ status: 402, body: { detail: "insufficient credits" } }]);
    await setConfig({ image: { provider: "fakepix", model: "fake-draw", fallbackModel: "" } });
    const res = await generateRecipe(deps, callerFor(UID), request({ image: "dish" }));
    expect(res).toMatchObject({ status: "DONE", balance: 3 });
    expect(res.image).toBeDefined();
    expect(q.calls).toHaveLength(1);
    expect(vertex.targets).toEqual([{ provider: "vertex", model: "gemini-3.1-flash-lite-image" }]);
    expect(deps.logs.find((l) => l.message === "provider_out_of_credit")?.data).toEqual({ provider: "fakepix", model: "fake-draw", surface: "image" });
    expect(deps.logs.find((l) => l.message === "image_call")?.data).toMatchObject({ provider: "fakepix", fallback: true, fallbackProvider: "vertex", outcome: "done" });
    noKeyInLogs(deps);
  });

  test("capacity: retried after at least the provider's Retry-After, then the named fallback at another provider", async () => {
    await seed(5);
    const busy = { status: 429, body: {}, headers: { "retry-after": "9" } };
    const { deps, vertex } = pictureDeps([busy, busy, busy, busy]);
    await setConfig({ image: { provider: "fakepix", model: "fake-draw", fallbackProvider: "vertex", fallbackModel: "gemini-3.1-flash-lite-image" } });
    expect(await generateRecipe(deps, callerFor(UID), request({ image: "dish" }))).toMatchObject({ status: "DONE" });
    expect(deps.sleeps[0]).toBe(9000);
    expect(vertex.targets).toEqual([{ provider: "vertex", model: "gemini-3.1-flash-lite-image" }]);
    expect(deps.logs.find((l) => l.message === "image_fallback")?.data).toMatchObject({ fallbackProvider: "vertex", reason: "QUOTA" });
  });

  test("a refused key goes to a fallback at another provider, never to one at the same provider; safety never", async () => {
    await seed(10);
    let t = pictureDeps([{ status: 401, body: {} }]);
    await setConfig({ image: { provider: "fakepix", model: "fake-draw", fallbackProvider: "vertex", fallbackModel: "gemini-3.1-flash-lite-image" } });
    expect(await generateRecipe(t.deps, callerFor(UID), request({ image: "dish" }))).toMatchObject({ status: "DONE" });
    expect(t.vertex.calls).toBe(1);
    t = pictureDeps([]);
    t.vertex.failWith("safety", true);
    await setConfig({ image: { provider: "vertex", model: "gemini-3.1-flash-lite-image", fallbackProvider: "fakepix", fallbackModel: "fake-draw" } });
    const res = await generateRecipe(t.deps, callerFor(UID), request({ image: "dish" }));
    expect(res).toMatchObject({ status: "DONE", imageFailed: true });
    expect(t.q.calls).toHaveLength(0);
  });

  test("the default setup still reserves and books exactly as before (no implicit fallback added)", () => {
    // Pictures off keep clearing the fallback model, as before the refactor.
    expect(parseRuntimeConfig({ image: { provider: "off" } }).image).toEqual({ provider: "off", model: "gemini-3.1-flash-lite-image", fallbackModel: "", maxStepImages: 2 });
    expect(parseRuntimeConfig({}).image).toEqual({ provider: "vertex", model: "gemini-3.1-flash-lite-image", fallbackModel: "gemini-nano-banana-2.1", maxStepImages: 2 });
    expect(planFor(validateGenerateRequest(request({ image: "dish" }), NOW), DEFAULT_RUNTIME_CONFIG).image).toMatchObject({ reservedMicroUsd: 60_000 });
  });

  test("sampleProviderPng is what the fake answers", async () => {
    expect((await sampleProviderPng()).length).toBeGreaterThan(0);
  });
});

describe("the balance probe", () => {
  test("logs provider_balance_low under the threshold, nothing above it, and a fixed line when it fails", async () => {
    const logs: { level: string; message: string; data?: Record<string, unknown> }[] = [];
    const logger = {
      info: (message: string, data?: Record<string, unknown>) => logs.push({ level: "info", message, data }),
      warn: (message: string, data?: Record<string, unknown>) => logs.push({ level: "warn", message, data }),
      error: (message: string, data?: Record<string, unknown>) => logs.push({ level: "error", message, data }),
    };
    const run = (credits: unknown, thresholds: Record<string, number>, status = 200) => {
      const q = fetchQueue([{ status, body: { credits } }]);
      return { q, out: checkProviderBalances({ providers: [fakeText, fakeImage], thresholds, context: (d) => contextFor(d, q.fetch), logger }) };
    };
    let r = run(12.5, { fakepix: 50 });
    expect(await r.out).toEqual([{ provider: "fakepix", outcome: "low", amount: 12.5, unit: "credits", threshold: 50 }]);
    expect(r.q.calls[0].url).toBe(FAKE_BALANCE_URL);
    expect(logs.pop()).toEqual({ level: "error", message: "provider_balance_low", data: { provider: "fakepix", amount: 12.5, unit: "credits", threshold: 50 } });
    r = run(80, { fakepix: 50 });
    expect((await r.out)[0].outcome).toBe("ok");
    expect(logs).toHaveLength(0);
    r = run(0, { fakepix: 50 }, 500);
    expect((await r.out)[0].outcome).toBe("unavailable");
    expect(logs.pop()).toEqual({ level: "warn", message: "provider_balance_unavailable", data: { provider: "fakepix", reason: "FAILED" } });
    // No threshold, or no probe: nothing is called.
    r = run(1, { fakeeu: 5 });
    expect(await r.out).toEqual([]);
    expect(r.q.calls).toHaveLength(0);
    expect(JSON.stringify(logs)).not.toContain(KEY);
  });

  test("config/runtime.providerBalanceAlerts is parsed fail-safe", () => {
    expect(parseRuntimeConfig({}).providerBalanceAlerts).toEqual({});
    expect(parseRuntimeConfig({ providerBalanceAlerts: { fakepix: 50 } }).providerBalanceAlerts).toEqual({ fakepix: 50 });
    expect(parseRuntimeConfig({ providerBalanceAlerts: { fakepix: -1 } }).providerBalanceAlerts).toEqual({});
  });
});
