/**
 * The common interface every recipe-text and recipe-picture provider is reached through (README
 * "Adding a provider"). A provider is one adapter file exporting a descriptor (below) plus one
 * entry in providers/registry.ts; nothing in core/ names a provider.
 *
 * - A **text provider** (TextProvider) takes the server's prompt (system instruction, user turn,
 *   JSON schema, all provider-neutral: adapters/prompt.ts), a target (provider id + model), the
 *   caller's abort signal and the token limits, and answers the parsed JSON and the token usage,
 *   or throws GenerationError with a failure class (money) and a kind (the shared taxonomy below).
 * - A **picture provider** (ImageProvider) takes the server's fixed picture prompt and a target and
 *   answers the bytes (and token counts when it reports them), or throws ImageGenerationError.
 *
 * Everything provider-specific stays inside the adapter: endpoint, authentication, the schema
 * dialect (Vertex responseJsonSchema, OpenAI-style json_schema strict), reasoning or thinking
 * settings, safety settings (or none, where the provider has none: the server's own output checks
 * still run), token accounting, streaming shape, the price table and the mapping of its errors to
 * the taxonomy. The core decides money, retries, fallback, timeouts and logs from the interface
 * alone.
 */
import type { GenerateRequest } from "../contract";
import type { Prompt } from "../adapters/prompt";
import type { GeneratedImage, GenerationLimits, GenerationResult, ImagePrompt, ImageTarget, TextTarget } from "../ports";

/**
 * The shared failure taxonomy of every provider call (providers/failure.ts maps HTTP answers to
 * it). What each kind means for the person and the money:
 * - `config`: the provider cannot be called at all (no adapter, no or refused key, a model it
 *   doesn't serve). Nothing was sent or billed. The fallback may be asked when it is at another
 *   provider.
 * - `infra`: anything else technical (network, an unexpected status, an unusable answer).
 * - `capacity`: quota, rate limit or overload (429, 500/502/503/504): worth another attempt
 *   after a pause, or the fallback.
 * - `safety`: the provider's own safety system refused the prompt or the answer. Never retried,
 *   never sent to the fallback (a prompt is not shopped around).
 * - `content`: the provider answered, but not with something usable (cut off at the output
 *   limit, not JSON, empty).
 * - `out_of_credit`: the provider's prepaid balance is empty or its spending limit reached (HTTP
 *   402, or a 429 that says so). Nothing was made; the core logs `provider_out_of_credit` (the
 *   provider id only, never its message) and asks the fallback, or the default provider when no
 *   fallback is named.
 */
export const PROVIDER_FAILURE_KINDS = ["config", "infra", "capacity", "safety", "content", "out_of_credit"] as const;
export type ProviderFailureKind = (typeof PROVIDER_FAILURE_KINDS)[number];

/** USD per 1M tokens; thinking or reasoning tokens are billed as output. */
export interface TokenPrices {
  inputUsdPerMTok: number;
  outputUsdPerMTok: number;
}

/** USD per 1M tokens of a picture model that reports token counts. */
export interface ImageTokenPrices {
  input: number;
  /** Text output and thinking. */
  outputText: number;
  outputImage: number;
}

/** What a balance probe reports: an amount in the provider's own unit (USD, credits ...). */
export interface ProviderBalance {
  amount: number;
  /** A short fixed unit name, e.g. "usd" or "credits". */
  unit: string;
}

/** A deploy parameter a provider reads (defined generically by src/index.ts with defineString). */
export interface ProviderParam {
  name: string;
  default: string;
  description: string;
}

/**
 * A provider's API key in Secret Manager. It is never bound with defineSecret (that would require
 * the secret at every deploy, whichever provider is in use): src/index.ts reads it through
 * adapters/secretManager.ts SecretReader, lazily, on the first call that needs it, keeps it in
 * memory 10 minutes, and never logs, stores or returns it. The functions' service account needs
 * roles/secretmanager.secretAccessor on this secret alone (docs/recipe-setup.md).
 */
export interface ProviderSecret {
  /** Stable id used by profiles' readiness checks, e.g. "openai". */
  id: string;
  /** The deploy parameter naming the secret, e.g. OPENAI_API_KEY_SECRET. */
  param: string;
  /** The secret's default name in Secret Manager, e.g. OPENAI_API_KEY. */
  name: string;
  /** Plain words for the console and the command when it can't be read (never its value or path). */
  missing: string;
}

/** What an adapter is given to build itself and to call out. */
export interface ProviderContext {
  /** The Google Cloud project the functions run in. */
  project: string;
  /** The value of one of the provider's own declared params (its default when unset). */
  param(name: string): string;
  /** Its declared secret's value, read on first use; rejects with a fixed message when unreadable. */
  secret(): Promise<string>;
  /** For tests; absent: the global fetch. */
  fetch?: typeof fetch;
}

/** The parts every provider descriptor has. */
interface DescriptorBase {
  /** Stable id, as written in config/runtime and the profiles; never reused for another provider. */
  id: string;
  label: string;
  /** Where it processes the prompt, in plain words (for the console and the privacy decision). */
  processing: string;
  /**
   * The owner's privacy approval of this provider, normally once the privacy policy's processor
   * list names it (README "What a new provider means for privacy"; bfl was approved before that,
   * 2026-11-18). false keeps every profile using it listed but never
   * switchable; the text is what the console shows.
   */
  privacy: { approved: boolean; missing: string };
  secret?: ProviderSecret;
  params?: readonly ProviderParam[];
  /**
   * Optional balance probe (prepaid providers): the scheduled `checkProviderBalances` calls it for
   * providers in use with a threshold in `config/runtime.providerBalanceAlerts`, and logs
   * `provider_balance_low` under it. Throws on any failure (a fixed message).
   */
  balance?(ctx: ProviderContext, signal: AbortSignal): Promise<ProviderBalance>;
}

/** The port a text adapter implements (RecipeGenerator with the target required). */
export interface TextProvider {
  /**
   * Sends `prompt` to `target.model`. Must honour `signal` and `limits` (abort past
   * limits.hardTokenCeiling as an infra failure). Throws GenerationError; a failure before
   * anything was generated carries the usage it knows (0 output tokens), which is what lets the
   * core ask a fallback without booking the worst case twice.
   */
  generate(request: GenerateRequest, prompt: Prompt, signal: AbortSignal, limits: GenerationLimits, target: TextTarget): Promise<GenerationResult>;
}

export interface TextProviderDescriptor extends DescriptorBase {
  kind: "text";
  /** Its default model when a profile or config names none. */
  defaultModel: string;
  /** The deploy parameter that overrides defaultModel (e.g. GEMINI_MODEL), if any. */
  defaultModelParam?: string;
  /** USD per 1M tokens by model; a model without a price is never selected (fail closed). */
  prices: Readonly<Record<string, TokenPrices>>;
  /**
   * The provider whose prices are `config/runtime.prices.inputUsdPerMTok` / `outputUsdPerMTok`
   * (the original, single-provider override, kept so an existing config means what it meant):
   * only "google". Other providers' overrides go in `prices.textUsdPerMTok["<provider>/<model>"]`.
   */
  runtimeTokenPrices?: boolean;
  create(ctx: ProviderContext): TextProvider;
}

/**
 * A picture provider's own times while it is the selected (primary) picture model, each defaulting
 * to constants.ts IMAGE (providers/registry.ts imageTimingOf); the fallback's attempt keeps
 * IMAGE.abortMs whichever provider it is at.
 * - `abortMs`: each attempt of this provider is aborted after this long.
 * - `budgetMs`: the picture's budget by level, from the start of the pictures.
 * - `reservationExtraMs`: how much longer a request with pictures is reserved, by level.
 * providerRegistryProblems checks that the dish's budget keeps IMAGE.minAttemptMs for the fallback
 * after a timed-out attempt and that each reservation covers its budget; test/recipeImage.test.ts
 * checks every provider's times against the reservation, the function timeout and the apps' waits.
 */
export interface ImageTiming {
  abortMs: number;
  budgetMs: Readonly<Record<"dish" | "steps", number>>;
  reservationExtraMs: Readonly<Record<"dish" | "steps", number>>;
}

/** The port a picture adapter implements. */
export interface ImageProvider {
  /** Must honour `signal`. Throws ImageGenerationError. */
  generate(prompt: ImagePrompt, target: ImageTarget, signal: AbortSignal): Promise<GeneratedImage>;
}

export interface ImageProviderDescriptor extends DescriptorBase {
  kind: "image";
  /**
   * USD per delivered picture by model: reserved against the spend ceilings for every picture a
   * request may get, and booked when the provider reported no token counts (or for a failed call
   * that may have been billed). `config/runtime.prices.imageUsdPerImage` adds to or overrides it.
   */
  usdPerImage: Readonly<Record<string, number>>;
  /** Token rates of the models whose answers report token counts (booked instead of the flat price). */
  tokenPrices?: Readonly<Record<string, ImageTokenPrices>>;
  /** Longer times while it is the selected model (ImageTiming); absent: constants.ts IMAGE. */
  timing?: Partial<ImageTiming>;
  create(ctx: ProviderContext): ImageProvider;
}

export type ProviderDescriptor = TextProviderDescriptor | ImageProviderDescriptor;
