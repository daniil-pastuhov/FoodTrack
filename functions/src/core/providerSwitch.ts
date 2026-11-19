/**
 * The four provider switches (providers/catalogue.ts; README "Provider switches"): the recipe
 * text's model, the text's fallback, the picture model and the picture's fallback, each switched on
 * its own. The one core behind `npm run admin -- providers list | set <switch> <value>` and the
 * owner console's callables `getProviderSwitches` / `setProviderSwitch`.
 *
 * `switchProvider` checks the option is in the switch's catalogue and ready, then in one
 * transaction reads config/runtime, plans the writes (the switch's own fields of `text` or
 * `image`, the other switch of the pair pinned only where its stored form would otherwise read
 * differently, and, for a picture model whose price in force is missing or lower, its
 * `prices.imageUsdPerImage.<provider/model>`), checks that the document as it would be reads as
 * exactly the four values wanted (this one changed, the other three as they were), priced, with
 * no field newly invalid, writes it with field paths (every other field untouched), and adds an
 * audit record to `providerProfileChanges/` (the collection the profiles it replaced wrote to,
 * kept so the history stays in one place); then logs `provider_profile_changed` with the switch.
 * The instances' config cache (30 s) is the only delay.
 *
 * Edge cases, the same for text and pictures: a fallback equal to its primary is refused
 * (SAME_AS_PRIMARY), and so is a primary switched to the model that is its fallback now (switch
 * the fallback first: no switch ever changes another). "off" for pictures writes only
 * `image.provider`: the model and the fallback stay stored, inert, for the way back, and the
 * picture fallback still shows (and switches) what it will be. A setup set by hand that matches no
 * option shows as it is, marked custom, and any switch can move off it.
 *
 * The owner gate (`ownerCallerFromToken`, `assertOwner`) is for the web callables: a Firebase ID
 * token, checked again here with revocation, of a Google sign-in (verified email) whose uid is in
 * `config/owner.uids`, or whose Firebase user has a linked Google identity (its `google.com`
 * provider entry, read with `auth.getUser`) with an email in `config/owner.emails`; never the
 * token's or the user's own email, which can belong to another sign-in method. `config/owner` is
 * a Firestore document only the owner edits in the console. Missing, empty or invalid: nobody is
 * an owner (fail closed).
 */
import { FieldPath, Timestamp, type Firestore } from "firebase-admin/firestore";
import type { Auth } from "firebase-admin/auth";
import { HttpsError } from "firebase-functions/v2/https";
import { DEFAULT_IMAGE_SELECTION, DEFAULT_PRICES, DEFAULT_TEXT_SELECTION } from "../constants";
import {
  imageFallbackOf,
  imageMicroUsdOf,
  imagePriceMicroUsd,
  parseRuntimeConfig,
  textPricesOf,
  uidHash,
  validImageSelection,
  type ImageSelection,
  type RuntimeConfig,
} from "../model";
import type { Logger, TextTarget } from "../ports";
import {
  NONE,
  OFF,
  SLOTS,
  SLOT_LABELS,
  catalogue,
  imageProvidersServing,
  isSlot,
  optionOf,
  pairedSlot,
  readinessOf,
  slotKind,
  targetOf,
  valueOf,
  type Readiness,
  type SlotId,
  type SwitchOption,
} from "../providers/catalogue";

export const RUNTIME_DOC = "config/runtime";
export const OWNER_DOC = "config/owner";
/** The switches' audit records (named after the profiles they replaced; older records stay readable). */
export const SWITCH_CHANGES = "providerProfileChanges";
/** A switch from the console needs a Google sign-in made at most this long ago. */
export const OWNER_RECENT_SIGN_IN_S = 30 * 60;
/** Entries of `config/owner.uids` / `emails` at most. */
export const OWNER_LIST_MAX = 10;

/** Reasons the owner callables and the command refuse with (HttpsError details.reason). */
export type SwitchRefusal =
  | "SIGN_IN" // no or unusable token
  | "NOT_OWNER"
  | "RECENT_SIGN_IN"
  | "UNKNOWN_SWITCH" // not one of the four switches
  | "UNKNOWN_OPTION" // not in that switch's catalogue
  | "NOT_READY"
  | "SAME_AS_PRIMARY" // a fallback equal to its primary, or a primary set to its fallback
  | "STALE" // the switch's value is no longer the one the caller saw
  | "PRICES_INVALID" // config/runtime.prices is invalid: a price can't be added to it
  | "INVALID_RESULT" // the document as it would be does not read as the switches asked (or the settings asked)
  // The settings (core/ownerSettings.ts):
  | "UNKNOWN_SETTING"
  | "INVALID_VALUE" // outside the setting's bounds or choices
  | "STORED_INVALID" // the setting's map in config/runtime is invalid as stored: fixed by hand first
  | "CONFIRM_SENSITIVE" // a sensitive change without its own confirmation
  | "WEB_LIMIT"; // outside the console's envelope: only the admin command makes it

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
  /** The deploy parameters' text default (TEXT_PROVIDER / GEMINI_MODEL). */
  textDefaults?: TextTarget;
  /** Whether the functions' service account can read the secret now; never returns its value. */
  secretReadable(secret: string): Promise<boolean>;
}

/** Who switched: the console (an owner's uid) or the admin command (the operator's credentials). */
export type Actor = { via: "web"; uid: string } | { via: "cli" };

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

/** How an owner email is compared: trimmed, lower-cased. */
export const normaliseOwnerEmail = (e: string): string => e.trim().toLowerCase();

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
  return { uids, emails: emails.map(normaliseOwnerEmail) };
}

/**
 * The emails of the Google identities linked to the Firebase user `uid` (its `google.com`
 * provider entries), normalised; null when the user can't be read or has no Google identity.
 * Never the user's own `email`, which another sign-in method (Apple, a former Google account)
 * may have set.
 */
export async function googleIdentityEmails(auth: Auth, uid: string): Promise<string[] | null> {
  let user;
  try {
    user = await auth.getUser(uid);
  } catch {
    return null;
  }
  const google = user.providerData.filter((p) => p.providerId === "google.com");
  if (google.length === 0) return null;
  return google.map((p) => (typeof p.email === "string" ? normaliseOwnerEmail(p.email) : "")).filter(Boolean);
}

/**
 * Refuses (NOT_OWNER) anyone but a Google sign-in with a verified email whose Firebase user still
 * has a Google identity and is listed in `config/owner` by uid, or by that Google identity's
 * email; and (RECENT_SIGN_IN) a write whose sign-in is older than OWNER_RECENT_SIGN_IN_S. At most
 * one Firestore read and, for a Google sign-in when the list is not empty, one Auth read.
 */
export async function assertOwner(db: Firestore, caller: OwnerCaller, opts: { write: boolean; nowMs: number; auth: Auth }): Promise<void> {
  const refuse = () => new SwitchRefused("permission-denied", "NOT_OWNER", "This Google account may not use the owner console");
  if (caller.signInProvider !== "google.com" || !caller.emailVerified) throw refuse();
  const owners = parseOwnerDoc((await db.doc(OWNER_DOC).get()).data() ?? null);
  if (owners === null || (owners.uids.length === 0 && owners.emails.length === 0)) throw refuse();
  const googleEmails = await googleIdentityEmails(opts.auth, caller.uid);
  // No Google identity left on the user (unlinked since the token was issued): nobody.
  if (googleEmails === null) throw refuse();
  const listed = owners.uids.includes(caller.uid) || googleEmails.some((e) => owners.emails.includes(e));
  if (!listed) throw refuse();
  if (opts.write && opts.nowMs / 1000 - caller.authTime > OWNER_RECENT_SIGN_IN_S) {
    throw new SwitchRefused("failed-precondition", "RECENT_SIGN_IN", "Sign in again (the sign-in is more than 30 minutes old)");
  }
}

// --- Reading the four switches ---------------------------------------------------------------

/** The four switches' values in force: "provider/model", "none" or "off". */
export type Setup = Record<SlotId, string>;

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);

interface Defaults {
  image: ImageSelection;
  text: TextTarget;
}

const parse = (data: Record<string, unknown>, d: Defaults) => parseRuntimeConfig(data, undefined, d.image, d.text);

/**
 * The picture fallback stored while pictures are off (parseRuntimeConfig clears it then, as no
 * fallback is asked): read from `image` as stored, its provider from `fallbackProvider`, else the
 * stored model's provider, else the one picture provider whose table has the model.
 */
function inertImageFallback(data: Record<string, unknown>, d: Defaults): string {
  const stored = data.image === undefined ? { ...d.image } : isObj(data.image) ? validImageSelection(data.image, d.image) : null;
  const s = stored ?? { ...d.image };
  if (!s.fallbackModel) return NONE;
  const serving = imageProvidersServing(s.fallbackModel);
  const provider = s.fallbackProvider || (s.provider !== OFF ? s.provider : serving.length === 1 ? serving[0] : "");
  return provider ? valueOf(provider, s.fallbackModel) : NONE;
}

/** The four switches as config/runtime (as stored) puts them in force. */
export function setupOf(data: Record<string, unknown>, d: Defaults, config: RuntimeConfig = parse(data, d)): Setup {
  const t = config.text;
  const fb = imageFallbackOf(config);
  return {
    text: valueOf(t.provider, t.model),
    "text-fallback": t.fallbackModel ? valueOf(t.fallbackProvider || t.provider, t.fallbackModel) : NONE,
    image: config.image.provider === OFF ? OFF : valueOf(config.image.provider, config.image.model),
    "image-fallback": config.image.provider === OFF ? inertImageFallback(data, d) : fb ? valueOf(fb.provider, fb.model) : NONE,
  };
}

const sameSetup = (a: Setup, b: Setup) => SLOTS.every((s) => a[s] === b[s]);

// --- Listing ----------------------------------------------------------------------------------

export interface OptionView {
  value: string;
  label: string;
  providerLabel: string;
  /** Its price in force, in words ("" for none / off). */
  price: string;
  ready: boolean;
  missing: string[];
  /** In force now. */
  current: boolean;
  /** Why it can't be chosen now although ready (it is the other switch of the pair), else "". */
  blocked: string;
}

export interface SlotView {
  id: SlotId;
  label: string;
  /** The value in force. */
  value: string;
  /** The option's label, or the value itself when it is no option ("custom"). */
  valueLabel: string;
  /** Set by hand: no option matches. Still switchable. */
  custom: boolean;
  /** The picture fallback while pictures are off: kept, asked by nobody. */
  inert: boolean;
  options: OptionView[];
}

export interface ChangeView {
  at: string;
  via: string;
  /** The switch, or "profile" for a record from before the four switches. */
  slot: string;
  from: string;
  to: string;
}

export interface SwitchesView {
  slots: SlotView[];
  maxStepImages: number;
  /** Whether the apps are offered pictures now (a priced model and `costs.recipeImage` on). */
  picturesOn: boolean;
  lastChange: ChangeView | null;
}

const usd = (n: number) => `USD ${n.toFixed(3).replace(/0$/, "")}`;

/** An option's price in force, in words. */
export function priceText(slot: SlotId, value: string, config: Pick<RuntimeConfig, "prices">): string {
  const t = targetOf(value);
  if (!t) return "";
  if (slotKind(slot) === "text") {
    const p = textPricesOf(config, t);
    return p ? `${usd(p.inputUsdPerMTok)} in, ${usd(p.outputUsdPerMTok)} out per 1M tokens` : "no price";
  }
  const micro = imagePriceMicroUsd(config, t.provider, t.model);
  return micro === null ? "no price" : `${usd(micro / 1e6)} a picture`;
}

/** Why `value` can't be chosen at `slot` next to `setup` (SAME_AS_PRIMARY), else "". */
export function blockedBy(slot: SlotId, value: string, setup: Setup): string {
  if (value === NONE || value === OFF) return "";
  const other = pairedSlot(slot);
  if (setup[other] !== value) return "";
  return slot.endsWith("-fallback")
    ? `it is the ${SLOT_LABELS[other].toLowerCase()} now`
    : `it is the ${SLOT_LABELS[other].toLowerCase()} now: switch the fallback first`;
}

async function lastChangeOf(db: Firestore): Promise<ChangeView | null> {
  const snap = await db.collection(SWITCH_CHANGES).orderBy("at", "desc").limit(1).get();
  const d = snap.docs[0]?.data();
  if (!d) return null;
  const at = d.at instanceof Timestamp ? d.at.toDate().toISOString() : "";
  const via = String(d.actor?.via ?? "");
  // A record of a profile switch (before 2026-11-19): from / to are maps with the profile id.
  if (typeof d.slot !== "string") return { at, via, slot: "profile", from: String(d.from?.profile ?? ""), to: String(d.to?.profile ?? "") };
  return { at, via, slot: d.slot, from: String(d.from ?? ""), to: String(d.to ?? "") };
}

const defaultsOf = (env: Pick<SwitchEnv, "imageDefaults" | "textDefaults">): Defaults => ({
  image: env.imageDefaults ?? DEFAULT_IMAGE_SELECTION,
  text: env.textDefaults ?? DEFAULT_TEXT_SELECTION,
});

/** Readiness of every option, each provider's secret read at most once. */
function readinessCache(env: SwitchEnv): (o: SwitchOption) => Promise<Readiness> {
  const secrets = new Map<string, Promise<boolean>>();
  const readable = (id: string) => {
    let p = secrets.get(id);
    if (!p) {
      p = env.secretReadable(id).catch(() => false);
      secrets.set(id, p);
    }
    return p;
  };
  return (o) => readinessOf(o, readable);
}

export async function listSwitches(env: SwitchEnv): Promise<SwitchesView> {
  const d = defaultsOf(env);
  const [snap, lastChange] = await Promise.all([env.db.doc(RUNTIME_DOC).get(), lastChangeOf(env.db)]);
  const data = (snap.data() ?? {}) as Record<string, unknown>;
  const config = parse(data, d);
  const setup = setupOf(data, d, config);
  const ready = readinessCache(env);
  const slots = await Promise.all(SLOTS.map(async (slot): Promise<SlotView> => {
    const options = catalogue(slot, d.text);
    const value = setup[slot];
    const match = options.find((o) => o.value === value);
    return {
      id: slot,
      label: SLOT_LABELS[slot],
      value,
      valueLabel: match ? match.label : value,
      custom: !match,
      inert: slot === "image-fallback" && setup.image === OFF,
      options: await Promise.all(options.map(async (o): Promise<OptionView> => {
        const r = await ready(o);
        return {
          value: o.value,
          label: o.label,
          providerLabel: o.providerLabel,
          price: priceText(slot, o.value, config),
          ready: r.ready,
          missing: r.missing,
          current: o.value === value,
          blocked: blockedBy(slot, o.value, setup),
        };
      })),
    };
  }));
  return {
    slots,
    maxStepImages: config.image.maxStepImages,
    picturesOn: imageMicroUsdOf(config) !== null && config.costs.recipeImage !== undefined,
    lastChange,
  };
}

// --- Planning and switching -------------------------------------------------------------------

/** One write, as a field path into config/runtime and its new value. */
export interface PlannedWrite {
  path: string[];
  value: unknown;
}

export interface Plan {
  slot: SlotId;
  from: Setup;
  to: Setup;
  /** Empty when the value is already in force. */
  writes: PlannedWrite[];
}

/** The fields `parseRuntimeConfig` reports invalid in `data`. */
export function invalidFields(data: Record<string, unknown>, imageDefaults: ImageSelection, textDefaults: TextTarget = DEFAULT_TEXT_SELECTION): string[] {
  let fields: string[] = [];
  parseRuntimeConfig(data, {
    info: () => undefined,
    error: () => undefined,
    warn: (_m, d) => {
      fields = Array.isArray(d?.fields) ? (d.fields as string[]) : [];
    },
  }, imageDefaults, textDefaults);
  return fields;
}

export function withWrites(data: Record<string, unknown>, writes: PlannedWrite[]): Record<string, unknown> {
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

/** The fields of `text` or `image` that say `want`'s pair ("" for none, and for a fallback at the same provider). */
function mapFields(kind: "text" | "image", want: Setup, current: RuntimeConfig): Record<string, unknown> {
  if (kind === "text") {
    const t = targetOf(want.text)!;
    const fb = targetOf(want["text-fallback"]);
    return { provider: t.provider, model: t.model, fallbackProvider: fb?.provider ?? "", fallbackModel: fb?.model ?? "" };
  }
  const t = targetOf(want.image);
  const fb = targetOf(want["image-fallback"]);
  const provider = t ? t.provider : OFF;
  return {
    provider,
    model: t ? t.model : current.image.model,
    fallbackModel: fb?.model ?? "",
    fallbackProvider: fb && fb.provider !== provider ? fb.provider : "",
  };
}

/**
 * Field-path writes into the map `kind` (`text` / `image`) for the named fields, only those that
 * differ from what is stored ("" and absent count as the same for `fallbackProvider`).
 */
function fieldWrites(data: Record<string, unknown>, kind: "text" | "image", fields: Record<string, unknown>): PlannedWrite[] {
  const stored = isObj(data[kind]) ? (data[kind] as Record<string, unknown>) : {};
  const out: PlannedWrite[] = [];
  for (const [k, v] of Object.entries(fields)) {
    const was = stored[k];
    const same = was === v || (k === "fallbackProvider" && v === "" && (was === undefined || was === null || was === ""));
    if (!same) out.push({ path: [kind, k], value: v });
  }
  return out;
}

/** The fields a switch writes for itself. */
function ownFields(slot: SlotId, value: string, want: Setup): Record<string, unknown> {
  const t = targetOf(value);
  switch (slot) {
    case "text":
      return { provider: t!.provider, model: t!.model };
    case "text-fallback":
      return t ? { fallbackProvider: t.provider, fallbackModel: t.model } : { fallbackProvider: "", fallbackModel: "" };
    case "image":
      return t ? { provider: t.provider, model: t.model } : { provider: OFF };
    case "image-fallback": {
      if (!t) return { fallbackModel: "", fallbackProvider: "" };
      const primary = targetOf(want.image);
      return { fallbackModel: t.model, fallbackProvider: primary && primary.provider === t.provider ? "" : t.provider };
    }
  }
}

/** The fields that pin the other switch of the pair to its value in `want`. */
function pinFields(slot: SlotId, want: Setup, current: RuntimeConfig): Record<string, unknown> {
  const all = mapFields(slotKind(slot), want, current);
  const other = pairedSlot(slot);
  const keys = other === "text" || other === "image" ? ["provider", "model"] : ["fallbackProvider", "fallbackModel"];
  return Object.fromEntries(keys.map((k) => [k, all[k]]));
}

/** The price write for a picture model (at least its default price in force), or none. */
function priceWrites(data: Record<string, unknown>, before: RuntimeConfig, option: SwitchOption, d: Defaults): PlannedWrite[] {
  if (option.usdPerImage === undefined) return [];
  const key = option.value;
  const inForce = before.prices.imageUsdPerImage?.[key];
  if (inForce !== undefined && inForce >= option.usdPerImage) return [];
  const raise = { [key]: option.usdPerImage };
  const stored = data.prices;
  if (stored === undefined || stored === null) {
    // No prices map yet: the token prices are required beside it, so the defaults are written out.
    return [{ path: ["prices"], value: { inputUsdPerMTok: DEFAULT_PRICES.inputUsdPerMTok, outputUsdPerMTok: DEFAULT_PRICES.outputUsdPerMTok, imageUsdPerImage: raise } }];
  }
  if (invalidFields(data, d.image, d.text).includes("prices") || !isObj(stored)) {
    throw new SwitchRefused("failed-precondition", "PRICES_INVALID",
      "config/runtime.prices is invalid, so a picture price can't be added to it; fix it first (README \"config/runtime\")");
  }
  if (stored.imageUsdPerImage === undefined || stored.imageUsdPerImage === null) return [{ path: ["prices", "imageUsdPerImage"], value: raise }];
  return [{ path: ["prices", "imageUsdPerImage", key], value: option.usdPerImage }];
}

/** Later writes to the same path win. */
function merged(writes: PlannedWrite[]): PlannedWrite[] {
  const out: PlannedWrite[] = [];
  for (const w of writes) {
    const key = JSON.stringify(w.path);
    const i = out.findIndex((x) => JSON.stringify(x.path) === key);
    if (i >= 0) out.splice(i, 1);
    out.push(w);
  }
  return out;
}

function unknownSwitch(): SwitchRefused {
  return new SwitchRefused("invalid-argument", "UNKNOWN_SWITCH", `No such switch; one of: ${SLOTS.join(", ")}`);
}

function unknownOption(slot: SlotId, textDefaults: TextTarget): SwitchRefused {
  return new SwitchRefused("invalid-argument", "UNKNOWN_OPTION", `Not an option of ${slot}; one of: ${catalogue(slot, textDefaults).map((o) => o.value).join(", ")}`);
}

/**
 * Pure: the writes that set switch `slot` to `value` over the document `data` (config/runtime as
 * stored), leaving the other three as they read now, checked by parsing the result. Throws
 * UNKNOWN_SWITCH, UNKNOWN_OPTION, SAME_AS_PRIMARY, PRICES_INVALID or INVALID_RESULT (readiness is
 * the caller's: switchProvider).
 */
export function planSwitch(
  data: Record<string, unknown>,
  slot: unknown,
  value: unknown,
  imageDefaults: ImageSelection = DEFAULT_IMAGE_SELECTION,
  textDefaults: TextTarget = DEFAULT_TEXT_SELECTION,
): Plan {
  if (!isSlot(slot)) throw unknownSwitch();
  const d: Defaults = { image: imageDefaults, text: textDefaults };
  const option = optionOf(slot, value, d.text);
  if (!option) throw unknownOption(slot, d.text);
  const before = parse(data, d);
  const from = setupOf(data, d, before);
  const want: Setup = { ...from, [slot]: option.value };
  const blocked = blockedBy(slot, option.value, from);
  if (blocked) throw new SwitchRefused("failed-precondition", "SAME_AS_PRIMARY", `${option.value} can't be the ${SLOT_LABELS[slot].toLowerCase()}: ${blocked}`);
  if (from[slot] === option.value) return { slot, from, to: from, writes: [] };

  const kind = slotKind(slot);
  const prices = priceWrites(data, before, option, d);
  const wasInvalid = invalidFields(data, d.image, d.text);
  const check = (writes: PlannedWrite[]) => {
    const next = withWrites(data, writes);
    const after = parse(next, d);
    const to = setupOf(next, d, after);
    const newlyInvalid = invalidFields(next, d.image, d.text).filter((f) => !wasInvalid.includes(f));
    const priced = to.image === OFF || imageMicroUsdOf(after) !== null;
    return { to, ok: sameSetup(to, want) && priced && newlyInvalid.length === 0, newlyInvalid };
  };

  const candidates: (() => PlannedWrite[])[] = [];
  const storedMap = data[kind];
  if (storedMap === undefined || isObj(storedMap)) {
    // Field paths: the switch's own fields, then, only if the result reads the other switch of the
    // pair differently (a fallback stored without its provider, say), that one pinned too.
    const own = fieldWrites(data, kind, ownFields(slot, option.value, want));
    candidates.push(() => own);
    candidates.push(() => merged([...own, ...fieldWrites(withWrites(data, own), kind, pinFields(slot, want, before))]));
  }
  // The map written whole, with the pair as wanted: a stored value that is not a map, or one whose
  // other fields make the field paths read wrong. `image.maxStepImages` is kept.
  candidates.push(() => [{
    path: [kind],
    value: kind === "image"
      ? { ...mapFields(kind, want, before), maxStepImages: before.image.maxStepImages }
      : mapFields(kind, want, before),
  }]);
  let last: ReturnType<typeof check> | null = null;
  for (const make of candidates) {
    const writes = merged([...make(), ...prices]);
    last = check(writes);
    if (last.ok) return { slot, from, to: last.to, writes };
  }
  throw new SwitchRefused("failed-precondition", "INVALID_RESULT",
    `config/runtime would not read as ${slot} = ${option.value} with the other switches unchanged${last?.newlyInvalid.length ? ` (invalid: ${last.newlyInvalid.join(", ")})` : ""}; nothing changed`);
}

export interface SwitchOptions {
  apply: boolean;
  actor: Actor;
  /** The switch's value the caller saw; a different one now refuses with STALE. */
  expect?: string;
}

export interface SwitchResult {
  applied: boolean;
  changed: boolean;
  plan: Plan;
  readiness: Readiness;
  /** The audit record's id (providerProfileChanges/<id>), when written. */
  changeId?: string;
}

/**
 * Sets one switch (README "Provider switches"). A dry run (`apply: false`) plans and checks
 * everything, refusals included, and writes nothing.
 */
export async function switchProvider(env: SwitchEnv, slot: unknown, value: unknown, opts: SwitchOptions): Promise<SwitchResult> {
  const d = defaultsOf(env);
  if (!isSlot(slot)) throw unknownSwitch();
  const option = optionOf(slot, value, d.text);
  if (!option) throw unknownOption(slot, d.text);
  const readiness = await readinessOf(option, (s) => env.secretReadable(s));
  if (!readiness.ready) {
    throw new SwitchRefused("failed-precondition", "NOT_READY", `${option.value} is not ready: ${readiness.missing.join("; ")}`);
  }
  const ref = env.db.doc(RUNTIME_DOC);
  const auditRef = env.db.collection(SWITCH_CHANGES).doc();
  const result = await env.db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const data = (snap.data() ?? {}) as Record<string, unknown>;
    const now = setupOf(data, d)[slot];
    if (opts.expect !== undefined && now !== opts.expect) {
      throw new SwitchRefused("aborted", "STALE", `${SLOT_LABELS[slot]} is now ${now}, not ${opts.expect}; reload and try again`);
    }
    const plan = planSwitch(data, slot, option.value, d.image, d.text);
    if (!opts.apply || plan.writes.length === 0) return { plan, changed: plan.writes.length > 0, written: false };
    if (!snap.exists) {
      tx.set(ref, withWrites({}, plan.writes));
    } else {
      const [first, ...rest] = plan.writes;
      const more = rest.flatMap((w) => [new FieldPath(...w.path), w.value]);
      tx.update(ref, new FieldPath(...first.path), first.value, ...more);
    }
    tx.create(auditRef, {
      at: Timestamp.fromMillis(env.now()),
      actor: opts.actor,
      slot,
      from: plan.from[slot],
      to: plan.to[slot],
      before: plan.from,
      after: plan.to,
      writes: plan.writes.map((w) => ({ path: w.path.join(" > "), value: w.value })),
    });
    return { plan, changed: true, written: true };
  });
  if (result.written) {
    env.logger.info("provider_profile_changed", {
      via: opts.actor.via,
      ...(opts.actor.via === "web" ? { uidHash: uidHash(opts.actor.uid) } : {}),
      slot,
      from: result.plan.from[slot],
      to: result.plan.to[slot],
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
    // firebase.local.json's Hosting emulator port (5000 is taken by AirPlay on macOS).
    ...(emulator ? ["http://127.0.0.1:5050", "http://localhost:5050"] : []),
  ];
}

// --- The two callables' bodies (src/index.ts binds them) --------------------------------------

export interface OwnerEnv extends SwitchEnv {
  auth: Auth;
  /** The owner's board (README "The board", "Always on"), for the console's link; absent: no link. */
  boardUrl?: string;
}

/** Clears Google's name and photo from the Auth user, as the apps' callables do (best effort). */
export async function scrubOwnerProfile(env: OwnerEnv, caller: OwnerCaller): Promise<void> {
  if (!caller.profileClaims) return;
  try {
    await env.auth.updateUser(caller.uid, { displayName: null, photoURL: null });
  } catch (e) {
    env.logger.warn("profile_scrub_failed", { uidHash: uidHash(caller.uid), code: String((e as { code?: string }).code ?? "unknown").slice(0, 60) });
  }
}

/** `getProviderSwitches`: the owner's view of the four switches. Takes no input. */
export async function getProviderSwitches(env: OwnerEnv, authorization: string | undefined): Promise<SwitchesView> {
  const caller = await ownerCallerFromToken(env.auth, authorization);
  await assertOwner(env.db, caller, { write: false, nowMs: env.now(), auth: env.auth });
  await scrubOwnerProfile(env, caller);
  return listSwitches(env);
}

/** `setProviderSwitch` `{slot, value, expect, confirm: true}`: sets one switch, then answers with the new view. */
export async function setProviderSwitch(env: OwnerEnv, authorization: string | undefined, data: unknown): Promise<SwitchesView & { changed: boolean }> {
  const caller = await ownerCallerFromToken(env.auth, authorization);
  await assertOwner(env.db, caller, { write: true, nowMs: env.now(), auth: env.auth });
  await scrubOwnerProfile(env, caller);
  const d = isObj(data) ? data : {};
  if (d.confirm !== true || typeof d.slot !== "string" || typeof d.value !== "string" || typeof d.expect !== "string") {
    throw new HttpsError("invalid-argument", "Expected {slot, value, expect, confirm: true}", { reason: "INVALID_REQUEST" });
  }
  const out = await switchProvider(env, d.slot, d.value, { apply: true, actor: { via: "web", uid: caller.uid }, expect: d.expect });
  return { ...(await listSwitches(env)), changed: out.changed };
}
