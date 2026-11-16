/**
 * The welcome grant (README "Welcome grant", CONTRACT.md `claimWelcome`, `welcomeRecipes`):
 * `config/runtime.welcome.recipes` free recipes for signing in with Google or Apple, once per
 * sign-in identity, ever. It is the one way a balance rises without a store receipt, so:
 *
 * - The guard is `welcomes/{key}`, one document per identity (the same key derivation as a
 *   linked sign-in's buyer key, `providerBuyerKey`), created in the same transaction that raises
 *   the balance and never deleted. An account linked to both Google and Apple writes both keys,
 *   and an account that already claimed records any identity it has since gained.
 * - At most `welcome.perDay` grants per UTC day across all accounts (`stats/{day}.welcomeGrants`,
 *   read inside the grant transaction, so concurrent claims cannot overshoot it).
 * - Free recipes are spent like bought ones but are not purchases: they go to `welcomeCredits`,
 *   never to `purchasedCredits` (no refund allowance), are left out of the FIFO ledger (spent
 *   first), and never count as revenue (`netCreditsGranted`).
 * - The amount is told two ways: getWelcomeOffer, the one call the apps make before any sign-in or
 *   purchase (no account needed, nothing about the caller read or stored), and `welcomeRecipes`
 *   beside every `balance` (welcomeFlag) for an account that already exists.
 */
import { FieldValue, Timestamp, type Transaction } from "firebase-admin/firestore";
import { capabilitiesOf, fail, type WelcomeClaimResponse, type WelcomeOfferResponse, type WelcomeStatus } from "../contract";
import { CONFIG_CACHE_MS } from "../constants";
import {
  assertOpen,
  emptyUser,
  isClosed,
  statsRef,
  uidHash,
  userRef,
  utcDay,
  welcomeRef,
  type RuntimeConfig,
  type UserDoc,
} from "../model";
import type { Caller, Deps } from "../ports";
import { blockFlag, providerBuyerKey } from "./buyers";
import { LogSampler } from "./guard";

/** The sign-in providers whose identity can claim. Anything else (anonymous, password, phone) cannot. */
export const WELCOME_PROVIDERS: readonly string[] = ["google.com", "apple.com"];

/** The `welcomes/{key}` of one identity: the hash of the provider's own user id (no personal data). */
export const welcomeKey = (providerId: string, providerUid: string) => providerBuyerKey(providerId, providerUid);

const num = (v: unknown) => (typeof v === "number" && Number.isFinite(v) ? v : 0);

/** Whether grants are being made at all (before the day's counter). */
function welcomeOn(config: RuntimeConfig): boolean {
  return config.welcome.recipes > 0 && config.welcome.perDay > 0 && config.generationEnabled;
}

export async function readDayGrants(deps: Pick<Deps, "db">, day: string): Promise<number> {
  return num((await statsRef(deps.db, day).get()).data()?.welcomeGrants);
}

/**
 * Today's grant count cached per instance for `ttlMs`, so neither a flood of getWelcomeOffer calls
 * nor the `welcomeRecipes` field of every response that carries `balance` costs more than one
 * read per instance per 30 s. Concurrent loads of the same day share one read (a load in flight
 * for one day is never handed to a call for another, around midnight). A failed read rejects and
 * is not cached; welcomeFlag turns that into `welcomeRecipes: 0`, so it never fails a response.
 * The field may lag the day's brake by up to `ttlMs`; claimWelcome reads the counter inside its
 * own transaction.
 */
export class WelcomeDayCounter {
  private day = "";
  private value = 0;
  private loadedAt = Number.NEGATIVE_INFINITY;
  private readonly inFlight = new Map<string, Promise<number>>();

  constructor(
    private readonly read: (day: string) => Promise<number>,
    private readonly now: () => number,
    private readonly ttlMs = CONFIG_CACHE_MS,
  ) {}

  grants(day: string): Promise<number> {
    if (this.day === day && this.now() - this.loadedAt < this.ttlMs) return Promise.resolve(this.value);
    const pending = this.inFlight.get(day);
    if (pending) return pending;
    const load = this.read(day).then(
      (v) => {
        this.day = day;
        this.value = v;
        this.loadedAt = this.now();
        this.inFlight.delete(day);
        return v;
      },
      (e: unknown) => {
        this.inFlight.delete(day);
        throw e;
      },
    );
    this.inFlight.set(day, load);
    return load;
  }
}

/**
 * `welcomeRecipes` for every response that carries `balance`: how many free recipes a claim
 * grants now, 0 when the grant is off (`recipes` or `perDay` 0, or generation paused) or today's
 * grants are used up. The same for every account. Reads only the runtime config and today's
 * count (through `deps.welcomeDayGrants`, cached in production; afresh without it).
 *
 * It is a hint beside an answer that is already decided, and most callers await it after their
 * transaction has committed (a grant, a settled generation, a verified purchase, a merge). So a
 * failed read of the day's count never fails the response: it answers 0 and logs one warning.
 * A claim reads the count inside its own transaction, so a 0 here cannot cause a wrong grant.
 */
const countUnavailableLog = new LogSampler(60_000, 1);

export async function welcomeFlag(deps: Deps, config: RuntimeConfig): Promise<{ welcomeRecipes: number }> {
  if (!welcomeOn(config)) return { welcomeRecipes: 0 };
  try {
    const day = utcDay(deps.now());
    const grants = deps.welcomeDayGrants ? await deps.welcomeDayGrants(day) : await readDayGrants(deps, day);
    return { welcomeRecipes: grants >= config.welcome.perDay ? 0 : config.welcome.recipes };
  } catch (e) {
    // At most one line a minute per instance, however many responses carry the field meanwhile.
    const sample = countUnavailableLog.take(deps.now());
    if (sample.log) {
      deps.logger.warn("welcome_count_unavailable", {
        error: (e instanceof Error ? e.message : String(e)).slice(0, 200), suppressedSinceLast: sample.suppressed,
      });
    }
    return { welcomeRecipes: 0 };
  }
}

/**
 * getWelcomeOffer: the same number as `welcomeRecipes`, for an install with no account yet. Takes
 * no input and reads nothing about the caller: the cached runtime config and today's count only.
 */
export async function getWelcomeOffer(deps: Deps): Promise<WelcomeOfferResponse> {
  const config = await deps.config();
  return { recipes: (await welcomeFlag(deps, config)).welcomeRecipes };
}

export interface Identity {
  provider: string;
  key: string;
}

/** The Google and Apple identities among an Auth record's providers, sorted by key. */
function identitiesFrom(providerData: readonly { providerId?: string; uid?: string }[]): Identity[] {
  const out = new Map<string, Identity>();
  for (const p of providerData) {
    if (!p.providerId || !p.uid || !WELCOME_PROVIDERS.includes(p.providerId)) continue;
    const key = welcomeKey(p.providerId, p.uid);
    out.set(key, { provider: p.providerId, key });
  }
  return [...out.values()].sort((a, b) => (a.key < b.key ? -1 : 1));
}

/** A uid's Google and Apple identities from its Auth record; [] when there is no record. */
export async function authIdentities(deps: Deps, uid: string): Promise<Identity[]> {
  try {
    return identitiesFrom((await deps.auth.getUser(uid)).providerData);
  } catch (e) {
    if ((e as { code?: string }).code === "auth/user-not-found") return [];
    throw e;
  }
}

/**
 * For an account that has claimed (`welcomeAt`): reads, inside `tx` and before any write, the
 * `welcomes/` documents of `ids`, and returns the writer that creates the missing ones (credits 0)
 * and keeps `welcomeProviders` current. So an identity linked after the claim is recorded as soon
 * as the server sees it (claimWelcome, getBalance, registerAppleSignIn, mergeBalance, deletion),
 * and can never claim again from another account. A no-op for an account that never claimed.
 */
export async function prepareWelcomeRecord(
  tx: Transaction,
  deps: Deps,
  uid: string,
  user: UserDoc | undefined,
  ids: readonly Identity[],
): Promise<(opts?: { updateUser?: boolean }) => number> {
  if (!user || user.welcomeAt === undefined || ids.length === 0) return () => 0;
  const snaps = await tx.getAll(...ids.map((id) => welcomeRef(deps.db, id.key)));
  return ({ updateUser = true } = {}) => {
    const at = Timestamp.fromMillis(deps.now());
    let created = 0;
    ids.forEach((id, i) => {
      if (snaps[i].exists) return;
      tx.create(welcomeRef(deps.db, id.key), { uid, provider: id.provider, credits: 0, at });
      created++;
    });
    const providers = [...new Set([...(user.welcomeProviders ?? []), ...ids.map((id) => id.provider)])].sort();
    if (updateUser && providers.join() !== [...(user.welcomeProviders ?? [])].sort().join()) {
      tx.update(userRef(deps.db, uid), { welcomeProviders: providers });
    }
    return created;
  };
}

/**
 * Records the identities an account that claimed has gained since (see prepareWelcomeRecord), in
 * its own transaction, from the Auth record. Best effort for the callers that only observe a link
 * (getBalance, registerAppleSignIn, mergeBalance): it never throws, and logs what it did.
 */
export async function recordWelcomeIdentities(deps: Deps, uid: string, via: string): Promise<number> {
  try {
    const ids = await authIdentities(deps, uid);
    if (ids.length === 0) return 0;
    const created = await deps.db.runTransaction(async (tx) => {
      const user = (await tx.get(userRef(deps.db, uid))).data();
      if (!user || isClosed(user)) return 0;
      const write = await prepareWelcomeRecord(tx, deps, uid, user, ids);
      return write();
    });
    if (created > 0) deps.logger.info("welcome_identities_recorded", { uidHash: uidHash(uid), recorded: created, via });
    return created;
  } catch (e) {
    deps.logger.warn("welcome_identity_record_failed", { uidHash: uidHash(uid), via, error: String(e).slice(0, 200) });
    return 0;
  }
}

/**
 * Whether the ID token names a Google or Apple provider the claimed account has not recorded yet
 * (`welcomeProviders`): the cheap signal, from data already at hand, that recordWelcomeIdentities
 * is worth an Auth lookup.
 */
export function hasUnrecordedProvider(user: UserDoc | undefined, caller: Caller): boolean {
  if (!user || user.welcomeAt === undefined || isClosed(user)) return false;
  const known = user.welcomeProviders ?? [];
  return caller.providers.some((p) => WELCOME_PROVIDERS.includes(p) && !known.includes(p));
}

function signInRequired(): never {
  fail("failed-precondition", "SIGN_IN_REQUIRED", "Sign in with Google or Apple to get free recipes");
}

/**
 * The caller's Google and Apple identities, from the Auth record (authoritative; the ID token's
 * list can be an hour stale). An anonymous caller whose token names neither is refused without
 * any read.
 */
async function identitiesOf(deps: Deps, caller: Caller): Promise<Identity[]> {
  const tokenHasOne = caller.providers.some((p) => WELCOME_PROVIDERS.includes(p));
  if (!tokenHasOne && (caller.signInProvider === "anonymous" || caller.signInProvider === "")) signInRequired();
  const ids = await authIdentities(deps, caller.uid);
  if (ids.length === 0) signInRequired();
  return ids;
}

type ClaimOutcome = {
  status: WelcomeStatus;
  granted: number;
  user: UserDoc | undefined;
  /** OFF because today's grants are used up. */
  dayLimited?: boolean;
  /** This grant was the day's last one. */
  reachedLimit?: boolean;
  /** This grant took the day's count to half of `perDay` (rounded up). */
  reachedHalf?: boolean;
  /** The day's grants after this one. */
  grantsToday?: number;
  /** Identities recorded for an account that had already claimed. */
  recorded?: number;
};

/** Refused claims at the day's limit: one log line a minute per instance, with a suppressed count. */
const dayLimitRefusals = new LogSampler(60_000, 1);

/**
 * claimWelcome: grants `welcome.recipes` to a caller signed in with Google or Apple whose
 * identities have never claimed (ALREADY otherwise, also when called again), unless the grant is
 * off or today's are used up (OFF). One transaction reads the account, every identity's
 * `welcomes/` document and (only for a grant) today's counter, then writes all of them, so two
 * concurrent claims, or a claim and a merge, can never both act on the same state.
 */
export async function claimWelcome(deps: Deps, caller: Caller): Promise<WelcomeClaimResponse> {
  const identities = await identitiesOf(deps, caller);
  const config = await deps.config();
  const { recipes, perDay } = config.welcome;
  const day = utcDay(deps.now());

  const outcome = await deps.db.runTransaction(async (tx): Promise<ClaimOutcome> => {
    const now = Timestamp.fromMillis(deps.now());
    const uRef = userRef(deps.db, caller.uid);
    const [uSnap, ...wSnaps] = await Promise.all([
      tx.get(uRef),
      ...identities.map((id) => tx.get(welcomeRef(deps.db, id.key))),
    ]);
    const user = uSnap.data();
    assertOpen(user);

    if (user?.welcomeAt !== undefined || wSnaps.some((s) => s.exists)) {
      // Already had: record the identities that have no document yet, so none of them can claim
      // again from another account (an identity unlinked from this one and signed in elsewhere).
      let recorded = 0;
      identities.forEach((id, i) => {
        if (wSnaps[i].exists) return;
        tx.create(welcomeRef(deps.db, id.key), { uid: caller.uid, provider: id.provider, credits: 0, at: now });
        recorded++;
      });
      if (user?.welcomeAt !== undefined) {
        const providers = [...new Set([...(user.welcomeProviders ?? []), ...identities.map((id) => id.provider)])].sort();
        if (providers.join() !== [...(user.welcomeProviders ?? [])].sort().join()) tx.update(uRef, { welcomeProviders: providers });
      }
      return { status: "ALREADY", granted: 0, user, recorded };
    }
    if (!welcomeOn(config)) return { status: "OFF", granted: 0, user };

    const sRef = statsRef(deps.db, day);
    const grants = num((await tx.get(sRef)).data()?.welcomeGrants);
    if (grants >= perDay) return { status: "OFF", granted: 0, user, dayLimited: true };

    for (const id of identities) {
      tx.create(welcomeRef(deps.db, id.key), { uid: caller.uid, provider: id.provider, credits: recipes, at: now });
    }
    const base: UserDoc = user ?? { ...emptyUser(), createdAt: now };
    const next: UserDoc = {
      ...base,
      balance: base.balance + recipes,
      welcomeCredits: (base.welcomeCredits ?? 0) + recipes,
      // Spent first by the charges after the grant (generation settle), never before it.
      welcomeUnspent: (base.welcomeUnspent ?? 0) + recipes,
      welcomeAt: now,
      welcomeProviders: [...new Set(identities.map((id) => id.provider))].sort(),
    };
    tx.set(uRef, next);
    // Not revenue: never `netCreditsGranted`, so the spend ceiling does not rise with free recipes.
    tx.set(sRef, { welcomeGrants: FieldValue.increment(1), welcomeCredits: FieldValue.increment(recipes) }, { merge: true });
    const half = Math.ceil(perDay / 2);
    return {
      status: "GRANTED", granted: recipes, user: next, grantsToday: grants + 1,
      reachedLimit: grants + 1 >= perDay, reachedHalf: grants < half && grants + 1 >= half,
    };
  });

  if (outcome.status === "GRANTED") {
    deps.logger.info("welcome_granted", { uidHash: uidHash(caller.uid), amount: outcome.granted, identities: identities.length });
    // Each fires on exactly one grant a day: the count rises by one per committed grant.
    if (outcome.reachedHalf) deps.logger.warn("welcome_threshold", { percent: 50, grants: outcome.grantsToday, perDay, day });
    if (outcome.reachedLimit) deps.logger.warn("welcome_day_limit", { day, perDay, event: "reached" });
  } else if (outcome.dayLimited) {
    const sample = dayLimitRefusals.take(deps.now());
    if (sample.log) deps.logger.warn("welcome_day_limit", { day, perDay, event: "refused", suppressedSinceLast: sample.suppressed });
  }

  return {
    status: outcome.status,
    granted: outcome.granted,
    balance: outcome.user?.balance ?? 0,
    costs: config.costs,
    ...capabilitiesOf(config),
    ...(await blockFlag(deps.db, outcome.user, config)),
    ...(await welcomeFlag(deps, config)),
  };
}
