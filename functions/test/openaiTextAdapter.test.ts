/**
 * OpenAI as a recipe text provider (src/adapters/openaiText.ts, README "OpenAI for the recipe
 * text"): the Responses API body against a fake fetch (strict JSON schema, the ceiling as
 * max_output_tokens, no identifier of the person, store off), the answer read back (usage with
 * reasoning as thinking, nulls for optional fields dropped), every failure mapped to the shared
 * taxonomy with fixed messages, the registry entry (one key shared with the pictures, not approved),
 * and the whole path through generateRecipe: booked at OpenAI's prices, out of credit to Gemini.
 */
import { randomUUID } from "node:crypto";
import { JUDGE, JUDGE_PRICES } from "../src/constants";
import { buildPrompt, type Prompt } from "../src/adapters/prompt";
import { OPENAI_EXHAUSTED, OPENAI_RESPONSES_URL, OPENAI_SECRET, OpenAITextGenerator, openAIResponsesBody, openaiText, strictSchema, withoutNulls } from "../src/adapters/openaiText";
import { openaiImage } from "../src/adapters/image";
import { validateGenerateRequest, type GenerateRequest } from "../src/contract";
import { generateRecipe, spendMicroUsd } from "../src/core/generation";
import { emptyUser, parseRuntimeConfig, textReservePricesOf, utcDay } from "../src/model";
import { GenerationError, type Deps, type RecipeGenerator } from "../src/ports";
import { providerRegistryProblems, providerSecrets, textProviders } from "../src/providers/registry";
import { TextRouter } from "../src/providers/router";
import type { ProviderContext } from "../src/providers/types";
import { callerFor, db, makeDeps, resetEmulators, sampleMenu, sampleRecipe, setConfig, type TestDeps } from "./helpers";

const KEY = "sk-openai-fake-never-logged-456";
const NOW = Date.now();
const UID = "openai-text-user";
const LIMITS = { maxOutputTokens: 4000, hardTokenCeiling: 8000 };
const TARGET = { provider: "openai", model: "gpt-6.1-terra" };

type Reply = { status: number; body: unknown; headers?: Record<string, string> };

function fetchQueue(replies: Reply[]): { fetch: typeof fetch; calls: { url: string; init: RequestInit }[] } {
  const calls: { url: string; init: RequestInit }[] = [];
  const f = (async (url: string, init: RequestInit) => {
    calls.push({ url: String(url), init });
    const r = replies.shift();
    if (!r) throw new Error("no reply queued");
    return new Response(typeof r.body === "string" ? r.body : JSON.stringify(r.body), { status: r.status, headers: r.headers });
  }) as unknown as typeof fetch;
  return { fetch: f, calls };
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

const validated = (over: Record<string, unknown> = {}): GenerateRequest => validateGenerateRequest(request(over), NOW);
const promptOf = (req: GenerateRequest): Prompt => buildPrompt(req, "CANARY-0000");

/** A completed Responses API answer: a reasoning item, then the message with the JSON. */
function answer(output: unknown, over: Record<string, unknown> = {}, usage: Record<string, unknown> | null = { input_tokens: 1200, output_tokens: 900, output_tokens_details: { reasoning_tokens: 300 } }) {
  return {
    id: "resp_1",
    object: "response",
    model: "gpt-6.1-terra-2026-10-26",
    status: "completed",
    output: [
      { type: "reasoning", id: "rs_1", summary: [] },
      { type: "message", id: "msg_1", role: "assistant", content: [{ type: "output_text", text: JSON.stringify(output), annotations: [] }] },
    ],
    ...(usage ? { usage } : {}),
    ...over,
  };
}

const generator = (f: typeof fetch, key: string | null = KEY, effort?: string) =>
  new OpenAITextGenerator({ apiKey: () => (key ? Promise.resolve(key) : Promise.reject(new Error("unreadable"))), fetch: f, ...(effort ? { reasoningEffort: effort } : {}) });

async function failure(p: Promise<unknown>): Promise<GenerationError> {
  try {
    await p;
  } catch (e) {
    if (e instanceof GenerationError) return e;
    throw e;
  }
  throw new Error("no failure");
}

describe("the request", () => {
  test("Responses API, strict JSON schema, the ceiling as max_output_tokens, low reasoning, nothing of the person, not stored", async () => {
    const req = validated();
    const prompt = promptOf(req);
    const q = fetchQueue([{ status: 200, body: answer(sampleRecipe()) }]);
    await generator(q.fetch).generate(req, prompt, new AbortController().signal, LIMITS, TARGET);
    expect(q.calls).toHaveLength(1);
    expect(q.calls[0].url).toBe(OPENAI_RESPONSES_URL);
    const init = q.calls[0].init;
    expect(init.method).toBe("POST");
    expect((init.headers as Record<string, string>).authorization).toBe(`Bearer ${KEY}`);
    expect(init.redirect).toBe("error");
    const body = JSON.parse(String(init.body));
    expect(body).toEqual({
      model: "gpt-6.1-terra",
      instructions: prompt.systemInstruction,
      input: [{ role: "user", content: prompt.userText }],
      text: { format: { type: "json_schema", name: "recipe", strict: true, schema: strictSchema(prompt.schema) } },
      max_output_tokens: LIMITS.hardTokenCeiling,
      reasoning: { effort: "low" },
      store: false,
    });
    for (const k of ["temperature", "user", "safety_identifier", "prompt_cache_key", "metadata", "tools", "stream"]) expect(body).not.toHaveProperty(k);
    expect(openAIResponsesBody(validated({ kind: "menu", courses: 2 }), prompt, LIMITS, "gpt-6-luna", "none")).toMatchObject({ model: "gpt-6-luna", reasoning: { effort: "none" }, text: { format: { name: "menu" } } });
    // An effort the API doesn't take is low.
    const q2 = fetchQueue([{ status: 200, body: answer(sampleRecipe()) }]);
    await generator(q2.fetch, KEY, "extreme").generate(req, prompt, new AbortController().signal, LIMITS, TARGET);
    expect(JSON.parse(String(q2.calls[0].init.body)).reasoning).toEqual({ effort: "low" });
  });

  test("the strict schema: every object closed and fully required, optional fields nullable, the input untouched", () => {
    const req = validated({ kind: "menu", courses: 3 });
    const schema = promptOf(req).schema;
    const before = JSON.stringify(schema);
    const strict = strictSchema(schema);
    expect(JSON.stringify(schema)).toBe(before);
    const objects: Record<string, unknown>[] = [];
    const walk = (s: unknown) => {
      if (Array.isArray(s)) return s.forEach(walk);
      if (!s || typeof s !== "object") return;
      const o = s as Record<string, unknown>;
      if (o.type === "object" || (Array.isArray(o.type) && o.type.includes("object"))) objects.push(o);
      Object.values(o).forEach(walk);
    };
    walk(strict);
    expect(objects.length).toBeGreaterThanOrEqual(5);
    for (const o of objects) {
      expect(o.additionalProperties).toBe(false);
      expect(o.required).toEqual(Object.keys(o.properties as object));
    }
    // `tip` was optional: now a union with null; the enum of ids gains nothing (it was required).
    const recipe = promptOf(validated()).schema;
    const s = strictSchema(recipe) as { properties: Record<string, { type: unknown; items?: { properties: Record<string, { enum?: unknown[] }> } }> };
    expect(s.properties.tip.type).toEqual(["string", "null"]);
    expect(s.properties.title.type).toBe("string");
    expect(s.properties.ingredients.items!.properties.pantryId.enum).not.toContain(null);
    expect(strictSchema({ type: "object", properties: { unit: { type: "string", enum: ["F", "C"] } } })).toEqual({
      type: "object", properties: { unit: { type: ["string", "null"], enum: ["F", "C", null] } }, required: ["unit"], additionalProperties: false,
    });
    // Within strict mode's documented limits: at most 1000 enum values, 10 levels of nesting.
    let enums = 0;
    const count = (v: unknown, depth: number): number => {
      if (Array.isArray(v)) return Math.max(depth, ...v.map((x) => count(x, depth)));
      if (!v || typeof v !== "object") return depth;
      const o = v as Record<string, unknown>;
      if (Array.isArray(o.enum)) enums += o.enum.length;
      const d = o.type === "object" ? depth + 1 : depth;
      return Math.max(d, ...Object.entries(o).filter(([k]) => k !== "enum").map(([, x]) => count(x, d)));
    };
    expect(count(strict, 0)).toBeLessThanOrEqual(10);
    expect(enums).toBeLessThanOrEqual(1000);
  });
});

describe("the answer", () => {
  test("the JSON, nulls for optional fields dropped, usage with reasoning as thinking", async () => {
    const req = validated();
    const prompt = promptOf(req);
    const out = { ...sampleRecipe(), tip: null };
    const q = fetchQueue([{ status: 200, body: answer(out) }]);
    const r = await generator(q.fetch).generate(req, prompt, new AbortController().signal, LIMITS, TARGET);
    expect(r.output).toEqual(sampleRecipe());
    expect(r.usage).toEqual({ inputTokens: 1200, outputTokens: 600, thinkingTokens: 300, model: "gpt-6.1-terra-2026-10-26", finishReason: "completed" });
    // A tip that is there stays; a menu's nested recipes lose their null tips too.
    expect(withoutNulls({ ...sampleRecipe(), tip: "Salt the water." }, prompt.schema)).toMatchObject({ tip: "Salt the water." });
    const menuReq = validated({ kind: "menu", courses: 2 });
    const menu = sampleMenu(menuReq);
    const withNulls = { days: menu.days.map((d) => ({ ...d, courses: d.courses.map((c) => ({ ...c, recipe: { ...c.recipe, tip: null } })) })) };
    expect(withoutNulls(withNulls, promptOf(menuReq).schema)).toEqual(menu);
    // A null where a value is required is left for the core's own checks.
    expect(withoutNulls({ ...sampleRecipe(), title: null }, prompt.schema)).toMatchObject({ title: null });
  });

  test("no usage reported: the worst case stays booked (usage absent)", async () => {
    const q = fetchQueue([{ status: 200, body: answer(sampleRecipe(), {}, null) }]);
    const r = await generator(q.fetch).generate(validated(), promptOf(validated()), new AbortController().signal, LIMITS, TARGET);
    expect(r.usage).toBeUndefined();
  });

  test("cut off, filtered, refused, failed, empty or not JSON: content or infra failures with fixed messages", async () => {
    const req = validated();
    const prompt = promptOf(req);
    const run = (body: unknown) => failure(generator(fetchQueue([{ status: 200, body }]).fetch).generate(req, prompt, new AbortController().signal, LIMITS, TARGET));
    let e = await run(answer(sampleRecipe(), { status: "incomplete", incomplete_details: { reason: "max_output_tokens" } }));
    expect(e).toMatchObject({ failureClass: "content", message: "finish incomplete:max_output_tokens", safety: undefined, usage: { finishReason: "incomplete:max_output_tokens", thinkingTokens: 300 } });
    expect(e.providerKind).toBe("content");
    e = await run(answer(sampleRecipe(), { status: "incomplete", incomplete_details: { reason: "content_filter" } }));
    expect(e).toMatchObject({ failureClass: "content", safety: "FINISH_CONTENT_FILTER" });
    expect(e.providerKind).toBe("safety");
    e = await run({ ...answer(sampleRecipe()), output: [{ type: "message", content: [{ type: "refusal", refusal: "I can't help with that, see example.com" }] }] });
    expect(e).toMatchObject({ failureClass: "content", message: "refusal", safety: "FINISH_REFUSAL" });
    expect(e.message).not.toContain("example");
    e = await run(answer(sampleRecipe(), { status: "failed", error: { code: "invalid_prompt", message: "quoted prompt text" } }));
    expect(e).toMatchObject({ failureClass: "content", safety: "PROMPT_BLOCKED", message: "prompt blocked" });
    e = await run(answer(sampleRecipe(), { status: "failed", error: { code: "server_error", message: "x" } }));
    expect(e).toMatchObject({ failureClass: "infra", message: "OpenAI failed" });
    e = await run({ ...answer(sampleRecipe()), output: [] });
    expect(e).toMatchObject({ failureClass: "content", message: "empty response" });
    e = await run({ ...answer(sampleRecipe()), output: [{ type: "message", content: [{ type: "output_text", text: "{not json" }] }] });
    expect(e).toMatchObject({ failureClass: "content", message: "response is not JSON" });
    e = await run(answer(sampleRecipe(), { status: "in_progress" }));
    expect(e).toMatchObject({ failureClass: "infra" });
    // Text far past the ceiling: infra, no usage (the worst case booked).
    e = await run({ ...answer(sampleRecipe()), output: [{ type: "message", content: [{ type: "output_text", text: "x".repeat(LIMITS.hardTokenCeiling * 8 + 1) }] }] });
    expect(e).toMatchObject({ failureClass: "infra", message: "token ceiling exceeded", usage: undefined });
    // A body that is not JSON at all.
    e = await run("<html>oops</html>");
    expect(e).toMatchObject({ failureClass: "infra", message: "OpenAI answer unreadable", usage: undefined });
  });
});

describe("refused before anything was generated", () => {
  const cases: [number, Record<string, unknown>, string, string][] = [
    [401, { error: { type: "invalid_request_error", code: "invalid_api_key" } }, "infra", "config"],
    [403, { error: { code: "unsupported_country_region_territory" } }, "infra", "config"],
    [404, { error: { code: "model_not_found" } }, "infra", "config"],
    [402, {}, "infra", "out_of_credit"],
    [429, { error: { type: "insufficient_quota", code: "insufficient_quota" } }, "infra", "out_of_credit"],
    [429, { error: { code: "credit_balance_exhausted" } }, "infra", "out_of_credit"],
    [429, { error: { code: "project_spend_limit_exceeded" } }, "infra", "out_of_credit"],
    [429, { error: { code: "organization_usage_limit_exceeded" } }, "infra", "out_of_credit"],
    [429, { error: { type: "rate_limit_error", code: "slow_down" } }, "infra", "capacity"],
    [429, { error: { message: "Rate limit reached for requests" } }, "infra", "capacity"],
    [500, {}, "infra", "capacity"],
    [503, { error: { type: "service_unavailable_error", code: "server_is_overloaded" } }, "infra", "capacity"],
    [400, { error: { type: "invalid_request_error", code: "invalid_json_schema" } }, "infra", "infra"],
    [400, { error: { type: "invalid_request_error", code: "invalid_prompt", message: "your prompt about X" } }, "content", "safety"],
  ];
  test.each(cases)("HTTP %i %j: %s / %s, known zero usage, a fixed message", async (status, body, failureClass, kind) => {
    const q = fetchQueue([{ status, body, headers: { "retry-after": "2" } }]);
    const e = await failure(generator(q.fetch).generate(validated(), promptOf(validated()), new AbortController().signal, LIMITS, TARGET));
    expect(e.failureClass).toBe(failureClass);
    expect(e.providerKind).toBe(kind);
    expect(e.usage).toEqual({ inputTokens: 0, outputTokens: 0, thinkingTokens: 0, model: "gpt-6.1-terra", finishReason: null });
    expect(e.message).toMatch(/^(OpenAI \d{3}|prompt blocked)$/);
  });

  test("no key: a config failure, nothing sent; a network failure or a timeout: unknown usage", async () => {
    const q = fetchQueue([]);
    const e = await failure(generator(q.fetch, null).generate(validated(), promptOf(validated()), new AbortController().signal, LIMITS, TARGET));
    expect(e).toMatchObject({ failureClass: "infra", kind: "config", message: "OpenAI API key unavailable", usage: { outputTokens: 0 } });
    expect(q.calls).toHaveLength(0);
    const broken = (async () => { throw new TypeError("fetch failed"); }) as unknown as typeof fetch;
    expect(await failure(generator(broken).generate(validated(), promptOf(validated()), new AbortController().signal, LIMITS, TARGET)))
      .toMatchObject({ failureClass: "infra", message: "OpenAI unreachable", usage: undefined });
    const ac = new AbortController();
    const hang = ((_u: string, init: RequestInit) => new Promise((_r, reject) => {
      if (init.signal!.aborted) return reject(new Error("aborted"));
      init.signal!.addEventListener("abort", () => reject(new Error("aborted")));
    })) as unknown as typeof fetch;
    const p = failure(generator(hang).generate(validated(), promptOf(validated()), ac.signal, LIMITS, TARGET));
    setTimeout(() => ac.abort(), 5);
    expect(await p).toMatchObject({ failureClass: "infra", message: "timeout", usage: undefined });
  });

  test("the exhausted codes are shared with the picture adapter", () => {
    for (const c of ["insufficient_quota", "credit_balance_exhausted", "organization_spend_limit_exceeded", "project_spend_limit_exceeded", "organization_usage_limit_exceeded"]) expect(OPENAI_EXHAUSTED.test(c)).toBe(true);
    for (const c of ["slow_down", "rate_limit_exceeded", ""]) expect(OPENAI_EXHAUSTED.test(c)).toBe(false);
  });
});

describe("the registry entry", () => {
  test("priced, sound, one key shared with the pictures, listed but not approved", () => {
    expect(providerRegistryProblems()).toEqual([]);
    expect(textProviders().map((d) => d.id)).toEqual(["google", "openai"]);
    expect(openaiText.prices).toEqual({ "gpt-6.1-terra": { inputUsdPerMTok: 1, outputUsdPerMTok: 5 }, "gpt-6-luna": { inputUsdPerMTok: 0.1, outputUsdPerMTok: 0.5 } });
    expect(openaiText.defaultModel).toBe("gpt-6.1-terra");
    expect(openaiText.secret).toBe(OPENAI_SECRET);
    expect(openaiImage.secret).toBe(OPENAI_SECRET);
    expect(providerSecrets().filter((s) => s.id === "openai")).toHaveLength(1);
    expect(openaiText.privacy.approved).toBe(false);
    expect(openaiText.privacy.missing).toContain("privacy policy");
    // Selectable by config/runtime (by hand) and priced from its table; the default is unchanged.
    expect(parseRuntimeConfig({ text: { provider: "openai" } }).text).toEqual({ provider: "openai", model: "gpt-6.1-terra", fallbackProvider: "", fallbackModel: "" });
    expect(parseRuntimeConfig({}).text.provider).toBe("google");
    // Out of credit goes to the Gemini default, so the reservation keeps Gemini's dearer prices.
    expect(textReservePricesOf(parseRuntimeConfig({ text: { provider: "openai" } }))).toEqual({ inputUsdPerMTok: 1.5, outputUsdPerMTok: 7.5 });
  });

  test("create reads its effort param and its key lazily", async () => {
    let asked = 0;
    const ctx: ProviderContext = {
      project: "p",
      param: (n) => (n === "OPENAI_REASONING_EFFORT" ? "medium" : ""),
      secret: async () => { asked++; return KEY; },
      fetch: fetchQueue([{ status: 200, body: answer(sampleRecipe()) }]).fetch,
    };
    const adapter = openaiText.create(ctx);
    expect(asked).toBe(0);
    await adapter.generate(validated(), promptOf(validated()), new AbortController().signal, LIMITS, TARGET);
    expect(asked).toBe(1);
  });
});

// --- Through generateRecipe (the emulators) --------------------------------------------------

/** Deps whose generator is a TextRouter over the registry, as src/index.ts builds it (Vertex is the FakeGenerator). */
function routed(f: typeof fetch): TestDeps {
  const deps = makeDeps();
  const google = deps.generator;
  const ctx = (d: (typeof textProviders extends () => readonly (infer T)[] ? T : never)): ProviderContext => ({
    project: "demo",
    param: (n) => d.params?.find((p) => p.name === n)?.default ?? "",
    secret: async () => KEY,
    fetch: f,
  });
  const factories = Object.fromEntries(textProviders().map((d) => [d.id, () => (d.id === "google" ? google : d.create(ctx(d)))]));
  (deps as Deps).generator = new TextRouter(factories, { provider: "google", model: "gemini-3.8-flash" }) as RecipeGenerator;
  return deps;
}

const gen = async (requestId: string) => (await db.collection("users").doc(UID).collection("generations").doc(requestId).get()).data()!;

describe("generateRecipe through OpenAI", () => {
  beforeEach(async () => {
    await resetEmulators();
    await db.collection("users").doc(UID).set({ ...emptyUser(), balance: 5, purchasedCredits: 5 });
  });

  test("its answer is delivered, booked at OpenAI's prices (the judge at its own), and logged by provider", async () => {
    const q = fetchQueue([{ status: 200, body: answer(sampleRecipe(), {}, { input_tokens: 2000, output_tokens: 700, output_tokens_details: { reasoning_tokens: 200 } }) }]);
    const deps = routed(q.fetch);
    await setConfig({ text: { provider: "openai", model: "gpt-6.1-terra" }, outputJudge: "shadow" });
    const r = request({ ingredients: [{ name: "Pasta", expiresOn: "2030-01-01", quantity: 1 }] });
    expect(await generateRecipe(deps, callerFor(UID), r)).toMatchObject({ status: "DONE", balance: 4 });
    expect(q.calls[0].url).toBe(OPENAI_RESPONSES_URL);
    const g = await gen(r.requestId);
    const judge = deps.judge.usage!;
    expect(g.actualMicroUsd).toBe(spendMicroUsd([
      { inputUsdPerMTok: 1, outputUsdPerMTok: 5, inputTokens: 2000, outputTokens: 700 },
      { ...JUDGE_PRICES[JUDGE.model], inputTokens: judge.inputTokens, outputTokens: judge.outputTokens + judge.thinkingTokens },
    ]));
    expect(g).toMatchObject({ textProvider: "openai" });
    expect(deps.logs.find((l) => l.message === "llm_call")?.data).toMatchObject({ provider: "openai", textFallback: false, outcome: "done" });
    expect(JSON.stringify(deps.logs)).not.toContain(KEY);
  });

  test("out of credit: logged without OpenAI's text, then the default provider (Gemini) writes it", async () => {
    const q = fetchQueue([{ status: 429, body: { error: { code: "credit_balance_exhausted", message: "Add credits at example" } } }]);
    const deps = routed(q.fetch);
    await setConfig({ text: { provider: "openai" } });
    const r = request();
    expect(await generateRecipe(deps, callerFor(UID), r)).toMatchObject({ status: "DONE", balance: 4 });
    expect(deps.logs.filter((l) => l.message === "provider_out_of_credit")).toEqual([
      { level: "error", message: "provider_out_of_credit", data: { provider: "openai", model: "gpt-6.1-terra", surface: "text" } },
    ]);
    expect(JSON.stringify(deps.logs)).not.toContain("Add credits");
    expect(deps.logs.find((l) => l.message === "llm_call")?.data).toMatchObject({ provider: "google", textFallback: true });
  });

  test("a policy refusal is a prompt rejection, never sent to the fallback", async () => {
    const q = fetchQueue([{ status: 400, body: { error: { code: "invalid_prompt" } } }]);
    const deps = routed(q.fetch);
    await setConfig({ text: { provider: "openai", fallbackProvider: "google", fallbackModel: "gemini-3.8-flash" } });
    const r = request();
    expect(await generateRecipe(deps, callerFor(UID), r)).toMatchObject({ status: "FAILED" });
    expect(deps.logs.some((l) => l.message === "text_fallback")).toBe(false);
    expect((await db.collection("users").doc(UID).get()).data()?.promptRejections).toMatchObject({ count: 1 });
  });
});
