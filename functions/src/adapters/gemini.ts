/**
 * RecipeGenerator backed by Gemini on Vertex AI, through @google/genai with `vertexai: true`. It
 * authenticates as the function's service account (Application Default Credentials), so there is
 * no API key. Schema-constrained JSON, capped output, low thinking, safety filters on, no tools.
 *
 * The call is streamed so it can be stopped early: after every chunk, the visible text and any
 * usageMetadata the chunk carries are checked against the hard token ceiling, and the request is
 * aborted past it. What this can and cannot bound:
 * - Visible output is observed chunk by chunk (and is capped by maxOutputTokens anyway).
 * - Thinking is not streamed (includeThoughts is off), so thinking tokens are seen only through
 *   usageMetadata, when Vertex attaches it to a chunk; thinking that happens before the first chunk
 *   is bounded only by the LLM abort time (TIMINGS.llmAbortMs). Whether Vertex bills tokens
 *   generated after the client aborts is not documented.
 */
import {
  ApiError,
  FinishReason,
  GoogleGenAI,
  HarmBlockThreshold,
  HarmCategory,
  ThinkingLevel,
  type GenerateContentConfig,
  type GenerateContentParameters,
  type GenerateContentResponse,
  type GenerateContentResponseUsageMetadata,
  type ThinkingConfig,
} from "@google/genai";
import type { GenerateRequest } from "../contract";
import { GenerationError, type GenerationLimits, type GenerationResult, type LlmUsage, type RecipeGenerator } from "../ports";
import { SYSTEM_INSTRUCTION, buildPrompt, responseSchema } from "./prompt";

export interface GeminiOptions {
  project: string;
  location: string;
  model: string;
  /** Gemini 3+ thinking level, e.g. LOW. Gemini 2.x models get thinkingBudget 0 instead. */
  thinkingLevel: string;
}

/** The one SDK call the adapter uses, so tests can stand in for Vertex AI. */
export interface StreamingModels {
  generateContentStream(params: GenerateContentParameters): Promise<AsyncGenerator<GenerateContentResponse>>;
}

const SAFETY_CATEGORIES = [
  HarmCategory.HARM_CATEGORY_HARASSMENT,
  HarmCategory.HARM_CATEGORY_HATE_SPEECH,
  HarmCategory.HARM_CATEGORY_SEXUALLY_EXPLICIT,
  HarmCategory.HARM_CATEGORY_DANGEROUS_CONTENT,
];

const CONTENT_FINISH: ReadonlySet<string> = new Set([
  FinishReason.SAFETY,
  FinishReason.PROHIBITED_CONTENT,
  FinishReason.BLOCKLIST,
  FinishReason.SPII,
  FinishReason.RECITATION,
  FinishReason.LANGUAGE,
  FinishReason.MAX_TOKENS,
]);

/** Text longer than this many characters per ceiling token is over the ceiling whatever the tokenisation. */
const MAX_CHARS_PER_TOKEN = 8;

export function thinkingConfigFor(model: string, level: string): ThinkingConfig {
  if (/^gemini-2\./.test(model)) return { thinkingBudget: 0 };
  const known = Object.values(ThinkingLevel) as string[];
  return { thinkingLevel: (known.includes(level) ? level : ThinkingLevel.LOW) as ThinkingLevel };
}

function usageOf(meta: GenerateContentResponseUsageMetadata | undefined, model: string, finish: string | null): LlmUsage {
  return {
    inputTokens: meta?.promptTokenCount ?? 0,
    outputTokens: meta?.candidatesTokenCount ?? 0,
    thinkingTokens: meta?.thoughtsTokenCount ?? 0,
    model,
    finishReason: finish,
  };
}

export class GeminiRecipeGenerator implements RecipeGenerator {
  private readonly models: StreamingModels;

  constructor(private readonly options: GeminiOptions, models?: StreamingModels) {
    this.models = models
      ?? new GoogleGenAI({ vertexai: true, project: options.project, location: options.location }).models;
  }

  async generate(request: GenerateRequest, signal: AbortSignal, limits: GenerationLimits): Promise<GenerationResult> {
    const local = new AbortController();
    const abortSignal = AbortSignal.any([signal, local.signal]);
    const config: GenerateContentConfig = {
      systemInstruction: SYSTEM_INSTRUCTION,
      responseMimeType: "application/json",
      responseJsonSchema: responseSchema(request),
      maxOutputTokens: limits.maxOutputTokens,
      temperature: 0.8,
      candidateCount: 1,
      thinkingConfig: thinkingConfigFor(this.options.model, this.options.thinkingLevel),
      safetySettings: SAFETY_CATEGORIES.map((category) => ({
        category,
        threshold: HarmBlockThreshold.BLOCK_MEDIUM_AND_ABOVE,
      })),
      abortSignal,
    };
    let model = this.options.model;
    let meta: GenerateContentResponseUsageMetadata | undefined;
    let finish: string | null = null;
    let blockReason: string | undefined;
    let text = "";
    // Before the first chunk nothing was generated (a rejected request is not billed for output).
    let started = false;
    const usage = () => usageOf(meta, model, finish);
    const known = () => (started ? usage() : { ...usage(), outputTokens: 0, thinkingTokens: 0 });

    try {
      const stream = await this.models.generateContentStream({
        model: this.options.model,
        contents: [{ role: "user", parts: [{ text: buildPrompt(request) }] }],
        config,
      });
      for await (const chunk of stream) {
        started = true;
        if (chunk.usageMetadata) meta = chunk.usageMetadata;
        if (chunk.modelVersion) model = chunk.modelVersion;
        if (chunk.promptFeedback?.blockReason) blockReason = String(chunk.promptFeedback.blockReason);
        const candidate = chunk.candidates?.[0];
        if (candidate?.finishReason) finish = String(candidate.finishReason);
        for (const part of candidate?.content?.parts ?? []) {
          if (typeof part.text === "string" && !part.thought) text += part.text;
        }
        const over =
          (meta?.candidatesTokenCount ?? 0) + (meta?.thoughtsTokenCount ?? 0) > limits.hardTokenCeiling ||
          text.length > limits.hardTokenCeiling * MAX_CHARS_PER_TOKEN;
        // The last chunk (with a finish reason) ends the call: its output is delivered whatever the
        // final count, since the tokens are spent either way and settle books the measured usage.
        if (over && !candidate?.finishReason) {
          local.abort();
          // Billed for at least the ceiling; the estimate keeps the worst case (no usage passed).
          throw new GenerationError("infra", "token ceiling exceeded");
        }
      }
    } catch (e) {
      if (e instanceof GenerationError) throw e;
      if (signal.aborted) throw new GenerationError("infra", "timeout");
      // An HTTP error before any chunk: nothing was generated. Mid-stream: usage is unknown.
      const reported = started ? undefined : known();
      if (e instanceof ApiError) throw new GenerationError("infra", `Vertex AI ${e.status}`, reported);
      throw new GenerationError("infra", `Vertex AI call failed: ${String(e).slice(0, 120)}`, reported);
    }
    if (signal.aborted) throw new GenerationError("infra", "timeout");
    if (blockReason) throw new GenerationError("content", `prompt blocked: ${blockReason}`, known());
    if (finish && CONTENT_FINISH.has(finish)) throw new GenerationError("content", `finish ${finish}`, usage());
    if (!text) throw new GenerationError("content", "empty response", known());
    try {
      return { output: JSON.parse(text), usage: usage() };
    } catch {
      throw new GenerationError("content", "response is not JSON", usage());
    }
  }
}
