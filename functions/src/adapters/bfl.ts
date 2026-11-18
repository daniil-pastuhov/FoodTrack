/**
 * Black Forest Labs (FLUX) as a recipe picture provider (provider id "bfl"): one adapter file plus
 * one entry in providers/registry.ts (README "Adding a provider", "FLUX by Black Forest Labs").
 *
 * The API is asynchronous (BFL docs, read 2026-11-17: docs.bfl.ml/api_integration/
 * integration_guidelines.md, api-reference/utility/generate-an-image-with-flux-3-klein.md,
 * api-reference/utility/get-result.md, api_integration/errors.md, quick_start/pricing.md):
 *
 * 1. `POST <base>/v1/<model>` with the key in `x-key` answers `{id, polling_url, cost}` at once
 *    (`cost` in credits; 1 credit = USD 0.01).
 * 2. `GET polling_url` (always the returned one: it names the cluster holding the task) until
 *    `status` is `Ready` (`result.sample` is the picture's URL), `Request Moderated` / `Content
 *    Moderated` (BFL's moderation refused the prompt or the picture: a `safety` failure, not
 *    charged), `Error` / `Task not found` / anything else terminal. `Pending`, `Reasoning`,
 *    `Generating` mean keep polling. A failed task may come back as HTTP 503 with a normal JSON
 *    body, so the body's status is read before the HTTP status.
 * 3. `GET result.sample` downloads the picture, from a delivery host under `bfl.ai`
 *    (`delivery.*.bfl.ai`), within 10 minutes; no key is sent there.
 *
 * What is sent: the server's fixed picture prompt (core/recipeImage.ts), the 4:3 shape at the 1K
 * tier, JPEG, and BFL's default moderation level (`safety_tolerance` 2, of 0 strictest to 7). Never
 * BFL's `user` field or any other identifier of the person, never a webhook. FLUX 3 Image is sent
 * `grounding: false` (no web or image search on the prompt). FLUX 3 Image Klein's API rewrites
 * every prompt with BFL's own prompt upsampler before drawing (it cannot be turned off).
 *
 * Safety of the calls themselves: the key goes only to the configured API host and to a
 * `polling_url` on an API host of the same region over https (BFL_HOSTS; anything else is refused
 * before it is asked); the picture is downloaded only from a delivery host of that region, with
 * redirects refused and at most IMAGE.maxInputBytes read. Every wait honours the abort signal (the
 * core's per-attempt timeout inside the picture's budget, `timing` below): the adapter has no time
 * limit of its own and polls for as long as the signal lets it. Error messages are fixed texts with at
 * most an HTTP status or BFL's status word: never BFL's text, the prompt, a URL or the key.
 */
import { IMAGE } from "../constants";
import { ImageGenerationError, type GeneratedImage, type ImagePrompt, type ImageTarget, type ImageUsage, type RecipeImageGenerator } from "../ports";
import { CAPACITY_5XX, httpFailureKind, retryAfterMs } from "../providers/failure";
import type { ImageProviderDescriptor } from "../providers/types";

/** The API hosts by BFL_REGION. `eu`: multi-cluster routing limited to EU regions (the default). */
export const BFL_API_BASES: Readonly<Record<string, string>> = {
  eu: "https://api.eu.bfl.ai",
  us: "https://api.us.bfl.ai",
  global: "https://api.bfl.ai",
};

/** The FLUX models this adapter can ask, each its own endpoint (`/v1/<model>`). */
export const BFL_MODELS = ["flux-3-image-klein", "flux-3-image"] as const;
export type BflModel = (typeof BFL_MODELS)[number];
const isBflModel = (m: string): m is BflModel => (BFL_MODELS as readonly string[]).includes(m);

/** BFL's moderation level, 0 strictest to 7; 2 is BFL's own default on every endpoint. */
export const BFL_SAFETY_TOLERANCE = 2;

/**
 * The submission body. Both models reject unknown fields (HTTP 422), so each gets only its own:
 * FLUX 3 Image Klein takes `output_format`; FLUX 3 Image has none (and `grounding`, sent false).
 * `resolution: "1k"` with `aspect_ratio: "4:3"` is about one megapixel, billed at the 1K tier.
 */
export function bflBody(prompt: ImagePrompt, model: BflModel): Record<string, unknown> {
  const common = { prompt: prompt.text, aspect_ratio: prompt.aspectRatio, resolution: "1k", safety_tolerance: BFL_SAFETY_TOLERANCE };
  return model === "flux-3-image-klein" ? { ...common, output_format: "jpeg" } : { ...common, grounding: false };
}

/**
 * The hosts a task's `polling_url` (which gets the key) and its picture (downloaded without it)
 * may be on, by region. Seen live on 2026-11-17 from api.eu.bfl.ai: polling on api.eu9.bfl.ai,
 * the picture on delivery.eu9.bfl.ai (BFL: "the region identifier is subject to change", so the
 * cluster number is not pinned, only its region). The EU endpoint accepts only EU clusters, so a
 * task routed anywhere else is refused before the key or the picture travels there.
 */
export const BFL_HOSTS: Readonly<Record<string, { api: RegExp; poll: RegExp; delivery: RegExp }>> = {
  eu: { api: /^api\.eu\.bfl\.ai$/, poll: /^api\.eu[0-9]{0,3}\.bfl\.ai$/, delivery: /^delivery\.eu[0-9]{0,3}\.bfl\.ai$/ },
  us: { api: /^api\.us\.bfl\.ai$/, poll: /^api\.us[0-9]{0,3}\.bfl\.ai$/, delivery: /^delivery\.us[0-9]{0,3}\.bfl\.ai$/ },
  global: { api: /^api\.bfl\.ai$/, poll: /^api(\.[a-z0-9-]+)?\.bfl\.ai$/, delivery: /^delivery(\.[a-z0-9-]+)?\.bfl\.ai$/ },
};

/** The URL when it is https on a host matching `host` with nothing unusual in it, else null. */
export function bflUrl(raw: unknown, host: RegExp): URL | null {
  if (typeof raw !== "string" || raw.length > 2048) return null;
  let u: URL;
  try {
    u = new URL(raw);
  } catch {
    return null;
  }
  if (u.protocol !== "https:" || u.username || u.password || u.port) return null;
  return host.test(u.hostname) ? u : null;
}

/** Statuses meaning the task is still running. */
const IN_PROGRESS: ReadonlySet<string> = new Set(["Pending", "Reasoning", "Generating", "Queued"]);
const MODERATED: ReadonlySet<string> = new Set(["Request Moderated", "Content Moderated"]);

/** Credits as micro-USD (1 credit = USD 0.01), when BFL's `cost` is a plausible number (0 to 200 credits). */
function creditsUsage(cost: unknown): ImageUsage | undefined {
  if (typeof cost !== "number" || !Number.isFinite(cost) || cost < 0 || cost > 200) return undefined;
  return { inputTokens: 0, outputTextTokens: 0, outputImageTokens: Math.round(cost * 10_000) };
}

/** The picture's type from its first bytes (JPEG, PNG or WebP), else a type the core refuses. */
function sniffMime(b: Buffer): string {
  if (b.length >= 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return "image/jpeg";
  if (b.length >= 4 && b[0] === 0x89 && b.toString("latin1", 1, 4) === "PNG") return "image/png";
  if (b.length >= 12 && b.toString("latin1", 0, 4) === "RIFF" && b.toString("latin1", 8, 12) === "WEBP") return "image/webp";
  return "application/octet-stream";
}

export interface BflImageOptions {
  /** The API key, read only when this provider is called (adapters/secretManager.ts). */
  apiKey: () => Promise<string>;
  /** BFL_REGION: eu (the default), us or global; anything else is a config failure. */
  region: string;
  fetch?: typeof fetch;
  /** The first pause before polling, then each pause grows by half up to pollMaxMs. */
  pollMs?: number;
  pollMaxMs?: number;
  /** Tests: an instant sleep. It must reject when `signal` aborts. */
  sleep?: (ms: number, signal: AbortSignal) => Promise<void>;
}

const abortable = (ms: number, signal: AbortSignal) =>
  new Promise<void>((resolve, reject) => {
    if (signal.aborted) return reject(new Error("aborted"));
    const t = setTimeout(() => {
      signal.removeEventListener("abort", onAbort);
      resolve();
    }, ms);
    const onAbort = () => {
      clearTimeout(t);
      reject(new Error("aborted"));
    };
    signal.addEventListener("abort", onAbort, { once: true });
  });

interface SubmitBody {
  id?: unknown;
  polling_url?: unknown;
  cost?: unknown;
}
interface PollBody {
  status?: unknown;
  result?: { sample?: unknown } | null;
  cost?: unknown;
}

export class BflImageGenerator implements RecipeImageGenerator {
  constructor(private readonly options: BflImageOptions) {}

  async generate(prompt: ImagePrompt, target: ImageTarget, signal: AbortSignal): Promise<GeneratedImage> {
    if (!isBflModel(target.model)) throw new ImageGenerationError("config", "BFL model not supported", false);
    const hosts = Object.prototype.hasOwnProperty.call(BFL_HOSTS, this.options.region) ? BFL_HOSTS[this.options.region] : undefined;
    const base = hosts ? bflUrl(BFL_API_BASES[this.options.region], hosts.api) : null;
    if (!hosts || !base) throw new ImageGenerationError("config", "BFL endpoint not supported", false);
    let key: string;
    try {
      key = await this.options.apiKey();
    } catch {
      throw new ImageGenerationError("config", "BFL API key unavailable", false);
    }
    if (!key) throw new ImageGenerationError("config", "BFL API key unavailable", false);
    const doFetch = this.options.fetch ?? fetch;
    const sleep = this.options.sleep ?? abortable;
    const timeout = (submitted: boolean) => new ImageGenerationError("infra", "timeout", submitted);

    // 1. Submit.
    let res: Response;
    try {
      res = await doFetch(new URL(`/v1/${target.model}`, base).toString(), {
        method: "POST",
        headers: { "x-key": key, "content-type": "application/json", accept: "application/json" },
        body: JSON.stringify(bflBody(prompt, target.model)),
        redirect: "error",
        signal,
      });
    } catch {
      if (signal.aborted) throw timeout(true);
      throw new ImageGenerationError("infra", "BFL unreachable", true);
    }
    if (!res.ok) {
      await res.body?.cancel().catch(() => undefined);
      const kind = httpFailureKind(res.status);
      // Nothing was made: an empty balance (402) goes to the fallback or the default provider;
      // a refused key (401/403) to a fallback at another provider.
      if (kind === "out_of_credit") throw new ImageGenerationError("out_of_credit", `BFL ${res.status}`, false, false, res.status);
      if (kind === "config") throw new ImageGenerationError("config", `BFL ${res.status}`, false, false, res.status);
      // A rate limit (429: at most 24 tasks at once) or overload is asked again after at least the
      // pause BFL asked for; a refused request (400, 422) is not billed and not retried.
      const retryable = res.status === 429 || CAPACITY_5XX.has(res.status);
      const wait = retryable ? retryAfterMs(res.headers?.get?.("retry-after"), Date.now()) : undefined;
      throw new ImageGenerationError("infra", `BFL ${res.status}`, res.status >= 500, retryable, res.status, wait);
    }
    let submitted: SubmitBody;
    try {
      submitted = (await res.json()) as SubmitBody;
    } catch {
      if (signal.aborted) throw timeout(true);
      throw new ImageGenerationError("infra", "BFL submit unreadable", true);
    }
    const pollUrl = bflUrl(submitted?.polling_url, hosts.poll);
    if (!pollUrl) throw new ImageGenerationError("infra", "BFL polling host refused", true);
    let usage = creditsUsage(submitted.cost);

    // 2. Poll until the task settles, within the signal.
    let pause = this.options.pollMs ?? 500;
    const maxPause = this.options.pollMaxMs ?? 1500;
    let unreadable = 0;
    let sample: URL;
    for (;;) {
      try {
        await sleep(pause, signal);
      } catch {
        throw timeout(true);
      }
      pause = Math.min(maxPause, Math.round(pause * 1.5));
      let poll: Response;
      try {
        poll = await doFetch(pollUrl.toString(), { headers: { "x-key": key, accept: "application/json" }, redirect: "error", signal });
      } catch {
        if (signal.aborted) throw timeout(true);
        if (++unreadable >= 3) throw new ImageGenerationError("infra", "BFL poll unreachable", true);
        continue;
      }
      const body = (await poll.json().catch(() => null)) as PollBody | null;
      if (signal.aborted) throw timeout(true);
      const status = typeof body?.status === "string" ? body.status : null;
      if (status === null) {
        // A rate-limited or failing poll without a task status: the task itself may be fine.
        if (poll.status === 429) {
          pause = Math.max(pause, retryAfterMs(poll.headers?.get?.("retry-after"), Date.now(), 5_000) ?? 0);
          continue;
        }
        if (++unreadable >= 3) throw new ImageGenerationError("infra", `BFL poll ${poll.status}`, true);
        continue;
      }
      unreadable = 0;
      if (IN_PROGRESS.has(status)) continue;
      // Moderated: nothing delivered and nothing charged; never retried, never sent to a fallback.
      if (MODERATED.has(status)) throw new ImageGenerationError("safety", `BFL ${status === "Request Moderated" ? "request" : "content"} moderated`, false);
      if (status === "Ready") {
        usage = creditsUsage(body?.cost) ?? usage;
        const url = bflUrl(body?.result?.sample, hosts.delivery);
        if (!url) throw new ImageGenerationError("infra", "BFL delivery host refused", true);
        sample = url;
        break;
      }
      // Error, Task not found, No Card Generated, or a status this adapter doesn't know: a failed
      // task, which BFL does not charge for; the core may ask the fallback.
      throw new ImageGenerationError("infra", status === "Error" ? "BFL task failed" : "BFL task not settled", false);
    }

    // 3. Download, without the key, from the delivery host only, at most IMAGE.maxInputBytes.
    const bytes = await this.download(doFetch, sample, signal);
    // Klein answers the JPEG asked for; FLUX 3 Image has no format setting and answered PNG live.
    // The core sniffs and re-encodes either way; this is what the bytes say, for the logs.
    return { bytes, mimeType: sniffMime(bytes), model: target.model, ...(usage ? { usage } : {}) };
  }

  private async download(doFetch: typeof fetch, url: URL, signal: AbortSignal): Promise<Buffer> {
    let res: Response;
    try {
      res = await doFetch(url.toString(), { redirect: "error", signal });
    } catch {
      if (signal.aborted) throw new ImageGenerationError("infra", "timeout", true);
      throw new ImageGenerationError("infra", "BFL delivery unreachable", true);
    }
    if (!res.ok || !res.body) {
      await res.body?.cancel().catch(() => undefined);
      throw new ImageGenerationError("infra", `BFL delivery ${res.status}`, true);
    }
    const declared = Number(res.headers?.get?.("content-length") ?? NaN);
    if (Number.isFinite(declared) && declared > IMAGE.maxInputBytes) {
      await res.body.cancel().catch(() => undefined);
      throw new ImageGenerationError("infra", "BFL picture too large", true);
    }
    const chunks: Buffer[] = [];
    let total = 0;
    const reader = res.body.getReader();
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        total += value.byteLength;
        if (total > IMAGE.maxInputBytes) {
          await reader.cancel().catch(() => undefined);
          throw new ImageGenerationError("infra", "BFL picture too large", true);
        }
        chunks.push(Buffer.from(value));
      }
    } catch (e) {
      if (e instanceof ImageGenerationError) throw e;
      if (signal.aborted) throw new ImageGenerationError("infra", "timeout", true);
      throw new ImageGenerationError("infra", "BFL delivery interrupted", true);
    }
    return Buffer.concat(chunks);
  }
}

/**
 * Black Forest Labs' FLUX models (provider id "bfl"), on the EU endpoint by default. Prices, USD
 * per picture at the 1K tier, the same on every BFL endpoint (BFL lists one price for the API and
 * the Playground and none by region; quick_start/pricing.md and the release notes, read
 * 2026-11-17), and checked by the `cost` BFL answers per task, which is what is booked:
 * - flux-3-image-klein: USD 0.009 (768sq 0.008, 1.5k 0.011, 2k 0.014); 0.01 is reserved.
 * - flux-3-image: USD 0.048 (768sq 0.041, 2k 0.10); 0.05 is reserved.
 * BFL's `cost` (credits) is reported to the core as "image tokens" of one micro-USD each (the
 * token rate 1 USD per 1M below), so a picture is booked at exactly what BFL says it charged.
 *
 * Privacy (owner's decision 2026-11-18, README "What a new provider means for privacy"): no user
 * data goes to the picture model; it receives only the fixed template around the brief of a
 * generated recipe, which is not user data.
 *
 * Times (`timing`, owner's request 2026-11-18): FLUX 3 Image Klein took 21.2-33.2 s end to end for
 * 8 of 9 pictures in the live check of 2026-11-17 (one 75.9 s), so the defaults' 30 s first
 * attempt would abandon about half of them, already charged, to the fallback. While BFL is the
 * selected model each attempt gets up to 45 s, and the dish's picture 65 s: 45 s for FLUX, then the
 * fallback's 20 s (IMAGE.minAttemptMs; Flash-Lite Image takes about 3 s). The reservation for
 * "dish" is 60 + 68 = 128 s, covering text 40 s + judge 12 s + 65 s = 117 s with 11 s to spare
 * and under the apps' 130 s wait; "steps" keeps 100 s / 105 s (40 + 12 + 100 = 152 s inside 165 s,
 * the 180 s function timeout and the apps' 190 s).
 */
export const bflImage: ImageProviderDescriptor = {
  kind: "image",
  id: "bfl",
  label: "Black Forest Labs FLUX (EU endpoint)",
  processing: "Black Forest Labs (BFL GmbH, Freiburg, Germany), on its EU endpoint api.eu.bfl.ai (EU clusters only; BFL_REGION)",
  privacy: { approved: true, missing: "" },
  secret: {
    id: "bfl",
    param: "BFL_API_KEY_SECRET",
    name: "BFL_API_KEY",
    missing: "The Black Forest Labs API key is not in Secret Manager, or the service account can't read it",
  },
  usdPerImage: { "flux-3-image-klein": 0.01, "flux-3-image": 0.05 },
  tokenPrices: {
    "flux-3-image-klein": { input: 0, outputText: 0, outputImage: 1 },
    "flux-3-image": { input: 0, outputText: 0, outputImage: 1 },
  },
  timing: { abortMs: 45_000, budgetMs: { dish: 65_000, steps: 100_000 }, reservationExtraMs: { dish: 68_000, steps: 105_000 } },
  params: [
    {
      name: "BFL_REGION",
      default: "eu",
      description: "Black Forest Labs endpoint of the recipe pictures: eu (api.eu.bfl.ai, EU clusters only), us or global.",
    },
  ],
  create: (ctx) =>
    new BflImageGenerator({
      apiKey: () => ctx.secret(),
      region: ctx.param("BFL_REGION"),
      ...(ctx.fetch ? { fetch: ctx.fetch } : {}),
    }),
  /** `GET <base>/v1/credits`: the organisation's balance in credits (1 credit = USD 0.01). */
  async balance(ctx, signal) {
    const region = ctx.param("BFL_REGION");
    const base = Object.prototype.hasOwnProperty.call(BFL_API_BASES, region) ? BFL_API_BASES[region] : undefined;
    if (!base) throw new Error("BFL endpoint not supported");
    const res = await (ctx.fetch ?? fetch)(`${base}/v1/credits`, {
      headers: { "x-key": await ctx.secret(), accept: "application/json" },
      redirect: "error",
      signal,
    });
    if (!res.ok) throw new Error("BFL balance unavailable");
    const body = (await res.json()) as { credits?: unknown };
    if (typeof body?.credits !== "number" || !Number.isFinite(body.credits)) throw new Error("BFL balance unreadable");
    return { amount: body.credits, unit: "credits" };
  },
};
