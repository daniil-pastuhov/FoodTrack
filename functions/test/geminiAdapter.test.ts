/** The Gemini adapter against a stand-in for the Vertex AI stream: usage, finish reasons and the token-ceiling abort. */
import type { GenerateContentParameters, GenerateContentResponse } from "@google/genai";
import { GeminiRecipeGenerator, type StreamingModels } from "../src/adapters/gemini";
import { validateGenerateRequest } from "../src/contract";
import { GenerationError } from "../src/ports";
import { buildPrompt } from "../src/adapters/prompt";
import { sampleRecipe } from "./helpers";

const req = validateGenerateRequest({
  requestId: "0b5e7d4e-8f3c-4d6a-9a1b-2c3d4e5f6a7b",
  kind: "recipe",
  config: { difficulty: "easy", detail: "brief", servings: 2, maxMinutes: null, mealType: "any", diet: "none", language: "en" },
  ingredients: [{ name: "eggs", expiresOn: null, quantity: 2 }],
  allergens: [],
  today: new Date().toISOString().slice(0, 10),
}, Date.now());

const limits = { maxOutputTokens: 1000, hardTokenCeiling: 2000 };

type Chunk = Partial<GenerateContentResponse>;

class FakeStream implements StreamingModels {
  params?: GenerateContentParameters;
  consumed = 0;
  constructor(private readonly chunks: Chunk[], private readonly failBeforeStream?: Error) {}
  async generateContentStream(params: GenerateContentParameters): Promise<AsyncGenerator<GenerateContentResponse>> {
    this.params = params;
    if (this.failBeforeStream) throw this.failBeforeStream;
    const chunks = this.chunks;
    const count = () => this.consumed++;
    return (async function* () {
      for (const c of chunks) {
        if (params.config?.abortSignal?.aborted) throw new Error("aborted");
        count();
        yield c as GenerateContentResponse;
      }
    })();
  }
}

const textChunk = (text: string, usage?: Record<string, number>, finishReason?: string): Chunk => ({
  candidates: [{ content: { role: "model", parts: [{ text }] }, ...(finishReason ? { finishReason } : {}) }] as never,
  ...(usage ? { usageMetadata: usage } : {}),
});

const gen = (models: StreamingModels) =>
  new GeminiRecipeGenerator({ project: "p", location: "global", model: "gemini-3.8-flash", thinkingLevel: "LOW" }, models);

async function failure(p: Promise<unknown>): Promise<GenerationError> {
  try {
    await p;
  } catch (e) {
    if (e instanceof GenerationError) return e;
    throw e;
  }
  throw new Error("expected a GenerationError");
}

describe("Gemini adapter", () => {
  test("assembles the streamed JSON and reports the final usage", async () => {
    const json = JSON.stringify(sampleRecipe());
    const models = new FakeStream([
      textChunk(json.slice(0, 20), { promptTokenCount: 300, candidatesTokenCount: 10, thoughtsTokenCount: 50 }),
      textChunk(json.slice(20), { promptTokenCount: 300, candidatesTokenCount: 120, thoughtsTokenCount: 50 }, "STOP"),
    ]);
    const res = await gen(models).generate(req, buildPrompt(req, "LRD-TEST"), new AbortController().signal, limits);
    expect(res.output).toEqual(sampleRecipe());
    expect(res.usage).toEqual({ inputTokens: 300, outputTokens: 120, thinkingTokens: 50, model: "gemini-3.8-flash", finishReason: "STOP" });
    expect(models.params?.config?.maxOutputTokens).toBe(1000);
  });

  test("aborts mid-stream once output + thinking pass the hard ceiling, as an infrastructure failure", async () => {
    const models = new FakeStream([
      textChunk("{", { promptTokenCount: 300, candidatesTokenCount: 5, thoughtsTokenCount: 1990 }),
      textChunk("\"a\":1", { promptTokenCount: 300, candidatesTokenCount: 10, thoughtsTokenCount: 2100 }),
      textChunk("}", undefined, "STOP"),
    ]);
    const e = await failure(gen(models).generate(req, buildPrompt(req, "LRD-TEST"), new AbortController().signal, limits));
    expect(e).toMatchObject({ failureClass: "infra", message: "token ceiling exceeded" });
    expect(e.usage).toBeUndefined();
    expect(models.consumed).toBe(2);
    expect(models.params?.config?.abortSignal?.aborted).toBe(true);
  });

  test("aborts when the visible text alone is too long for the ceiling", async () => {
    const models = new FakeStream([textChunk("x".repeat(2000 * 8 + 1)), textChunk("}", undefined, "STOP")]);
    expect(await failure(gen(models).generate(req, buildPrompt(req, "LRD-TEST"), new AbortController().signal, limits))).toMatchObject({ failureClass: "infra" });
  });

  test("a final chunk over the ceiling is still delivered with its measured usage", async () => {
    const json = JSON.stringify(sampleRecipe());
    const models = new FakeStream([textChunk(json, { promptTokenCount: 300, candidatesTokenCount: 900, thoughtsTokenCount: 1500 }, "STOP")]);
    const res = await gen(models).generate(req, buildPrompt(req, "LRD-TEST"), new AbortController().signal, limits);
    expect(res.usage?.thinkingTokens).toBe(1500);
  });

  test("MAX_TOKENS and safety stops are content failures with usage", async () => {
    const models = new FakeStream([textChunk("{\"title\":", { promptTokenCount: 300, candidatesTokenCount: 1000 }, "MAX_TOKENS")]);
    const e = await failure(gen(models).generate(req, buildPrompt(req, "LRD-TEST"), new AbortController().signal, limits));
    expect(e).toMatchObject({ failureClass: "content", message: "finish MAX_TOKENS" });
    expect(e.usage?.outputTokens).toBe(1000);
  });

  test("an HTTP error before the stream starts is infra with zero output tokens", async () => {
    const e = await failure(gen(new FakeStream([], new Error("503"))).generate(req, buildPrompt(req, "LRD-TEST"), new AbortController().signal, limits));
    expect(e).toMatchObject({ failureClass: "infra" });
    expect(e.usage).toMatchObject({ outputTokens: 0, thinkingTokens: 0 });
  });

  test("an abort by the caller is a timeout", async () => {
    const controller = new AbortController();
    controller.abort();
    const models = new FakeStream([textChunk("{")]);
    expect(await failure(gen(models).generate(req, buildPrompt(req, "LRD-TEST"), controller.signal, limits))).toMatchObject({ failureClass: "infra", message: "timeout" });
  });
});
