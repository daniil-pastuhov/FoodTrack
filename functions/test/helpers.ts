/**
 * Test wiring: the Admin SDK pointed at the Firestore and Auth emulators (env vars set by
 * `firebase emulators:exec`), plus fakes for the stores, the LLM and Apple token revocation.
 */
import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { randomUUID } from "node:crypto";
import type { GenerateRequest } from "../src/contract";
import type { Prompt } from "../src/adapters/prompt";
import { menuDates, coursesFor } from "../src/contract";
import { ANDROID_FIREBASE_APP_ID, IOS_FIREBASE_APP_ID } from "../src/constants";
import { loadRuntimeConfig } from "../src/model";
import { verifyPurchase as verifyCore } from "../src/core/purchases";
import { EnvelopeSealer, LocalKeyWrapper } from "../src/adapters/envelope";
import {
  AppleTokenError,
  ConsumptionRejectedError,
  GenerationError,
  InvalidSignatureError,
  RetryableStoreError,
  type ConsumptionInfo,
  type AppleEnvironment,
  type AppleNotification,
  type AppleTransaction,
  type AppStoreVerifier,
  type AppleTokenRevoker,
  type Caller,
  type Deps,
  type GenerationLimits,
  type GenerationResult,
  type JudgeResult,
  type JudgeVerdict,
  type LlmUsage,
  type OutputJudge,
  type PlayPurchase,
  type PlayVerifier,
  type RecipeGenerator,
  type VoidedPurchase,
} from "../src/ports";

export const PROJECT_ID = "demo-foodtracker";

if (!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_AUTH_EMULATOR_HOST) {
  throw new Error("Run the tests with `npm test`, which starts the Firestore and Auth emulators.");
}

if (getApps().length === 0) initializeApp({ projectId: PROJECT_ID });
export const db = getFirestore();
export const auth = getAuth();

export async function resetEmulators(): Promise<void> {
  const fs = process.env.FIRESTORE_EMULATOR_HOST;
  const au = process.env.FIREBASE_AUTH_EMULATOR_HOST;
  const r1 = await fetch(`http://${fs}/emulator/v1/projects/${PROJECT_ID}/databases/(default)/documents`, { method: "DELETE" });
  const r2 = await fetch(`http://${au}/emulator/v1/projects/${PROJECT_ID}/accounts`, { method: "DELETE" });
  if (!r1.ok || !r2.ok) throw new Error(`emulator reset failed: ${r1.status} ${r2.status}`);
}

// --- Auth emulator users ----------------------------------------------------------------------

async function signUp(body: Record<string, unknown>): Promise<{ uid: string; idToken: string }> {
  const res = await fetch(
    `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signUp?key=fake-api-key`,
    { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ ...body, returnSecureToken: true }) },
  );
  const json = (await res.json()) as { localId: string; idToken: string };
  if (!res.ok) throw new Error(`signUp failed: ${JSON.stringify(json)}`);
  return { uid: json.localId, idToken: json.idToken };
}

export const anonymousUser = () => signUp({});
export const passwordUser = () => signUp({ email: `${randomUUID()}@example.com`, password: "secret-password" });

export function callerFor(uid: string, provider = "password", authTime = Math.floor(Date.now() / 1000), appId: string | null = null, profileClaims = false): Caller {
  // The ID token's identities: the sign-in provider (none for anonymous), issued at sign-in.
  const providers = provider === "anonymous" || provider === "" ? [] : [provider];
  return { uid, signInProvider: provider, authTime, appId, profileClaims, providers, issuedAt: authTime };
}

/** verifyPurchase as called from the app that matches the store (Android for Play, iOS for the App Store). */
export function verifyPurchase(deps: Deps, caller: Caller, data: unknown) {
  const store = (data as { store?: unknown } | null)?.store;
  const appId = store === "appstore" ? IOS_FIREBASE_APP_ID : ANDROID_FIREBASE_APP_ID;
  return verifyCore(deps, { ...caller, appId: caller.appId ?? appId }, data);
}

// --- Clock ------------------------------------------------------------------------------------

export class Clock {
  constructor(public ms = Date.now()) {}
  now = () => this.ms;
  advance(ms: number) {
    this.ms += ms;
  }
}

// --- Fakes ------------------------------------------------------------------------------------

export class FakePlay implements PlayVerifier {
  purchases = new Map<string, PlayPurchase>();
  consumed: string[] = [];
  failConsume = false;
  failGet = false;
  voided: VoidedPurchase[] = [];
  getCalls = 0;

  add(token: string, p: Partial<PlayPurchase> = {}): string {
    this.purchases.set(token, {
      productId: "recipes_10",
      quantity: 1,
      purchaseState: "PURCHASED",
      consumed: false,
      isTest: false,
      orderId: `GPA.${token.slice(0, 8)}`,
      refundableQuantity: p.quantity ?? 1,
      obfuscatedExternalAccountId: null,
      ...p,
    });
    return token;
  }

  async getPurchase(token: string): Promise<PlayPurchase | null> {
    this.getCalls++;
    if (this.failGet) throw new RetryableStoreError("play down");
    return this.purchases.get(token) ?? null;
  }

  async consume(_productId: string, token: string): Promise<void> {
    if (this.failConsume) throw new RetryableStoreError("consume down");
    this.consumed.push(token);
  }

  async listVoided(): Promise<VoidedPurchase[]> {
    return this.voided;
  }
}

export function appleTx(over: Partial<AppleTransaction> = {}): AppleTransaction {
  const id = over.transactionId ?? String(Math.floor(Math.random() * 1e12));
  return {
    transactionId: id,
    originalTransactionId: id,
    bundleId: "com.foodbest.ios",
    productId: "recipes_10",
    quantity: 1,
    type: "Consumable",
    environment: "production",
    revocationDate: null,
    revocationPercentage: null,
    signedDate: Date.now(),
    appAccountToken: null,
    appTransactionId: null,
    ...over,
  };
}

/** JWS strings are opaque handles here: "jws-<n>" maps to a transaction; anything unknown is forged. */
export class FakeAppStore implements AppStoreVerifier {
  signed = new Map<string, AppleTransaction>();
  /** What the App Store Server API returns now, by transaction id (defaults to the signed copy). */
  fresh = new Map<string, AppleTransaction | null>();
  notifications = new Map<string, AppleNotification>();
  failFetch = false;
  /** Consumption answers sent, in order. */
  consumptionSent: { transactionId: string; environment: AppleEnvironment; info: ConsumptionInfo }[] = [];
  /** Next consumption sends fail with these, in order ("retry" or "reject"). */
  consumptionFailures: ("retry" | "reject")[] = [];
  private n = 0;

  sign(tx: AppleTransaction): string {
    const jws = `h.jws${++this.n}.s`;
    this.signed.set(jws, tx);
    return jws;
  }

  signNotification(type: string, tx: AppleTransaction | null, signedDate = Date.now(), uuid: string = randomUUID()): string {
    const payload = `h.ntf${++this.n}.s`;
    this.notifications.set(payload, {
      notificationUUID: uuid,
      notificationType: type,
      subtype: null,
      signedDate,
      environment: tx?.environment ?? "production",
      transaction: tx,
    });
    return payload;
  }

  async verifyTransaction(jws: string): Promise<AppleTransaction> {
    const tx = this.signed.get(jws);
    if (!tx) throw new InvalidSignatureError("forged");
    return tx;
  }

  fetchCalls = 0;

  async fetchTransaction(id: string, env: AppleEnvironment): Promise<AppleTransaction | null> {
    this.fetchCalls++;
    if (this.failFetch) throw new RetryableStoreError("apple down");
    if (this.fresh.has(id)) return this.fresh.get(id)!;
    for (const tx of this.signed.values()) if (tx.transactionId === id && tx.environment === env) return tx;
    return null;
  }

  async verifyNotification(payload: string): Promise<AppleNotification> {
    const n = this.notifications.get(payload);
    if (!n) throw new InvalidSignatureError("forged notification");
    return n;
  }

  async sendConsumptionInformation(transactionId: string, environment: AppleEnvironment, info: ConsumptionInfo): Promise<void> {
    const failure = this.consumptionFailures.shift();
    if (failure === "retry") throw new RetryableStoreError("apple 503");
    if (failure === "reject") throw new ConsumptionRejectedError("apple 400");
    this.consumptionSent.push({ transactionId, environment, info });
  }
}

/** A recipe in the model's answer shape (pantry ids), as the generator returns it. */
export function sampleRecipe(title = "Pantry pasta") {
  return {
    title,
    summary: "A quick pasta.",
    minutes: 20,
    ingredients: [
      { amount: "200 g", name: "pasta", pantryId: "p1" },
      { amount: "", name: "salt", pantryId: "none" },
    ],
    steps: ["Boil the pasta.", "Toss with sauce."],
  };
}

export function sampleMenu(req: GenerateRequest) {
  return {
    days: menuDates(req.today).map((date, d) => ({
      date,
      uses: ["p1"],
      courses: coursesFor(req.courses!).map((course) => ({ course, recipe: sampleRecipe(`${course} ${d}`) })),
    })),
  };
}

type Behaviour = (req: GenerateRequest, signal: AbortSignal, prompt: Prompt) => Promise<unknown>;

export function fakeUsage(over: Partial<LlmUsage> = {}): LlmUsage {
  return { inputTokens: 1000, outputTokens: 1500, thinkingTokens: 500, model: "fake-model", finishReason: "STOP", ...over };
}

export class FakeGenerator implements RecipeGenerator {
  calls = 0;
  next: Behaviour[] = [];
  /** Usage reported for successful calls (and content failures from failWith). */
  usage: LlmUsage | undefined = fakeUsage();
  limits: GenerationLimits[] = [];
  prompts: Prompt[] = [];
  fallback: Behaviour = async (req) => (req.kind === "menu" ? sampleMenu(req) : sampleRecipe());

  /** Usage reported for successful calls; undefined means the model reported none. */
  async generate(req: GenerateRequest, prompt: Prompt, signal: AbortSignal, limits: GenerationLimits): Promise<GenerationResult> {
    this.calls++;
    this.limits.push(limits);
    this.prompts.push(prompt);
    const output = await (this.next.shift() ?? this.fallback)(req, signal, prompt);
    return { output, ...(this.usage ? { usage: this.usage } : {}) };
  }

  /** Content failures report usage (the tokens were spent); infra failures report none (unknown). */
  failWith(cls: "infra" | "content", times = 1, usage?: LlmUsage) {
    for (let i = 0; i < times; i++) {
      this.next.push(async () => {
        throw new GenerationError(cls, `${cls} failure`, usage ?? (cls === "content" ? this.usage : undefined));
      });
    }
  }

  returns(value: unknown) {
    this.next.push(async () => value);
  }

  /** The next call blocks until the returned release() is called. */
  blockNext(): { release: () => void; started: Promise<void> } {
    let release!: () => void;
    let started!: () => void;
    const startedP = new Promise<void>((r) => (started = r));
    const gate = new Promise<void>((r) => (release = r));
    this.next.push(async () => {
      started();
      await gate;
      return sampleRecipe("Slow pasta");
    });
    return { release, started: startedP };
  }
}

/** The output judge: answers `verdict` (or the queued ones) and counts its calls. */
export class FakeJudge implements OutputJudge {
  calls = 0;
  verdict: JudgeVerdict = "ok";
  next: (() => Promise<JudgeResult>)[] = [];
  usage: LlmUsage | undefined = fakeUsage({ inputTokens: 800, outputTokens: 5, thinkingTokens: 20 });

  async judge(): Promise<JudgeResult> {
    this.calls++;
    const n = this.next.shift();
    if (n) return n();
    return { verdict: this.verdict, ...(this.usage ? { usage: this.usage } : {}) };
  }
}

/**
 * Sign in with Apple's token endpoints. A code `code-N` exchanges for `refresh-for-code-N`; codes
 * in `rejectedCodes` fail as Apple's 400 invalid_grant, and `down` makes every call retryable.
 */
export class FakeRevoker implements AppleTokenRevoker {
  /** Codes revoked through revokeWithAuthorizationCode. */
  codes: string[] = [];
  /** Codes exchanged through exchangeAuthorizationCode. */
  exchanged: string[] = [];
  /** Refresh tokens revoked through revokeRefreshToken. */
  revokedTokens: string[] = [];
  rejectedCodes = new Set<string>();
  down = false;
  failRevoke = false;

  static tokenFor(code: string): string {
    return `refresh-for-${code}`;
  }

  private check(code?: string): void {
    if (this.down) throw new AppleTokenError("retryable", "Apple /auth/token unreachable (TimeoutError)");
    if (code !== undefined && this.rejectedCodes.has(code)) {
      throw new AppleTokenError("rejected", "Apple /auth/token returned 400 invalid_grant");
    }
  }

  async revokeWithAuthorizationCode(code: string): Promise<void> {
    this.check(code);
    if (this.failRevoke) throw new AppleTokenError("retryable", "Apple /auth/revoke returned 503 other");
    this.codes.push(code);
  }

  async exchangeAuthorizationCode(code: string): Promise<string> {
    this.check(code);
    this.exchanged.push(code);
    return FakeRevoker.tokenFor(code);
  }

  async revokeRefreshToken(token: string): Promise<void> {
    this.check();
    if (this.failRevoke) throw new AppleTokenError("retryable", "Apple /auth/revoke returned 503 other");
    this.revokedTokens.push(token);
  }
}

export interface TestDeps extends Deps {
  play: FakePlay;
  appStore: FakeAppStore;
  generator: FakeGenerator;
  judge: FakeJudge;
  appleRevoker: FakeRevoker;
  clock: Clock;
  logs: LogLine[];
}

export interface LogLine {
  level: "info" | "warn" | "error";
  message: string;
  data?: Record<string, unknown>;
}

export function makeDeps(): TestDeps {
  const clock = new Clock();
  const logs: LogLine[] = [];
  const record = (level: LogLine["level"]) => (message: string, data?: Record<string, unknown>) => {
    logs.push({ level, message, data });
  };
  return {
    db,
    auth,
    play: new FakePlay(),
    appStore: new FakeAppStore(),
    generator: new FakeGenerator(),
    judge: new FakeJudge(),
    appleRevoker: new FakeRevoker(),
    // The real envelope code, with an in-memory key-encryption key in place of Cloud KMS.
    sealer: new EnvelopeSealer(new LocalKeyWrapper()),
    clock,
    now: clock.now,
    logs,
    logger: { info: record("info"), warn: record("warn"), error: record("error") },
    // Uncached in tests: setConfig must take effect on the next call.
    config: () => loadRuntimeConfig(db),
  };
}

export async function setConfig(values: Record<string, unknown>): Promise<void> {
  await db.doc("config/runtime").set(values, { merge: true });
}

export async function balanceOf(uid: string): Promise<number | undefined> {
  return (await db.collection("users").doc(uid).get()).data()?.balance;
}

export const playToken = () => `tok-${randomUUID()}-${randomUUID()}`;
