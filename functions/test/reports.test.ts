/** "Report this recipe": validation, snapshots, idempotency, limits, logs, deletion. */
import { randomUUID } from "node:crypto";
import { HttpsError } from "firebase-functions/v2/https";
import { deleteAccount } from "../src/core/account";
import { generateRecipe } from "../src/core/generation";
import { REPORT_LIMITS, cleanReportText, reportRecipe, validateReport } from "../src/core/reports";
import { CAPABILITIES } from "../src/contract";
import { Timestamp } from "firebase-admin/firestore";
import { emptyUser, reportableRef, statsRef, utcDay } from "../src/model";
import { callerFor, db, makeDeps, resetEmulators, setConfig, type TestDeps } from "./helpers";

const UID = "user-report";
const NOW = Date.parse("2026-06-15T12:00:00Z");
const app = { platform: "android", version: "2.3.0", language: "en" };

function report(over: Record<string, unknown> = {}) {
  return { reportId: randomUUID(), reason: "offensive", requestId: randomUUID(), app, ...over };
}

async function seedUser(extra: Record<string, unknown> = {}) {
  await db.collection("users").doc(UID).set({ ...emptyUser(), balance: 5, purchasedCredits: 5, ...extra });
}

async function generate(image?: string) {
  const requestId = randomUUID();
  await generateRecipe(deps, callerFor(UID), {
    requestId, kind: "recipe", ...(image ? { image } : {}),
    config: { difficulty: "easy", detail: "standard", servings: 2, maxMinutes: 30, mealType: "dinner", diet: "none", language: "en" },
    ingredients: [{ name: "Pasta", expiresOn: null, quantity: 1 }], today: utcDay(NOW),
  });
  return requestId;
}

async function reason(p: Promise<unknown>): Promise<string> {
  try {
    await p;
  } catch (e) {
    if (e instanceof HttpsError) return (e.details as { reason?: string } | undefined)?.reason ?? e.code;
    throw e;
  }
  return "no error";
}

const reports = async () => (await db.collection("reports").get()).docs.map((d) => ({ id: d.id, ...d.data() }) as Record<string, ReturnType<typeof JSON.parse>>);
const stats = async (d: TestDeps) => (await statsRef(db, utcDay(d.now())).get()).data() ?? {};

let deps: TestDeps;
beforeEach(async () => {
  await resetEmulators();
  deps = makeDeps();
  deps.clock.ms = NOW;
});

describe("validation", () => {
  test.each([
    ["no reportId", { reportId: undefined }],
    ["a bad reason", { reason: "spam" }],
    ["no requestId", { requestId: undefined }],
    ["recipe text sent along", { recipe: { title: "Omelette", steps: ["Cook."] } }],
    ["a menu sent along", { menu: { days: [] } }],
    ["a note that is not a string", { note: 3 }],
    ["a huge note", { note: "x".repeat(REPORT_LIMITS.noteRawChars + 1) }],
    ["a bad platform", { app: { ...app, platform: "web" } }],
    ["a bad version", { app: { ...app, version: "2.3.0; drop" } }],
    ["a bad language", { app: { ...app, language: "English" } }],
  ])("refused: %s", (_l, over) => {
    expect(() => validateReport(report(over))).toThrow(HttpsError);
  });

  test("the note is cleaned and capped at 500 code points", () => {
    const v = validateReport(report({ note: `  bad\x00 \u{202E}text\n\n${"é".repeat(600)}` }));
    expect(Array.from(v.note!)).toHaveLength(500);
    expect(v.note!.startsWith("bad text é")).toBe(true);
    expect(validateReport(report({ note: "   " })).note).toBeNull();
    expect(cleanReportText("a\x07b", 10)).toBe("a b");
  });

  test("every reason is accepted, the picture one included (whether or not the result has pictures)", () => {
    for (const r of ["offensive", "unsafe", "picture", "other"]) expect(validateReport(report({ reason: r })).reason).toBe(r);
  });
});

/** The lasting record a delivered generation leaves (as settle writes it), for limits tests. */
async function reportable(uid = UID) {
  const id = randomUUID();
  await reportableRef(db, uid, id).set({ kind: "recipe", at: Timestamp.fromMillis(NOW), expiresAt: Timestamp.fromMillis(NOW + 86_400_000) });
  return id;
}

describe("reportRecipe", () => {
  test("a recipe from a pack: the server's own copy and its pictures are the snapshot, the balance never moves", async () => {
    await seedUser();
    const requestId = await generate("steps");
    expect((await reportableRef(db, UID, requestId).get()).data()).toMatchObject({ kind: "recipe" });
    const before = (await db.collection("users").doc(UID).get()).data()!.balance;
    const r = report({ requestId, reason: "picture", note: "Not what I cooked" });
    expect(await reportRecipe(deps, { uid: UID }, r)).toEqual({ status: "RECEIVED" });
    const [stored] = await reports();
    expect(stored).toMatchObject({
      reportId: r.reportId, reason: "picture", note: "Not what I cooked", requestId, uid: UID, kind: "recipe",
      platform: "android", appVersion: "2.3.0", language: "en", pictures: 3, imageLevel: "steps",
      snapshot: { recipe: { title: "Pantry pasta" } },
    });
    expect(stored.id).not.toBe(r.reportId);
    expect(stored.id).not.toContain(UID);
    expect(stored.expiresAt.toMillis() - stored.createdAt.toMillis()).toBe(90 * 86_400_000);
    const pics = await db.collection("reports").doc(stored.id).collection("reportPictures").get();
    expect(pics.docs.map((d) => d.id).sort()).toEqual(["main", "step-0", "step-1"]);
    expect(pics.docs[0].data().expiresAt.toMillis()).toBe(stored.expiresAt.toMillis());
    expect((await db.collection("users").doc(UID).get()).data()).toMatchObject({ balance: before, reportWindow: { day: utcDay(NOW), count: 1 } });
    expect(deps.generator.calls).toBe(1);
    expect(await stats(deps)).toMatchObject({ reports: 1, reportReasons: { picture: 1 } });
    const line = deps.logs.find((l) => l.message === "recipe_reported")!;
    expect(line.level).toBe("warn");
    expect(line.data).toMatchObject({ reason: "picture", kind: "recipe", snapshot: true, hasNote: true, pictures: 3, platform: "android" });
    expect(JSON.stringify(line)).not.toContain("Not what I cooked");
    expect(JSON.stringify(line)).not.toContain("Pantry pasta");
    expect(JSON.stringify(line)).not.toContain(UID);
  });

  test("a menu from a pack is reported the same way", async () => {
    await seedUser();
    const requestId = randomUUID();
    await generateRecipe(deps, callerFor(UID), {
      requestId, kind: "menu", courses: 2,
      config: { difficulty: "easy", detail: "standard", servings: 2, maxMinutes: 30, mealType: "dinner", diet: "none", language: "en" },
      ingredients: [{ name: "Pasta", expiresOn: null, quantity: 1 }], today: utcDay(NOW),
    });
    await reportRecipe(deps, { uid: UID }, report({ requestId }));
    expect((await reports())[0]).toMatchObject({ kind: "menu", pictures: 0, imageLevel: null, snapshot: { menu: { days: expect.any(Array) } } });
  });

  test("the caller's generation no longer kept (after 7 days): stored without a snapshot, from its lasting record", async () => {
    await seedUser();
    const requestId = await generate();
    // What Firestore's TTL does to the generation after its window.
    await db.recursiveDelete(db.collection("users").doc(UID).collection("generations").doc(requestId));
    const r = report({ requestId });
    expect(await reportRecipe(deps, { uid: UID }, r)).toEqual({ status: "RECEIVED" });
    expect((await reports())[0]).toMatchObject({ requestId, uid: UID, kind: "recipe", snapshot: null, pictures: 0 });
    expect((await reports())[0].generatedAt).not.toBeNull();
    expect(deps.logs.find((l) => l.message === "recipe_reported")!.data).toMatchObject({ snapshot: false });
  });

  test("an id the caller never had, or another account's generation: UNKNOWN_REQUEST, nothing stored or counted", async () => {
    await seedUser();
    const requestId = await generate();
    await db.collection("users").doc("other").set({ ...emptyUser() });
    expect(await reason(reportRecipe(deps, { uid: UID }, report()))).toBe("UNKNOWN_REQUEST");
    expect(await reason(reportRecipe(deps, { uid: "other" }, report({ requestId })))).toBe("UNKNOWN_REQUEST");
    expect(await reports()).toHaveLength(0);
    expect((await stats(deps)).reports).toBeUndefined();
    expect((await db.collection("users").doc("other").get()).data()!.reportWindow).toBeUndefined();
  });

  test("an account without a document (it has no result from a pack) is UNKNOWN_REQUEST, and none is created", async () => {
    expect(await reason(reportRecipe(deps, { uid: "anon-1" }, report()))).toBe("UNKNOWN_REQUEST");
    expect((await db.collection("users").doc("anon-1").get()).exists).toBe(false);
    expect(await reports()).toHaveLength(0);
  });

  test("a deleted account cannot report", async () => {
    await seedUser({ deletedAt: new Date() });
    expect(await reason(reportRecipe(deps, { uid: UID }, report({ requestId: await reportable() })))).toBe("ACCOUNT_CLOSED");
  });

  test("the same reportId twice is one report; for another result it is refused; another account's same id is its own", async () => {
    await seedUser();
    await db.collection("users").doc("other").set({ ...emptyUser() });
    const requestId = await reportable();
    const r = report({ requestId });
    await reportRecipe(deps, { uid: UID }, r);
    expect(await reportRecipe(deps, { uid: UID }, { ...r, reason: "other" })).toEqual({ status: "RECEIVED" });
    expect(await reports()).toHaveLength(1);
    expect((await reports())[0]).toMatchObject({ reason: "offensive" });
    expect(await reason(reportRecipe(deps, { uid: UID }, { ...r, requestId: await reportable() }))).toBe("INVALID_REQUEST");
    expect(await reportRecipe(deps, { uid: "other" }, { ...r, requestId: await reportable("other") })).toEqual({ status: "RECEIVED" });
    const all = await reports();
    expect(all).toHaveLength(2);
    expect(all.map((x) => x.uid).sort()).toEqual(["other", UID].sort());
    expect((await stats(deps)).reports).toBe(2);
    expect((await db.collection("users").doc(UID).get()).data()!.reportWindow).toMatchObject({ count: 1 });
  });

  test("limits: the account's own first, then the service's day, whose first refusal is logged once", async () => {
    await setConfig({ reports: { perUserPerDay: 2, perDay: 3 } });
    await seedUser();
    await db.collection("users").doc("other").set({ ...emptyUser() });
    await reportRecipe(deps, { uid: UID }, report({ requestId: await reportable() }));
    await reportRecipe(deps, { uid: UID }, report({ requestId: await reportable() }));
    expect(await reason(reportRecipe(deps, { uid: UID }, report({ requestId: await reportable() })))).toBe("REPORT_LIMIT");
    // Made-up ids are refused before the day's cap and never use it up.
    for (let i = 0; i < 5; i++) expect(await reason(reportRecipe(deps, { uid: "other" }, report()))).toBe("UNKNOWN_REQUEST");
    await reportRecipe(deps, { uid: "other" }, report({ requestId: await reportable("other") }));
    expect(await reason(reportRecipe(deps, { uid: "other" }, report({ requestId: await reportable("other") })))).toBe("REPORT_LIMIT");
    expect(await reason(reportRecipe(deps, { uid: "other" }, report({ requestId: await reportable("other") })))).toBe("REPORT_LIMIT");
    expect(await reports()).toHaveLength(3);
    const capLines = deps.logs.filter((l) => l.message === "report_day_limit");
    expect(capLines).toHaveLength(1);
    expect(capLines[0]).toMatchObject({ level: "error", data: { perDay: 3, reports: 3 } });
    expect(await stats(deps)).toHaveProperty("reportCapReachedAt");
    // The next UTC day starts afresh.
    deps.clock.advance(86_400_000);
    expect(await reportRecipe(deps, { uid: UID }, report({ requestId: await reportable() }))).toEqual({ status: "RECEIVED" });
  });

  test("keepDays sets the expiry; an invalid reports config takes the defaults", async () => {
    await seedUser();
    await setConfig({ reports: { keepDays: 30 } });
    await reportRecipe(deps, { uid: UID }, report({ requestId: await reportable() }));
    await setConfig({ reports: { keepDays: 0 } });
    await reportRecipe(deps, { uid: UID }, report({ requestId: await reportable() }));
    expect((await reports()).map((x) => (x.expiresAt.toMillis() - x.createdAt.toMillis()) / 86_400_000).sort((a, b) => a - b)).toEqual([30, 90]);
  });

  test("account deletion keeps the account's reports without the uid, and removes its reportable records", async () => {
    await seedUser({ balance: 0 });
    const requestId = await reportable();
    await reportRecipe(deps, { uid: UID }, report({ requestId }));
    await db.collection("reports").doc(randomUUID()).set({ uid: "someone-else", reason: "other" });
    await deleteAccount(deps, callerFor(UID, "anonymous"), {});
    expect((await reports()).map((r) => r.uid).sort()).toEqual([null, "someone-else"].sort());
    expect((await reportableRef(db, UID, requestId).get()).exists).toBe(false);
  });

  test("the capability is beside every balance", () => {
    expect(CAPABILITIES.acceptsReports).toBe(true);
  });
});
