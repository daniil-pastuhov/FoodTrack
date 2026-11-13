/**
 * The FIFO ledger and what is built on it: answering Apple's CONSUMPTION_REQUEST for consenting
 * buyers, and the refund-after-use blocklist.
 */
import { deleteAccount, getBalance, mergeBalance } from "../src/core/account";
import { generateRecipe, getGeneration } from "../src/core/generation";
import { handleAppleNotification, handlePlayNotification } from "../src/core/notifications";
import { applePurchaseId, playPurchaseId } from "../src/core/purchases";
import { sweep } from "../src/core/sweeper";
import { CONSUMPTION_ANSWER_WINDOW_MS, DEFAULT_COSTS } from "../src/constants";
import { emptyUser } from "../src/model";
import { randomUUID } from "node:crypto";
import {
  anonymousUser,
  appleTx,
  balanceOf,
  callerFor,
  db,
  makeDeps,
  passwordUser,
  playToken,
  resetEmulators,
  verifyPurchase,
  type TestDeps,
} from "./helpers";
import type { AppleTransaction } from "../src/ports";

let deps: TestDeps;
beforeEach(async () => {
  await resetEmulators();
  deps = makeDeps();
});

const purchase = async (id: string) => (await db.collection("purchases").doc(id).get()).data();
const buyer = async (key: string) => (await db.collection("buyers").doc(key).get()).data();
/** Spends credits the way generation does: balance down, consumedCredits up. */
const spend = async (uid: string, n: number) => {
  const u = (await db.collection("users").doc(uid).get()).data()!;
  await db.collection("users").doc(uid).update({ balance: u.balance - n, consumedCredits: (u.consumedCredits ?? 0) + n });
};

async function buyApple(uid: string, over: Partial<AppleTransaction> = {}, consumptionConsent?: boolean) {
  const tx = appleTx(over);
  deps.clock.advance(1000); // distinct grant times, for FIFO order
  const res = await verifyPurchase(deps, callerFor(uid), {
    store: "appstore", signedTransaction: deps.appStore.sign(tx), ...(consumptionConsent === undefined ? {} : { consumptionConsent }),
  });
  expect(res.status).toBe("GRANTED");
  return tx;
}

async function buyPlay(uid: string, quantity = 1) {
  const token = deps.play.add(playToken(), { quantity });
  deps.clock.advance(1000);
  expect((await verifyPurchase(deps, callerFor(uid), { store: "play", purchaseToken: token })).status).toBe("GRANTED");
  return token;
}

let signedAt = Date.now();
const notify = (type: string, tx: AppleTransaction | null, uuid?: string) =>
  handleAppleNotification(deps, { signedPayload: deps.appStore.signNotification(type, tx, ++signedAt, uuid) });
const refundApple = (tx: AppleTransaction) => notify("REFUND", { ...tx, revocationDate: signedAt });

function rtdn(token: string, refundType = 1) {
  return {
    packageName: "com.foodtracker.android",
    eventTimeMillis: String(++signedAt),
    voidedPurchaseNotification: { purchaseToken: token, orderId: "GPA.1", productType: 2, refundType },
  };
}

describe("consumptionConsent and CONSUMPTION_REQUEST", () => {
  test("consent is recorded with its time; no answer is sent without it", async () => {
    const tx = await buyApple("c1", {}, true);
    const doc = await purchase(applePurchaseId("production", tx.transactionId));
    expect(doc).toMatchObject({ consumptionConsent: true });
    expect(doc?.consumptionConsentAt).toBeDefined();
    const noConsent = await buyApple("c1");
    expect((await notify("CONSUMPTION_REQUEST", noConsent)).outcome).toBe("consumption request: not consented");
    expect(deps.appStore.consumptionSent).toEqual([]);
  });

  test("answers with the FIFO-consumed share: unused, partly used and fully used purchases", async () => {
    const first = await buyApple("c2", {}, true); // credits 1-10
    const second = await buyApple("c2", {}, true); // credits 11-20
    const third = await buyApple("c2", {}, true); // credits 21-30
    await spend("c2", 14); // all of the first, 4 of the second
    for (const tx of [first, second, third]) {
      expect((await notify("CONSUMPTION_REQUEST", tx)).outcome).toBe("consumption request: answered");
    }
    expect(deps.appStore.consumptionSent.map((s) => [s.transactionId, s.info])).toEqual([
      [first.transactionId, { customerConsented: true, consumptionPercentage: 100_000, deliveryStatus: "DELIVERED", sampleContentProvided: true }],
      [second.transactionId, { customerConsented: true, consumptionPercentage: 40_000, deliveryStatus: "DELIVERED", sampleContentProvided: true }],
      [third.transactionId, { customerConsented: true, consumptionPercentage: 0, deliveryStatus: "DELIVERED", sampleContentProvided: true }],
    ]);
    expect((await purchase(applePurchaseId("production", second.transactionId)))?.consumption)
      .toMatchObject({ consumptionPercentage: 40_000, attempts: 1 });
  });

  test("consent given on a later resubmission counts; a repeated notification is answered once", async () => {
    const tx = appleTx();
    const jws = deps.appStore.sign(tx);
    await verifyPurchase(deps, callerFor("c3"), { store: "appstore", signedTransaction: jws });
    await verifyPurchase(deps, callerFor("c3"), { store: "appstore", signedTransaction: jws, consumptionConsent: true });
    const uuid = randomUUID();
    expect((await notify("CONSUMPTION_REQUEST", tx, uuid)).outcome).toBe("consumption request: answered");
    expect((await notify("CONSUMPTION_REQUEST", tx, uuid)).outcome).toBe("consumption request: duplicate");
    expect(deps.appStore.consumptionSent).toHaveLength(1);
  });

  test("a failed answer is retried by the sweeper inside Apple's 12-hour window, then given up", async () => {
    const tx = await buyApple("c4", {}, true);
    deps.appStore.consumptionFailures = ["retry", "retry"];
    expect((await notify("CONSUMPTION_REQUEST", tx)).outcome).toBe("consumption request: retry later");
    expect(deps.logs.find((l) => l.message === "apple_consumption_answer_failed")?.data).toMatchObject({ reason: "APPLE_RETRYABLE" });
    await sweep(deps);
    expect(deps.appStore.consumptionSent).toHaveLength(0);
    expect((await sweep(deps)).consumptionAnswers).toBe(1);
    expect(deps.appStore.consumptionSent).toHaveLength(1);

    const late = await buyApple("c4", {}, true);
    deps.appStore.consumptionFailures = ["retry"];
    await notify("CONSUMPTION_REQUEST", late);
    deps.clock.advance(CONSUMPTION_ANSWER_WINDOW_MS + 60_000);
    await sweep(deps);
    expect(deps.appStore.consumptionSent).toHaveLength(1);
    expect(deps.logs.some((l) => l.message === "apple_consumption_answer_expired")).toBe(true);
    expect((await purchase(applePurchaseId("production", late.transactionId)))?.consumptionPending).toBe(false);
  });
});

describe("refund after use", () => {
  test("a refund of unused credits never counts", async () => {
    const first = await buyApple("r1", { appTransactionId: "A-r1" });
    const second = await buyApple("r1", { appTransactionId: "A-r1" });
    await spend("r1", 10); // FIFO: all of the first purchase, none of the second
    await refundApple(second);
    expect(await balanceOf("r1")).toBe(0);
    expect(await buyer("apple_A-r1")).toBeUndefined();
    expect(deps.logs.some((l) => l.message === "refund_after_use")).toBe(false);
    expect((await purchase(applePurchaseId("production", second.transactionId)))?.refundAfterUse).toBe(false);
    void first;
  });

  test("a refund of spent credits counts once per purchase: duplicates, reversal and re-refund", async () => {
    const tx = await buyApple("r2", { appTransactionId: "A-r2" });
    await spend("r2", 4);
    await refundApple(tx);
    await refundApple(tx);
    expect(await buyer("apple_A-r2")).toMatchObject({ refundAfterUseCount: 1, blocked: false });
    expect(deps.logs.filter((l) => l.message === "refund_after_use")).toHaveLength(1);
    expect(deps.logs.find((l) => l.message === "refund_after_use")?.data).toMatchObject({ spentCreditsTaken: 4 });
    await notify("REFUND_REVERSED", tx);
    expect(await buyer("apple_A-r2")).toMatchObject({ refundAfterUseCount: 0 });
    await refundApple(tx);
    expect(await buyer("apple_A-r2")).toMatchObject({ refundAfterUseCount: 1 });
    expect(await balanceOf("r2")).toBe(-4);
  });

  test("Play partial refunds after use count once for the purchase", async () => {
    const token = await buyPlay("r3", 3); // 30 credits
    await spend("r3", 25);
    deps.play.purchases.get(token)!.refundableQuantity = 2;
    await handlePlayNotification(deps, rtdn(token, 2)); // 10 refunded, only 5 unused: 5 spent taken
    deps.play.purchases.get(token)!.refundableQuantity = 1;
    await handlePlayNotification(deps, rtdn(token, 2));
    expect(await buyer("uid_r3")).toMatchObject({ refundAfterUseCount: 1 });
    expect((await purchase(playPurchaseId(token)))?.refundAfterUse).toBe(true);
  });

  test("the threshold blocks the buyer: purchasesBlocked in every response with a balance; paid purchases are still granted", async () => {
    for (let i = 0; i < 2; i++) {
      const tx = await buyApple("r4", { appTransactionId: "A-r4" });
      await spend("r4", 10);
      await refundApple(tx);
    }
    expect(await buyer("apple_A-r4")).toMatchObject({ refundAfterUseCount: 2, blocked: true });
    expect(deps.logs.filter((l) => l.message === "buyer_blocked")).toHaveLength(1);
    expect(await getBalance(deps, callerFor("r4"))).toEqual({ balance: -20, costs: DEFAULT_COSTS, purchasesBlocked: true });

    const res = await verifyPurchase(deps, callerFor("r4"), {
      store: "appstore", signedTransaction: deps.appStore.sign(appleTx({ appTransactionId: "A-r4", productId: "recipes_50" })),
    });
    expect(res).toEqual({ status: "GRANTED", balance: 30, costs: DEFAULT_COSTS, purchasesBlocked: true });
    expect(deps.logs.filter((l) => l.message === "purchase_by_blocked_buyer")).toHaveLength(1);

    const req = {
      requestId: randomUUID(), kind: "recipe",
      config: { difficulty: "easy", detail: "brief", servings: 2, maxMinutes: null, mealType: "any", diet: "none", language: "en" },
      ingredients: [{ name: "Egg", expiresOn: null, quantity: 1 }], allergens: [], today: new Date(deps.now()).toISOString().slice(0, 10),
    };
    expect(await generateRecipe(deps, callerFor("r4"), req)).toMatchObject({ status: "DONE", purchasesBlocked: true });
    expect(await getGeneration(deps, callerFor("r4"), { requestId: req.requestId })).toMatchObject({ purchasesBlocked: true });
  });

  test("a new anonymous install is recognised by its first purchase with the same Apple buyer", async () => {
    await db.collection("buyers").doc("apple_A-r5").set({ events: {}, refundAfterUseCount: 2, blocked: true, createdAt: new Date() });
    expect(await getBalance(deps, callerFor("fresh-install"))).toEqual({ balance: 0, costs: DEFAULT_COSTS });
    await buyApple("fresh-install", { appTransactionId: "A-r5" });
    expect(await getBalance(deps, callerFor("fresh-install"))).toMatchObject({ purchasesBlocked: true });
  });

  test("the block follows a merge", async () => {
    const anon = await anonymousUser();
    const signed = await passwordUser();
    await buyPlay(anon.uid);
    await db.collection("buyers").doc(`uid_${anon.uid}`).set({ events: {}, refundAfterUseCount: 2, blocked: true, createdAt: new Date() });
    await db.collection("users").doc(signed.uid).set({ ...emptyUser() });
    const res = await mergeBalance(deps, callerFor(signed.uid), { sourceIdToken: anon.idToken });
    expect(res).toMatchObject({ moved: 10, purchasesBlocked: true });
    expect(await getBalance(deps, callerFor(signed.uid))).toMatchObject({ purchasesBlocked: true });
  });

  test("the owner's override is respected both ways", async () => {
    for (let i = 0; i < 2; i++) {
      const tx = await buyApple("r6", { appTransactionId: "A-r6" });
      await spend("r6", 10);
      await refundApple(tx);
    }
    await db.collection("buyers").doc("apple_A-r6").update({ blocked: false, blockedOverride: true });
    expect(await getBalance(deps, callerFor("r6"))).toEqual({ balance: -20, costs: DEFAULT_COSTS });
    // A further event does not undo the owner's decision.
    const tx = await buyApple("r6", { appTransactionId: "A-r6" });
    await spend("r6", 10);
    await refundApple(tx);
    expect(await buyer("apple_A-r6")).toMatchObject({ refundAfterUseCount: 3, blocked: false });
    expect((await getBalance(deps, callerFor("r6"))).purchasesBlocked).toBeUndefined();
    // And a forced block holds below the threshold.
    await db.collection("buyers").doc("apple_A-r6").update({ blocked: true, refundAfterUseCount: 0 });
    expect(await getBalance(deps, callerFor("r6"))).toMatchObject({ purchasesBlocked: true });
  });

  test("refundAbuseThreshold comes from config/runtime, fail-closed", async () => {
    await db.doc("config/runtime").set({ refundAbuseThreshold: 1 });
    const tx = await buyApple("r7", { appTransactionId: "A-r7" });
    await spend("r7", 1);
    await refundApple(tx);
    expect(await getBalance(deps, callerFor("r7"))).toMatchObject({ purchasesBlocked: true });
    await db.doc("config/runtime").set({ refundAbuseThreshold: 0 });
    // Invalid: the default (2) applies.
    expect((await getBalance(deps, callerFor("r7"))).purchasesBlocked).toBeUndefined();
  });
});

describe("debt follows the buyer", () => {
  test("a negative balance left on an abandoned install moves to the buyer's next purchase, once", async () => {
    await db.collection("users").doc("old-anon").set({ ...emptyUser(), balance: -7, buyerKeys: ["apple_A-d1"] });
    const res = await verifyPurchase(deps, callerFor("new-account"), {
      store: "appstore", signedTransaction: deps.appStore.sign(appleTx({ appTransactionId: "A-d1" })),
    });
    expect(res).toMatchObject({ status: "GRANTED", balance: 3 });
    expect((await db.collection("users").doc("old-anon").get()).data()).toMatchObject({ balance: 0, debtMovedTo: "new-account" });
    expect(deps.logs.find((l) => l.message === "debt_followed_buyer")?.data).toMatchObject({ amount: -7 });
    await verifyPurchase(deps, callerFor("new-account"), {
      store: "appstore", signedTransaction: deps.appStore.sign(appleTx({ appTransactionId: "A-d1" })),
    });
    expect(await balanceOf("new-account")).toBe(13);
  });
});

describe("consent withdrawal and deleted accounts", () => {
  test("consent can be withdrawn by the redeemer only, and a withdrawn purchase is not answered", async () => {
    const tx = appleTx();
    const jws = deps.appStore.sign(tx);
    await verifyPurchase(deps, callerFor("w1"), { store: "appstore", signedTransaction: jws, consumptionConsent: true });
    // Another account cannot change it.
    await verifyPurchase(deps, callerFor("w2"), { store: "appstore", signedTransaction: jws, consumptionConsent: false });
    expect((await purchase(applePurchaseId("production", tx.transactionId)))?.consumptionConsent).toBe(true);
    await verifyPurchase(deps, callerFor("w1"), { store: "appstore", signedTransaction: jws, consumptionConsent: false });
    expect((await purchase(applePurchaseId("production", tx.transactionId)))?.consumptionConsent).toBe(false);
    expect((await notify("CONSUMPTION_REQUEST", tx)).outcome).toBe("consumption request: not consented");
    expect(deps.appStore.consumptionSent).toEqual([]);
  });

  test("consent withdrawn while an answer is owed stops the sweeper's retry", async () => {
    const tx = appleTx();
    const jws = deps.appStore.sign(tx);
    await verifyPurchase(deps, callerFor("w3"), { store: "appstore", signedTransaction: jws, consumptionConsent: true });
    deps.appStore.consumptionFailures = ["retry"];
    expect((await notify("CONSUMPTION_REQUEST", tx)).outcome).toBe("consumption request: retry later");
    await verifyPurchase(deps, callerFor("w3"), { store: "appstore", signedTransaction: jws, consumptionConsent: false });
    await sweep(deps);
    expect(deps.appStore.consumptionSent).toEqual([]);
    expect(await purchase(applePurchaseId("production", tx.transactionId)))
      .toMatchObject({ consumptionPending: false, consumption: { lastError: "CONSENT_WITHDRAWN" } });
  });

  test("deleting the account before a refund does not escape refund-after-use or the consumption answer", async () => {
    const tx = await buyApple("d1", { appTransactionId: "A-del" }, true);
    await spend("d1", 10);
    await deleteAccount(deps, callerFor("d1", "anonymous"), {});
    expect((await purchase(applePurchaseId("production", tx.transactionId)))).toMatchObject({ uid: null, formerUid: "d1" });
    expect((await notify("CONSUMPTION_REQUEST", tx)).outcome).toBe("consumption request: answered");
    expect(deps.appStore.consumptionSent[0].info.consumptionPercentage).toBe(100_000);
    await refundApple(tx);
    expect(await buyer("apple_A-del")).toMatchObject({ refundAfterUseCount: 1 });
    // The deleted account's balance is not touched.
    expect(await balanceOf("d1")).toBe(0);
  });
});
