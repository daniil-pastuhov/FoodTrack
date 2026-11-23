/**
 * The home worker (README "Home worker"): the recipe text of the owner's own test accounts, written
 * by a small HTTP server on the owner's computer (home-worker/server.mjs: the Claude Code CLI under
 * the owner's own subscription) instead of a provider. For the owner's accounts only: the server
 * is no provider of the registry, so no switch can ever select it, and only a uid in
 * `config/runtime.homeWorker.testers` or a verified email in `emails` reaches it (homeWorkerMatch).
 * Everything else about such a request is unchanged: reservation, output checks, quality gate,
 * judge, settle and refunds. For a recipe with pictures it writes the text with the picture brief
 * and step marks (the same schema a text provider gets); the pictures come from the picture router.
 *
 * The call: POST <config/runtime.homeWorker.url>/v1/text (https only, redirects refused) with two
 * proofs, both checked by the server: `Authorization: Bearer <a Google-signed ID token>` of the
 * functions' runtime service account with the audience set to that url exactly (the metadata
 * server's identity endpoint, through google-auth-library), and `X-Worker-Secret:
 * <HOME_WORKER_SECRET>`; and the prompt exactly as a text provider gets it
 * (system instruction, user turn, schema, output limit, kind, the time the server may take), and
 * nothing else: no uid, no account data. It waits at most `waitMs`; a 200 with `{text, model}`
 * whose text parses as a JSON object is the answer. Anything else (unreachable, busy 429, an error
 * status, a redirect, a timeout, an oversized or unreadable body, text that is not JSON): null, and
 * the normal text provider writes the recipe. Nothing runs anywhere while no tester asks.
 */
import { HOME_WORKER } from "../constants";
import type { GenerateRequest } from "../contract";
import type { Prompt } from "../adapters/prompt";
import { uidHash, type RuntimeConfig } from "../model";
import type { GenerationLimits, HomeAnswer, HomeWorkerQueue, HomeWorkerTarget, Logger } from "../ports";

/** The provider id the logs name when the home worker wrote the text (never a registry id). */
export const HOME_PROVIDER = "home";

/** Who is asking: the uid, and the email the ID token says is verified (Caller.verifiedEmail). */
export interface HomeWho {
  uid: string;
  verifiedEmail?: string | null;
}

/**
 * The owner's home address, shortened wherever it is shown (the admin command, the owner
 * console): the scheme and the host's first three and last six characters, never in full.
 */
export function maskedUrl(url: string): string {
  if (!url) return "none";
  const u = new URL(url);
  const host = u.hostname;
  return `https://${host.length > 8 ? `${host.slice(0, 3)}…${host.slice(-6)}` : "…"}${u.pathname === "/" ? "" : "/…"}`;
}

/** How a caller was found on the list (logged; never the email itself). */
export type HomeMatch = "uid" | "email";

/**
 * Whether the caller is one of the owner's test accounts: the feature enabled with a url, and the
 * uid in `testers` or the verified email in `emails` (exact, lower-cased; an unverified email is
 * never matched: Caller.verifiedEmail is null then). null otherwise.
 */
export function homeWorkerMatch(config: Pick<RuntimeConfig, "homeWorker">, who: HomeWho): HomeMatch | null {
  const h = config.homeWorker;
  if (!h.enabled || !h.url) return null;
  if (h.testers.includes(who.uid)) return "uid";
  const email = typeof who.verifiedEmail === "string" ? who.verifiedEmail.trim().toLowerCase() : "";
  return email && h.emails.includes(email) ? "email" : null;
}

/** Where, how long and why a request is sent to the home server. */
export interface HomePlan extends HomeWorkerTarget {
  match: HomeMatch;
}

/**
 * The home server for this request, or null: a listed caller (homeWorkerMatch), waiting
 * `waitMs.recipeImage` for a recipe with pictures (whose text, picture brief and step marks the
 * server writes; the pictures still come from the picture router) and `waitMs.recipe` /
 * `waitMs.menu` otherwise.
 */
export function homePlanOf(config: Pick<RuntimeConfig, "homeWorker">, who: HomeWho, req: Pick<GenerateRequest, "kind" | "image">): HomePlan | null {
  const match = homeWorkerMatch(config, who);
  if (!match) return null;
  const h = config.homeWorker;
  return { url: h.url, waitMs: req.image ? h.waitMs.recipeImage : h.waitMs[req.kind], match };
}

/** How long a request waits for the home server: 0 when it is never asked. */
export function homeWorkerWaitMs(config: Pick<RuntimeConfig, "homeWorker">, who: HomeWho | string, req: Pick<GenerateRequest, "kind" | "image">): number {
  return homePlanOf(config, typeof who === "string" ? { uid: who } : who, req)?.waitMs ?? 0;
}

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);

/** The server's text as the JSON object the output checks expect (a code fence around it is allowed); undefined otherwise. */
export function parseHomeAnswer(text: unknown): unknown {
  if (typeof text !== "string") return undefined;
  const t = text.trim();
  const fenced = /^```(?:json)?\s*([\s\S]*?)\s*```$/.exec(t);
  try {
    const v: unknown = JSON.parse(fenced ? fenced[1] : t);
    return isObj(v) ? v : undefined;
  } catch {
    return undefined;
  }
}

/** What the home server is sent (home-worker/server.mjs reads exactly this). */
export interface HomeTextRequest {
  kind: "recipe" | "menu";
  systemInstruction: string;
  userText: string;
  schema: Record<string, unknown>;
  maxOutputTokens: number;
  /** How long it may take; the call stops waiting HOME_WORKER.serverMarginMs later. */
  timeoutMs: number;
}

const MODEL = /^[A-Za-z0-9][A-Za-z0-9._:[\]-]{0,79}$/;

/** A body read up to `max` bytes; null when it is longer (the rest is not read). */
async function readCapped(res: Response, max: number): Promise<string | null> {
  if (!res.body) return "";
  const reader = res.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > max) {
      await reader.cancel().catch(() => undefined);
      return null;
    }
    chunks.push(value);
  }
  return Buffer.concat(chunks).toString("utf8");
}

export interface HttpHomeWorkerOptions {
  /** The shared secret (Secret Manager HOME_WORKER_SECRET); rejects when it cannot be read. */
  secret(): Promise<string>;
  /** A Google-signed ID token of the functions' service account for `audience`; rejects when none can be had. */
  idToken(audience: string): Promise<string>;
  logger: Logger;
  /** For tests; absent: the global fetch. */
  fetch?: typeof fetch;
}

/** Calls the home server (Deps.homeWorker). */
export class HttpHomeWorker implements HomeWorkerQueue {
  constructor(private readonly options: HttpHomeWorkerOptions) {}

  async ask(uid: string, req: GenerateRequest, prompt: Prompt, limits: GenerationLimits, signal: AbortSignal, target: HomeWorkerTarget): Promise<HomeAnswer | null> {
    const started = Date.now();
    let outcome = "error";
    let status: number | null = null;
    let answer: HomeAnswer | null = null;
    try {
      answer = await this.call(req, prompt, limits, signal, target, (o, s) => {
        outcome = o;
        status = s ?? null;
      });
    } catch (e) {
      // The url is the owner's home address: the error's name only, never its message.
      const name = (e as Error)?.name;
      outcome = name === "TimeoutError" || name === "AbortError" ? "timeout" : "unreachable";
    }
    this.options.logger.info("home_worker", {
      uidHash: uidHash(uid),
      kind: req.kind,
      // answered: the server's text goes on to the checks; anything else: the normal provider writes it.
      outcome,
      status,
      // How the caller was found on the list; never the email.
      match: target.match ?? null,
      durationMs: Date.now() - started,
      waitMs: target.waitMs,
    });
    return answer;
  }

  private async call(
    req: GenerateRequest,
    prompt: Prompt,
    limits: GenerationLimits,
    signal: AbortSignal,
    target: HomeWorkerTarget,
    set: (outcome: string, status?: number) => void,
  ): Promise<HomeAnswer | null> {
    let secret: string;
    try {
      secret = await this.options.secret();
    } catch {
      set("no_secret");
      return null;
    }
    if (secret.length < HOME_WORKER.minSecretChars) {
      set("no_secret");
      return null;
    }
    if (!target.url.startsWith("https://")) {
      set("bad_url");
      return null;
    }
    let idToken: string;
    try {
      idToken = await this.options.idToken(target.url);
    } catch {
      set("no_identity");
      return null;
    }
    const body: HomeTextRequest = {
      kind: req.kind,
      systemInstruction: prompt.systemInstruction,
      userText: prompt.userText,
      schema: prompt.schema,
      maxOutputTokens: limits.maxOutputTokens,
      timeoutMs: Math.max(1_000, target.waitMs - HOME_WORKER.serverMarginMs),
    };
    const res = await (this.options.fetch ?? fetch)(`${target.url}${HOME_WORKER.path}`, {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bearer ${idToken}`, "x-worker-secret": secret },
      body: JSON.stringify(body),
      // An owner-set address, still: never followed elsewhere.
      redirect: "error",
      signal: AbortSignal.any([signal, AbortSignal.timeout(target.waitMs)]),
    });
    if (res.status !== 200) {
      await res.body?.cancel().catch(() => undefined);
      set("status", res.status);
      return null;
    }
    const raw = await readCapped(res, HOME_WORKER.maxResponseBytes);
    if (raw === null) {
      set("oversized", 200);
      return null;
    }
    let parsed: unknown;
    try {
      parsed = JSON.parse(raw);
    } catch {
      set("unusable", 200);
      return null;
    }
    const text = isObj(parsed) ? parsed.text : undefined;
    if (typeof text !== "string" || text.length > HOME_WORKER.maxResultChars) {
      set(typeof text === "string" ? "oversized" : "unusable", 200);
      return null;
    }
    const output = parseHomeAnswer(text);
    if (output === undefined) {
      set("unusable", 200);
      return null;
    }
    const model = isObj(parsed) && typeof parsed.model === "string" && MODEL.test(parsed.model) ? parsed.model : "unknown";
    set("answered", 200);
    return { output, model };
  }
}
