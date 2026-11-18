/**
 * Model lifecycle (README "Model lifecycle"): when the models the server calls are announced to be
 * retired, and a daily check that tells the owner in time.
 *
 * - **The table** (MODEL_LIFECYCLE): one hand-kept entry per provider/model the server can select,
 *   with the retirement (shutdown) date the provider has announced, or null when none is, the page
 *   it is announced on, and the day a person or agent last read that page. Providers announce
 *   retirements on documentation pages (and by email to projects using the model); only some APIs
 *   say anything about it, so the table is the source and the probes below only add to it.
 *   `lifecycleProblems` (run by test/modelLifecycle.test.ts) fails when a model any profile, deploy
 *   default, default param or price table uses has no entry, so a model cannot be added without one.
 * - **The check** (`assessModels` + `logModelLifecycle`, run daily by the scheduled function
 *   checkModelLifecycle; read-only by `npm run admin -- models`): for every model selected or
 *   configured as a fallback in `config/runtime` (over the params, over the defaults: text, its
 *   fallback, the output judge, the weekly prompt review, pictures, their fallback, the deploy defaults that out-of-credit
 *   failures go to) and, less urgently, every model of a listed profile:
 *   `model_retirement_near` inside 90 / 30 / 7 days of the retirement date or past it (once per
 *   band, rising severity; every run once past while in use), `model_lifecycle_stale` when the
 *   table's last check of a model in use is older than LIFECYCLE_STALE_DAYS (or it has no entry),
 *   and, where the provider has a free metadata call (`modelInfo` on its descriptor), `model_unavailable`
 *   when the provider says the id is not served, and `model_lifecycle_changed` when it reports a
 *   retirement date earlier than the table's or one the table lacks. A probe that fails for any
 *   other reason is `model_probe_failed` (info), never an alarm. Never a generating call.
 *
 * "Outdated" (a newer, better or cheaper model exists) is not detected: no API says it reliably.
 * The routine is the periodic review in the README; `model_lifecycle_stale` is its reminder.
 *
 * Best effort: the check never touches a user request. Its marks (which band was already told)
 * live in `modelLifecycle/{provider}__{model}` (Admin SDK only, like every collection).
 */
import type { Firestore } from "firebase-admin/firestore";
import { DEFAULT_IMAGE_SELECTION, DEFAULT_TEXT_SELECTION, JUDGE, JUDGE_PRICES, PROMPT_REVIEW } from "../constants";
import type { RuntimeConfig } from "../model";
import type { Logger } from "../ports";
import { PROVIDER_PROFILES, type ProviderProfile } from "./profiles";
import { allProviders, imageProviders, textProviders } from "./registry";
import type { ModelInfo, ProviderContext, ProviderDescriptor } from "./types";

export interface LifecycleEntry {
  provider: string;
  model: string;
  /**
   * The announced retirement / shutdown / discontinuation date (YYYY-MM-DD, read as UTC), or null
   * when the provider has announced none.
   */
  retiresOn: string | null;
  /** true: the provider gives it as "this date or later" (the earliest it may retire). */
  earliest?: boolean;
  /** The page the date (or the absence of one) was read on. */
  source: string;
  /** The day that page was last read for this model (YYYY-MM-DD). */
  checkedOn: string;
  note?: string;
}

const VERTEX_LIFECYCLE = "https://docs.cloud.google.com/vertex-ai/generative-ai/docs/learn/model-versions";
const OPENAI_DEPRECATIONS = "https://developers.openai.com/api/docs/deprecations";
const BFL_RELEASE_NOTES = "https://docs.bfl.ai/release-notes";

/**
 * The table. Update `checkedOn` whenever the source is read again, even when nothing changed (the
 * README routine); a changed date goes in with the same commit.
 */
export const MODEL_LIFECYCLE: readonly LifecycleEntry[] = [
  {
    provider: "google",
    model: "gemini-3.8-flash",
    retiresOn: "2027-09-02",
    earliest: true,
    source: VERTEX_LIFECYCLE,
    checkedOn: "2026-11-18",
    note: "GA table: \"September 2, 2027 or later\"; released September 2, 2026. Also the weekly prompt review's model (PROMPT_REVIEW_MODEL, default PROMPT_REVIEW.model), and the output judge's while GEMINI_JUDGE_MODEL is set empty.",
  },
  {
    provider: "google",
    model: "gemini-3.5-flash-lite",
    retiresOn: "2027-07-21",
    earliest: true,
    source: VERTEX_LIFECYCLE,
    checkedOn: "2026-11-18",
    note: "GA table: \"July 21, 2027 or later\"; released July 21, 2026. The output judge's model (GEMINI_JUDGE_MODEL, default JUDGE.model).",
  },
  {
    provider: "google",
    model: "gemini-3.1-flash-lite",
    retiresOn: "2027-05-07",
    earliest: true,
    source: VERTEX_LIFECYCLE,
    checkedOn: "2026-11-18",
    note: "GA table: \"May 7, 2027 or later\"; released May 7, 2026. Priced for the judge (JUDGE_PRICES), not selected by default.",
  },
  {
    provider: "vertex",
    model: "gemini-3.1-flash-lite-image",
    retiresOn: "2027-06-28",
    earliest: true,
    source: VERTEX_LIFECYCLE,
    checkedOn: "2026-11-18",
    note: "Gemini image table: \"June 28, 2027 or later\".",
  },
  {
    provider: "vertex",
    model: "gemini-nano-banana-2.1",
    retiresOn: null,
    source: VERTEX_LIFECYCLE,
    checkedOn: "2026-11-18",
    note: "Short-term table: \"No retirement date announced\". Short-term models get a fixed date at least 45 days ahead when one is set: re-read often.",
  },
  {
    provider: "openai",
    model: "gpt-image-2.6-flare",
    retiresOn: null,
    source: OPENAI_DEPRECATIONS,
    checkedOn: "2026-11-18",
    note: "Not on the deprecations page: no shutdown announced. Named there (with gpt-image-2.6-sunburst) as the replacement of gpt-image-1-mini, shut down December 1, 2026, which it replaced in profile openai (listed only, not tried live).",
  },
  {
    provider: "bfl",
    model: "flux-3-image-klein",
    retiresOn: null,
    source: BFL_RELEASE_NOTES,
    checkedOn: "2026-11-18",
    note: "Launched October 15, 2026; BFL has no deprecations page and no model API, only its release notes (its one precedent gave about four weeks' notice).",
  },
  {
    provider: "bfl",
    model: "flux-3-image",
    retiresOn: null,
    source: BFL_RELEASE_NOTES,
    checkedOn: "2026-11-18",
    note: "Launched October 1, 2026; as flux-3-image-klein.",
  },
];

/** Days before the retirement date at which the owner is told, the most distant first. */
export const LIFECYCLE_WARN_DAYS = [90, 30, 7] as const;
/** A model in use whose entry was last checked longer ago than this is `model_lifecycle_stale`. */
export const LIFECYCLE_STALE_DAYS = 60;
/** How often a stale entry is told again while it stays stale. */
export const LIFECYCLE_STALE_REPEAT_DAYS = 7;
/** Each probe's time limit. */
export const LIFECYCLE_PROBE_TIMEOUT_MS = 10_000;
export const LIFECYCLE_COLLECTION = "modelLifecycle";

const DAY_MS = 86_400_000;
const DAY = /^\d{4}-\d{2}-\d{2}$/;

export function lifecycleEntry(provider: string, model: string, table: readonly LifecycleEntry[] = MODEL_LIFECYCLE): LifecycleEntry | undefined {
  return table.find((e) => e.provider === provider && e.model === model);
}

/** Whole days from `from` to `to` (YYYY-MM-DD each); negative when `to` is earlier. */
export function daysBetween(from: string, to: string): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / DAY_MS);
}

/** A valid calendar day (YYYY-MM-DD that round-trips). */
export function isDay(s: unknown): s is string {
  if (typeof s !== "string" || !DAY.test(s)) return false;
  const t = Date.parse(`${s}T00:00:00Z`);
  return Number.isFinite(t) && new Date(t).toISOString().slice(0, 10) === s;
}

/** The band a retirement `daysLeft` away is in: 0 (today or past), 7, 30, 90, or null (further). */
export function retirementBand(daysLeft: number): 0 | 7 | 30 | 90 | null {
  if (daysLeft <= 0) return 0;
  for (const d of [...LIFECYCLE_WARN_DAYS].reverse()) if (daysLeft <= d) return d;
  return null;
}

// --- Which models are in use ---------------------------------------------------------------------

export type ModelRole = "text" | "text_fallback" | "judge" | "prompt_review" | "image" | "image_fallback" | "text_default" | "image_default" | `profile:${string}`;

export interface ModelUse {
  provider: string;
  model: string;
  /** Every role it has; profile roles are `profile:<id>`. */
  roles: ModelRole[];
  /** Selected, a fallback or a deploy default (true), or only in a listed profile (false). */
  inUse: boolean;
}

function profileModels(p: ProviderProfile): { provider: string; model: string }[] {
  const out: { provider: string; model: string }[] = [];
  if (p.text) {
    out.push({ provider: p.text.provider, model: p.text.model });
    if (p.text.fallbackModel) out.push({ provider: p.text.fallbackProvider || p.text.provider, model: p.text.fallbackModel });
  }
  if (p.image.provider !== "off" && p.image.model) {
    out.push({ provider: p.image.provider, model: p.image.model });
    if (p.image.fallbackModel) out.push({ provider: p.image.fallbackProvider || p.image.provider, model: p.image.fallbackModel });
  }
  return out;
}

/**
 * The models a parsed `config/runtime` puts in use, then those only in a listed profile. `judge`
 * is the output judge's model (GEMINI_JUDGE_MODEL, else GEMINI_MODEL; always the google provider),
 * in use while `outputJudge` is not "off". `review` is the weekly prompt review's model
 * (PROMPT_REVIEW_MODEL, always the google provider; core/promptReview.ts), in use while
 * `promptReviewEnabled` is on.
 */
export function modelsInUse(
  config: Pick<RuntimeConfig, "text" | "image" | "providerDefaults" | "outputJudge"> & Partial<Pick<RuntimeConfig, "promptReviewEnabled">>,
  judge: { provider: string; model: string } | null,
  profiles: readonly ProviderProfile[] = PROVIDER_PROFILES,
  review: { provider: string; model: string } | null = null,
): ModelUse[] {
  const out: ModelUse[] = [];
  const add = (provider: string, model: string, role: ModelRole, inUse: boolean) => {
    if (!provider || !model) return;
    let u = out.find((x) => x.provider === provider && x.model === model);
    if (!u) {
      u = { provider, model, roles: [], inUse: false };
      out.push(u);
    }
    if (!u.roles.includes(role)) u.roles.push(role);
    u.inUse ||= inUse;
  };
  const t = config.text;
  add(t.provider, t.model, "text", true);
  if (t.fallbackModel) add(t.fallbackProvider || t.provider, t.fallbackModel, "text_fallback", true);
  if (judge) add(judge.provider, judge.model, "judge", config.outputJudge !== "off");
  if (review) add(review.provider, review.model, "prompt_review", config.promptReviewEnabled !== false);
  const i = config.image;
  if (i.provider !== "off") {
    add(i.provider, i.model, "image", true);
    if (i.fallbackModel) add(i.fallbackProvider || i.provider, i.fallbackModel, "image_fallback", true);
  }
  add(config.providerDefaults.text.provider, config.providerDefaults.text.model, "text_default", true);
  if (config.providerDefaults.image) add(config.providerDefaults.image.provider, config.providerDefaults.image.model, "image_default", true);
  for (const p of profiles) for (const m of profileModels(p)) add(m.provider, m.model, `profile:${p.id}`, false);
  // In use first, then listed; each in the order found.
  return [...out.filter((u) => u.inUse), ...out.filter((u) => !u.inUse)];
}

// --- The table's own checks ----------------------------------------------------------------------

/**
 * Every problem with the table (empty when sound): a model a profile, a deploy default, a
 * provider's default model or default param, the output judge's default (JUDGE.model, the
 * GEMINI_JUDGE_MODEL param's default) or a price table (JUDGE_PRICES included) names without an entry; an entry
 * twice; an entry for a provider the registry doesn't have; a malformed date or source. `extra`
 * adds models named elsewhere (the judge's default param, read by the test).
 */
export function lifecycleProblems(
  table: readonly LifecycleEntry[] = MODEL_LIFECYCLE,
  extra: readonly { provider: string; model: string; why: string }[] = [],
): string[] {
  const out: string[] = [];
  const need: { provider: string; model: string; why: string }[] = [...extra];
  need.push({ provider: DEFAULT_TEXT_SELECTION.provider, model: DEFAULT_TEXT_SELECTION.model, why: "DEFAULT_TEXT_SELECTION" });
  if (DEFAULT_TEXT_SELECTION.fallbackModel) {
    need.push({ provider: DEFAULT_TEXT_SELECTION.fallbackProvider || DEFAULT_TEXT_SELECTION.provider, model: DEFAULT_TEXT_SELECTION.fallbackModel, why: "DEFAULT_TEXT_SELECTION fallback" });
  }
  need.push({ provider: DEFAULT_IMAGE_SELECTION.provider, model: DEFAULT_IMAGE_SELECTION.model, why: "DEFAULT_IMAGE_SELECTION" });
  if (DEFAULT_IMAGE_SELECTION.fallbackModel) need.push({ provider: DEFAULT_IMAGE_SELECTION.provider, model: DEFAULT_IMAGE_SELECTION.fallbackModel, why: "DEFAULT_IMAGE_SELECTION fallback" });
  for (const d of textProviders()) {
    need.push({ provider: d.id, model: d.defaultModel, why: `${d.id} defaultModel` });
    const param = d.defaultModelParam ? d.params?.find((p) => p.name === d.defaultModelParam) : undefined;
    if (param?.default) need.push({ provider: d.id, model: param.default, why: `${d.id} ${param.name} default` });
    for (const m of Object.keys(d.prices)) need.push({ provider: d.id, model: m, why: `${d.id} price table` });
  }
  for (const d of imageProviders()) for (const m of Object.keys(d.usdPerImage)) need.push({ provider: d.id, model: m, why: `${d.id} price table` });
  // The output judge is always Gemini on Vertex AI (provider "google").
  need.push({ provider: "google", model: JUDGE.model, why: "JUDGE.model (GEMINI_JUDGE_MODEL default)" });
  // So is the weekly prompt review (core/promptReview.ts).
  need.push({ provider: "google", model: PROMPT_REVIEW.model, why: "PROMPT_REVIEW.model (PROMPT_REVIEW_MODEL default)" });
  for (const m of Object.keys(JUDGE_PRICES)) need.push({ provider: "google", model: m, why: "JUDGE_PRICES" });
  for (const p of PROVIDER_PROFILES) for (const m of profileModels(p)) need.push({ ...m, why: `profile ${p.id}` });
  const missing = new Set<string>();
  for (const n of need) {
    const key = `${n.provider}/${n.model}`;
    if (!lifecycleEntry(n.provider, n.model, table) && !missing.has(key)) {
      missing.add(key);
      out.push(`${key} (${n.why}) has no lifecycle entry (providers/lifecycle.ts MODEL_LIFECYCLE)`);
    }
  }
  const ids = new Set(allProviders().map((d) => d.id));
  const seen = new Set<string>();
  for (const e of table) {
    const key = `${e.provider}/${e.model}`;
    if (seen.has(key)) out.push(`${key}: two lifecycle entries`);
    seen.add(key);
    if (!ids.has(e.provider)) out.push(`${key}: unknown provider`);
    if (e.retiresOn !== null && !isDay(e.retiresOn)) out.push(`${key}: retiresOn must be YYYY-MM-DD or null`);
    if (e.earliest && e.retiresOn === null) out.push(`${key}: earliest needs a retiresOn`);
    if (!isDay(e.checkedOn)) out.push(`${key}: checkedOn must be YYYY-MM-DD`);
    if (!/^https:\/\/[^\s?#]+$/.test(e.source)) out.push(`${key}: source must be an https URL without a query`);
  }
  return out;
}

// --- The check -----------------------------------------------------------------------------------

export type ProbeOutcome = "ok" | "unavailable" | "failed" | "none" | "skipped";

export interface ModelReport extends ModelUse {
  /** The table's entry; absent: none. */
  entry?: LifecycleEntry;
  /** The date the check goes by: the earlier of the table's and the API's. */
  retiresOn: string | null;
  retiresOnFrom: "table" | "api" | null;
  daysLeft: number | null;
  band: 0 | 7 | 30 | 90 | null;
  /** Days since the table's entry was checked; null without an entry. */
  checkedDaysAgo: number | null;
  /** A model in use whose entry is missing or older than LIFECYCLE_STALE_DAYS. */
  stale: boolean;
  probe: ProbeOutcome;
  /** `failed`: TIMEOUT or FAILED (a fixed reason, never the provider's text). */
  probeReason?: "TIMEOUT" | "FAILED";
  /** The retirement date the API reported (null: it said none; absent: it has no such field or wasn't asked). */
  apiRetiresOn?: string | null;
  /** The API reported a date earlier than the table's, or one the table lacks. */
  changed: boolean;
}

export interface AssessEnv {
  models: readonly ModelUse[];
  providers?: readonly ProviderDescriptor[];
  context(d: ProviderDescriptor): ProviderContext;
  now(): number;
  /** false: the table only, no provider is asked (the admin command without --probe). */
  probe: boolean;
  table?: readonly LifecycleEntry[];
  timeoutMs?: number;
}

/** What each model's dates and probe say; logs nothing, writes nothing. */
export async function assessModels(env: AssessEnv): Promise<ModelReport[]> {
  const providers = env.providers ?? allProviders();
  const today = new Date(env.now()).toISOString().slice(0, 10);
  const out: ModelReport[] = [];
  for (const u of env.models) {
    const entry = lifecycleEntry(u.provider, u.model, env.table);
    let probe: ProbeOutcome = env.probe ? "none" : "skipped";
    let probeReason: ModelReport["probeReason"];
    let apiRetiresOn: string | null | undefined;
    // A provider id may be both a text and a picture provider: the first with a probe answers.
    const d = providers.find((x) => x.id === u.provider && x.modelInfo);
    if (env.probe && d?.modelInfo) {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), env.timeoutMs ?? LIFECYCLE_PROBE_TIMEOUT_MS);
      try {
        const info: ModelInfo = await d.modelInfo(env.context(d), u.model, controller.signal);
        probe = info.served ? "ok" : "unavailable";
        if (info.retiresOn !== undefined) apiRetiresOn = isDay(info.retiresOn) ? info.retiresOn : null;
      } catch {
        probe = "failed";
        probeReason = controller.signal.aborted ? "TIMEOUT" : "FAILED";
      } finally {
        clearTimeout(timer);
      }
    }
    const tableOn = entry?.retiresOn ?? null;
    const changed = typeof apiRetiresOn === "string" && (tableOn === null || apiRetiresOn < tableOn);
    const retiresOn = changed ? (apiRetiresOn as string) : tableOn;
    const daysLeft = retiresOn === null ? null : daysBetween(today, retiresOn);
    const checkedDaysAgo = entry ? daysBetween(entry.checkedOn, today) : null;
    out.push({
      ...u,
      ...(entry ? { entry } : {}),
      retiresOn,
      retiresOnFrom: retiresOn === null ? null : changed ? "api" : "table",
      daysLeft,
      band: daysLeft === null ? null : retirementBand(daysLeft),
      checkedDaysAgo,
      stale: u.inUse && (checkedDaysAgo === null || checkedDaysAgo > LIFECYCLE_STALE_DAYS),
      probe,
      ...(probeReason ? { probeReason } : {}),
      ...(apiRetiresOn !== undefined ? { apiRetiresOn } : {}),
      changed,
    });
  }
  return out;
}

/** What was already told about a model, so a band or a stale entry is emailed once, not daily. */
export interface LifecycleMark {
  provider: string;
  model: string;
  /** The most urgent band told for `bandRetiresOn`. */
  band?: number | null;
  bandRetiresOn?: string | null;
  staleLoggedAt?: number | null;
  staleCheckedOn?: string | null;
  /** The API date already told as `model_lifecycle_changed`. */
  changedRetiresOn?: string | null;
  /** The last run's outcome (for a later console card): day, probe, daysLeft. */
  lastRun?: { at: number; probe: ProbeOutcome; daysLeft: number | null; retiresOn: string | null; inUse: boolean; roles: string[] };
}

export const lifecycleMarkId = (provider: string, model: string) => `${provider}__${model}`;

function level(logger: Logger, l: "info" | "warn" | "error") {
  return l === "info" ? logger.info.bind(logger) : l === "warn" ? logger.warn.bind(logger) : logger.error.bind(logger);
}

/**
 * Logs what the reports call for, against the marks of earlier runs, and writes the new marks.
 * Without `db` (or when the marks can't be read) nothing is deduplicated: every line is logged.
 * Returns the messages logged, in order.
 */
export async function logModelLifecycle(reports: readonly ModelReport[], env: { db?: Firestore; logger: Logger; now(): number }): Promise<string[]> {
  const logged: string[] = [];
  const log = (l: "info" | "warn" | "error", message: string, data: Record<string, unknown>) => {
    level(env.logger, l)(message, data);
    logged.push(message);
  };
  const col = env.db?.collection(LIFECYCLE_COLLECTION);
  let marks = new Map<string, LifecycleMark>();
  if (col) {
    try {
      const snap = await col.get();
      marks = new Map(snap.docs.map((d) => [d.id, d.data() as LifecycleMark]));
    } catch {
      env.logger.warn("model_lifecycle_marks_unavailable", { reason: "READ_FAILED" });
    }
  }
  const nowMs = env.now();
  for (const r of reports) {
    const id = lifecycleMarkId(r.provider, r.model);
    const old = marks.get(id) ?? { provider: r.provider, model: r.model };
    const next: LifecycleMark = { ...old, provider: r.provider, model: r.model };
    const base = { provider: r.provider, model: r.model, role: r.roles[0], roles: r.roles, inUse: r.inUse };

    if (r.band !== null) {
      const sameDate = old.bandRetiresOn === r.retiresOn;
      const moreUrgent = !sameDate || old.band === undefined || old.band === null || r.band < old.band;
      // Once past while in use, every run says so: the calls will fail any day now.
      if (moreUrgent || (r.band === 0 && r.inUse)) {
        const l = r.inUse ? (r.band <= 7 ? "error" : r.band === 30 ? "warn" : "info") : r.band <= 7 ? "warn" : "info";
        log(l, "model_retirement_near", {
          ...base,
          retiresOn: r.retiresOn,
          daysLeft: r.daysLeft,
          band: r.band,
          from: r.retiresOnFrom,
          ...(r.entry?.earliest && r.retiresOnFrom === "table" ? { earliest: true } : {}),
        });
      }
      next.band = sameDate && typeof old.band === "number" ? Math.min(old.band, r.band) : r.band;
      next.bandRetiresOn = r.retiresOn;
    } else {
      next.band = null;
      next.bandRetiresOn = r.retiresOn;
    }

    if (r.stale) {
      const checkedOn = r.entry?.checkedOn ?? null;
      const due = old.staleCheckedOn !== checkedOn || !old.staleLoggedAt || nowMs - old.staleLoggedAt >= LIFECYCLE_STALE_REPEAT_DAYS * DAY_MS;
      if (due) {
        log("warn", "model_lifecycle_stale", {
          ...base,
          checkedOn,
          checkedDaysAgo: r.checkedDaysAgo,
          staleDays: LIFECYCLE_STALE_DAYS,
          reason: r.entry ? "OLD_CHECK" : "NO_ENTRY",
        });
        next.staleLoggedAt = nowMs;
        next.staleCheckedOn = checkedOn;
      }
    } else {
      next.staleLoggedAt = null;
      next.staleCheckedOn = null;
    }

    if (r.probe === "unavailable") log(r.inUse ? "error" : "warn", "model_unavailable", base);
    if (r.probe === "failed") log("info", "model_probe_failed", { ...base, reason: r.probeReason ?? "FAILED" });
    if (r.changed) {
      if (old.changedRetiresOn !== r.apiRetiresOn) {
        log("warn", "model_lifecycle_changed", { ...base, tableRetiresOn: r.entry?.retiresOn ?? null, apiRetiresOn: r.apiRetiresOn });
      }
      next.changedRetiresOn = r.apiRetiresOn ?? null;
    } else {
      next.changedRetiresOn = null;
    }

    next.lastRun = { at: nowMs, probe: r.probe, daysLeft: r.daysLeft, retiresOn: r.retiresOn, inUse: r.inUse, roles: r.roles };
    if (col) {
      try {
        await col.doc(id).set(next);
      } catch {
        env.logger.warn("model_lifecycle_marks_unavailable", { reason: "WRITE_FAILED", provider: r.provider, model: r.model });
      }
    }
  }
  const inUse = reports.filter((r) => r.inUse);
  env.logger.info("model_lifecycle_checked", {
    models: reports.length,
    inUse: inUse.length,
    soonestDaysLeft: inUse.reduce<number | null>((m, r) => (r.daysLeft === null ? m : m === null ? r.daysLeft : Math.min(m, r.daysLeft)), null),
    stale: reports.filter((r) => r.stale).length,
    unavailable: reports.filter((r) => r.probe === "unavailable").length,
    probeFailed: reports.filter((r) => r.probe === "failed").length,
  });
  return logged;
}
