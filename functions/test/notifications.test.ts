import { deleteAccount } from "../src/core/account";
import {
  handleAppleNotification,
  handleBudgetAlert,
  handlePlayNotification,
  pollVoidedPurchases,
} from "../src/core/notifications";
import { applePurchaseId, playPurchaseId } from "../src/core/purchases";
import { emptyUser } from "../src/model";
import { appleTx, balanceOf, callerFor, db, makeDeps, playToken, resetEmulators, verifyPurchase, type TestDeps } from "./helpers";

const alice = callerFor("alice");
const purchase = async (id: string) => (await db.collection("purchases").doc(id).get()).data();

let deps: TestDeps;
beforeEach(async () => {
  await resetEmulators();
  deps = makeDeps();
});

function rtdn(token: string, refundType = 1, eventTimeMillis = Date.now(), packageName = "com.foodtracker.android") {
  return {
    version: "1.0",
    packageName,
    eventTimeMillis: String(eventTimeMillis),
    voidedPurchaseNotification: { purchaseToken: token, orderId: "GPA.1", productType: 2, refundType },
  };
}

async function buyPlay(quantity = 1, productId = "recipes_10") {
  const token = deps.play.add(playToken(), { productId, quantity });
  await verifyPurchase(deps, alice, { store: "play", purchaseToken: token });
  return token;
}

describe("Play voided purchases", () => {
  test("a full refund takes the credits back once, even if the balance goes negative", async () => {
    const token = await buyPlay();
    await db.collection("users").doc("alice").update({ balance: 3 }); // 7 already spent
    expect(await handlePlayNotification(deps, rtdn(token))).toBe("applied");
    expect(await balanceOf("alice")).toBe(-7);
    expect(await handlePlayNotification(deps, rtdn(token))).toBe("duplicate");
    expect(await balanceOf("alice")).toBe(-7);
    expect(await purchase(playPurchaseId(token))).toMatchObject({ state: "refunded", refundedCredits: 10 });
  });

  test("a quantity-based partial refund takes back only the refunded units", async () => {
    const token = await buyPlay(3);
    expect(await balanceOf("alice")).toBe(30);
    deps.play.purchases.get(token)!.refundableQuantity = 2;
    await handlePlayNotification(deps, rtdn(token, 2, Date.now() + 1));
    expect(await balanceOf("alice")).toBe(20);
    deps.play.purchases.get(token)!.refundableQuantity = 0;
    await handlePlayNotification(deps, rtdn(token, 1, Date.now() + 2));
    expect(await balanceOf("alice")).toBe(0);
  });

  test("a partial refund Play can no longer look up is not applied as a full one", async () => {
    const token = await buyPlay(3);
    deps.play.purchases.delete(token);
    expect(await handlePlayNotification(deps, rtdn(token, 2))).toBe("skipped");
    expect(await balanceOf("alice")).toBe(30);
  });

  test("a refund for a purchase never submitted blocks its later redemption", async () => {
    const token = deps.play.add(playToken());
    expect(await handlePlayNotification(deps, rtdn(token))).toBe("created");
    expect(await purchase(playPurchaseId(token))).toMatchObject({ uid: null, state: "refunded", credits: 10 });
    expect((await verifyPurchase(deps, alice, { store: "play", purchaseToken: token })).status).toBe("REJECTED_PERMANENT");
    expect(await balanceOf("alice")).toBe(0);
  });

  test("notifications for another package, subscriptions or tests change nothing", async () => {
    const token = await buyPlay();
    expect(await handlePlayNotification(deps, rtdn(token, 1, Date.now(), "com.evil.app"))).toBe("ignored: other package");
    expect(await handlePlayNotification(deps, { ...rtdn(token), voidedPurchaseNotification: { purchaseToken: token, productType: 1, refundType: 1 } }))
      .toBe("ignored: not a one-time product");
    expect(await handlePlayNotification(deps, { packageName: "com.foodtracker.android", testNotification: { version: "1.0" } })).toBe("test");
    expect(await handlePlayNotification(deps, "garbage")).toBe("ignored: not an object");
    expect(await balanceOf("alice")).toBe(10);
  });

  test("the daily poll catches a lost notification and agrees with a delivered one", async () => {
    const lost = await buyPlay();
    const delivered = await buyPlay();
    await handlePlayNotification(deps, rtdn(delivered));
    deps.play.voided = [
      { purchaseToken: lost, orderId: null, voidedTimeMillis: Date.now() },
      { purchaseToken: delivered, orderId: null, voidedTimeMillis: Date.now() - 1000 },
    ];
    expect(await pollVoidedPurchases(deps)).toEqual({ seen: 2, applied: 1 });
    expect(await balanceOf("alice")).toBe(0);
    expect(await pollVoidedPurchases(deps)).toEqual({ seen: 2, applied: 0 });
    expect(await balanceOf("alice")).toBe(0);
  });

  test("a refund after the account was deleted changes no balance", async () => {
    const token = await buyPlay();
    await deleteAccount(deps, alice, {});
    expect(await handlePlayNotification(deps, rtdn(token))).toBe("applied");
    expect(await balanceOf("alice")).toBe(0);
  });

  test("a refund of a purchase merged into another account debits that account", async () => {
    const token = await buyPlay();
    await db.collection("users").doc("alice").update({ balance: 0, mergedInto: "carol" });
    await db.collection("users").doc("carol").set({ ...emptyUser(), balance: 10 });
    await handlePlayNotification(deps, rtdn(token));
    expect(await balanceOf("carol")).toBe(0);
    expect(await balanceOf("alice")).toBe(0);
  });
});

describe("App Store Server Notifications", () => {
  async function buyApple(quantity = 1) {
    const tx = appleTx({ quantity });
    await verifyPurchase(deps, alice, { store: "appstore", signedTransaction: deps.appStore.sign(tx) });
    return tx;
  }

  test("REFUND takes the credits back once; a retried delivery changes nothing", async () => {
    const tx = await buyApple();
    const payload = deps.appStore.signNotification("REFUND", { ...tx, revocationDate: Date.now() });
    expect(await handleAppleNotification(deps, { signedPayload: payload })).toEqual({ httpStatus: 200, outcome: "refund applied" });
    expect(await handleAppleNotification(deps, { signedPayload: payload })).toEqual({ httpStatus: 200, outcome: "refund duplicate" });
    expect(await balanceOf("alice")).toBe(0);
  });

  test("REFUND_REVERSED restores the credits; an older REFUND arriving late is ignored", async () => {
    const tx = await buyApple();
    const t0 = Date.now();
    const refund = deps.appStore.signNotification("REFUND", { ...tx, revocationDate: t0 }, t0);
    await handleAppleNotification(deps, { signedPayload: refund });
    expect(await balanceOf("alice")).toBe(0);
    const reversal = deps.appStore.signNotification("REFUND_REVERSED", tx, t0 + 1000);
    expect((await handleAppleNotification(deps, { signedPayload: reversal })).outcome).toBe("reversal applied");
    expect(await balanceOf("alice")).toBe(10);
    expect((await purchase(applePurchaseId("production", tx.transactionId)))?.state).toBe("granted");
    expect((await handleAppleNotification(deps, { signedPayload: refund })).outcome).toBe("refund stale");
    expect(await balanceOf("alice")).toBe(10);
  });

  test("a prorated refund takes back its share, rounded up", async () => {
    const tx = await buyApple(2); // 20 credits
    const payload = deps.appStore.signNotification("REFUND", { ...tx, revocationDate: Date.now(), revocationPercentage: 33_000 });
    await handleAppleNotification(deps, { signedPayload: payload });
    expect(await balanceOf("alice")).toBe(13);
  });

  test("a forged notification is answered 400 and changes nothing", async () => {
    await buyApple();
    expect(await handleAppleNotification(deps, { signedPayload: "eyJh.eyJh.Zm9yZ2Vk" })).toEqual({ httpStatus: 400, outcome: "INVALID_SIGNATURE" });
    expect(await handleAppleNotification(deps, {})).toEqual({ httpStatus: 400, outcome: "MISSING_PAYLOAD" });
    expect(await balanceOf("alice")).toBe(10);
  });

  test("a refund for a never-submitted transaction blocks it; a reversal makes it redeemable again", async () => {
    const tx = appleTx();
    const jws = deps.appStore.sign(tx);
    const t0 = Date.now();
    await handleAppleNotification(deps, { signedPayload: deps.appStore.signNotification("REFUND", { ...tx, revocationDate: t0 }, t0) });
    expect((await verifyPurchase(deps, alice, { store: "appstore", signedTransaction: jws })).status).toBe("REJECTED_PERMANENT");
    await handleAppleNotification(deps, { signedPayload: deps.appStore.signNotification("REFUND_REVERSED", tx, t0 + 1) });
    expect((await verifyPurchase(deps, alice, { store: "appstore", signedTransaction: jws })).status).toBe("GRANTED");
    expect(await balanceOf("alice")).toBe(10);
  });

  test("CONSUMPTION_REQUEST without consent is recorded and not answered; TEST and other types are acknowledged", async () => {
    const tx = await buyApple();
    expect((await handleAppleNotification(deps, { signedPayload: deps.appStore.signNotification("CONSUMPTION_REQUEST", tx) })).outcome)
      .toBe("consumption request: not consented");
    expect(deps.appStore.consumptionSent).toEqual([]);
    expect(deps.logs.find((l) => l.message === "apple_consumption_request")?.data).toMatchObject({ outcome: "not consented" });
    expect((await purchase(applePurchaseId("production", tx.transactionId)))?.consumptionRequestedAt).toBeDefined();
    expect((await handleAppleNotification(deps, { signedPayload: deps.appStore.signNotification("TEST", null) })).outcome).toBe("test");
    expect((await handleAppleNotification(deps, { signedPayload: deps.appStore.signNotification("REFUND_DECLINED", tx) })).outcome)
      .toBe("ignored: REFUND_DECLINED");
    expect(await balanceOf("alice")).toBe(10);
  });
});

describe("budget kill switch", () => {
  test("flips generationEnabled off at the threshold and only then", async () => {
    expect(await handleBudgetAlert(deps, { costAmount: 50, budgetAmount: 100 }, 1)).toBe(false);
    expect((await db.doc("config/runtime").get()).exists).toBe(false);
    expect(await handleBudgetAlert(deps, { costAmount: 100, budgetAmount: 100, budgetDisplayName: "recipes" }, 1)).toBe(true);
    expect((await db.doc("config/runtime").get()).data()).toMatchObject({ generationEnabled: false });
    expect(await handleBudgetAlert(deps, "junk", 1)).toBe(false);
  });
});
