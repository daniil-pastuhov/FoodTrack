/**
 * Test wiring: the Admin SDK pointed at the Firestore and Auth emulators (env vars set by
 * `firebase emulators:exec`), plus fakes for the stores, the LLM and Apple token revocation.
 */
import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { randomUUID } from "node:crypto";
import type { GenerateRequest, Recipe } from "../src/contract";
import { menuDates, coursesFor } from "../src/contract";
import {
  GenerationError,
  InvalidSignatureError,
  RetryableStoreError,
  type AppleEnvironment,
  type AppleNotification,
  type AppleTransaction,
  type AppStoreVerifier,
  type AppleTokenRevoker,
  type Caller,
  type Deps,
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

export function callerFor(uid: string, provider = "password", authTime = Math.floor(Date.now() / 1000)): Caller {
  return { uid, signInProvider: provider, authTime };
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
  private n = 0;

  sign(tx: AppleTransaction): string {
    const jws = `h.jws${++this.n}.s`;
    this.signed.set(jws, tx);
    return jws;
  }

  signNotification(type: string, tx: AppleTransaction | null, signedDate = Date.now()): string {
    const payload = `h.ntf${++this.n}.s`;
    this.notifications.set(payload, {
      notificationUUID: randomUUID(),
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

  async fetchTransaction(id: string, env: AppleEnvironment): Promise<AppleTransaction | null> {
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
}

export function sampleRecipe(title = "Pantry pasta"): Recipe {
  return {
    title,
    summary: "A quick pasta.",
    minutes: 20,
    servings: 2,
    difficulty: "easy",
    ingredients: [{ amount: "200 g", name: "pasta", fromPantry: true }],
    steps: ["Boil the pasta.", "Toss with sauce."],
  };
}

export function sampleMenu(req: GenerateRequest) {
  return {
    days: menuDates(req.today).map((date, d) => ({
      date,
      uses: ["pasta"],
      courses: coursesFor(req.courses!).map((course) => ({ course, recipe: sampleRecipe(`${course} ${d}`) })),
    })),
  };
}

type Behaviour = (req: GenerateRequest, signal: AbortSignal) => Promise<unknown>;

export class FakeGenerator implements RecipeGenerator {
  calls = 0;
  next: Behaviour[] = [];
  fallback: Behaviour = async (req) => (req.kind === "menu" ? sampleMenu(req) : sampleRecipe());

  async generate(req: GenerateRequest, signal: AbortSignal): Promise<unknown> {
    this.calls++;
    return (this.next.shift() ?? this.fallback)(req, signal);
  }

  failWith(cls: "infra" | "content", times = 1) {
    for (let i = 0; i < times; i++) this.next.push(async () => { throw new GenerationError(cls, `${cls} failure`); });
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
    this.next.push(async (req) => {
      started();
      await gate;
      return sampleRecipe(`slow ${req.requestId}`);
    });
    return { release, started: startedP };
  }
}

export class FakeRevoker implements AppleTokenRevoker {
  codes: string[] = [];
  async revokeWithAuthorizationCode(code: string): Promise<void> {
    this.codes.push(code);
  }
}

export interface TestDeps extends Deps {
  play: FakePlay;
  appStore: FakeAppStore;
  generator: FakeGenerator;
  appleRevoker: FakeRevoker;
  clock: Clock;
}

export function makeDeps(): TestDeps {
  const clock = new Clock();
  return {
    db,
    auth,
    play: new FakePlay(),
    appStore: new FakeAppStore(),
    generator: new FakeGenerator(),
    appleRevoker: new FakeRevoker(),
    clock,
    now: clock.now,
    logger: { info: () => undefined, warn: () => undefined, error: () => undefined },
  };
}

export async function setConfig(values: Record<string, unknown>): Promise<void> {
  await db.doc("config/runtime").set(values, { merge: true });
}

export async function balanceOf(uid: string): Promise<number | undefined> {
  return (await db.collection("users").doc(uid).get()).data()?.balance;
}

export const playToken = () => `tok-${randomUUID()}-${randomUUID()}`;
