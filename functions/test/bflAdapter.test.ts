/**
 * Black Forest Labs (FLUX) as a picture provider (src/adapters/bfl.ts, README "FLUX by Black Forest
 * Labs"): the submit, poll and download against a fake fetch; every failure mapped to the shared
 * taxonomy; the hosts the key and the picture may travel to; the size cap; the cost booked; the
 * fallback to Google pictures through the core's own rules; the balance probe; that the registry
 * entry and the profile are sound and switchable once the key is readable (privacy approved by
 * the owner on 2026-11-18); and the longer first attempt while BFL is the selected model.
 */
import { BFL_SAFETY_TOLERANCE, BflImageGenerator, bflBody, bflImage, bflUrl, BFL_HOSTS } from "../src/adapters/bfl";
import { makeRecipeImage, imagePromptFor } from "../src/core/recipeImage";
import { IMAGE } from "../src/constants";
import { DEFAULT_RUNTIME_CONFIG, parseRuntimeConfig } from "../src/model";
import { ImageGenerationError, type Deps, type GeneratedImage } from "../src/ports";
import { checkProviderBalances } from "../src/providers/balance";
import { PROVIDER_PROFILES, readinessOf, registryProblems } from "../src/providers/profiles";
import { imageProviderIds, imageTimingOf, imageProviders, providerRegistryProblems, providerSecrets, registerProviderForTests } from "../src/providers/registry";
import { ImageRouter } from "../src/providers/router";
import type { ProviderContext } from "../src/providers/types";
import { SAMPLE_BRIEF, makeDeps, sampleProviderJpeg } from "./helpers";

const KEY = "bfl-test-key-never-logged-42";
const SUBMIT = "https://api.eu.bfl.ai/v1/flux-3-image-klein";
const POLL = "https://api.eu9.bfl.ai/v1/get_result?id=task-1";
const SAMPLE = "https://delivery.eu9.bfl.ai/durable/2026111720/task-1/sample.jpg?se=x&sig=y";
const FLASH_LITE = "gemini-3.1-flash-lite-image";
const PROMPT = imagePromptFor(SAMPLE_BRIEF);

type Reply = { status: number; body?: unknown; headers?: Record<string, string>; bytes?: Buffer };
type Call = { url: string; init: RequestInit };

/** A fetch answering queued replies in order (JSON bodies, or raw bytes for the download). */
function fetchQueue(replies: Reply[]): { fetch: typeof fetch; calls: Call[] } {
  const calls: Call[] = [];
  const f = (async (url: string, init: RequestInit = {}) => {
    calls.push({ url: String(url), init });
    if (init.signal?.aborted) throw new DOMException("aborted", "AbortError");
    const r = replies.shift();
    if (!r) throw new Error("no reply queued");
    const body = r.bytes ? new Uint8Array(r.bytes) : r.body === undefined ? null : JSON.stringify(r.body);
    return new Response(body, { status: r.status, headers: r.headers });
  }) as unknown as typeof fetch;
  return { fetch: f, calls };
}

const submitted = (over: Record<string, unknown> = {}): Reply => ({ status: 200, body: { id: "task-1", polling_url: POLL, cost: 0.9, ...over } });
const poll = (status: string, over: Record<string, unknown> = {}): Reply => ({ status: 200, body: { id: "task-1", status, result: null, ...over } });
const ready = (sample = SAMPLE, over: Record<string, unknown> = {}): Reply => poll("Ready", { result: { sample, prompt: "expanded", duration: 4.5 }, ...over });
const picture = async (): Promise<Reply> => ({ status: 200, bytes: await sampleProviderJpeg(), headers: { "content-type": "image/jpeg" } });

/** The adapter with instant pauses (recorded), as the descriptor builds it otherwise. */
function adapter(f: typeof fetch, opts: { key?: string | null; region?: string } = {}) {
  const pauses: number[] = [];
  const gen = new BflImageGenerator({
    apiKey: () => (opts.key === null ? Promise.reject(new Error("unreadable")) : Promise.resolve(opts.key ?? KEY)),
    region: opts.region ?? "eu",
    fetch: f,
    sleep: async (ms, signal) => {
      pauses.push(ms);
      if (signal.aborted) throw new Error("aborted");
    },
  });
  return { gen, pauses };
}

const target = (model = "flux-3-image-klein") => ({ provider: "bfl", model });
const run = (gen: BflImageGenerator, signal = new AbortController().signal, model?: string) =>
  gen.generate(PROMPT, target(model), signal).then(
    (r) => r,
    (e: unknown) => e as ImageGenerationError,
  );

function contextFor(f: typeof fetch, key: string | null = KEY, region = "eu"): ProviderContext {
  return {
    project: "demo",
    param: (n) => (n === "BFL_REGION" ? region : ""),
    secret: () => (key ? Promise.resolve(key) : Promise.reject(new Error("secret unavailable"))),
    fetch: f,
  };
}

const headerOf = (c: Call, name: string) => new Headers(c.init.headers as ConstructorParameters<typeof Headers>[0]).get(name);

describe("the request", () => {
  test("submits the production picture prompt unchanged to the EU endpoint, then polls the URL it was given and downloads without the key", async () => {
    const q = fetchQueue([submitted(), poll("Pending"), poll("Generating"), ready(), await picture()]);
    const { gen, pauses } = adapter(q.fetch);
    const r = (await run(gen)) as GeneratedImage;
    expect(r.bytes.equals(await sampleProviderJpeg())).toBe(true);
    expect(r).toMatchObject({ mimeType: "image/jpeg", model: "flux-3-image-klein" });
    expect(q.calls.map((c) => c.url)).toEqual([SUBMIT, POLL, POLL, POLL, SAMPLE]);
    const [submit, firstPoll, , , download] = q.calls;
    expect(submit.init.method).toBe("POST");
    expect(headerOf(submit, "x-key")).toBe(KEY);
    expect(JSON.parse(String(submit.init.body))).toEqual({
      prompt: PROMPT.text,
      aspect_ratio: "4:3",
      resolution: "1k",
      safety_tolerance: 2,
      output_format: "jpeg",
    });
    expect(headerOf(firstPoll, "x-key")).toBe(KEY);
    // The picture's host gets no key, and no redirect is followed anywhere.
    expect(headerOf(download, "x-key")).toBeNull();
    expect(q.calls.every((c) => c.init.redirect === "error")).toBe(true);
    // Polling backs off: 500 ms, then by half up to 1.5 s.
    expect(pauses).toEqual([500, 750, 1125]);
  });

  test("each model gets only its own fields; never a user id or a webhook", () => {
    expect(bflBody(PROMPT, "flux-3-image")).toEqual({ prompt: PROMPT.text, aspect_ratio: "4:3", resolution: "1k", safety_tolerance: BFL_SAFETY_TOLERANCE, grounding: false });
    for (const m of ["flux-3-image", "flux-3-image-klein"] as const) {
      const body = bflBody(PROMPT, m);
      expect(body).not.toHaveProperty("user");
      expect(body).not.toHaveProperty("webhook_url");
      expect(body.prompt).toBe(PROMPT.text);
    }
  });

  test("an unknown model, region or missing key is a config failure with nothing sent", async () => {
    const q = fetchQueue([]);
    expect(await run(adapter(q.fetch).gen, undefined, "flux-dev")).toMatchObject({ kind: "config", billed: false });
    expect(await run(adapter(q.fetch, { region: "asia" }).gen)).toMatchObject({ kind: "config", billed: false });
    expect(await run(adapter(q.fetch, { region: "__proto__" }).gen)).toMatchObject({ kind: "config", billed: false });
    expect(await run(adapter(q.fetch, { key: null }).gen)).toMatchObject({ kind: "config", message: "BFL API key unavailable" });
    expect(await run(adapter(q.fetch, { key: "" }).gen)).toMatchObject({ kind: "config" });
    expect(q.calls).toHaveLength(0);
  });

  test("the cost BFL answers is reported as micro-USD (the settled cost first), else nothing (the flat price is booked)", async () => {
    let q = fetchQueue([submitted({ cost: 0.9 }), ready(SAMPLE, { cost: 0.8 }), await picture()]);
    expect(((await run(adapter(q.fetch).gen)) as GeneratedImage).usage).toEqual({ inputTokens: 0, outputTextTokens: 0, outputImageTokens: 8000 });
    q = fetchQueue([submitted({ cost: 0.9 }), ready(), await picture()]);
    expect(((await run(adapter(q.fetch).gen)) as GeneratedImage).usage?.outputImageTokens).toBe(9000);
    q = fetchQueue([submitted({ cost: "lots" }), ready(), await picture()]);
    expect(((await run(adapter(q.fetch).gen)) as GeneratedImage).usage).toBeUndefined();
    q = fetchQueue([submitted({ cost: 10_000 }), ready(), await picture()]);
    expect(((await run(adapter(q.fetch).gen)) as GeneratedImage).usage).toBeUndefined();
  });

  test("a PNG answer (FLUX 3 Image has no format setting) is said to be one", async () => {
    const png = await (await import("sharp")).default({ create: { width: 8, height: 6, channels: 3, background: "#884422" } }).png().toBuffer();
    const q = fetchQueue([submitted({ cost: 4.8 }), ready(), { status: 200, bytes: png }]);
    expect(((await run(adapter(q.fetch).gen, undefined, "flux-3-image")) as GeneratedImage).mimeType).toBe("image/png");
    expect(q.calls[0].url).toBe("https://api.eu.bfl.ai/v1/flux-3-image");
  });
});

describe("failures, in the shared taxonomy", () => {
  test("402: out of credit, nothing made, nothing billed, not retried", async () => {
    const q = fetchQueue([{ status: 402, body: { detail: "Insufficient credits, see https://example" } }]);
    const e = await run(adapter(q.fetch).gen);
    expect(e).toBeInstanceOf(ImageGenerationError);
    expect(e).toMatchObject({ kind: "out_of_credit", billed: false, retryable: false, status: 402, message: "BFL 402" });
  });

  test("429 with Retry-After: capacity, retryable after at least that pause; 5xx capacity; 401/403 config; 422 refused", async () => {
    let q = fetchQueue([{ status: 429, body: { detail: "Too many active tasks" }, headers: { "retry-after": "7" } }]);
    let e = (await run(adapter(q.fetch).gen)) as ImageGenerationError;
    expect(e).toMatchObject({ kind: "infra", retryable: true, billed: false, status: 429, retryAfterMs: 7000 });
    expect(e.providerKind).toBe("capacity");
    q = fetchQueue([{ status: 503, body: {} }]);
    e = (await run(adapter(q.fetch).gen)) as ImageGenerationError;
    expect(e).toMatchObject({ kind: "infra", retryable: true, billed: true, status: 503 });
    for (const s of [401, 403]) {
      q = fetchQueue([{ status: s, body: {} }]);
      expect(await run(adapter(q.fetch).gen)).toMatchObject({ kind: "config", billed: false, status: s });
    }
    q = fetchQueue([{ status: 422, body: { detail: [{ msg: "quoted prompt" }] } }]);
    e = (await run(adapter(q.fetch).gen)) as ImageGenerationError;
    expect(e).toMatchObject({ kind: "infra", retryable: false, billed: false, status: 422 });
    expect(e.message).not.toContain("quoted");
  });

  test("moderation of the request or of the picture is a safety refusal, not billed", async () => {
    for (const status of ["Request Moderated", "Content Moderated"]) {
      const q = fetchQueue([submitted(), poll("Pending"), poll(status, { details: { "Moderation Reasons": ["Violence"] } })]);
      const e = (await run(adapter(q.fetch).gen)) as ImageGenerationError;
      expect(e).toMatchObject({ kind: "safety", billed: false, retryable: false });
      expect(e.message).not.toContain("Violence");
      expect(q.calls).toHaveLength(3);
    }
  });

  test("a failed task (also as a 503 with a JSON body) or an unknown status: infra, not billed; a poll rate limit just waits", async () => {
    let q = fetchQueue([submitted(), { status: 503, body: { id: "task-1", status: "Error", result: null } }]);
    expect(await run(adapter(q.fetch).gen)).toMatchObject({ kind: "infra", billed: false, message: "BFL task failed" });
    q = fetchQueue([submitted(), poll("Task not found")]);
    expect(await run(adapter(q.fetch).gen)).toMatchObject({ kind: "infra", billed: false, message: "BFL task not settled" });
    q = fetchQueue([submitted(), { status: 429, body: {}, headers: { "retry-after": "2" } }, poll("Generating"), ready(), await picture()]);
    const { gen, pauses } = adapter(q.fetch);
    expect(await run(gen)).toMatchObject({ mimeType: "image/jpeg" });
    expect(pauses[1]).toBe(2000);
    // Three unreadable polls in a row: given up, billed (the task may have run).
    q = fetchQueue([submitted(), { status: 502 }, { status: 502 }, { status: 502 }]);
    expect(await run(adapter(q.fetch).gen)).toMatchObject({ kind: "infra", billed: true, message: "BFL poll 502" });
  });

  test("an abort mid-poll stops at once: a timeout, billed (the task may still be made and charged)", async () => {
    const q = fetchQueue([submitted(), poll("Pending"), poll("Generating"), poll("Generating"), poll("Generating")]);
    // The real pause (abortable), short: the abort lands while it waits.
    const gen = new BflImageGenerator({ apiKey: async () => KEY, region: "eu", fetch: q.fetch, pollMs: 40, pollMaxMs: 40 });
    const controller = new AbortController();
    const started = Date.now();
    setTimeout(() => controller.abort(), 100);
    const e = await gen.generate(PROMPT, target(), controller.signal).catch((x: unknown) => x);
    expect(e).toMatchObject({ kind: "infra", message: "timeout", billed: true });
    expect(Date.now() - started).toBeLessThan(1000);
    expect(q.calls.length).toBeLessThanOrEqual(4);
  });

  test("an error never carries the key or BFL's text", async () => {
    const q = fetchQueue([{ status: 400, body: { detail: `bad key ${KEY}` } }]);
    const e = (await run(adapter(q.fetch).gen)) as ImageGenerationError;
    expect(JSON.stringify({ m: e.message, ...e })).not.toContain(KEY);
  });
});

describe("where the key and the picture may travel", () => {
  test("a polling URL off BFL's API hosts of the region is refused before the key is sent there", async () => {
    for (const bad of [
      "https://evil.example/v1/get_result?id=1",
      "http://api.eu9.bfl.ai/v1/get_result?id=1",
      "https://api.us1.bfl.ai/v1/get_result?id=1",
      "https://api.eu9.bfl.ai.evil.example/v1/get_result",
      "https://user:pw@api.eu9.bfl.ai/v1/get_result",
      "https://api.eu9.bfl.ai:8443/v1/get_result",
      "not a url",
    ]) {
      const q = fetchQueue([submitted({ polling_url: bad })]);
      expect(await run(adapter(q.fetch).gen)).toMatchObject({ kind: "infra", message: "BFL polling host refused", billed: true });
      expect(q.calls).toHaveLength(1);
    }
  });

  test("a picture URL off BFL's delivery hosts of the region is refused, nothing downloaded", async () => {
    for (const bad of [
      "https://evil.example/sample.jpg",
      "http://delivery.eu9.bfl.ai/sample.jpg",
      "https://delivery.us2.bfl.ai/sample.jpg",
      "https://api.eu9.bfl.ai/sample.jpg",
      "https://delivery.eu9.bfl.ai.evil.example/sample.jpg",
      "file:///etc/passwd",
    ]) {
      const q = fetchQueue([submitted(), ready(bad)]);
      expect(await run(adapter(q.fetch).gen)).toMatchObject({ kind: "infra", message: "BFL delivery host refused", billed: true });
      expect(q.calls).toHaveLength(2);
    }
  });

  test("the host rules by region", () => {
    expect(bflUrl("https://delivery.eu1.bfl.ai/x", BFL_HOSTS.eu.delivery)).not.toBeNull();
    expect(bflUrl("https://delivery.bfl.ai/x", BFL_HOSTS.global.delivery)).not.toBeNull();
    expect(bflUrl("https://delivery.us1.bfl.ai/x", BFL_HOSTS.us.delivery)).not.toBeNull();
    expect(bflUrl("https://delivery.us1.bfl.ai/x", BFL_HOSTS.eu.delivery)).toBeNull();
    expect(bflUrl("https://api.eu.bfl.ai", BFL_HOSTS.eu.api)).not.toBeNull();
  });

  test("an oversized picture is refused: by its declared length, or while it streams", async () => {
    const big = Buffer.alloc(8 * 1024 * 1024 + 1, 0xff);
    let q = fetchQueue([submitted(), ready(), { status: 200, bytes: Buffer.from([0xff, 0xd8, 0xff]), headers: { "content-length": String(big.length) } }]);
    expect(await run(adapter(q.fetch).gen)).toMatchObject({ kind: "infra", message: "BFL picture too large", billed: true });
    q = fetchQueue([submitted(), ready(), { status: 200, bytes: big }]);
    expect(await run(adapter(q.fetch).gen)).toMatchObject({ kind: "infra", message: "BFL picture too large" });
    q = fetchQueue([submitted(), ready(), { status: 404, body: {} }]);
    expect(await run(adapter(q.fetch).gen)).toMatchObject({ kind: "infra", message: "BFL delivery 404", billed: true });
  });
});

describe("through the core: booking and the fallback to Google pictures", () => {
  const config = (over: Record<string, unknown> = {}) =>
    parseRuntimeConfig({ image: { provider: "bfl", model: "flux-3-image-klein", fallbackProvider: "vertex", fallbackModel: FLASH_LITE, ...over } });

  function routed(replies: Reply[]) {
    const q = fetchQueue(replies);
    const deps = makeDeps();
    const vertex = deps.imageGenerator;
    const bfl = new BflImageGenerator({ apiKey: async () => KEY, region: "eu", fetch: q.fetch, sleep: async () => undefined });
    (deps as Deps).imageGenerator = new ImageRouter({ vertex: () => vertex, bfl: () => bfl });
    return { deps, q, vertex };
  }
  const make = (deps: ReturnType<typeof makeDeps>, c = config()) => makeRecipeImage(deps, "u1", SAMPLE_BRIEF, c, deps.now() + 50_000);

  test("a FLUX picture is delivered and booked at the cost BFL answered", async () => {
    const { deps, vertex } = routed([submitted({ cost: 0.9 }), ready(), await picture()]);
    const out = await make(deps);
    expect(out).toMatchObject({ ok: true, microUsd: 9000, model: "flux-3-image-klein" });
    expect(vertex.calls).toBe(0);
    expect(deps.logs.find((l) => l.message === "image_call")?.data).toMatchObject({ provider: "bfl", outcome: "done", fallback: false, estimatedMicroUsd: 9000 });
    expect(JSON.stringify(deps.logs)).not.toContain(KEY);
  });

  test("without a cost in the answer the flat price (USD 0.01) is booked", async () => {
    const { deps } = routed([submitted({ cost: null }), ready(), await picture()]);
    expect(await make(deps)).toMatchObject({ ok: true, microUsd: 10_000 });
  });

  test("out of credit: logged, not retried, and Google's Flash-Lite Image makes it", async () => {
    const { deps, q, vertex } = routed([{ status: 402, body: {} }]);
    const out = await make(deps);
    expect(out).toMatchObject({ ok: true, model: FLASH_LITE });
    expect(q.calls).toHaveLength(1);
    expect(vertex.targets).toEqual([{ provider: "vertex", model: FLASH_LITE }]);
    expect(deps.logs.find((l) => l.message === "provider_out_of_credit")?.data).toEqual({ provider: "bfl", model: "flux-3-image-klein", surface: "image" });
    expect(deps.logs.find((l) => l.message === "image_fallback")?.data).toMatchObject({ fallbackProvider: "vertex", reason: "OUT_OF_CREDIT", outcome: "done" });
  });

  test("out of credit with no fallback named: the deploy default (Vertex) makes it", async () => {
    const { deps, vertex } = routed([{ status: 402, body: {} }]);
    expect(await make(deps, config({ fallbackProvider: "", fallbackModel: "" }))).toMatchObject({ ok: true });
    expect(vertex.targets).toEqual([{ provider: "vertex", model: FLASH_LITE }]);
  });

  test("capacity: retried after BFL's Retry-After while time is left, then Google", async () => {
    const busy = { status: 429, body: {}, headers: { "retry-after": "3" } };
    const { deps, q, vertex } = routed([busy, busy, busy, busy]);
    const out = await make(deps);
    expect(out).toMatchObject({ ok: true, model: FLASH_LITE });
    expect(deps.sleeps[0]).toBeGreaterThanOrEqual(3000);
    expect(q.calls.length).toBeGreaterThanOrEqual(2);
    expect(vertex.calls).toBe(1);
    expect(deps.logs.find((l) => l.message === "image_fallback")?.data).toMatchObject({ reason: "QUOTA", fallbackProvider: "vertex" });
  });

  test("a moderated picture is never sent to Google: no picture, nothing booked", async () => {
    const { deps, vertex } = routed([submitted(), poll("Content Moderated")]);
    expect(await make(deps)).toEqual({ ok: false, failure: "SAFETY", microUsd: 0, model: "flux-3-image-klein" });
    expect(vertex.calls).toBe(0);
    expect(deps.logs.some((l) => l.message === "image_fallback")).toBe(false);
  });

  test("a picture from a foreign host is not used; Google is asked once, and BFL's charge is booked", async () => {
    const { deps, vertex } = routed([submitted(), ready("https://evil.example/x.jpg")]);
    const out = await make(deps);
    expect(out).toMatchObject({ ok: true, model: FLASH_LITE });
    expect(vertex.calls).toBe(1);
    // BFL's flat price for the task it may have charged, plus Google's picture (FakeImageGenerator reports no tokens: its flat price).
    expect(out.microUsd).toBe(10_000 + 40_000);
  });
});

describe("registry, profile and balance", () => {
  test("one registry entry: priced, its secret declared, sound; the default setup is unchanged", () => {
    expect(providerRegistryProblems()).toEqual([]);
    expect(imageProviderIds()).toContain("bfl");
    expect(providerSecrets()).toContainEqual(expect.objectContaining({ id: "bfl", param: "BFL_API_KEY_SECRET", name: "BFL_API_KEY" }));
    expect(DEFAULT_RUNTIME_CONFIG.prices.imageUsdPerImage).toMatchObject({ "bfl/flux-3-image-klein": 0.01, "bfl/flux-3-image": 0.05 });
    expect(parseRuntimeConfig({}).image).toEqual({ provider: "vertex", model: FLASH_LITE, fallbackModel: "gemini-nano-banana-2.1", maxStepImages: 2 });
    expect(parseRuntimeConfig({ image: { provider: "bfl", model: "flux-3-image-klein" } }).image.provider).toBe("bfl");
  });

  test("the flux-eu profile: sound, Google pictures as its fallback, switchable exactly when the key is readable", async () => {
    expect(registryProblems()).toEqual([]);
    const p = PROVIDER_PROFILES.find((x) => x.id === "flux-eu")!;
    expect(p.image).toEqual({
      provider: "bfl",
      model: "flux-3-image-klein",
      fallbackProvider: "vertex",
      fallbackModel: FLASH_LITE,
      usdPerImage: { "bfl/flux-3-image-klein": 0.01, "vertex/gemini-3.1-flash-lite-image": 0.04 },
    });
    expect(p.text).toBeUndefined();
    // Privacy approved (owner's decision 2026-11-18): the readable key is its only requirement.
    expect(bflImage.privacy).toEqual({ approved: true, missing: "" });
    expect(p.requires).toEqual([{ kind: "secret", secret: "bfl", missing: bflImage.secret!.missing }]);
    expect(await readinessOf(p, async (id) => id === "bfl")).toEqual({ ready: true, missing: [] });
    expect(await readinessOf(p, async () => false)).toEqual({ ready: false, missing: [bflImage.secret!.missing] });
  });

  test("the balance probe asks the EU credits endpoint; credits under the threshold are low; a failure is unavailable", async () => {
    const logs: { level: string; message: string; data?: Record<string, unknown> }[] = [];
    const push = (level: string) => (message: string, data?: Record<string, unknown>) => logs.push({ level, message, data });
    const logger = { info: push("info"), warn: push("warn"), error: push("error") };
    const probe = (reply: Reply, threshold = 500) => {
      const q = fetchQueue([reply]);
      return { q, out: checkProviderBalances({ providers: [bflImage], thresholds: { bfl: threshold }, context: () => contextFor(q.fetch), logger }) };
    };
    let r = probe({ status: 200, body: { credits: 420.5 } });
    expect(await r.out).toEqual([{ provider: "bfl", outcome: "low", amount: 420.5, unit: "credits", threshold: 500 }]);
    expect(r.q.calls[0].url).toBe("https://api.eu.bfl.ai/v1/credits");
    expect(headerOf(r.q.calls[0], "x-key")).toBe(KEY);
    expect(logs.pop()).toEqual({ level: "error", message: "provider_balance_low", data: { provider: "bfl", amount: 420.5, unit: "credits", threshold: 500 } });
    r = probe({ status: 200, body: { credits: 871 } });
    expect((await r.out)[0].outcome).toBe("ok");
    expect(logs).toHaveLength(0);
    for (const bad of [{ status: 403, body: {} }, { status: 200, body: { credits: "many" } }]) {
      r = probe(bad);
      expect((await r.out)[0].outcome).toBe("unavailable");
      expect(logs.pop()).toEqual({ level: "warn", message: "provider_balance_unavailable", data: { provider: "bfl", reason: "FAILED" } });
    }
    expect(JSON.stringify(logs)).not.toContain(KEY);
  });

  test("the descriptor builds the adapter from its own param and secret, read only when called", async () => {
    let reads = 0;
    const q = fetchQueue([{ status: 402, body: {} }]);
    const ctx: ProviderContext = { ...contextFor(q.fetch), secret: async () => (reads++, KEY) };
    const gen = bflImage.create(ctx);
    expect(reads).toBe(0);
    expect(await gen.generate(PROMPT, target(), new AbortController().signal).catch((e: unknown) => e)).toMatchObject({ kind: "out_of_credit" });
    expect(reads).toBe(1);
    expect(q.calls[0].url).toBe(SUBMIT);
    const us = fetchQueue([{ status: 402, body: {} }]);
    await bflImage.create(contextFor(us.fetch, KEY, "us")).generate(PROMPT, target(), new AbortController().signal).catch(() => undefined);
    expect(us.calls[0].url).toBe("https://api.us.bfl.ai/v1/flux-3-image-klein");
  });
});

describe("the time each picture attempt gets", () => {
  const FLUX = "flux-3-image-klein";
  /** A provider that never answers: it rejects `lagMs` after the core's abort (an abort lands a little late in production). */
  function hanging(lagMs = 300) {
    const aborts: number[] = [];
    let started = 0;
    const gen = {
      calls: 0,
      async generate(_p: unknown, _t: unknown, signal: AbortSignal): Promise<GeneratedImage> {
        gen.calls++;
        started = Date.now();
        return new Promise((_r, reject) => {
          signal.addEventListener("abort", () => {
            aborts.push(Date.now() - started);
            setTimeout(() => reject(new ImageGenerationError("infra", "timeout", true)), lagMs);
          });
        });
      },
    };
    return { gen, aborts };
  }
  /** makeRecipeImage on a real-time clock (faked with the timers), within `budgetMs`. */
  async function timed(config: ReturnType<typeof parseRuntimeConfig>, primary: string, budgetMs: number, advanceMs: number) {
    const deps = makeDeps();
    const vertex = deps.imageGenerator;
    const slow = hanging();
    (deps as Deps).imageGenerator = new ImageRouter(primary === "vertex"
      ? { vertex: () => ({
          generate: (p, t, s) => (t.model === config.image.model ? slow.gen.generate(p, t, s) : vertex.generate(p, t, s)),
        }) }
      : { vertex: () => vertex, [primary]: () => slow.gen });
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "Date"] });
    try {
      const d = { ...deps, now: () => Date.now() };
      const pending = makeRecipeImage(d, "u1", SAMPLE_BRIEF, config, Date.now() + budgetMs);
      await vi.advanceTimersByTimeAsync(advanceMs);
      return { out: await pending, aborts: slow.aborts, vertex, deps };
    } finally {
      vi.useRealTimers();
    }
  }

  test("the defaults are unchanged for Vertex, OpenAI and off; only BFL has times of its own", () => {
    const defaults = { abortMs: 35_000, budgetMs: { dish: 50_000, steps: 100_000 }, reservationExtraMs: { dish: 55_000, steps: 105_000 } };
    expect({ abortMs: IMAGE.abortMs, budgetMs: IMAGE.budgetMs, reservationExtraMs: IMAGE.reservationExtraMs }).toEqual(defaults);
    for (const id of ["vertex", "openai", "off", "unknown"]) expect(imageTimingOf(id)).toEqual(defaults);
    expect(imageTimingOf("bfl")).toEqual({ abortMs: 45_000, budgetMs: { dish: 65_000, steps: 100_000 }, reservationExtraMs: { dish: 68_000, steps: 105_000 } });
    expect(imageProviders().filter((d) => d.timing).map((d) => d.id)).toEqual(["bfl"]);
  });

  test("the registry refuses times that leave the fallback no attempt or a reservation short of its budget", () => {
    const remove = registerProviderForTests({
      ...bflImage,
      id: "slowpoke",
      secret: undefined,
      timing: { abortMs: 50_000, budgetMs: { dish: 60_000, steps: 70_000 }, reservationExtraMs: { dish: 55_000, steps: 105_000 } },
    });
    try {
      const problems = providerRegistryProblems().join("\n");
      expect(problems).toContain("timing.budgetMs.dish must leave IMAGE.minAttemptMs");
      expect(problems).toContain("timing.budgetMs.steps must exceed");
      expect(problems).toContain("timing.reservationExtraMs.dish must cover its budget");
    } finally {
      remove();
    }
    expect(providerRegistryProblems()).toEqual([]);
  });

  test("Vertex with a fallback (the default): the first attempt is aborted at 30 s, the fallback gets the rest", async () => {
    const config = parseRuntimeConfig({});
    const { out, aborts, vertex, deps } = await timed(config, "vertex", IMAGE.budgetMs.dish, IMAGE.budgetMs.dish);
    expect(aborts).toEqual([30_000]);
    expect(vertex.targets).toEqual([{ provider: "vertex", model: "gemini-nano-banana-2.1" }]);
    expect(out).toMatchObject({ ok: true, model: "gemini-nano-banana-2.1" });
    expect(deps.logs.find((l) => l.message === "image_fallback")?.data).toMatchObject({ reason: "TIMEOUT", outcome: "done" });
  });

  test("OpenAI without a fallback: one attempt, aborted at 35 s, booked at its price", async () => {
    const config = parseRuntimeConfig({ image: { provider: "openai", model: "gpt-image-2.6-flare", fallbackModel: "" } });
    const { out, aborts } = await timed(config, "openai", IMAGE.budgetMs.dish, IMAGE.budgetMs.dish);
    expect(aborts).toEqual([35_000]);
    expect(out).toMatchObject({ ok: false, failure: "TIMEOUT", microUsd: 35_000 });
  });

  test("BFL selected: the first attempt is aborted only at 45 s, booked (BFL finishes and charges it), then Flash-Lite Image gets the remaining time", async () => {
    const config = parseRuntimeConfig({ image: { provider: "bfl", model: FLUX, fallbackProvider: "vertex", fallbackModel: FLASH_LITE } });
    const budget = imageTimingOf("bfl").budgetMs.dish;
    const { out, aborts, vertex, deps } = await timed(config, "bfl", budget, budget);
    expect(aborts).toEqual([45_000]);
    expect(vertex.targets).toEqual([{ provider: "vertex", model: FLASH_LITE }]);
    // BFL's abandoned task at its flat price plus Flash-Lite Image's picture.
    expect(out).toMatchObject({ ok: true, model: FLASH_LITE, microUsd: 10_000 + 40_000 });
    expect(deps.logs.find((l) => l.message === "image_fallback")?.data).toMatchObject({ provider: "bfl", fallbackProvider: "vertex", reason: "TIMEOUT", outcome: "done" });
    expect(deps.logs.find((l) => l.message === "image_call")?.data).toMatchObject({ provider: "bfl", attempts: 2, fallback: true });
  });

  test("BFL selected, the fallback also slow: it is aborted at the dish's 65 s, both booked", async () => {
    const config = parseRuntimeConfig({ image: { provider: "bfl", model: FLUX, fallbackProvider: "vertex", fallbackModel: FLASH_LITE } });
    const deps = makeDeps();
    const flux = hanging(0);
    const flash = hanging(0);
    (deps as Deps).imageGenerator = new ImageRouter({ vertex: () => flash.gen, bfl: () => flux.gen });
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "Date"] });
    try {
      const start = Date.now();
      const pending = makeRecipeImage({ ...deps, now: () => Date.now() }, "u1", SAMPLE_BRIEF, config, start + imageTimingOf("bfl").budgetMs.dish);
      await vi.advanceTimersByTimeAsync(70_000);
      expect(await pending).toMatchObject({ ok: false, failure: "TIMEOUT", microUsd: 10_000 + 40_000 });
      expect(flux.aborts).toEqual([45_000]);
      // The fallback's time is what is left: 65 s - 45 s (to within the clock's millisecond).
      expect(flash.aborts[0]).toBeGreaterThanOrEqual(19_000);
      expect(flash.aborts[0]).toBeLessThanOrEqual(20_000);
    } finally {
      vi.useRealTimers();
    }
  });

  test("the adapter itself polls for as long as the signal lets it (no limit of its own past the old 35 s)", async () => {
    const replies: Reply[] = [submitted(), ...Array.from({ length: 30 }, () => poll("Pending")), ready(), await picture()];
    const q = fetchQueue(replies);
    let slept = 0;
    const gen = new BflImageGenerator({
      apiKey: async () => KEY,
      region: "eu",
      fetch: q.fetch,
      sleep: async (ms, signal) => {
        slept += ms;
        if (signal.aborted) throw new Error("aborted");
      },
    });
    const out = await gen.generate(PROMPT, target(), new AbortController().signal);
    expect(slept).toBeGreaterThan(40_000);
    expect(out.model).toBe(FLUX);
  });
});
