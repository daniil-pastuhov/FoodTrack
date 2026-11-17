/**
 * Provider profiles (src/providers/profiles.ts) and the switch (src/core/providerSwitch.ts): the
 * registry's checks, readiness, the planned writes, the transaction against the emulators (only the
 * picture fields and prices change, an audit record, the log line), the owner gate with real
 * Auth emulator ID tokens (unsigned, not Google, not listed, revoked, disabled, an old sign-in),
 * the admin command's dry run and --apply, and the board and console page showing nothing more.
 */
import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { HttpsError } from "firebase-functions/v2/https";
import { AdminRefusal } from "../src/admin/accountRequests";
import { parseImageArgs, runImage, type ImageEnv } from "../src/admin/cli";
import { buildBoard, boardStatsDays } from "../src/board/boardData";
import { boardJson, boardSummary, renderBoardHtml } from "../src/board/boardHtml";
import { DEFAULT_IMAGE_SELECTION } from "../src/constants";
import { DEFAULT_RUNTIME_CONFIG, loadRuntimeConfig, parseRuntimeConfig, uidHash } from "../src/model";
import type { Logger } from "../src/ports";
import {
  OWNER_DOC,
  PROFILE_CHANGES,
  RUNTIME_DOC,
  SwitchRefused,
  activateProfile,
  getProviderProfiles,
  listProfiles,
  ownerConsoleOrigins,
  parseOwnerDoc,
  planProfile,
  setProviderProfile,
  type OwnerEnv,
} from "../src/core/providerSwitch";
import {
  PROVIDER_PROFILES,
  activeProfileOf,
  profileById,
  readinessOf,
  registryProblems,
  type ProviderProfile,
} from "../src/providers/profiles";
import { auth, db, passwordUser, resetEmulators } from "./helpers";

interface Line { level: string; message: string; data?: Record<string, unknown> }

function makeEnv(over: Partial<OwnerEnv> = {}): OwnerEnv & { logs: Line[] } {
  const logs: Line[] = [];
  const rec = (level: string) => (message: string, data?: Record<string, unknown>) => void logs.push({ level, message, data });
  const logger: Logger = { info: rec("info"), warn: rec("warn"), error: rec("error") };
  return { db, auth, now: () => Date.now(), logger, secretReadable: async () => false, logs, ...over };
}

async function reason(p: Promise<unknown>): Promise<string> {
  try {
    await p;
  } catch (e) {
    if (e instanceof SwitchRefused) return e.reason;
    if (e instanceof HttpsError) return `${e.code}:${(e.details as { reason?: string } | undefined)?.reason ?? ""}`;
    if (e instanceof AdminRefusal) return `admin:${e.message}`;
    throw e;
  }
  return "no refusal";
}

const profile = (id: string) => profileById(id)!;
const runtime = async () => (await db.doc(RUNTIME_DOC).get()).data() as Record<string, unknown> | undefined;
const audits = async () => (await db.collection(PROFILE_CHANGES).get()).docs.map((d) => d.data());

/** A config/runtime with many fields, to show a switch leaves every other one as it was. */
const FULL_CONFIG = {
  generationEnabled: false,
  allowSandbox: true,
  dailyCap: 77,
  costs: { recipe: 1, menu2: 4, menu3: 5, recipeImage: 3 },
  prices: { inputUsdPerMTok: 2, outputUsdPerMTok: 9, imageUsdPerImage: { "vertex/gemini-3.1-flash-image": 0.08 } },
  welcome: { recipes: 2, perDay: 50 },
  tasteQuiz: { hidden: ["units"] },
  image: { provider: "vertex", model: "gemini-3.1-flash-lite-image", fallbackModel: "gemini-nano-banana-2.1", maxStepImages: 3 },
};

beforeEach(async () => {
  await resetEmulators();
});

// --- The registry -----------------------------------------------------------------------------

describe("the registry", () => {
  test("is sound, lists the four profiles and matches the deploy defaults", () => {
    expect(registryProblems()).toEqual([]);
    expect(PROVIDER_PROFILES.map((p) => p.id)).toEqual(["flash-lite", "nano-banana", "off", "openai"]);
    expect(activeProfileOf({ ...DEFAULT_IMAGE_SELECTION })?.id).toBe("flash-lite");
    expect(activeProfileOf({ ...DEFAULT_IMAGE_SELECTION, provider: "off" })?.id).toBe("off");
    expect(activeProfileOf({ ...DEFAULT_IMAGE_SELECTION, model: "gemini-3-pro-image" })).toBeNull();
    // Every profile's price is in force by default, so switching needs no price write today.
    for (const p of PROVIDER_PROFILES) {
      for (const [key, usd] of Object.entries(p.image.usdPerImage)) expect(DEFAULT_RUNTIME_CONFIG.prices.imageUsdPerImage?.[key], key).toBe(usd);
    }
  });

  test("catches a bad entry", () => {
    const good = profile("nano-banana");
    const bad: ProviderProfile[] = [
      good,
      { ...good, label: "again" },
      { ...good, id: "No Caps", image: { ...good.image, fallbackModel: good.image.model } },
      { ...good, id: "unpriced", image: { provider: "vertex", model: "gemini-3-pro-image", fallbackModel: "", usdPerImage: {} } },
      { ...good, id: "dear", image: { provider: "openai", model: "gpt-image-2", fallbackModel: "", usdPerImage: { "openai/gpt-image-2": 5 } } },
      { ...good, id: "off-with-model", image: { provider: "off", model: "x", usdPerImage: {} } },
    ];
    const problems = registryProblems(bad).join("\n");
    expect(problems).toContain("duplicate id");
    expect(problems).toContain("id must match");
    expect(problems).toContain("the fallback must differ");
    expect(problems).toContain("no reserved price for vertex/gemini-3-pro-image");
    expect(problems).toContain("must be 0.001 to 2 USD");
    expect(problems).toContain("provider off takes no model");
    expect(problems).toContain("no profile matches DEFAULT_IMAGE_SELECTION");
  });

  test("readiness: OpenAI is listed but not ready, even with its key", async () => {
    const openai = profile("openai");
    expect(await readinessOf(openai, async () => false)).toEqual({ ready: false, missing: openai.requires.map((r) => r.missing) });
    const withKey = await readinessOf(openai, async () => true);
    expect(withKey.ready).toBe(false);
    expect(withKey.missing).toEqual([expect.stringContaining("privacy policy")]);
    // A profile needing only a secret is ready exactly when the secret can be read.
    const secretOnly: ProviderProfile = { ...openai, requires: openai.requires.filter((r) => r.kind === "secret") };
    expect((await readinessOf(secretOnly, async () => true)).ready).toBe(true);
    expect((await readinessOf(secretOnly, async () => false)).ready).toBe(false);
    expect((await readinessOf(secretOnly, async () => { throw new Error("denied"); })).ready).toBe(false);
    for (const id of ["flash-lite", "nano-banana", "off"]) expect((await readinessOf(profile(id), async () => false)).ready).toBe(true);
  });
});

// --- Planning (pure) --------------------------------------------------------------------------

describe("planProfile", () => {
  const paths = (data: Record<string, unknown>, id: string) => planProfile(data, profile(id)).writes.map((w) => [w.path.join(">"), w.value]);

  test("writes only the picture fields that change, and nothing when already in force", () => {
    expect(paths({}, "flash-lite")).toEqual([]);
    expect(paths({}, "nano-banana")).toEqual([
      ["image>provider", "vertex"],
      ["image>model", "gemini-nano-banana-2.1"],
      ["image>fallbackModel", "gemini-3.1-flash-lite-image"],
    ]);
    expect(paths(FULL_CONFIG, "nano-banana")).toEqual([
      ["image>model", "gemini-nano-banana-2.1"],
      ["image>fallbackModel", "gemini-3.1-flash-lite-image"],
    ]);
    expect(paths(FULL_CONFIG, "off")).toEqual([["image>provider", "off"]]);
    expect(paths({ image: { provider: "off", model: "gemini-3.1-flash-lite-image" } }, "off")).toEqual([]);
    expect(paths({ image: { provider: "off", model: "gemini-3.1-flash-lite-image" } }, "flash-lite")).toEqual([
      ["image>provider", "vertex"],
      ["image>fallbackModel", "gemini-nano-banana-2.1"],
    ]);
  });

  test("an image value that is not a map is replaced as a whole", () => {
    expect(paths({ image: "nano" }, "nano-banana")).toEqual([
      ["image", { provider: "vertex", model: "gemini-nano-banana-2.1", fallbackModel: "gemini-3.1-flash-lite-image" }],
    ]);
  });

  test("raises a price that is missing or lower, never lowers one set higher", () => {
    const lower = { prices: { inputUsdPerMTok: 1.5, outputUsdPerMTok: 7.5, imageUsdPerImage: { "vertex/gemini-nano-banana-2.1": 0.05 } } };
    expect(paths(lower, "nano-banana")).toContainEqual(["prices>imageUsdPerImage>vertex/gemini-nano-banana-2.1", 0.06]);
    const higher = { prices: { inputUsdPerMTok: 1.5, outputUsdPerMTok: 7.5, imageUsdPerImage: { "vertex/gemini-nano-banana-2.1": 0.09 } } };
    expect(paths(higher, "nano-banana").some(([p]) => String(p).startsWith("prices"))).toBe(false);
  });

  test("refuses to add a price to an invalid prices map, and an image map it can't make valid", () => {
    const lowerButBroken = { prices: { outputUsdPerMTok: 7.5, imageUsdPerImage: { "vertex/gemini-nano-banana-2.1": 0.05 } } };
    // An invalid prices map is ignored as a whole, so the default price is in force: nothing to add.
    expect(paths(lowerButBroken, "nano-banana").some(([p]) => String(p).startsWith("prices"))).toBe(false);
    expect(() => planProfile({ image: { maxStepImages: 99 } }, profile("nano-banana"))).toThrow(/would not select nano-banana/);
    // A profile whose price is not a default one (as a future one may be).
    const pro: ProviderProfile = {
      id: "pro", label: "Pro", summary: "Dear.", requires: [],
      image: { provider: "vertex", model: "gemini-3-pro-image", fallbackModel: "", usdPerImage: { "vertex/gemini-3-pro-image": 0.15 } },
    };
    expect(() => planProfile(lowerButBroken, pro)).toThrow(/prices is invalid/);
    expect(planProfile({}, pro).writes).toContainEqual({
      path: ["prices"],
      value: { inputUsdPerMTok: 1.5, outputUsdPerMTok: 7.5, imageUsdPerImage: { "vertex/gemini-3-pro-image": 0.15 } },
    });
    expect(planProfile({ prices: { inputUsdPerMTok: 2, outputUsdPerMTok: 9 } }, pro).writes).toContainEqual({
      path: ["prices", "imageUsdPerImage"], value: { "vertex/gemini-3-pro-image": 0.15 },
    });
  });

  test("the planned document parses to exactly the profile", () => {
    for (const p of PROVIDER_PROFILES.filter((x) => x.id !== "openai")) {
      const plan = planProfile(FULL_CONFIG, p);
      expect(plan.to.profile).toBe(p.id);
      expect(plan.from.profile).toBe("flash-lite");
    }
  });
});

// --- The switch against the emulators ---------------------------------------------------------

describe("activateProfile", () => {
  test("apply: only the picture fields change, one audit record, one log line", async () => {
    await db.doc(RUNTIME_DOC).set(FULL_CONFIG);
    const env = makeEnv();
    const out = await activateProfile(env, "nano-banana", { apply: true, actor: { via: "cli" } });
    expect(out).toMatchObject({ applied: true, changed: true });
    const after = await runtime();
    expect(after).toEqual({ ...FULL_CONFIG, image: { ...FULL_CONFIG.image, model: "gemini-nano-banana-2.1", fallbackModel: "gemini-3.1-flash-lite-image" } });
    const parsed = await loadRuntimeConfig(db);
    expect(activeProfileOf(parsed.image)?.id).toBe("nano-banana");
    expect(parsed.image.maxStepImages).toBe(3);
    const [audit, ...more] = await audits();
    expect(more).toEqual([]);
    expect(audit).toMatchObject({
      actor: { via: "cli" },
      from: { profile: "flash-lite", provider: "vertex", model: "gemini-3.1-flash-lite-image", fallbackModel: "gemini-nano-banana-2.1" },
      to: { profile: "nano-banana", provider: "vertex", model: "gemini-nano-banana-2.1", fallbackModel: "gemini-3.1-flash-lite-image" },
    });
    expect(audit.at.toMillis()).toBeGreaterThan(Date.now() - 60_000);
    expect(env.logs.filter((l) => l.message === "provider_profile_changed")).toEqual([
      { level: "info", message: "provider_profile_changed", data: expect.objectContaining({ via: "cli", from: "flash-lite", to: "nano-banana", change: out.changeId }) },
    ]);
  });

  test("a price write keeps the other prices; a missing document is created with only what the profile needs", async () => {
    await db.doc(RUNTIME_DOC).set({ ...FULL_CONFIG, prices: { ...FULL_CONFIG.prices, imageUsdPerImage: { ...FULL_CONFIG.prices.imageUsdPerImage, "vertex/gemini-nano-banana-2.1": 0.05 } } });
    await activateProfile(makeEnv(), "nano-banana", { apply: true, actor: { via: "cli" } });
    expect((await runtime())!.prices).toEqual({
      inputUsdPerMTok: 2,
      outputUsdPerMTok: 9,
      imageUsdPerImage: { "vertex/gemini-3.1-flash-image": 0.08, "vertex/gemini-nano-banana-2.1": 0.06 },
    });
    await resetEmulators();
    await activateProfile(makeEnv(), "off", { apply: true, actor: { via: "cli" } });
    expect(await runtime()).toEqual({ image: { provider: "off" } });
    expect(parseRuntimeConfig((await runtime())!).costs.recipeImage).toBeUndefined();
  });

  test("dry run and an unchanged switch write nothing", async () => {
    await db.doc(RUNTIME_DOC).set(FULL_CONFIG);
    const env = makeEnv();
    const dry = await activateProfile(env, "off", { apply: false, actor: { via: "cli" } });
    expect(dry).toMatchObject({ applied: false, changed: true });
    expect(dry.plan.writes).toEqual([{ path: ["image", "provider"], value: "off" }]);
    const same = await activateProfile(env, "flash-lite", { apply: true, actor: { via: "cli" } });
    expect(same).toMatchObject({ applied: false, changed: false });
    expect(await runtime()).toEqual(FULL_CONFIG);
    expect(await audits()).toEqual([]);
    expect(env.logs.filter((l) => l.message === "provider_profile_changed")).toEqual([]);
  });

  test("refuses a profile that is not ready, an unknown one, and a stale view, writing nothing", async () => {
    await db.doc(RUNTIME_DOC).set(FULL_CONFIG);
    const env = makeEnv({ secretReadable: async () => true });
    expect(await reason(activateProfile(env, "openai", { apply: true, actor: { via: "cli" } }))).toBe("NOT_READY");
    expect(await reason(activateProfile(env, "dall-e", { apply: true, actor: { via: "cli" } }))).toBe("UNKNOWN_PROFILE");
    expect(await reason(activateProfile(env, { id: "off" }, { apply: true, actor: { via: "cli" } }))).toBe("UNKNOWN_PROFILE");
    expect(await reason(activateProfile(env, "off", { apply: true, actor: { via: "cli" }, expect: "nano-banana" }))).toBe("STALE");
    expect(await runtime()).toEqual(FULL_CONFIG);
    expect(await audits()).toEqual([]);
  });

  test("listProfiles: the active setup, readiness and the last change", async () => {
    const env = makeEnv();
    let view = await listProfiles(env);
    expect(view.active).toMatchObject({ profile: "flash-lite", provider: "vertex", picturesOn: true, maxStepImages: 2 });
    expect(view.lastChange).toBeNull();
    expect(view.profiles.map((p) => [p.id, p.ready])).toEqual([["flash-lite", true], ["nano-banana", true], ["off", true], ["openai", false]]);
    await activateProfile(env, "off", { apply: true, actor: { via: "cli" } });
    view = await listProfiles(env);
    expect(view.active).toMatchObject({ profile: "off", picturesOn: false });
    expect(view.lastChange).toMatchObject({ via: "cli", from: "flash-lite", to: "off" });
    await db.doc(RUNTIME_DOC).set({ image: { provider: "vertex", model: "gemini-3-pro-image" } }, { merge: true });
    expect((await listProfiles(env)).active.profile).toBe("custom");
  });
});

// --- The owner gate with Auth emulator tokens ------------------------------------------------

/** A Google sign-in in the Auth emulator (it accepts an unsigned id_token). */
async function googleUser(email = `owner-${randomUUID()}@example.com`): Promise<{ uid: string; idToken: string; email: string }> {
  const res = await fetch(
    `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=fake-api-key`,
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        postBody: `id_token=${encodeURIComponent(JSON.stringify({ sub: randomUUID(), email, email_verified: true, name: "Some Name" }))}&providerId=google.com`,
        requestUri: "http://localhost",
        returnSecureToken: true,
        returnIdpCredential: true,
      }),
    },
  );
  const json = (await res.json()) as { localId: string; idToken: string };
  if (!res.ok) throw new Error(`signInWithIdp failed: ${JSON.stringify(json)}`);
  return { uid: json.localId, idToken: json.idToken, email };
}

const bearer = (t: string) => `Bearer ${t}`;

describe("the owner callables", () => {
  test("unsigned, garbage, not Google, not listed and no allowlist are all refused", async () => {
    const env = makeEnv();
    const owner = await googleUser();
    expect(await reason(getProviderProfiles(env, undefined))).toBe("SIGN_IN");
    expect(await reason(getProviderProfiles(env, "Bearer not-a-token"))).toBe("SIGN_IN");
    expect(await reason(getProviderProfiles(env, owner.idToken))).toBe("SIGN_IN");
    // No config/owner at all: nobody.
    expect(await reason(getProviderProfiles(env, bearer(owner.idToken)))).toBe("NOT_OWNER");
    // Another Google account.
    await db.doc(OWNER_DOC).set({ emails: [owner.email] });
    const other = await googleUser();
    expect(await reason(getProviderProfiles(env, bearer(other.idToken)))).toBe("NOT_OWNER");
    expect(await reason(setProviderProfile(env, bearer(other.idToken), { profile: "off", expect: "flash-lite", confirm: true }))).toBe("NOT_OWNER");
    // A listed uid that signed in some other way (email and password, unverified).
    const pw = await passwordUser();
    await db.doc(OWNER_DOC).set({ uids: [pw.uid], emails: [owner.email] });
    expect(await reason(getProviderProfiles(env, bearer(pw.idToken)))).toBe("NOT_OWNER");
    // An invalid allowlist: nobody.
    await db.doc(OWNER_DOC).set({ emails: owner.email });
    expect(await reason(getProviderProfiles(env, bearer(owner.idToken)))).toBe("NOT_OWNER");
    expect(await runtime()).toBeUndefined();
    expect(await audits()).toEqual([]);
  });

  test("the owner, by email (any case) or uid, sees the profiles and switches with confirm", async () => {
    const env = makeEnv();
    const owner = await googleUser();
    await db.doc(OWNER_DOC).set({ emails: [owner.email.toUpperCase()] });
    const view = await getProviderProfiles(env, bearer(owner.idToken));
    expect(view.active.profile).toBe("flash-lite");
    expect(view.profiles.find((p) => p.id === "openai")).toMatchObject({ ready: false });
    // Google's name was cleared from the Auth user, as for the apps.
    expect((await auth.getUser(owner.uid)).displayName).toBeUndefined();

    expect(await reason(setProviderProfile(env, bearer(owner.idToken), { profile: "off", expect: "flash-lite" }))).toBe("invalid-argument:INVALID_REQUEST");
    expect(await reason(setProviderProfile(env, bearer(owner.idToken), { profile: "openai", expect: "flash-lite", confirm: true }))).toBe("NOT_READY");
    const done = await setProviderProfile(env, bearer(owner.idToken), { profile: "nano-banana", expect: "flash-lite", confirm: true });
    expect(done).toMatchObject({ changed: true, active: { profile: "nano-banana" }, lastChange: { via: "web", from: "flash-lite", to: "nano-banana" } });
    expect(await reason(setProviderProfile(env, bearer(owner.idToken), { profile: "off", expect: "flash-lite", confirm: true }))).toBe("STALE");
    const [audit] = await audits();
    expect(audit.actor).toEqual({ via: "web", uid: owner.uid });

    await db.doc(OWNER_DOC).set({ uids: [owner.uid] });
    expect((await getProviderProfiles(env, bearer(owner.idToken))).active.profile).toBe("nano-banana");

    // Log lines carry a uid hash at most: never the email or the uid.
    const logged = JSON.stringify(env.logs);
    expect(logged).not.toContain(owner.email);
    expect(logged).not.toContain(owner.uid);
    expect(env.logs.find((l) => l.message === "provider_profile_changed")?.data).toMatchObject({ via: "web", uidHash: uidHash(owner.uid) });
  });

  test("a switch needs a recent sign-in; revoked tokens and disabled users are refused", async () => {
    const owner = await googleUser();
    await db.doc(OWNER_DOC).set({ emails: [owner.email] });
    const later = makeEnv({ now: () => Date.now() + 31 * 60_000 });
    expect((await getProviderProfiles(later, bearer(owner.idToken))).active.profile).toBe("flash-lite");
    expect(await reason(setProviderProfile(later, bearer(owner.idToken), { profile: "off", expect: "flash-lite", confirm: true }))).toBe("RECENT_SIGN_IN");

    await new Promise((r) => setTimeout(r, 1100));
    await auth.revokeRefreshTokens(owner.uid);
    expect(await reason(getProviderProfiles(makeEnv(), bearer(owner.idToken)))).toBe("SIGN_IN");

    const second = await googleUser();
    await db.doc(OWNER_DOC).set({ emails: [second.email] });
    await auth.updateUser(second.uid, { disabled: true });
    expect(await reason(getProviderProfiles(makeEnv(), bearer(second.idToken)))).toBe("SIGN_IN");
    expect(await runtime()).toBeUndefined();
  });

  test("parseOwnerDoc and the console's origins", () => {
    expect(parseOwnerDoc({ emails: ["A@Example.com"] })).toEqual({ uids: [], emails: ["a@example.com"] });
    expect(parseOwnerDoc(null)).toBeNull();
    expect(parseOwnerDoc({ uids: "x" })).toBeNull();
    expect(parseOwnerDoc({ uids: Array.from({ length: 11 }, (_, i) => `u${i}`) })).toBeNull();
    expect(parseOwnerDoc({ emails: [""] })).toBeNull();
    expect(ownerConsoleOrigins("bestbefore-f9ee1", false)).toEqual(["https://bestbefore-f9ee1.firebaseapp.com", "https://bestbefore-f9ee1.web.app"]);
    expect(ownerConsoleOrigins("demo-foodtracker", true)).toContain("http://127.0.0.1:5000");
    expect(ownerConsoleOrigins("", false)).toBe(false);
    expect(ownerConsoleOrigins("x.evil.com", false)).toBe(false);
  });
});

// --- The admin command ------------------------------------------------------------------------

describe("npm run admin -- image", () => {
  function cliEnv(): ImageEnv & { lines: string[] } {
    const lines: string[] = [];
    return { ...makeEnv(), print: (l: string) => void lines.push(l), lines };
  }

  test("parses list and use, refuses the rest", () => {
    expect(parseImageArgs(["image", "list"])).toEqual({ command: "list", project: "demo-foodtracker" });
    expect(parseImageArgs(["image", "use", "off", "--apply", "--project", "p"])).toEqual({ command: "use", profile: "off", apply: true, project: "p" });
    expect(parseImageArgs(["image", "use", "off"])).toMatchObject({ apply: false });
    expect(() => parseImageArgs(["image"])).toThrow(AdminRefusal);
    expect(() => parseImageArgs(["image", "use"])).toThrow(AdminRefusal);
    expect(() => parseImageArgs(["image", "list", "--apply"])).toThrow(AdminRefusal);
    expect(() => parseImageArgs(["image", "use", "off", "--force"])).toThrow(AdminRefusal);
  });

  test("list shows the active profile and readiness", async () => {
    const env = cliEnv();
    await runImage(parseImageArgs(["image", "list"]), env);
    const text = env.lines.join("\n");
    expect(text).toContain("Active:      flash-lite: vertex / gemini-3.1-flash-lite-image, fallback gemini-nano-banana-2.1");
    expect(text).toMatch(/\* flash-lite\s+ready/);
    expect(text).toMatch(/openai\s+NOT READY/);
    expect(text).toContain("privacy policy");
    expect(text).toContain("Last change: none recorded");
  });

  test("use: a dry run writes nothing, --apply writes, a refusal is an AdminRefusal", async () => {
    await db.doc(RUNTIME_DOC).set(FULL_CONFIG);
    const dry = cliEnv();
    await runImage(parseImageArgs(["image", "use", "nano-banana"]), dry);
    expect(dry.lines.join("\n")).toContain("Dry run, nothing changed. --apply would write to config/runtime:");
    expect(dry.lines).toContain('  - image > model = "gemini-nano-banana-2.1"');
    expect(await runtime()).toEqual(FULL_CONFIG);
    expect(await audits()).toEqual([]);

    const wet = cliEnv();
    await runImage(parseImageArgs(["image", "use", "nano-banana", "--apply"]), wet);
    expect(wet.lines.join("\n")).toMatch(/Done, written to config\/runtime:[\s\S]*Audit record providerProfileChanges\//);
    expect(((await runtime())!.image as Record<string, unknown>).model).toBe("gemini-nano-banana-2.1");
    expect(await audits()).toHaveLength(1);

    const again = cliEnv();
    await runImage(parseImageArgs(["image", "use", "nano-banana", "--apply"]), again);
    expect(again.lines).toContain("Already in force: nothing to write.");
    expect(await reason(runImage(parseImageArgs(["image", "use", "openai", "--apply"]), cliEnv()))).toMatch(/^admin:Refused \(NOT_READY\)/);
    expect(await reason(runImage(parseImageArgs(["image", "use", "nope"]), cliEnv()))).toMatch(/^admin:Refused \(UNKNOWN_PROFILE\)/);
    expect(await audits()).toHaveLength(1);
  });
});

// --- What the board and the console page show -------------------------------------------------

describe("the board and the console page", () => {
  const NOW = Date.parse("2026-11-15T13:45:00Z");
  const boardOf = (data: Record<string, unknown>) => buildBoard({
    project: "demo-foodtracker",
    generatedAtMs: NOW,
    days: 30,
    config: parseRuntimeConfig(data),
    stats: new Map(boardStatsDays(NOW, 30).map((d) => [d, undefined])),
    purchases: [],
    ledger: { negativeBalanceTotal: 0, checkedAtMs: null },
    holders: { accounts: 0, recipes: 0 },
    reads: 40,
  });

  test("the board shows the active profile read-only and links to the console", () => {
    const b = boardOf({});
    const html = renderBoardHtml(b);
    expect(html).toContain("Gemini 3.1 Flash-Lite Image (Nano Banana 2.1 as fallback)");
    expect(html).toContain('href="https://demo-foodtracker.firebaseapp.com/"');
    expect(html).not.toMatch(/<form|<select|fetch\(/);
    expect(boardJson(b).pictures).toEqual({ profile: "flash-lite", provider: "vertex", model: "gemini-3.1-flash-lite-image", fallbackModel: "gemini-nano-banana-2.1", offered: true });
    expect(boardSummary(boardOf({ image: { provider: "off" } })).join("\n")).toContain("Picture setup: off (off); offered to the apps: no.");
    expect(boardJson(boardOf({ image: { model: "gemini-3-pro-image" } })).pictures).toMatchObject({ profile: "custom", offered: false });
  });

  test("the static page holds no profile, model, address or data, and writes text only", () => {
    const dir = join(__dirname, "../../owner-console");
    const html = readFileSync(join(dir, "index.html"), "utf8");
    const js = readFileSync(join(dir, "console.js"), "utf8");
    for (const p of PROVIDER_PROFILES) {
      expect(html).not.toContain(p.id === "off" ? "Pictures off" : p.id);
      if (p.image.model) expect(html + js).not.toContain(p.image.model);
    }
    expect(html + js).not.toContain("@");
    expect(js).not.toMatch(/innerHTML|outerHTML|insertAdjacentHTML|document\.write|eval\(|localStorage|sessionStorage/);
    expect(js).toContain("inMemoryPersistence");
    const hosting = JSON.parse(readFileSync(join(__dirname, "../../firebase.json"), "utf8")).hosting;
    expect(hosting.public).toBe("owner-console");
    const csp = hosting.headers[0].headers.find((h: { key: string }) => h.key === "Content-Security-Policy").value as string;
    expect(csp).toContain("default-src 'none'");
    expect(csp).toContain("frame-ancestors 'none'");
    expect(csp).not.toContain("unsafe-inline");
  });
});
