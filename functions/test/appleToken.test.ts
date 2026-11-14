import { HttpsError } from "firebase-functions/v2/https";
import { randomUUID } from "node:crypto";
import { Timestamp } from "firebase-admin/firestore";
import { appleTokenContext } from "../src/constants";
import { deleteAccount, getBalance, mergeBalance } from "../src/core/account";
import { registerAppleSignIn } from "../src/core/appleToken";
import { emptyUser } from "../src/model";
import type { Caller } from "../src/ports";
import { anonymousUser, auth, callerFor, db, FakeRevoker, makeDeps, resetEmulators, type TestDeps } from "./helpers";

let deps: TestDeps;
/** Every code and token a test handed to the fakes: none may appear in any log line. */
let secrets: string[];

beforeEach(async () => {
  await resetEmulators();
  deps = makeDeps();
  secrets = [];
});

afterEach(() => {
  const logged = JSON.stringify(deps.logs);
  for (const s of secrets) expect(logged).not.toContain(s);
  expect(logged).not.toContain("refresh-for-");
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

function newCode(): string {
  const code = `code-${randomUUID()}`;
  secrets.push(code, FakeRevoker.tokenFor(code));
  return code;
}

/** An Auth user with Google, and Apple too unless `apple` is false. */
async function account(apple = true): Promise<string> {
  const u = await auth.createUser({ email: `${randomUUID()}@example.com` });
  await auth.updateUser(u.uid, { providerToLink: { providerId: "google.com", uid: `g-${u.uid}` } });
  if (apple) await auth.updateUser(u.uid, { providerToLink: { providerId: "apple.com", uid: `a-${u.uid}` } });
  return u.uid;
}

/** As the ID token says: signed in with `provider`, with `providers` linked, issued `issuedAt`. */
function caller(uid: string, provider: string, providers: string[], issuedAt = Math.floor(Date.now() / 1000)): Caller {
  return { ...callerFor(uid, provider), providers, issuedAt, authTime: issuedAt };
}

const iphone = (uid: string) => caller(uid, "apple.com", ["apple.com", "google.com"]);
const android = (uid: string) => caller(uid, "google.com", ["apple.com", "google.com"]);

async function userDoc(uid: string) {
  return (await db.collection("users").doc(uid).get()).data();
}

describe("registerAppleSignIn", () => {
  test("stores the refresh token encrypted, bound to the uid, and never in plain form", async () => {
    const uid = await account();
    const code = newCode();
    expect(await registerAppleSignIn(deps, iphone(uid), { appleAuthorizationCode: code })).toEqual({ registered: true });
    expect(deps.appleRevoker.exchanged).toEqual([code]);

    const doc = await userDoc(uid);
    const raw = JSON.stringify(doc);
    expect(raw).not.toContain(FakeRevoker.tokenFor(code));
    expect(raw).not.toContain(code);
    expect(doc?.appleToken.sealed).toMatchObject({ v: 1, kek: "local" });
    for (const f of ["wrappedKey", "iv", "ciphertext", "tag"]) expect(typeof doc?.appleToken.sealed[f]).toBe("string");
    expect(doc?.appleToken.storedAt).toBeInstanceOf(Timestamp);
    // It opens for this account only.
    expect(await deps.sealer.open(doc!.appleToken.sealed, appleTokenContext(uid))).toBe(FakeRevoker.tokenFor(code));
    await expect(deps.sealer.open(doc!.appleToken.sealed, appleTokenContext("someone-else"))).rejects.toThrow();
  });

  test("a later sign-in replaces the token without revoking the earlier one", async () => {
    const uid = await account();
    const first = newCode();
    const second = newCode();
    await registerAppleSignIn(deps, iphone(uid), { appleAuthorizationCode: first });
    await registerAppleSignIn(deps, iphone(uid), { appleAuthorizationCode: second });
    expect(deps.appleRevoker.revokedTokens).toEqual([]);
    const doc = await userDoc(uid);
    expect(await deps.sealer.open(doc!.appleToken.sealed, appleTokenContext(uid))).toBe(FakeRevoker.tokenFor(second));
  });

  test("a caller without the Apple provider is refused and nothing is exchanged", async () => {
    const uid = await account(false);
    const code = newCode();
    expect(await reason(registerAppleSignIn(deps, caller(uid, "google.com", ["google.com"]), { appleAuthorizationCode: code })))
      .toBe("APPLE_NOT_LINKED");
    const anon = await anonymousUser();
    expect(await reason(registerAppleSignIn(deps, callerFor(anon.uid, "anonymous"), { appleAuthorizationCode: code })))
      .toBe("APPLE_NOT_LINKED");
    expect(deps.appleRevoker.exchanged).toEqual([]);
    expect((await userDoc(uid))?.appleToken).toBeUndefined();
  });

  test("an ID token from before the link is checked against Auth, which has Apple", async () => {
    const uid = await account();
    const code = newCode();
    expect(await registerAppleSignIn(deps, caller(uid, "google.com", ["google.com"]), { appleAuthorizationCode: code }))
      .toEqual({ registered: true });
  });

  test("a code Apple refuses, or Apple being down, stores nothing", async () => {
    const uid = await account();
    const used = newCode();
    deps.appleRevoker.rejectedCodes.add(used);
    expect(await reason(registerAppleSignIn(deps, iphone(uid), { appleAuthorizationCode: used }))).toBe("APPLE_CODE_REJECTED");
    deps.appleRevoker.down = true;
    expect(await reason(registerAppleSignIn(deps, iphone(uid), { appleAuthorizationCode: newCode() }))).toBe("APPLE_UNAVAILABLE");
    expect((await userDoc(uid))?.appleToken).toBeUndefined();
  });

  test("the key service failing stores nothing and does not revoke", async () => {
    const uid = await account();
    deps.sealer = { seal: async () => Promise.reject(new Error("Cloud KMS encrypt failed (403)")), open: deps.sealer.open };
    expect(await reason(registerAppleSignIn(deps, iphone(uid), { appleAuthorizationCode: newCode() }))).toBe("APPLE_UNAVAILABLE");
    expect((await userDoc(uid))?.appleToken).toBeUndefined();
    expect(deps.appleRevoker.revokedTokens).toEqual([]);
  });

  test("bad input and closed accounts are refused", async () => {
    const uid = await account();
    expect(await reason(registerAppleSignIn(deps, iphone(uid), {}))).toBe("INVALID_REQUEST");
    expect(await reason(registerAppleSignIn(deps, iphone(uid), { appleAuthorizationCode: "" }))).toBe("INVALID_REQUEST");
    expect(await reason(registerAppleSignIn(deps, iphone(uid), { appleAuthorizationCode: "x".repeat(4097) }))).toBe("INVALID_REQUEST");
    await db.collection("users").doc(uid).set({ ...emptyUser(), deletedAt: Timestamp.now() });
    expect(await reason(registerAppleSignIn(deps, iphone(uid), { appleAuthorizationCode: newCode() }))).toBe("ACCOUNT_CLOSED");
    expect(deps.appleRevoker.exchanged).toEqual([]);
  });
});

describe("deleteAccount and the stored Apple token", () => {
  test("deleting from Android, with no fresh code, revokes the stored token and removes it", async () => {
    const uid = await account();
    const code = newCode();
    await registerAppleSignIn(deps, iphone(uid), { appleAuthorizationCode: code });
    expect(await deleteAccount(deps, android(uid), {})).toEqual({ deleted: true, balance: 0, appleLinkRemoved: true });
    expect(deps.appleRevoker.revokedTokens).toEqual([FakeRevoker.tokenFor(code)]);
    const doc = await userDoc(uid);
    expect(doc?.deletedAt).toBeDefined();
    expect(doc?.appleToken).toBeUndefined();
  });

  test("a fresh code from the iPhone is preferred; the stored token is removed all the same", async () => {
    const uid = await account();
    await registerAppleSignIn(deps, iphone(uid), { appleAuthorizationCode: newCode() });
    const fresh = newCode();
    expect(await deleteAccount(deps, iphone(uid), { appleAuthorizationCode: fresh })).toMatchObject({ appleLinkRemoved: true });
    expect(deps.appleRevoker.codes).toEqual([fresh]);
    expect(deps.appleRevoker.revokedTokens).toEqual([]);
    expect((await userDoc(uid))?.appleToken).toBeUndefined();
  });

  test("a fresh code Apple refuses falls back to the stored token", async () => {
    const uid = await account();
    const stored = newCode();
    await registerAppleSignIn(deps, iphone(uid), { appleAuthorizationCode: stored });
    const fresh = newCode();
    deps.appleRevoker.rejectedCodes.add(fresh);
    expect(await deleteAccount(deps, iphone(uid), { appleAuthorizationCode: fresh })).toMatchObject({ appleLinkRemoved: true });
    expect(deps.appleRevoker.revokedTokens).toEqual([FakeRevoker.tokenFor(stored)]);
  });

  test("when Apple's revocation fails the deletion still completes and says the link stayed", async () => {
    const uid = await account();
    await registerAppleSignIn(deps, iphone(uid), { appleAuthorizationCode: newCode() });
    deps.appleRevoker.failRevoke = true;
    expect(await deleteAccount(deps, android(uid), {})).toEqual({ deleted: true, balance: 0, appleLinkRemoved: false });
    expect((await userDoc(uid))?.appleToken).toBeUndefined();
    await expect(auth.getUser(uid)).rejects.toMatchObject({ code: "auth/user-not-found" });
    expect(deps.logs.some((l) => l.message === "apple_token_revoke_failed")).toBe(true);
  });

  test("an Apple account from before tokens were stored, deleted on Android, gets appleLinkRemoved: false", async () => {
    const uid = await account();
    expect(await deleteAccount(deps, android(uid), {})).toEqual({ deleted: true, balance: 0, appleLinkRemoved: false });
  });

  test("an account without Apple gets no appleLinkRemoved at all", async () => {
    const uid = await account(false);
    expect(await deleteAccount(deps, caller(uid, "google.com", ["google.com"]), {})).toEqual({ deleted: true, balance: 0 });
  });

  test("a token copied onto another account does not open there", async () => {
    const victim = await account();
    await registerAppleSignIn(deps, iphone(victim), { appleAuthorizationCode: newCode() });
    const other = await account();
    const sealed = (await userDoc(victim))!.appleToken;
    await db.collection("users").doc(other).set({ ...emptyUser(), appleToken: sealed });
    expect(await deleteAccount(deps, android(other), {})).toMatchObject({ appleLinkRemoved: false });
    expect(deps.appleRevoker.revokedTokens).toEqual([]);
    expect((await userDoc(other))?.appleToken).toBeUndefined();
  });

  test("a merged-away record never keeps a token", async () => {
    const uid = await account();
    await registerAppleSignIn(deps, iphone(uid), { appleAuthorizationCode: newCode() });
    await db.collection("users").doc(uid).update({ mergedInto: "elsewhere" });
    expect(await deleteAccount(deps, android(uid), {})).toMatchObject({ appleLinkRemoved: true });
    expect((await userDoc(uid))?.appleToken).toBeUndefined();
  });
});

describe("unlinking Apple and merging", () => {
  test("getBalance after Apple was unlinked removes and revokes the token", async () => {
    const uid = await account();
    const code = newCode();
    await registerAppleSignIn(deps, iphone(uid), { appleAuthorizationCode: code });
    await auth.updateUser(uid, { providersToUnlink: ["apple.com"] });
    const later = Math.floor(Date.now() / 1000) + 5;
    await getBalance(deps, caller(uid, "google.com", ["google.com"], later));
    expect((await userDoc(uid))?.appleToken).toBeUndefined();
    expect(deps.appleRevoker.revokedTokens).toEqual([FakeRevoker.tokenFor(code)]);
  });

  test("an ID token issued before the token was stored, or Auth still linking Apple, changes nothing", async () => {
    const uid = await account();
    await registerAppleSignIn(deps, iphone(uid), { appleAuthorizationCode: newCode() });
    // Stale: issued before the registration.
    await getBalance(deps, caller(uid, "google.com", ["google.com"], Math.floor(Date.now() / 1000) - 60));
    // Fresh token without the claim, but Auth still has Apple.
    await getBalance(deps, caller(uid, "google.com", ["google.com"], Math.floor(Date.now() / 1000) + 5));
    // The claim lists Apple: no Auth read at all.
    await getBalance(deps, iphone(uid));
    expect((await userDoc(uid))?.appleToken).toBeDefined();
    expect(deps.appleRevoker.revokedTokens).toEqual([]);
  });

  test("a merged anonymous source's tombstone keeps no token", async () => {
    const anon = await anonymousUser();
    const target = await account();
    // An anonymous record cannot register one; written directly to prove the tombstone drops it.
    const sealed = await deps.sealer.seal("refresh-for-planted", appleTokenContext(anon.uid));
    await db.collection("users").doc(anon.uid).set({ ...emptyUser(), balance: 3, appleToken: { sealed, storedAt: Timestamp.now() } });
    await mergeBalance(deps, iphone(target), { sourceIdToken: anon.idToken });
    const tomb = await userDoc(anon.uid);
    expect(tomb?.mergedInto).toBe(target);
    expect(tomb?.appleToken).toBeUndefined();
    expect(deps.appleRevoker.revokedTokens).toEqual(["refresh-for-planted"]);
    // The target's own record gained nothing.
    expect((await userDoc(target))?.appleToken).toBeUndefined();
  });
});
