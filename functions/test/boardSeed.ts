/**
 * Realistic seeded data for the owner's board: 30 days of `stats/{day}`, purchases from both
 * stores (refunds, sandbox, a deleted account's purchase with `uid: null`), balances and the
 * ledger, all with identifiers the board must never show. Deterministic (fixed PRNG seed).
 */
import { Timestamp, type Firestore } from "firebase-admin/firestore";
import { PRODUCT_CREDITS } from "../src/constants";
import { utcDay } from "../src/model";

const DAY = 86_400_000;

/** mulberry32 */
function prng(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export interface Seeded {
  /** Every uid, token, order id and transaction id written: none may appear on the board. */
  identifiers: string[];
  /** Production purchases granted in the last 30 days (today included). */
  productionPurchases: number;
  sandboxPurchases: number;
  statsDays: number;
}

export async function seedBoardData(db: Firestore, nowMs: number, days = 30): Promise<Seeded> {
  const rnd = prng(20261115);
  const today = Math.floor(nowMs / DAY) * DAY;
  const identifiers: string[] = [];
  const writer = db.bulkWriter();
  let statsDays = 0;
  let productionPurchases = 0;
  let sandboxPurchases = 0;
  // Net production recipes granted per day, so stats agree with the purchases written.
  const net = new Map<string, number>();
  const sandbox = new Map<string, { grants: number; credits: number }>();

  const packs = ["recipes_10", "recipes_10", "recipes_10", "recipes_50", "recipes_50", "recipes_100"];
  // Purchases over the period and the week before it (the ceiling's revenue window).
  for (let i = days + 6; i >= 0; i--) {
    const dayStart = today - i * DAY;
    const growth = 1 - i / (days + 10);
    const n = Math.floor(rnd() * 2.6 * growth * growth + (i % 9 === 1 ? 1 : 0));
    for (let k = 0; k < n; k++) {
      const at = dayStart + Math.floor(rnd() * (i === 0 ? Math.max(1, nowMs - today) : DAY));
      const productId = packs[Math.floor(rnd() * packs.length)];
      const credits = PRODUCT_CREDITS[productId];
      const store = rnd() < 0.55 ? "appstore" : "play";
      const isSandbox = rnd() < 0.12;
      const refunded = !isSandbox && rnd() < 0.08 ? (rnd() < 0.5 ? credits : Math.floor(credits / 2)) : 0;
      const deleted = !isSandbox && rnd() < 0.1;
      const uid = `uid-${i}-${k}-${Math.floor(rnd() * 1e9).toString(36)}`;
      const token = `token-${uid}-secretpurchasetoken`;
      const orderId = `GPA.${3300 + i}-${1000 + k}-${Math.floor(rnd() * 1e4)}-${Math.floor(rnd() * 1e5)}`;
      const transactionId = `${2000000000000000 + i * 100 + k}`;
      const env = isSandbox ? "sandbox" : "production";
      const id = store === "play" ? `play_${token.slice(0, 20)}` : `appstore_${env}_${transactionId}`;
      identifiers.push(uid, token, orderId, transactionId, id);
      writer.set(db.collection("purchases").doc(id), {
        store,
        uid: deleted ? null : uid,
        ...(deleted ? { formerUid: uid } : {}),
        env,
        productId,
        quantity: 1,
        credits,
        refundedCredits: refunded,
        state: refunded >= credits ? "refunded" : store === "play" ? "consumed" : "granted",
        grantedAt: Timestamp.fromMillis(at),
        ...(refunded ? { refundEventAt: at + DAY } : {}),
        buyerKeys: [uid],
        accountToken: `acct-${uid}`,
        ...(store === "play" ? { orderId, purchaseToken: token } : { transactionId }),
      });
      const day = utcDay(at);
      if (isSandbox) {
        const s = sandbox.get(day) ?? { grants: 0, credits: 0 };
        sandbox.set(day, { grants: s.grants + 1, credits: s.credits + credits });
        if (i < days) sandboxPurchases++;
      } else {
        net.set(day, (net.get(day) ?? 0) + credits - refunded);
        if (i < days) productionPurchases++;
      }
    }
  }
  // A purchase refunded before it was ever redeemed: no grantedAt, never on the board.
  writer.set(db.collection("purchases").doc("play_refunded-before-grant"), {
    store: "play", uid: null, env: "production", productId: "recipes_50", quantity: 1, credits: 50,
    refundedCredits: 50, state: "refunded", grantedAt: null, orderId: "GPA.9999-0000-0000-00000",
  });
  identifiers.push("GPA.9999-0000-0000-00000", "play_refunded-before-grant");

  for (let i = days + 6; i >= 0; i--) {
    // Two days with no stats doc at all (nothing happened): the board reads them as zero.
    if (i === 12 || i === days + 3) continue;
    const day = utcDay(today - i * DAY);
    const growth = 1 - i / (days + 10);
    const recipes = Math.floor((6 + rnd() * 30) * growth) + (i === 4 ? 45 : 0);
    const menus = Math.floor(rnd() * 5 * growth);
    const content = rnd() < 0.4 ? 1 + Math.floor(rnd() * 2) : 0;
    const infra = rnd() < 0.2 ? 1 : 0;
    const quality = rnd() < 0.25 ? 1 : 0;
    const unknown = infra && rnd() < 0.5 ? 1 : 0;
    const succeeded = recipes + menus;
    const calls = succeeded + content + infra + quality;
    const inputTokens = calls * (2600 + Math.floor(rnd() * 600));
    const outputTokens = recipes * 1900 + menus * 11000;
    const thinkingTokens = recipes * 400 + menus * 2000;
    const spent = Math.ceil(inputTokens * 1.5 + (outputTokens + thinkingTokens) * 7.5) + unknown * 82_000;
    const s = sandbox.get(day);
    writer.set(db.collection("stats").doc(day.replace(/-/g, "")), {
      spentMicroUsd: spent,
      reservedMicroUsd: i === 0 ? 82_000 : 0,
      generations: calls,
      generationUnits: recipes + menus * 4 + content + infra + quality,
      llmCalls: calls,
      inputTokens,
      outputTokens,
      thinkingTokens,
      succeeded,
      contentFailures: content,
      infraFailures: infra,
      qualityFailures: quality,
      refundedCredits: infra + quality + (content && rnd() < 0.5 ? 1 : 0),
      unknownUsageCalls: unknown,
      netCreditsGranted: net.get(day) ?? 0,
      ...(s ? { sandboxGrants: s.grants, sandboxCredits: s.credits } : {}),
    });
    if (i < days) statsDays++;
  }
  writer.set(db.doc("stats/ledger"), { negativeBalanceTotal: -7, checkedAt: Timestamp.fromMillis(today + 3 * 3_600_000) });

  for (let u = 0; u < 60; u++) {
    const uid = `holder-${u}-${Math.floor(rnd() * 1e9).toString(36)}`;
    identifiers.push(uid);
    const balance = u < 48 ? 1 + Math.floor(rnd() * 60) : u < 55 ? 0 : -1 - Math.floor(rnd() * 3);
    writer.set(db.collection("users").doc(uid), {
      balance, inFlight: 0, purchasedCredits: 100, consumedCredits: 100 - balance, refundedCredits: 0,
      contentRefundsUsed: 0, timeoutRefundsUsed: 0, buyerKeys: [uid],
    });
  }
  writer.set(db.doc("config/runtime"), { generationEnabled: true, allowSandbox: true });
  await writer.close();
  return { identifiers, productionPurchases, sandboxPurchases, statsDays };
}
