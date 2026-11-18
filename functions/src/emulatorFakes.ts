/**
 * Stand-ins for the stores and the LLM, for client development against the local emulator suite
 * only. They are used when EMULATOR_FAKES=true AND the code runs inside the Functions emulator
 * (FUNCTIONS_EMULATOR=true, set by the emulator itself) AND the project id starts with `demo-`
 * (a demo project has no real resources), so a deployed function never uses them.
 *
 * - Play: any purchase token starting with "fake-" is a purchased recipes_10 (or the product named
 *   after it, e.g. "fake-recipes_50-1"); other tokens are unknown.
 * - App Store: the JWS payload is decoded WITHOUT verification, and Xcode/LocalTesting
 *   transactions (StoreKit configuration files in Xcode) are treated as sandbox.
 * - LLM: returns a canned recipe or menu, in the model's answer shape (pantry ids), which then goes
 *   through the real output checks. An ingredient named "fail infra" or "fail content" makes the
 *   call fail with that class, to exercise refunds. A request with a taste profile gets a tip that
 *   says so, so a client can see the profile reached the server. The judge always answers ok, and
 *   the weekly prompt review's model with a fixed summary and no problems.
 * - Recipe pictures: a recipe asked with `image: "dish"` gets a fixed brief (`"steps"` also one
 *   step brief), and the image fake
 *   answers with one small fixed JPEG (96 x 96), which goes through the real re-encoding; an
 *   ingredient named "fail image" makes the picture fail (the recipe is delivered without it).
 * - Sign in with Apple: any authorization code is exchanged for a random fake refresh token, and
 *   revoking succeeds; the token is sealed with an in-memory key (LocalKeyWrapper), not Cloud KMS.
 * - Report emails: kept in memory (RecordingMailer), never sent.
 */
import { randomBytes } from "node:crypto";
import { coursesFor, menuDates, menuDaysOf, type GenerateRequest } from "./contract";
import { JUDGE, PROMPT_REVIEW } from "./constants";
import {
  type AppleTokenRevoker,
  GenerationError,
  InvalidSignatureError,
  type ConsumptionInfo,
  type AppleNotification,
  type AppleTransaction,
  type AppStoreVerifier,
  type GeneratedImage,
  type GenerationResult,
  ImageGenerationError,
  type ImagePrompt,
  type ImageTarget,
  type JudgeResult,
  type LlmUsage,
  type MailError,
  type Mailer,
  type OutputJudge,
  type PlayPurchase,
  type PromptReviewer,
  type PlayVerifier,
  type RecipeGenerator,
  type RecipeImageGenerator,
  type ReportMail,
  type VoidedPurchase,
} from "./ports";

export function emulatorFakesEnabled(env: NodeJS.ProcessEnv = process.env): boolean {
  const project = env.GCLOUD_PROJECT ?? env.GOOGLE_CLOUD_PROJECT ?? "";
  return env.FUNCTIONS_EMULATOR === "true" && env.EMULATOR_FAKES === "true" && project.startsWith("demo-");
}

export class EmulatorPlay implements PlayVerifier {
  async getPurchase(token: string): Promise<PlayPurchase | null> {
    if (!token.startsWith("fake-")) return null;
    const productId = /^fake-(recipes_(?:10|50|100))/.exec(token)?.[1] ?? "recipes_10";
    return {
      productId, quantity: 1, purchaseState: "PURCHASED", consumed: false, isTest: false, orderId: null,
      refundableQuantity: 1, obfuscatedExternalAccountId: null,
    };
  }
  async consume(): Promise<void> {}
  async listVoided(): Promise<VoidedPurchase[]> {
    return [];
  }
}

function decode(jws: string): Record<string, unknown> {
  try {
    return JSON.parse(Buffer.from(jws.split(".")[1] ?? "", "base64url").toString("utf8")) as Record<string, unknown>;
  } catch {
    throw new InvalidSignatureError("malformed JWS");
  }
}

function tx(p: Record<string, unknown>): AppleTransaction {
  const id = String(p.transactionId ?? "");
  if (!id) throw new InvalidSignatureError("no transactionId");
  return {
    transactionId: id,
    originalTransactionId: String(p.originalTransactionId ?? id),
    bundleId: String(p.bundleId ?? ""),
    productId: String(p.productId ?? ""),
    quantity: Number(p.quantity ?? 1),
    type: String(p.type ?? ""),
    environment: p.environment === "Production" ? "production" : "sandbox",
    revocationDate: typeof p.revocationDate === "number" ? p.revocationDate : null,
    revocationPercentage: typeof p.revocationPercentage === "number" ? p.revocationPercentage : null,
    signedDate: Number(p.signedDate ?? Date.now()),
    appAccountToken: typeof p.appAccountToken === "string" ? p.appAccountToken : null,
    appTransactionId: typeof p.appTransactionId === "string" ? p.appTransactionId : null,
  };
}

export class EmulatorAppStore implements AppStoreVerifier {
  private readonly seen = new Map<string, AppleTransaction>();
  async verifyTransaction(jws: string): Promise<AppleTransaction> {
    const t = tx(decode(jws));
    this.seen.set(t.transactionId, t);
    return t;
  }
  async fetchTransaction(id: string): Promise<AppleTransaction | null> {
    return this.seen.get(id) ?? null;
  }
  async sendConsumptionInformation(_id: string, _env: string, _info: ConsumptionInfo): Promise<void> {}
  async verifyNotification(signedPayload: string): Promise<AppleNotification> {
    const p = decode(signedPayload);
    const data = (p.data ?? {}) as Record<string, unknown>;
    const signedTx = typeof data.signedTransactionInfo === "string" ? data.signedTransactionInfo : null;
    return {
      notificationUUID: String(p.notificationUUID ?? ""),
      notificationType: String(p.notificationType ?? ""),
      subtype: p.subtype ? String(p.subtype) : null,
      signedDate: Number(p.signedDate ?? Date.now()),
      environment: data.environment === "Production" ? "production" : "sandbox",
      transaction: signedTx ? tx(decode(signedTx)) : null,
    };
  }
}

function cannedRecipe(req: GenerateRequest, title: string) {
  return {
    title,
    summary: `A test recipe from the emulator using ${req.ingredients.map((i) => i.name).join(", ")}.`,
    minutes: req.config.maxMinutes ?? 30,
    ingredients: req.ingredients.map((i, n) => ({ amount: "1", name: i.name, pantryId: `p${n + 1}` })),
    steps: ["Prepare the ingredients.", "Cook them.", "Serve."],
    tip: req.taste
      ? "Generated by the emulator fake with your taste profile, not a real model."
      : "Generated by the emulator fake, not a real model.",
  };
}

export class EmulatorGenerator implements RecipeGenerator {
  async generate(req: GenerateRequest): Promise<GenerationResult> {
    const usage = {
      inputTokens: 600,
      outputTokens: req.kind === "menu" ? 9000 : 1200,
      thinkingTokens: 300,
      model: "emulator-fake",
      finishReason: "STOP",
    };
    const names = req.ingredients.map((i) => i.name.toLowerCase());
    if (names.includes("fail infra")) throw new GenerationError("infra", "emulator fake infra failure");
    if (names.includes("fail content")) throw new GenerationError("content", "emulator fake content failure");
    await new Promise((r) => setTimeout(r, 1500));
    if (req.kind === "recipe") {
      const brief = names.includes("fail image")
        ? "A bowl of vegetable soup the emulator fails on."
        : "A white plate of golden pasta with a creamy sauce and fresh green herbs on top.";
      const stepBriefs = req.image === "steps"
        ? [{ step: 2, brief: "Golden pasta tossed in a pan until the sauce turns glossy and coats every strand." }].slice(0, req.stepImagesMax ?? 0)
        : undefined;
      return { output: { ...cannedRecipe(req, "Emulator pantry dish"), ...(req.image ? { imageBrief: brief } : {}), ...(stepBriefs ? { stepBriefs } : {}) }, usage };
    }
    const output = {
      days: menuDates(req.today, menuDaysOf(req)).map((date, d) => ({
        date,
        uses: req.ingredients.slice(0, 2).map((_, n) => `p${n + 1}`),
        courses: coursesFor(req.courses!).map((course) => ({ course, recipe: cannedRecipe(req, `Day ${d + 1} ${course}`) })),
      })),
    };
    return { output, usage };
  }
}

/** A 96 x 96 JPEG (a plate on a table), the emulator's every picture. */
export const EMULATOR_IMAGE_JPEG = Buffer.from(
  "/9j/2wBDAAoHBwgHBgoICAgLCgoLDhgQDg0NDh0VFhEYIx8lJCIfIiEmKzcvJik0KSEiMEExNDk7Pj4+JS5ESUM8SDc9Pjv/2wBDAQoLCw4NDhwQEBw7KCIoOzs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozv/wAARCABgAGADASIAAhEBAxEB/8QAGwAAAgIDAQAAAAAAAAAAAAAAAAYBAgQFBwP/xABCEAABAwMABgQHDQkBAAAAAAABAAIDBAURBhIhMUFRE2GB0RQWIjI1ccEVIzZCRFOCkqOxsuHwM1JUVWRzdJGTcv/EABkBAAIDAQAAAAAAAAAAAAAAAAIEAAEFA//EACIRAAMAAQUAAgMBAAAAAAAAAAABAgMEERIhMRMiMkGxof/aAAwDAQACEQMRAD8A8EIQs00gQpYx8j2xxtL3uIDWtGSTyCcbVoL5st0l5HoIj6jhzv8AYIHYUUy68Aq1PonMY+R7Y42l73EBrWjJJ5BZfuNdP5bV/wDB3cujsNoszDHEIKc7GuDG+UcDZrY2n1nmvHxlovmp/qjvUp4oe1V2c/lp+I597jXT+W1f/B3csaaGWnlMU8T4pG72PaWkdhXSvGWi+an+qO9e4q7VdGNjl6GXaCI52jzjyB3n1KTWGntN9k+Sl6jlaE8XTQWCXMlsl6B3zUhJZw3HeOJ49iS6mmmo6h9PURmOWM4c08FdQ59Ok2q8PNCEIAwUsY+R7Y42l73EBrWjJJ5BQnLQW1ftLpKznHDkfWcNnZkH94Ipnk9gLritzc2Gw09ipS97mvqXNzLMdwHIch9/+gMK5X6WZzoqRxjiB88ZDndw/XUi/XIzTGkicRFGcP2Y1nD2D9cFpiUlq9U9/jx9JHPHj5faicqMqMqMrN2G1BbKnKplTlTYjg2tuvU9GRHITLDkZBOS0dXd9y3FztlHpBbg1zhu1oZmjaw93Me0bFMFbK0XJ1DUBsjj4O8+WMZweYWhpdW4fDJ2v4K5Me32n0TqykloayWlnGJInFp2HB6xngd4Xin7TW1eF29tdEzMtN52Btcw7+HA7eQGskFaFzxewcVyW5LGPke2ONpe9xAa1oySeQXUixtmsIijI94iDA5rQMu3a2OsnJXOLN6boP8AJj/EF0HSX0dH/dH3FU64YqpenPL3SQsFVKkqpWAhuEBKjKCqkqxqZ3LZRlVypChKkuFIVQrBUxW0NtmlbWWhscrQ8NzE8OaMOHLHEYIC5rX0j6CvnpH5JheW5LdXWHA46xt7U+6L/Kvoe1Kel3wnq/ofgat3FXPBNMUjrI0YVm9N0H+TH+ILoOkvo6P+6PuK5rDNJTzxzxO1ZI3B7TjOCDkLqNWGXSydLG0++xCWMFuXbsgY58O1FUusNyvSZOqTFAqpVyqlYCG4ZUqpViowrGpohSEYUqy6okKwUBSELFbYwaL/ACr6HtSnpd8J6v6H4Gp4sFN0FuEhGHTHW2twcbh6+fauc3WtNxulRVkkiV5LdYAEN3NBx1ALdxS508picd5GzETxoLdOlpZLZIfKhzJH/wCSdo3cCeJ+N1JHXpTVM1HUMqKeQxyxnLXDgjiuL3OlzyWw63q3GjqTJG09DKcg42NP7v69i1ZCbLZc6PSC3FzWjdqzQuO1h7uR9o2aW5Wiahc6RoL6fOx/EZ4HvWfq9K4fyR4/8Ax5NvrXprMKMK2EYWfuNKyuFOFOEYU3LdgAs22UDq+qEflCMbZHDgPzRQWyevf72NWMHDpDuHemOaah0fthkkOpEztdI7l1k/rACd0uleR8q/H+iuTJ+l6a7S+5Nt1m8Fiw2SqBjaANjWDzuGNxA7epc7WXc7jNdK+SqmcfKPkNJzqN4NHq/NYi07rky8c8UCEIQHQ9qSsqaGcT0sz4ZBxacZGc4PMbNxTxatNaOr1Yq5vgspwNffG47OPxdud+wAb0goRzbnwCoVenUpbNbqxgljaGB+HB8LtjhjZjhj1LC8V/6z7L80hUlfV0D9ekqZISSCdR2A7G7I3HtWx8br7/AB32TO5BWLBfdSc+GReMbPFf+s+y/NZlNYKKDBkDpnDBy87MjqHtykfxuvv8d9kzuWDW3WvuJJq6qSUEg6pOGggYyGjYFJxaeXupJwyP1jzctL7Zbm9FS4q5GgANiIDGjZ8bduPDO7GxI1xudXdKgzVUpftJazPks6mjhuCxEI6t0HONSCEIQHQ//9k=",
  "base64",
);

export class EmulatorImageGenerator implements RecipeImageGenerator {
  async generate(prompt: ImagePrompt, target: ImageTarget): Promise<GeneratedImage> {
    await new Promise((r) => setTimeout(r, 1000));
    if (prompt.text.includes("emulator fails on")) throw new ImageGenerationError("infra", "emulator fake image failure", false);
    return { bytes: EMULATOR_IMAGE_JPEG, mimeType: "image/jpeg", model: `emulator-fake-${target.model}` };
  }
}

export class EmulatorJudge implements OutputJudge {
  readonly model = JUDGE.model;
  async judge(): Promise<JudgeResult> {
    return {
      verdict: "ok",
      answer: { where: "none", issues: [], quality: 5, note: "Emulator: no judge ran." },
      usage: { inputTokens: 500, outputTokens: 40, thinkingTokens: 0, model: "emulator-fake", finishReason: "STOP" },
    };
  }
}

/**
 * The weekly prompt review's model under the emulators (and the admin command against them): one
 * fixed answer, a summary and no problems, so a run is stored as done without any call.
 */
export class EmulatorPromptReviewer implements PromptReviewer {
  readonly model = PROMPT_REVIEW.model;
  async review(): Promise<{ text: string; usage: LlmUsage }> {
    return {
      text: JSON.stringify({ summary: "Emulator: no model ran; the counts above are real.", promptProblems: [], judgeProblems: [] }),
      usage: { inputTokens: 12_000, outputTokens: 60, thinkingTokens: 0, model: "emulator-fake", finishReason: "STOP" },
    };
  }
}

/**
 * The report emails under the emulators, in the admin command against them, and in the tests: the
 * messages are kept in memory and nothing is sent. `fail` makes every send throw that error (after
 * recording nothing); `gate` holds a send until it resolves (for races).
 */
export class RecordingMailer implements Mailer {
  readonly sent: ReportMail[] = [];
  fail: MailError | null = null;
  gate: Promise<void> | null = null;
  calls = 0;

  async send(mail: ReportMail): Promise<void> {
    this.calls++;
    if (this.gate) await this.gate;
    if (this.fail) throw this.fail;
    this.sent.push(mail);
  }
}

export class EmulatorAppleTokens implements AppleTokenRevoker {
  async exchangeAuthorizationCode(): Promise<string> {
    return `emulator-refresh-${randomBytes(12).toString("hex")}`;
  }

  async revokeRefreshToken(): Promise<void> {}

  async revokeWithAuthorizationCode(): Promise<void> {}
}
