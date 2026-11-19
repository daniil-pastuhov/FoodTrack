/**
 * Model lifecycle (src/providers/lifecycle.ts, README "Model lifecycle"): the table covers every
 * model the server can select, which models a config puts in use, the bands and their marks in
 * Firestore (once per band, rising severity), the stale reminder, the probes (unavailable, failed,
 * an API date earlier than the table's), the Vertex AI and OpenAI probe calls, and the read-only
 * admin command.
 */
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { AdminRefusal } from "../src/admin/accountRequests";
import { judgeModelOf, parseModelsArgs, runModels, type ModelsEnv } from "../src/admin/cli";
import { DEFAULT_IMAGE_SELECTION, JUDGE, JUDGE_PRICES } from "../src/constants";
import { countTokensUrl, openaiModelInfo, vertexModelInfo } from "../src/adapters/modelProbes";
import { googleText } from "../src/adapters/gemini";
import { openaiImage, vertexImage } from "../src/adapters/image";
import { parseRuntimeConfig } from "../src/model";
import type { Logger } from "../src/ports";
import {
  LIFECYCLE_COLLECTION,
  MODEL_LIFECYCLE,
  assessModels,
  daysBetween,
  lifecycleEntry,
  lifecycleProblems,
  logModelLifecycle,
  modelsInUse,
  retirementBand,
  type LifecycleEntry,
  type ModelUse,
} from "../src/providers/lifecycle";
import type { ModelInfo, ProviderContext, ProviderDescriptor } from "../src/providers/types";
import { db, resetEmulators } from "./helpers";

interface Line { level: string; message: string; data?: Record<string, unknown> }

function recorder(): { logger: Logger; logs: Line[] } {
  const logs: Line[] = [];
  const rec = (level: string) => (message: string, data?: Record<string, unknown>) => void logs.push({ level, message, data });
  return { logger: { info: rec("info"), warn: rec("warn"), error: rec("error") }, logs };
}

const NOW = Date.parse("2026-11-18T06:23:00Z");
const DAY = 86_400_000;
const day = (ms: number) => new Date(ms).toISOString().slice(0, 10);
const ctx = (): ProviderContext => ({ project: "p", param: () => "global", secret: async () => "k" });
const entry = (model: string, over: Partial<LifecycleEntry> = {}): LifecycleEntry => ({
  provider: "vertex", model, retiresOn: null, source: "https://example.com/lifecycle", checkedOn: day(NOW), ...over,
});
const use = (model: string, inUse = true, roles: ModelUse["roles"] = [inUse ? "image" : "option"]): ModelUse => ({ provider: "vertex", model, roles, inUse });
const prober = (fn: (model: string, signal: AbortSignal) => Promise<ModelInfo>): ProviderDescriptor =>
  ({ ...vertexImage, modelInfo: (_c: ProviderContext, m: string, s: AbortSignal) => fn(m, s) }) as ProviderDescriptor;
const noProbe: ProviderDescriptor = { ...vertexImage, modelInfo: undefined } as ProviderDescriptor;

async function run(models: ModelUse[], table: LifecycleEntry[], now: number, providers: ProviderDescriptor[] = [noProbe], probe = true, only?: string) {
  const { logger, logs } = recorder();
  const reports = await assessModels({ models, providers, context: ctx, now: () => now, probe, table });
  await logModelLifecycle(reports, { db, logger, now: () => now });
  return { reports, logs: logs.filter((l) => l.message !== "model_lifecycle_checked" && (!only || l.message === only)), all: logs };
}
const near = "model_retirement_near";

beforeEach(async () => {
  await resetEmulators();
});

describe("the table", () => {
  // GEMINI_JUDGE_MODEL is defined in src/index.ts with JUDGE.model as its default (set empty: GEMINI_MODEL);
  // lifecycleProblems requires an entry for JUDGE.model and every JUDGE_PRICES model.
  test("the judge's default param is JUDGE.model, which the table must have", () => {
    const src = readFileSync(join(__dirname, "../src/index.ts"), "utf8");
    expect(src).toMatch(/defineString\("GEMINI_JUDGE_MODEL",\s*\{\s*default:\s*JUDGE\.model,/);
    const without = MODEL_LIFECYCLE.filter((e) => e.model !== JUDGE.model);
    expect(lifecycleProblems(without).join("\n")).toContain(`google/${JUDGE.model} (JUDGE.model (GEMINI_JUDGE_MODEL default)) has no lifecycle entry`);
    for (const m of Object.keys(JUDGE_PRICES)) expect(lifecycleEntry("google", m), m).toBeDefined();
  });

  test("the admin command reads the judge's model as the functions do", () => {
    expect(judgeModelOf({}, "gemini-3.8-flash")).toBe(JUDGE.model);
    expect(judgeModelOf({ GEMINI_JUDGE_MODEL: "gemini-3.1-flash-lite" }, "gemini-3.8-flash")).toBe("gemini-3.1-flash-lite");
    expect(judgeModelOf({ GEMINI_JUDGE_MODEL: "" }, "gemini-3.8-flash")).toBe("gemini-3.8-flash");
  });

  test("has an entry for every model a switch offers, a default, a default param or a price table uses", () => {
    expect(lifecycleProblems(MODEL_LIFECYCLE)).toEqual([]);
  });

  test("fails for a model without an entry, and for a malformed entry", () => {
    const without = MODEL_LIFECYCLE.filter((e) => e.model !== DEFAULT_IMAGE_SELECTION.fallbackModel);
    expect(lifecycleProblems(without).join("\n")).toContain(`vertex/${DEFAULT_IMAGE_SELECTION.fallbackModel} (DEFAULT_IMAGE_SELECTION fallback) has no lifecycle entry`);
    expect(lifecycleProblems(MODEL_LIFECYCLE, [{ provider: "google", model: "gemini-9-new", why: "test" }])).toEqual([
      "google/gemini-9-new (test) has no lifecycle entry (providers/lifecycle.ts MODEL_LIFECYCLE)",
    ]);
    const bad = [
      ...MODEL_LIFECYCLE,
      entry("x1", { retiresOn: "2026-13-01" }),
      entry("x2", { checkedOn: "yesterday" }),
      entry("x3", { source: "http://example.com" }),
      entry("x4", { provider: "nosuch" }),
      entry("x5", { earliest: true }),
      entry("x5"),
    ];
    expect(lifecycleProblems(bad)).toEqual([
      "vertex/x1: retiresOn must be YYYY-MM-DD or null",
      "vertex/x2: checkedOn must be YYYY-MM-DD",
      "vertex/x3: source must be an https URL without a query",
      "nosuch/x4: unknown provider",
      "vertex/x5: earliest needs a retiresOn",
      "vertex/x5: two lifecycle entries",
    ]);
  });

  test("bands and day arithmetic", () => {
    expect(daysBetween("2026-11-18", "2026-12-01")).toBe(13);
    expect(daysBetween("2026-11-18", "2026-11-17")).toBe(-1);
    expect([120, 91, 90, 31, 30, 8, 7, 1, 0, -5].map(retirementBand)).toEqual([null, null, 90, 90, 30, 30, 7, 7, 0, 0]);
  });
});

describe("which models are in use", () => {
  test("the defaults: text, the judge while it is on, pictures and their fallback; then the models only a switch offers", () => {
    const off = modelsInUse(parseRuntimeConfig({}), { provider: "google", model: "gemini-3.8-flash" });
    expect(off.filter((u) => u.inUse).map((u) => [`${u.provider}/${u.model}`, u.roles])).toEqual([
      ["google/gemini-3.8-flash", ["text", "judge", "text_default", "option"]],
      ["vertex/gemini-3.1-flash-lite-image", ["image", "image_default", "option"]],
      ["vertex/gemini-nano-banana-2.1", ["image_fallback", "option"]],
    ]);
    expect(off.filter((u) => !u.inUse).map((u) => [`${u.provider}/${u.model}`, u.roles])).toEqual([
      ["openai/gpt-6.1-terra", ["option"]],
      ["openai/gpt-6-luna", ["option"]],
      ["openai/gpt-image-2.6-flare", ["option"]],
      ["bfl/flux-3-image-klein", ["option"]],
      ["bfl/flux-3-image", ["option"]],
    ]);
    // The judge's own model is in use only while the judge runs.
    const judgeOff = modelsInUse(parseRuntimeConfig({}), { provider: "google", model: "gemini-3.5-flash-lite" });
    expect(judgeOff.find((u) => u.model === "gemini-3.5-flash-lite")).toEqual({ provider: "google", model: "gemini-3.5-flash-lite", roles: ["judge"], inUse: false });
    const judgeOn = modelsInUse(parseRuntimeConfig({ outputJudge: "shadow" }), { provider: "google", model: "gemini-3.5-flash-lite" });
    expect(judgeOn.find((u) => u.model === "gemini-3.5-flash-lite")?.inUse).toBe(true);
    // As now configured (GEMINI_JUDGE_MODEL's default): the judge's own entry, a dated GA model.
    const configured = modelsInUse(parseRuntimeConfig({ outputJudge: "shadow" }), { provider: "google", model: JUDGE.model });
    expect(configured.find((u) => u.roles.includes("judge"))).toMatchObject({ provider: "google", model: JUDGE.model, inUse: true });
    expect(lifecycleEntry("google", JUDGE.model)).toMatchObject({ retiresOn: "2027-07-21", earliest: true });
  });

  test("config/runtime over the defaults: a hand-set picture setup and pictures off", () => {
    const custom = parseRuntimeConfig({ image: { provider: "vertex", model: "gemini-nano-banana-2.1", fallbackModel: "" } });
    const inUse = modelsInUse(custom, null).filter((u) => u.inUse);
    expect(inUse.map((u) => [u.model, u.roles.filter((r) => r !== "option")])).toEqual([
      ["gemini-3.8-flash", ["text", "text_default"]],
      ["gemini-nano-banana-2.1", ["image"]],
      ["gemini-3.1-flash-lite-image", ["image_default"]],
    ]);
    const off = modelsInUse(parseRuntimeConfig({ image: { provider: "off" } }), null).filter((u) => u.inUse);
    expect(off.map((u) => u.roles.filter((r) => r !== "option"))).toEqual([["text", "text_default"], ["image_default"]]);
  });
});

describe("retirement bands, told once each, against the marks in Firestore", () => {
  test("90 (info), 30 (warn), 7 (error) for a model in use, then every run once past", async () => {
    const table = [entry("m1", { retiresOn: day(NOW + 80 * DAY) })];
    let r = await run([use("m1")], table, NOW);
    expect(r.logs).toEqual([{ level: "info", message: "model_retirement_near", data: {
      provider: "vertex", model: "m1", role: "image", roles: ["image"], inUse: true, retiresOn: day(NOW + 80 * DAY), daysLeft: 80, band: 90, from: "table",
    } }]);
    expect((await run([use("m1")], table, NOW + DAY)).logs).toEqual([]);
    r = await run([use("m1")], table, NOW + 51 * DAY);
    expect(r.logs.map((l) => [l.level, l.data?.band, l.data?.daysLeft])).toEqual([["warn", 30, 29]]);
    expect((await run([use("m1")], table, NOW + 60 * DAY)).logs).toEqual([]);
    // From day 61 the entry (checked on day 0) is also stale: only the retirement lines here.
    r = await run([use("m1")], table, NOW + 74 * DAY, [noProbe], true, near);
    expect(r.logs.map((l) => [l.level, l.data?.band, l.data?.daysLeft])).toEqual([["error", 7, 6]]);
    expect((await run([use("m1")], table, NOW + 75 * DAY, [noProbe], true, near)).logs).toEqual([]);
    for (const d of [80, 81]) {
      r = await run([use("m1")], table, NOW + d * DAY, [noProbe], true, near);
      expect(r.logs.map((l) => [l.level, l.data?.band, l.data?.daysLeft])).toEqual([["error", 0, 80 - d]]);
    }
    const mark = (await db.collection(LIFECYCLE_COLLECTION).doc("vertex__m1").get()).data();
    expect(mark).toMatchObject({ provider: "vertex", model: "m1", band: 0, bandRetiresOn: day(NOW + 80 * DAY), lastRun: { probe: "none", daysLeft: -1, inUse: true } });
  });

  test("a model only a switch offers: lower severity, and once even when past", async () => {
    const table = [entry("m2", { retiresOn: day(NOW + 5 * DAY) })];
    let r = await run([use("m2", false)], table, NOW);
    expect(r.logs.map((l) => [l.level, l.message, l.data?.band, l.data?.inUse])).toEqual([["warn", "model_retirement_near", 7, false]]);
    r = await run([use("m2", false)], table, NOW + 6 * DAY);
    expect(r.logs.map((l) => [l.level, l.data?.band])).toEqual([["warn", 0]]);
    expect((await run([use("m2", false)], table, NOW + 7 * DAY)).logs).toEqual([]);
  });

  test("a model first seen inside a band is told at once; a new date starts over; \"or later\" says so", async () => {
    const t1 = [entry("m3", { retiresOn: day(NOW + 20 * DAY), earliest: true })];
    let r = await run([use("m3")], t1, NOW);
    expect(r.logs.map((l) => [l.data?.band, l.data?.earliest])).toEqual([[30, true]]);
    // The provider extended it: a new date, further out, is told again in its own band.
    const t2 = [entry("m3", { retiresOn: day(NOW + 60 * DAY) })];
    r = await run([use("m3")], t2, NOW + DAY);
    expect(r.logs.map((l) => [l.data?.band, l.data?.daysLeft, l.data?.earliest])).toEqual([[90, 59, undefined]]);
    // No date any more: nothing, and the mark forgets the band.
    expect((await run([use("m3")], [entry("m3")], NOW + 2 * DAY)).logs).toEqual([]);
    expect((await db.collection(LIFECYCLE_COLLECTION).doc("vertex__m3").get()).data()?.band).toBeNull();
  });

  test("without the marks (no db) every line is logged, and the summary line counts", async () => {
    const { logger, logs } = recorder();
    const reports = await assessModels({ models: [use("m4"), use("m5", false)], providers: [noProbe], context: ctx, now: () => NOW, probe: true, table: [entry("m4", { retiresOn: day(NOW + 3 * DAY) }), entry("m5")] });
    await logModelLifecycle(reports, { logger, now: () => NOW });
    await logModelLifecycle(reports, { logger, now: () => NOW });
    expect(logs.map((l) => l.message)).toEqual(["model_retirement_near", "model_lifecycle_checked", "model_retirement_near", "model_lifecycle_checked"]);
    expect(logs[1].data).toEqual({ models: 2, inUse: 1, soonestDaysLeft: 3, stale: 0, unavailable: 0, probeFailed: 0 });
  });
});

describe("the stale reminder", () => {
  test("a model in use checked more than 60 days ago: once, then weekly; a fresh check stops it", async () => {
    const old = [entry("s1", { checkedOn: day(NOW - 61 * DAY) })];
    const r = await run([use("s1")], old, NOW);
    expect(r.logs).toEqual([{ level: "warn", message: "model_lifecycle_stale", data: {
      provider: "vertex", model: "s1", role: "image", roles: ["image"], inUse: true, checkedOn: day(NOW - 61 * DAY), checkedDaysAgo: 61, staleDays: 60, reason: "OLD_CHECK",
    } }]);
    expect((await run([use("s1")], old, NOW + 6 * DAY)).logs).toEqual([]);
    expect((await run([use("s1")], old, NOW + 7 * DAY)).logs.map((l) => l.data?.checkedDaysAgo)).toEqual([68]);
    expect((await run([use("s1")], [entry("s1", { checkedOn: day(NOW + 8 * DAY) })], NOW + 8 * DAY)).logs).toEqual([]);
    // Only listed: never stale.
    expect((await run([use("s2", false)], [entry("s2", { checkedOn: "2025-01-01" })], NOW)).logs).toEqual([]);
  });

  test("a model in use with no entry (set by hand in config/runtime) is stale at once", async () => {
    const r = await run([use("handset")], [], NOW);
    expect(r.logs.map((l) => [l.message, l.data?.reason, l.data?.checkedOn])).toEqual([["model_lifecycle_stale", "NO_ENTRY", null]]);
    expect(r.reports[0]).toMatchObject({ retiresOn: null, daysLeft: null, stale: true, checkedDaysAgo: null });
  });
});

describe("the probes", () => {
  test("not served: model_unavailable, an error for a model in use and a warning for a listed one, every run", async () => {
    const gone = prober(async () => ({ served: false }));
    for (let i = 0; i < 2; i++) {
      const r = await run([use("p1"), use("p2", false)], [entry("p1"), entry("p2")], NOW + i * DAY, [gone]);
      expect(r.logs.map((l) => [l.level, l.message, l.data?.model])).toEqual([["error", "model_unavailable", "p1"], ["warn", "model_unavailable", "p2"]]);
    }
  });

  test("a probe that fails or times out is an info line, never an alarm", async () => {
    const broken = prober(async () => {
      throw new Error("503 from the provider, with text we never log");
    });
    let r = await run([use("p3")], [entry("p3")], NOW, [broken]);
    expect(r.logs).toEqual([{ level: "info", message: "model_probe_failed", data: { provider: "vertex", model: "p3", role: "image", roles: ["image"], inUse: true, reason: "FAILED" } }]);
    const slow = prober((_m, signal) => new Promise((_res, rej) => signal.addEventListener("abort", () => rej(new Error("aborted")))));
    const { logger, logs } = recorder();
    const reports = await assessModels({ models: [use("p3")], providers: [slow], context: ctx, now: () => NOW, probe: true, table: [entry("p3")], timeoutMs: 20 });
    await logModelLifecycle(reports, { logger, now: () => NOW });
    expect(logs[0]).toMatchObject({ level: "info", message: "model_probe_failed", data: { reason: "TIMEOUT" } });
    r = await run([use("p3")], [entry("p3")], NOW, [prober(async () => ({ served: true }))]);
    expect(r.logs).toEqual([]);
    expect(r.reports[0].probe).toBe("ok");
  });

  test("an API date earlier than the table's (or one it lacks) is told once and counts for the bands", async () => {
    const soon = day(NOW + 40 * DAY);
    const api = prober(async () => ({ served: true, retiresOn: soon }));
    let r = await run([use("p4")], [entry("p4", { retiresOn: day(NOW + 300 * DAY) })], NOW, [api]);
    expect(r.logs.map((l) => [l.message, l.data?.band, l.data?.from, l.data?.tableRetiresOn, l.data?.apiRetiresOn])).toEqual([
      ["model_retirement_near", 90, "api", undefined, undefined],
      ["model_lifecycle_changed", undefined, undefined, day(NOW + 300 * DAY), soon],
    ]);
    expect((await run([use("p4")], [entry("p4", { retiresOn: day(NOW + 300 * DAY) })], NOW + DAY, [api])).logs).toEqual([]);
    // The table has none: told too. A later API date than the table's is not a change.
    r = await run([use("p5")], [entry("p5")], NOW, [api]);
    expect(r.logs.map((l) => l.message)).toEqual(["model_retirement_near", "model_lifecycle_changed"]);
    r = await run([use("p6")], [entry("p6", { retiresOn: day(NOW + 20 * DAY) })], NOW, [api]);
    expect(r.logs.map((l) => [l.message, l.data?.from])).toEqual([["model_retirement_near", "table"]]);
    // The API says none: nothing changes either.
    r = await run([use("p7")], [entry("p7")], NOW, [prober(async () => ({ served: true, retiresOn: null }))]);
    expect(r.logs).toEqual([]);
  });

  test("without --probe nothing is asked", async () => {
    let asked = 0;
    const counting = prober(async () => (asked++, { served: false }));
    const r = await run([use("p8")], [entry("p8")], NOW, [counting], false);
    expect(asked).toBe(0);
    expect(r.reports[0].probe).toBe("skipped");
  });

  test("Vertex AI: countTokens in the configured location (free, not generating); 404 is not served, other failures throw", async () => {
    expect(countTokensUrl("proj", "global", "gemini-3.8-flash")).toBe("https://aiplatform.googleapis.com/v1/projects/proj/locations/global/publishers/google/models/gemini-3.8-flash:countTokens");
    expect(countTokensUrl("proj", "europe-west4", "m")).toBe("https://europe-west4-aiplatform.googleapis.com/v1/projects/proj/locations/europe-west4/publishers/google/models/m:countTokens");
    const seen: { url: string; init?: RequestInit }[] = [];
    const answer = (status: number) => (async (url: string, init?: RequestInit) => (seen.push({ url, init }), new Response("{}", { status }))) as unknown as typeof fetch;
    const o = { project: "proj", location: "global", model: "gemini-3.8-flash", signal: new AbortController().signal, token: async () => "tok" };
    expect(await vertexModelInfo({ ...o, fetch: answer(200) })).toEqual({ served: true });
    expect(seen[0].init?.method).toBe("POST");
    expect(new Headers(seen[0].init?.headers).get("authorization")).toBe("Bearer tok");
    expect(String(seen[0].init?.body)).not.toContain("generationConfig");
    expect(await vertexModelInfo({ ...o, fetch: answer(404) })).toEqual({ served: false });
    await expect(vertexModelInfo({ ...o, fetch: answer(403) })).rejects.toThrow("Vertex probe failed");
    await expect(vertexModelInfo({ ...o, fetch: answer(429) })).rejects.toThrow("Vertex probe failed");
    // The descriptors probe in their own location params.
    expect(googleText.modelInfo).toBeDefined();
    expect(vertexImage.modelInfo).toBeDefined();
  });

  test("OpenAI: GET /v1/models/{model}, its shutdown_date, and 404", async () => {
    const answer = (status: number, body: unknown) => (async () => new Response(JSON.stringify(body), { status })) as unknown as typeof fetch;
    const o = { model: "gpt-image-1-mini", apiKey: "sk-test", signal: new AbortController().signal };
    expect(await openaiModelInfo({ ...o, fetch: answer(200, { id: "gpt-image-1-mini", shutdown_date: "2026-12-01" }) })).toEqual({ served: true, retiresOn: "2026-12-01" });
    expect(await openaiModelInfo({ ...o, fetch: answer(200, { id: "x", shutdown_date: null }) })).toEqual({ served: true, retiresOn: null });
    expect(await openaiModelInfo({ ...o, fetch: answer(200, { id: "x" }) })).toEqual({ served: true });
    expect(await openaiModelInfo({ ...o, fetch: answer(404, { error: { code: "model_not_found" } }) })).toEqual({ served: false });
    await expect(openaiModelInfo({ ...o, fetch: answer(401, {}) })).rejects.toThrow("OpenAI probe failed");
    // Through the descriptor: the key comes from the context, the result from the API.
    const ctxWith: ProviderContext = { project: "p", param: () => "", secret: async () => "sk-test", fetch: answer(200, { shutdown_date: "2026-12-01" }) };
    expect(await openaiImage.modelInfo!(ctxWith, "gpt-image-1-mini", new AbortController().signal)).toEqual({ served: true, retiresOn: "2026-12-01" });
  });
});

describe("npm run admin -- models (read-only)", () => {
  test("arguments: --probe needs a live project", () => {
    expect(parseModelsArgs(["models"])).toEqual({ probe: false, project: "demo-foodtracker" });
    expect(parseModelsArgs(["models", "--probe", "--project", "bestbefore-f9ee1"])).toEqual({ probe: true, project: "bestbefore-f9ee1" });
    expect(() => parseModelsArgs(["models", "--probe"])).toThrow(AdminRefusal);
    expect(() => parseModelsArgs(["models", "extra"])).toThrow(AdminRefusal);
    expect(() => parseModelsArgs(["models", "--apply"])).toThrow(AdminRefusal);
  });

  test("prints the models of config/runtime with their dates, and writes nothing", async () => {
    await db.doc("config/runtime").set({ image: { provider: "vertex", model: "gemini-nano-banana-2.1", fallbackModel: "gemini-3.1-flash-lite-image" } });
    const lines: string[] = [];
    const env: ModelsEnv = {
      db,
      imageDefaults: { ...DEFAULT_IMAGE_SELECTION },
      textDefaults: { provider: "google", model: "gemini-3.8-flash" },
      judgeModel: "gemini-3.8-flash",
      context: ctx,
      now: () => NOW,
      print: (l) => void lines.push(l),
    };
    const reports = await runModels({ probe: false, project: "demo-foodtracker" }, env);
    const text = lines.join("\n");
    expect(text).toContain("In use:");
    expect(text).toContain("vertex/gemini-nano-banana-2.1  [image, option]");
    expect(text).toContain("vertex/gemini-3.1-flash-lite-image  [image_fallback, image_default,");
    expect(text).toContain("google/gemini-3.8-flash  [text, judge, prompt_review, text_default, option]");
    expect(text).toContain("retires 2027-09-02 or later (288 days left); checked 2026-11-18 (0 days ago)");
    expect(text).toContain("Only offered by a switch:");
    expect(text).toContain("openai/gpt-image-2.6-flare  [option]");
    expect(text).toContain("openai/gpt-6.1-terra  [option]");
    expect(text).toContain("no retirement announced");
    expect(text).toContain("Add --probe");
    expect(reports.every((r) => r.probe === "skipped")).toBe(true);
    expect((await db.collection(LIFECYCLE_COLLECTION).get()).size).toBe(0);
  });
});
