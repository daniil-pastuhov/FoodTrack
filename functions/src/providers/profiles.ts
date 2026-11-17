/**
 * Named provider setups ("profiles") the owner switches between (README "Provider profiles"):
 * `npm run admin -- image list | use <id>` and the owner console's drop-down
 * (core/providerSwitch.ts). Today a profile sets the recipe picture's provider, model, fallback
 * model and the price reserved per picture; nothing else of config/runtime.
 *
 * Adding a provider is one entry here plus its adapter (adapters/image.ts, a line in
 * `imageRouter` in src/index.ts and its name in IMAGE_PROVIDERS). A later profile may also carry
 * the recipe text's provider (e.g. an "EU pair", Mistral for the text and FLUX for the pictures):
 * that is a new optional part beside `image` (`text?: TextPart`), written by its own writer in
 * core/providerSwitch.ts `planProfile`; the profiles without it leave the text setup alone.
 */
import { DEFAULT_IMAGE_PRICES, DEFAULT_IMAGE_SELECTION, IMAGE_PROVIDERS, type ImageProvider } from "../constants";
import type { ImageSelection } from "../model";

/** Secrets a profile may need, by what they are for; the deploy wiring maps each to its Secret Manager name. */
export const PROFILE_SECRETS = ["openai"] as const;
export type ProfileSecret = (typeof PROFILE_SECRETS)[number];

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

export interface ProviderProfile {
  /** Stable id, used by the command, the console and the audit records; never reused for something else. */
  id: string;
  label: string;
  /** One sentence for the console and `image list`. */
  summary: string;
  image: ImagePart;
  requires: readonly Requirement[];
}

const price = (provider: string, model: string): Record<string, number> => {
  const key = `${provider}/${model}`;
  const usd = DEFAULT_IMAGE_PRICES[key];
  if (usd === undefined) throw new Error(`no default price for ${key}`);
  return { [key]: usd };
};

const FLASH_LITE = "gemini-3.1-flash-lite-image";
const NANO_BANANA = "gemini-nano-banana-2.1";
const OPENAI_MINI = "gpt-image-1-mini";

export const PROVIDER_PROFILES: readonly ProviderProfile[] = [
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
    requires: [
      // Owner's decision needed before this flips: README "What a new provider means for privacy".
      { kind: "approval", approved: false, missing: "OpenAI is not yet named in the privacy policy's processor list (a code change turns this on)" },
      { kind: "secret", secret: "openai", missing: "The OpenAI API key is not in Secret Manager, or the service account can't read it" },
    ],
  },
];

export function profileById(id: unknown): ProviderProfile | undefined {
  return typeof id === "string" ? PROVIDER_PROFILES.find((p) => p.id === id) : undefined;
}

/** The profile a parsed `config/runtime.image` matches, or null for a setup set by hand ("custom"). */
export function activeProfileOf(image: ImageSelection, profiles: readonly ProviderProfile[] = PROVIDER_PROFILES): ProviderProfile | null {
  for (const p of profiles) {
    if (p.image.provider !== image.provider) continue;
    if (p.image.provider === "off") return p;
    if (p.image.model === image.model && (p.image.fallbackModel ?? "") === image.fallbackModel) return p;
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
    if (!(IMAGE_PROVIDERS as readonly string[]).includes(img.provider)) out.push(`${at}: unknown image provider ${img.provider}`);
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
    if (fallback === img.model) out.push(`${at}: the fallback must differ from the model`);
    const keys = [img.model, fallback].filter(Boolean).map((m) => `${img.provider}/${m}`);
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
