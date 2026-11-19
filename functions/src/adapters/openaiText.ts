/**
 * OpenAI as a recipe text provider (provider id "openai"): one adapter file plus one entry in
 * providers/registry.ts (README "Adding a provider", "OpenAI for the recipe text").
 *
 * The call is the Responses API (`POST https://api.openai.com/v1/responses`; OpenAI API reference
 * "Create a model response" and the guides "Structured model outputs", "Reasoning models", "Error
 * codes" and "Your data", read 2026-11-19), not streamed:
 *
 * - `instructions`: the server's system instruction; `input`: the user turn, one user message.
 * - `text.format`: `{type: "json_schema", name, strict: true, schema}`. Strict mode accepts a
 *   subset of JSON Schema and needs `additionalProperties: false` on every object and every
 *   property listed in `required` (an optional one is a union with null); `strictSchema` converts
 *   the prompt's plain schema to that and `withoutNulls` turns the nulls back into absent fields, so
 *   the core sees exactly what it would from Gemini. Everything else the prompt's schema uses
 *   (`enum`, `minItems` / `maxItems`, `minimum` / `maximum`, `description`) is supported
 *   there; string lengths are not enforced by either provider (core/outputSafety.ts is).
 * - `max_output_tokens`: `limits.hardTokenCeiling`. It bounds reasoning and visible output
 *   together, server-side, so the ceiling is enforced by OpenAI itself and no stream needs watching;
 *   an answer stopped there is `status: "incomplete"`, `incomplete_details.reason:
 *   "max_output_tokens"`, a content failure (like Gemini's MAX_TOKENS).
 * - `reasoning: {effort}` (OPENAI_REASONING_EFFORT, default `low`; GPT-6.1 Terra and GPT-6 Luna
 *   take none, low, medium, high, xhigh and max, default medium). No `temperature`: reasoning
 *   models are tuned at its default.
 * - `store: false` (no 30-day application state), no `safety_identifier`, no `user`, no
 *   `prompt_cache_key`, no tools, no metadata: OpenAI gets no identifier of the person or device.
 *   What it does receive is the whole recipe prompt, which carries the ticked pantry items, the
 *   allergens (health data, sent only with consent), the diets and the taste answers: OpenAI is a
 *   processor of user data here (README "What a new provider means for privacy"), unlike for
 *   pictures.
 *
 * Usage: `usage.input_tokens`, `usage.output_tokens` (reasoning included) and
 * `usage.output_tokens_details.reasoning_tokens`, booked as input, visible output and thinking.
 * Cached input (`input_tokens_details.cached_tokens`, billed lower) is booked at the full input
 * rate: never less than the bill.
 *
 * Failures (providers/failure.ts): 401/403 `config`, 404 (a model it doesn't serve) `config`,
 * 402 or a 429 whose code says the credit, a spending limit or the usage limit is exhausted
 * `out_of_credit`, any other 429 and 500/502/503/504 `capacity`, each refused before anything
 * was generated (known usage of nothing, so the core may ask the fallback). A prompt OpenAI's own
 * policy refuses (`invalid_prompt` and the other policy codes), a `refusal` in the answer or an
 * answer stopped by its content filter is a content failure counted as a prompt rejection, never
 * sent to the fallback. Error messages are fixed texts with at most an HTTP status or a fixed code:
 * never OpenAI's own text, which could quote the prompt.
 */
import type { GenerateRequest } from "../contract";
import { GenerationError, type GenerationLimits, type GenerationResult, type LlmUsage, type TextTarget } from "../ports";
import { httpFailureKind } from "../providers/failure";
import type { ProviderSecret, TextProvider, TextProviderDescriptor } from "../providers/types";
import type { Prompt } from "./prompt";
import { openaiModelInfo } from "./modelProbes";

export const OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses";

/**
 * OpenAI's one API key, shared by the recipe text (here) and the pictures (adapters/image.ts
 * openaiImage): one secret in Secret Manager, one grant.
 */
export const OPENAI_SECRET: ProviderSecret = {
  id: "openai",
  param: "OPENAI_API_KEY_SECRET",
  name: "OPENAI_API_KEY",
  missing: "The OpenAI API key is not in Secret Manager, or the service account can't read it",
};

/**
 * The `error.code` values of a 429 that mean money, not speed (OpenAI "Error codes", read
 * 2026-11-19: `credit_balance_exhausted`, `organization_spend_limit_exceeded`,
 * `project_spend_limit_exceeded`, `organization_usage_limit_exceeded`; `insufficient_quota`,
 * the older code, may still come as the code or the type). Retrying won't clear them.
 */
export const OPENAI_EXHAUSTED = /insufficient_quota|credit_balance_exhausted|spend_limit_exceeded|usage_limit_exceeded/;

/** `error.code` values meaning OpenAI's usage policies refused the prompt (a 400, or a failed response). */
const POLICY = /invalid_prompt|bio_policy|misalignment_policy|content_policy|moderation|safety/;

/** The reasoning efforts the API takes (`reasoning.effort`); not every model takes every one. */
export const OPENAI_REASONING_EFFORTS = ["none", "minimal", "low", "medium", "high", "xhigh", "max"] as const;

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);

/**
 * The prompt's JSON schema in strict mode's dialect: every object closed
 * (`additionalProperties: false`) with every property required, a property the schema left
 * optional made a union with null (its enum, if any, gains null). The input is not changed.
 */
export function strictSchema(schema: Record<string, unknown>): Record<string, unknown> {
  const walk = (s: unknown): unknown => {
    if (Array.isArray(s)) return s.map(walk);
    if (!isObj(s)) return s;
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(s)) out[k] = k === "properties" || k === "enum" ? v : walk(v);
    if (s.type === "object" && isObj(s.properties)) {
      const required = new Set(Array.isArray(s.required) ? (s.required as string[]) : []);
      const props: Record<string, unknown> = {};
      for (const [name, p] of Object.entries(s.properties)) {
        const strict = walk(p) as Record<string, unknown>;
        props[name] = required.has(name) ? strict : nullable(strict);
      }
      out.properties = props;
      out.required = Object.keys(s.properties);
      out.additionalProperties = false;
    }
    return out;
  };
  return walk(schema) as Record<string, unknown>;
}

function nullable(s: Record<string, unknown>): Record<string, unknown> {
  const type = s.type;
  const types = Array.isArray(type) ? type : type === undefined ? [] : [type];
  const out: Record<string, unknown> = { ...s, type: types.includes("null") ? types : [...types, "null"] };
  if (Array.isArray(s.enum) && !s.enum.includes(null)) out.enum = [...s.enum, null];
  return out;
}

/**
 * The answer as the plain schema means it: a null in a property `schema` left optional is
 * removed (strict mode writes null for "absent"); anything else is kept as it came, for the core's
 * own checks.
 */
export function withoutNulls(value: unknown, schema: unknown): unknown {
  if (Array.isArray(value)) {
    const items = isObj(schema) ? schema.items : undefined;
    return value.map((v) => withoutNulls(v, items));
  }
  if (!isObj(value) || !isObj(schema) || !isObj(schema.properties)) return value;
  const required = new Set(Array.isArray(schema.required) ? (schema.required as string[]) : []);
  const props = schema.properties;
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(value)) {
    if (v === null && !required.has(k) && Object.prototype.hasOwnProperty.call(props, k)) continue;
    out[k] = withoutNulls(v, props[k]);
  }
  return out;
}

/** The schema's name in the request: what the answer is (letters only, as the API asks). */
const schemaName = (req: GenerateRequest) => (req.kind === "menu" ? "menu" : "recipe");

/** The whole request body of one recipe or menu call. */
export function openAIResponsesBody(req: GenerateRequest, prompt: Prompt, limits: GenerationLimits, model: string, effort: string): Record<string, unknown> {
  return {
    model,
    instructions: prompt.systemInstruction,
    input: [{ role: "user", content: prompt.userText }],
    text: { format: { type: "json_schema", name: schemaName(req), strict: true, schema: strictSchema(prompt.schema) } },
    max_output_tokens: limits.hardTokenCeiling,
    reasoning: { effort },
    store: false,
  };
}

/** The parts of a Response the adapter reads (the rest is ignored). */
interface ResponsesBody {
  model?: unknown;
  status?: unknown;
  incomplete_details?: { reason?: unknown } | null;
  error?: { code?: unknown; type?: unknown } | null;
  output?: unknown;
  usage?: {
    input_tokens?: unknown;
    output_tokens?: unknown;
    output_tokens_details?: { reasoning_tokens?: unknown } | null;
  } | null;
}

const count = (v: unknown): number => (typeof v === "number" && Number.isFinite(v) && v >= 0 ? Math.floor(v) : 0);
const none = (model: string): LlmUsage => ({ inputTokens: 0, outputTokens: 0, thinkingTokens: 0, model, finishReason: null });

/** The usage of an answer; undefined when it reported none (the reserved worst case is booked then). */
export function openAIUsage(body: ResponsesBody, model: string, finish: string | null): LlmUsage | undefined {
  const u = body.usage;
  if (!isObj(u)) return undefined;
  const output = count(u.output_tokens);
  const reasoning = Math.min(output, count(isObj(u.output_tokens_details) ? u.output_tokens_details.reasoning_tokens : 0));
  return { inputTokens: count(u.input_tokens), outputTokens: output - reasoning, thinkingTokens: reasoning, model, finishReason: finish };
}

/** The visible text of an answer (every output_text of every message), and whether it held a refusal. */
function answerText(output: unknown): { text: string; refused: boolean } {
  let text = "";
  let refused = false;
  for (const item of Array.isArray(output) ? output : []) {
    if (!isObj(item) || item.type !== "message" || !Array.isArray(item.content)) continue;
    for (const part of item.content) {
      if (!isObj(part)) continue;
      if (part.type === "output_text" && typeof part.text === "string") text += part.text;
      if (part.type === "refusal") refused = true;
    }
  }
  return { text, refused };
}

/** Text longer than this many characters per ceiling token is over the ceiling whatever the tokenisation. */
const MAX_CHARS_PER_TOKEN = 8;

export interface OpenAITextOptions {
  /** The API key, read only when this provider is called (adapters/secretManager.ts). */
  apiKey: () => Promise<string>;
  /** `reasoning.effort`; anything the API doesn't take is `low`. */
  reasoningEffort?: string;
  fetch?: typeof fetch;
}

export class OpenAITextGenerator implements TextProvider {
  private readonly effort: string;

  constructor(private readonly options: OpenAITextOptions) {
    const e = options.reasoningEffort ?? "low";
    this.effort = (OPENAI_REASONING_EFFORTS as readonly string[]).includes(e) ? e : "low";
  }

  async generate(request: GenerateRequest, prompt: Prompt, signal: AbortSignal, limits: GenerationLimits, target: TextTarget): Promise<GenerationResult> {
    let key: string;
    try {
      key = await this.options.apiKey();
    } catch {
      key = "";
    }
    // Nothing was sent: a fallback at another provider may be asked.
    if (!key) throw new GenerationError("infra", "OpenAI API key unavailable", none(target.model), undefined, "config");
    const doFetch = this.options.fetch ?? fetch;
    let res: Response;
    try {
      res = await doFetch(OPENAI_RESPONSES_URL, {
        method: "POST",
        headers: { authorization: `Bearer ${key}`, "content-type": "application/json", accept: "application/json" },
        body: JSON.stringify(openAIResponsesBody(request, prompt, limits, target.model, this.effort)),
        redirect: "error",
        signal,
      });
    } catch {
      // The request may have reached OpenAI (and been billed) before the connection broke: unknown usage.
      throw new GenerationError("infra", signal.aborted ? "timeout" : "OpenAI unreachable");
    }
    let body: ResponsesBody | null;
    try {
      body = (await res.json()) as ResponsesBody;
    } catch {
      body = null;
    }
    if (signal.aborted) throw new GenerationError("infra", "timeout");
    if (!res.ok) {
      // Refused before anything was generated: known usage of nothing (the fallback may be asked).
      const code = `${String(body?.error?.code ?? "")} ${String(body?.error?.type ?? "")}`;
      if (res.status === 400 && POLICY.test(code)) {
        throw new GenerationError("content", "prompt blocked", none(target.model), "PROMPT_BLOCKED");
      }
      const kind = httpFailureKind(res.status, { exhausted: OPENAI_EXHAUSTED.test(code), modelNotFound: true });
      throw new GenerationError("infra", `OpenAI ${res.status}`, none(target.model), undefined, kind);
    }
    if (!isObj(body)) throw new GenerationError("infra", "OpenAI answer unreadable");
    const answer: ResponsesBody = body;
    const model = typeof answer.model === "string" && answer.model ? answer.model.slice(0, 80) : target.model;
    const status = typeof answer.status === "string" ? answer.status : "";
    const details = answer.incomplete_details;
    const reason = isObj(details) && typeof details.reason === "string" ? details.reason : "";
    const finish = status === "incomplete" && reason ? `incomplete:${reason.slice(0, 40)}` : status.slice(0, 40) || null;
    const usage = openAIUsage(answer, model, finish);
    if (status === "failed") {
      const code = isObj(answer.error) ? String(answer.error.code ?? "") : "";
      if (POLICY.test(code)) throw new GenerationError("content", "prompt blocked", usage, "PROMPT_BLOCKED");
      throw new GenerationError("infra", code === "rate_limit_exceeded" ? "OpenAI rate limited" : "OpenAI failed", usage);
    }
    const { text, refused } = answerText(answer.output);
    if (refused) throw new GenerationError("content", "refusal", usage, "FINISH_REFUSAL");
    if (status === "incomplete") {
      throw new GenerationError("content", `finish ${finish}`, usage, reason === "content_filter" ? "FINISH_CONTENT_FILTER" : undefined);
    }
    if (status !== "completed") throw new GenerationError("infra", "OpenAI answer not completed", usage);
    if (text.length > limits.hardTokenCeiling * MAX_CHARS_PER_TOKEN) throw new GenerationError("infra", "token ceiling exceeded");
    if (!text) throw new GenerationError("content", "empty response", usage);
    let parsed: unknown;
    try {
      parsed = JSON.parse(text);
    } catch {
      throw new GenerationError("content", "response is not JSON", usage);
    }
    return { output: withoutNulls(parsed, prompt.schema), ...(usage ? { usage } : {}) };
  }
}

/**
 * OpenAI's GPT models for the recipe text (provider id "openai"). Prices, standard tier, USD per
 * 1M tokens, reasoning billed as output (OpenAI's pricing page and the models' pages, read
 * 2026-11-19; prompts under 272K input tokens, which a recipe prompt always is):
 * - gpt-6.1-terra: 1.00 input, 5.00 output (cached input 0.05). "A fast, efficient model";
 *   the default here: cheaper than Gemini 3.8 Flash's 1.50 / 7.50.
 * - gpt-6-luna: 0.10 input, 0.50 output (cached input 0.01). "Our most efficient model for
 *   focused, high-volume tasks"; to be compared on real recipes before it is chosen.
 * Neither is on the deprecations page (read 2026-11-19). Not tried live, and not yet switchable:
 * OpenAI is not in the privacy policy's processor list, and the recipe text carries user data.
 */
export const openaiText: TextProviderDescriptor = {
  kind: "text",
  id: "openai",
  label: "OpenAI GPT (Responses API)",
  processing: "OpenAI, United States (no EU-only processing by default; store: false)",
  // Owner's decision needed before this flips: README "What a new provider means for privacy".
  privacy: {
    approved: false,
    missing: "OpenAI is not yet named in the privacy policy's processor list for the recipe text, which carries the pantry, allergens and taste answers, nor in the apps' allergen consent, which names Google only (a code change turns this on)",
  },
  secret: OPENAI_SECRET,
  defaultModel: "gpt-6.1-terra",
  prices: {
    "gpt-6.1-terra": { inputUsdPerMTok: 1, outputUsdPerMTok: 5 },
    "gpt-6-luna": { inputUsdPerMTok: 0.1, outputUsdPerMTok: 0.5 },
  },
  params: [
    {
      name: "OPENAI_REASONING_EFFORT",
      default: "low",
      description: "reasoning.effort of the OpenAI recipe text models: none, low, medium, high, xhigh or max (anything else is low).",
    },
  ],
  create: (ctx) => new OpenAITextGenerator({
    apiKey: () => ctx.secret(),
    reasoningEffort: ctx.param("OPENAI_REASONING_EFFORT"),
    ...(ctx.fetch ? { fetch: ctx.fetch } : {}),
  }),
  modelInfo: async (ctx, model, signal) => openaiModelInfo({ model, apiKey: await ctx.secret(), signal, ...(ctx.fetch ? { fetch: ctx.fetch } : {}) }),
};
