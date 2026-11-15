/**
 * The Sign in with Apple refresh token the server keeps per account, so that Apple's link to the
 * app can be removed when the account is deleted from either platform (Android has no Sign in
 * with Apple to get a fresh authorization code from).
 *
 * - registerAppleSignIn: the iOS app sends the authorization code of an Apple sign-in or link; the
 *   server exchanges it at Apple for a refresh token and stores it envelope-encrypted on the user
 *   doc (`appleToken`), replacing an earlier one.
 * - deleteAccount revokes it (or a fresh code, preferred) and removes it; so do unlinking Apple
 *   (noticed by getBalance) and every tombstone (mergeBalance's source).
 *
 * Neither a code nor a token is ever logged or returned.
 */
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { fail } from "../contract";
import { appleTokenContext } from "../constants";
import { emptyUser, uidHash, userRef, type StoredAppleToken } from "../model";
import { AppleTokenError, type Caller, type Deps } from "../ports";

export const APPLE_PROVIDER = "apple.com";

function errorKind(e: unknown): string {
  return e instanceof AppleTokenError ? e.kind : "other";
}

/** The message of an error from our own adapters (which never put a code or token in one), bounded. */
function safeMessage(e: unknown): string {
  return (e instanceof Error ? e.message : String(e)).slice(0, 160);
}

/** Whether the Auth user has Apple linked now: the ID token's claim, confirmed with Auth when it says no. */
async function hasAppleNow(deps: Deps, caller: Caller): Promise<boolean> {
  if (caller.providers.includes(APPLE_PROVIDER)) return true;
  try {
    const user = await deps.auth.getUser(caller.uid);
    return user.providerData.some((p) => p.providerId === APPLE_PROVIDER);
  } catch {
    return false;
  }
}

/** Revokes a stored token, best effort. Returns whether Apple confirmed. */
export async function revokeStored(deps: Deps, uid: string, stored: StoredAppleToken, why: string): Promise<boolean> {
  try {
    const token = await deps.sealer.open(stored.sealed, appleTokenContext(uid));
    await deps.appleRevoker.revokeRefreshToken(token);
    return true;
  } catch (e) {
    deps.logger.warn("apple_token_revoke_failed", { uidHash: uidHash(uid), why, kind: errorKind(e), error: safeMessage(e) });
    return false;
  }
}

/**
 * registerAppleSignIn: `{ appleAuthorizationCode }` from an Apple sign-in or link on iOS. The
 * caller must have Apple linked. Exchanges the code (single use, five minutes) for a refresh
 * token and stores it encrypted. Errors: APPLE_NOT_LINKED, APPLE_CODE_REJECTED (Apple refused the
 * code: expired, used, foreign), APPLE_UNAVAILABLE (Apple or the key service unreachable),
 * ACCOUNT_CLOSED. The client ignores every failure and tries again at the next Apple sign-in.
 */
export async function registerAppleSignIn(deps: Deps, caller: Caller, data: unknown): Promise<{ registered: true }> {
  const code = (data as { appleAuthorizationCode?: unknown } | null)?.appleAuthorizationCode;
  if (typeof code !== "string" || code.length === 0 || code.length > 4096) {
    fail("invalid-argument", "INVALID_REQUEST", "appleAuthorizationCode must be a non-empty string");
  }
  if (!(await hasAppleNow(deps, caller))) {
    fail("failed-precondition", "APPLE_NOT_LINKED", "Sign in with Apple is not linked to this account");
  }
  const ref = userRef(deps.db, caller.uid);
  const before = (await ref.get()).data();
  if (before && (before.deletedAt !== undefined || before.mergedInto !== undefined)) {
    fail("failed-precondition", "ACCOUNT_CLOSED", "The account was deleted or merged");
  }

  let token: string;
  try {
    token = await deps.appleRevoker.exchangeAuthorizationCode(code);
  } catch (e) {
    deps.logger.warn("apple_code_exchange_failed", { uidHash: uidHash(caller.uid), kind: errorKind(e), error: safeMessage(e) });
    if (e instanceof AppleTokenError && e.kind === "rejected") {
      fail("failed-precondition", "APPLE_CODE_REJECTED", "Apple refused the authorization code");
    }
    fail("unavailable", "APPLE_UNAVAILABLE", "Apple could not be reached; try at the next sign-in");
  }

  let stored: StoredAppleToken;
  try {
    stored = { sealed: await deps.sealer.seal(token, appleTokenContext(caller.uid)), storedAt: Timestamp.fromMillis(deps.now()) };
  } catch (e) {
    // Not kept, and not revoked either: revoking would sign the person out of Apple's side of
    // this app. The next Apple sign-in tries again.
    deps.logger.error("apple_token_seal_failed", { uidHash: uidHash(caller.uid), error: safeMessage(e) });
    fail("unavailable", "APPLE_UNAVAILABLE", "The token could not be stored; try at the next sign-in");
  }

  const closed = await deps.db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const user = snap.data();
    if (user && (user.deletedAt !== undefined || user.mergedInto !== undefined)) return true;
    // An earlier token is replaced, not revoked: revoking it could end the authorization the new
    // one belongs to.
    if (snap.exists) tx.update(ref, { appleToken: stored });
    else tx.set(ref, { ...emptyUser(), createdAt: stored.storedAt, appleToken: stored });
    return false;
  });
  if (closed) {
    // Deleted meanwhile: nothing may keep a token for it.
    await revokeStored(deps, caller.uid, stored, "closed_during_register");
    fail("failed-precondition", "ACCOUNT_CLOSED", "The account was deleted or merged");
  }
  deps.logger.info("apple_token_registered", { uidHash: uidHash(caller.uid), replaced: before?.appleToken !== undefined });
  return { registered: true };
}

/**
 * Removes Apple's link at deletion: a fresh authorization code first (iOS sends one), else the
 * stored token. Never throws. Returns whether Apple confirmed a revocation.
 */
export async function removeAppleLink(
  deps: Deps,
  uid: string,
  freshCode: string | undefined,
  stored: StoredAppleToken | undefined,
): Promise<boolean> {
  if (freshCode) {
    try {
      await deps.appleRevoker.revokeWithAuthorizationCode(freshCode);
      return true;
    } catch (e) {
      deps.logger.warn("apple_code_revoke_failed", { uidHash: uidHash(uid), kind: errorKind(e), error: safeMessage(e) });
    }
  }
  if (stored) return revokeStored(deps, uid, stored, "delete");
  return false;
}

/**
 * Called where the user doc is already read (getBalance): a stored token on an account whose ID
 * token, issued after the token was stored, no longer lists Apple. Confirmed with Auth before
 * anything changes (a stale ID token could predate the link); then the token is removed and
 * revoked. Best effort; never throws.
 */
export async function dropTokenIfAppleUnlinked(deps: Deps, caller: Caller, stored: StoredAppleToken | undefined): Promise<void> {
  if (!stored || caller.providers.includes(APPLE_PROVIDER)) return;
  if (caller.issuedAt * 1000 <= stored.storedAt.toMillis()) return;
  try {
    let linked: boolean;
    try {
      const user = await deps.auth.getUser(caller.uid);
      linked = user.providerData.some((p) => p.providerId === APPLE_PROVIDER);
    } catch (e) {
      if ((e as { code?: string }).code !== "auth/user-not-found") return;
      linked = false;
    }
    if (linked) return;
    const ref = userRef(deps.db, caller.uid);
    const removed = await deps.db.runTransaction(async (tx) => {
      const current = (await tx.get(ref)).data()?.appleToken;
      // Only the token that was seen: a newer registration stays.
      if (!current || current.storedAt.toMillis() !== stored.storedAt.toMillis()) return false;
      tx.update(ref, { appleToken: FieldValue.delete() });
      return true;
    });
    if (removed) {
      await revokeStored(deps, caller.uid, stored, "unlinked");
      deps.logger.info("apple_token_removed_unlinked", { uidHash: uidHash(caller.uid) });
    }
  } catch (e) {
    deps.logger.warn("apple_unlink_check_failed", { uidHash: uidHash(caller.uid), error: safeMessage(e) });
  }
}
