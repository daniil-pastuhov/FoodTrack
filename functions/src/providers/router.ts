/**
 * Sends each call to the adapter of its target's provider. Adapters are built on first use, so a
 * provider that is never selected is never constructed and its credentials are never read. A
 * provider without an adapter is a "config" failure: nothing sent, nothing billed, and the
 * fallback (core/generation.ts, core/recipeImage.ts) may be asked.
 */
import type { GenerateRequest } from "../contract";
import type { Prompt } from "../adapters/prompt";
import {
  GenerationError,
  ImageGenerationError,
  type GeneratedImage,
  type GenerationLimits,
  type GenerationResult,
  type ImagePrompt,
  type ImageTarget,
  type RecipeGenerator,
  type RecipeImageGenerator,
  type TextTarget,
} from "../ports";
import type { ImageProvider, TextProvider } from "./types";

class Built<T> {
  private readonly built = new Map<string, T>();

  constructor(private readonly factories: Readonly<Record<string, () => T>>) {}

  get(provider: string): T | undefined {
    const factory = Object.prototype.hasOwnProperty.call(this.factories, provider) ? this.factories[provider] : undefined;
    if (!factory) return undefined;
    let adapter = this.built.get(provider);
    if (!adapter) {
      adapter = factory();
      this.built.set(provider, adapter);
    }
    return adapter;
  }
}

export class ImageRouter implements RecipeImageGenerator {
  private readonly adapters: Built<RecipeImageGenerator | ImageProvider>;

  constructor(factories: Readonly<Record<string, () => RecipeImageGenerator | ImageProvider>>) {
    this.adapters = new Built(factories);
  }

  generate(prompt: ImagePrompt, target: ImageTarget, signal: AbortSignal): Promise<GeneratedImage> {
    const adapter = this.adapters.get(target.provider);
    if (!adapter) return Promise.reject(new ImageGenerationError("config", "no such image provider", false));
    return adapter.generate(prompt, target, signal);
  }
}

/** The recipe text's router; a call without a target goes to `defaultTarget`. */
export class TextRouter implements RecipeGenerator {
  private readonly adapters: Built<TextProvider>;

  constructor(factories: Readonly<Record<string, () => TextProvider>>, private readonly defaultTarget: TextTarget) {
    this.adapters = new Built(factories);
  }

  generate(request: GenerateRequest, prompt: Prompt, signal: AbortSignal, limits: GenerationLimits, target?: TextTarget): Promise<GenerationResult> {
    const t = target ?? this.defaultTarget;
    const adapter = this.adapters.get(t.provider);
    if (!adapter) {
      // Nothing was sent: known usage of nothing, so the core may ask the fallback.
      const none = { inputTokens: 0, outputTokens: 0, thinkingTokens: 0, model: t.model, finishReason: null };
      return Promise.reject(new GenerationError("infra", "no such text provider", none, undefined, "config"));
    }
    return adapter.generate(request, prompt, signal, limits, t);
  }
}
