/** getBalance, mergeBalance and deleteAccount. */
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { fail } from "../contract";
import { RECENT_SIGN_IN_SECONDS, type Costs } from "../constants";
import { assertOpen, emptyUser, loadRuntimeConfig, uidHash, userRef, utcDay, type UserDoc } from "../model";
import type { Caller, Deps } from "../ports";

export async function getBalance(deps: Deps, caller: Caller): Promise<{ balance: number; costs: Costs }> {
  const [snap, config] = await Promise.all([userRef(deps.db, caller.uid).get(), loadRuntimeConfig(deps.db, deps.logger)]);
  const user = snap.data();
  return {
    balance: user && user.deletedAt === undefined && user.mergedInto === undefined ? user.balance : 0,
    costs: config.costs,
  };
}

/** Adds two same-day counters so a merge cannot reset a daily limit; another day's counter is dropped. */
function mergeDay<T extends { day: string }>(
  day: string,
  a: T | undefined,
  b: T | undefined,
  add: (x: T, y: T) => T,
): T | undefined {
  const x = a?.day === day ? a : undefined;
  const y = b?.day === day ? b : undefined;
  if (x && y) return add(x, y);
  return x ?? y;
}

const isAnonymous = (provider: string) => provider === "anonymous";

/**
 * Moves an anonymous install's credits into the signed-in caller's account. The source must prove
 * itself with its own ID token, must be anonymous, and ends up tombstoned (`mergedInto`) and its
 * Auth user deleted, so a balance can be moved once and never onward through it.
 */
export async function mergeBalance(
  deps: Deps,
  caller: Caller,
  data: unknown,
): Promise<{ balance: number; moved: number; costs: Costs }> {
  const sourceIdToken = (data as { sourceIdToken?: unknown } | null)?.sourceIdToken;
  if (typeof sourceIdToken !== "string" || sourceIdToken.length > 8192) {
    fail("invalid-argument", "INVALID_REQUEST", "sourceIdToken is required");
  }
  if (isAnonymous(caller.signInProvider)) {
    fail("failed-precondition", "MERGE_NOT_ALLOWED", "Sign in before merging");
  }
  let source;
  try {
    source = await deps.auth.verifyIdToken(sourceIdToken, true);
  } catch {
    fail("permission-denied", "MERGE_NOT_ALLOWED", "The source token is not valid");
  }
  if (source.firebase.sign_in_provider !== "anonymous") {
    fail("failed-precondition", "MERGE_NOT_ALLOWED", "Only an anonymous account can be merged");
  }
  if (source.uid === caller.uid) fail("failed-precondition", "MERGE_NOT_ALLOWED", "Cannot merge an account into itself");

  const result = await deps.db.runTransaction(async (tx) => {
    const sRef = userRef(deps.db, source.uid);
    const tRef = userRef(deps.db, caller.uid);
    const [sSnap, tSnap] = await Promise.all([tx.get(sRef), tx.get(tRef)]);
    const src = sSnap.data();
    const target = tSnap.data();
    assertOpen(target);
    if (src && (src.deletedAt !== undefined || src.mergedInto !== undefined)) {
      fail("failed-precondition", "MERGE_NOT_ALLOWED", "The source account was already merged or deleted");
    }
    if (src && src.inFlight > 0) fail("failed-precondition", "BUSY", "The source account is generating a recipe");
    // A negative source balance (a store refund of spent credits) moves too: the debt follows.
    const moved = src?.balance ?? 0;
    const base: UserDoc = target ?? { ...emptyUser(), createdAt: Timestamp.fromMillis(deps.now()) };
    const day = utcDay(deps.now());
    const merged: UserDoc = {
      ...base,
      balance: base.balance + moved,
      purchasedCredits: base.purchasedCredits + (src?.purchasedCredits ?? 0),
      consumedCredits: base.consumedCredits + (src?.consumedCredits ?? 0),
      refundedCredits: base.refundedCredits + (src?.refundedCredits ?? 0),
      // The refund allowance is tied to purchases, so what was used of it moves with them.
      contentRefundsUsed: (base.contentRefundsUsed ?? 0) + (src?.contentRefundsUsed ?? 0),
    };
    const addCount = (x: { day: string; count: number }, y: { count: number }) => ({ day, count: x.count + y.count });
    const dayWindow = mergeDay(day, base.dayWindow, src?.dayWindow, addCount);
    const infraRefunds = mergeDay(day, base.infraRefunds, src?.infraRefunds, addCount);
    const spendWindow = mergeDay(day, base.spendWindow, src?.spendWindow, (x, y) => ({ day, microUsd: x.microUsd + y.microUsd }));
    if (dayWindow) merged.dayWindow = dayWindow;
    if (infraRefunds) merged.infraRefunds = infraRefunds;
    if (spendWindow) merged.spendWindow = spendWindow;
    tx.set(tRef, merged);
    // The tombstone stays: refunds of the source's purchases follow `mergedInto` to the target.
    tx.set(sRef, {
      ...(src ?? emptyUser()),
      balance: 0,
      mergedInto: caller.uid,
      mergedAt: Timestamp.fromMillis(deps.now()),
    });
    return { balance: base.balance + moved, moved };
  });

  try {
    await deps.auth.deleteUser(source.uid);
  } catch (e) {
    // The tombstone already blocks every use of the source account.
    deps.logger.warn("Could not delete merged anonymous user", { uidHash: uidHash(source.uid), error: String(e) });
  }
  const config = await loadRuntimeConfig(deps.db, deps.logger);
  return { ...result, costs: config.costs };
}

/**
 * Deletes the caller's account: tombstones the user doc (the balance is forfeited), detaches its
 * purchases (kept, as replay guards), deletes its generations, revokes Sign in with Apple when the
 * client sends a fresh authorization code, and deletes the Auth user.
 */
export async function deleteAccount(deps: Deps, caller: Caller, data: unknown): Promise<{ deleted: true; balance: 0 }> {
  const code = (data as { appleAuthorizationCode?: unknown } | null)?.appleAuthorizationCode;
  if (code !== undefined && code !== null && (typeof code !== "string" || code.length > 4096)) {
    fail("invalid-argument", "INVALID_REQUEST", "appleAuthorizationCode must be a string");
  }
  const nowS = Math.floor(deps.now() / 1000);
  if (!isAnonymous(caller.signInProvider) && nowS - caller.authTime > RECENT_SIGN_IN_SECONDS) {
    fail("failed-precondition", "RECENT_SIGN_IN_REQUIRED", "Sign in again to delete the account");
  }

  const uRef = userRef(deps.db, caller.uid);
  const mergedAway = await deps.db.runTransaction(async (tx) => {
    const user = (await tx.get(uRef)).data();
    // A merged-away account holds nothing, and its purchases must keep their uid so refunds still
    // follow `mergedInto` to the account that holds the credits now.
    if (user?.mergedInto !== undefined) return true;
    if (user?.deletedAt !== undefined) return false;
    // Deleting mid-generation would delete the pending reservation before it settles.
    if (user && user.inFlight > 0) fail("failed-precondition", "BUSY", "A recipe is being generated; try again shortly");
    tx.set(uRef, {
      ...(user ?? emptyUser()),
      balance: 0,
      forfeitedBalance: user?.balance ?? 0,
      minuteWindow: FieldValue.delete(),
      dayWindow: FieldValue.delete(),
      verifyWindow: FieldValue.delete(),
      infraRefunds: FieldValue.delete(),
      spendWindow: FieldValue.delete(),
      deletedAt: Timestamp.fromMillis(deps.now()),
    }, { merge: true });
    return false;
  });
  if (mergedAway) {
    await deps.auth.deleteUser(caller.uid).catch(() => undefined);
    return { deleted: true, balance: 0 };
  }

  const purchases = await deps.db.collection("purchases").where("uid", "==", caller.uid).get();
  for (let i = 0; i < purchases.docs.length; i += 400) {
    const batch = deps.db.batch();
    for (const d of purchases.docs.slice(i, i + 400)) batch.update(d.ref, { uid: null });
    await batch.commit();
  }
  await deps.db.recursiveDelete(uRef.collection("generations"));

  if (typeof code === "string" && code.length > 0) {
    try {
      await deps.appleRevoker.revokeWithAuthorizationCode(code);
    } catch (e) {
      deps.logger.warn("Sign in with Apple revocation failed", { error: String(e) });
    }
  }
  try {
    await deps.auth.deleteUser(caller.uid);
  } catch (e) {
    if ((e as { code?: string }).code !== "auth/user-not-found") throw e;
  }
  return { deleted: true, balance: 0 };
}
