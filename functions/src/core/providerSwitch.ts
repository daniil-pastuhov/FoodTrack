/**
 * Switching the provider setup by named profile (providers/profiles.ts; README "Provider
 * profiles"): the one core behind `npm run admin -- image list | use <id>` and the owner
 * console's callables `getProviderProfiles` / `setProviderProfile`.
 *
 * `activateProfile` checks the profile is ready, then in one transaction reads config/runtime,
 * plans the writes (only `image.provider`, `image.model`, `image.fallbackModel` and, where the
 * price in force is missing or lower, `prices.imageUsdPerImage.<provider/model>`), checks that the
 * document as it would be parses to exactly that setup with no new invalid field, writes it with
 * field paths (every other field untouched), and adds an audit record to
 * `providerProfileChanges/`; then logs `provider_profile_changed`. The instances' config cache
 * (30 s) is the only delay.
 *
 * The owner gate (`ownerCallerFromToken`, `assertOwner`) is for the web callables: a Firebase ID
 * token, checked again here with revocation, of a Google sign-in (verified email) whose uid or
 * email is in `config/owner` (`uids` / `emails`), a Firestore document only the owner edits in
 * the console. Missing or invalid: nobody is an owner (fail closed).
 */
import { FieldPath, Timestamp, type Firestore } from "firebase-admin/firestore";
import type { Auth } from "firebase-admin/auth";
import { HttpsError } from "firebase-functions/v2/https";
import { DEFAULT_IMAGE_SELECTION, DEFAULT_PRICES } from "../constants";
import { imageMicroUsdOf, parseRuntimeConfig, uidHash, type ImageSelection } from "../model";
import type { Logger } from "../ports";
import {
  PROVIDER_PROFILES,
  activeProfileOf,
  profileById,
  readinessOf,
  type ProfileSecret,
  type ProviderProfile,
  type Readiness,
} from "../providers/profiles";

export const RUNTIME_DOC = "config/runtime";
export const OWNER_DOC = "config/owner";
export const PROFILE_CHANGES = "providerProfileChanges";
/** A switch from the console needs a Google sign-in made at most this long ago. */
export const OWNER_RECENT_SIGN_IN_S = 30 * 60;
/** Entries of `config/owner.uids` / `emails` at most. */
export const OWNER_LIST_MAX = 10;

/** Reasons the owner callables and the command refuse with (HttpsError details.reason). */
export type SwitchRefusal =
  | "SIGN_IN" // no or unusable token
  | "NOT_OWNER"
  | "RECENT_SIGN_IN"
  | "UNKNOWN_PROFILE"
  | "NOT_READY"
  | "STALE" // the active setup is no longer the one the caller saw
  | "PRICES_INVALID" // config/runtime.prices is invalid: a price can't be added to it
  | "INVALID_RESULT"; // the document as it would be does not parse to the profile

export class SwitchRefused extends HttpsError {
  constructor(code: ConstructorParameters<typeof HttpsError>[0], readonly reason: SwitchRefusal, message: string) {
    super(code, message, { reason });
  }
}

export interface SwitchEnv {
  db: Firestore;
  now(): number;
  logger: Logger;
  /** The deploy parameters' picture defaults (IMAGE_PROVIDER / IMAGE_MODEL / IMAGE_FALLBACK_MODEL). */
  imageDefaults?: ImageSelection;
  /** Whether the functions' service account can read the secret now; never returns its value. */
  secretReadable(secret: ProfileSecret): Promise<boolean>;
}

/** Who switched: the console (an owner's uid) or the admin command (the operator's credentials). */
export type Actor = { via: "web"; uid: string } | { via: "cli" };

export interface SetupView {
  /** The matching profile's id, or "custom" when config/runtime.image was set by hand. */
  profile: string;
  label: string;
  provider: string;
  model: string;
  fallbackModel: string;
  maxStepImages: number;
  /** Whether the apps are offered pictures now (a priced provider and `costs.recipeImage` on). */
  picturesOn: boolean;
}

export interface ProfileView {
  id: string;
  label: string;
  summary: string;
  provider: string;
  model: string;
  fallbackModel: string;
  usdPerImage: Record<string, number>;
  ready: boolean;
  missing: string[];
}

export interface ChangeView {
  at: string;
  via: string;
  from: string;
  to: string;
}

export interface ProfilesView {
  active: SetupView;
  profiles: ProfileView[];
  lastChange: ChangeView | null;
}

// --- The owner gate ---------------------------------------------------------------------------

export interface OwnerCaller {
  uid: string;
  email: string | null;
  emailVerified: boolean;
  signInProvider: string;
  /** Seconds since the epoch of the sign-in itself (not of the token's refresh). */
  authTime: number;
  /** The token still carries Google's name or photo (cleared from the Auth user, as for the apps). */
  profileClaims: boolean;
}

/**
 * The caller from an `Authorization: Bearer <Firebase ID token>` header, verified with revocation
 * (a disabled user or revoked tokens are refused). Throws SIGN_IN for anything else.
 */
export async function ownerCallerFromToken(auth: Auth, header: string | undefined): Promise<OwnerCaller> {
  const m = /^Bearer\s+([A-Za-z0-9._-]+)$/.exec(header ?? "");
  if (!m) throw new SwitchRefused("unauthenticated", "SIGN_IN", "Sign in with Google");
  let t;
  try {
    t = await auth.verifyIdToken(m[1], true);
  } catch {
    throw new SwitchRefused("unauthenticated", "SIGN_IN", "Sign in with Google");
  }
  return {
    uid: t.uid,
    email: typeof t.email === "string" ? t.email : null,
    emailVerified: t.email_verified === true,
    signInProvider: t.firebase?.sign_in_provider ?? "",
    authTime: typeof t.auth_time === "number" ? t.auth_time : 0,
    profileClaims: Boolean(t.name || t.picture),
  };
}

/** `config/owner`: lists of uids and lower-cased emails, each at most OWNER_LIST_MAX; null when invalid. */
export function parseOwnerDoc(data: unknown): { uids: string[]; emails: string[] } | null {
  if (typeof data !== "object" || data === null || Array.isArray(data)) return null;
  const d = data as Record<string, unknown>;
  const list = (v: unknown): string[] | null => {
    if (v === undefined) return [];
    if (!Array.isArray(v) || v.length > OWNER_LIST_MAX) return null;
    if (!v.every((s) => typeof s === "string" && s.length > 0 && s.length <= 254)) return null;
    return v as string[];
  };
  const uids = list(d.uids);
  const emails = list(d.emails);
  if (!uids || !emails) return null;
  return { uids, emails: emails.map((e) => e.trim().toLowerCase()) };
}

/**
 * Refuses (NOT_OWNER) anyone but a Google sign-in with a verified email whose uid or email is in
 * `config/owner`, and (RECENT_SIGN_IN) a write whose sign-in is older than OWNER_RECENT_SIGN_IN_S.
 */
export async function assertOwner(db: Firestore, caller: OwnerCaller, opts: { write: boolean; nowMs: number }): Promise<void> {
  const owners = parseOwnerDoc((await db.doc(OWNER_DOC).get()).data() ?? null);
  const google = caller.signInProvider === "google.com" && caller.emailVerified;
  const listed = owners !== null &&
    (owners.uids.includes(caller.uid) || (caller.email !== null && owners.emails.includes(caller.email.toLowerCase())));
  if (!google || !listed) throw new SwitchRefused("permission-denied", "NOT_OWNER", "This Google account may not use the owner console");
  if (opts.write && opts.nowMs / 1000 - caller.authTime > OWNER_RECENT_SIGN_IN_S) {
    throw new SwitchRefused("failed-precondition", "RECENT_SIGN_IN", "Sign in again to switch (the sign-in is more than 30 minutes old)");
  }
}

// --- Listing ----------------------------------------------------------------------------------

function setupOf(data: Record<string, unknown>, imageDefaults: ImageSelection | undefined): SetupView {
  const config = parseRuntimeConfig(data, undefined, imageDefaults ?? DEFAULT_IMAGE_SELECTION);
  const p = activeProfileOf(config.image);
  return {
    profile: p?.id ?? "custom",
    label: p?.label ?? "Set by hand (no profile)",
    provider: config.image.provider,
    model: config.image.model,
    fallbackModel: config.image.fallbackModel,
    maxStepImages: config.image.maxStepImages,
    picturesOn: imageMicroUsdOf(config) !== null && config.costs.recipeImage !== undefined,
  };
}

function profileView(p: ProviderProfile, r: Readiness): ProfileView {
  return {
    id: p.id,
    label: p.label,
    summary: p.summary,
    provider: p.image.provider,
    model: p.image.model ?? "",
    fallbackModel: p.image.fallbackModel ?? "",
    usdPerImage: { ...p.image.usdPerImage },
    ready: r.ready,
    missing: r.missing,
  };
}

async function lastChangeOf(db: Firestore): Promise<ChangeView | null> {
  const snap = await db.collection(PROFILE_CHANGES).orderBy("at", "desc").limit(1).get();
  const d = snap.docs[0]?.data();
  if (!d) return null;
  return {
    at: d.at instanceof Timestamp ? d.at.toDate().toISOString() : "",
    via: String(d.actor?.via ?? ""),
    from: String(d.from?.profile ?? ""),
    to: String(d.to?.profile ?? ""),
  };
}

export async function listProfiles(env: SwitchEnv): Promise<ProfilesView> {
  const [snap, lastChange, readiness] = await Promise.all([
    env.db.doc(RUNTIME_DOC).get(),
    lastChangeOf(env.db),
    Promise.all(PROVIDER_PROFILES.map((p) => readinessOf(p, (s) => env.secretReadable(s)))),
  ]);
  return {
    active: setupOf((snap.data() ?? {}) as Record<string, unknown>, env.imageDefaults),
    profiles: PROVIDER_PROFILES.map((p, i) => profileView(p, readiness[i])),
    lastChange,
  };
}

// --- Planning and activating ------------------------------------------------------------------

/** One write, as a field path into config/runtime and its new value. */
export interface PlannedWrite {
  path: string[];
  value: unknown;
}

export interface Plan {
  from: SetupView;
  to: SetupView;
  /** Empty when the profile is already in force. */
  writes: PlannedWrite[];
}

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);

/** The fields `parseRuntimeConfig` reports invalid in `data`. */
function invalidFields(data: Record<string, unknown>, imageDefaults: ImageSelection): string[] {
  let fields: string[] = [];
  parseRuntimeConfig(data, {
    info: () => undefined,
    error: () => undefined,
    warn: (_m, d) => {
      fields = Array.isArray(d?.fields) ? (d.fields as string[]) : [];
    },
  }, imageDefaults);
  return fields;
}

function withWrites(data: Record<string, unknown>, writes: PlannedWrite[]): Record<string, unknown> {
  const out = structuredClone(data);
  for (const w of writes) {
    let at: Record<string, unknown> = out;
    for (const seg of w.path.slice(0, -1)) {
      if (!isObj(at[seg])) at[seg] = {};
      at = at[seg] as Record<string, unknown>;
    }
    at[w.path[w.path.length - 1]] = structuredClone(w.value);
  }
  return out;
}

/**
 * Pure: the writes that put `profile` in force over the document `data` (config/runtime as
 * stored), checked by parsing the result. Throws PRICES_INVALID or INVALID_RESULT.
 */
export function planProfile(data: Record<string, unknown>, profile: ProviderProfile, imageDefaults: ImageSelection = DEFAULT_IMAGE_SELECTION): Plan {
  const from = setupOf(data, imageDefaults);
  const img = profile.image;
  const writes: PlannedWrite[] = [];
  const before = parseRuntimeConfig(data, undefined, imageDefaults);

  // The picture part. Field paths keep image.maxStepImages and anything else in the map; an
  // image value that is not a map (invalid, pictures off) is replaced as a whole.
  const imageFields: Record<string, string> = img.provider === "off"
    ? { provider: "off" }
    : { provider: img.provider, model: img.model!, fallbackModel: img.fallbackModel ?? "" };
  const stored = data.image;
  const inForce = before.image.provider === img.provider &&
    (img.provider === "off" || (before.image.model === img.model && before.image.fallbackModel === (img.fallbackModel ?? "")));
  if (!inForce) {
    if (stored !== undefined && !isObj(stored)) {
      writes.push({ path: ["image"], value: imageFields });
    } else {
      for (const [k, v] of Object.entries(imageFields)) {
        if (!isObj(stored) || stored[k] !== v) writes.push({ path: ["image", k], value: v });
      }
    }
  }

  // The prices: at least the profile's reserved price in force for each model it uses.
  const prices = before.prices.imageUsdPerImage ?? {};
  const raise = Object.entries(img.usdPerImage).filter(([key, usd]) => !(prices[key] !== undefined && prices[key] >= usd));
  if (raise.length) {
    const storedPrices = data.prices;
    if (storedPrices === undefined || storedPrices === null) {
      // No prices map yet: the token prices are required beside it, so the defaults are written out.
      writes.push({
        path: ["prices"],
        value: { inputUsdPerMTok: DEFAULT_PRICES.inputUsdPerMTok, outputUsdPerMTok: DEFAULT_PRICES.outputUsdPerMTok, imageUsdPerImage: Object.fromEntries(raise) },
      });
    } else if (invalidFields(data, imageDefaults).includes("prices") || !isObj(storedPrices)) {
      throw new SwitchRefused("failed-precondition", "PRICES_INVALID",
        "config/runtime.prices is invalid, so a picture price can't be added to it; fix it first (README \"config/runtime\")");
    } else if (storedPrices.imageUsdPerImage === undefined || storedPrices.imageUsdPerImage === null) {
      writes.push({ path: ["prices", "imageUsdPerImage"], value: Object.fromEntries(raise) });
    } else {
      for (const [key, usd] of raise) writes.push({ path: ["prices", "imageUsdPerImage", key], value: usd });
    }
  }

  // The check: the document as it would be selects exactly this profile, priced, with no field
  // newly invalid.
  const next = withWrites(data, writes);
  const to = setupOf(next, imageDefaults);
  const after = parseRuntimeConfig(next, undefined, imageDefaults);
  const newlyInvalid = invalidFields(next, imageDefaults).filter((f) => !invalidFields(data, imageDefaults).includes(f));
  const priced = img.provider === "off" || imageMicroUsdOf(after) !== null;
  const selects = after.image.provider === img.provider &&
    (img.provider === "off" || (after.image.model === img.model && after.image.fallbackModel === (img.fallbackModel ?? "")));
  if (!selects || !priced || newlyInvalid.length) {
    throw new SwitchRefused("failed-precondition", "INVALID_RESULT",
      `config/runtime would not select ${profile.id} as written${newlyInvalid.length ? ` (invalid: ${newlyInvalid.join(", ")})` : ""}; nothing changed`);
  }
  return { from, to, writes };
}

export interface ActivateOptions {
  apply: boolean;
  actor: Actor;
  /** The active profile id the caller saw; a different one now refuses with STALE. */
  expect?: string;
}

export interface ActivateResult {
  applied: boolean;
  changed: boolean;
  plan: Plan;
  readiness: Readiness;
  /** The audit record's id (providerProfileChanges/<id>), when written. */
  changeId?: string;
}

/**
 * Puts a profile in force (README "Provider profiles"). A dry run (`apply: false`) plans and
 * checks everything, refusals included, and writes nothing.
 */
export async function activateProfile(env: SwitchEnv, id: unknown, opts: ActivateOptions): Promise<ActivateResult> {
  const profile = profileById(id);
  if (!profile) throw new SwitchRefused("invalid-argument", "UNKNOWN_PROFILE", `No such profile; one of: ${PROVIDER_PROFILES.map((p) => p.id).join(", ")}`);
  const readiness = await readinessOf(profile, (s) => env.secretReadable(s));
  if (!readiness.ready) {
    throw new SwitchRefused("failed-precondition", "NOT_READY", `${profile.id} is not ready: ${readiness.missing.join("; ")}`);
  }
  const ref = env.db.doc(RUNTIME_DOC);
  const auditRef = env.db.collection(PROFILE_CHANGES).doc();
  const imageDefaults = env.imageDefaults ?? DEFAULT_IMAGE_SELECTION;
  const result = await env.db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const data = (snap.data() ?? {}) as Record<string, unknown>;
    const plan = planProfile(data, profile, imageDefaults);
    if (opts.expect !== undefined && plan.from.profile !== opts.expect) {
      throw new SwitchRefused("aborted", "STALE", `The active setup is now ${plan.from.profile}, not ${opts.expect}; reload and try again`);
    }
    if (!opts.apply || plan.writes.length === 0) return { plan, changed: plan.writes.length > 0, written: false };
    if (!snap.exists) {
      tx.set(ref, withWrites({}, plan.writes));
    } else {
      const [first, ...rest] = plan.writes;
      const more = rest.flatMap((w) => [new FieldPath(...w.path), w.value]);
      tx.update(ref, new FieldPath(...first.path), first.value, ...more);
    }
    const strip = (s: SetupView) => ({ profile: s.profile, provider: s.provider, model: s.model, fallbackModel: s.fallbackModel });
    tx.create(auditRef, {
      at: Timestamp.fromMillis(env.now()),
      actor: opts.actor,
      from: strip(plan.from),
      to: strip(plan.to),
      writes: plan.writes.map((w) => ({ path: w.path.join(" > "), value: w.value })),
    });
    return { plan, changed: true, written: true };
  });
  if (result.written) {
    env.logger.info("provider_profile_changed", {
      via: opts.actor.via,
      ...(opts.actor.via === "web" ? { uidHash: uidHash(opts.actor.uid) } : {}),
      from: result.plan.from.profile,
      to: result.plan.to.profile,
      provider: result.plan.to.provider,
      model: result.plan.to.model,
      fallbackModel: result.plan.to.fallbackModel,
      change: auditRef.id,
    });
  }
  return {
    applied: result.written,
    changed: result.changed,
    plan: result.plan,
    readiness,
    ...(result.written ? { changeId: auditRef.id } : {}),
  };
}

/** The owner console's origins: the project's default Hosting site (and the local Hosting emulator). */
export function ownerConsoleOrigins(project: string, emulator: boolean): string[] | false {
  if (!/^[a-z][a-z0-9-]{4,29}$/.test(project)) return false;
  return [
    `https://${project}.firebaseapp.com`,
    `https://${project}.web.app`,
    ...(emulator ? ["http://127.0.0.1:5000", "http://localhost:5000"] : []),
  ];
}

// --- The two callables' bodies (src/index.ts binds them) --------------------------------------

export interface OwnerEnv extends SwitchEnv {
  auth: Auth;
}

/** Clears Google's name and photo from the Auth user, as the apps' callables do (best effort). */
async function scrubOwnerProfile(env: OwnerEnv, caller: OwnerCaller): Promise<void> {
  if (!caller.profileClaims) return;
  try {
    await env.auth.updateUser(caller.uid, { displayName: null, photoURL: null });
  } catch (e) {
    env.logger.warn("profile_scrub_failed", { uidHash: uidHash(caller.uid), code: String((e as { code?: string }).code ?? "unknown").slice(0, 60) });
  }
}

/** `getProviderProfiles`: the owner's view. Takes no input. */
export async function getProviderProfiles(env: OwnerEnv, authorization: string | undefined): Promise<ProfilesView> {
  const caller = await ownerCallerFromToken(env.auth, authorization);
  await scrubOwnerProfile(env, caller);
  await assertOwner(env.db, caller, { write: false, nowMs: env.now() });
  return listProfiles(env);
}

/** `setProviderProfile` `{profile, expect, confirm: true}`: switches, then answers with the new view. */
export async function setProviderProfile(env: OwnerEnv, authorization: string | undefined, data: unknown): Promise<ProfilesView & { changed: boolean }> {
  const caller = await ownerCallerFromToken(env.auth, authorization);
  await scrubOwnerProfile(env, caller);
  await assertOwner(env.db, caller, { write: true, nowMs: env.now() });
  const d = isObj(data) ? data : {};
  if (d.confirm !== true || typeof d.profile !== "string" || typeof d.expect !== "string") {
    throw new HttpsError("invalid-argument", "Expected {profile, expect, confirm: true}", { reason: "INVALID_REQUEST" });
  }
  const out = await activateProfile(env, d.profile, { apply: true, actor: { via: "web", uid: caller.uid }, expect: d.expect });
  return { ...(await listProfiles(env)), changed: out.changed };
}
