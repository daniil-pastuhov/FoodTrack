/**
 * Every recipe-text and recipe-picture provider the server can call: one entry per adapter
 * (README "Adding a provider"). The rest of the server reads providers only through this file:
 * which ids are valid in config/runtime and the profiles, their default prices, their secrets,
 * params and readiness, and the adapters src/index.ts builds.
 *
 * To add a provider: write its adapter file in src/adapters/ exporting a descriptor
 * (providers/types.ts) and add it to TEXT or IMAGE below. Nothing else names it.
 */
import { IMAGE as IMAGE_TIMES } from "../constants";
import { bflImage } from "../adapters/bfl";
import { googleText } from "../adapters/gemini";
import { openaiImage, vertexImage } from "../adapters/image";
import type {
  ImageProviderDescriptor,
  ImageTiming,
  ImageTokenPrices,
  ProviderDescriptor,
  ProviderSecret,
  TextProviderDescriptor,
  TokenPrices,
} from "./types";

const TEXT: TextProviderDescriptor[] = [
  googleText,
];

const IMAGE: ImageProviderDescriptor[] = [
  vertexImage,
  openaiImage,
  bflImage,
];

/** The picture provider id that makes no pictures (no adapter). */
export const IMAGE_OFF = "off";

export const textProviders = (): readonly TextProviderDescriptor[] => TEXT;
export const imageProviders = (): readonly ImageProviderDescriptor[] => IMAGE;
export const allProviders = (): readonly ProviderDescriptor[] => [...TEXT, ...IMAGE];

export const textProviderById = (id: unknown): TextProviderDescriptor | undefined =>
  typeof id === "string" ? TEXT.find((d) => d.id === id) : undefined;
export const imageProviderById = (id: unknown): ImageProviderDescriptor | undefined =>
  typeof id === "string" ? IMAGE.find((d) => d.id === id) : undefined;

/** The ids valid as config/runtime.image.provider: every picture adapter, and "off". */
export const imageProviderIds = (): string[] => [...IMAGE.map((d) => d.id), IMAGE_OFF];

/** USD per picture by "provider/model": every adapter's table (config/runtime may add or override). */
export function defaultImagePrices(): Record<string, number> {
  const out: Record<string, number> = {};
  for (const d of IMAGE) for (const [model, usd] of Object.entries(d.usdPerImage)) out[`${d.id}/${model}`] = usd;
  return out;
}

/** The token rates of a picture model that reports token counts, if its adapter lists them. */
export function imageTokenPricesOf(provider: string, model: string): ImageTokenPrices | undefined {
  const rates = imageProviderById(provider)?.tokenPrices;
  return rates && Object.prototype.hasOwnProperty.call(rates, model) ? rates[model] : undefined;
}

/**
 * The picture times while `provider` is the selected picture model: its descriptor's `timing`
 * over constants.ts IMAGE (also for "off" and an unknown id).
 */
export function imageTimingOf(provider: string): ImageTiming {
  const own = imageProviderById(provider)?.timing ?? {};
  return {
    abortMs: own.abortMs ?? IMAGE_TIMES.abortMs,
    budgetMs: { ...IMAGE_TIMES.budgetMs, ...own.budgetMs },
    reservationExtraMs: { ...IMAGE_TIMES.reservationExtraMs, ...own.reservationExtraMs },
  };
}

/** A text model's default price from its adapter's table, if listed. */
export function textDefaultPrices(provider: string, model: string): TokenPrices | undefined {
  const prices = textProviderById(provider)?.prices;
  return prices && Object.prototype.hasOwnProperty.call(prices, model) ? prices[model] : undefined;
}

/** Every secret any provider declares, by id (one per id). */
export function providerSecrets(): ProviderSecret[] {
  const out = new Map<string, ProviderSecret>();
  for (const d of allProviders()) if (d.secret && !out.has(d.secret.id)) out.set(d.secret.id, d.secret);
  return [...out.values()];
}

/** Every problem with the registry itself (empty when sound); run by the tests. */
export function providerRegistryProblems(): string[] {
  const out: string[] = [];
  // The same shape as a "provider/model" price key's provider (model.ts).
  const ID = /^[a-z][a-z0-9]{1,19}$/;
  const seen = new Set<string>();
  for (const d of allProviders()) {
    const at = `${d.kind} provider ${JSON.stringify(d.id)}`;
    if (!ID.test(d.id) || d.id === IMAGE_OFF) out.push(`${at}: id must match ${ID} and not be "off"`);
    if (seen.has(`${d.kind}/${d.id}`)) out.push(`${at}: duplicate id`);
    seen.add(`${d.kind}/${d.id}`);
    if (!d.privacy.approved && !d.privacy.missing.trim()) out.push(`${at}: an unapproved provider must say what is missing`);
    if (d.kind === "text") {
      if (!d.runtimeTokenPrices && !(d.defaultModel in d.prices)) out.push(`${at}: no price for its default model`);
      for (const [m, p] of Object.entries(d.prices)) {
        const ok = (n: number) => Number.isFinite(n) && n >= 0.01 && n <= 1000;
        if (!ok(p.inputUsdPerMTok) || !ok(p.outputUsdPerMTok)) out.push(`${at}: price of ${m} must be 0.01 to 1000 USD per 1M tokens`);
      }
    } else {
      for (const [m, usd] of Object.entries(d.usdPerImage)) {
        if (!Number.isFinite(usd) || usd < 0.001 || usd > 2) out.push(`${at}: price of ${m} must be 0.001 to 2 USD`);
      }
      if (d.timing) {
        const t = imageTimingOf(d.id);
        const min = IMAGE_TIMES.minAttemptMs;
        if (!(t.abortMs >= min)) out.push(`${at}: timing.abortMs must be at least IMAGE.minAttemptMs`);
        // A timed-out first attempt still leaves the fallback its attempt.
        if (!(t.budgetMs.dish >= t.abortMs + min)) out.push(`${at}: timing.budgetMs.dish must leave IMAGE.minAttemptMs after an attempt`);
        if (!(t.budgetMs.steps > t.budgetMs.dish + min)) out.push(`${at}: timing.budgetMs.steps must exceed the dish's budget by IMAGE.minAttemptMs`);
        for (const level of ["dish", "steps"] as const) {
          if (!(t.reservationExtraMs[level] >= t.budgetMs[level])) out.push(`${at}: timing.reservationExtraMs.${level} must cover its budget`);
        }
      }
    }
  }
  // src/index.ts defines each param name once, with the first adapter's default: two adapters
  // sharing a name must agree on it.
  const params = new Map<string, string>();
  for (const d of allProviders()) {
    for (const p of d.params ?? []) {
      const prior = params.get(p.name);
      if (prior !== undefined && prior !== p.default) out.push(`param ${p.name}: declared twice with different defaults`);
      if (prior === undefined) params.set(p.name, p.default);
    }
  }
  const secrets = new Map<string, string>();
  for (const d of allProviders()) {
    if (!d.secret) continue;
    const key = `${d.secret.param}=${d.secret.name}`;
    const prior = secrets.get(d.secret.id);
    if (prior !== undefined && prior !== key) out.push(`secret ${d.secret.id}: declared twice with different params`);
    secrets.set(d.secret.id, key);
  }
  return out;
}

/**
 * Tests only: adds a provider as if it had an entry above, and returns the function that removes it
 * again (test/providerAdapters.test.ts proves "one file plus one entry" with it).
 */
export function registerProviderForTests(d: ProviderDescriptor): () => void {
  const list: ProviderDescriptor[] = d.kind === "text" ? TEXT : IMAGE;
  list.push(d);
  return () => {
    const i = list.indexOf(d);
    if (i >= 0) list.splice(i, 1);
  };
}
