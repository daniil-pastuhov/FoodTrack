/**
 * The provider switches' catalogue (README "Provider switches"): what each of the four switches
 * (the recipe text's model, its fallback, the picture model, its fallback) may be set to, built from
 * the provider registry (providers/registry.ts), so a provider or model added there is offered here
 * with no second list. A text option is every model in a text adapter's price table (and the text's
 * deploy default, when its provider prices it at run time); a picture option every model in a
 * picture adapter's price table. The picture model can also be "off" (no pictures) and each
 * fallback "none".
 *
 * An option's readiness is its provider's own requirements (registry): its key readable by the
 * functions' service account, and its privacy approval (`privacy.approved`; README "What a new
 * provider means for privacy"). "off" and "none" are always ready. The switch itself is
 * core/providerSwitch.ts.
 */
import { DEFAULT_IMAGE_SELECTION, DEFAULT_TEXT_SELECTION } from "../constants";
import type { TextTarget } from "../ports";
import { IMAGE_OFF, defaultImagePrices, imageProviderById, imageProviders, textDefaultPrices, textProviderById, textProviders } from "./registry";
import type { ProviderDescriptor } from "./types";

/** The four switches, in the order the console and the command show them. Stable ids: the audit records name them. */
export const SLOTS = ["text", "text-fallback", "image", "image-fallback"] as const;
export type SlotId = (typeof SLOTS)[number];

export const SLOT_LABELS: Readonly<Record<SlotId, string>> = {
  text: "Text model",
  "text-fallback": "Text fallback",
  image: "Picture model",
  "image-fallback": "Picture fallback",
};

/** A fallback switch's "no fallback". */
export const NONE = "none";
/** The picture switch's "no pictures" (config/runtime.image.provider "off"). */
export const OFF = IMAGE_OFF;

export const isSlot = (v: unknown): v is SlotId => typeof v === "string" && (SLOTS as readonly string[]).includes(v);
export const slotKind = (slot: SlotId): "text" | "image" => (slot.startsWith("text") ? "text" : "image");
export const isFallbackSlot = (slot: SlotId): boolean => slot.endsWith("-fallback");
/** The primary a fallback belongs to, and the fallback of a primary. */
export const pairedSlot = (slot: SlotId): SlotId =>
  slot === "text" ? "text-fallback" : slot === "text-fallback" ? "text" : slot === "image" ? "image-fallback" : "image";

/** A switch's value as text: "provider/model", "none" or "off". */
export const valueOf = (provider: string, model: string): string => `${provider}/${model}`;

/** "provider/model" split; null for "none", "off" or anything malformed. */
export function targetOf(value: string): TextTarget | null {
  const m = /^([a-z][a-z0-9]{1,19})\/([A-Za-z0-9][A-Za-z0-9._-]{0,79})$/.exec(value);
  return m ? { provider: m[1], model: m[2] } : null;
}

/**
 * What must hold before an option can be switched on.
 * - `secret`: the functions' service account can read this secret now (checked at the switch).
 * - `approval`: the provider's privacy approval, a decision made in code; `approved: false` keeps
 *   the option listed but never switchable.
 */
export type Requirement =
  | { kind: "secret"; secret: string; missing: string }
  | { kind: "approval"; approved: boolean; missing: string };

export interface SwitchOption {
  /** "provider/model", or "none" / "off". */
  value: string;
  /** "" for "none" / "off". */
  provider: string;
  model: string;
  /** For the console and the command: the model id, or "No fallback" / "Pictures off". */
  label: string;
  /** The provider's label, "" for "none" / "off". */
  providerLabel: string;
  /** A picture option's default price per picture (the adapter's table), raised in config/runtime when lower. */
  usdPerImage?: number;
  requires: readonly Requirement[];
}

/** A provider's requirements: its privacy approval, then its key. */
export function requirementsOf(d: ProviderDescriptor): Requirement[] {
  const out: Requirement[] = [];
  if (!d.privacy.approved) out.push({ kind: "approval", approved: false, missing: d.privacy.missing });
  if (d.secret) out.push({ kind: "secret", secret: d.secret.id, missing: d.secret.missing });
  return out;
}

const NONE_OPTION: SwitchOption = { value: NONE, provider: "", model: "", label: "No fallback", providerLabel: "", requires: [] };
const OFF_OPTION: SwitchOption = { value: OFF, provider: "", model: "", label: "Pictures off", providerLabel: "", requires: [] };

/** Every text model the registry prices, and the deploy default when its provider prices it at run time. */
function textOptions(textDefaults: TextTarget): SwitchOption[] {
  const out: SwitchOption[] = [];
  const add = (d: ProviderDescriptor, model: string) => {
    const value = valueOf(d.id, model);
    if (!out.some((o) => o.value === value)) out.push({ value, provider: d.id, model, label: model, providerLabel: d.label, requires: requirementsOf(d) });
  };
  for (const d of textProviders()) {
    if (d.runtimeTokenPrices && !(d.defaultModel in d.prices)) add(d, d.defaultModel);
    for (const model of Object.keys(d.prices)) add(d, model);
    if (d.id === textDefaults.provider && (d.runtimeTokenPrices || textDefaultPrices(d.id, textDefaults.model))) add(d, textDefaults.model);
  }
  return out;
}

/** Every picture model the registry prices. */
function imageOptions(): SwitchOption[] {
  const out: SwitchOption[] = [];
  for (const d of imageProviders()) {
    for (const [model, usd] of Object.entries(d.usdPerImage)) {
      out.push({ value: valueOf(d.id, model), provider: d.id, model, label: model, providerLabel: d.label, usdPerImage: usd, requires: requirementsOf(d) });
    }
  }
  return out;
}

/** A switch's options, in order: the models, then "off" (pictures) or "none" (a fallback). */
export function catalogue(slot: SlotId, textDefaults: TextTarget = DEFAULT_TEXT_SELECTION): SwitchOption[] {
  const models = slotKind(slot) === "text" ? textOptions(textDefaults) : imageOptions();
  if (isFallbackSlot(slot)) return [...models, NONE_OPTION];
  return slot === "image" ? [...models, OFF_OPTION] : models;
}

export function optionOf(slot: SlotId, value: unknown, textDefaults?: TextTarget): SwitchOption | undefined {
  return typeof value === "string" ? catalogue(slot, textDefaults).find((o) => o.value === value) : undefined;
}

/** The provider descriptor of a value at a switch, if it names a known one. */
export function providerOfValue(slot: SlotId, value: string): ProviderDescriptor | undefined {
  const t = targetOf(value);
  if (!t) return undefined;
  return slotKind(slot) === "text" ? textProviderById(t.provider) : imageProviderById(t.provider);
}

/** Every provider/model any switch offers (the model lifecycle table must have each). */
export function catalogueModels(textDefaults: TextTarget = DEFAULT_TEXT_SELECTION): { provider: string; model: string; slot: SlotId }[] {
  const out: { provider: string; model: string; slot: SlotId }[] = [];
  for (const slot of ["text", "image"] as const) {
    for (const o of catalogue(slot, textDefaults)) if (o.provider) out.push({ provider: o.provider, model: o.model, slot });
  }
  return out;
}

/** The picture providers whose table has `model` (to read a fallback stored without its provider while pictures are off). */
export function imageProvidersServing(model: string): string[] {
  return imageProviders().filter((d) => Object.prototype.hasOwnProperty.call(d.usdPerImage, model)).map((d) => d.id);
}

/** Every problem with the catalogue (empty when sound); run by test/providerSwitches.test.ts. */
export function catalogueProblems(textDefaults: TextTarget = DEFAULT_TEXT_SELECTION): string[] {
  const out: string[] = [];
  for (const slot of SLOTS) {
    const seen = new Set<string>();
    for (const o of catalogue(slot, textDefaults)) {
      const at = `${slot} option ${JSON.stringify(o.value)}`;
      if (seen.has(o.value)) out.push(`${at}: listed twice`);
      seen.add(o.value);
      if (o.provider && !targetOf(o.value)) out.push(`${at}: not a valid provider/model`);
      if (o.provider && !o.label.trim()) out.push(`${at}: no label`);
      if (slotKind(slot) === "image" && o.provider) {
        const usd = defaultImagePrices()[o.value];
        if (usd === undefined || !(usd >= 0.001 && usd <= 2)) out.push(`${at}: its price must be 0.001 to 2 USD`);
      }
      if (slotKind(slot) === "text" && o.provider) {
        const d = textProviderById(o.provider);
        if (!d || !(d.runtimeTokenPrices || textDefaultPrices(o.provider, o.model))) out.push(`${at}: no price`);
      }
      for (const r of o.requires) if (!r.missing.trim()) out.push(`${at}: a requirement must say what is missing`);
    }
  }
  // The deploy defaults must be options, so the setup a fresh project starts with reads as a catalogue setup.
  const has = (slot: SlotId, provider: string, model: string) => catalogue(slot, textDefaults).some((o) => o.value === valueOf(provider, model));
  if (!has("text", DEFAULT_TEXT_SELECTION.provider, DEFAULT_TEXT_SELECTION.model)) out.push("DEFAULT_TEXT_SELECTION is not a text option");
  if (!has("image", DEFAULT_IMAGE_SELECTION.provider, DEFAULT_IMAGE_SELECTION.model)) out.push("DEFAULT_IMAGE_SELECTION is not a picture option");
  if (DEFAULT_IMAGE_SELECTION.fallbackModel && !has("image-fallback", DEFAULT_IMAGE_SELECTION.provider, DEFAULT_IMAGE_SELECTION.fallbackModel)) {
    out.push("DEFAULT_IMAGE_SELECTION's fallback is not a picture fallback option");
  }
  return out;
}

export interface Readiness {
  ready: boolean;
  /** Why not, in plain words (never a secret's value or path). */
  missing: string[];
}

/** Checks an option's requirements; `secretReadable` is asked only for the secret it names. */
export async function readinessOf(option: Pick<SwitchOption, "requires">, secretReadable: (secret: string) => Promise<boolean>): Promise<Readiness> {
  const missing: string[] = [];
  for (const r of option.requires) {
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
