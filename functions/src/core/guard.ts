/**
 * Checks that run before any Firestore read or store/LLM call: App Check replay and cheap
 * in-memory throttles per uid and per client IP. The throttles are per instance (no shared state,
 * so they cost nothing); with maxInstances they bound how fast one source can drive invocations
 * into Firestore. The money guards (reservation, rate windows, spend breaker) are in Firestore.
 */
import { fail } from "../contract";

const OVERFLOW = "\u0000overflow";

/** Fixed-window counter per key, with a bounded number of keys. */
export class WindowLimiter {
  private windowStart = 0;
  private counts = new Map<string, number>();

  constructor(
    private readonly limit: number,
    private readonly windowMs: number,
    private readonly maxKeys = 20_000,
  ) {}

  /** Counts one hit for `key`; false when the key is over its limit in the current window. */
  hit(key: string, nowMs: number): boolean {
    const start = Math.floor(nowMs / this.windowMs) * this.windowMs;
    if (start !== this.windowStart) {
      this.windowStart = start;
      this.counts.clear();
    }
    // A flood of distinct keys cannot grow memory without bound: past the cap, new keys share one
    // counter with a larger limit.
    const overflow = !this.counts.has(key) && this.counts.size >= this.maxKeys;
    const k = overflow ? OVERFLOW : key;
    const n = (this.counts.get(k) ?? 0) + 1;
    this.counts.set(k, n);
    return n <= (overflow ? this.limit * 50 : this.limit);
  }
}

/**
 * The client address as Google's front end saw it: the last X-Forwarded-For entry (entries before
 * it are client-supplied and spoofable), else the socket address.
 */
export function clientIp(headers: Record<string, string | string[] | undefined>, socketIp: string | undefined): string {
  const raw = headers["x-forwarded-for"];
  const xff = Array.isArray(raw) ? raw.join(",") : raw;
  const last = xff?.split(",").map((s) => s.trim()).filter(Boolean).pop();
  return last || socketIp || "unknown";
}

/**
 * With consumeAppCheckToken on, firebase-functions verifies and consumes the token but lets a
 * replayed one through with `alreadyConsumed: true`; the handler has to refuse it.
 */
export function assertFreshAppCheck(app: { alreadyConsumed?: boolean } | undefined, consume: boolean): void {
  if (consume && app?.alreadyConsumed !== false) {
    fail("unauthenticated", "APP_CHECK_REPLAYED", "App Check token already used");
  }
}

/** Throttles shared by every callable on an instance. Generous for people, tight for scripts. */
export const PER_UID_PER_MINUTE = 30;
export const PER_IP_PER_MINUTE = 120;

export class CallThrottle {
  private readonly uid = new WindowLimiter(PER_UID_PER_MINUTE, 60_000);
  private readonly ip = new WindowLimiter(PER_IP_PER_MINUTE, 60_000);

  admit(uid: string, ip: string, nowMs: number): void {
    // Both are counted, so neither key can be used to dodge the other.
    const ipOk = this.ip.hit(ip, nowMs);
    const uidOk = this.uid.hit(uid, nowMs);
    if (!ipOk || !uidOk) fail("resource-exhausted", "RATE_LIMITED", "Too many requests");
  }
}

/** Logs at most one line per window, with how many were suppressed, so a flood cannot flood the logs. */
export class LogSampler {
  private last = Number.NEGATIVE_INFINITY;
  private suppressed = 0;
  constructor(private readonly windowMs: number) {}
  take(nowMs: number): { log: boolean; suppressed: number } {
    if (nowMs - this.last >= this.windowMs) {
      const suppressed = this.suppressed;
      this.last = nowMs;
      this.suppressed = 0;
      return { log: true, suppressed };
    }
    this.suppressed++;
    return { log: false, suppressed: this.suppressed };
  }
}
