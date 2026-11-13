/**
 * RecipeGenerator backed by Gemini on Vertex AI, through @google/genai with `vertexai: true`. It
 * authenticates as the function's service account (Application Default Credentials), so there is
 * no API key. Schema-constrained JSON, capped output, low thinking, safety filters on, no tools.
 */
import {
  ApiError,
  FinishReason,
  GoogleGenAI,
  HarmBlockThreshold,
  HarmCategory,
  ThinkingLevel,
  type GenerateContentConfig,
  type ThinkingConfig,
} from "@google/genai";
import { MAX_OUTPUT_TOKENS } from "../constants";
import type { GenerateRequest } from "../contract";
import { GenerationError, type RecipeGenerator } from "../ports";
import { SYSTEM_INSTRUCTION, buildPrompt, responseSchema } from "./prompt";

export interface GeminiOptions {
  project: string;
  location: string;
  model: string;
  /** Gemini 3+ thinking level, e.g. LOW. Gemini 2.x models get thinkingBudget 0 instead. */
  thinkingLevel: string;
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

export function thinkingConfigFor(model: string, level: string): ThinkingConfig {
  if (/^gemini-2\./.test(model)) return { thinkingBudget: 0 };
  const known = Object.values(ThinkingLevel) as string[];
  return { thinkingLevel: (known.includes(level) ? level : ThinkingLevel.LOW) as ThinkingLevel };
}

export class GeminiRecipeGenerator implements RecipeGenerator {
  private readonly client: GoogleGenAI;

  constructor(private readonly options: GeminiOptions) {
    this.client = new GoogleGenAI({ vertexai: true, project: options.project, location: options.location });
  }

  async generate(request: GenerateRequest, signal: AbortSignal): Promise<unknown> {
    const config: GenerateContentConfig = {
      systemInstruction: SYSTEM_INSTRUCTION,
      responseMimeType: "application/json",
      responseJsonSchema: responseSchema(request),
      maxOutputTokens: MAX_OUTPUT_TOKENS[request.kind],
      temperature: 0.8,
      candidateCount: 1,
      thinkingConfig: thinkingConfigFor(this.options.model, this.options.thinkingLevel),
      safetySettings: SAFETY_CATEGORIES.map((category) => ({
        category,
        threshold: HarmBlockThreshold.BLOCK_MEDIUM_AND_ABOVE,
      })),
      abortSignal: signal,
    };
    let response;
    try {
      response = await this.client.models.generateContent({
        model: this.options.model,
        contents: [{ role: "user", parts: [{ text: buildPrompt(request) }] }],
        config,
      });
    } catch (e) {
      if (signal.aborted) throw new GenerationError("infra", "timeout");
      if (e instanceof ApiError) throw new GenerationError("infra", `Vertex AI ${e.status}`);
      throw new GenerationError("infra", `Vertex AI call failed: ${String(e).slice(0, 120)}`);
    }
    if (response.promptFeedback?.blockReason) {
      throw new GenerationError("content", `prompt blocked: ${response.promptFeedback.blockReason}`);
    }
    const candidate = response.candidates?.[0];
    const finish = candidate?.finishReason;
    if (finish && CONTENT_FINISH.has(finish)) throw new GenerationError("content", `finish ${finish}`);
    const text = response.text;
    if (!text) throw new GenerationError("content", "empty response");
    try {
      return JSON.parse(text);
    } catch {
      throw new GenerationError("content", "response is not JSON");
    }
  }
}
