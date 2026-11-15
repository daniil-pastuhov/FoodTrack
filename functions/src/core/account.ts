/** getBalance, mergeBalance, deleteAccount, and clearing the name and photo Auth keeps. */
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { CAPABILITIES, fail } from "../contract";
import { RECENT_SIGN_IN_SECONDS, type Costs } from "../constants";
import { assertOpen, emptyUser, uidHash, userRef, utcDay, type UserDoc } from "../model";
import type { Caller, Deps } from "../ports";
import { blockFlag, mergeKeys } from "./buyers";
import { APPLE_PROVIDER, dropTokenIfAppleUnlinked, removeAppleLink, revokeStored } from "./appleToken";

type Block = { purchasesBlocked?: true; acceptsOptionalIngredients?: true };

/** uid:authTime pairs this instance already handled, so a token's later calls cost no read. Bounded. */
const scrubbed = new Set<string>();
const SCRUBBED_MAX = 10_000;

/**
 * Clears the display name and photo URL Firebase Auth stores for a signed-in (non-anonymous) user:
 * Google sign-in always supplies them, and they are not wanted. Runs when the ID token still
 * carries a `name` / `picture` claim (no Auth read otherwise), at most once per sign-in: the user
 * doc remembers `profileScrubbedAt`, and a later sign-in (newer `auth_time`) that brought them back
 * is cleared again. `force` (mergeBalance's target) clears without looking at the claims. Best
 * effort: never fails the call, and never logs either value.
 */
export async function scrubProfile(deps: Deps, caller: Caller, force = false): Promise<boolean> {
  if (caller.signInProvider === "anonymous" || caller.signInProvider === "") return false;
  if (!force && !caller.profileClaims) return false;
  const memo = `${caller.uid}:${caller.authTime}`;
  if (!force && scrubbed.has(memo)) return false;
  const ref = userRef(deps.db, caller.uid);
  try {
    const user = (await ref.get()).data();
    if (user && (user.deletedAt !== undefined || user.mergedInto !== undefined)) return false;
    const last = user?.profileScrubbedAt ? Math.floor(user.profileScrubbedAt.toMillis() / 1000) : -1;
    if (!force && last >= caller.authTime) {
      remember(memo);
      return false;
    }
    await deps.auth.updateUser(caller.uid, { displayName: null, photoURL: null });
    const at = Timestamp.fromMillis(deps.now());
    await deps.db.runTransaction(async (tx) => {
      const snap = await tx.get(ref);
      if (snap.exists) tx.update(ref, { profileScrubbedAt: at });
      else tx.set(ref, { ...emptyUser(), createdAt: at, profileScrubbedAt: at });
    });
    remember(memo);
    return true;
  } catch (e) {
    deps.logger.warn("profile_scrub_failed", { uidHash: uidHash(caller.uid), code: String((e as { code?: string }).code ?? "unknown").slice(0, 60) });
    return false;
  }
}

function remember(memo: string): void {
  if (scrubbed.size >= SCRUBBED_MAX) scrubbed.clear();
  scrubbed.add(memo);
}

export async function getBalance(deps: Deps, caller: Caller): Promise<{ balance: number; costs: Costs } & Block> {
  const [snap, config] = await Promise.all([userRef(deps.db, caller.uid).get(), deps.config()]);
  const user = snap.data();
  // Apple unlinked since its token was stored: the token goes (and is revoked). No extra read
  // unless the ID token says Apple is gone.
  if (user?.appleToken) await dropTokenIfAppleUnlinked(deps, caller, user.appleToken);
  return {
    balance: user && user.deletedAt === undefined && user.mergedInto === undefined ? user.balance : 0,
    costs: config.costs,
    ...CAPABILITIES, ...(await blockFlag(deps.db, user, config)),
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
): Promise<{ balance: number; moved: number; costs: Costs } & Block> {
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
      timeoutRefundsUsed: (base.timeoutRefundsUsed ?? 0) + (src?.timeoutRefundsUsed ?? 0),
      // The block follows the merge: the source's buyers are now this account's buyers.
      buyerKeys: mergeKeys(base.buyerKeys, src?.buyerKeys),
    };
    if (merged.buyerKeys!.length === 0) delete merged.buyerKeys;
    const addCount = (x: { day: string; count: number }, y: { count: number }) => ({ day, count: x.count + y.count });
    const dayWindow = mergeDay(day, base.dayWindow, src?.dayWindow, addCount);
    const infraRefunds = mergeDay(day, base.infraRefunds, src?.infraRefunds, addCount);
    const spendWindow = mergeDay(day, base.spendWindow, src?.spendWindow, (x, y) => ({ day, microUsd: x.microUsd + y.microUsd }));
    if (dayWindow) merged.dayWindow = dayWindow;
    if (infraRefunds) merged.infraRefunds = infraRefunds;
    if (spendWindow) merged.spendWindow = spendWindow;
    tx.set(tRef, merged);
    // The tombstone stays: refunds of the source's purchases follow `mergedInto` to the target.
    // It never keeps an Apple token (an anonymous source should have none; one that does is
    // revoked below).
    const { appleToken: sourceAppleToken, ...sourceRest } = src ?? emptyUser();
    tx.set(sRef, {
      ...sourceRest,
      balance: 0,
      mergedInto: caller.uid,
      mergedAt: Timestamp.fromMillis(deps.now()),
    });
    return { balance: base.balance + moved, moved, target: merged, sourceAppleToken };
  });
  if (result.sourceAppleToken) await revokeStored(deps, source.uid, result.sourceAppleToken, "merged_source");

  try {
    await deps.auth.deleteUser(source.uid);
  } catch (e) {
    // The tombstone already blocks every use of the source account.
    deps.logger.warn("Could not delete merged anonymous user", { uidHash: uidHash(source.uid), error: String(e).slice(0, 200) });
  }
  await scrubProfile(deps, caller, true);
  const config = await deps.config();
  return { balance: result.balance, moved: result.moved, costs: config.costs, ...CAPABILITIES, ...(await blockFlag(deps.db, result.target, config)) };
}

/**
 * Deletes the caller's account: tombstones the user doc (the balance is forfeited), detaches its
 * purchases (kept, as replay guards), deletes its generations, removes Sign in with Apple's link
 * (with a fresh authorization code when the client sends one, else with the stored refresh
 * token), and deletes the Auth user. The stored token is gone from the record either way.
 * `appleLinkRemoved` is present only for an account that carried Apple: false when neither way
 * worked, so the client can tell the person how to remove the link themselves. Apple's answer
 * never blocks the deletion.
 */
export async function deleteAccount(
  deps: Deps,
  caller: Caller,
  data: unknown,
): Promise<{ deleted: true; balance: 0; appleLinkRemoved?: boolean }> {
  const code = (data as { appleAuthorizationCode?: unknown } | null)?.appleAuthorizationCode;
  if (code !== undefined && code !== null && (typeof code !== "string" || code.length > 4096)) {
    fail("invalid-argument", "INVALID_REQUEST", "appleAuthorizationCode must be a string");
  }
  const nowS = Math.floor(deps.now() / 1000);
  if (!isAnonymous(caller.signInProvider) && nowS - caller.authTime > RECENT_SIGN_IN_SECONDS) {
    fail("failed-precondition", "RECENT_SIGN_IN_REQUIRED", "Sign in again to delete the account");
  }

  const freshCode = typeof code === "string" && code.length > 0 ? code : undefined;
  return eraseAccount(deps, caller.uid, caller.providers, freshCode);
}

/**
 * The deletion itself, after deleteAccount's checks of the caller (also the entry point of the
 * owner's admin command, src/admin, which checks the person by email instead). `providers`: the
 * sign-in providers linked to the account (the ID token's, or the Auth record's).
 */
export async function eraseAccount(
  deps: Deps,
  uid: string,
  providers: string[],
  freshCode: string | undefined,
): Promise<{ deleted: true; balance: 0; appleLinkRemoved?: boolean }> {
  const uRef = userRef(deps.db, uid);
  const { mergedAway, storedApple } = await deps.db.runTransaction(async (tx) => {
    const user = (await tx.get(uRef)).data();
    const storedApple = user?.appleToken;
    // A merged-away account holds nothing, and its purchases must keep their uid so refunds still
    // follow `mergedInto` to the account that holds the credits now.
    if (user?.mergedInto !== undefined) {
      if (storedApple) tx.update(uRef, { appleToken: FieldValue.delete() });
      return { mergedAway: true, storedApple };
    }
    if (user?.deletedAt !== undefined) {
      if (storedApple) tx.update(uRef, { appleToken: FieldValue.delete() });
      return { mergedAway: false, storedApple };
    }
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
      // The tombstone never keeps the token; it is revoked below.
      appleToken: FieldValue.delete(),
      deletedAt: Timestamp.fromMillis(deps.now()),
    }, { merge: true });
    return { mergedAway: false, storedApple };
  });
  const carriesApple = providers.includes(APPLE_PROVIDER) || storedApple !== undefined || freshCode !== undefined;
  if (mergedAway) {
    const removed = await removeAppleLink(deps, uid, freshCode, storedApple);
    await deps.auth.deleteUser(uid).catch(() => undefined);
    return { deleted: true, balance: 0, ...(carriesApple ? { appleLinkRemoved: removed } : {}) };
  }

  const purchases = await deps.db.collection("purchases").where("uid", "==", uid).get();
  for (let i = 0; i < purchases.docs.length; i += 400) {
    const batch = deps.db.batch();
    // `formerUid` keeps the ledger: a refund after deletion is still attributed (refund after use,
    // consumption answers), while the null `uid` keeps the purchase from being redeemed again.
    for (const d of purchases.docs.slice(i, i + 400)) batch.update(d.ref, { uid: null, formerUid: uid });
    await batch.commit();
  }
  await deps.db.recursiveDelete(uRef.collection("generations"));

  const removed = carriesApple ? await removeAppleLink(deps, uid, freshCode, storedApple) : false;
  try {
    await deps.auth.deleteUser(uid);
  } catch (e) {
    if ((e as { code?: string }).code !== "auth/user-not-found") throw e;
  }
  return { deleted: true, balance: 0, ...(carriesApple ? { appleLinkRemoved: removed } : {}) };
}
