/**
 * Checks that run before any Firestore read or store/LLM call: App Check replay and cheap
 * in-memory throttles per uid and per client IP. The throttles are per instance (no shared state,
 * so they cost nothing); with maxInstances they bound how fast one source can drive invocations
 * into Firestore. The money guards (reservation, rate windows, spend breaker) are in Firestore.
 */
import { isIP } from "node:net";
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
 * The client address as Google's front end saw it: the last X-Forwarded-For entry, else the socket
 * address. Google's Cloud Functions header reference says the first entry is "generally" the
 * client, but it does not say a client-sent X-Forwarded-For is discarded, and on Cloud Run (which
 * runs 2nd-gen functions) a client-sent value is kept and Google's front end appends the address
 * it saw: entries before the last are whatever the caller wrote. With no load balancer of ours in
 * front, the last entry is the only one the caller cannot choose.
 */
export function clientIp(headers: Record<string, string | string[] | undefined>, socketIp: string | undefined): string {
  const raw = headers["x-forwarded-for"];
  const xff = Array.isArray(raw) ? raw.join(",") : raw;
  const last = xff?.split(",").map((s) => s.trim()).filter(Boolean).pop();
  return last || socketIp || "unknown";
}

/**
 * The throttle key of an address: an IPv4 address as is, an IPv6 address as its /64 (one
 * subscriber usually gets a whole /64, so per-address keys would be free to rotate). IPv4-mapped
 * IPv6 addresses count as IPv4.
 */
export function ipKey(ip: string): string {
  const mapped = /^::ffff:(\d+\.\d+\.\d+\.\d+)$/i.exec(ip);
  if (mapped) return mapped[1];
  if (isIP(ip) !== 6) return ip;
  const [head, tail = ""] = ip.toLowerCase().split("::");
  const left = head ? head.split(":") : [];
  const right = tail ? tail.split(":") : [];
  // An embedded IPv4 tail ("::1.2.3.4") takes two groups.
  const width = (gs: string[]) => gs.reduce((n, g) => n + (g.includes(".") ? 2 : 1), 0);
  const groups = ip.includes("::") ? [...left, ...Array(Math.max(0, 8 - width(left) - width(right))).fill("0"), ...right] : left;
  return `${groups.slice(0, 4).map((g) => g.replace(/^0+(?=.)/, "")).join(":")}::/64`;
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

/**
 * The per-instance throttle of one function: per uid, per client address (IPv6 by /64), and a
 * ceiling on all calls the instance lets through to Firestore per minute. The ceiling is what bounds
 * the function's worst-case Firestore bill (maxInstances x ceiling); see README "Function limits".
 * Every limit here is per instance, so service-wide it is maxInstances x the limit: a function with
 * more instances passes a smaller per-uid limit so one account still gets the same share in total.
 */
export class CallThrottle {
  private readonly uid: WindowLimiter;
  private readonly ip = new WindowLimiter(PER_IP_PER_MINUTE, 60_000);
  private readonly all: WindowLimiter;

  constructor(perInstancePerMinute = Number.MAX_SAFE_INTEGER, perUidPerMinute = PER_UID_PER_MINUTE) {
    this.all = new WindowLimiter(perInstancePerMinute, 60_000, 1);
    this.uid = new WindowLimiter(perUidPerMinute, 60_000);
  }

  admit(uid: string, ip: string, nowMs: number): void {
    // Both are counted, so neither key can be used to dodge the other.
    const ipOk = this.ip.hit(ipKey(ip), nowMs);
    const uidOk = this.uid.hit(uid, nowMs);
    if (!ipOk || !uidOk) fail("resource-exhausted", "RATE_LIMITED", "Too many requests");
    if (!this.all.hit("all", nowMs)) fail("resource-exhausted", "RATE_LIMITED", "Too many requests");
  }
}

/**
 * Logs at most `perWindow` lines per window, with how many were suppressed, so a flood cannot flood
 * the logs.
 */
export class LogSampler {
  private windowStart = Number.NEGATIVE_INFINITY;
  private taken = 0;
  private suppressed = 0;
  constructor(private readonly windowMs: number, private readonly perWindow = 1) {}
  take(nowMs: number): { log: boolean; suppressed: number } {
    if (nowMs - this.windowStart >= this.windowMs) {
      this.windowStart = nowMs;
      this.taken = 0;
    }
    if (this.taken < this.perWindow) {
      this.taken++;
      const suppressed = this.suppressed;
      this.suppressed = 0;
      return { log: true, suppressed };
    }
    this.suppressed++;
    return { log: false, suppressed: this.suppressed };
  }
}
