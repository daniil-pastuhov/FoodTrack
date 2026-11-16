/**
 * The welcome grant: free recipes once per sign-in identity (claimWelcome), the offer
 * (getWelcomeOffer, `welcomeRecipes`), the day's brake, and that free recipes are never treated
 * as purchases (refund allowance, FIFO ledger, Apple consumption answers, refund after use).
 * Each abuse case of README "Welcome grant" has a test here.
 */
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { HttpsError } from "firebase-functions/v2/https";
import { deleteAccount, getBalance, mergeBalance } from "../src/core/account";
import { generateRecipe } from "../src/core/generation";
import { handleAppleNotification } from "../src/core/notifications";
import { applePurchaseId } from "../src/core/purchases";
import { CallThrottle } from "../src/core/guard";
import { providerBuyerKey } from "../src/core/buyers";
import { WelcomeDayCounter, claimWelcome, getWelcomeOffer, welcomeKey } from "../src/core/welcome";
import { DEFAULT_COSTS } from "../src/constants";
import { CAPABILITIES } from "../src/contract";
import {
  DEFAULT_RUNTIME_CONFIG,
  WELCOME_OFF,
  emptyUser,
  paidAllowance,
  parseRuntimeConfig,
  statsRef,
  utcDay,
  validWelcome,
  type UserDoc,
} from "../src/model";
import type { AppleTransaction, Caller } from "../src/ports";
import { tasteQuizVersion } from "../src/taste/quiz";
import {
  anonymousUser,
  appleTx,
  auth,
  balanceOf,
  callerFor,
  db,
  makeDeps,
  playToken,
  resetEmulators,
  setConfig,
  verifyPurchase,
  type TestDeps,
} from "./helpers";

/** A fixed server time, noon UTC, so nothing here depends on the day the suite runs. */
const NOW = Date.parse("2026-06-15T12:00:00Z");
const DAY_MS = 86_400_000;
const TQV = tasteQuizVersion([]);

let deps: TestDeps;
beforeEach(async () => {
  await resetEmulators();
  deps = makeDeps();
  deps.clock.ms = NOW;
});

async function reason(p: Promise<unknown>): Promise<string> {
  try {
    await p;
  } catch (e) {
    if (e instanceof HttpsError) return (e.details as { reason?: string } | undefined)?.reason ?? e.code;
    throw e;
  }
  return "no error";
}

/** An Auth user with the given identities linked (provider -> the provider's own user id). */
async function account(ids: Record<string, string>): Promise<string> {
  const u = await auth.createUser({});
  for (const [providerId, uid] of Object.entries(ids)) await auth.updateUser(u.uid, { providerToLink: { providerId, uid } });
  return u.uid;
}

/** As the ID token says, signed in with `provider` just now (by the test clock). */
function signedIn(uid: string, provider: string, providers: string[] = [provider]): Caller {
  return { ...callerFor(uid, provider, Math.floor(NOW / 1000)), providers };
}

const google = (uid: string) => signedIn(uid, "google.com");
const userDoc = async (uid: string) => (await db.collection("users").doc(uid).get()).data() as UserDoc | undefined;
const welcomeDoc = async (key: string) => (await db.collection("welcomes").doc(key).get()).data();
const welcomeCount = async () => (await db.collection("welcomes").get()).size;
const stats = async (day = utcDay(NOW)) => (await statsRef(db, day).get()).data() ?? {};

const FLAGS = { ...CAPABILITIES, tasteQuizVersion: TQV };

function request(over: Record<string, unknown> = {}) {
  return {
    requestId: randomUUID(),
    kind: "recipe",
    config: { difficulty: "easy", detail: "brief", servings: 1, maxMinutes: null, mealType: "any", diet: "none", language: "en" },
    ingredients: [{ name: "eggs", expiresOn: null, quantity: 1 }],
    allergens: [],
    today: utcDay(NOW),
    ...over,
  };
}

describe("config/runtime.welcome", () => {
  test("defaults to 3 recipes and 200 grants a day; each field is optional", () => {
    expect(DEFAULT_RUNTIME_CONFIG.welcome).toEqual({ recipes: 3, perDay: 200 });
    expect(parseRuntimeConfig({}).welcome).toEqual({ recipes: 3, perDay: 200 });
    expect(parseRuntimeConfig({ welcome: { recipes: 5 } }).welcome).toEqual({ recipes: 5, perDay: 200 });
    expect(parseRuntimeConfig({ welcome: { perDay: 0 } }).welcome).toEqual({ recipes: 3, perDay: 0 });
    expect(validWelcome({ recipes: 0, perDay: 10_000 })).toEqual({ recipes: 0, perDay: 10_000 });
    expect(validWelcome({ recipes: 20, perDay: 1 })).toEqual({ recipes: 20, perDay: 1 });
  });

  test("anything invalid turns the grant off as a whole (never fails open) and is named in the warning", () => {
    for (const bad of [null, [], "3", { recipes: 21 }, { recipes: -1 }, { recipes: 1.5 }, { recipes: "3" }, { perDay: 10_001 }, { perDay: -1 }, { perDay: 2.5 }]) {
      expect(validWelcome(bad)).toBeNull();
      const warnings: unknown[] = [];
      const config = parseRuntimeConfig({ welcome: bad }, { info() {}, warn: (_m, d) => warnings.push(d), error() {} });
      expect(config.welcome).toEqual(WELCOME_OFF);
      expect(warnings).toEqual([{ fields: ["welcome"] }]);
    }
  });
});

describe("getWelcomeOffer and welcomeRecipes", () => {
  test("say the current amount, and 0 when off, paused or at the day's limit; they store nothing", async () => {
    expect(await getWelcomeOffer(deps)).toEqual({ recipes: 3 });
    await setConfig({ welcome: { recipes: 5 } });
    expect(await getWelcomeOffer(deps)).toEqual({ recipes: 5 });
    await setConfig({ welcome: { recipes: 0 } });
    expect(await getWelcomeOffer(deps)).toEqual({ recipes: 0 });
    await setConfig({ welcome: { recipes: 3, perDay: 0 } });
    expect(await getWelcomeOffer(deps)).toEqual({ recipes: 0 });
    await setConfig({ welcome: { recipes: 3, perDay: 2 }, generationEnabled: false });
    expect(await getWelcomeOffer(deps)).toEqual({ recipes: 0 });
    await setConfig({ generationEnabled: true });
    await statsRef(db, utcDay(NOW)).set({ welcomeGrants: 2 });
    expect(await getWelcomeOffer(deps)).toEqual({ recipes: 0 });
    expect((await getBalance(deps, callerFor("nobody"))).welcomeRecipes).toBe(0);
    // The next UTC day has its own count.
    deps.clock.advance(DAY_MS);
    expect(await getWelcomeOffer(deps)).toEqual({ recipes: 3 });
    // Nothing about any caller was written.
    expect((await db.collection("users").get()).size).toBe(0);
    expect(await welcomeCount()).toBe(0);
  });

  test("welcomeRecipes is beside every balance, the same for every account", async () => {
    expect(await getBalance(deps, callerFor("nobody"))).toEqual({ balance: 0, costs: DEFAULT_COSTS, ...FLAGS, welcomeRecipes: 3 });
    const uid = await account({ "google.com": "g-flag" });
    await claimWelcome(deps, google(uid));
    expect((await getBalance(deps, google(uid))).welcomeRecipes).toBe(3);
    const token = deps.play.add(playToken());
    expect((await verifyPurchase(deps, google(uid), { store: "play", purchaseToken: token })).welcomeRecipes).toBe(3);
    expect((await generateRecipe(deps, google(uid), request())).welcomeRecipes).toBe(3);
  });

  test("the per-instance day counter reads at most once per 30 s and per day, and never caches a failure", async () => {
    let reads = 0;
    let value = 1;
    let fail = false;
    const counter = new WelcomeDayCounter(async () => {
      reads++;
      if (fail) throw new Error("firestore down");
      return value;
    }, deps.now);
    expect(await counter.grants("2026-06-15")).toBe(1);
    value = 7;
    expect(await counter.grants("2026-06-15")).toBe(1);
    expect(reads).toBe(1);
    deps.clock.advance(30_000);
    expect(await counter.grants("2026-06-15")).toBe(7);
    expect(await counter.grants("2026-06-16")).toBe(7);
    expect(reads).toBe(3);
    fail = true;
    await expect(counter.grants("2026-06-17")).rejects.toThrow("firestore down");
    fail = false;
    expect(await counter.grants("2026-06-17")).toBe(7);
  });

  test("flooding the offer without sign-in: the address and the instance ceiling bound it", () => {
    const t = new CallThrottle(120);
    for (let i = 0; i < 120; i++) t.admitAddress("203.0.113.7", NOW);
    expect(() => t.admitAddress("203.0.113.7", NOW)).toThrow(HttpsError);
    // Rotating addresses does not get past the instance ceiling.
    const all = new CallThrottle(5);
    for (let i = 0; i < 5; i++) all.admitAddress(`198.51.100.${i}`, NOW);
    expect(() => all.admitAddress("198.51.100.99", NOW)).toThrow(HttpsError);
    // A new minute lets calls through again.
    expect(() => all.admitAddress("198.51.100.99", NOW + 60_000)).not.toThrow();
  });
});

describe("claimWelcome", () => {
  test("grants once to a Google sign-in, in one transaction with its welcomes/ document", async () => {
    const uid = await account({ "google.com": "g-1" });
    const res = await claimWelcome(deps, google(uid));
    expect(res).toEqual({ status: "GRANTED", granted: 3, balance: 3, costs: DEFAULT_COSTS, ...FLAGS, welcomeRecipes: 3 });
    expect(await userDoc(uid)).toMatchObject({ balance: 3, welcomeCredits: 3, purchasedCredits: 0 });
    const doc = await welcomeDoc(welcomeKey("google.com", "g-1"));
    // The key, the uid at the time, the amount, the time; no email, name or token.
    expect(Object.keys(doc!).sort()).toEqual(["at", "credits", "provider", "uid"]);
    expect(doc).toMatchObject({ uid, provider: "google.com", credits: 3 });
    expect(doc!.at.toMillis()).toBe(NOW);
    // The same derivation as a linked sign-in's buyer key.
    expect(welcomeKey("google.com", "g-1")).toBe(providerBuyerKey("google.com", "g-1"));
    // Not revenue: the spend ceiling's netCreditsGranted is untouched.
    expect(await stats()).toEqual({ welcomeGrants: 1, welcomeCredits: 3 });
    const line = deps.logs.find((l) => l.message === "welcome_granted");
    expect(line).toMatchObject({ level: "info", data: { amount: 3, identities: 1 } });
    expect(JSON.stringify(line)).not.toContain(uid);
  });

  test("twice with one identity: ALREADY with the current balance, nothing more", async () => {
    const uid = await account({ "google.com": "g-2" });
    await claimWelcome(deps, google(uid));
    await db.collection("users").doc(uid).update({ balance: 1 });
    expect(await claimWelcome(deps, google(uid))).toMatchObject({ status: "ALREADY", granted: 0, balance: 1 });
    expect(await userDoc(uid)).toMatchObject({ balance: 1, welcomeCredits: 3 });
    expect(await stats()).toMatchObject({ welcomeGrants: 1 });
  });

  test("an anonymous account, or one with no Google or Apple identity, is refused with nothing written", async () => {
    const anon = await anonymousUser();
    expect(await reason(claimWelcome(deps, callerFor(anon.uid, "anonymous")))).toBe("SIGN_IN_REQUIRED");
    const pw = await auth.createUser({ email: `${randomUUID()}@example.com`, password: "secret-password" });
    expect(await reason(claimWelcome(deps, callerFor(pw.uid, "password")))).toBe("SIGN_IN_REQUIRED");
    // A token that claims Google for a user Auth does not know.
    expect(await reason(claimWelcome(deps, google("ghost")))).toBe("SIGN_IN_REQUIRED");
    expect((await db.collection("users").get()).size).toBe(0);
    expect(await welcomeCount()).toBe(0);
  });

  test("the Auth record decides, not a stale ID token: a token naming Google for a user without it is refused", async () => {
    const anon = await anonymousUser();
    expect(await reason(claimWelcome(deps, signedIn(anon.uid, "anonymous", ["google.com"])))).toBe("SIGN_IN_REQUIRED");
  });

  test("Google then Apple on one account: one grant; the later identity is recorded and cannot claim elsewhere", async () => {
    const a = await account({ "google.com": "g-3" });
    expect((await claimWelcome(deps, google(a))).status).toBe("GRANTED");
    await auth.updateUser(a, { providerToLink: { providerId: "apple.com", uid: "a-3" } });
    expect(await claimWelcome(deps, signedIn(a, "apple.com", ["google.com", "apple.com"]))).toMatchObject({ status: "ALREADY", balance: 3 });
    expect(await welcomeDoc(welcomeKey("apple.com", "a-3"))).toMatchObject({ uid: a, provider: "apple.com", credits: 0 });
    // Apple is moved to a new account: it has had its free recipes.
    await auth.updateUser(a, { providersToUnlink: ["apple.com"] });
    const b = await account({ "apple.com": "a-3" });
    expect(await claimWelcome(deps, signedIn(b, "apple.com"))).toMatchObject({ status: "ALREADY", granted: 0, balance: 0 });
    expect(await userDoc(a)).toMatchObject({ welcomeCredits: 3 });
    expect(await stats()).toMatchObject({ welcomeGrants: 1 });
  });

  test("an account linked to both from the start claims once, and neither identity claims again", async () => {
    const a = await account({ "google.com": "g-4", "apple.com": "a-4" });
    expect(await claimWelcome(deps, signedIn(a, "apple.com", ["google.com", "apple.com"]))).toMatchObject({ status: "GRANTED", granted: 3 });
    expect(await welcomeCount()).toBe(2);
    expect(deps.logs.find((l) => l.message === "welcome_granted")?.data).toMatchObject({ identities: 2 });
    await auth.updateUser(a, { providersToUnlink: ["google.com"] });
    const b = await account({ "google.com": "g-4" });
    expect((await claimWelcome(deps, google(b))).status).toBe("ALREADY");
    expect(await balanceOf(b)).toBeUndefined();
  });

  test("an account that claimed stays claimed even after unlinking the identity it claimed with", async () => {
    const a = await account({ "google.com": "g-5", "apple.com": "a-5" });
    await claimWelcome(deps, google(a));
    await db.collection("welcomes").doc(welcomeKey("apple.com", "a-5")).delete(); // as if claimed before Apple was linked
    await auth.updateUser(a, { providersToUnlink: ["google.com"] });
    expect((await claimWelcome(deps, signedIn(a, "apple.com"))).status).toBe("ALREADY");
    expect(await welcomeDoc(welcomeKey("apple.com", "a-5"))).toMatchObject({ credits: 0 });
  });

  test("delete the account and sign in again with the same Google account: nothing granted; welcomes/ survives", async () => {
    const a = await account({ "google.com": "g-6" });
    await claimWelcome(deps, google(a));
    await deleteAccount(deps, google(a), {});
    expect(await userDoc(a)).toMatchObject({ balance: 0, forfeitedBalance: 3, welcomeCredits: 3 });
    expect(await welcomeDoc(welcomeKey("google.com", "g-6"))).toMatchObject({ uid: a, credits: 3 });
    // The tombstone itself cannot claim.
    expect(await reason(claimWelcome(deps, google(a)))).toBe("SIGN_IN_REQUIRED");
    const again = await account({ "google.com": "g-6" });
    expect(await claimWelcome(deps, google(again))).toMatchObject({ status: "ALREADY", granted: 0, balance: 0 });
    expect(await stats()).toMatchObject({ welcomeGrants: 1 });
  });

  test("a closed account (deleted or merged tombstone whose Auth user still exists) is ACCOUNT_CLOSED", async () => {
    const a = await account({ "google.com": "g-7" });
    await db.collection("users").doc(a).set({ ...emptyUser(), mergedInto: "elsewhere" });
    expect(await reason(claimWelcome(deps, google(a)))).toBe("ACCOUNT_CLOSED");
    expect(await welcomeCount()).toBe(0);
  });

  test("OFF when the grant is off, generation is paused or the config is invalid; OFF uses up nothing", async () => {
    const a = await account({ "google.com": "g-8" });
    for (const config of [{ welcome: { recipes: 0 } }, { welcome: { recipes: 3, perDay: 0 } }, { welcome: { recipes: 99 } }, { welcome: { recipes: 3, perDay: 200 }, generationEnabled: false }]) {
      await setConfig(config);
      expect(await claimWelcome(deps, google(a))).toMatchObject({ status: "OFF", granted: 0, balance: 0, welcomeRecipes: 0 });
    }
    expect(await welcomeCount()).toBe(0);
    expect(await userDoc(a)).toBeUndefined();
    await setConfig({ generationEnabled: true });
    expect((await claimWelcome(deps, google(a))).status).toBe("GRANTED");
  });

  test("the amount is the config's at claim time", async () => {
    await setConfig({ welcome: { recipes: 7 } });
    const a = await account({ "google.com": "g-9" });
    expect(await claimWelcome(deps, google(a))).toMatchObject({ status: "GRANTED", granted: 7, balance: 7 });
  });

  test("many Google accounts: the day's brake stops grants at perDay, logs it, and the next UTC day opens again", async () => {
    await setConfig({ welcome: { recipes: 3, perDay: 2 } });
    const uids = [];
    for (let i = 0; i < 4; i++) uids.push(await account({ "google.com": `farm-${i}` }));
    expect((await claimWelcome(deps, google(uids[0]))).status).toBe("GRANTED");
    const second = await claimWelcome(deps, google(uids[1]));
    expect(second).toMatchObject({ status: "GRANTED", welcomeRecipes: 0 });
    expect(deps.logs.filter((l) => l.message === "welcome_day_limit")).toEqual([
      { level: "warn", message: "welcome_day_limit", data: { day: "2026-06-15", perDay: 2, event: "reached" } },
    ]);
    expect(await claimWelcome(deps, google(uids[2]))).toMatchObject({ status: "OFF", granted: 0, balance: 0 });
    expect(await claimWelcome(deps, google(uids[3]))).toMatchObject({ status: "OFF" });
    // Refusals at the limit are logged, sampled: one line a minute per instance.
    const refused = deps.logs.filter((l) => l.message === "welcome_day_limit" && l.data?.event === "refused");
    expect(refused.length).toBeLessThanOrEqual(1);
    expect(await getWelcomeOffer(deps)).toEqual({ recipes: 0 });
    expect(await stats()).toEqual({ welcomeGrants: 2, welcomeCredits: 6 });
    // A refused claim did not use up the identity.
    deps.clock.advance(DAY_MS);
    expect((await claimWelcome(deps, google(uids[2]))).status).toBe("GRANTED");
    expect(await stats(utcDay(NOW + DAY_MS))).toEqual({ welcomeGrants: 1, welcomeCredits: 3 });
  });

  test("concurrent claims of one account grant once", async () => {
    const a = await account({ "google.com": "g-10", "apple.com": "a-10" });
    const results = await Promise.all(Array.from({ length: 5 }, (_, i) => claimWelcome(deps, i % 2 ? google(a) : signedIn(a, "apple.com"))));
    expect(results.filter((r) => r.status === "GRANTED")).toHaveLength(1);
    expect(results.filter((r) => r.status === "ALREADY")).toHaveLength(4);
    expect(await userDoc(a)).toMatchObject({ balance: 3, welcomeCredits: 3 });
    expect(await stats()).toMatchObject({ welcomeGrants: 1 });
  });

  test("concurrent claims of different accounts cannot overshoot the day's brake", async () => {
    await setConfig({ welcome: { recipes: 3, perDay: 1 } });
    const uids = [];
    for (let i = 0; i < 4; i++) uids.push(await account({ "google.com": `race-${i}` }));
    const results = await Promise.all(uids.map((u) => claimWelcome(deps, google(u))));
    expect(results.filter((r) => r.status === "GRANTED")).toHaveLength(1);
    expect(results.filter((r) => r.status === "OFF")).toHaveLength(3);
    expect(await stats()).toEqual({ welcomeGrants: 1, welcomeCredits: 3 });
  });

  test("a negative balance (a refund after use) gets the grant on top", async () => {
    const a = await account({ "google.com": "g-11" });
    await db.collection("users").doc(a).set({ ...emptyUser(), balance: -5 });
    expect(await claimWelcome(deps, google(a))).toMatchObject({ status: "GRANTED", balance: -2 });
  });
});

describe("merges", () => {
  async function anonymousBuyer(credits = 10): Promise<{ uid: string; idToken: string }> {
    const anon = await anonymousUser();
    const token = deps.play.add(playToken(), { productId: credits === 50 ? "recipes_50" : "recipes_10" });
    expect((await verifyPurchase(deps, callerFor(anon.uid, "anonymous"), { store: "play", purchaseToken: token })).status).toBe("GRANTED");
    return anon;
  }

  test("an anonymous buyer's balance merged into an account that claimed: both kept, counters apart", async () => {
    const anon = await anonymousBuyer();
    const a = await account({ "google.com": "g-m1" });
    await claimWelcome(deps, google(a));
    const res = await mergeBalance(deps, google(a), { sourceIdToken: anon.idToken });
    expect(res).toMatchObject({ balance: 13, moved: 10 });
    expect(await userDoc(a)).toMatchObject({ balance: 13, purchasedCredits: 10, welcomeCredits: 3 });
    expect(await welcomeDoc(welcomeKey("google.com", "g-m1"))).toMatchObject({ uid: a, credits: 3 });
    // Merging first, then claiming, gives the same.
    const anon2 = await anonymousBuyer();
    const b = await account({ "google.com": "g-m2" });
    await mergeBalance(deps, google(b), { sourceIdToken: anon2.idToken });
    expect(await claimWelcome(deps, google(b))).toMatchObject({ status: "GRANTED", balance: 13 });
    expect(await userDoc(b)).toMatchObject({ purchasedCredits: 10, welcomeCredits: 3 });
  });

  test("a claim racing a merge into the same account: neither update is lost", async () => {
    const anon = await anonymousBuyer();
    const a = await account({ "google.com": "g-m3" });
    const [claim, merge] = await Promise.all([
      claimWelcome(deps, google(a)),
      mergeBalance(deps, google(a), { sourceIdToken: anon.idToken }),
    ]);
    expect(claim.status).toBe("GRANTED");
    expect(merge.moved).toBe(10);
    expect(await userDoc(a)).toMatchObject({ balance: 13, purchasedCredits: 10, welcomeCredits: 3 });
    expect(await userDoc(anon.uid)).toMatchObject({ balance: 0, mergedInto: a });
  });

  test("an anonymous source that somehow holds welcome credits carries them to the target", async () => {
    const anon = await anonymousUser();
    await db.collection("users").doc(anon.uid).set({ ...emptyUser(), balance: 2, welcomeCredits: 2 });
    const a = await account({ "google.com": "g-m4" });
    await mergeBalance(deps, google(a), { sourceIdToken: anon.idToken });
    expect(await userDoc(a)).toMatchObject({ balance: 2, welcomeCredits: 2 });
  });
});

describe("free recipes are not purchases", () => {
  test("the refund allowance counts paid credits only", () => {
    const u = (purchased: number, welcome: number, refunded = 0): UserDoc =>
      ({ ...emptyUser(), purchasedCredits: purchased, refundedCredits: refunded, welcomeCredits: welcome });
    expect(paidAllowance(u(0, 3), 10)).toBe(0);
    expect(paidAllowance(u(0, 20), 10)).toBe(0);
    expect(paidAllowance(u(10, 3), 10)).toBe(1);
    expect(paidAllowance(u(19, 20), 10)).toBe(1);
    expect(paidAllowance(u(20, 3), 10)).toBe(2);
    expect(paidAllowance(u(10, 3, 10), 10)).toBe(0);
  });

  test("free recipes are spent like bought ones; a content failure on free recipes alone is charged, infra is refunded", async () => {
    const a = await account({ "google.com": "g-p1" });
    await claimWelcome(deps, google(a));
    expect(await generateRecipe(deps, google(a), request())).toMatchObject({ status: "DONE", charged: true, balance: 2 });
    deps.generator.failWith("content");
    expect(await generateRecipe(deps, google(a), request())).toMatchObject({ status: "FAILED", charged: true, balance: 1 });
    deps.generator.failWith("infra", 1, { inputTokens: 0, outputTokens: 0, thinkingTokens: 0, model: "m", finishReason: null });
    expect(await generateRecipe(deps, google(a), request())).toMatchObject({ status: "FAILED", charged: false, balance: 1 });
    expect(await userDoc(a)).toMatchObject({ consumedCredits: 2, contentRefundsUsed: 0, purchasedCredits: 0 });
  });

  test("a buyer's allowance is unchanged by a grant: 10 bought + 3 free still refund one content failure", async () => {
    const a = await account({ "google.com": "g-p2" });
    await claimWelcome(deps, google(a));
    const token = deps.play.add(playToken());
    await verifyPurchase(deps, google(a), { store: "play", purchaseToken: token });
    await setConfig({ perUserPerMinute: 100 });
    deps.generator.failWith("content", 2);
    expect((await generateRecipe(deps, google(a), request())).charged).toBe(false);
    expect((await generateRecipe(deps, google(a), request())).charged).toBe(true);
  });

  describe("FIFO: free recipes count as spent first", () => {
    let signedAt = NOW;
    const notify = (type: string, tx: AppleTransaction) =>
      handleAppleNotification(deps, { signedPayload: deps.appStore.signNotification(type, tx, ++signedAt) });
    const spend = async (uid: string, n: number) => {
      const u = (await userDoc(uid))!;
      await db.collection("users").doc(uid).update({ balance: u.balance - n, consumedCredits: (u.consumedCredits ?? 0) + n });
    };
    async function claimThenBuy(sub: string): Promise<{ uid: string; tx: AppleTransaction }> {
      const uid = await account({ "apple.com": sub });
      await claimWelcome(deps, signedIn(uid, "apple.com"));
      deps.clock.advance(1000);
      const tx = appleTx({ appTransactionId: `T-${sub}` });
      const res = await verifyPurchase(deps, signedIn(uid, "apple.com"), { store: "appstore", signedTransaction: deps.appStore.sign(tx), consumptionConsent: true });
      expect(res.status).toBe("GRANTED");
      return { uid, tx };
    }

    test("Apple's consumption answer leaves the free recipes out", async () => {
      const { uid, tx } = await claimThenBuy("f-1");
      await spend(uid, 3);
      await notify("CONSUMPTION_REQUEST", tx);
      await spend(uid, 2);
      await notify("CONSUMPTION_REQUEST", tx);
      expect(deps.appStore.consumptionSent.map((s) => s.info.consumptionPercentage)).toEqual([0, 20_000]);
    });

    test("refunding a pack after using only the free recipes is not a refund after use", async () => {
      const { uid, tx } = await claimThenBuy("f-2");
      await spend(uid, 3);
      await notify("REFUND", { ...tx, revocationDate: signedAt });
      const p = (await db.collection("purchases").doc(applePurchaseId("production", tx.transactionId)).get()).data();
      expect(p).toMatchObject({ refundAfterUse: false, refundedCredits: 10 });
      expect((await db.collection("buyers").doc("apple_T-f-2").get()).exists).toBe(false);
      // The free recipes were spent; the refund takes the pack's 10 back.
      expect(await balanceOf(uid)).toBe(0);
    });

    test("using more than the free recipes, then refunding, is a refund after use of the excess only", async () => {
      const { uid, tx } = await claimThenBuy("f-3");
      await spend(uid, 5);
      await notify("REFUND", { ...tx, revocationDate: signedAt });
      expect((await db.collection("buyers").doc("apple_T-f-3").get()).data()?.events).toMatchObject({
        [applePurchaseId("production", tx.transactionId)]: { spentCreditsTaken: 2, active: true },
      });
      expect(await balanceOf(uid)).toBe(-2);
    });
  });

  test("a grant never sets purchasesBlocked or adds buyer keys", async () => {
    const a = await account({ "google.com": "g-p3" });
    const res = await claimWelcome(deps, google(a));
    expect(res.purchasesBlocked).toBeUndefined();
    expect((await userDoc(a))?.buyerKeys).toBeUndefined();
    expect((await db.collection("buyers").get()).size).toBe(0);
  });
});

describe("the callables' wiring (src/index.ts)", () => {
  // The callable wrappers are not run in tests; this pins what they are built from.
  const index = readFileSync(join(__dirname, "../src/index.ts"), "utf8");

  test("claimWelcome: App Check enforced and consumed (a replayed token is refused), signed-in caller", () => {
    expect(index).toMatch(/export const claimWelcome = onCall\(\s*\{\s*\.\.\.callable,\s*consumeAppCheckToken: CONSUME_APP_CHECK_TOKENS,/);
    expect(index).toContain("callerOf(request, throttles.claimWelcome, CONSUME_APP_CHECK_TOKENS)");
    expect(index).toContain("claimWelcome: { maxInstances: 1, perMinute: 10 },");
  });

  test("getWelcomeOffer: App Check enforced, not consumed, no sign-in, throttled by address, no input", () => {
    expect(index).toMatch(/export const getWelcomeOffer = onCall\(\s*\{ \.\.\.callable, timeoutSeconds: 10, maxInstances: LIMITS\.getWelcomeOffer\.maxInstances, concurrency: 1 \}/);
    expect(index).not.toMatch(/getWelcomeOffer = onCall\(\s*\{[^}]*consumeAppCheckToken/);
    expect(index).toContain('if (!request.app) throw new HttpsError("unauthenticated", "App Check required");');
    expect(index).toContain("throttles.getWelcomeOffer.admitAddress(");
    expect(index).toContain("return welcomeOfferCore(getDeps());");
    expect(index).toContain("getWelcomeOffer: { maxInstances: 1, perMinute: 120 },");
  });
});
