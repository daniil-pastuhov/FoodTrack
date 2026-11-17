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
import { IMAGE } from "../constants";
import { ImageNormalizeError, normalizeImage, type NormalizedImage } from "../adapters/imageNormalize";
import { imageMicroUsdOf, uidHash, type RuntimeConfig } from "../model";
import { ImageGenerationError, type Deps, type ImagePrompt } from "../ports";

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
  return { text: `${IMAGE_PROMPT_HEAD} ${sentence} ${IMAGE_PROMPT_TAIL}` };
}

export type ImageOutcome =
  | { ok: true; image: NormalizedImage; microUsd: number; model: string }
  /**
   * `failure`: a fixed code (BRIEF_<rule>, SAFETY, CONFIG, TIMEOUT, INFRA, DECODE). `microUsd`:
   * what is booked for it, the picture's price when the provider may have charged, else 0.
   */
  | { ok: false; failure: string; microUsd: number; model: string | null };

/** The outcome of a picture never asked for, because its brief was refused (nothing sent). */
export function briefRefused(rule: string): ImageOutcome {
  return { ok: false, failure: `BRIEF_${rule}`, microUsd: 0, model: null };
}

/**
 * Asks the selected provider for the picture, under IMAGE.abortMs, and re-encodes the answer.
 * Never throws: every failure is an ImageOutcome. Logs one `image_call` line (never the prompt).
 */
export async function makeRecipeImage(
  deps: Pick<Deps, "imageGenerator" | "logger" | "now">,
  uid: string,
  brief: string,
  config: RuntimeConfig,
  /** A step's picture: its 0-based index (logs only); absent for the dish's. */
  step?: number,
): Promise<ImageOutcome> {
  const price = imageMicroUsdOf(config) ?? 0;
  const target = { provider: config.image.provider, model: config.image.model };
  const started = deps.now();
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), IMAGE.abortMs);
  let outcome: ImageOutcome;
  let inputBytes: number | null = null;
  try {
    const raw = await deps.imageGenerator.generate(imagePromptFor(brief), target, controller.signal);
    inputBytes = raw.bytes.length;
    if (controller.signal.aborted) throw new ImageGenerationError("infra", "timeout", true);
    const image = await normalizeImage(raw.bytes);
    outcome = { ok: true, image, microUsd: price, model: raw.model.slice(0, 80) };
  } catch (e) {
    if (e instanceof ImageGenerationError) {
      const failure = controller.signal.aborted ? "TIMEOUT" : e.kind === "safety" ? "SAFETY" : e.kind === "config" ? "CONFIG" : "INFRA";
      outcome = { ok: false, failure, microUsd: e.billed || controller.signal.aborted ? price : 0, model: target.model };
    } else if (e instanceof ImageNormalizeError) {
      // The provider answered (and charged): what it sent could not be used.
      outcome = { ok: false, failure: "DECODE", microUsd: price, model: target.model };
    } else {
      deps.logger.error("Image generator threw unexpectedly", { error: String(e).slice(0, 200) });
      outcome = { ok: false, failure: "INFRA", microUsd: price, model: target.model };
    }
  } finally {
    clearTimeout(timer);
  }
  deps.logger.info("image_call", {
    uidHash: uidHash(uid),
    provider: target.provider,
    model: outcome.model,
    picture: step === undefined ? "dish" : "step",
    outcome: outcome.ok ? "done" : outcome.failure,
    durationMs: deps.now() - started,
    inputBytes,
    bytes: outcome.ok ? outcome.image.data.length : null,
    width: outcome.ok ? outcome.image.width : null,
    height: outcome.ok ? outcome.image.height : null,
    estimatedMicroUsd: outcome.microUsd,
  });
  return outcome;
}
