/**
 * The taste quiz as the apps get it (`getTasteQuiz`), and a request's `taste` cleaned against it.
 * Both read the catalogue (catalogue.ts) through the `config/runtime.tasteQuiz.hidden` list, so a
 * hidden step or option is neither offered nor used.
 */
import { createHash } from "node:crypto";
import type { TasteProfile, TasteQuizResponse, TasteQuizStep } from "../contract";
import type { Deps } from "../ports";
import {
  TASTE_CATALOGUE,
  TASTE_LANGUAGES,
  type CatalogueOption,
  type CatalogueStep,
  type TasteCatalogue,
  type TasteLanguage,
} from "./catalogue";

/** A catalogue step with only its visible groups and options (none empty). */
interface VisibleStep {
  step: CatalogueStep;
  groups: { title: CatalogueStep["groups"][number]["title"]; options: CatalogueOption[] }[];
  /** Every visible option, in catalogue order. */
  options: CatalogueOption[];
}

/**
 * The entries of `hidden` that name a step or an option of the catalogue, sorted, without
 * repeats: what actually hides something (a typo hides nothing and changes no version).
 */
export function effectiveHidden(hidden: readonly string[], catalogue: TasteCatalogue = TASTE_CATALOGUE): string[] {
  const known = new Set<string>();
  for (const s of catalogue.steps) {
    known.add(s.id);
    for (const g of s.groups) for (const o of g.options) known.add(`${s.id}:${o.id}`);
  }
  return [...new Set(hidden.filter((h) => known.has(h)))].sort();
}

export function visibleSteps(hidden: readonly string[], catalogue: TasteCatalogue = TASTE_CATALOGUE): VisibleStep[] {
  const off = new Set(hidden);
  const out: VisibleStep[] = [];
  for (const step of catalogue.steps) {
    if (off.has(step.id)) continue;
    const groups = step.groups
      .map((g) => ({ title: g.title, options: g.options.filter((o) => !off.has(`${step.id}:${o.id}`)) }))
      .filter((g) => g.options.length > 0);
    if (groups.length === 0) continue;
    out.push({ step, groups, options: groups.flatMap((g) => g.options) });
  }
  return out;
}

/**
 * The quiz's version: the first 16 hex characters of sha256 over the whole catalogue (structure,
 * every text, the prompt words) and the hidden entries that hide something. It changes whenever
 * any of those change and never otherwise; the same in every language.
 */
export function tasteQuizVersion(hidden: readonly string[], catalogue: TasteCatalogue = TASTE_CATALOGUE): string {
  const effective = effectiveHidden(hidden, catalogue);
  const key = JSON.stringify(effective);
  if (catalogue === TASTE_CATALOGUE && versions.has(key)) return versions.get(key)!;
  const version = createHash("sha256").update(JSON.stringify({ catalogue, hidden: effective })).digest("hex").slice(0, 16);
  // Sent beside every balance: memoised per hidden list for the deployed catalogue (a few entries at most).
  if (catalogue === TASTE_CATALOGUE && versions.size < 32) versions.set(key, version);
  return version;
}
const versions = new Map<string, string>();

/**
 * The language to answer in: an app language as sent (lower-cased, its first subtag: "de-DE" is
 * "de"; "no" and "nn" are "nb"), else English. Never an error.
 */
export function tasteLanguageOf(v: unknown): TasteLanguage {
  if (typeof v !== "string" || v.length > 35) return "en";
  const primary = v.toLowerCase().split(/[-_]/)[0];
  const code = primary === "no" || primary === "nn" ? "nb" : primary;
  return (TASTE_LANGUAGES as readonly string[]).includes(code) ? (code as TasteLanguage) : "en";
}

export function tasteQuizFor(language: TasteLanguage, hidden: readonly string[], catalogue: TasteCatalogue = TASTE_CATALOGUE): TasteQuizResponse {
  const visible = visibleSteps(hidden, catalogue);
  const shown = new Set(visible.flatMap((v) => [v.step.id, ...v.options.map((o) => `${v.step.id}:${o.id}`)]));
  const steps: TasteQuizStep[] = visible.map(({ step, groups }) => ({
    id: step.id,
    kind: step.kind,
    tone: step.tone,
    icon: step.icon,
    question: step.question[language],
    note: step.note ? step.note[language] : null,
    max: step.max,
    groups: groups.map((g) => ({
      title: g.title ? g.title[language] : null,
      options: g.options.map((o) => ({
        id: o.id,
        label: o.label[language],
        ...(o.sub ? { sub: o.sub[language] } : {}),
        ...(o.level ? { level: o.level } : {}),
      })),
    })),
  }));
  return {
    version: tasteQuizVersion(hidden, catalogue),
    language,
    steps,
    conflicts: catalogue.conflicts.filter(([a, b]) => shown.has(a) && shown.has(b)).map(([a, b]) => [a, b]),
    summary: catalogue.summary.filter((s) => shown.has(s)),
  };
}

/** getTasteQuiz: reads only `config/runtime` (cached); no account, no reservation, no model. */
export async function getTasteQuiz(deps: Pick<Deps, "config">, data: unknown): Promise<TasteQuizResponse> {
  const config = await deps.config();
  const language = tasteLanguageOf(typeof data === "object" && data !== null ? (data as Record<string, unknown>).language : undefined);
  return tasteQuizFor(language, config.tasteQuiz.hidden);
}

/**
 * A request's `taste` (already shape-checked into step → ids) cleaned against the visible
 * catalogue (CONTRACT.md "Taste profile"): unknown or hidden steps and options dropped, repeats
 * collapsed; the `avoid`-tone steps are cut to their cap first (in catalogue order), then every
 * conflict pair with both sides chosen loses its side on a `like` step (of two `like` sides, the
 * second of the pair), then the other steps are cut to their cap in catalogue order. Undefined
 * when nothing is left. Never throws: old answers to a changed quiz only lose what is gone.
 */
export function cleanTaste(sent: ReadonlyMap<string, readonly string[]>, hidden: readonly string[], catalogue: TasteCatalogue = TASTE_CATALOGUE): TasteProfile | undefined {
  const visible = visibleSteps(hidden, catalogue);
  const chosen = new Map<string, CatalogueOption[]>();
  for (const v of visible) {
    const ids = new Set(sent.get(v.step.id) ?? []);
    chosen.set(v.step.id, v.options.filter((o) => ids.has(o.id)));
  }
  const cap = (v: VisibleStep) => chosen.set(v.step.id, chosen.get(v.step.id)!.slice(0, v.step.max));
  visible.filter((v) => v.step.tone === "avoid").forEach(cap);
  const has = (key: string) => {
    const [step, option] = key.split(":");
    return chosen.get(step)?.some((o) => o.id === option) ?? false;
  };
  const toneOf = (key: string) => visible.find((v) => v.step.id === key.split(":")[0])?.step.tone;
  for (const [a, b] of catalogue.conflicts) {
    if (!has(a) || !has(b)) continue;
    const loser = toneOf(b) === "like" ? b : toneOf(a) === "like" ? a : b;
    const [step, option] = loser.split(":");
    chosen.set(step, chosen.get(step)!.filter((o) => o.id !== option));
  }
  visible.filter((v) => v.step.tone !== "avoid").forEach(cap);
  const profile = visible
    .map((v) => ({ step: v.step.id, options: chosen.get(v.step.id)!.map((o) => o.id) }))
    .filter((s) => s.options.length > 0);
  return profile.length ? profile : undefined;
}

/** The catalogue step and option of a cleaned answer (cleanTaste only keeps ones that exist). */
export function catalogueStep(id: string, catalogue: TasteCatalogue = TASTE_CATALOGUE): CatalogueStep {
  const s = catalogue.steps.find((x) => x.id === id);
  if (!s) throw new Error(`unknown taste step ${id}`);
  return s;
}

export function catalogueOption(step: CatalogueStep, id: string): CatalogueOption {
  for (const g of step.groups) for (const o of g.options) if (o.id === id) return o;
  throw new Error(`unknown taste option ${step.id}:${id}`);
}
