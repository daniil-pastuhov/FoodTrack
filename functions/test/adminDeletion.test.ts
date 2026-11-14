/**
 * The owner's admin command for deletion requests by email (src/admin): every lookup kind, merged
 * and already-deleted accounts, the dry run, --confirm, and what --apply changes and keeps.
 */
import { randomUUID } from "node:crypto";
import { Timestamp } from "firebase-admin/firestore";
import {
  AdminRefusal,
  deleteForRequest,
  describeAccount,
  findAccounts,
  type AdminStoreLookups,
} from "../src/admin/accountRequests";
import { parseAdminArgs, runAdmin } from "../src/admin/cli";
import { mergeBalance } from "../src/core/account";
import { registerAppleSignIn } from "../src/core/appleToken";
import { generateRecipe } from "../src/core/generation";
import { applePurchaseId, playPurchaseId } from "../src/core/purchases";
import { emptyUser, uidHash } from "../src/model";
import type { AppleTransaction } from "../src/ports";
import {
  anonymousUser,
  appleTx,
  auth,
  callerFor,
  db,
  FakeRevoker,
  makeDeps,
  passwordUser,
  playToken,
  resetEmulators,
  verifyPurchase,
  type TestDeps,
} from "./helpers";

class FakeStores implements AdminStoreLookups {
  playOrders = new Map<string, string>();
  appleOrders = new Map<string, AppleTransaction[]>();
  async playOrderToken(orderId: string) {
    return this.playOrders.get(orderId) ?? null;
  }
  async appleOrderTransactions(orderId: string) {
    return this.appleOrders.get(orderId) ?? [];
  }
}

let deps: TestDeps;
let stores: FakeStores;
beforeEach(async () => {
  await resetEmulators();
  deps = makeDeps();
  stores = new FakeStores();
});

afterEach(() => {
  // Log lines carry uid hashes only: never an email, a uid, a token or a code.
  const logged = JSON.stringify(deps.logs);
  expect(logged).not.toMatch(/@example\.com/);
  expect(logged).not.toContain("refresh-for-");
});

/** Firestore and Auth as plain JSON, to show a dry run changed nothing. */
async function snapshot(): Promise<string> {
  const out: Record<string, unknown> = {};
  for (const c of ["users", "purchases", "buyers"]) {
    for (const d of (await db.collection(c).get()).docs) out[`${c}/${d.id}`] = d.data();
  }
  out.auth = (await auth.listUsers()).users.map((u) => u.uid).sort();
  return JSON.stringify(out);
}

async function refusal(p: Promise<unknown>): Promise<string> {
  try {
    await p;
  } catch (e) {
    if (e instanceof AdminRefusal) return e.message;
    throw e;
  }
  return "no refusal";
}

/** A Google account (email on the Auth user) that bought a Play pack; returns its uid and the order id. */
async function playBuyer(): Promise<{ uid: string; email: string; orderId: string; token: string }> {
  const email = `${randomUUID()}@example.com`;
  const u = await auth.createUser({ email });
  await auth.updateUser(u.uid, { providerToLink: { providerId: "google.com", uid: `g-${u.uid}`, email } });
  const token = deps.play.add(playToken());
  await verifyPurchase(deps, callerFor(u.uid, "google.com"), { store: "play", purchaseToken: token });
  return { uid: u.uid, email, orderId: deps.play.purchases.get(token)!.orderId!, token };
}

const userDoc = async (uid: string) => (await db.collection("users").doc(uid).get()).data();

describe("find", () => {
  test("--uid summarises the account without personal data", async () => {
    const b = await playBuyer();
    const s = await describeAccount(deps, b.uid);
    expect(s).toMatchObject({
      uid: b.uid, uidHash: uidHash(b.uid), state: "live", authUser: true, anonymous: false,
      providers: ["google.com"], balance: 10, purchases: { linked: 1, detached: 0 }, inFlight: 0,
      appleTokenStored: false, mergeChain: [],
    });
    expect(s.createdAt).toMatch(/^\d{4}-\d\d-\d\dT/);
    expect(s.purchaseList).toEqual([
      { store: "play", productId: "recipes_10", grantedAt: expect.stringMatching(/^\d{4}-/), state: "consumed", detached: false },
    ]);
    expect(JSON.stringify(s)).not.toContain(b.email);
  });

  test("--email finds the account's own email, case-insensitively", async () => {
    const b = await playBuyer();
    expect((await findAccounts(deps, stores, { kind: "email", value: b.email.toUpperCase() })).matches)
      .toEqual([{ uid: b.uid, via: "Auth email" }]);
  });

  test("--email falls back to the provider entries when no account has it at the top level", async () => {
    const relay = `${randomUUID()}@example.com`;
    const u = await auth.createUser({});
    await auth.updateUser(u.uid, { providerToLink: { providerId: "apple.com", uid: `a-${u.uid}`, email: relay } });
    expect((await auth.getUser(u.uid)).email).toBeUndefined();
    expect((await findAccounts(deps, stores, { kind: "email", value: relay })).matches)
      .toEqual([{ uid: u.uid, via: "provider email (apple.com)" }]);
    expect((await findAccounts(deps, stores, { kind: "email", value: `${randomUUID()}@example.com` })).matches).toEqual([]);
  });

  test("--play-order finds the purchase by the order id stored at verification", async () => {
    const b = await playBuyer();
    const r = await findAccounts(deps, stores, { kind: "play-order", value: ` ${b.orderId} ` });
    expect(r.matches).toEqual([{ uid: b.uid, via: expect.stringMatching(/^purchase play_/) }]);
  });

  test("--play-order asks Play's Orders API when no purchase doc has the order id", async () => {
    const b = await playBuyer();
    await db.collection("purchases").doc(playPurchaseId(b.token)).update({ orderId: null });
    expect((await findAccounts(deps, stores, { kind: "play-order", value: b.orderId })).matches).toEqual([]);
    stores.playOrders.set(b.orderId, b.token);
    const r = await findAccounts(deps, stores, { kind: "play-order", value: b.orderId });
    expect(r.matches.map((m) => m.uid)).toEqual([b.uid]);
    expect(r.notes.join()).toContain("Orders API");
    // An order Play knows but the server never saw.
    stores.playOrders.set("GPA.0000-unseen", playToken());
    const unseen = await findAccounts(deps, stores, { kind: "play-order", value: "GPA.0000-unseen" });
    expect(unseen.matches).toEqual([]);
    expect(unseen.notes.join()).toContain("never sent to the server");
  });

  test("a purchase nobody redeemed (refund seen first) names no account", async () => {
    await db.collection("purchases").doc("play_x").set({
      store: "play", uid: null, env: "production", productId: "recipes_10", quantity: 1, credits: 10,
      refundedCredits: 10, state: "refunded", grantedAt: null, orderId: "GPA.1111-refunded",
    });
    const r = await findAccounts(deps, stores, { kind: "play-order", value: "GPA.1111-refunded" });
    expect(r.matches).toEqual([]);
    expect(r.notes.join()).toContain("never redeemed");
  });

  test("--apple-order resolves the order's transactions to their purchase docs; --apple-transaction directly", async () => {
    const u = await anonymousUser();
    const tx = appleTx({ appTransactionId: "A-1" });
    await verifyPurchase(deps, callerFor(u.uid, "anonymous"), { store: "appstore", signedTransaction: deps.appStore.sign(tx) });
    expect((await db.collection("purchases").doc(applePurchaseId("production", tx.transactionId)).get()).exists).toBe(true);

    stores.appleOrders.set("MK1234ABCD", [tx, appleTx()]);
    const r = await findAccounts(deps, stores, { kind: "apple-order", value: "MK1234ABCD" });
    expect(r.matches.map((m) => m.uid)).toEqual([u.uid]);
    expect(r.notes.join()).toContain("never sent to the server");
    expect((await findAccounts(deps, stores, { kind: "apple-order", value: "UNKNOWN" })).notes.join()).toContain("no transactions");

    const byTx = await findAccounts(deps, stores, { kind: "apple-transaction", value: tx.transactionId });
    expect(byTx.matches.map((m) => m.uid)).toEqual([u.uid]);
    expect((await describeAccount(deps, u.uid))).toMatchObject({ state: "live", anonymous: true, providers: [], balance: 10 });
  });

  test("a merged anonymous buyer shows the chain to the account that holds the credits", async () => {
    const anon = await anonymousUser();
    const signed = await passwordUser();
    const token = deps.play.add(playToken());
    await verifyPurchase(deps, callerFor(anon.uid, "anonymous"), { store: "play", purchaseToken: token });
    await mergeBalance(deps, callerFor(signed.uid), { sourceIdToken: anon.idToken });

    const r = await findAccounts(deps, stores, { kind: "play-order", value: deps.play.purchases.get(token)!.orderId! });
    expect(r.matches.map((m) => m.uid)).toEqual([anon.uid]);
    const s = await describeAccount(deps, anon.uid);
    expect(s).toMatchObject({ state: "merged", mergedInto: signed.uid, authUser: false, balance: 0 });
    expect(s.mergeChain).toEqual([{ uid: signed.uid, state: "live" }]);
  });

  test("an already deleted account: purchases found through formerUid, state deleted", async () => {
    const b = await playBuyer();
    await deleteForRequest(deps, b.uid, "play-order", { apply: true, confirm: b.uid });
    const r = await findAccounts(deps, stores, { kind: "play-order", value: b.orderId });
    expect(r.matches).toEqual([{ uid: b.uid, via: expect.stringContaining("detached by an earlier deletion") }]);
    const s = await describeAccount(deps, b.uid);
    expect(s).toMatchObject({ state: "deleted", authUser: false, purchases: { linked: 0, detached: 1 } });
    expect(s.deletedAt).toBeDefined();
  });
});

describe("delete", () => {
  test("a dry run changes nothing and says what --apply would do", async () => {
    const b = await playBuyer();
    const before = await snapshot();
    const out = await deleteForRequest(deps, b.uid, "email", { apply: false });
    expect(out.applied).toBe(false);
    expect(out.plan.steps.join("\n")).toContain("balance 10 forfeited");
    expect(out.plan.steps.join("\n")).toContain("detach 1 purchase(s)");
    expect(await snapshot()).toBe(before);
    expect(deps.logs.filter((l) => l.message === "admin_account_deleted")).toEqual([]);
  });

  test("--apply without --confirm, or with another uid, refuses and changes nothing", async () => {
    const b = await playBuyer();
    const other = await playBuyer();
    const before = await snapshot();
    expect(await refusal(deleteForRequest(deps, b.uid, "email", { apply: true }))).toContain("--apply needs --confirm");
    expect(await refusal(deleteForRequest(deps, b.uid, "email", { apply: true, confirm: other.uid }))).toContain("does not match");
    expect(await refusal(deleteForRequest(deps, b.uid, "email", { apply: false, confirm: other.uid }))).toContain("does not match");
    expect(await snapshot()).toBe(before);
  });

  test("--apply tombstones, detaches purchases, keeps buyers, revokes and removes the Apple token, deletes the Auth user", async () => {
    const b = await playBuyer();
    await auth.updateUser(b.uid, { providerToLink: { providerId: "apple.com", uid: `a-${b.uid}` } });
    const code = `code-${randomUUID()}`;
    await registerAppleSignIn(deps, { ...callerFor(b.uid, "apple.com"), providers: ["apple.com", "google.com"] }, { appleAuthorizationCode: code });
    expect((await userDoc(b.uid))?.appleToken).toBeDefined();
    const pid = playPurchaseId(b.token);
    const buyerKeys = (await db.collection("purchases").doc(pid).get()).data()!.buyerKeys as string[];
    for (const k of buyerKeys) await db.collection("buyers").doc(k).set({ events: {}, refundAfterUseCount: 1, blocked: false, createdAt: new Date() });

    const out = await deleteForRequest(deps, b.uid, "email", { apply: true, confirm: b.uid });
    expect(out).toMatchObject({ applied: true, appleLinkRemoved: true });
    expect(deps.appleRevoker.revokedTokens).toEqual([FakeRevoker.tokenFor(code)]);

    const user = await userDoc(b.uid);
    expect(user).toMatchObject({ balance: 0, forfeitedBalance: 10, adminDeletionKind: "email" });
    expect(user?.deletedAt).toBeInstanceOf(Timestamp);
    expect(user?.adminDeletionAt).toBeInstanceOf(Timestamp);
    expect(user?.appleToken).toBeUndefined();
    const purchase = (await db.collection("purchases").doc(pid).get()).data();
    expect(purchase).toMatchObject({ uid: null, formerUid: b.uid, orderId: b.orderId });
    for (const k of buyerKeys) expect((await db.collection("buyers").doc(k).get()).exists).toBe(true);
    await expect(auth.getUser(b.uid)).rejects.toMatchObject({ code: "auth/user-not-found" });

    const line = deps.logs.find((l) => l.message === "admin_account_deleted");
    expect(line?.data).toEqual({ uidHash: uidHash(b.uid), kind: "email", state: "live", purchasesDetached: 1, appleLinkRemoved: true });
    expect(JSON.stringify(deps.logs)).not.toContain(b.uid);

    // Running it again changes nothing that matters and never touches the purchase.
    await deleteForRequest(deps, b.uid, "email", { apply: true, confirm: b.uid });
    expect((await db.collection("purchases").doc(pid).get()).data()).toMatchObject({ uid: null, formerUid: b.uid });
  });

  test("a merged-away account keeps its tombstone and its purchases' uid; only its leftovers go", async () => {
    const anon = await anonymousUser();
    const signed = await passwordUser();
    const token = deps.play.add(playToken());
    await verifyPurchase(deps, callerFor(anon.uid, "anonymous"), { store: "play", purchaseToken: token });
    await mergeBalance(deps, callerFor(signed.uid), { sourceIdToken: anon.idToken });
    const out = await deleteForRequest(deps, anon.uid, "play-order", { apply: true, confirm: anon.uid });
    expect(out.plan.steps.join()).toContain(`merged into ${signed.uid}`);
    expect((await db.collection("purchases").doc(playPurchaseId(token)).get()).data()?.uid).toBe(anon.uid);
    expect(await userDoc(anon.uid)).toMatchObject({ mergedInto: signed.uid });
    expect((await userDoc(signed.uid))?.balance).toBe(10);
  });

  test("an account with nothing on the server is refused", async () => {
    expect(await refusal(deleteForRequest(deps, "nobody", "uid", { apply: false }))).toContain("nothing to delete");
  });

  test("BUSY while a generation is in flight; one past its deadline is settled first, then deleted", async () => {
    const u = await anonymousUser();
    await db.collection("users").doc(u.uid).set({ ...emptyUser(), balance: 5, purchasedCredits: 5 });
    const slow = deps.generator.blockNext();
    const running = generateRecipe(deps, callerFor(u.uid, "anonymous"), {
      requestId: randomUUID(), kind: "recipe", today: new Date().toISOString().slice(0, 10), allergens: [],
      ingredients: [{ name: "eggs", expiresOn: null, quantity: 1 }],
      config: { difficulty: "easy", detail: "brief", servings: 1, maxMinutes: null, mealType: "any", diet: "none", language: "en" },
    }).catch(() => undefined);
    await slow.started;
    expect(await refusal(deleteForRequest(deps, u.uid, "uid", { apply: true, confirm: u.uid }))).toMatch(/^BUSY/);
    expect((await userDoc(u.uid))?.deletedAt).toBeUndefined();

    deps.clock.advance(60 * 60_000);
    const dry = await deleteForRequest(deps, u.uid, "uid", { apply: false });
    expect(dry.plan.steps[0]).toContain("settle 1 generation");
    await deleteForRequest(deps, u.uid, "uid", { apply: true, confirm: u.uid });
    // The stuck reservation was refunded (infrastructure failure) before the balance was forfeited.
    expect(await userDoc(u.uid)).toMatchObject({ balance: 0, forfeitedBalance: 5, inFlight: 0 });
    slow.release();
    await running;
  });

  test("an inFlight counter with no pending generation is refused for a look by hand", async () => {
    const u = await anonymousUser();
    await db.collection("users").doc(u.uid).set({ ...emptyUser(), balance: 1, inFlight: 1 });
    expect(await refusal(deleteForRequest(deps, u.uid, "uid", { apply: false }))).toContain("counter is off");
  });
});

describe("command line", () => {
  test("needs exactly one lookup, and --kind only with --uid", () => {
    expect(() => parseAdminArgs(["find"])).toThrow(AdminRefusal);
    expect(() => parseAdminArgs(["find", "--uid", "a", "--email", "b@example.com"])).toThrow(/exactly one/);
    expect(() => parseAdminArgs(["delete", "--email", "b@example.com", "--kind", "uid"])).toThrow(/--kind goes with --uid/);
    expect(() => parseAdminArgs(["find", "--uid", "a", "--apply"])).toThrow(/belong to delete/);
    expect(() => parseAdminArgs(["remove", "--uid", "a"])).toThrow(AdminRefusal);
    expect(parseAdminArgs(["delete", "--uid", "a", "--kind", "play-order"])).toMatchObject({
      command: "delete", lookup: { kind: "uid", value: "a" }, kind: "play-order", project: "demo-foodtracker", apply: false,
    });
    expect(parseAdminArgs(["find", "--email", "x@example.com", "--project", "bestbefore-f9ee1"]).project).toBe("bestbefore-f9ee1");
  });

  test("find and delete print uids and counts, never the email typed", async () => {
    const b = await playBuyer();
    const lines: string[] = [];
    const env = { deps, stores, print: (l: string) => lines.push(l) };
    await runAdmin(["find", "--email", b.email], env);
    await runAdmin(["delete", "--email", b.email], env);
    expect(lines.join("\n")).toContain(`account ${b.uid}`);
    expect(lines.join("\n")).toContain(`--apply --confirm ${b.uid}`);
    expect(lines.join("\n")).not.toContain(b.email);
    expect((await auth.getUser(b.uid)).uid).toBe(b.uid);

    expect(await refusal(runAdmin(["delete", "--email", b.email, "--apply", "--confirm", "someone-else"], env))).toContain("does not match");
    await runAdmin(["delete", "--email", b.email, "--apply", "--confirm", b.uid], env);
    await expect(auth.getUser(b.uid)).rejects.toMatchObject({ code: "auth/user-not-found" });
  });

  test("several matching accounts are listed, and delete refuses to pick one", async () => {
    const email = `${randomUUID()}@example.com`;
    for (let i = 0; i < 2; i++) {
      const u = await auth.createUser({});
      await auth.updateUser(u.uid, { providerToLink: { providerId: "google.com", uid: `g-${randomUUID()}`, email } });
    }
    const env = { deps, stores, print: () => undefined };
    expect(await refusal(runAdmin(["delete", "--email", email], env))).toContain("2 accounts match");
  });
});
