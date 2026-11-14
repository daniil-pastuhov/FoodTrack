/** Pre-Firestore guards (App Check replay, per-instance throttles) and the request hash. */
import { HttpsError } from "firebase-functions/v2/https";
import { CallThrottle, LogSampler, WindowLimiter, assertFreshAppCheck, clientIp } from "../src/core/guard";
import { inputHashOf, validateGenerateRequest } from "../src/contract";

const today = new Date().toISOString().slice(0, 10);
const base = {
  requestId: "0b5e7d4e-8f3c-4d6a-9a1b-2c3d4e5f6a7b",
  kind: "recipe",
  config: { difficulty: "easy", detail: "brief", servings: 2, maxMinutes: null, mealType: "any", diet: "none", language: "en" },
  ingredients: [{ name: "Tomat", expiresOn: today, quantity: 3 }],
  allergens: ["sesame"],
  allergenConsent: true,
  today,
};

describe("guards", () => {
  test("a replayed or missing App Check token is refused when consumption is on", () => {
    expect(() => assertFreshAppCheck({ alreadyConsumed: true }, true)).toThrow(HttpsError);
    expect(() => assertFreshAppCheck(undefined, true)).toThrow(HttpsError);
    expect(() => assertFreshAppCheck({ alreadyConsumed: false }, true)).not.toThrow();
    expect(() => assertFreshAppCheck({ alreadyConsumed: true }, false)).not.toThrow();
  });

  test("the per-instance limiter counts per key per window and caps its memory", () => {
    const l = new WindowLimiter(2, 60_000, 3);
    expect([l.hit("a", 0), l.hit("a", 1), l.hit("a", 2)]).toEqual([true, true, false]);
    expect(l.hit("b", 3)).toBe(true);
    expect(l.hit("a", 60_000)).toBe(true);
    for (const k of ["k1", "k2"]) l.hit(k, 60_001);
    // Past 3 keys, new keys share one counter with 50 x the limit.
    let ok = 0;
    for (let i = 0; i < 200; i++) if (l.hit(`spoof-${i}`, 60_002)) ok++;
    expect(ok).toBe(100);
  });

  test("the client IP is the last X-Forwarded-For entry", () => {
    expect(clientIp({ "x-forwarded-for": "1.1.1.1, 2.2.2.2" }, "9.9.9.9")).toBe("2.2.2.2");
    expect(clientIp({}, "9.9.9.9")).toBe("9.9.9.9");
  });

  test("the call throttle refuses a uid, or an IP, over its limit", () => {
    const t = new CallThrottle();
    for (let i = 0; i < 30; i++) t.admit("u", `ip-${i}`, 0);
    expect(() => t.admit("u", "ip-x", 1)).toThrow(HttpsError);
    for (let i = 0; i < 120; i++) t.admit(`uid-${i}`, "shared-ip", 0);
    expect(() => t.admit("fresh", "shared-ip", 1)).toThrow(HttpsError);
  });

  test("a smaller per-uid limit refuses that uid sooner and leaves the ceiling to others", () => {
    const t = new CallThrottle(24, 12);
    for (let i = 0; i < 12; i++) t.admit("hog", `ip-${i}`, 0);
    expect(() => t.admit("hog", "ip-x", 1)).toThrow(HttpsError);
    // The refused call is not counted against the ceiling: the other half of it is still free.
    for (let i = 0; i < 12; i++) t.admit(`other-${i}`, `other-ip-${i}`, 2);
    expect(() => t.admit("late", "late-ip", 3)).toThrow(HttpsError);
    // A new window admits the uid again.
    expect(() => t.admit("hog", "ip-0", 60_000)).not.toThrow();
  });

  test("rejected-notification logging is sampled", () => {
    const s = new LogSampler(60_000);
    expect(s.take(0).log).toBe(true);
    for (let i = 1; i < 100; i++) expect(s.take(i).log).toBe(false);
    expect(s.take(60_000)).toEqual({ log: true, suppressed: 99 });
  });

  test("the request hash binds every input but not the requestId", () => {
    const a = validateGenerateRequest(base, Date.now());
    const b = validateGenerateRequest({ ...base, requestId: "1b5e7d4e-8f3c-4d6a-9a1b-2c3d4e5f6a7b" }, Date.now());
    expect(inputHashOf(a)).toBe(inputHashOf(b));
    for (const over of [
      { allergens: ["milk"] },
      { ingredients: [{ name: "Tomat", expiresOn: null, quantity: 3 }] },
      { config: { ...base.config, language: "nb" } },
      { kind: "menu", courses: 2 },
    ]) {
      expect(inputHashOf(validateGenerateRequest({ ...base, ...over }, Date.now()))).not.toBe(inputHashOf(a));
    }
  });
});
