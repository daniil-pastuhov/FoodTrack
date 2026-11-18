/**
 * The owner console's callables in front of the core (src/core/providerSwitch.ts): what runs
 * before any token check of ours (README "The owner console").
 *
 * - A call without a Firebase ID token (firebase-functions has already checked its signature; the
 *   core checks it again with revocation) is refused with SIGN_IN and not counted, so calls
 *   without a token can't use up anything.
 * - Everyone else is counted per client address (IPv6 by /64; OWNER_PER_ADDRESS_PER_MINUTE) and
 *   then in one instance ceiling (OWNER_OTHERS_PER_MINUTE), which with one instance bounds what
 *   the two functions cost.
 * - A listed owner is counted in a bucket of their own (OWNER_LISTED_PER_MINUTE per uid), so
 *   nobody else's calls can use up the owner's share: a uid in `config/owner.uids` (the document
 *   cached OWNER_LIST_CACHE_MS), a Google sign-in whose token email is in `config/owner.emails`,
 *   or a uid the core has already let in on this instance. This picks a bucket only: the core
 *   still decides who is an owner.
 * - Refusals are logged as `owner_call_refused`: a fixed reason code (refusalReason) and, past
 *   the token check, the caller's uidHash; never the uid or email. Those before anything is counted
 *   or at the throttle (SIGN_IN without a token, RATE_LIMITED) are sampled to
 *   OWNER_REFUSED_LOGS_PER_MINUTE; every other one (NOT_OWNER, INVALID_REQUEST, WEB_LIMIT, ...) is
 *   logged, since the throttle already bounds how many there can be. Reasons other than NOT_OWNER,
 *   SIGN_IN and RATE_LIMITED come from a listed owner's session and are alerted on
 *   (alerts/policies/owner-refusals.json).
 */
import type { Firestore } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { fail } from "../contract";
import { uidHash } from "../model";
import type { Logger } from "../ports";
import { LogSampler, WindowLimiter, clientIp, ipKey } from "./guard";
import { OWNER_DOC, OWNER_LIST_MAX, SwitchRefused, normaliseOwnerEmail, parseOwnerDoc } from "./providerSwitch";

export const OWNER_PER_ADDRESS_PER_MINUTE = 5;
export const OWNER_OTHERS_PER_MINUTE = 30;
export const OWNER_LISTED_PER_MINUTE = 30;
export const OWNER_LIST_CACHE_MS = 60_000;
export const OWNER_REFUSED_LOGS_PER_MINUTE = 10;

/**
 * The fixed reason code of a refusal: SwitchRefused's reason, an HttpsError's details.reason when
 * it is a code, else its code (INVALID_ARGUMENT, ...); null for anything that is not a refusal (an
 * internal failure, logged as callable_failed instead). Never text from the request.
 */
export function refusalReason(e: unknown): string | null {
  if (e instanceof SwitchRefused) return e.reason;
  if (!(e instanceof HttpsError)) return null;
  const r = (e.details as { reason?: unknown } | undefined)?.reason;
  if (typeof r === "string" && /^[A-Z][A-Z_]{1,39}$/.test(r)) return r;
  return e.code.toUpperCase().replace(/-/g, "_");
}

/** The part of a CallableRequest this reads. */
export interface OwnerCallRequest {
  auth?: { uid: string; token?: { email?: unknown; firebase?: { sign_in_provider?: unknown } } };
  rawRequest: { headers: Record<string, string | string[] | undefined>; socket?: { remoteAddress?: string } };
}

export interface OwnerCallEnv {
  db: Firestore;
  logger: Logger;
  now(): number;
}

export class OwnerCallGate {
  private readonly perAddress = new WindowLimiter(OWNER_PER_ADDRESS_PER_MINUTE, 60_000);
  private readonly others = new WindowLimiter(OWNER_OTHERS_PER_MINUTE, 60_000, 1);
  private readonly listed = new WindowLimiter(OWNER_LISTED_PER_MINUTE, 60_000, OWNER_LIST_MAX * 2);
  private readonly refusedLog = new LogSampler(60_000, OWNER_REFUSED_LOGS_PER_MINUTE);
  /** Uids the core has let in on this instance (at most the allowlist's size in practice). */
  private readonly admitted = new Set<string>();
  private list: { atMs: number; uids: string[]; emails: string[] } | null = null;

  constructor(private readonly env: OwnerCallEnv) {}

  private async ownerList(nowMs: number): Promise<{ uids: string[]; emails: string[] }> {
    if (this.list && nowMs - this.list.atMs < OWNER_LIST_CACHE_MS) return this.list;
    let parsed: { uids: string[]; emails: string[] } | null = null;
    try {
      parsed = parseOwnerDoc((await this.env.db.doc(OWNER_DOC).get()).data() ?? null);
    } catch {
      // Unreadable: nobody gets the owner's bucket until the next read (the core refuses anyway).
    }
    this.list = { atMs: nowMs, uids: parsed?.uids ?? [], emails: parsed?.emails ?? [] };
    return this.list;
  }

  /** Sampled: refusals before anything is counted, or at the throttle. */
  private refused(name: string, reason: string, nowMs: number): void {
    const sample = this.refusedLog.take(nowMs);
    // The reason only: never the caller's uid or email.
    if (sample.log) this.env.logger.warn("owner_call_refused", { function: name, reason, suppressedSinceLast: sample.suppressed });
  }

  /** Every one: refusals of a call the throttle let through (so bounded by it). */
  private refusedCounted(name: string, reason: string, uid: string): void {
    this.env.logger.warn("owner_call_refused", { function: name, reason, uidHash: uidHash(uid) });
  }

  async run<T>(name: string, request: OwnerCallRequest, fn: (authorization: string | undefined) => Promise<T>): Promise<T> {
    const nowMs = this.env.now();
    const raw = request.rawRequest;
    const auth = request.auth;
    if (!auth?.uid) {
      this.refused(name, "SIGN_IN", nowMs);
      throw new SwitchRefused("unauthenticated", "SIGN_IN", "Sign in with Google");
    }
    const owners = await this.ownerList(nowMs);
    const email = auth.token?.email;
    const ownerBucket = this.admitted.has(auth.uid) || owners.uids.includes(auth.uid) ||
      (auth.token?.firebase?.sign_in_provider === "google.com" && typeof email === "string" && owners.emails.includes(normaliseOwnerEmail(email)));
    const admitted = ownerBucket
      ? this.listed.hit(auth.uid, nowMs)
      // Both are counted, so a flood from many addresses still meets the ceiling.
      : [this.perAddress.hit(ipKey(clientIp(raw.headers, raw.socket?.remoteAddress)), nowMs), this.others.hit("all", nowMs)].every(Boolean);
    if (!admitted) {
      this.refused(name, "RATE_LIMITED", nowMs);
      fail("resource-exhausted", "RATE_LIMITED", "Too many requests");
    }
    try {
      const out = await fn(raw.headers.authorization as string | undefined);
      this.admitted.add(auth.uid);
      return out;
    } catch (e) {
      const reason = refusalReason(e);
      if (reason) this.refusedCounted(name, reason, auth.uid);
      throw e;
    }
  }
}
