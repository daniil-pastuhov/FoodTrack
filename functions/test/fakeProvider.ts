/**
 * A provider that exists only in the tests: one adapter file (this one) plus one registry entry
 * (registerProviderForTests, standing in for the line in providers/registry.ts), to prove that a new
 * provider needs nothing else (README "Adding a provider"). It is shaped like the next real ones:
 *
 * - `fakeText`: an OpenAI-style chat-completions API over HTTPS with a bearer key from Secret
 *   Manager, `response_format: {type: "json_schema", json_schema: {strict: true, ...}}`,
 *   `reasoning_effort: "none"`, no provider-side safety settings, a streamed answer whose delta
 *   `content` is a string or a list of `{type: "text", text}` parts, `finish_reason: "length"`,
 *   402 for an empty prepaid balance, 429 with Retry-After, and an "EU endpoint" priced 1.1x.
 * - `fakeImage`: a picture API with a prepaid balance (402) and a balance probe.
 *
 * The endpoints are fake (example.invalid); every test passes its own fetch.
 */
import type { GenerateRequest } from "../src/contract";
import type { Prompt } from "../src/adapters/prompt";
import { GenerationError, ImageGenerationError, type GenerationLimits, type GenerationResult, type ImagePrompt, type ImageTarget, type LlmUsage, type TextTarget } from "../src/ports";
import { httpFailureKind, retryAfterMs } from "../src/providers/failure";
import type { ImageProviderDescriptor, ProviderContext, TextProviderDescriptor } from "../src/providers/types";

export const FAKE_TEXT_URL = "https://eu.fake-llm.example.invalid/v1/chat/completions";
export const FAKE_IMAGE_URL = "https://fake-pictures.example.invalid/v1/draw";
export const FAKE_BALANCE_URL = "https://fake-pictures.example.invalid/v1/credits";

/** The request body: everything provider-specific stays here. */
export function fakeTextBody(prompt: Prompt, model: string, limits: GenerationLimits): Record<string, unknown> {
  return {
    model,
    messages: [
      { role: "system", content: prompt.systemInstruction },
      { role: "user", content: prompt.userText },
    ],
    response_format: { type: "json_schema", json_schema: { name: "recipe", strict: true, schema: prompt.schema } },
    reasoning_effort: "none",
    max_tokens: limits.maxOutputTokens,
    temperature: 0.8,
    stream: true,
    stream_options: { include_usage: true },
  };
}

type Delta = { content?: string | { type: string; text?: string }[] | null };
type Chunk = { model?: string; choices?: { delta?: Delta; finish_reason?: string | null }[]; usage?: { prompt_tokens?: number; completion_tokens?: number } };

/** The text of one streamed delta: a string, or the text parts of a list. */
export function deltaText(d: Delta | undefined): string {
  const c = d?.content;
  if (typeof c === "string") return c;
  if (Array.isArray(c)) return c.map((p) => (p && p.type === "text" && typeof p.text === "string" ? p.text : "")).join("");
  return "";
}

export class FakeTextAdapter {
  constructor(private readonly ctx: ProviderContext) {}

  async generate(_req: GenerateRequest, prompt: Prompt, signal: AbortSignal, limits: GenerationLimits, target: TextTarget): Promise<GenerationResult> {
    let key: string;
    try {
      key = await this.ctx.secret();
    } catch {
      throw new GenerationError("infra", "fake key unavailable", none(target.model), undefined, "config");
    }
    const doFetch = this.ctx.fetch ?? fetch;
    let res: Response;
    try {
      res = await doFetch(FAKE_TEXT_URL, {
        method: "POST",
        headers: { authorization: `Bearer ${key}`, "content-type": "application/json" },
        body: JSON.stringify(fakeTextBody(prompt, target.model, limits)),
        signal,
      });
    } catch {
      throw new GenerationError("infra", signal.aborted ? "timeout" : "fake unreachable", signal.aborted ? undefined : none(target.model));
    }
    if (!res.ok) {
      // The text is never retried at the same provider (a capacity failure goes to the fallback),
      // so Retry-After is not read here. Refused before anything was generated: known usage of nothing (the fallback may be asked).
      throw new GenerationError("infra", `fake ${res.status}`, none(target.model), undefined, httpFailureKind(res.status));
    }
    let text = "";
    let finish: string | null = null;
    let usage: LlmUsage | undefined;
    let model = target.model;
    for (const line of (await res.text()).split("\n")) {
      if (!line.startsWith("data: ") || line === "data: [DONE]") continue;
      const chunk = JSON.parse(line.slice(6)) as Chunk;
      if (chunk.model) model = chunk.model;
      const choice = chunk.choices?.[0];
      text += deltaText(choice?.delta);
      if (choice?.finish_reason) finish = choice.finish_reason;
      if (chunk.usage) {
        usage = { inputTokens: chunk.usage.prompt_tokens ?? 0, outputTokens: chunk.usage.completion_tokens ?? 0, thinkingTokens: 0, model, finishReason: finish };
      }
      if (text.length > limits.hardTokenCeiling * 8) throw new GenerationError("infra", "token ceiling exceeded");
    }
    if (usage) usage = { ...usage, model, finishReason: finish };
    if (finish === "length") throw new GenerationError("content", "finish length", usage, undefined, "content");
    try {
      return { output: JSON.parse(text), ...(usage ? { usage } : {}) };
    } catch {
      throw new GenerationError("content", "response is not JSON", usage, undefined, "content");
    }
  }
}

const none = (model: string): LlmUsage => ({ inputTokens: 0, outputTokens: 0, thinkingTokens: 0, model, finishReason: null });

export const fakeText: TextProviderDescriptor = {
  kind: "text",
  id: "fakeeu",
  label: "Fake EU text provider (tests only)",
  processing: "nowhere (tests only)",
  privacy: { approved: true, missing: "" },
  secret: { id: "fakeeu", param: "FAKEEU_API_KEY_SECRET", name: "FAKEEU_API_KEY", missing: "The fake key is not readable" },
  defaultModel: "fake-small",
  // The EU endpoint's 1.1x of a list price of 0.10 / 0.30 (small) and, dearer than Gemini on
  // purpose, 2.00 / 30.00 (large).
  prices: {
    "fake-small": { inputUsdPerMTok: 0.11, outputUsdPerMTok: 0.33 },
    "fake-large": { inputUsdPerMTok: 2.2, outputUsdPerMTok: 33 },
  },
  create: (ctx) => new FakeTextAdapter(ctx),
};

export class FakeImageAdapter {
  constructor(private readonly ctx: ProviderContext) {}

  async generate(prompt: ImagePrompt, target: ImageTarget, signal: AbortSignal) {
    const key = await this.ctx.secret().catch(() => "");
    if (!key) throw new ImageGenerationError("config", "fake key unavailable", false);
    const res = await (this.ctx.fetch ?? fetch)(FAKE_IMAGE_URL, {
      method: "POST",
      headers: { "x-key": key, "content-type": "application/json" },
      body: JSON.stringify({ model: target.model, prompt: prompt.text, aspect_ratio: prompt.aspectRatio, safety_tolerance: 0 }),
      signal,
    });
    if (!res.ok) {
      const kind = httpFailureKind(res.status);
      const wait = retryAfterMs(res.headers.get("retry-after"), Date.now());
      if (kind === "out_of_credit") throw new ImageGenerationError("out_of_credit", `fake ${res.status}`, false, false, res.status);
      if (kind === "config") throw new ImageGenerationError("config", `fake ${res.status}`, false, false, res.status);
      throw new ImageGenerationError("infra", `fake ${res.status}`, res.status >= 500, kind === "capacity", res.status, wait);
    }
    const body = (await res.json()) as { jpeg?: string };
    return { bytes: Buffer.from(body.jpeg ?? "", "base64"), mimeType: "image/jpeg", model: target.model };
  }
}

export const fakeImage: ImageProviderDescriptor = {
  kind: "image",
  id: "fakepix",
  label: "Fake picture provider (tests only)",
  processing: "nowhere (tests only)",
  privacy: { approved: false, missing: "The fake picture provider is not in the privacy policy" },
  secret: { id: "fakepix", param: "FAKEPIX_API_KEY_SECRET", name: "FAKEPIX_API_KEY", missing: "The fake picture key is not readable" },
  usdPerImage: { "fake-draw": 0.03 },
  create: (ctx) => new FakeImageAdapter(ctx),
  async balance(ctx, signal) {
    const res = await (ctx.fetch ?? fetch)(FAKE_BALANCE_URL, { headers: { "x-key": await ctx.secret() }, signal });
    if (!res.ok) throw new Error("balance unavailable");
    const body = (await res.json()) as { credits?: unknown };
    if (typeof body.credits !== "number") throw new Error("balance unreadable");
    return { amount: body.credits, unit: "credits" };
  },
};
