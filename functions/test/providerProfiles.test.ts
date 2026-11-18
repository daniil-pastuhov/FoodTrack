/**
 * Provider profiles (src/providers/profiles.ts) and the switch (src/core/providerSwitch.ts): the
 * registry's checks, readiness, the planned writes, the transaction against the emulators (only the
 * picture fields and prices change, an audit record, the log line), the owner gate with real
 * Auth emulator ID tokens (unsigned, not Google, not listed, revoked, disabled, an old sign-in),
 * the admin command's dry run and --apply, and the board and console page showing nothing more.
 */
import { createHash, randomUUID } from "node:crypto";
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
  OWNER_OTHERS_PER_MINUTE,
  OWNER_PER_ADDRESS_PER_MINUTE,
  OwnerCallGate,
  type OwnerCallRequest,
} from "../src/core/ownerCall";
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
  test("is sound, lists the five profiles and matches the deploy defaults", () => {
    expect(registryProblems()).toEqual([]);
    expect(PROVIDER_PROFILES.map((p) => p.id)).toEqual(["flash-lite", "nano-banana", "off", "openai", "flux-eu"]);
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

  test("flux-eu switches once its key is readable (privacy approved 2026-11-18); OpenAI still doesn't", async () => {
    await db.doc(RUNTIME_DOC).set(FULL_CONFIG);
    const env = makeEnv({ secretReadable: async (id) => id === "bfl" || id === "openai" });
    expect((await listProfiles(env)).profiles.map((p) => [p.id, p.ready])).toEqual([["flash-lite", true], ["nano-banana", true], ["off", true], ["openai", false], ["flux-eu", true]]);
    expect(await reason(activateProfile(makeEnv(), "flux-eu", { apply: true, actor: { via: "cli" } }))).toBe("NOT_READY");
    await activateProfile(env, "flux-eu", { apply: true, actor: { via: "cli" } });
    expect((await runtime())?.image).toMatchObject({ provider: "bfl", model: "flux-3-image-klein", fallbackProvider: "vertex", fallbackModel: "gemini-3.1-flash-lite-image" });
  });

  test("listProfiles: the active setup, readiness and the last change", async () => {
    const env = makeEnv();
    let view = await listProfiles(env);
    expect(view.active).toMatchObject({ profile: "flash-lite", provider: "vertex", picturesOn: true, maxStepImages: 2 });
    expect(view.lastChange).toBeNull();
    expect(view.profiles.map((p) => [p.id, p.ready])).toEqual([["flash-lite", true], ["nano-banana", true], ["off", true], ["openai", false], ["flux-eu", false]]);
    await activateProfile(env, "off", { apply: true, actor: { via: "cli" } });
    view = await listProfiles(env);
    expect(view.active).toMatchObject({ profile: "off", picturesOn: false });
    expect(view.lastChange).toMatchObject({ via: "cli", from: "flash-lite", to: "off" });
    await db.doc(RUNTIME_DOC).set({ image: { provider: "vertex", model: "gemini-3-pro-image" } }, { merge: true });
    expect((await listProfiles(env)).active.profile).toBe("custom");
  });
});

// --- The owner gate with Auth emulator tokens ------------------------------------------------

/**
 * A sign-in with an identity provider in the Auth emulator (it accepts an unsigned id_token);
 * with `linkTo` (a session's ID token) the identity is linked to that user instead.
 */
async function idpSignIn(providerId: string, claims: { sub: string; email: string; email_verified: boolean }, linkTo?: string): Promise<{ uid: string; idToken: string }> {
  const res = await fetch(
    `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=fake-api-key`,
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        postBody: `id_token=${encodeURIComponent(JSON.stringify({ ...claims, name: "Some Name" }))}&providerId=${providerId}`,
        requestUri: "http://localhost",
        returnSecureToken: true,
        returnIdpCredential: true,
        ...(linkTo ? { idToken: linkTo } : {}),
      }),
    },
  );
  const json = (await res.json()) as { localId: string; idToken: string };
  if (!res.ok) throw new Error(`signInWithIdp failed: ${JSON.stringify(json)}`);
  return { uid: json.localId, idToken: json.idToken };
}

/** A Google sign-in in the Auth emulator. */
async function googleUser(email = `owner-${randomUUID()}@example.com`, emailVerified = true): Promise<{ uid: string; idToken: string; email: string; sub: string }> {
  const sub = randomUUID();
  return { ...(await idpSignIn("google.com", { sub, email, email_verified: emailVerified })), email, sub };
}

const newEmail = (who: string) => `${who}-${randomUUID()}@example.com`;

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

  test("a Google sign-in whose email is not verified is refused, listed by uid or by email", async () => {
    const env = makeEnv();
    const owner = await googleUser(newEmail("unverified"), false);
    const token = await auth.verifyIdToken(owner.idToken);
    expect(token.firebase.sign_in_provider).toBe("google.com");
    expect(token.email_verified).toBe(false);
    await db.doc(OWNER_DOC).set({ uids: [owner.uid], emails: [owner.email] });
    expect(await reason(getProviderProfiles(env, bearer(owner.idToken)))).toBe("NOT_OWNER");
    expect(await reason(setProviderProfile(env, bearer(owner.idToken), { profile: "off", expect: "flash-lite", confirm: true }))).toBe("NOT_OWNER");
    // Refused before anything else: Google's name stays on the user (no write for a non-owner).
    expect((await auth.getUser(owner.uid)).displayName).toBe("Some Name");
    expect(await runtime()).toBeUndefined();
  });

  test("an account whose own email is another sign-in's: only its Google identity's email counts", async () => {
    const env = makeEnv();
    const appleEmail = newEmail("apple");
    const googleEmail = newEmail("google");
    // Made with Apple (verified email A), then Google (email B) linked to it.
    const apple = await idpSignIn("apple.com", { sub: randomUUID(), email: appleEmail, email_verified: true });
    const googleSub = randomUUID();
    await idpSignIn("google.com", { sub: googleSub, email: googleEmail, email_verified: true }, apple.idToken);
    const session = await idpSignIn("google.com", { sub: googleSub, email: googleEmail, email_verified: true });
    expect(session.uid).toBe(apple.uid);
    const token = await auth.verifyIdToken(session.idToken);
    expect(token.firebase.sign_in_provider).toBe("google.com");
    // The scenario: the account's (and the token's) email is the Apple one, and verified.
    expect(token.email).toBe(appleEmail);
    expect(token.email_verified).toBe(true);
    expect((await auth.getUser(apple.uid)).email).toBe(appleEmail);

    await db.doc(OWNER_DOC).set({ emails: [appleEmail] });
    expect(await reason(getProviderProfiles(env, bearer(session.idToken)))).toBe("NOT_OWNER");
    await db.doc(OWNER_DOC).set({ emails: [googleEmail.toUpperCase()] });
    expect((await getProviderProfiles(env, bearer(session.idToken))).active.profile).toBe("flash-lite");
    // The Apple session itself is not a Google sign-in, whatever the list says.
    await db.doc(OWNER_DOC).set({ uids: [apple.uid], emails: [googleEmail, appleEmail] });
    expect(await reason(getProviderProfiles(env, bearer(apple.idToken)))).toBe("NOT_OWNER");
  });

  test("unlink and relink: the Google identity linked now decides, never the email left behind", async () => {
    const env = makeEnv();
    const first = await googleUser(newEmail("first"));
    // Google account G1 unlinked: the user keeps G1's email but has no Google identity.
    await auth.updateUser(first.uid, { providersToUnlink: ["google.com"] });
    expect((await auth.getUser(first.uid)).email).toBe(first.email);
    expect((await auth.getUser(first.uid)).providerData.map((p) => p.providerId)).not.toContain("google.com");
    await db.doc(OWNER_DOC).set({ uids: [first.uid], emails: [first.email] });
    // G1's session token is still valid (not revoked), but the user has no Google identity now.
    expect(await reason(getProviderProfiles(env, bearer(first.idToken)))).toBe("NOT_OWNER");

    // Google account G2 linked: the account email is still G1's.
    const secondEmail = newEmail("second");
    const secondSub = randomUUID();
    await auth.updateUser(first.uid, { providerToLink: { providerId: "google.com", uid: secondSub, email: secondEmail } });
    const session = await idpSignIn("google.com", { sub: secondSub, email: secondEmail, email_verified: true });
    expect(session.uid).toBe(first.uid);
    expect((await auth.verifyIdToken(session.idToken)).email).toBe(first.email);
    await db.doc(OWNER_DOC).set({ emails: [first.email] });
    expect(await reason(getProviderProfiles(env, bearer(session.idToken)))).toBe("NOT_OWNER");
    await db.doc(OWNER_DOC).set({ emails: [secondEmail] });
    expect((await getProviderProfiles(env, bearer(session.idToken))).active.profile).toBe("flash-lite");
    // Listed by uid, the account passes with any Google session of it, since it has one again.
    await db.doc(OWNER_DOC).set({ uids: [first.uid] });
    expect((await getProviderProfiles(env, bearer(session.idToken))).active.profile).toBe("flash-lite");
  });

  test("an empty allowlist is nobody", async () => {
    const owner = await googleUser();
    await db.doc(OWNER_DOC).set({ uids: [], emails: [] });
    expect(await reason(getProviderProfiles(makeEnv(), bearer(owner.idToken)))).toBe("NOT_OWNER");
    await db.doc(OWNER_DOC).set({});
    expect(await reason(getProviderProfiles(makeEnv(), bearer(owner.idToken)))).toBe("NOT_OWNER");
  });

  test("parseOwnerDoc and the console's origins", () => {
    expect(parseOwnerDoc({ emails: ["A@Example.com"] })).toEqual({ uids: [], emails: ["a@example.com"] });
    expect(parseOwnerDoc(null)).toBeNull();
    expect(parseOwnerDoc({ uids: "x" })).toBeNull();
    expect(parseOwnerDoc({ uids: Array.from({ length: 11 }, (_, i) => `u${i}`) })).toBeNull();
    expect(parseOwnerDoc({ emails: [""] })).toBeNull();
    expect(ownerConsoleOrigins("bestbefore-f9ee1", false)).toEqual(["https://bestbefore-f9ee1.firebaseapp.com", "https://bestbefore-f9ee1.web.app"]);
    expect(ownerConsoleOrigins("demo-foodtracker", true)).toContain("http://127.0.0.1:5050");
    expect(ownerConsoleOrigins("", false)).toBe(false);
    expect(ownerConsoleOrigins("x.evil.com", false)).toBe(false);
  });
});

// --- The callables' gate (src/core/ownerCall.ts, used by src/index.ts) ------------------------

describe("OwnerCallGate", () => {
  // At the start of a minute, so no test crosses a throttle window.
  const NOW = Math.floor(Date.now() / 60_000) * 60_000 + 1_000;
  const gateEnv = () => {
    const env = makeEnv({ now: () => NOW });
    return { env, gate: new OwnerCallGate(env) };
  };
  const req = (ip: string, auth?: OwnerCallRequest["auth"], authorization?: string): OwnerCallRequest => ({
    ...(auth ? { auth } : {}),
    rawRequest: { headers: { "x-forwarded-for": ip, ...(authorization ? { authorization } : {}) }, socket: { remoteAddress: "10.0.0.1" } },
  });
  const anyone = (n: number | string) => ({ uid: `u-${n}`, token: { email: `x${n}@example.com`, firebase: { sign_in_provider: "anonymous" } } });
  const ok = async () => "ok";

  test("a call without a token is refused with SIGN_IN, logged, and not counted", async () => {
    const { env, gate } = gateEnv();
    for (let i = 0; i < OWNER_OTHERS_PER_MINUTE * 3; i++) {
      expect(await reason(gate.run("getProviderProfiles", req("203.0.113.7"), ok))).toBe("SIGN_IN");
    }
    // Neither the address nor the instance ceiling was used up.
    expect(await gate.run("getProviderProfiles", req("203.0.113.7", anyone(1)), ok)).toBe("ok");
    const refused = env.logs.filter((l) => l.message === "owner_call_refused");
    expect(refused.length).toBe(10);
    expect(refused[0].data).toEqual({ function: "getProviderProfiles", reason: "SIGN_IN", suppressedSinceLast: 0 });
  });

  test("per address below the instance ceiling; the ceiling holds across addresses", async () => {
    const { env, gate } = gateEnv();
    expect(OWNER_PER_ADDRESS_PER_MINUTE).toBeLessThan(OWNER_OTHERS_PER_MINUTE);
    for (let i = 0; i < OWNER_PER_ADDRESS_PER_MINUTE; i++) expect(await gate.run("f", req("198.51.100.1", anyone(i)), ok)).toBe("ok");
    expect(await reason(gate.run("f", req("198.51.100.1", anyone("next")), ok))).toBe("resource-exhausted:RATE_LIMITED");
    // An IPv6 /64 is one address.
    for (let i = 0; i < OWNER_PER_ADDRESS_PER_MINUTE; i++) expect(await gate.run("f", req(`2001:db8:1:2::${i + 1}`, anyone(`v6-${i}`)), ok)).toBe("ok");
    expect(await reason(gate.run("f", req("2001:db8:1:2::ff", anyone("v6-next")), ok))).toBe("resource-exhausted:RATE_LIMITED");
    // Other addresses, until the instance ceiling (the refused calls above counted toward it).
    let admitted = 2 * OWNER_PER_ADDRESS_PER_MINUTE;
    let counted = admitted + 2;
    for (let i = 0; counted < OWNER_OTHERS_PER_MINUTE; i++, counted++, admitted++) {
      expect(await gate.run("f", req(`192.0.2.${i + 1}`, anyone(`c${i}`)), ok)).toBe("ok");
    }
    expect(await reason(gate.run("f", req("192.0.2.200", anyone("late")), ok))).toBe("resource-exhausted:RATE_LIMITED");
    expect(admitted).toBeLessThanOrEqual(OWNER_OTHERS_PER_MINUTE);
    expect(env.logs.filter((l) => l.message === "owner_call_refused").map((l) => l.data?.reason)).toEqual(["RATE_LIMITED", "RATE_LIMITED", "RATE_LIMITED"]);
  });

  test("nobody else's calls starve a listed owner", async () => {
    const { gate } = gateEnv();
    const byEmail = newEmail("owner");
    await db.doc(OWNER_DOC).set({ uids: ["owner-uid"], emails: [byEmail] });
    // Others use up the instance ceiling.
    for (let i = 0; i < OWNER_OTHERS_PER_MINUTE; i++) await gate.run("f", req(`192.0.2.${i + 1}`, anyone(i)), ok);
    expect(await reason(gate.run("f", req("192.0.2.250", anyone("late")), ok))).toBe("resource-exhausted:RATE_LIMITED");
    // Listed by uid, from an address that is also used up.
    expect(await gate.run("f", req("192.0.2.1", { uid: "owner-uid", token: { firebase: { sign_in_provider: "google.com" } } }), ok)).toBe("ok");
    // Listed by email, a Google session (the bucket only: the core decides who is an owner).
    expect(await gate.run("f", req("192.0.2.1", { uid: "email-owner", token: { email: byEmail, firebase: { sign_in_provider: "google.com" } } }), ok)).toBe("ok");
    // The same email on a session that is not Google gets no owner bucket.
    expect(await reason(gate.run("f", req("192.0.2.251", { uid: "apple-user", token: { email: byEmail, firebase: { sign_in_provider: "apple.com" } } }), ok))).toBe("resource-exhausted:RATE_LIMITED");
  });

  test("a uid the core let in keeps the owner's bucket on this instance", async () => {
    const { gate } = gateEnv();
    const owner = { uid: "let-in", token: { firebase: { sign_in_provider: "google.com" } } };
    expect(await gate.run("f", req("192.0.2.9", owner), ok)).toBe("ok");
    for (let i = 0; i < OWNER_OTHERS_PER_MINUTE; i++) await gate.run("f", req(`198.51.100.${i + 1}`, anyone(i)), ok).catch(() => undefined);
    expect(await gate.run("f", req("192.0.2.9", owner), ok)).toBe("ok");
  });

  test("with the real core: the owner gets through, refusals are logged by reason, no log carries a uid or email", async () => {
    const { env, gate } = gateEnv();
    const owner = await googleUser(newEmail("owner"));
    const other = await googleUser(newEmail("other"));
    await db.doc(OWNER_DOC).set({ emails: [owner.email] });
    const call = (u: { uid: string; idToken: string; email: string }, ip: string) =>
      gate.run("getProviderProfiles", req(ip, { uid: u.uid, token: { email: u.email, firebase: { sign_in_provider: "google.com" } } }, bearer(u.idToken)),
        (authorization) => getProviderProfiles(env, authorization));
    expect((await call(owner, "192.0.2.1")).active.profile).toBe("flash-lite");
    expect(await reason(call(other, "192.0.2.2"))).toBe("NOT_OWNER");
    expect(await reason(gate.run("setProviderProfile", req("192.0.2.2", { uid: other.uid }, "Bearer garbage"),
      (authorization) => setProviderProfile(env, authorization, {})))).toBe("SIGN_IN");
    const refused = env.logs.filter((l) => l.message === "owner_call_refused").map((l) => l.data);
    // Past the throttle every refusal is logged, with the caller's uid hash (never the uid).
    expect(refused).toEqual([
      { function: "getProviderProfiles", reason: "NOT_OWNER", uidHash: uidHash(other.uid) },
      { function: "setProviderProfile", reason: "SIGN_IN", uidHash: uidHash(other.uid) },
    ]);
    const logged = JSON.stringify(env.logs);
    for (const s of [owner.uid, owner.email, other.uid, other.email]) expect(logged).not.toContain(s);
  });

  test("src/index.ts binds both callables through the gate, one instance each, no App Check", () => {
    const index = readFileSync(join(__dirname, "../src/index.ts"), "utf8");
    expect(index).toContain("return guarded(name, () => ownerGate().run(name, request, (authorization) => fn(ownerEnv(), authorization)));");
    expect(index).toMatch(/const ownerCallable = \{\s*enforceAppCheck: false,\s*invoker: "public",\s*cors: ownerConsoleOrigins\(projectId\(\), process\.env\.FUNCTIONS_EMULATOR === "true"\),\s*timeoutSeconds: 30,\s*maxInstances: 1,\s*concurrency: 1,\s*\} as const;/);
    expect(index).toMatch(/export const getProviderProfiles = onCall\(ownerCallable, \(request\) =>\s*ownerCall\("getProviderProfiles", request,/);
    expect(index).toMatch(/export const setProviderProfile = onCall\(ownerCallable, \(request\) =>\s*ownerCall\("setProviderProfile", request,/);
    // Nothing of the caller's is logged there.
    const section = index.slice(index.indexOf("// --- The owner console"), index.indexOf("function decodePubSub"));
    expect(section).not.toMatch(/logger\.(info|warn|error)\(/);
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
  });

  test("the SDK is served from the site, as recorded in its SOURCE.txt", () => {
    const dir = join(__dirname, "../../owner-console");
    const js = readFileSync(join(dir, "console.js"), "utf8");
    const imports = [...js.matchAll(/from "([^"]+)"/g)].map((m) => m[1]);
    expect(imports).toEqual(["/vendor/firebasejs/13.4.0/firebase-app.js", "/vendor/firebasejs/13.4.0/firebase-auth.js"]);
    const vendor = join(dir, "vendor/firebasejs/13.4.0");
    const note = readFileSync(join(vendor, "SOURCE.txt"), "utf8");
    const sha = (f: string) => createHash("sha256").update(readFileSync(join(vendor, f))).digest("hex");
    expect(note).toContain(`${sha("firebase-app.js")} (served here unchanged)`);
    expect(note).toContain(`${sha("firebase-auth.js")} as served here`);
    // Nothing is loaded from elsewhere but the one Google sign-in script the SDK adds itself.
    const auth = readFileSync(join(vendor, "firebase-auth.js"), "utf8");
    expect(auth).not.toContain("www.gstatic.com");
    expect(auth.startsWith('import{') && auth.includes('from"./firebase-app.js"')).toBe(true);
  });

  test("Hosting's headers cover the console's own files only, never Hosting's reserved /__/ paths", () => {
    const root = join(__dirname, "../..");
    const hosting = JSON.parse(readFileSync(join(root, "firebase.json"), "utf8")).hosting;
    expect(hosting.public).toBe("owner-console");
    expect(hosting.headers).toHaveLength(1);
    const rule = hosting.headers[0];
    expect(rule.source).toBeUndefined();
    const re = new RegExp(rule.regex);
    for (const p of ["/", "/index.html", "/console.js", "/console.css", "/vendor/firebasejs/13.4.0/firebase-app.js", "/vendor/firebasejs/13.4.0/firebase-auth.js"]) {
      expect(re.test(p), p).toBe(true);
    }
    for (const p of ["/__/auth/handler", "/__/auth/iframe", "/__/auth/handler.js", "/__/firebase/init.json", "/__/auth/iframe?apiKey=x", "/x/index.html", "/index.html/x"]) {
      expect(re.test(p), p).toBe(false);
    }
    const csp = rule.headers.find((h: { key: string }) => h.key === "Content-Security-Policy").value as string;
    expect(csp).toContain("default-src 'none'");
    expect(csp).toContain("frame-ancestors 'none'");
    expect(csp).toMatch(/script-src 'self' https:\/\/apis\.google\.com;/);
    expect(csp).not.toContain("gstatic");
    expect(csp).not.toContain("unsafe-inline");
    expect(csp).not.toContain("127.0.0.1");

    // firebase.local.json (the Hosting emulator only) is the same but for the emulators in the CSP.
    const local = JSON.parse(readFileSync(join(root, "firebase.local.json"), "utf8"));
    expect(Object.keys(local).sort()).toEqual(["emulators", "hosting"]);
    const localCsp = local.hosting.headers[0].headers.find((h: { key: string }) => h.key === "Content-Security-Policy").value;
    expect(localCsp).toBe(csp
      .replace("connect-src 'self'", "connect-src 'self' http://127.0.0.1:9099 http://127.0.0.1:5001")
      .replace("frame-src ", "frame-src http://127.0.0.1:9099 "));
    const withoutCsp = (h: typeof hosting) => JSON.stringify({ ...h, headers: h.headers.map((r: typeof rule) => ({ ...r, headers: r.headers.filter((x: { key: string }) => x.key !== "Content-Security-Policy") })) });
    expect(withoutCsp(local.hosting)).toBe(withoutCsp(hosting));
    expect(local.emulators.hosting.port).toBe(5050);
    expect(ownerConsoleOrigins("demo-foodtracker", true)).toContain(`http://127.0.0.1:${local.emulators.hosting.port}`);
  });
});
