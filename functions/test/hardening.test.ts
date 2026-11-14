/**
 * Fixes from the security and cost-abuse review: one block per finding. Each test failed on the
 * code before the fix.
 */
import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { HttpsError } from "firebase-functions/v2/https";
import type { GenerateContentParameters, GenerateContentResponse } from "@google/genai";
import { AppleAppStoreVerifier } from "../src/adapters/appstore";
import { buildPrompt } from "../src/adapters/prompt";
import { GeminiRecipeGenerator } from "../src/adapters/gemini";
import { IOS_FIREBASE_APP_ID, ANDROID_FIREBASE_APP_ID, CONSUME_MAX_ATTEMPTS } from "../src/constants";
import { validateGenerateRequest } from "../src/contract";
import { getBalance, mergeBalance } from "../src/core/account";
import { generateRecipe, getGeneration, planFor } from "../src/core/generation";
import { CallThrottle, LogSampler, ipKey } from "../src/core/guard";
import { APPLE_SIGNED_PAYLOAD_MAX, handleAppleNotification } from "../src/core/notifications";
import { applePurchaseId, playPurchaseId } from "../src/core/purchases";
import { sweep, watchNegativeBalances } from "../src/core/sweeper";
import { emulatorFakesEnabled } from "../src/emulatorFakes";
import {
  DEFAULT_RUNTIME_CONFIG,
  RuntimeConfigCache,
  dailySpendCeiling,
  emptyUser,
  parseRuntimeConfig,
  purchaseAccountToken,
  statsRef,
  utcDay,
  uuidV5,
  type RuntimeConfig,
} from "../src/model";
import { InvalidSignatureError } from "../src/ports";
import {
  anonymousUser,
  appleTx,
  balanceOf,
  callerFor,
  db,
  fakeUsage,
  makeDeps,
  passwordUser,
  playToken,
  resetEmulators,
  setConfig,
  verifyPurchase,
  type TestDeps,
} from "./helpers";

let deps: TestDeps;
beforeEach(async () => {
  await resetEmulators();
  deps = makeDeps();
});

async function reason(p: Promise<unknown>): Promise<string> {
  try {
    await p;
  } catch (e) {
    if (e instanceof HttpsError) return (e.details as { reason: string }).reason;
    throw e;
  }
  return "no error";
}

const today = () => new Date(deps.now()).toISOString().slice(0, 10);
function request(over: Record<string, unknown> = {}) {
  return {
    requestId: randomUUID(),
    kind: "recipe",
    config: { difficulty: "easy", detail: "standard", servings: 2, maxMinutes: 30, mealType: "dinner", diet: "none", language: "en" },
    ingredients: [{ name: "Pasta", expiresOn: "2030-01-01", quantity: 1 }],
    allergens: [],
    today: today(),
    ...over,
  };
}
const menu3 = () => request({ kind: "menu", courses: 3 });
const worstCase = (req: Record<string, unknown>) =>
  planFor(validateGenerateRequest(req, Date.now()), parseRuntimeConfig({})).reservedMicroUsd;

async function seed(uid: string, balance: number, extra: Record<string, unknown> = {}) {
  await db.collection("users").doc(uid).set({ ...emptyUser(), balance, purchasedCredits: balance, ...extra });
}
const user = async (uid: string) => (await db.collection("users").doc(uid).get()).data()!;
const purchase = async (id: string) => (await db.collection("purchases").doc(id).get()).data();

/** Records which collections a call touches and which documents its transactions read. */
function spy(d: TestDeps) {
  const collections: string[] = [];
  const txReads: string[] = [];
  const pathOf = (r: unknown) => (r as { path?: string }).path ?? "query";
  const wrapTx = (tx: FirebaseFirestore.Transaction) =>
    new Proxy(tx, {
      get(t, p) {
        if (p === "get") return (r: unknown) => (txReads.push(pathOf(r)), t.get(r as FirebaseFirestore.DocumentReference));
        if (p === "getAll") {
          return (...rs: FirebaseFirestore.DocumentReference[]) => (rs.forEach((r) => txReads.push(pathOf(r))), t.getAll(...rs));
        }
        const v = (t as unknown as Record<string | symbol, unknown>)[p];
        return typeof v === "function" ? v.bind(t) : v;
      },
    });
  const proxied = new Proxy(d.db, {
    get(t, p) {
      if (p === "collection") return (n: string) => (collections.push(n), t.collection(n));
      if (p === "runTransaction") {
        return (fn: (tx: FirebaseFirestore.Transaction) => Promise<unknown>, o?: object) =>
          t.runTransaction((tx) => fn(wrapTx(tx)), o as never);
      }
      const v = (t as unknown as Record<string | symbol, unknown>)[p];
      return typeof v === "function" ? v.bind(t) : v;
    },
  });
  return { deps: { ...d, db: proxied } as TestDeps, collections, txReads };
}

// --- 1. Log flood ------------------------------------------------------------------------------

describe("1. no attacker content or unsampled volume in logs", () => {
  test("a refused environment never echoes the payload, and a forged body logs nothing per request", async () => {
    const verifier = new AppleAppStoreVerifier({
      rootCertificates: [readFileSync(join(__dirname, "..", "certs", "AppleRootCA-G3.cer"))], appAppleId: 1, enableOnlineChecks: false,
    });
    const big = "A".repeat(10_000);
    const payload = Buffer.from(JSON.stringify({ data: { environment: big } })).toString("base64url");
    const body = { signedPayload: `e30.${payload}.sig` };
    await expect(verifier.verifyNotification(body.signedPayload)).rejects.toThrow(new InvalidSignatureError("refused environment"));
    const d = { ...deps, appStore: verifier };
    for (let i = 0; i < 5; i++) expect(await handleAppleNotification(d, body)).toEqual({ httpStatus: 400, outcome: "INVALID_SIGNATURE" });
    expect(deps.logs).toEqual([]);
  });

  test("an oversized signedPayload is refused before verification", async () => {
    const r = await handleAppleNotification(deps, { signedPayload: "x".repeat(APPLE_SIGNED_PAYLOAD_MAX + 1) });
    expect(r).toEqual({ httpStatus: 400, outcome: "PAYLOAD_TOO_LARGE" });
    expect(APPLE_SIGNED_PAYLOAD_MAX).toBeLessThanOrEqual(24 * 1024);
  });

  test("the sampler lets a few lines through per window and counts the rest", () => {
    const s = new LogSampler(60_000, 3);
    expect([0, 1, 2, 3, 4].map((t) => s.take(t).log)).toEqual([true, true, true, false, false]);
    expect(s.take(60_000)).toEqual({ log: true, suppressed: 2 });
  });

  test("an SDK error message never becomes a failure reason", async () => {
    const models = {
      async generateContentStream(_p: GenerateContentParameters): Promise<AsyncGenerator<GenerateContentResponse>> {
        throw new Error("echo of the prompt: Secretingredient");
      },
    };
    const g = new GeminiRecipeGenerator({ project: "p", location: "global", model: "gemini-3.8-flash", thinkingLevel: "LOW" }, models);
    const req = validateGenerateRequest(request(), Date.now());
    await expect(g.generate(req, buildPrompt(req, "LRD-TEST"), new AbortController().signal, { maxOutputTokens: 10, hardTokenCeiling: 20 }))
      .rejects.toMatchObject({ message: "Vertex AI call failed" });
  });
});

// --- 2. Cheap-call cost ------------------------------------------------------------------------

describe("2. cheap calls", () => {
  test("config/runtime is read once per TTL, a failed read fails closed, concurrent loads share one read", async () => {
    let now = 0;
    let reads = 0;
    let fail = false;
    const cache = new RuntimeConfigCache(async () => {
      reads++;
      if (fail) throw new Error("firestore down");
      return { ...DEFAULT_RUNTIME_CONFIG } as RuntimeConfig;
    }, () => now, 30_000);
    await Promise.all([cache.get(), cache.get(), cache.get()]);
    expect(reads).toBe(1);
    now = 29_999;
    await cache.get();
    expect(reads).toBe(1);
    now = 30_000;
    fail = true;
    await expect(cache.get()).rejects.toThrow("firestore down");
    // No stale copy after a failure.
    await expect(cache.get()).rejects.toThrow("firestore down");
    fail = false;
    await cache.get();
    expect(reads).toBe(4);
  });

  test("IPv6 callers are throttled by /64; IPv4 and mapped IPv4 by address", () => {
    expect(ipKey("2001:db8:1:2:aaaa::1")).toBe("2001:db8:1:2::/64");
    expect(ipKey("2001:0db8:0001:0002:ffff:ffff:ffff:ffff")).toBe("2001:db8:1:2::/64");
    expect(ipKey("2001:db8::1")).toBe("2001:db8:0:0::/64");
    expect(ipKey("::ffff:192.0.2.7")).toBe("192.0.2.7");
    expect(ipKey("192.0.2.7")).toBe("192.0.2.7");
    const t = new CallThrottle();
    for (let i = 0; i < 120; i++) t.admit(`u${i}`, `2001:db8:1:2::${i.toString(16)}`, 0);
    expect(() => t.admit("fresh", "2001:db8:1:2:dead:beef::1", 1)).toThrow(HttpsError);
  });

  test("the per-instance ceiling bounds every call let through, whatever the keys", () => {
    const t = new CallThrottle(5);
    for (let i = 0; i < 5; i++) t.admit(`u${i}`, `10.0.0.${i}`, 0);
    expect(() => t.admit("u9", "10.0.0.9", 1)).toThrow(HttpsError);
    expect(() => t.admit("u9", "10.0.0.9", 60_000)).not.toThrow();
  });
});

// --- 3. getGeneration --------------------------------------------------------------------------

describe("3. getGeneration", () => {
  test("returns generateRecipe's response for the caller's generation without touching stats, the LLM or the balance", async () => {
    await seed("g1", 3);
    const req = request();
    const done = await generateRecipe(deps, callerFor("g1"), req);
    const s = spy(deps);
    const polled = await getGeneration(s.deps, callerFor("g1"), { requestId: req.requestId.toUpperCase() });
    expect(polled).toEqual(done);
    expect(s.collections).not.toContain("stats");
    expect(deps.generator.calls).toBe(1);
    expect(await balanceOf("g1")).toBe(2);
  });

  test("a pending generation is IN_PROGRESS; another user's or an unknown id is not-found / UNKNOWN_REQUEST", async () => {
    await seed("g1", 3);
    const slow = deps.generator.blockNext();
    const req = request();
    const live = generateRecipe(deps, callerFor("g1"), req);
    await slow.started;
    expect(await getGeneration(deps, callerFor("g1"), { requestId: req.requestId }))
      .toMatchObject({ status: "IN_PROGRESS", charged: true, balance: 2 });
    try {
      await getGeneration(deps, callerFor("g2"), { requestId: req.requestId });
      throw new Error("expected not-found");
    } catch (e) {
      expect(e).toBeInstanceOf(HttpsError);
      expect((e as HttpsError).code).toBe("not-found");
      expect((e as HttpsError).details).toEqual({ reason: "UNKNOWN_REQUEST" });
    }
    expect(await reason(getGeneration(deps, callerFor("g1"), { requestId: randomUUID() }))).toBe("UNKNOWN_REQUEST");
    expect(await reason(getGeneration(deps, callerFor("g1"), { requestId: "nope" }))).toBe("INVALID_REQUEST");
    slow.release();
    await live;
    expect(deps.generator.calls).toBe(1);
  });
});

// --- 4. Spend ceilings -------------------------------------------------------------------------

describe("4. spend ceilings", () => {
  test("defaults: the global ceiling is base + a share of 7-day net revenue, capped; the per-user one is a fraction of it", () => {
    const c = parseRuntimeConfig({});
    expect(dailySpendCeiling(c, 0)).toBe(1_200_000);
    // 10 credits net x USD 0.12 x 0.25 = USD 0.30 more.
    expect(dailySpendCeiling(c, 10)).toBe(1_500_000);
    expect(dailySpendCeiling(c, 10_000)).toBe(2_000_000);
    expect(dailySpendCeiling(c, -50)).toBe(1_200_000);
    expect(c.perUserDailySpendMicroUsd * 2).toBeLessThan(c.dailySpendBaseMicroUsd);
  });

  test("an ordinary user can run two 3-course menus a day at a realistic measured cost; the third is refused", async () => {
    await seed("m1", 50);
    deps.generator.usage = fakeUsage({ inputTokens: 3000, outputTokens: 12_000, thinkingTokens: 2000 });
    expect((await generateRecipe(deps, callerFor("m1"), menu3())).status).toBe("DONE");
    expect((await generateRecipe(deps, callerFor("m1"), menu3())).status).toBe("DONE");
    expect(await reason(generateRecipe(deps, callerFor("m1"), menu3()))).toBe("USER_DAILY_LIMIT");
    // Recipes still fit after the two menus.
    expect((await generateRecipe(deps, callerFor("m1"), request())).status).toBe("DONE");
    expect(worstCase(menu3())).toBeGreaterThan(200_000);
  });

  test("two accounts at their own ceilings cannot pause generation for everyone", async () => {
    deps.generator.usage = undefined; // every call booked at the worst case
    for (const u of ["a1", "a2"]) {
      await seed(u, 50);
      for (let i = 0; i < 5; i++) await generateRecipe(deps, callerFor(u), menu3()).catch(() => undefined);
      expect(await reason(generateRecipe(deps, callerFor(u), menu3()))).toBe("USER_DAILY_LIMIT");
    }
    await seed("a3", 5);
    deps.generator.usage = fakeUsage();
    expect((await generateRecipe(deps, callerFor("a3"), menu3())).status).toBe("DONE");
  });

  test("production sales raise today's ceiling; sandbox grants do not; refunds take it back", async () => {
    await seed("r1", 5);
    const sRef = statsRef(db, utcDay(deps.now()));
    await sRef.set({ spentMicroUsd: 1_200_000 - worstCase(request()) + 1 });
    expect(await reason(generateRecipe(deps, callerFor("r1"), request()))).toBe("GENERATION_DISABLED");
    // A production grant 3 days ago (in the 7-day window) of 10 credits: +USD 0.30.
    await statsRef(db, utcDay(deps.now() - 3 * 86_400_000)).set({ netCreditsGranted: 10 });
    expect((await generateRecipe(deps, callerFor("r1"), request())).status).toBe("DONE");

    const token = deps.play.add(playToken());
    await verifyPurchase(deps, callerFor("buyer"), { store: "play", purchaseToken: token });
    expect((await sRef.get()).data()?.netCreditsGranted).toBe(10);
  });

  test("timeouts (unknown usage) are refunded once per 10 paid credits over the account's life, then charged", async () => {
    await seed("t1", 10);
    await setConfig({ perUserPerMinute: 100 });
    deps.generator.failWith("infra", 2);
    expect(await generateRecipe(deps, callerFor("t1"), request())).toMatchObject({ status: "FAILED", charged: false, balance: 10 });
    expect(await generateRecipe(deps, callerFor("t1"), request())).toMatchObject({ status: "FAILED", charged: true, balance: 9 });
    expect(await user("t1")).toMatchObject({ timeoutRefundsUsed: 1, consumedCredits: 1 });
  });

  test("timeout allowance usage moves with mergeBalance", async () => {
    const anon = await anonymousUser();
    const signed = await passwordUser();
    await seed(anon.uid, 10, { timeoutRefundsUsed: 1 });
    await seed(signed.uid, 0, { timeoutRefundsUsed: 2 });
    await mergeBalance(deps, callerFor(signed.uid), { sourceIdToken: anon.idToken });
    expect(await user(signed.uid)).toMatchObject({ timeoutRefundsUsed: 3 });
  });
});

// --- 5. Missing usageMetadata ------------------------------------------------------------------

describe("5. a stream without usageMetadata is unknown usage, booked at the worst case", () => {
  test("adapter reports no usage; settle books the reservation", async () => {
    const recipe = { title: "t", summary: "s", minutes: 10, servings: 2, difficulty: "easy",
      ingredients: [{ amount: "1", name: "x", fromPantry: true }], steps: ["a"] };
    const models = {
      async generateContentStream(): Promise<AsyncGenerator<GenerateContentResponse>> {
        return (async function* () {
          yield { candidates: [{ finishReason: "STOP", content: { parts: [{ text: JSON.stringify(recipe) }] } }] } as never;
        })();
      },
    };
    const g = new GeminiRecipeGenerator({ project: "p", location: "global", model: "gemini-3.8-flash", thinkingLevel: "LOW" }, models);
    const res = await g.generate(validateGenerateRequest(request(), Date.now()), buildPrompt(validateGenerateRequest(request(), Date.now()), "LRD-TEST"), new AbortController().signal, { maxOutputTokens: 4096, hardTokenCeiling: 8192 });
    expect(res.usage).toBeUndefined();

    await seed("u5", 2);
    deps.generator.usage = undefined;
    const req = request();
    expect((await generateRecipe(deps, callerFor("u5"), req)).status).toBe("DONE");
    expect((await statsRef(db, utcDay(deps.now())).get()).data()).toMatchObject({ spentMicroUsd: worstCase(req), unknownUsageCalls: 1 });
  });
});

// --- 6. stats contention -----------------------------------------------------------------------

describe("6. the shared stats docs are read only when needed", () => {
  test("refused and repeated generateRecipe calls never read stats", async () => {
    const s = spy(deps);
    expect(await reason(generateRecipe(s.deps, callerFor("poor"), request()))).toBe("INSUFFICIENT_CREDITS");
    await seed("rich", 3);
    const req = request();
    await generateRecipe(deps, callerFor("rich"), req);
    await generateRecipe(s.deps, callerFor("rich"), req);
    expect(s.txReads.filter((p) => p.startsWith("stats/"))).toEqual([]);
    // A new reservation does read them.
    await generateRecipe(s.deps, callerFor("rich"), request());
    expect(s.txReads.some((p) => p.startsWith("stats/"))).toBe(true);
  });

  test("a production grant reads no stats; a sandbox grant does", async () => {
    const s = spy(deps);
    await verifyPurchase(s.deps, callerFor("p6"), { store: "play", purchaseToken: deps.play.add(playToken()) });
    expect(s.txReads.filter((p) => p.startsWith("stats/"))).toEqual([]);
    await setConfig({ allowSandbox: true });
    await verifyPurchase(s.deps, callerFor("p6"), { store: "play", purchaseToken: deps.play.add(playToken(), { isTest: true }) });
    expect(s.txReads.filter((p) => p.startsWith("stats/"))).toHaveLength(1);
  });
});

// --- 7. Local checks before store calls --------------------------------------------------------

describe("7. verifyPurchase answers from the local record and ties the store to the app", () => {
  test("a settled Play purchase is answered without calling Play", async () => {
    const token = deps.play.add(playToken());
    await verifyPurchase(deps, callerFor("v7"), { store: "play", purchaseToken: token });
    const calls = deps.play.getCalls;
    expect((await verifyPurchase(deps, callerFor("v7"), { store: "play", purchaseToken: token })).status).toBe("ALREADY_GRANTED");
    expect((await verifyPurchase(deps, callerFor("other"), { store: "play", purchaseToken: token })).status).toBe("ALREADY_GRANTED");
    expect(deps.play.getCalls).toBe(calls);
  });

  test("a settled App Store purchase is answered after local JWS verification, without calling Apple", async () => {
    const tx = appleTx();
    await verifyPurchase(deps, callerFor("v7"), { store: "appstore", signedTransaction: deps.appStore.sign(tx) });
    expect(deps.appStore.fetchCalls).toBe(1);
    expect((await verifyPurchase(deps, callerFor("v7"), { store: "appstore", signedTransaction: deps.appStore.sign(tx) })).status)
      .toBe("ALREADY_GRANTED");
    expect(deps.appStore.fetchCalls).toBe(1);
  });

  test("Play only from the Android app, App Store only from the iOS app", async () => {
    const token = deps.play.add(playToken());
    const ios = callerFor("v7", "password", 0, IOS_FIREBASE_APP_ID);
    const android = callerFor("v7", "password", 0, ANDROID_FIREBASE_APP_ID);
    expect(await reason(verifyPurchase(deps, ios, { store: "play", purchaseToken: token }))).toBe("STORE_MISMATCH");
    expect(await reason(verifyPurchase(deps, android, { store: "appstore", signedTransaction: deps.appStore.sign(appleTx()) })))
      .toBe("STORE_MISMATCH");
    expect(await reason(verifyPurchase(deps, callerFor("v7", "password", 0, "1:1:web:x"), { store: "play", purchaseToken: token })))
      .toBe("STORE_MISMATCH");
    expect(deps.play.getCalls).toBe(0);
    expect(deps.appStore.fetchCalls).toBe(0);
  });
});

// --- 8. Purchase account token -----------------------------------------------------------------

describe("8. purchases are bound to the buyer's account when the client set the token", () => {
  test("the token is UUIDv5 of the uid in the fixed namespace (RFC 9562 test vector for the algorithm)", () => {
    // RFC 9562 appendix A.4: UUIDv5 of "www.example.com" in the DNS namespace.
    expect(uuidV5("6ba7b810-9dad-11d1-80b4-00c04fd430c8", "www.example.com")).toBe("2ed6657d-e927-568b-95e1-2665a8aea6a2");
    // Documented in CONTRACT.md as the client test vector.
    expect(purchaseAccountToken("test-uid-123")).toBe("e0612f8d-d882-5109-b5f4-6e979f3b69ce");
  });

  test("Play: the caller's token grants and is recorded; another account's token is refused and grants nothing", async () => {
    const mine = deps.play.add(playToken(), { obfuscatedExternalAccountId: purchaseAccountToken("alice") });
    expect((await verifyPurchase(deps, callerFor("alice"), { store: "play", purchaseToken: mine })).status).toBe("GRANTED");
    expect((await purchase(playPurchaseId(mine)))?.accountToken).toBe(purchaseAccountToken("alice"));
    const theirs = deps.play.add(playToken(), { obfuscatedExternalAccountId: purchaseAccountToken("bob") });
    expect(await reason(verifyPurchase(deps, callerFor("alice"), { store: "play", purchaseToken: theirs }))).toBe("PURCHASE_ACCOUNT_MISMATCH");
    expect(await purchase(playPurchaseId(theirs))).toBeUndefined();
    expect(await balanceOf("alice")).toBe(10);
    // Still redeemable by its buyer.
    expect((await verifyPurchase(deps, callerFor("bob"), { store: "play", purchaseToken: theirs })).status).toBe("GRANTED");
  });

  test("App Store: a purchase made by an anonymous install that was later merged redeems for the merged account", async () => {
    await db.collection("users").doc("anon1").set({ ...emptyUser(), mergedInto: "carol" });
    const tx = appleTx({ appAccountToken: purchaseAccountToken("anon1").toUpperCase() });
    expect((await verifyPurchase(deps, callerFor("carol"), { store: "appstore", signedTransaction: deps.appStore.sign(tx) })).status)
      .toBe("GRANTED");
    const other = appleTx({ appAccountToken: purchaseAccountToken("mallory") });
    expect(await reason(verifyPurchase(deps, callerFor("carol"), { store: "appstore", signedTransaction: deps.appStore.sign(other) })))
      .toBe("PURCHASE_ACCOUNT_MISMATCH");
  });
});

// --- 9. Partial refunds ------------------------------------------------------------------------

describe("9. partial refunds", () => {
  test("Play units refunded before submission are not granted; all refunded is rejected", async () => {
    const part = deps.play.add(playToken(), { quantity: 3, refundableQuantity: 2 });
    expect((await verifyPurchase(deps, callerFor("p9"), { store: "play", purchaseToken: part })).status).toBe("GRANTED");
    expect(await balanceOf("p9")).toBe(20);
    expect(await purchase(playPurchaseId(part))).toMatchObject({ credits: 30, refundedCredits: 10, state: "refunded" });
    const all = deps.play.add(playToken(), { quantity: 2, refundableQuantity: 0 });
    expect((await verifyPurchase(deps, callerFor("p9"), { store: "play", purchaseToken: all })).status).toBe("REJECTED_PERMANENT");
    expect(await balanceOf("p9")).toBe(20);
  });

  test("Apple REFUND_REVERSED keeps what the transaction still reports refunded", async () => {
    const tx = appleTx({ quantity: 2 });
    await verifyPurchase(deps, callerFor("p9"), { store: "appstore", signedTransaction: deps.appStore.sign(tx) });
    const t0 = Date.now();
    await handleAppleNotification(deps, { signedPayload: deps.appStore.signNotification("REFUND", { ...tx, revocationDate: t0 }, t0) });
    expect(await balanceOf("p9")).toBe(0);
    const stillHalf = { ...tx, revocationDate: t0, revocationPercentage: 50_000 };
    await handleAppleNotification(deps, { signedPayload: deps.appStore.signNotification("REFUND_REVERSED", stillHalf, t0 + 1) });
    expect(await balanceOf("p9")).toBe(10);
    expect((await purchase(applePurchaseId("production", tx.transactionId)))?.refundedCredits).toBe(10);
    // A reversal whose transaction still carries revocationDate (no percentage) restores everything.
    await handleAppleNotification(deps, { signedPayload: deps.appStore.signNotification("REFUND_REVERSED", { ...tx, revocationDate: t0 }, t0 + 2) });
    expect(await balanceOf("p9")).toBe(20);
  });
});

// --- 10. Sweeper -------------------------------------------------------------------------------

describe("10. sweeper", () => {
  test("one failing settle does not stop the others", async () => {
    await seed("s1", 3);
    await seed("s2", 3);
    const past = deps.now() - 10 * 60_000;
    const base = { state: "pending", kind: "recipe", cost: 1, reservedMicroUsd: 5, createdAt: new Date(past), deadlineAt: new Date(past) };
    await db.doc("users/s1/generations/bad").set({ ...base, statsDay: "a/b" });
    await db.doc("users/s2/generations/good").set({ ...base, statsDay: utcDay(past) });
    await db.doc("users/s1").update({ inFlight: 1 });
    await db.doc("users/s2").update({ inFlight: 1 });
    const r = await sweep(deps);
    expect(r).toMatchObject({ settled: 1, failures: 1 });
    expect((await db.doc("users/s2/generations/good").get()).data()?.state).toBe("failed");
    expect(deps.logs.find((l) => l.message === "sweep_item_failed")?.data).toMatchObject({ queue: "generations", id: "bad" });
  });

  test("consume retries page through the whole queue, and a stuck purchase is dropped with an alert", async () => {
    deps.play.failConsume = true;
    const tokens: string[] = [];
    for (let i = 0; i < 5; i++) {
      const t = deps.play.add(playToken());
      tokens.push(t);
      await verifyPurchase(deps, callerFor("s3"), { store: "play", purchaseToken: t });
    }
    await db.collection("purchases").doc(playPurchaseId(tokens[0])).update({ consumeAttempts: CONSUME_MAX_ATTEMPTS - 1 });
    deps.clock.advance(61_000);
    await sweep(deps, 2);
    expect(await purchase(playPurchaseId(tokens[0]))).toMatchObject({ consumePending: false, consumeAttempts: CONSUME_MAX_ATTEMPTS });
    expect(deps.logs.filter((l) => l.message === "play_consume_gave_up")).toHaveLength(1);
    deps.play.failConsume = false;
    const r = await sweep(deps, 2);
    expect(r.consumed).toBe(4);
    expect(deps.play.consumed).toHaveLength(4);
  });

  test("a growing total of negative balances is an alert", async () => {
    await seed("n1", -5);
    expect(await watchNegativeBalances(deps)).toBe(-5);
    expect(deps.logs.filter((l) => l.message === "negative_balance_total_grew")).toHaveLength(1);
    await watchNegativeBalances(deps);
    expect(deps.logs.filter((l) => l.message === "negative_balance_total_grew")).toHaveLength(1);
    await seed("n2", -1);
    await watchNegativeBalances(deps);
    expect(deps.logs.filter((l) => l.message === "negative_balance_total_grew")).toHaveLength(2);
  });
});

// --- 11. Emulator fakes ------------------------------------------------------------------------

describe("11. emulator fakes", () => {
  test("need the emulator, the flag and a demo- project", () => {
    const env = { FUNCTIONS_EMULATOR: "true", EMULATOR_FAKES: "true" };
    expect(emulatorFakesEnabled({ ...env, GCLOUD_PROJECT: "demo-foodtracker" })).toBe(true);
    expect(emulatorFakesEnabled({ ...env, GCLOUD_PROJECT: "bestbefore-f9ee1" })).toBe(false);
    expect(emulatorFakesEnabled({ ...env })).toBe(false);
    expect(emulatorFakesEnabled({ EMULATOR_FAKES: "true", GCLOUD_PROJECT: "demo-x" })).toBe(false);
  });
});

// --- getBalance shape stays the same for everyone else --------------------------------------------

test("getBalance carries no purchasesBlocked for an ordinary buyer", async () => {
  await verifyPurchase(deps, callerFor("ord"), { store: "play", purchaseToken: deps.play.add(playToken()) });
  expect(await getBalance(deps, callerFor("ord"))).toEqual({ balance: 10, costs: DEFAULT_RUNTIME_CONFIG.costs });
});
