/**
 * The recipe picture's providers, behind the one provider-neutral port RecipeImageGenerator
 * (ports.ts). Which one answers is chosen per call from `config/runtime.image` (over the
 * IMAGE_PROVIDER / IMAGE_MODEL parameters) by ImageRouter, so a switch of provider or model needs
 * no change to core/ and, for a provider whose credentials are already in place, no deploy.
 * Whatever a provider returns is re-encoded to the same JPEG by core/recipeImage.ts
 * (adapters/imageNormalize.ts).
 *
 * What every provider receives: only the picture prompt, the server's fixed template around the
 * text model's checked English brief (core/recipeImage.ts). The server copies nothing the person
 * typed into it (a product name the text model chose to repeat in its brief may appear), and
 * never the system instruction, a uid or any identifier of the person. Each adapter keeps
 * the provider's own safety filters on and asks for no people where the API has such a setting.
 * Error messages are fixed texts with at most an HTTP status: a provider's own error text could
 * quote the prompt.
 */
import { ApiError, FinishReason, GoogleGenAI, MediaModality, Modality, type GenerateContentParameters, type GenerateContentResponse } from "@google/genai";
import { safetySettingsFor } from "./gemini";
import { ImageGenerationError, type GeneratedImage, type ImagePrompt, type ImageTarget, type ImageUsage, type RecipeImageGenerator } from "../ports";

/** HTTP statuses worth one more attempt after a pause: quota or rate limit, overload, a passing server error. */
const RETRYABLE_5XX: ReadonlySet<number> = new Set([500, 502, 503, 504]);

/** A count from a provider's usage report: a finite non-negative integer, else 0. */
const count = (v: unknown): number => (typeof v === "number" && Number.isFinite(v) && v >= 0 ? Math.floor(v) : 0);

// --- Vertex AI (Gemini image models) -------------------------------------------------------------

/** The one SDK call the Vertex adapter uses, so tests can stand in for Vertex AI. */
export interface ImageModels {
  generateContent(params: GenerateContentParameters): Promise<GenerateContentResponse>;
}

export interface VertexImageOptions {
  project: string;
  /** Nano Banana 2.1 is offered on the `global` endpoint only (VERTEX_IMAGE_LOCATION). */
  location: string;
}

/** Finish reasons meaning the model's own safety system refused the picture. */
const IMAGE_SAFETY_FINISH: ReadonlySet<string> = new Set([
  FinishReason.SAFETY,
  FinishReason.PROHIBITED_CONTENT,
  FinishReason.BLOCKLIST,
  FinishReason.SPII,
  FinishReason.IMAGE_SAFETY,
  FinishReason.IMAGE_PROHIBITED_CONTENT,
]);

/**
 * The whole Vertex call for a picture prompt: image output only, one picture in the prompt's
 * aspect ratio (4:3: 1200 x 896) at 1K (the smallest size Nano Banana 2.1 accepts; 1K, 2K and 4K
 * only, its model page read 2026-11-17), JPEG, no people (`personGeneration: ALLOW_NONE`), and
 * the same explicit safety settings as the recipe text (adapters/gemini.ts safetySettingsFor).
 */
export function vertexImageParams(prompt: ImagePrompt, model: string): GenerateContentParameters {
  return {
    model,
    contents: [{ role: "user", parts: [{ text: prompt.text }] }],
    config: {
      responseModalities: [Modality.IMAGE],
      candidateCount: 1,
      imageConfig: {
        aspectRatio: prompt.aspectRatio,
        imageSize: "1K",
        personGeneration: "ALLOW_NONE",
        imageOutputOptions: { mimeType: "image/jpeg", compressionQuality: 85 },
      },
      safetySettings: safetySettingsFor(false),
    },
  };
}

export class VertexImageGenerator implements RecipeImageGenerator {
  private models: ImageModels | undefined;

  constructor(private readonly options: VertexImageOptions, models?: ImageModels) {
    this.models = models;
  }

  async generate(prompt: ImagePrompt, target: ImageTarget, signal: AbortSignal): Promise<GeneratedImage> {
    this.models ??= new GoogleGenAI({ vertexai: true, project: this.options.project, location: this.options.location }).models;
    const params = vertexImageParams(prompt, target.model);
    let res: GenerateContentResponse;
    try {
      res = await this.models.generateContent({ ...params, config: { ...params.config, abortSignal: signal } });
    } catch (e) {
      if (signal.aborted) throw new ImageGenerationError("infra", "timeout", true);
      // A refused request (4xx, 429 RESOURCE_EXHAUSTED included) was not billed; a network failure
      // or 5xx may have been. 429 and the passing 5xx are worth another attempt.
      const status = e instanceof ApiError ? Number(e.status) || 0 : 0;
      throw new ImageGenerationError(
        "infra",
        `Vertex AI ${status || "error"}`,
        !(status >= 400 && status < 500),
        status === 429 || RETRYABLE_5XX.has(status),
        status || undefined,
      );
    }
    if (res.promptFeedback?.blockReason) throw new ImageGenerationError("safety", "prompt blocked", false);
    const candidate = res.candidates?.[0];
    const finish = candidate?.finishReason ? String(candidate.finishReason) : null;
    if (finish && IMAGE_SAFETY_FINISH.has(finish)) throw new ImageGenerationError("safety", `finish ${finish.slice(0, 40)}`, true);
    const part = candidate?.content?.parts?.find((p) => p.inlineData?.data && (p.inlineData.mimeType ?? "").startsWith("image/"));
    if (!part?.inlineData?.data) throw new ImageGenerationError("infra", `no image${finish ? ` (finish ${finish.slice(0, 40)})` : ""}`, true);
    const usage = vertexImageUsage(res);
    return {
      bytes: Buffer.from(part.inlineData.data, "base64"),
      mimeType: part.inlineData.mimeType ?? "",
      model: res.modelVersion ?? target.model,
      ...(usage ? { usage } : {}),
    };
  }
}

/**
 * The token counts of a Vertex answer (`usageMetadata`): the image tokens from
 * `candidatesTokensDetails` (modality IMAGE; all of `candidatesTokenCount` without details), the
 * rest of the candidates' tokens and `thoughtsTokenCount` as text output. Undefined without a
 * positive `candidatesTokenCount` (the flat price is booked then).
 */
export function vertexImageUsage(res: GenerateContentResponse): ImageUsage | undefined {
  const u = res.usageMetadata;
  const candidates = count(u?.candidatesTokenCount);
  if (!u || candidates === 0) return undefined;
  const details = u.candidatesTokensDetails;
  const image = Array.isArray(details) && details.length > 0
    ? details.filter((d) => d.modality === MediaModality.IMAGE).reduce((s, d) => s + count(d.tokenCount), 0)
    : candidates;
  return {
    inputTokens: count(u.promptTokenCount),
    outputTextTokens: Math.max(0, candidates - image) + count(u.thoughtsTokenCount),
    outputImageTokens: image,
  };
}

// --- OpenAI (GPT Image models, Image API) --------------------------------------------------------

export const OPENAI_IMAGES_URL = "https://api.openai.com/v1/images/generations";

export interface OpenAIImageOptions {
  /** The API key, read only when this provider is called (adapters/secretManager.ts). */
  apiKey: () => Promise<string>;
  fetch?: typeof fetch;
  /** `quality`: low, medium or high (the price in DEFAULT_IMAGE_PRICES assumes medium). */
  quality?: "low" | "medium" | "high";
}

/** The response body's parts the adapter reads (the rest is ignored). */
interface OpenAIImagesResponse {
  data?: { b64_json?: string }[];
  output_format?: string;
  usage?: {
    input_tokens?: number;
    output_tokens?: number;
    output_tokens_details?: { image_tokens?: number; text_tokens?: number };
  };
  error?: { code?: string | null; type?: string | null };
}

/**
 * The models that take any WIDTHxHEIGHT (both divisible by 16, ratio 1:3 to 3:1), as the API
 * reference lists them (read 2026-11-17). Every other GPT Image model takes only 1024x1024,
 * 1536x1024 and 1024x1536 (or "auto").
 */
const OPENAI_FREE_SIZE_MODELS: ReadonlySet<string> = new Set([
  "gpt-image-2",
  "gpt-image-2-2026-04-21",
  "gpt-image-2.5-sunburst",
  "gpt-image-2.5-sunburst-2026-09-08",
  "gpt-image-2.5-flare",
  "gpt-image-2.5-flare-2026-09-08",
]);

/**
 * The `size` for an aspect ratio: 4:3 exactly (1024x768, about 1K like the other providers) where
 * the model takes any size, else the nearest landscape standard size, 1536x1024 (3:2).
 */
export function openAISize(model: string, aspectRatio: ImagePrompt["aspectRatio"]): string {
  if (aspectRatio === "4:3") return OPENAI_FREE_SIZE_MODELS.has(model) ? "1024x768" : "1536x1024";
  return "1024x1024";
}

/**
 * The request body of POST /v1/images/generations (OpenAI API reference, "Create image", read
 * 2026-11-17): one JPEG in the prompt's shape (openAISize), compression 85, moderation "auto" (the
 * stricter of the two). No `safety_identifier` and no `user`: OpenAI gets no identifier of the
 * person.
 */
export function openAIImageBody(prompt: ImagePrompt, model: string, quality: "low" | "medium" | "high"): Record<string, unknown> {
  return {
    model,
    prompt: prompt.text,
    n: 1,
    size: openAISize(model, prompt.aspectRatio),
    quality,
    output_format: "jpeg",
    output_compression: 85,
    moderation: "auto",
  };
}

const MODERATION = /moderation|safety|content_policy/i;

export class OpenAIImageGenerator implements RecipeImageGenerator {
  constructor(private readonly options: OpenAIImageOptions) {}

  async generate(prompt: ImagePrompt, target: ImageTarget, signal: AbortSignal): Promise<GeneratedImage> {
    let key: string;
    try {
      key = await this.options.apiKey();
    } catch {
      throw new ImageGenerationError("config", "OpenAI API key unavailable", false);
    }
    if (!key) throw new ImageGenerationError("config", "OpenAI API key unavailable", false);
    const doFetch = this.options.fetch ?? fetch;
    let res: Response;
    try {
      res = await doFetch(OPENAI_IMAGES_URL, {
        method: "POST",
        headers: { authorization: `Bearer ${key}`, "content-type": "application/json" },
        body: JSON.stringify(openAIImageBody(prompt, target.model, this.options.quality ?? "medium")),
        signal,
      });
    } catch {
      if (signal.aborted) throw new ImageGenerationError("infra", "timeout", true);
      throw new ImageGenerationError("infra", "OpenAI unreachable", true);
    }
    let body: OpenAIImagesResponse;
    try {
      body = (await res.json()) as OpenAIImagesResponse;
    } catch {
      if (signal.aborted) throw new ImageGenerationError("infra", "timeout", true);
      throw new ImageGenerationError("infra", `OpenAI ${res.status} unreadable`, res.ok);
    }
    if (!res.ok) {
      const code = `${body.error?.code ?? ""} ${body.error?.type ?? ""}`;
      if (res.status === 400 && MODERATION.test(code)) throw new ImageGenerationError("safety", "OpenAI moderation", false);
      if (res.status === 401 || res.status === 403) throw new ImageGenerationError("config", `OpenAI ${res.status}`, false);
      // A refused request is not billed; a 5xx may have been. A rate limit (429, but not an
      // exhausted account, `insufficient_quota`) and the passing 5xx are worth another attempt.
      const retryable = (res.status === 429 && !/insufficient_quota/.test(code)) || RETRYABLE_5XX.has(res.status);
      throw new ImageGenerationError("infra", `OpenAI ${res.status}`, res.status >= 500, retryable, res.status);
    }
    const b64 = body.data?.[0]?.b64_json;
    if (typeof b64 !== "string" || !b64) throw new ImageGenerationError("infra", "OpenAI no image", true);
    const usage = openAIImageUsage(body);
    return { bytes: Buffer.from(b64, "base64"), mimeType: `image/${body.output_format ?? "jpeg"}`, model: target.model, ...(usage ? { usage } : {}) };
  }
}

/** The token counts of an OpenAI answer (`usage`); undefined without a positive `output_tokens`. */
function openAIImageUsage(body: OpenAIImagesResponse): ImageUsage | undefined {
  const u = body.usage;
  const out = count(u?.output_tokens);
  if (!u || out === 0) return undefined;
  const d = u.output_tokens_details;
  const image = d && d.image_tokens !== undefined ? count(d.image_tokens) : out;
  return { inputTokens: count(u.input_tokens), outputTextTokens: Math.max(0, out - image), outputImageTokens: image };
}

// --- Choosing the provider -----------------------------------------------------------------------

/**
 * Sends each call to the adapter of `target.provider`. Adapters are built on first use, so a
 * provider that is never selected is never constructed and its credentials are never read. A
 * provider without an adapter is a "config" failure (nothing sent, nothing billed).
 */
export class ImageRouter implements RecipeImageGenerator {
  private readonly built = new Map<string, RecipeImageGenerator>();

  constructor(private readonly factories: Readonly<Record<string, () => RecipeImageGenerator>>) {}

  generate(prompt: ImagePrompt, target: ImageTarget, signal: AbortSignal): Promise<GeneratedImage> {
    const factory = Object.prototype.hasOwnProperty.call(this.factories, target.provider) ? this.factories[target.provider] : undefined;
    if (!factory) return Promise.reject(new ImageGenerationError("config", "no such image provider", false));
    let adapter = this.built.get(target.provider);
    if (!adapter) {
      adapter = factory();
      this.built.set(target.provider, adapter);
    }
    return adapter.generate(prompt, target, signal);
  }
}
