import { DEFAULT_COSTS } from "../src/constants";
import { HttpsError } from "firebase-functions/v2/https";
import { randomUUID } from "node:crypto";
import { deleteAccount, getBalance, mergeBalance, scrubProfile } from "../src/core/account";
import { generateRecipe } from "../src/core/generation";
import { handlePlayNotification } from "../src/core/notifications";
import { playPurchaseId } from "../src/core/purchases";
import { emptyUser } from "../src/model";
import {
  anonymousUser,
  auth,
  balanceOf,
  callerFor,
  db,
  makeDeps,
  passwordUser,
  playToken,
  resetEmulators,
  type TestDeps,
  verifyPurchase,
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

const give = (uid: string, balance: number) => db.collection("users").doc(uid).set({ ...emptyUser(), balance });

describe("getBalance", () => {
  test("is 0 for an unknown, deleted or merged account", async () => {
    expect(await getBalance(deps, callerFor("nobody"))).toEqual({ balance: 0, costs: DEFAULT_COSTS, acceptsOptionalIngredients: true });
    await give("u1", 4);
    expect(await getBalance(deps, callerFor("u1"))).toEqual({ balance: 4, costs: DEFAULT_COSTS, acceptsOptionalIngredients: true });
    await db.collection("users").doc("u1").update({ mergedInto: "u2" });
    expect(await getBalance(deps, callerFor("u1"))).toEqual({ balance: 0, costs: DEFAULT_COSTS, acceptsOptionalIngredients: true });
  });
});

describe("mergeBalance", () => {
  test("moves an anonymous install's credits into the signed-in account and closes the source", async () => {
    const anon = await anonymousUser();
    const signed = await passwordUser();
    await give(anon.uid, 7);
    await give(signed.uid, 2);
    const res = await mergeBalance(deps, callerFor(signed.uid), { sourceIdToken: anon.idToken });
    expect(res).toEqual({ balance: 9, moved: 7, costs: DEFAULT_COSTS, acceptsOptionalIngredients: true });
    expect(await balanceOf(anon.uid)).toBe(0);
    expect((await db.collection("users").doc(anon.uid).get()).data()?.mergedInto).toBe(signed.uid);
    await expect(auth.getUser(anon.uid)).rejects.toMatchObject({ code: "auth/user-not-found" });
    // The source cannot be merged again.
    expect(await reason(mergeBalance(deps, callerFor(signed.uid), { sourceIdToken: anon.idToken }))).toBe("MERGE_NOT_ALLOWED");
    expect(await balanceOf(signed.uid)).toBe(9);
  });

  test("a merged source is closed for every mutating call", async () => {
    const anon = await anonymousUser();
    const signed = await passwordUser();
    await give(anon.uid, 3);
    await mergeBalance(deps, callerFor(signed.uid), { sourceIdToken: anon.idToken });
    const req = {
      requestId: randomUUID(), kind: "recipe", today: new Date().toISOString().slice(0, 10), allergens: [],
      ingredients: [{ name: "eggs", expiresOn: null, quantity: 1 }],
      config: { difficulty: "easy", detail: "brief", servings: 1, maxMinutes: null, mealType: "any", diet: "none", language: "en" },
    };
    expect(await reason(generateRecipe(deps, callerFor(anon.uid, "anonymous"), req))).toBe("ACCOUNT_CLOSED");
    const token = deps.play.add(playToken());
    expect(await reason(verifyPurchase(deps, callerFor(anon.uid, "anonymous"), { store: "play", purchaseToken: token }))).toBe("ACCOUNT_CLOSED");
  });

  test("refuses an anonymous caller, a non-anonymous source, itself and a bad token", async () => {
    const anon = await anonymousUser();
    const anon2 = await anonymousUser();
    const signed = await passwordUser();
    const other = await passwordUser();
    await give(anon.uid, 5);
    await give(other.uid, 5);
    expect(await reason(mergeBalance(deps, callerFor(anon2.uid, "anonymous"), { sourceIdToken: anon.idToken }))).toBe("MERGE_NOT_ALLOWED");
    expect(await reason(mergeBalance(deps, callerFor(signed.uid), { sourceIdToken: other.idToken }))).toBe("MERGE_NOT_ALLOWED");
    expect(await reason(mergeBalance(deps, callerFor(anon.uid), { sourceIdToken: anon.idToken }))).toBe("MERGE_NOT_ALLOWED");
    expect(await reason(mergeBalance(deps, callerFor(signed.uid), { sourceIdToken: "not-a-token" }))).toBe("MERGE_NOT_ALLOWED");
    expect(await reason(mergeBalance(deps, callerFor(signed.uid), {}))).toBe("INVALID_REQUEST");
    expect(await balanceOf(other.uid)).toBe(5);
    expect(await balanceOf(anon.uid)).toBe(5);
  });

  test("deleting a merged-away source keeps its purchases attached, so refunds still reach the target", async () => {
    const anon = await anonymousUser();
    const signed = await passwordUser();
    const token = deps.play.add(playToken());
    await verifyPurchase(deps, callerFor(anon.uid, "anonymous"), { store: "play", purchaseToken: token });
    await mergeBalance(deps, callerFor(signed.uid), { sourceIdToken: anon.idToken });
    expect(await balanceOf(signed.uid)).toBe(10);
    expect(await deleteAccount(deps, callerFor(anon.uid, "anonymous", 0), {})).toEqual({ deleted: true, balance: 0 });
    expect((await db.collection("purchases").doc(playPurchaseId(token)).get()).data()?.uid).toBe(anon.uid);
    await handlePlayNotification(deps, {
      packageName: "com.foodtracker.android",
      eventTimeMillis: String(Date.now()),
      voidedPurchaseNotification: { purchaseToken: token, productType: 2, refundType: 1 },
    });
    expect(await balanceOf(signed.uid)).toBe(0);
  });

  test("refuses while the source has a generation in flight", async () => {
    const anon = await anonymousUser();
    const signed = await passwordUser();
    await db.collection("users").doc(anon.uid).set({ ...emptyUser(), balance: 2, inFlight: 1 });
    expect(await reason(mergeBalance(deps, callerFor(signed.uid), { sourceIdToken: anon.idToken }))).toBe("BUSY");
  });

  test("a merge into a deleted account is refused", async () => {
    const anon = await anonymousUser();
    const signed = await passwordUser();
    await give(anon.uid, 2);
    await db.collection("users").doc(signed.uid).set({ ...emptyUser(), deletedAt: new Date() });
    expect(await reason(mergeBalance(deps, callerFor(signed.uid), { sourceIdToken: anon.idToken }))).toBe("ACCOUNT_CLOSED");
    expect(await balanceOf(anon.uid)).toBe(2);
  });
});

describe("deleteAccount", () => {
  test("needs a recent sign-in for a signed-in account", async () => {
    const user = await passwordUser();
    const stale = callerFor(user.uid, "password", Math.floor(Date.now() / 1000) - 3600);
    expect(await reason(deleteAccount(deps, stale, {}))).toBe("RECENT_SIGN_IN_REQUIRED");
  });

  test("tombstones the user, detaches purchases, deletes generations, revokes Apple and deletes the Auth user", async () => {
    const user = await passwordUser();
    const caller = callerFor(user.uid, "apple.com");
    const token = deps.play.add(playToken());
    await verifyPurchase(deps, caller, { store: "play", purchaseToken: token });
    await db.collection("users").doc(user.uid).collection("generations").doc("g1").set({ state: "done" });
    expect(await deleteAccount(deps, caller, { appleAuthorizationCode: "apple-code" })).toEqual({
      deleted: true, balance: 0, appleLinkRemoved: true,
    });

    const doc = (await db.collection("users").doc(user.uid).get()).data();
    expect(doc).toMatchObject({ balance: 0, forfeitedBalance: 10 });
    expect(doc?.deletedAt).toBeDefined();
    expect((await db.collection("purchases").doc(playPurchaseId(token)).get()).data()).toMatchObject({ uid: null });
    expect((await db.collection("users").doc(user.uid).collection("generations").get()).empty).toBe(true);
    expect(deps.appleRevoker.codes).toEqual(["apple-code"]);
    await expect(auth.getUser(user.uid)).rejects.toMatchObject({ code: "auth/user-not-found" });
    // Deleting again is harmless. Nothing left to revoke Apple with: the client is told so.
    expect(await deleteAccount(deps, caller, {})).toEqual({ deleted: true, balance: 0, appleLinkRemoved: false });
  });

  test("an anonymous install can delete itself without re-authenticating", async () => {
    const anon = await anonymousUser();
    const old = callerFor(anon.uid, "anonymous", 0);
    expect(await deleteAccount(deps, old, {})).toEqual({ deleted: true, balance: 0 });
  });
});

describe("names and photos are not kept", () => {
  async function googleUser(name = "Ada Lovelace", photo = "https://lh3.example/photo.jpg") {
    const u = await auth.createUser({ email: `${randomUUID()}@example.com`, password: "secret-password", displayName: name, photoURL: photo });
    return u.uid;
  }
  function countingUpdates(d: TestDeps) {
    const calls: string[] = [];
    const real = d.auth.updateUser.bind(d.auth);
    const counting = Object.assign(Object.create(d.auth), {
      updateUser: (uid: string, req: object) => {
        calls.push(uid);
        return real(uid, req as never);
      },
    });
    return { deps: { ...d, auth: counting } as TestDeps, calls };
  }

  test("a signed-in caller's display name and photo are cleared once per sign-in, and never logged", async () => {
    const uid = await googleUser();
    const { deps: d, calls } = countingUpdates(deps);
    const signIn = Math.floor(Date.now() / 1000);
    const caller = callerFor(uid, "google.com", signIn, null, true);
    expect(await scrubProfile(d, caller)).toBe(true);
    const record = await auth.getUser(uid);
    expect(record.displayName).toBeUndefined();
    expect(record.photoURL).toBeUndefined();
    expect((await db.collection("users").doc(uid).get()).data()).toMatchObject({ balance: 0, inFlight: 0 });
    // The same token keeps its claims until it refreshes: no second update.
    expect(await scrubProfile(d, caller)).toBe(false);
    expect(calls).toHaveLength(1);
    // A later sign-in that brought them back is cleared again.
    await auth.updateUser(uid, { displayName: "Ada Lovelace" });
    expect(await scrubProfile(d, callerFor(uid, "google.com", signIn + 10, null, true))).toBe(true);
    expect((await auth.getUser(uid)).displayName).toBeUndefined();
    expect(JSON.stringify(deps.logs)).not.toContain("Ada");
  });

  test("the flag on the user doc stops a second update from another instance", async () => {
    const uid = await googleUser();
    const signIn = Math.floor(Date.now() / 1000) - 100;
    await db.collection("users").doc(uid).set({ ...emptyUser(), profileScrubbedAt: new Date() });
    const { deps: d, calls } = countingUpdates(deps);
    expect(await scrubProfile(d, callerFor(uid, "google.com", signIn, null, true))).toBe(false);
    expect(calls).toHaveLength(0);
  });

  test("anonymous callers and tokens without name or picture cost nothing", async () => {
    const uid = await googleUser();
    const { deps: d, calls } = countingUpdates(deps);
    expect(await scrubProfile(d, callerFor(uid, "anonymous", 1, null, true))).toBe(false);
    expect(await scrubProfile(d, callerFor(uid, "google.com", 1, null, false))).toBe(false);
    expect(calls).toHaveLength(0);
    expect((await db.collection("users").doc(uid).get()).exists).toBe(false);
  });

  test("mergeBalance clears the target account's name and photo", async () => {
    const anon = await anonymousUser();
    const target = await googleUser("Grace Hopper");
    await give(anon.uid, 3);
    await mergeBalance(deps, callerFor(target, "google.com"), { sourceIdToken: anon.idToken });
    const record = await auth.getUser(target);
    expect(record.displayName).toBeUndefined();
    expect(record.photoURL).toBeUndefined();
  });
});
