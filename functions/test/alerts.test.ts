/**
 * What the alerts (functions/alerts/, README "Alerts") are built on: the once-per-UTC-day log
 * lines for the model-spend thresholds, the daily cap and the welcome grant's half-way mark, the
 * per-account refusal lines, and the alert files themselves (every log name they match is one the
 * code writes).
 */
import { execFileSync } from "node:child_process";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { HttpsError } from "firebase-functions/v2/https";
import { generateRecipe, planFor } from "../src/core/generation";
import { claimWelcome } from "../src/core/welcome";
import { validateGenerateRequest } from "../src/contract";
import { emptyUser, parseRuntimeConfig, statsRef, utcDay } from "../src/model";
import { auth, callerFor, db, makeDeps, resetEmulators, setConfig, type TestDeps } from "./helpers";

/** A fixed server time, noon UTC: everything here is per UTC day. */
const NOW = Date.parse("2026-06-15T12:00:00Z");
const DAY_MS = 86_400_000;
const CEILING = 1_000_000;

let deps: TestDeps;
beforeEach(async () => {
  await resetEmulators();
  deps = makeDeps();
  deps.clock.ms = NOW;
  // No sales, so the day's ceiling is exactly the base.
  await setConfig({ dailySpendBaseMicroUsd: CEILING, dailySpendMaxMicroUsd: 2 * CEILING, perUserPerMinute: 100 });
});

function request(over: Record<string, unknown> = {}) {
  return {
    requestId: randomUUID(),
    kind: "recipe",
    config: { difficulty: "easy", detail: "brief", servings: 1, maxMinutes: null, mealType: "any", diet: "none", language: "en" },
    ingredients: [{ name: "eggs", expiresOn: null, quantity: 1 }],
    allergens: [],
    today: utcDay(deps.now()),
    ...over,
  };
}

/** The worst case a recipe request reserves. */
const worst = () => planFor(validateGenerateRequest(request(), NOW), parseRuntimeConfig({})).reservedMicroUsd;
const seed = (uid: string, balance = 50) => db.collection("users").doc(uid).set({ ...emptyUser(), balance, purchasedCredits: balance });
const stats = async (ms = deps.now()) => (await statsRef(db, utcDay(ms)).get()).data() ?? {};
/** Sets today's settled spend, as if earlier calls had booked it. */
const spent = (micro: number) => statsRef(db, utcDay(deps.now())).set({ spentMicroUsd: micro, reservedMicroUsd: 0 }, { merge: true });
const lines = (message: string) => deps.logs.filter((l) => l.message === message);

async function reason(p: Promise<unknown>): Promise<string> {
  try {
    await p;
  } catch (e) {
    if (e instanceof HttpsError) return (e.details as { reason?: string } | undefined)?.reason ?? e.code;
    throw e;
  }
  return "no error";
}

describe("spend_threshold: 50 %, 80 % and the ceiling, each once per UTC day", () => {
  test("each threshold is logged by the reservation that crosses it, once, and marked on the day's stats", async () => {
    await seed("u1");
    const w = worst();
    await spent(CEILING / 2 - w - 1);
    await generateRecipe(deps, callerFor("u1"), request());
    expect(lines("spend_threshold")).toHaveLength(0);
    expect((await stats()).ceilingMicroUsd).toBe(CEILING);

    await spent(CEILING / 2 - w);
    await generateRecipe(deps, callerFor("u1"), request());
    expect(lines("spend_threshold")).toEqual([{
      level: "warn", message: "spend_threshold",
      data: { percent: 50, spentMicroUsd: CEILING / 2, ceilingMicroUsd: CEILING, day: "2026-06-15", netCredits7d: 0 },
    }]);
    await generateRecipe(deps, callerFor("u1"), request());
    expect(lines("spend_threshold")).toHaveLength(1);

    await spent(0.8 * CEILING);
    await generateRecipe(deps, callerFor("u1"), request());
    await generateRecipe(deps, callerFor("u1"), request());
    expect(lines("spend_threshold").map((l) => l.data?.percent)).toEqual([50, 80]);
    expect(Object.keys((await stats()).spendAlerts).sort()).toEqual(["50", "80"]);

    // At the ceiling: the first refusal records and logs it; later refusals write and log nothing.
    await spent(CEILING);
    expect(await reason(generateRecipe(deps, callerFor("u1"), request()))).toBe("GENERATION_DISABLED");
    const mark = (await stats()).spendAlerts["100"];
    expect(mark).toBe(NOW);
    deps.clock.advance(60_000);
    expect(await reason(generateRecipe(deps, callerFor("u1"), request()))).toBe("GENERATION_DISABLED");
    expect((await stats()).spendAlerts["100"]).toBe(mark);
    const reached = lines("spend_threshold").filter((l) => l.data?.percent === 100);
    expect(reached).toEqual([{
      level: "error", message: "spend_threshold",
      data: { percent: 100, spentMicroUsd: CEILING, ceilingMicroUsd: CEILING, day: "2026-06-15", netCredits7d: 0 },
    }]);
    // The refusal charged nothing.
    expect((await db.collection("users").doc("u1").get()).data()?.inFlight).toBe(0);
  });

  test("a jump past both thresholds logs both in one reservation", async () => {
    await seed("u2");
    await spent(0.85 * CEILING);
    await generateRecipe(deps, callerFor("u2"), request());
    expect(lines("spend_threshold").map((l) => l.data?.percent)).toEqual([50, 80]);
  });

  test("the next UTC day starts again", async () => {
    await seed("u3");
    await spent(0.6 * CEILING);
    await generateRecipe(deps, callerFor("u3"), request());
    deps.clock.advance(DAY_MS);
    await spent(0.6 * CEILING);
    await generateRecipe(deps, callerFor("u3"), request());
    expect(lines("spend_threshold").map((l) => [l.data?.percent, l.data?.day])).toEqual([[50, "2026-06-15"], [50, "2026-06-16"]]);
  });

  test("concurrent reservations crossing a threshold log it once", async () => {
    const users = ["c1", "c2", "c3", "c4"];
    for (const u of users) await seed(u);
    // 40 % settled: the four worst cases together take it past 50 % but stay below 80 %.
    await spent(0.4 * CEILING);
    expect(0.4 * CEILING + 4 * worst()).toBeLessThan(0.8 * CEILING);
    await Promise.all(users.map((u) => generateRecipe(deps, callerFor(u), request())));
    expect(lines("spend_threshold").map((l) => l.data?.percent)).toEqual([50]);
  });

  test("the kill switch refuses before any threshold or mark", async () => {
    await seed("u4");
    await setConfig({ generationEnabled: false });
    await spent(CEILING);
    expect(await reason(generateRecipe(deps, callerFor("u4"), request()))).toBe("GENERATION_DISABLED");
    expect(lines("spend_threshold")).toHaveLength(0);
    expect((await stats()).spendAlerts).toBeUndefined();
  });
});

describe("daily_cap_reached", () => {
  test("the first DAILY_CAP refusal of the day is recorded and logged once", async () => {
    await seed("d1");
    await setConfig({ dailyCap: 1 });
    await generateRecipe(deps, callerFor("d1"), request());
    expect(await reason(generateRecipe(deps, callerFor("d1"), request()))).toBe("DAILY_CAP");
    expect(await reason(generateRecipe(deps, callerFor("d1"), request()))).toBe("DAILY_CAP");
    expect(lines("daily_cap_reached")).toEqual([{ level: "error", message: "daily_cap_reached", data: { day: "2026-06-15", dailyCap: 1, generationUnits: 1 } }]);
    expect((await stats()).dailyCapReachedAt).toBe(NOW);
    deps.clock.advance(DAY_MS);
    await generateRecipe(deps, callerFor("d1"), request());
    expect(await reason(generateRecipe(deps, callerFor("d1"), request()))).toBe("DAILY_CAP");
    expect(lines("daily_cap_reached")).toHaveLength(2);
  });
});

describe("generation_refused (the per-account spike alert)", () => {
  test("USER_DAILY_LIMIT and INFRA_FAILURE_LIMIT refusals are logged with their reason and no uid", async () => {
    await seed("r1");
    await setConfig({ perUserPerDay: 1 });
    await generateRecipe(deps, callerFor("r1"), request());
    expect(await reason(generateRecipe(deps, callerFor("r1"), request()))).toBe("USER_DAILY_LIMIT");
    await seed("r2");
    await setConfig({ perUserPerDay: 40, infraRefundsPerDay: 0 });
    expect(await reason(generateRecipe(deps, callerFor("r2"), request()))).toBe("INFRA_FAILURE_LIMIT");
    const refused = lines("generation_refused");
    expect(refused.map((l) => [l.level, l.data?.reason, l.data?.why])).toEqual([
      ["warn", "USER_DAILY_LIMIT", "generations"],
      ["warn", "INFRA_FAILURE_LIMIT", "infra_refunds"],
    ]);
    expect(JSON.stringify(refused)).not.toMatch(/"r1"|"r2"/);
  });
});

describe("welcome_threshold: half of perDay, once per UTC day", () => {
  test("logged by the grant that reaches half (rounded up), once", async () => {
    await setConfig({ welcome: { recipes: 3, perDay: 5 } });
    const caller = async (i: number) => {
      const u = await auth.createUser({});
      await auth.updateUser(u.uid, { providerToLink: { providerId: "google.com", uid: `half-${i}-${randomUUID()}` } });
      return { ...callerFor(u.uid, "google.com", Math.floor(NOW / 1000)), providers: ["google.com"] };
    };
    for (let i = 0; i < 2; i++) await claimWelcome(deps, await caller(i));
    expect(lines("welcome_threshold")).toHaveLength(0);
    await claimWelcome(deps, await caller(2));
    expect(lines("welcome_threshold")).toEqual([{ level: "warn", message: "welcome_threshold", data: { percent: 50, grants: 3, perDay: 5, day: "2026-06-15" } }]);
    for (let i = 3; i < 5; i++) await claimWelcome(deps, await caller(i));
    expect(lines("welcome_threshold")).toHaveLength(1);
    expect(lines("welcome_day_limit").map((l) => l.data?.event)).toEqual(["reached"]);
  });
});

describe("the alert files (functions/alerts)", () => {
  const dir = join(__dirname, "../alerts");
  const source = readdirSync(join(__dirname, "../src"), { recursive: true, withFileTypes: true })
    .filter((f) => f.isFile() && f.name.endsWith(".ts"))
    .map((f) => readFileSync(join(f.parentPath, f.name), "utf8"))
    .join("\n");
  const files = [
    ...readdirSync(join(dir, "metrics")).map((f) => join(dir, "metrics", f)),
    ...readdirSync(join(dir, "policies")).map((f) => join(dir, "policies", f)),
  ];

  test("pass the script's own offline checks", () => {
    const out = execFileSync("python3", [join(dir, "alerts.py"), "--check-files"], { encoding: "utf8" });
    expect(out).toContain("local checks passed");
  });

  test("give every policy a severity Cloud Monitoring accepts (CRITICAL, ERROR, WARNING; no INFO)", () => {
    for (const f of readdirSync(join(dir, "policies"))) {
      const policy = JSON.parse(readFileSync(join(dir, "policies", f), "utf8")) as { severity?: string };
      expect(["CRITICAL", "ERROR", "WARNING"], f).toContain(policy.severity);
    }
  });

  /** Runs Python with alerts.py importable as `alerts`; never gcloud (no runner reaches it). */
  const py = (code: string) => execFileSync("python3", ["-c", `import sys; sys.path.insert(0, ${JSON.stringify(dir)})\nimport alerts\n${code}`], { encoding: "utf8" });

  test("the offline checks refuse a severity outside CRITICAL, ERROR, WARNING", () => {
    const out = py(`
import json
base = json.load(open(alerts.os.path.join(alerts.HERE, "policies", "model-lifecycle-stale.json")))
for sev in ["INFO", "SEVERITY_UNSPECIFIED", "warning", None, "WARNING", "ERROR", "CRITICAL"]:
    p = dict(base)
    if sev is None:
        p.pop("severity")
    else:
        p["severity"] = sev
    try:
        alerts.validate([], [("x.json", p)])
        print(sev, "ok")
    except alerts.Fail as e:
        print(sev, "refused" if "severity" in str(e) else "other: " + str(e))
`);
    expect(out.trim().split("\n")).toEqual([
      "INFO refused", "SEVERITY_UNSPECIFIED refused", "warning refused", "None refused", "WARNING ok", "ERROR ok", "CRITICAL ok",
    ]);
  });

  test("an apply goes on past a failure and returns every failure", () => {
    const out = py(`
import json, tempfile
calls = []
def runner(args, base):
    calls.append(" ".join(args[:4]))
    path = args[-1].split("=", 1)[1]
    if "bad" in open(path).read():
        raise alerts.Fail("refused")
metric = {"name": "m_bad", "filter": "bad"}
threshold = {"displayName": "Recipe: t", "conditions": [{"conditionThreshold": {"filter": "metric.type=\\"logging.googleapis.com/user/m_bad\\""}}]}
todo = [
    ("create", "policy", "Recipe: a", "a.json", {"displayName": "Recipe: a", "severity": "bad"}, None, []),
    ("create", "policy", "Recipe: b", "b.json", {"displayName": "Recipe: b"}, None, []),
    ("create", "policy", "Recipe: t", "t.json", threshold, None, []),
    ("update", "policy", "Recipe: c", "c.json", {"displayName": "Recipe: c"}, {"name": "projects/p/alertPolicies/1"}, []),
    ("create", "metric", "m_bad", "m.json", metric, None, []),
]
with tempfile.TemporaryDirectory() as tmp:
    failures = alerts.apply_plan(todo, [], tmp, runner)
print(json.dumps({"calls": calls, "failures": [[k, n, m] for k, n, _, m in failures]}))
`);
    const result = JSON.parse(out.trim().split("\n").pop()!);
    expect(result.calls).toEqual([
      "logging metrics create m_bad",
      "beta monitoring policies create",
      "beta monitoring policies create",
      "beta monitoring policies update",
    ]);
    expect(result.failures).toEqual([
      ["metric", "m_bad", "refused"],
      ["policy", "Recipe: a", "refused"],
      ["policy", "Recipe: t", "skipped: its metric m_bad failed above"],
    ]);
  });

  test("every log line they match is one the code writes", () => {
    const names = new Set<string>();
    for (const f of files) {
      const text = readFileSync(f, "utf8");
      for (const m of text.matchAll(/jsonPayload\.message=\\"([^"\\]+)\\"/g)) names.add(m[1]);
      for (const m of text.matchAll(/jsonPayload\.message=\(([^)]*)\)/g)) for (const n of m[1].matchAll(/\\"([^"\\]+)\\"/g)) names.add(n[1]);
    }
    expect(names.size).toBeGreaterThan(15);
    for (const n of names) expect(source, n).toContain(`"${n}"`);
    for (const n of ["spend_threshold", "daily_cap_reached", "welcome_threshold", "welcome_day_limit", "generation_refused", "board_redraw_failed", "recipe_reported", "report_day_limit"]) {
      expect(names).toContain(n);
    }
  });

  test("hold no address and no channel", () => {
    for (const f of files) {
      const text = readFileSync(f, "utf8");
      expect(text, f).not.toContain("@");
      expect(text, f).not.toContain("notificationChannels");
    }
  });
});
