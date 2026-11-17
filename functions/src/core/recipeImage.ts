/**
 * A recipe's picture (README "Recipe pictures"): made after the recipe's text passed every check
 * (output rules, quality gate, judge), within the same reservation and the same generateRecipe
 * call. Nothing here decides money: settle (core/generation.ts) keeps the whole cost when the
 * picture is delivered and gives the picture's share back when it is not.
 *
 * Prompt-injection defence: the picture prompt is the server's fixed template around the text
 * model's `imageBrief`, and that brief is used only after checkImageBrief accepted it (plain
 * ASCII English, every output rule, no people, writing or brands). The server copies nothing the
 * person typed (pantry names, allergens, taste answers) into it, nor the system instruction; the
 * brief is the text model's own English, which saw the pantry, so a product name the model chose
 * to repeat may appear in it (briefs naming the dish's food are expected and not refused).
 * Neither the prompt nor the brief is ever logged or stored.
 */
import { IMAGE, IMAGE_TOKEN_PRICES } from "../constants";
import { ImageNormalizeError, normalizeImage, type NormalizedImage } from "../adapters/imageNormalize";
import { imagePriceMicroUsd, uidHash, type RuntimeConfig } from "../model";
import { ImageGenerationError, type Deps, type ImagePrompt, type ImageTarget, type ImageUsage } from "../ports";

/** The fixed parts of every picture prompt; only the checked brief goes between them. */
export const IMAGE_PROMPT_HEAD = "A photorealistic overhead food photograph of one home-cooked dish, served and ready to eat. The dish:";
export const IMAGE_PROMPT_TAIL = [
  "Soft natural daylight, a plain wooden or stone table, simple everyday tableware, shallow depth of field.",
  "Show only the food and its tableware.",
  "No people, no hands, no faces, no text, no letters, no numbers, no labels, no packaging, no brands, no logos, no watermark.",
].join(" ");

/** The picture prompt for a checked brief (core/outputSafety.ts checkImageBrief). */
export function imagePromptFor(brief: string): ImagePrompt {
  const sentence = /[.]$/.test(brief) ? brief : `${brief}.`;
  return { text: `${IMAGE_PROMPT_HEAD} ${sentence} ${IMAGE_PROMPT_TAIL}`, aspectRatio: IMAGE.aspectRatio };
}

export type ImageOutcome =
  | { ok: true; image: NormalizedImage; microUsd: number; model: string }
  /**
   * `failure`: a fixed code (BRIEF_<rule>, SAFETY, CONFIG, TIMEOUT, INFRA, DECODE, NO_TIME, NO_DISH,
   * SETTLED, MISSING). `microUsd`: what is booked for it, the price of every attempt the provider
   * may have charged for, else 0.
   */
  | { ok: false; failure: string; microUsd: number; model: string | null };

/** The outcome of a picture never asked for, because its brief was refused (nothing sent). */
export function briefRefused(rule: string): ImageOutcome {
  return { ok: false, failure: `BRIEF_${rule}`, microUsd: 0, model: null };
}

/** What one call to one model gave (core of makeRecipeImage's attempts). */
type Attempt =
  | { ok: true; image: NormalizedImage; microUsd: number; model: string; inputBytes: number }
  | {
      ok: false;
      failure: string;
      microUsd: number;
      /** A quota or overload answer: the same model may be asked again after a pause. */
      retryable: boolean;
      /** About the model or capacity, not the brief: the fallback model may be asked. */
      fallbackOk: boolean;
      /** The reason class for the logs: QUOTA, SERVER, NOT_FOUND, REFUSED_REQUEST, TIMEOUT, DECODE, INFRA, SAFETY, CONFIG. */
      reason: string;
      inputBytes: number | null;
    };

/**
 * What one picture costs: from the provider's token counts at IMAGE_TOKEN_PRICES when it reported
 * them and the model has rates there, else the model's flat price (DEFAULT_IMAGE_PRICES and
 * `config/runtime.prices.imageUsdPerImage`). Micro-USD, rounded up.
 */
export function pictureMicroUsd(config: Pick<RuntimeConfig, "prices">, target: ImageTarget, usage: ImageUsage | undefined): number {
  const key = `${target.provider}/${target.model}`;
  const rates = Object.prototype.hasOwnProperty.call(IMAGE_TOKEN_PRICES, key) ? IMAGE_TOKEN_PRICES[key] : undefined;
  if (usage && rates) {
    // Tokens x USD per 1M tokens = micro-USD.
    return Math.ceil(usage.inputTokens * rates.input + usage.outputTextTokens * rates.outputText + usage.outputImageTokens * rates.outputImage);
  }
  return imagePriceMicroUsd(config, target.provider, target.model) ?? 0;
}

function reasonOf(e: ImageGenerationError, aborted: boolean): string {
  if (aborted) return "TIMEOUT";
  if (e.kind !== "infra") return e.kind.toUpperCase();
  const s = e.status ?? 0;
  if (s === 429) return "QUOTA";
  if (s >= 500) return "SERVER";
  if (s === 404) return "NOT_FOUND";
  if (s >= 400) return "REFUSED_REQUEST";
  return "INFRA";
}

async function attemptOnce(
  deps: Pick<Deps, "imageGenerator" | "logger">,
  prompt: ImagePrompt,
  target: ImageTarget,
  config: RuntimeConfig,
  timeoutMs: number,
): Promise<Attempt> {
  const flat = imagePriceMicroUsd(config, target.provider, target.model) ?? 0;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  let inputBytes: number | null = null;
  try {
    const raw = await deps.imageGenerator.generate(prompt, target, controller.signal);
    inputBytes = raw.bytes.length;
    if (controller.signal.aborted) throw new ImageGenerationError("infra", "timeout", true);
    const microUsd = pictureMicroUsd(config, target, raw.usage);
    try {
      const image = await normalizeImage(raw.bytes);
      return { ok: true, image, microUsd, model: raw.model.slice(0, 80), inputBytes };
    } catch (e) {
      if (!(e instanceof ImageNormalizeError)) throw e;
      // The provider answered (and charged): what it sent could not be used.
      return { ok: false, failure: "DECODE", microUsd, retryable: false, fallbackOk: true, reason: "DECODE", inputBytes };
    }
  } catch (e) {
    if (e instanceof ImageGenerationError) {
      const aborted = controller.signal.aborted;
      const failure = aborted ? "TIMEOUT" : e.kind === "safety" ? "SAFETY" : e.kind === "config" ? "CONFIG" : "INFRA";
      return {
        ok: false,
        failure,
        microUsd: e.billed || aborted ? flat : 0,
        retryable: !aborted && e.kind === "infra" && e.retryable,
        // Never after a safety refusal (the brief is not shopped around) or a configuration
        // failure (the fallback is at the same provider): only the model's or capacity's failures.
        fallbackOk: failure === "INFRA" || failure === "TIMEOUT",
        reason: reasonOf(e, aborted),
        inputBytes,
      };
    }
    deps.logger.error("Image generator threw unexpectedly", { error: String(e).slice(0, 200) });
    return { ok: false, failure: "INFRA", microUsd: flat, retryable: false, fallbackOk: false, reason: "INFRA", inputBytes };
  } finally {
    clearTimeout(timer);
  }
}

/** The pause before retry `n` (1-based): IMAGE.retryBaseMs x 2^(n-1), jittered by 0.5..1.5. */
export function retryDelayMs(n: number, random: () => number): number {
  return Math.round(IMAGE.retryBaseMs * 2 ** (n - 1) * (0.5 + random()));
}

const realSleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/**
 * Asks for one picture and re-encodes (or passes through) the answer, finishing by `deadlineAt`
 * (deps.now() time; README "Recipe pictures", IMAGE in constants.ts):
 * 1. the selected model, each attempt aborted after IMAGE.abortMs (less, to leave the fallback
 *    IMAGE.minAttemptMs); a retryable failure (429, 5xx) is asked again after retryDelayMs while
 *    fewer than IMAGE.maxAttempts were made and IMAGE.minAttemptMs (twice that with a fallback)
 *    is still left after the pause; one `image_retry` line per retry;
 * 2. when it still failed for a reason about the model or capacity (an INFRA failure, a
 *    timeout, an answer that could not be decoded; never SAFETY or CONFIG) and a fallback model is
 *    configured (`image.fallbackModel`) and IMAGE.minAttemptMs is left: one attempt of the
 *    fallback model, and one `image_fallback` line.
 * Every attempt the provider may have charged for is booked at its model's price. Never throws:
 * every failure is an ImageOutcome. Logs one `image_call` line (never the prompt).
 */
export async function makeRecipeImage(
  deps: Pick<Deps, "imageGenerator" | "logger" | "now" | "sleep" | "random">,
  uid: string,
  brief: string,
  config: RuntimeConfig,
  deadlineAt: number,
  /** A step's picture: its 0-based index (logs only); absent for the dish's. */
  step?: number,
): Promise<ImageOutcome> {
  const prompt = imagePromptFor(brief);
  const primary: ImageTarget = { provider: config.image.provider, model: config.image.model };
  const fallback: ImageTarget | null = config.image.fallbackModel ? { provider: config.image.provider, model: config.image.fallbackModel } : null;
  const sleep = deps.sleep ?? realSleep;
  const random = deps.random ?? Math.random;
  const picture = step === undefined ? "dish" : "step";
  const started = deps.now();
  const keepForFallback = fallback ? IMAGE.minAttemptMs : 0;
  let booked = 0;
  let attempts = 0;
  let last: Attempt | null = null;
  let madeBy: string | null = null;
  for (let n = 1; ; n++) {
    const left = deadlineAt - deps.now();
    if (left < IMAGE.minAttemptMs) break;
    last = await attemptOnce(deps, prompt, primary, config, Math.min(IMAGE.abortMs, Math.max(IMAGE.minAttemptMs, left - keepForFallback)));
    attempts++;
    booked += last.microUsd;
    madeBy = primary.model;
    if (last.ok || !last.retryable || n >= IMAGE.maxAttempts) break;
    const wait = retryDelayMs(n, random);
    if (deadlineAt - deps.now() - wait < IMAGE.minAttemptMs + keepForFallback) break;
    deps.logger.info("image_retry", {
      uidHash: uidHash(uid),
      provider: primary.provider,
      model: primary.model,
      picture,
      attempt: n + 1,
      reason: last.reason,
      waitMs: wait,
    });
    await sleep(wait);
  }
  if (last && !last.ok && last.fallbackOk && fallback && deadlineAt - deps.now() >= IMAGE.minAttemptMs) {
    const reason = last.reason;
    last = await attemptOnce(deps, prompt, fallback, config, Math.min(IMAGE.abortMs, deadlineAt - deps.now()));
    attempts++;
    booked += last.microUsd;
    madeBy = fallback.model;
    deps.logger.info("image_fallback", {
      uidHash: uidHash(uid),
      provider: primary.provider,
      model: primary.model,
      fallbackModel: fallback.model,
      picture,
      reason,
      outcome: last.ok ? "done" : last.failure,
    });
  }
  const outcome: ImageOutcome = !last
    ? { ok: false, failure: "NO_TIME", microUsd: 0, model: null }
    : last.ok
      ? { ok: true, image: last.image, microUsd: booked, model: last.model }
      : { ok: false, failure: last.failure, microUsd: booked, model: madeBy };
  deps.logger.info("image_call", {
    uidHash: uidHash(uid),
    provider: primary.provider,
    model: outcome.model,
    picture,
    outcome: outcome.ok ? "done" : outcome.failure,
    attempts,
    fallback: madeBy !== null && madeBy === fallback?.model,
    durationMs: deps.now() - started,
    inputBytes: last?.inputBytes ?? null,
    passedThrough: outcome.ok ? outcome.image.passedThrough : null,
    bytes: outcome.ok ? outcome.image.data.length : null,
    width: outcome.ok ? outcome.image.width : null,
    height: outcome.ok ? outcome.image.height : null,
    estimatedMicroUsd: outcome.microUsd,
  });
  return outcome;
}

/**
 * The step pictures of a recipe whose dish picture was made, at most IMAGE.maxParallel at once,
 * in step order, all by `deadlineAt`. Outcomes in the order of `briefs`.
 */
export async function makeStepImages(
  deps: Pick<Deps, "imageGenerator" | "logger" | "now" | "sleep" | "random">,
  uid: string,
  briefs: readonly { step: number; brief: string }[],
  config: RuntimeConfig,
  deadlineAt: number,
): Promise<ImageOutcome[]> {
  const out: ImageOutcome[] = new Array(briefs.length);
  let next = 0;
  const worker = async () => {
    while (next < briefs.length) {
      const i = next++;
      out[i] = await makeRecipeImage(deps, uid, briefs[i].brief, config, deadlineAt, briefs[i].step);
    }
  };
  await Promise.all(Array.from({ length: Math.min(IMAGE.maxParallel, briefs.length) }, worker));
  return out;
}
