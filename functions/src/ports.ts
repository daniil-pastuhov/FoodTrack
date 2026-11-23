/**
 * Interfaces to everything outside Firestore: the two stores, the LLM and Apple's token revocation.
 * Production implementations live in src/adapters; tests use fakes.
 */
import type { Auth } from "firebase-admin/auth";
import type { Firestore } from "firebase-admin/firestore";
import type { GenerateRequest, Menu, Recipe } from "./contract";
import type { Prompt } from "./adapters/prompt";
import type { RuntimeConfig } from "./model";
import type { ProviderFailureKind } from "./providers/types";

/** A store error that should make the client try again later (network, 5xx, quota, misconfiguration). */
export class RetryableStoreError extends Error {}

// --- Google Play ------------------------------------------------------------------------------

export interface PlayPurchase {
  productId: string;
  quantity: number;
  purchaseState: "PURCHASED" | "PENDING" | "CANCELLED" | "UNSPECIFIED";
  consumed: boolean;
  /** Purchased by a licence tester (testPurchaseContext present). */
  isTest: boolean;
  orderId: string | null;
  /** Units not yet voided; quantity minus refundableQuantity have been refunded. */
  refundableQuantity: number;
  /** BillingFlowParams.setObfuscatedAccountId as set by the client at purchase, or null. */
  obfuscatedExternalAccountId: string | null;
}

export interface VoidedPurchase {
  purchaseToken: string;
  orderId: string | null;
  voidedTimeMillis: number;
}

export interface PlayVerifier {
  /** null when Play says the token does not exist or is invalid (a permanent rejection). Throws RetryableStoreError. */
  getPurchase(purchaseToken: string): Promise<PlayPurchase | null>;
  /** Throws RetryableStoreError on a failure worth retrying. */
  consume(productId: string, purchaseToken: string): Promise<void>;
  /** Fully voided one-time purchases seen by Google since `startTimeMillis`. */
  listVoided(startTimeMillis: number): Promise<VoidedPurchase[]>;
}

// --- App Store --------------------------------------------------------------------------------

export type AppleEnvironment = "production" | "sandbox";

export interface AppleTransaction {
  transactionId: string;
  originalTransactionId: string;
  bundleId: string;
  productId: string;
  quantity: number;
  /** StoreKit product type, e.g. "Consumable". */
  type: string;
  environment: AppleEnvironment;
  revocationDate: number | null;
  /** Milliunits (100000 = 100 %) of the transaction refunded, when the store reports a partial refund. */
  revocationPercentage: number | null;
  signedDate: number;
  /** The appAccountToken the client set at purchase (a UUID), or null. */
  appAccountToken: string | null;
  /** Stable per Apple Account per app; null on transactions from before Apple added it. */
  appTransactionId: string | null;
}

export interface AppleNotification {
  notificationUUID: string;
  notificationType: string;
  subtype: string | null;
  signedDate: number;
  environment: AppleEnvironment;
  /** The transaction the notification is about, already signature-verified. */
  transaction: AppleTransaction | null;
}

/** Thrown when a signed payload is not genuine, is for another app, or for a refused environment (Xcode). */
export class InvalidSignatureError extends Error {}

/** The message of the InvalidSignatureError for an Xcode or LocalTesting transaction (logged as XCODE_ENVIRONMENT). */
export const REFUSED_ENVIRONMENT = "refused environment";

export interface AppStoreVerifier {
  /** Verifies the JWS chain to Apple's root and the bundle id/environment. Throws InvalidSignatureError. */
  verifyTransaction(jws: string): Promise<AppleTransaction>;
  /** Fetches the transaction fresh from the App Store Server API. null when Apple doesn't know it. */
  fetchTransaction(transactionId: string, environment: AppleEnvironment): Promise<AppleTransaction | null>;
  /** Verifies an App Store Server Notifications v2 `signedPayload`. Throws InvalidSignatureError. */
  verifyNotification(signedPayload: string): Promise<AppleNotification>;
  /**
   * App Store Server API "Send Consumption Information" (v2). Throws RetryableStoreError when
   * worth retrying (network, 429, 5xx, missing key) and ConsumptionRejectedError when Apple refused
   * the request for good (other 4xx).
   */
  sendConsumptionInformation(transactionId: string, environment: AppleEnvironment, info: ConsumptionInfo): Promise<void>;
}

/** The current (v2) ConsumptionRequest body. Apple: a deliveryStatus other than DELIVERED needs consumptionPercentage 0. */
export interface ConsumptionInfo {
  customerConsented: true;
  /** Milliunits, 0 to 100000. */
  consumptionPercentage: number;
  deliveryStatus: "DELIVERED" | "UNDELIVERED_OTHER";
  sampleContentProvided: boolean;
}

/** Apple refused a consumption answer for good (a 4xx other than 429). */
export class ConsumptionRejectedError extends Error {}

// --- Sign in with Apple -----------------------------------------------------------------------

/**
 * A Sign in with Apple REST call failed. `rejected`: Apple answered 4xx (an expired, used or
 * foreign code, an invalid client secret); `retryable`: network, timeout, 5xx or misconfiguration.
 * The message carries only the endpoint, the HTTP status and Apple's `error` code, never a code or
 * a token.
 */
export class AppleTokenError extends Error {
  constructor(
    readonly kind: "rejected" | "retryable",
    message: string,
  ) {
    super(message);
  }
}

export interface AppleTokenRevoker {
  /** Exchanges a fresh Sign in with Apple authorization code for a token and revokes it. Throws AppleTokenError. */
  revokeWithAuthorizationCode(authorizationCode: string): Promise<void>;
  /**
   * Exchanges an authorization code (single use, valid five minutes) at /auth/token and returns
   * the refresh token. Throws AppleTokenError.
   */
  exchangeAuthorizationCode(authorizationCode: string): Promise<string>;
  /** Revokes a refresh token at /auth/revoke (Apple answers 200 for an already invalid one too). Throws AppleTokenError. */
  revokeRefreshToken(refreshToken: string): Promise<void>;
}

// --- Application-level encryption -------------------------------------------------------------

/**
 * A secret sealed with envelope encryption: a fresh AES-256-GCM data key per value, itself
 * encrypted ("wrapped") by a key-encryption key the plaintext never reaches (Cloud KMS in
 * production). All fields are base64 except `kek`, the name of the key that wrapped the data key.
 */
export interface SealedSecret {
  v: 1;
  kek: string;
  wrappedKey: string;
  iv: string;
  ciphertext: string;
  tag: string;
}

export interface SecretSealer {
  /** `context` is bound to the value as additional authenticated data: opening needs the same context. */
  seal(plaintext: string, context: string): Promise<SealedSecret>;
  /** Throws when the value was tampered with, sealed for another context, or the key is unavailable. */
  open(sealed: SealedSecret, context: string): Promise<string>;
}

// --- LLM --------------------------------------------------------------------------------------

/**
 * Failure classes. `infra` (timeout, 5xx, quota, token-ceiling abort) is always refunded, up to a
 * per-user daily ceiling after which generation is refused; `content` (safety block, MAX_TOKENS,
 * unusable output) is refunded within an allowance tied to the credits the user has paid for;
 * `quality` (a clean answer the quality gate refuses: no pantry item used, a staples-only dish, a
 * repeated dish) is always refunded and counts towards the same daily ceiling as `infra`.
 */
export type FailureClass = "infra" | "content" | "quality";

/** Token counts of one LLM call, from the response's usageMetadata. */
export interface LlmUsage {
  inputTokens: number;
  /** Visible output (candidatesTokenCount). */
  outputTokens: number;
  thinkingTokens: number;
  model: string;
  finishReason: string | null;
}

export class GenerationError extends Error {
  /**
   * `usage`: what the call is known to have used. Absent means unknown (the call may have been
   * billed for anything up to the worst case), so the spend estimate keeps the reserved worst case.
   */
  /**
   * `safety`: set when the model's own safety system refused the prompt or the answer (a fixed
   * code such as `PROMPT_BLOCKED` or `FINISH_SAFETY`); such a failure counts against the account's
   * daily prompt-rejection limit.
   */
  /**
   * `kind`: the shared provider failure taxonomy (providers/types.ts), when the adapter knows it;
   * absent: derived from the class (`providerKind`). It decides only whether the fallback provider
   * may be asked; the money follows `failureClass` and `usage` as before.
   */
  constructor(
    readonly failureClass: FailureClass,
    message: string,
    readonly usage?: LlmUsage,
    readonly safety?: string,
    readonly kind?: ProviderFailureKind,
  ) {
    super(message);
  }

  get providerKind(): ProviderFailureKind {
    if (this.kind) return this.kind;
    if (this.safety) return "safety";
    return this.failureClass === "content" ? "content" : "infra";
  }
}

/** The selected text provider and model (RuntimeConfig.text), passed on every call. */
export interface TextTarget {
  provider: string;
  model: string;
}

export interface GenerationLimits {
  maxOutputTokens: number;
  /** Abort the call, as an infrastructure failure, once output + thinking is observed above this. */
  hardTokenCeiling: number;
}

export interface GenerationResult {
  /** The model's parsed JSON, validated by the caller against the expected shape. */
  output: unknown;
  /** Absent when the model reported no usage: the call is then booked at the reserved worst case. */
  usage?: LlmUsage;
}

export interface RecipeGenerator {
  /**
   * Sends `prompt` (built by the core from `request`; the request is passed for fakes). Throws
   * GenerationError. Must honour `signal` and `limits`. `target`: the provider and model to
   * ask (providers/router.ts TextRouter picks the adapter by it); absent: the generator's own.
   */
  generate(request: GenerateRequest, prompt: Prompt, signal: AbortSignal, limits: GenerationLimits, target?: TextTarget): Promise<GenerationResult>;
}

/** The home worker's answer: the parsed JSON, and the model it named (logs only). */
export interface HomeAnswer {
  output: unknown;
  model: string;
}

/** Where and how long to ask the home worker (config/runtime.homeWorker). */
export interface HomeWorkerTarget {
  /** Its https base address (owner-set; never logged). */
  url: string;
  waitMs: number;
}

/** The home server on the owner's computer (core/homeWorker.ts HttpHomeWorker). */
export interface HomeWorkerQueue {
  /** Its answer within `target.waitMs`, or null on any failure (unreachable, busy, an error status, not JSON): never throws. */
  ask(uid: string, req: GenerateRequest, prompt: Prompt, limits: GenerationLimits, signal: AbortSignal, target: HomeWorkerTarget): Promise<HomeAnswer | null>;
}

/** What the judge classifies: a validated answer, and the picture's brief when one was asked. */
export interface JudgedOutput {
  /** The recipe language asked for (config.language), a code from a fixed list. */
  language?: string;
  recipe?: Recipe;
  menu?: Menu;
  imageBrief?: string;
  /** Level "steps": the kept step briefs. */
  stepBriefs?: string[];
}

/** The optional second check on a validated answer (config/runtime `outputJudge`). */
export type JudgeVerdict = "ok" | "off_topic" | "unsafe_food" | "harmful";

/** The field the judge found the most serious problem in (`where`). */
export const JUDGE_WHERE = ["title", "summary", "ingredients", "steps", "tip", "imageBrief", "stepBriefs", "none"] as const;
export type JudgeWhere = (typeof JUDGE_WHERE)[number];

/**
 * The judge's issue codes, for improving the recipe writer's prompt (README item 5 defines each):
 * what the deterministic checks and the quality gate cannot see.
 */
export const JUDGE_ISSUES = [
  "vague_amount",
  "missing_step",
  "step_order",
  "unclear_step",
  "unsafe_temp_time",
  "non_food",
  "implausible",
  "title_mismatch",
  "wrong_language",
  "off_topic_text",
  "image_mismatch",
] as const;
export type JudgeIssue = (typeof JUDGE_ISSUES)[number];

/**
 * The judge's description of an answer, best effort: each field absent when the model left it out
 * or wrote something malformed. Never decides anything. `note` is model text about untrusted
 * content: never shown to app users, never fed into a prompt, never logged.
 */
export interface JudgeAnswer {
  where?: JudgeWhere;
  issues?: JudgeIssue[];
  /** 1 (can't be cooked as written) to 5. */
  quality?: number;
  note?: string;
}

export interface JudgeResult {
  verdict: JudgeVerdict;
  answer?: JudgeAnswer;
  /** Absent when unknown: the call is then booked at its reserved worst case. */
  usage?: LlmUsage;
}

/**
 * One judge answer as the owner collects it (core/judgeLog.ts): no uid, no user input. `note`
 * and `text` are model text about untrusted content (see JudgeAnswer).
 */
export interface JudgeEntry {
  kind: "recipe" | "menu";
  days?: number;
  courses?: number;
  image?: string;
  language: string;
  /** Who wrote the answer (the provider and model asked, and the version that answered). */
  textProvider: string;
  textModel: string;
  textModelVersion: string | null;
  judgeModel: string;
  judgeModelVersion: string | null;
  /** adapters/gemini.ts JUDGE_VERSION. */
  judgeVersion: string;
  /** The generator's system instruction and schema for this request, canary masked, hashed. */
  promptHash: string;
  /** The deployed revision (K_REVISION), or null outside Cloud Run. */
  revision: string | null;
  mode: "shadow" | "enforce";
  /** The quality-gate rule that had refused the answer before the judge saw it, if any. */
  gateRule: string | null;
  /** null when the judge failed (`failure` says why). */
  verdict: JudgeVerdict | null;
  failure: string | null;
  where: JudgeWhere | null;
  issues: JudgeIssue[];
  quality: number | null;
  note: string | null;
  latencyMs: number;
  usage: { inputTokens: number; outputTokens: number; thinkingTokens: number; finishReason: string | null } | null;
  /** judgeText's document, only while config/runtime `outputJudgeKeepText` is on. */
  text?: string;
}

/** Where judge answers are kept (core/judgeLog.ts FirestoreJudgeLog). Best effort: may reject, never awaited by a request. */
export interface JudgeLog {
  record(entry: JudgeEntry): Promise<void>;
}

export interface OutputJudge {
  /** The Vertex AI model id it calls: its calls are reserved and booked at that model's prices (model.ts judgePricesOf). */
  readonly model: string;
  /**
   * Classifies a validated recipe or menu. Sees only the answer (never the user's input), and
   * answers with an enum. Throws GenerationError ("content" when the judge's own safety filter
   * blocked the text, "infra" otherwise).
   */
  judge(output: JudgedOutput, signal: AbortSignal): Promise<JudgeResult>;
}

// --- The weekly prompt review (core/promptReview.ts) ---------------------------------------------

/** One call of the weekly prompt review: a fixed instruction, the data as one user turn, a JSON schema. */
export interface ReviewPrompt {
  systemInstruction: string;
  userText: string;
  schema: Record<string, unknown>;
}

export interface PromptReviewer {
  /** The Vertex AI model id it calls (PROMPT_REVIEW_MODEL): booked at the google text provider's prices. */
  readonly model: string;
  /**
   * The model's JSON text, unparsed (core/promptReview.ts reads and checks it). Throws
   * GenerationError: "content" when a safety filter refused the prompt or the answer, or the
   * answer was cut at its token limit; "infra" for anything else (`usage` absent when unknown).
   */
  review(prompt: ReviewPrompt, signal: AbortSignal, limits: { maxOutputTokens: number; tokenCeiling: number }): Promise<{ text: string; usage?: LlmUsage }>;
}

// --- Recipe pictures ---------------------------------------------------------------------------

/**
 * What a picture is asked from: one fixed English text the server built (core/recipeImage.ts
 * imagePromptFor) from its own template and the text model's checked `imageBrief`. Never the
 * person's own text, never the system instruction.
 */
export interface ImagePrompt {
  text: string;
  /** The picture's shape, width:height (IMAGE.aspectRatio); each adapter asks its provider for it or its nearest size. */
  aspectRatio: ImageAspect;
}

/** The shapes a picture may be asked in. */
export type ImageAspect = "4:3";

/** Token counts a provider reported for one picture (for booking its real cost). */
export interface ImageUsage {
  inputTokens: number;
  /** Text output and thinking, billed at the text output rate. */
  outputTextTokens: number;
  outputImageTokens: number;
}

/** The selected provider and model (RuntimeConfig.image), passed on every call. */
export interface ImageTarget {
  provider: string;
  model: string;
}

/** A provider's answer, before core/recipeImage.ts re-encodes it to the delivered JPEG. */
export interface GeneratedImage {
  bytes: Buffer;
  /** As the provider said (image/png, image/jpeg, image/webp ...); the bytes are checked anyway. */
  mimeType: string;
  /** The model that answered, when the provider says (logs only). */
  model: string;
  /** Token counts, when the provider reported them. */
  usage?: ImageUsage;
}

/**
 * A picture that was not made. `kind`: "safety" when the provider's own filters refused the
 * prompt or the picture, "config" when the provider cannot be called at all (unknown provider, no
 * API key: nothing was sent, nothing billed), "out_of_credit" when its prepaid balance or spending
 * limit is exhausted (nothing made, nothing billed), "infra" otherwise (network, 4xx/5xx, timeout,
 * an unusable answer). `billed`: whether the call may have been charged; when true the picture's
 * reserved price is booked. The message is a fixed text (status codes, never provider text, which
 * could quote the prompt). `retryable`: a quota or overload answer (429, 5xx) worth asking again
 * after a pause (core/recipeImage.ts); `status`: the HTTP status, when there was one (logs only);
 * `retryAfterMs`: how long the provider asked to wait (Retry-After), when it said.
 */
export class ImageGenerationError extends Error {
  constructor(
    readonly kind: "safety" | "config" | "infra" | "out_of_credit",
    message: string,
    readonly billed: boolean,
    readonly retryable = false,
    readonly status?: number,
    readonly retryAfterMs?: number,
  ) {
    super(message);
  }

  /** The shared taxonomy (providers/types.ts): a retryable infra failure is "capacity". */
  get providerKind(): ProviderFailureKind {
    if (this.kind === "infra" && this.retryable) return "capacity";
    return this.kind;
  }
}

/**
 * The provider-neutral port behind every recipe picture. Implementations: the adapters in
 * providers/registry.ts (Vertex AI and OpenAI today), chosen per call by `target.provider`
 * (providers/router.ts ImageRouter), and the emulator fake.
 * Must honour `signal`. Throws ImageGenerationError.
 */
export interface RecipeImageGenerator {
  generate(prompt: ImagePrompt, target: ImageTarget, signal: AbortSignal): Promise<GeneratedImage>;
}

// --- Wiring -----------------------------------------------------------------------------------

/**
 * One of the owner's report emails (core/reportEmail.ts): the subject and both bodies. The sender
 * and the recipient are not here: they are the mailer's own (the owner's address, a secret), so no
 * caller ever holds or logs the address.
 */
export interface ReportMail {
  subject: string;
  text: string;
  html: string;
}

/** Why a report email was not sent: fixed codes, never the server's reply (which could name the address). */
export type MailFailure = "NOT_CONFIGURED" | "AUTH_FAILED" | "CONNECTION" | "TIMEOUT" | "REJECTED" | "RENDER_FAILED" | "ERROR";

export class MailError extends Error {
  constructor(
    readonly reason: MailFailure,
    /** The message may have reached the server anyway (the connection broke after it was handed over): never sent again by itself. */
    readonly maybeSent: boolean,
  ) {
    super("mail not sent: " + reason);
    this.name = "MailError";
  }
}

/** Sends one message to the owner, once: no retry inside. Throws MailError. */
export interface Mailer {
  send(mail: ReportMail): Promise<void>;
}

export interface Logger {
  info(message: string, data?: Record<string, unknown>): void;
  warn(message: string, data?: Record<string, unknown>): void;
  error(message: string, data?: Record<string, unknown>): void;
}

export interface Deps {
  db: Firestore;
  auth: Auth;
  play: PlayVerifier;
  appStore: AppStoreVerifier;
  generator: RecipeGenerator;
  judge: OutputJudge;
  /** Where every judge answer is kept for the owner (core/judgeLog.ts); absent: not kept. */
  judgeLog?: JudgeLog;
  /** Recipe pictures, whatever the provider (RecipeImageGenerator). */
  imageGenerator: RecipeImageGenerator;
  appleRevoker: AppleTokenRevoker;
  /** Encrypts the stored Sign in with Apple refresh tokens (adapters/envelope.ts). */
  sealer: SecretSealer;
  now(): number;
  /** Waits (the pause before a picture's retry); absent: a real timer. Tests advance their clock. */
  sleep?(ms: number): Promise<void>;
  /** A number in [0, 1) (the retry jitter); absent: Math.random. */
  random?(): number;
  logger: Logger;
  /** `config/runtime`, parsed fail-closed (cached for CONFIG_CACHE_MS in production). */
  config(): Promise<RuntimeConfig>;
  /**
   * The home server (core/homeWorker.ts): asked first for the text of a listed tester's request;
   * absent: never asked (the admin command, the red-team runner).
   */
  homeWorker?: HomeWorkerQueue;
  /**
   * Today's welcome grants (`stats/{day}.welcomeGrants`) for the `welcomeRecipes` field, cached
   * per instance in production (core/welcome.ts WelcomeDayCounter); absent: read afresh.
   */
  welcomeDayGrants?(day: string): Promise<number>;
}

/** The parts of a verified Firebase ID token the core needs. */
export interface Caller {
  uid: string;
  signInProvider: string;
  /** Seconds since epoch of the user's last sign-in. */
  authTime: number;
  /** The Firebase app id from the verified App Check token, or null when there is none. */
  appId: string | null;
  /** The ID token still carries a `name` or `picture` claim (the Auth user has a display name or photo). */
  profileClaims: boolean;
  /**
   * Sign-in providers linked to the user when the ID token was issued (the keys of its
   * `firebase.identities` claim, e.g. `apple.com`, `google.com`). Up to an hour stale.
   */
  providers: string[];
  /** Seconds since epoch when the ID token was issued (`iat`). */
  issuedAt: number;
}
