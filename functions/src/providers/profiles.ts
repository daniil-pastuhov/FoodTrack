/**
 * Named provider setups ("profiles") the owner switches between (README "Provider profiles"):
 * `npm run admin -- providers list | use <id>` (alias `image`) and the owner console's drop-down
 * (core/providerSwitch.ts). A profile sets the recipe picture's provider, model, fallback and the
 * price reserved per picture, and, optionally, the recipe text's provider, model and fallback
 * (`text`); nothing else of config/runtime. A profile without `text` means the text's deploy
 * default (Gemini on Vertex AI): switching to it from one with a text part puts the default back.
 *
 * The providers themselves are in providers/registry.ts (one adapter file plus one entry each,
 * README "Adding a provider"); a profile's readiness includes each of its providers' own
 * requirements (its key readable, its privacy approval), added here automatically.
 */
import { DEFAULT_IMAGE_SELECTION, DEFAULT_TEXT_SELECTION, type ImageProvider } from "../constants";
import type { ImageSelection, TextSelection } from "../model";
import { defaultImagePrices, imageProviderById, imageProviderIds, providerSecrets, textDefaultPrices, textProviderById } from "./registry";
import type { ProviderDescriptor } from "./types";

/** Secrets a profile may need, by id: each provider's declared secret (providers/registry.ts). */
export const PROFILE_SECRETS: readonly string[] = providerSecrets().map((s) => s.id);
export type ProfileSecret = string;

/**
 * What must hold before a profile can be switched on.
 * - `secret`: the functions' service account can read this secret now (checked at the switch).
 * - `approval`: a decision made in code, e.g. a provider not yet named in the privacy policy's
 *   processor list; `approved: false` keeps the profile listed but never switchable.
 */
export type Requirement =
  | { kind: "secret"; secret: ProfileSecret; missing: string }
  | { kind: "approval"; approved: boolean; missing: string };

/** The recipe picture part of a profile: written to `config/runtime.image` (and prices). */
export interface ImagePart {
  provider: ImageProvider;
  /** Absent for provider "off" (the model and fallback stay as they were). */
  model?: string;
  /** The fallback's provider when it is another one (absent: the same provider). */
  fallbackProvider?: string;
  /** "" for none. Absent for provider "off". */
  fallbackModel?: string;
  /**
   * USD reserved per picture, by "provider/model", for the model and the fallback: the switch
   * makes sure at least this price is in force (`config/runtime.prices.imageUsdPerImage`), so a
   * switch can never leave a model without a price (pictures would turn off) and never lowers a
   * price someone set higher.
   */
  usdPerImage: Readonly<Record<string, number>>;
}

/**
 * The recipe text part of a profile: written to `config/runtime.text`. Its prices come from the
 * adapter's table (or `prices.textUsdPerMTok`), so the switch writes none.
 */
export interface TextPart {
  provider: string;
  model: string;
  /** "" for none; the fallback provider defaults to `provider`. Owner's decision: an EU profile may fall back to Google. */
  fallbackProvider?: string;
  fallbackModel?: string;
}

export interface ProviderProfile {
  /** Stable id, used by the command, the console and the audit records; never reused for something else. */
  id: string;
  label: string;
  /** One sentence for the console and `providers list`. */
  summary: string;
  image: ImagePart;
  /** Absent: the text's deploy default (no `config/runtime.text`). */
  text?: TextPart;
  /** The profile's own requirements plus every requirement of the providers it uses (withProviderRequirements). */
  requires: readonly Requirement[];
}

const price = (provider: string, model: string): Record<string, number> => {
  const key = `${provider}/${model}`;
  const usd = defaultImagePrices()[key];
  if (usd === undefined) throw new Error(`no default price for ${key}`);
  return { [key]: usd };
};

/** The providers a profile calls: its text's and fallback's, its picture's and fallback's. */
export function providersOf(p: Pick<ProviderProfile, "image" | "text">): ProviderDescriptor[] {
  const out: ProviderDescriptor[] = [];
  const add = (d: ProviderDescriptor | undefined) => {
    if (d && !out.includes(d)) out.push(d);
  };
  if (p.text) {
    add(textProviderById(p.text.provider));
    if (p.text.fallbackModel) add(textProviderById(p.text.fallbackProvider || p.text.provider));
  }
  if (p.image.provider !== "off") {
    add(imageProviderById(p.image.provider));
    if (p.image.fallbackModel) add(imageProviderById(p.image.fallbackProvider || p.image.provider));
  }
  return out;
}

/**
 * A profile's requirements: its own, then each of its providers' privacy approval and key, each
 * once (by its text).
 */
export function withProviderRequirements(p: Omit<ProviderProfile, "requires"> & { requires?: readonly Requirement[] }): ProviderProfile {
  const reqs: Requirement[] = [...(p.requires ?? [])];
  const has = (missing: string) => reqs.some((r) => r.missing === missing);
  for (const d of providersOf(p)) {
    if (!d.privacy.approved && !has(d.privacy.missing)) reqs.push({ kind: "approval", approved: false, missing: d.privacy.missing });
    if (d.secret && !has(d.secret.missing)) reqs.push({ kind: "secret", secret: d.secret.id, missing: d.secret.missing });
  }
  return { ...p, requires: reqs };
}

const FLASH_LITE = "gemini-3.1-flash-lite-image";
const NANO_BANANA = "gemini-nano-banana-2.1";
const OPENAI_MINI = "gpt-image-1-mini";

export const PROVIDER_PROFILES: readonly ProviderProfile[] = ([
  {
    id: "flash-lite",
    label: "Gemini 3.1 Flash-Lite Image (Nano Banana 2.1 as fallback)",
    summary: "The default: Vertex AI, about 3 s a picture; Nano Banana 2.1 is asked once when Flash-Lite fails for capacity.",
    image: {
      provider: "vertex",
      model: FLASH_LITE,
      fallbackModel: NANO_BANANA,
      usdPerImage: { ...price("vertex", FLASH_LITE), ...price("vertex", NANO_BANANA) },
    },
    requires: [],
  },
  {
    id: "nano-banana",
    label: "Nano Banana 2.1 (Flash-Lite Image as fallback)",
    summary: "Vertex AI, about 15 s a picture and dearer (it thinks); Flash-Lite Image is asked once when it fails for capacity.",
    image: {
      provider: "vertex",
      model: NANO_BANANA,
      fallbackModel: FLASH_LITE,
      usdPerImage: { ...price("vertex", NANO_BANANA), ...price("vertex", FLASH_LITE) },
    },
    requires: [],
  },
  {
    id: "off",
    label: "Pictures off",
    summary: "No recipe pictures: the apps stop offering them within 30 s and a request still asking gets IMAGE_UNAVAILABLE.",
    image: { provider: "off", usdPerImage: {} },
    requires: [],
  },
  {
    id: "openai",
    label: "OpenAI gpt-image-1-mini (no fallback)",
    summary: "OpenAI's Images API; needs its API key in Secret Manager and OpenAI named as a processor in the privacy policy.",
    image: { provider: "openai", model: OPENAI_MINI, fallbackModel: "", usdPerImage: price("openai", OPENAI_MINI) },
    // From the provider (adapters/image.ts openaiImage): the privacy approval, then the key.
    requires: [],
  },
] satisfies (Omit<ProviderProfile, "requires"> & { requires: readonly Requirement[] })[]).map(withProviderRequirements);

export function profileById(id: unknown): ProviderProfile | undefined {
  return typeof id === "string" ? PROVIDER_PROFILES.find((p) => p.id === id) : undefined;
}

/** Whether a parsed picture selection is exactly a profile's picture part. */
export function imageMatches(part: ImagePart, image: ImageSelection): boolean {
  if (part.provider !== image.provider) return false;
  if (part.provider === "off") return true;
  const fbProvider = (part.fallbackProvider ?? "") === part.provider ? "" : (part.fallbackProvider ?? "");
  return part.model === image.model && (part.fallbackModel ?? "") === image.fallbackModel && fbProvider === (image.fallbackProvider ?? "");
}

/** The text selection a profile puts in force (its text part, or the deploy default). */
export function textSelectionFor(p: Pick<ProviderProfile, "text">, textDefaults: { provider: string; model: string } = DEFAULT_TEXT_SELECTION): TextSelection {
  if (!p.text) return { provider: textDefaults.provider, model: textDefaults.model, fallbackProvider: "", fallbackModel: "" };
  const fbModel = p.text.fallbackModel ?? "";
  return {
    provider: p.text.provider,
    model: p.text.model,
    fallbackProvider: fbModel ? p.text.fallbackProvider || p.text.provider : "",
    fallbackModel: fbModel,
  };
}

const sameText = (a: TextSelection, b: TextSelection) =>
  a.provider === b.provider && a.model === b.model && a.fallbackProvider === b.fallbackProvider && a.fallbackModel === b.fallbackModel;

/**
 * The profile a parsed `config/runtime` matches, or null for a setup set by hand ("custom"). With
 * `text` given, a profile matches only when its text part (or, without one, the deploy default)
 * is in force too; without it only the picture part is compared.
 */
export function activeProfileOf(
  image: ImageSelection,
  profiles: readonly ProviderProfile[] = PROVIDER_PROFILES,
  text?: TextSelection,
  textDefaults: { provider: string; model: string } = DEFAULT_TEXT_SELECTION,
): ProviderProfile | null {
  for (const p of profiles) {
    if (!imageMatches(p.image, image)) continue;
    if (text && !sameText(textSelectionFor(p, textDefaults), text)) continue;
    return p;
  }
  return null;
}

const ID = /^[a-z][a-z0-9-]{1,30}$/;
const MODEL = /^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/;

/** Every problem with a registry (empty when sound); the tests run it over PROVIDER_PROFILES. */
export function registryProblems(profiles: readonly ProviderProfile[] = PROVIDER_PROFILES): string[] {
  const out: string[] = [];
  const ids = new Set<string>();
  for (const p of profiles) {
    const at = `profile ${JSON.stringify(p.id)}`;
    if (!ID.test(p.id)) out.push(`${at}: id must match ${ID}`);
    if (ids.has(p.id)) out.push(`${at}: duplicate id`);
    ids.add(p.id);
    if (!p.label.trim() || !p.summary.trim()) out.push(`${at}: label and summary are required`);
    const img = p.image;
    if (!imageProviderIds().includes(img.provider)) out.push(`${at}: unknown image provider ${img.provider}`);
    if (p.text) {
      const t = p.text;
      if (!textProviderById(t.provider)) out.push(`${at}: unknown text provider ${t.provider}`);
      if (!MODEL.test(t.model)) out.push(`${at}: text model id is invalid`);
      const tfModel = t.fallbackModel ?? "";
      const tfProvider = t.fallbackProvider || t.provider;
      if (tfModel !== "" && !MODEL.test(tfModel)) out.push(`${at}: text fallback model id is invalid`);
      if (tfModel !== "" && !textProviderById(tfProvider)) out.push(`${at}: unknown text fallback provider ${tfProvider}`);
      if (tfModel === t.model && tfProvider === t.provider) out.push(`${at}: the text fallback must differ from the model`);
      const priced = (provider: string, model: string) => textProviderById(provider)?.runtimeTokenPrices || textDefaultPrices(provider, model) !== undefined;
      if (!priced(t.provider, t.model)) out.push(`${at}: no price for text ${t.provider}/${t.model}`);
      if (tfModel && !priced(tfProvider, tfModel)) out.push(`${at}: no price for text ${tfProvider}/${tfModel}`);
    }
    if (img.fallbackProvider !== undefined && (img.fallbackProvider === "off" || !imageProviderIds().includes(img.fallbackProvider))) {
      out.push(`${at}: unknown image fallback provider ${img.fallbackProvider}`);
    }
    for (const [key, usd] of Object.entries(img.usdPerImage)) {
      if (!Number.isFinite(usd) || usd < 0.001 || usd > 2) out.push(`${at}: price of ${key} must be 0.001 to 2 USD`);
    }
    if (img.provider === "off") {
      if (img.model !== undefined || img.fallbackModel !== undefined || Object.keys(img.usdPerImage).length) {
        out.push(`${at}: provider off takes no model, fallback or price`);
      }
      continue;
    }
    if (!img.model || !MODEL.test(img.model)) out.push(`${at}: model id is invalid`);
    const fallback = img.fallbackModel ?? "";
    if (fallback !== "" && !MODEL.test(fallback)) out.push(`${at}: fallback model id is invalid`);
    const fbAt = img.fallbackProvider || img.provider;
    if (fallback === img.model && fbAt === img.provider) out.push(`${at}: the fallback must differ from the model`);
    const keys = [`${img.provider}/${img.model}`, ...(fallback ? [`${fbAt}/${fallback}`] : [])];
    for (const k of keys) if (!(k in img.usdPerImage)) out.push(`${at}: no reserved price for ${k}`);
    for (const k of Object.keys(img.usdPerImage)) if (!keys.includes(k)) out.push(`${at}: price for ${k}, which it doesn't use`);
  }
  const defaults = activeProfileOf({ ...DEFAULT_IMAGE_SELECTION }, profiles);
  if (!defaults) out.push("no profile matches DEFAULT_IMAGE_SELECTION (the deploy defaults)");
  return out;
}

export interface Readiness {
  ready: boolean;
  /** Why not, in plain words (never a secret's value or path). */
  missing: string[];
}

/** Checks a profile's requirements; `secretReadable` is asked only for the secrets it names. */
export async function readinessOf(
  profile: ProviderProfile,
  secretReadable: (secret: ProfileSecret) => Promise<boolean>,
): Promise<Readiness> {
  const missing: string[] = [];
  for (const r of profile.requires) {
    if (r.kind === "approval" && !r.approved) missing.push(r.missing);
    if (r.kind === "secret") {
      let ok: boolean;
      try {
        ok = await secretReadable(r.secret);
      } catch {
        ok = false;
      }
      if (!ok) missing.push(r.missing);
    }
  }
  return { ready: missing.length === 0, missing };
}
