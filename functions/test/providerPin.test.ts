/**
 * Pins what the server sends and books for existing requests, so the provider-adapter refactor
 * (README "Adding a provider") can be shown to change nothing: for the replay fixtures (and the
 * picture levels), the exact Vertex AI call of the recipe text and of the judge (hashed), the
 * idempotency hash, the plan (cost, limits, reserved worst case), the picture calls of both
 * picture providers, the booked picture costs, and, end to end against the emulators, the
 * generation's stored money fields and the llm_call / image_call log lines.
 *
 * The expected values were computed by the code before the refactor (commit "Pin prompts,
 * hashes and costs before the provider refactor") and are in
 * test/fixtures/provider-pin-2026-11-17.json; this file and that one are never regenerated to make
 * a refactor pass.
 *
 * Changed on purpose since, and only these values: on 2026-11-18 the output judge moved to a lite
 * model with its own prices, a 320-token ceiling and a described answer (README "Prompt injection
 * defences" item 5), so `judgeSent`, `judgeWorstCase` and every `reservedWithJudge` were
 * recomputed; the judge pinned here still runs on gemini-3.8-flash at LOW, that model's least.
 */
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import type { GenerateContentParameters, GenerateContentResponse } from "@google/genai";
import { buildPrompt } from "../src/adapters/prompt";
import { GeminiOutputJudge, GeminiRecipeGenerator, type StreamingModels } from "../src/adapters/gemini";
import { openAIImageBody, vertexImageParams } from "../src/adapters/image";
import { inputHashOf, validateGenerateRequest, type GenerateRequest, type Recipe } from "../src/contract";
import { generateRecipe, judgeWorstCaseMicroUsd, planFor } from "../src/core/generation";
import { imagePromptFor, pictureMicroUsd } from "../src/core/recipeImage";
import { DEFAULT_RUNTIME_CONFIG, emptyUser, type RuntimeConfig } from "../src/model";
import { MAX_OUTPUT_TOKENS } from "../src/constants";
import { SAMPLE_BRIEF, SAMPLE_STEP_BRIEFS, callerFor, db, makeDeps, resetEmulators, sampleRecipe } from "./helpers";

const pin = () => JSON.parse(readFileSync(join(__dirname, "fixtures/provider-pin-2026-11-17.json"), "utf8")) as Record<string, unknown>;
const NOW = Date.parse("2026-11-14T12:00:00Z");
const CANARY = "LRD-0123456789ABCDEF";
const REQUEST_ID = "7d0b6a8e-4c1f-4f7a-9d2e-1a2b3c4d5e6f";
const FIXTURES = ["config-recipe-2026-11-14", "config-menu2-2026-11-14", "config-menu3-2026-11-14", "taste-recipe-2026-11-15", "live-menu-2026-11-14"];

const sha = (v: unknown) => createHash("sha256").update(typeof v === "string" ? v : JSON.stringify(v)).digest("hex");

/** The call a streaming adapter sends, without its abort signal, hashed; answers one empty chunk. */
function recorder(): { models: StreamingModels; sent: string[] } {
  const sent: string[] = [];
  const models: StreamingModels = {
    async generateContentStream(params: GenerateContentParameters) {
      const config = { ...((params.config ?? {}) as Record<string, unknown>) };
      delete config.abortSignal;
      sent.push(sha({ ...params, config }));
      return (async function* () {
        yield { candidates: [{ finishReason: "STOP", content: { parts: [{ text: "{\"verdict\":\"ok\"}" }] } }] } as unknown as GenerateContentResponse;
      })();
    },
  };
  return { models, sent };
}

function requestsOf(name: string): [string, GenerateRequest | string][] {
  const raw = JSON.parse(readFileSync(join(__dirname, "fixtures", `${name}.json`), "utf8")) as Record<string, unknown>;
  const variants: [string, Record<string, unknown>][] = [["plain", {}]];
  if (raw.kind === "recipe") variants.push(["dish", { image: "dish" }], ["steps", { image: "steps", stepImagesMax: 2 }]);
  return variants.map(([v, over]) => {
    try {
      return [`${name}/${v}`, validateGenerateRequest({ ...raw, requestId: REQUEST_ID, today: "2026-11-14", ...over }, NOW, [], 2)];
    } catch (e) {
      return [`${name}/${v}`, `refused: ${(e as { details?: { reason?: string } }).details?.reason ?? String(e)}`];
    }
  });
}

async function computeRequests(): Promise<Record<string, unknown>> {
  const out: Record<string, unknown> = {};
  const judgeOn: RuntimeConfig = { ...DEFAULT_RUNTIME_CONFIG, outputJudge: "enforce" };
  for (const name of FIXTURES) {
    for (const [key, req] of requestsOf(name)) {
      if (typeof req === "string") {
        out[key] = req;
        continue;
      }
      const plan = planFor(req, DEFAULT_RUNTIME_CONFIG);
      const prompt = buildPrompt(req, CANARY);
      const text = recorder();
      await new GeminiRecipeGenerator({ project: "p", location: "global", model: "gemini-3.8-flash", thinkingLevel: "LOW" }, text.models)
        .generate(req, prompt, new AbortController().signal, plan.limits)
        .catch(() => undefined);
      out[key] = {
        inputHash: inputHashOf(req),
        prompt: sha(prompt),
        sent: text.sent,
        cost: plan.cost,
        limits: plan.limits,
        reservedMicroUsd: plan.reservedMicroUsd,
        reservedWithJudge: planFor(req, judgeOn).reservedMicroUsd,
        image: plan.image ?? null,
      };
    }
  }
  const judge = recorder();
  // The judge on the generator's model (GEMINI_JUDGE_MODEL empty) sends what it always sent: LOW is that model's least thinking.
  await new GeminiOutputJudge({ project: "p", location: "global", model: "gemini-3.8-flash" }, judge.models)
    .judge({ recipe: sampleRecipe() as unknown as Recipe }, new AbortController().signal);
  await new GeminiOutputJudge({ project: "p", location: "global", model: "gemini-3.8-flash", jailbreakFilter: true }, judge.models)
    .judge({ recipe: sampleRecipe() as unknown as Recipe, imageBrief: SAMPLE_BRIEF }, new AbortController().signal);
  out.judgeSent = judge.sent;
  out.judgeWorstCase = Object.fromEntries(Object.entries(MAX_OUTPUT_TOKENS).map(([k, n]) => [k, judgeWorstCaseMicroUsd(DEFAULT_RUNTIME_CONFIG, n)]));
  return out;
}

function computeImages(): Record<string, unknown> {
  const dish = imagePromptFor(SAMPLE_BRIEF);
  const step = imagePromptFor(SAMPLE_STEP_BRIEFS[0].brief, "step");
  const usage = { inputTokens: 120, outputTextTokens: 300, outputImageTokens: 1120 };
  const targets = [
    { provider: "vertex", model: "gemini-3.1-flash-lite-image" },
    { provider: "vertex", model: "gemini-nano-banana-2.1" },
    { provider: "openai", model: "gpt-image-1-mini" },
    { provider: "openai", model: "gpt-image-2" },
    { provider: "vertex", model: "unpriced-model" },
  ];
  return {
    prompts: [sha(dish), sha(step)],
    vertex: targets.slice(0, 2).flatMap((t) => [sha(vertexImageParams(dish, t.model)), sha(vertexImageParams(step, t.model))]),
    openai: ["gpt-image-1-mini", "gpt-image-2"].flatMap((m) => (["low", "medium", "high"] as const).map((q) => sha(openAIImageBody(dish, m, q)))),
    cost: targets.map((t) => [pictureMicroUsd(DEFAULT_RUNTIME_CONFIG, t, usage), pictureMicroUsd(DEFAULT_RUNTIME_CONFIG, t, undefined)]),
  };
}

const UID = "pin-user";
const strip = (d: Record<string, unknown> | undefined) => {
  if (!d) return d;
  const rest = { ...d };
  delete rest.uidHash;
  delete rest.durationMs;
  return rest;
};

async function computeEndToEnd(): Promise<Record<string, unknown>> {
  const out: Record<string, unknown> = {};
  const cases: [string, Record<string, unknown>, (deps: ReturnType<typeof makeDeps>) => void][] = [
    ["recipe", {}, () => undefined],
    ["recipe-dish", { image: "dish" }, () => undefined],
    ["recipe-steps", { image: "steps", stepImagesMax: 2 }, () => undefined],
    ["infra-unknown-usage", {}, (d) => d.generator.failWith("infra")],
    ["content", {}, (d) => d.generator.failWith("content")],
    ["picture-capacity-then-fallback", { image: "dish" }, (d) => { d.imageGenerator.failWith("infra", false, true); d.imageGenerator.failWith("infra", false, true); d.imageGenerator.failWith("infra", false, true); d.imageGenerator.failWith("infra", false, true); }],
    ["picture-safety", { image: "dish" }, (d) => d.imageGenerator.failWith("safety", true)],
    ["picture-config", { image: "dish" }, (d) => d.imageGenerator.failWith("config", false)],
  ];
  for (const [name, over, arrange] of cases) {
    await resetEmulators();
    const deps = makeDeps();
    arrange(deps);
    await db.collection("users").doc(UID).set({ ...emptyUser(), balance: 20, purchasedCredits: 20 });
    const today = new Date(deps.now()).toISOString().slice(0, 10);
    const requestId = "0b7c6d5e-4f3a-4b2c-9d1e-0f1e2d3c4b5a";
    await generateRecipe(deps, callerFor(UID), {
      requestId,
      kind: "recipe",
      config: { difficulty: "easy", detail: "standard", servings: 2, maxMinutes: 30, mealType: "dinner", diet: "none", language: "en" },
      ingredients: [{ name: "Pasta", expiresOn: "2030-01-01", quantity: 1 }, { name: "Tomatoes", expiresOn: null, quantity: 4 }],
      allergens: [],
      today,
      ...over,
    }).catch((e) => ({ error: String((e as { details?: { reason?: string } }).details?.reason ?? e) }));
    const gen = (await db.collection("users").doc(UID).collection("generations").doc(requestId).get()).data() ?? {};
    out[name] = {
      generation: {
        state: gen.state, cost: gen.cost, charged: gen.charged ?? null, reservedMicroUsd: gen.reservedMicroUsd,
        actualMicroUsd: gen.actualMicroUsd, usage: gen.usage ?? null, imageReservedMicroUsd: gen.imageReservedMicroUsd ?? null,
        imageFailure: gen.imageFailure ?? null, failureClass: gen.failureClass ?? null, failureReason: gen.failureReason ?? null,
      },
      logs: deps.logs.filter((l) => l.message !== "image_brief_rejected").map((l) => [l.level, l.message, strip(l.data)]),
      imageTargets: deps.imageGenerator.targets,
    };
  }
  return out;
}

describe("existing requests send and book exactly what they did before the provider refactor", () => {
  test("the text calls, hashes and plans of the replay fixtures", async () => {
    expect(await computeRequests()).toEqual(pin().requests);
  });

  test("the picture calls and their booked costs", () => {
    expect(computeImages()).toEqual(pin().images);
  });

  test("end to end: stored money fields and log lines", async () => {
    expect(await computeEndToEnd()).toEqual(pin().endToEnd);
  }, 60_000);
});

