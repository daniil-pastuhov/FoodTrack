/**
 * Deletion requests that arrive by email, without the app (README "Deletion requests by email"):
 * finding the account a request is about, and deleting it with the same core function as the
 * `deleteAccount` callable (eraseAccount). Run by the owner through src/admin/cli.ts, never deployed
 * as a function.
 *
 * Nothing here prints or logs an email address, a purchase token, an Apple token or code: a summary
 * carries uids, provider ids, counts and dates only.
 */
import type { UserRecord } from "firebase-admin/auth";
import { Timestamp } from "firebase-admin/firestore";
import { MERGE_CHAIN_MAX, SWEEP_GRACE_MS } from "../constants";
import { purchaseRef, uidHash, userRef, type GenerationDoc, type PurchaseDoc, type UserDoc } from "../model";
import type { AppleTransaction, Deps } from "../ports";
import { eraseAccount } from "../core/account";
import { logLlmCall, settle, type Outcome } from "../core/generation";
import { applePurchaseId, playPurchaseId } from "../core/purchases";

export const REQUEST_KINDS = ["email", "play-order", "apple-order", "apple-transaction", "uid"] as const;
export type RequestKind = (typeof REQUEST_KINDS)[number];

export interface Lookup {
  kind: RequestKind;
  value: string;
}

/** Store lookups the admin command needs beyond Deps. Production: src/admin/stores.ts. */
export interface AdminStoreLookups {
  /** Play Developer API orders.get: the purchase token of an order, null when Play doesn't know the order. */
  playOrderToken(orderId: string): Promise<string | null>;
  /** App Store Server API "Look Up Order ID": the order's signature-verified transactions ([] for an unknown order). */
  appleOrderTransactions(orderId: string): Promise<AppleTransaction[]>;
}

/** Thrown for a request the command refuses; the message is printed as is (it holds no personal data). */
export class AdminRefusal extends Error {}

export interface Match {
  uid: string;
  /** How the account was found, e.g. "Auth email", "purchase play_1a2b…". No personal data. */
  via: string;
}

export interface FindResult {
  matches: Match[];
  /** Things the operator should know (a purchase nobody redeemed, a Play order found only through Play). */
  notes: string[];
}

export type AccountState = "live" | "deleted" | "merged" | "no record";

export interface AccountSummary {
  uid: string;
  uidHash: string;
  state: AccountState;
  authUser: boolean;
  anonymous: boolean;
  /** Linked sign-in providers (`google.com`, `apple.com`), from the Auth record. */
  providers: string[];
  /** Auth creation time, else the user doc's createdAt; ISO 8601. */
  createdAt: string | null;
  balance: number;
  /** Purchases with this uid, and purchases a deletion detached from it (formerUid). */
  purchases: { linked: number; detached: number };
  /**
   * Up to PURCHASES_LISTED of them (linked first), to check a request against the person's
   * receipt: store, product, date granted, state.
   */
  purchaseList: { store: string; productId: string | null; grantedAt: string | null; state: string; detached: boolean }[];
  inFlight: number;
  pendingGenerations: { requestId: string; deadlineAt: string; pastDeadline: boolean }[];
  appleTokenStored: boolean;
  deletedAt?: string;
  mergedInto?: string;
  /** Where `mergedInto` leads, one hop per entry, ending at the account that holds the credits now. */
  mergeChain: { uid: string; state: AccountState }[];
}

const PURCHASES_LISTED = 20;
const iso = (t:Timestamp | undefined | null) => (t ? t.toDate().toISOString() : null);
const short = (id: string) => (id.length > 24 ? `${id.slice(0, 20)}…` : id);

function stateOf(user: UserDoc | undefined, auth: UserRecord | null): AccountState {
  if (user?.mergedInto !== undefined) return "merged";
  if (user?.deletedAt !== undefined) return "deleted";
  return user || auth ? "live" : "no record";
}

async function authUser(deps: Deps, uid: string): Promise<UserRecord | null> {
  try {
    return await deps.auth.getUser(uid);
  } catch (e) {
    if ((e as { code?: string }).code === "auth/user-not-found") return null;
    throw e;
  }
}

/** Every Auth user whose own email or one of whose provider entries' email is `email` (case-insensitive). */
async function usersByEmail(deps: Deps, email: string): Promise<Match[]> {
  try {
    const u = await deps.auth.getUserByEmail(email);
    return [{ uid: u.uid, via: "Auth email" }];
  } catch (e) {
    if ((e as { code?: string }).code !== "auth/user-not-found") throw e;
  }
  // No account has it as its own email: look through the provider entries (a provider email that
  // never became the account's, e.g. Google linked to an account made with Apple).
  const want = email.toLowerCase();
  const out: Match[] = [];
  let pageToken: string | undefined;
  do {
    const page = await deps.auth.listUsers(1000, pageToken);
    for (const u of page.users) {
      const p = u.providerData.find((d) => d.email?.toLowerCase() === want);
      if (p) out.push({ uid: u.uid, via: `provider email (${p.providerId})` });
    }
    pageToken = page.pageToken;
  } while (pageToken);
  return out;
}

/** The account a purchase belongs (or belonged) to, or a note why there is none. */
function ownerOf(id: string, doc: PurchaseDoc | undefined, notes: string[]): Match[] {
  if (!doc) return [];
  const owner = doc.uid ?? doc.formerUid;
  if (!owner) {
    notes.push(`purchase ${short(id)} was never redeemed by an account (refund seen first): no account holds data for it`);
    return [];
  }
  return [{ uid: owner, via: `purchase ${short(id)}${doc.uid ? "" : " (detached by an earlier deletion)"}` }];
}

const dedupe = (ms: Match[]) => ms.filter((m, i) => ms.findIndex((x) => x.uid === m.uid) === i);

export async function findAccounts(deps: Deps, stores: AdminStoreLookups, lookup: Lookup): Promise<FindResult> {
  const value = lookup.value.trim();
  if (!value) throw new AdminRefusal(`--${lookup.kind} needs a value`);
  const notes: string[] = [];
  switch (lookup.kind) {
    case "uid":
      return { matches: [{ uid: value, via: "uid" }], notes };
    case "email":
      return { matches: await usersByEmail(deps, value), notes };
    case "play-order": {
      // Every Play purchase doc stores the order id Play reported at verification or refund.
      const q = await deps.db.collection("purchases").where("orderId", "==", value).get();
      let matches = q.docs
        .filter((d) => (d.data() as PurchaseDoc).store === "play")
        .flatMap((d) => ownerOf(d.id, d.data() as PurchaseDoc, notes));
      if (q.empty) {
        // A doc without the order id (Play reported none): ask Play for the order's purchase token.
        const token = await stores.playOrderToken(value);
        if (token) {
          const id = playPurchaseId(token);
          const doc = (await purchaseRef(deps.db, id).get()).data();
          if (doc) notes.push("found through Play's Orders API: the purchase doc has no order id");
          else notes.push("Play knows the order, but it was never sent to the server: no account holds data for it");
          matches = ownerOf(id, doc, notes);
        }
      }
      return { matches: dedupe(matches), notes };
    }
    case "apple-order": {
      const txs = await stores.appleOrderTransactions(value);
      if (txs.length === 0) notes.push("Apple returned no transactions for this order id");
      const matches: Match[] = [];
      for (const tx of txs) {
        const id = applePurchaseId(tx.environment, tx.transactionId);
        const doc = (await purchaseRef(deps.db, id).get()).data();
        if (!doc) notes.push(`transaction ${tx.transactionId} of the order was never sent to the server`);
        matches.push(...ownerOf(id, doc, notes));
      }
      return { matches: dedupe(matches), notes };
    }
    case "apple-transaction": {
      const matches: Match[] = [];
      for (const env of ["production", "sandbox"] as const) {
        const id = applePurchaseId(env, value);
        matches.push(...ownerOf(id, (await purchaseRef(deps.db, id).get()).data(), notes));
      }
      return { matches: dedupe(matches), notes };
    }
  }
}

export async function describeAccount(deps: Deps, uid: string): Promise<AccountSummary> {
  const ref = userRef(deps.db, uid);
  const purchasesOf = (field: "uid" | "formerUid") =>
    deps.db.collection("purchases").where(field, "==", uid).limit(PURCHASES_LISTED).get();
  const [snap, auth, linked, detached, pending, linkedDocs, detachedDocs] = await Promise.all([
    ref.get(),
    authUser(deps, uid),
    deps.db.collection("purchases").where("uid", "==", uid).count().get(),
    deps.db.collection("purchases").where("formerUid", "==", uid).count().get(),
    ref.collection("generations").where("state", "==", "pending").get(),
    purchasesOf("uid"),
    purchasesOf("formerUid"),
  ]);
  const listed = (docs: typeof linkedDocs.docs, isDetached: boolean) => docs.map((d) => {
    const p = d.data() as PurchaseDoc;
    return { store: p.store, productId: p.productId, grantedAt: iso(p.grantedAt), state: p.state, detached: isDetached };
  });
  const user = snap.data();
  const now = deps.now();
  const summary: AccountSummary = {
    uid,
    uidHash: uidHash(uid),
    state: stateOf(user, auth),
    authUser: auth !== null,
    anonymous: auth !== null && auth.providerData.length === 0,
    providers: auth?.providerData.map((p) => p.providerId) ?? [],
    createdAt: auth?.metadata.creationTime ? new Date(auth.metadata.creationTime).toISOString() : iso(user?.createdAt),
    balance: user?.balance ?? 0,
    purchases: { linked: linked.data().count, detached: detached.data().count },
    purchaseList: [...listed(linkedDocs.docs, false), ...listed(detachedDocs.docs, true)].slice(0, PURCHASES_LISTED),
    inFlight: user?.inFlight ?? 0,
    pendingGenerations: pending.docs.map((d) => {
      const g = d.data() as GenerationDoc;
      return { requestId: d.id, deadlineAt: iso(g.deadlineAt)!, pastDeadline: g.deadlineAt.toMillis() + SWEEP_GRACE_MS < now };
    }),
    appleTokenStored: user?.appleToken !== undefined,
    mergeChain: [],
  };
  if (user?.deletedAt) summary.deletedAt = iso(user.deletedAt)!;
  if (user?.mergedInto !== undefined) summary.mergedInto = user.mergedInto;
  let next = user?.mergedInto;
  for (let i = 0; next !== undefined && i < MERGE_CHAIN_MAX; i++) {
    const hop = (await userRef(deps.db, next).get()).data();
    summary.mergeChain.push({ uid: next, state: stateOf(hop, await authUser(deps, next)) });
    next = hop?.mergedInto;
  }
  return summary;
}

export interface DeletionPlan {
  /** Set when the deletion must not run now; nothing is changed. */
  refusal?: string;
  /** What --apply changes, in order. */
  steps: string[];
  /** What stays, and why. */
  kept: string[];
}

export function planDeletion(s: AccountSummary): DeletionPlan {
  const steps: string[] = [];
  const kept = [
    "purchases/ documents (replay guard against redeeming a store purchase twice; refunds still attributed through formerUid)",
    "buyers/ documents (refund-after-use record; no personal data)",
    "the users/ tombstone (deletedAt, forfeitedBalance, credit counters, buyerKeys; every transaction checks it)",
  ];
  if (s.state === "no record") return { refusal: "No user doc and no Auth user with this uid: nothing to delete.", steps, kept: [] };
  const fresh = s.pendingGenerations.filter((g) => !g.pastDeadline);
  if (fresh.length > 0) {
    return {
      refusal: `BUSY: ${fresh.length} generation(s) in flight until ${fresh.map((g) => g.deadlineAt).join(", ")}. Run again a few minutes later.`,
      steps,
      kept: [],
    };
  }
  const stale = s.pendingGenerations.length;
  if (s.state === "live" && s.inFlight > stale) {
    return {
      refusal: `inFlight is ${s.inFlight} but only ${stale} generation(s) are pending: the counter is off. Look at users/${s.uid} by hand first.`,
      steps,
      kept: [],
    };
  }
  if (stale > 0) steps.push(`settle ${stale} generation(s) left pending past their deadline, as the sweeper would (infrastructure failure, refunded by the usual rules)`);
  if (s.state === "live") {
    steps.push(`tombstone users/${s.uid}: balance ${s.balance} forfeited (forfeitedBalance), rate windows cleared, deletedAt set`);
    steps.push(`detach ${s.purchases.linked} purchase(s): uid set to null, formerUid kept`);
    steps.push("delete the account's generations (users/{uid}/generations)");
  } else if (s.state === "merged") {
    steps.push(`none on the tombstone: merged into ${s.mergedInto}, whose account holds the credits and purchases' refunds; its purchases keep their uid`);
  } else {
    steps.push(`none on the tombstone: already deleted at ${s.deletedAt}`);
  }
  if (s.appleTokenStored) steps.push("revoke the stored Sign in with Apple token at Apple and remove it from the user doc");
  else if (s.providers.includes("apple.com")) {
    steps.push("Apple is linked but no token is stored: Apple's link can't be removed by the server (tell the person to remove it in their Apple Account settings)");
  }
  steps.push(s.authUser ? "delete the Firebase Auth user (with its provider entries and email)" : "no Firebase Auth user left to delete");
  steps.push("mark the tombstone with adminDeletionAt and adminDeletionKind");
  return { steps, kept };
}

export interface DeleteOutcome {
  summary: AccountSummary;
  plan: DeletionPlan;
  applied: boolean;
  appleLinkRemoved?: boolean;
}

/**
 * Plans the deletion of `uid` and, with `apply`, performs it through eraseAccount (the callable's
 * core). `confirm` must equal `uid` for `apply`; a mismatch refuses even a dry run.
 */
export async function deleteForRequest(
  deps: Deps,
  uid: string,
  kind: RequestKind,
  options: { apply: boolean; confirm?: string },
): Promise<DeleteOutcome> {
  if (options.confirm !== undefined && options.confirm !== uid) {
    throw new AdminRefusal(`--confirm ${options.confirm} does not match the account found (${uid}). Nothing was changed.`);
  }
  if (options.apply && options.confirm === undefined) {
    throw new AdminRefusal(`--apply needs --confirm ${uid}. Nothing was changed.`);
  }
  const summary = await describeAccount(deps, uid);
  const plan = planDeletion(summary);
  if (plan.refusal) throw new AdminRefusal(plan.refusal);
  if (!options.apply) return { summary, plan, applied: false };

  if (summary.pendingGenerations.length > 0) {
    const config = await deps.config();
    for (const g of summary.pendingGenerations) {
      const gen = (await userRef(deps.db, uid).collection("generations").doc(g.requestId).get()).data() as GenerationDoc;
      const outcome: Outcome = { ok: false, failureClass: "infra", reason: "invocation died (admin deletion)", swept: true };
      const res = await settle(deps, uid, g.requestId, outcome, config);
      if (res.settledHere) logLlmCall(deps, uid, gen, outcome, res, null);
    }
  }
  const result = await eraseAccount(deps, uid, summary.providers, undefined);
  // Not on UserDoc: only this command writes or reads them (README "Firestore").
  await deps.db.collection("users").doc(uid).set(
    { adminDeletionAt: Timestamp.fromMillis(deps.now()), adminDeletionKind: kind },
    { merge: true },
  );
  deps.logger.info("admin_account_deleted", {
    uidHash: summary.uidHash,
    kind,
    state: summary.state,
    purchasesDetached: summary.state === "live" ? summary.purchases.linked : 0,
    ...(result.appleLinkRemoved !== undefined ? { appleLinkRemoved: result.appleLinkRemoved } : {}),
  });
  return { summary, plan, applied: true, appleLinkRemoved: result.appleLinkRemoved };
}
