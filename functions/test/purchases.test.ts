import { DEFAULT_COSTS } from "../src/constants";
import { HttpsError } from "firebase-functions/v2/https";
import { deleteAccount } from "../src/core/account";
import { playPurchaseId, applePurchaseId } from "../src/core/purchases";
import { sweep } from "../src/core/sweeper";
import { appleTx, balanceOf, callerFor, db, makeDeps, playToken, resetEmulators, setConfig, verifyPurchase, type TestDeps } from "./helpers";

const alice = callerFor("alice");
const bob = callerFor("bob");

let deps: TestDeps;
beforeEach(async () => {
  await resetEmulators();
  deps = makeDeps();
});

const play = (purchaseToken: string) => ({ store: "play", purchaseToken });
const apple = (signedTransaction: string) => ({ store: "appstore", signedTransaction });
const purchase = async (id: string) => (await db.collection("purchases").doc(id).get()).data();

async function reason(p: Promise<unknown>): Promise<string> {
  try {
    await p;
  } catch (e) {
    if (e instanceof HttpsError) return (e.details as { reason: string }).reason;
    throw e;
  }
  return "no error";
}

describe("Play", () => {
  test("grants credits from Play's product and quantity, then consumes", async () => {
    const token = deps.play.add(playToken(), { productId: "recipes_50", quantity: 2 });
    expect(await verifyPurchase(deps, alice, play(token))).toEqual({ status: "GRANTED", balance: 100, costs: DEFAULT_COSTS });
    expect(deps.play.consumed).toEqual([token]);
    const doc = await purchase(playPurchaseId(token));
    expect(doc).toMatchObject({ uid: "alice", productId: "recipes_50", quantity: 2, credits: 100, state: "consumed" });
    expect(doc?.purchaseToken).toBeUndefined();
    expect(doc?.consumePending).toBeUndefined();
  });

  test("a second submission is a no-op", async () => {
    const token = deps.play.add(playToken());
    await verifyPurchase(deps, alice, play(token));
    expect(await verifyPurchase(deps, alice, play(token))).toEqual({ status: "ALREADY_GRANTED", balance: 10, costs: DEFAULT_COSTS });
    expect(await balanceOf("alice")).toBe(10);
  });

  test("parallel submissions of one purchase grant once", async () => {
    const token = deps.play.add(playToken());
    const results = await Promise.all(Array.from({ length: 5 }, () => verifyPurchase(deps, alice, play(token))));
    expect(results.filter((r) => r.status === "GRANTED")).toHaveLength(1);
    expect(await balanceOf("alice")).toBe(10);
  });

  test("a replay under another uid grants nothing", async () => {
    const token = deps.play.add(playToken());
    await verifyPurchase(deps, alice, play(token));
    expect(await verifyPurchase(deps, bob, play(token))).toEqual({ status: "ALREADY_GRANTED", balance: 0, costs: DEFAULT_COSTS });
    expect(await balanceOf("bob")).toBe(0);
  });

  test("a replay after the owner deleted the account grants nothing", async () => {
    const token = deps.play.add(playToken());
    await verifyPurchase(deps, alice, play(token));
    await deleteAccount(deps, alice, {});
    expect((await purchase(playPurchaseId(token)))?.uid).toBeNull();
    expect(await verifyPurchase(deps, bob, play(token))).toEqual({ status: "ALREADY_GRANTED", balance: 0, costs: DEFAULT_COSTS });
    // The deleted account itself can do nothing more, though its token is still valid for a while.
    expect(await reason(verifyPurchase(deps, alice, play(deps.play.add(playToken()))))).toBe("ACCOUNT_CLOSED");
  });

  test("pending, cancelled, unknown and unknown-product purchases", async () => {
    expect((await verifyPurchase(deps, alice, play(deps.play.add(playToken(), { purchaseState: "PENDING" })))).status).toBe("RETRY");
    expect((await verifyPurchase(deps, alice, play(deps.play.add(playToken(), { purchaseState: "CANCELLED" })))).status)
      .toBe("REJECTED_PERMANENT");
    expect((await verifyPurchase(deps, alice, play(playToken()))).status).toBe("REJECTED_PERMANENT");
    expect((await verifyPurchase(deps, alice, play(deps.play.add(playToken(), { productId: "gold_coins" })))).status)
      .toBe("REJECTED_PERMANENT");
    expect(await balanceOf("alice")).toBe(0);
  });

  test("Play being unreachable is RETRY", async () => {
    const token = deps.play.add(playToken());
    deps.play.failGet = true;
    expect((await verifyPurchase(deps, alice, play(token))).status).toBe("RETRY");
    deps.play.failGet = false;
    expect((await verifyPurchase(deps, alice, play(token))).status).toBe("GRANTED");
  });

  test("a failed consume is retried on resubmission and by the sweeper", async () => {
    const t1 = deps.play.add(playToken());
    const t2 = deps.play.add(playToken());
    deps.play.failConsume = true;
    expect((await verifyPurchase(deps, alice, play(t1))).status).toBe("GRANTED");
    expect((await verifyPurchase(deps, alice, play(t2))).status).toBe("GRANTED");
    expect(await purchase(playPurchaseId(t1))).toMatchObject({ state: "granted", consumePending: true, consumeAttempts: 1 });
    deps.play.failConsume = false;
    // Resubmission retries the consume (and grants nothing more).
    expect(await verifyPurchase(deps, alice, play(t1))).toEqual({ status: "ALREADY_GRANTED", balance: 20, costs: DEFAULT_COSTS });
    expect((await purchase(playPurchaseId(t1)))?.state).toBe("consumed");
    // The sweeper picks up the other one once it is old enough.
    expect((await sweep(deps)).consumed).toBe(0);
    deps.clock.advance(61_000);
    expect((await sweep(deps)).consumed).toBe(1);
    expect((await purchase(playPurchaseId(t2)))?.state).toBe("consumed");
    expect(await balanceOf("alice")).toBe(20);
  });

  test("licence-tester purchases grant only while allowSandbox is on, under a daily cap", async () => {
    const t1 = deps.play.add(playToken(), { isTest: true });
    expect((await verifyPurchase(deps, alice, play(t1))).status).toBe("REJECTED_PERMANENT");
    await setConfig({ allowSandbox: true, sandboxDailyCap: 1 });
    const t2 = deps.play.add(playToken(), { isTest: true });
    expect((await verifyPurchase(deps, alice, play(t2))).status).toBe("GRANTED");
    expect((await purchase(playPurchaseId(t2)))?.env).toBe("sandbox");
    const t3 = deps.play.add(playToken(), { isTest: true });
    expect((await verifyPurchase(deps, alice, play(t3))).status).toBe("RETRY");
    expect(await balanceOf("alice")).toBe(10);
  });

  test("sandbox grants are also capped in credits per day; one larger than the whole cap is rejected", async () => {
    await setConfig({ allowSandbox: true, sandboxDailyCap: 20, sandboxDailyCredits: 120 });
    const big = deps.play.add(playToken(), { isTest: true, productId: "recipes_100", quantity: 10 });
    expect((await verifyPurchase(deps, alice, play(big))).status).toBe("REJECTED_PERMANENT");
    const t1 = deps.play.add(playToken(), { isTest: true, productId: "recipes_100" });
    expect((await verifyPurchase(deps, alice, play(t1))).status).toBe("GRANTED");
    const t2 = deps.play.add(playToken(), { isTest: true, productId: "recipes_50" });
    expect((await verifyPurchase(deps, alice, play(t2))).status).toBe("RETRY");
    const t3 = deps.play.add(playToken(), { isTest: true, productId: "recipes_10" });
    expect((await verifyPurchase(deps, alice, play(t3))).status).toBe("GRANTED");
    expect(await balanceOf("alice")).toBe(110);
  });

  test("malformed tokens never reach Play", async () => {
    expect(await reason(verifyPurchase(deps, alice, play("short")))).toBe("INVALID_REQUEST");
    expect(await reason(verifyPurchase(deps, alice, play(`${"x".repeat(30)}/../`)))).toBe("INVALID_REQUEST");
    expect(await reason(verifyPurchase(deps, alice, { store: "steam" }))).toBe("INVALID_REQUEST");
    expect(deps.play.getCalls).toBe(0);
  });

  test("verifyPurchase is throttled per uid", async () => {
    await setConfig({ verifyPerMinute: 2 });
    await verifyPurchase(deps, alice, play(playToken()));
    await verifyPurchase(deps, alice, play(playToken()));
    expect(await reason(verifyPurchase(deps, alice, play(playToken())))).toBe("RATE_LIMITED");
    expect(deps.play.getCalls).toBe(2);
  });
});

describe("App Store", () => {
  test("grants a verified consumable", async () => {
    const tx = appleTx({ productId: "recipes_100", quantity: 1 });
    expect(await verifyPurchase(deps, alice, apple(deps.appStore.sign(tx)))).toEqual({ status: "GRANTED", balance: 100, costs: DEFAULT_COSTS });
    expect(await purchase(applePurchaseId("production", tx.transactionId))).toMatchObject({ uid: "alice", state: "granted" });
  });

  test("replay of the same transaction, as another JWS or another uid, grants nothing", async () => {
    const tx = appleTx();
    await verifyPurchase(deps, alice, apple(deps.appStore.sign(tx)));
    expect((await verifyPurchase(deps, alice, apple(deps.appStore.sign(tx)))).status).toBe("ALREADY_GRANTED");
    expect((await verifyPurchase(deps, bob, apple(deps.appStore.sign(tx)))).status).toBe("ALREADY_GRANTED");
    expect(await balanceOf("alice")).toBe(10);
    expect(await balanceOf("bob")).toBe(0);
  });

  test("forged, foreign and non-consumable transactions are rejected", async () => {
    expect((await verifyPurchase(deps, alice, apple("eyJh.eyJh.c2ln"))).status).toBe("REJECTED_PERMANENT");
    expect((await verifyPurchase(deps, alice, apple(deps.appStore.sign(appleTx({ bundleId: "com.other.app" }))))).status)
      .toBe("REJECTED_PERMANENT");
    expect((await verifyPurchase(deps, alice, apple(deps.appStore.sign(appleTx({ type: "Non-Consumable" }))))).status)
      .toBe("REJECTED_PERMANENT");
    expect(await balanceOf("alice")).toBe(0);
  });

  test("a transaction refunded before submission is rejected and can never be redeemed", async () => {
    const tx = appleTx();
    deps.appStore.fresh.set(tx.transactionId, { ...tx, revocationDate: Date.now() });
    const jws = deps.appStore.sign(tx);
    expect((await verifyPurchase(deps, alice, apple(jws))).status).toBe("REJECTED_PERMANENT");
    expect(await purchase(applePurchaseId("production", tx.transactionId))).toMatchObject({ uid: null, state: "refunded" });
    deps.appStore.fresh.delete(tx.transactionId);
    expect((await verifyPurchase(deps, bob, apple(jws))).status).toBe("REJECTED_PERMANENT");
    expect(await balanceOf("bob")).toBe(0);
  });

  test("the App Store Server API being down is RETRY", async () => {
    deps.appStore.failFetch = true;
    expect((await verifyPurchase(deps, alice, apple(deps.appStore.sign(appleTx())))).status).toBe("RETRY");
  });

  test("sandbox transactions follow allowSandbox", async () => {
    const tx = appleTx({ environment: "sandbox" });
    expect((await verifyPurchase(deps, alice, apple(deps.appStore.sign(tx)))).status).toBe("REJECTED_PERMANENT");
    await setConfig({ allowSandbox: true });
    const tx2 = appleTx({ environment: "sandbox" });
    expect((await verifyPurchase(deps, alice, apple(deps.appStore.sign(tx2)))).status).toBe("GRANTED");
    expect(await purchase(applePurchaseId("sandbox", tx2.transactionId))).toMatchObject({ env: "sandbox" });
  });
});
