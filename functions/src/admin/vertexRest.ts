/**
 * The Vertex AI REST form of the generator's call, for the offline tools: `restBodyOf` turns the
 * SDK parameters the adapter builds (adapters/gemini.ts generationParamsFor) into the JSON body of
 * `generateContent` / `streamGenerateContent`, and `RestStreamingModels` sends it with a bearer
 * token the caller supplies, so admin/redteam can run the real adapter (finish reasons, the token
 * ceiling, usage) against the live model without Application Default Credentials or a key file.
 *
 * The token is only ever put in the Authorization header: never logged, stored or put in an error.
 */
import { ApiError, type GenerateContentConfig, type GenerateContentParameters, type GenerateContentResponse } from "@google/genai";
import type { StreamingModels } from "../adapters/gemini";

/** The REST body for SDK parameters: the same fields, in the order prompt-body has always printed them. */
export function restBodyOf(params: GenerateContentParameters): Record<string, unknown> {
  const c: GenerateContentConfig = params.config ?? {};
  const system = c.systemInstruction;
  return {
    contents: params.contents,
    ...(typeof system === "string" ? { systemInstruction: { parts: [{ text: system }] } } : system ? { systemInstruction: system } : {}),
    generationConfig: {
      ...(c.responseMimeType !== undefined ? { responseMimeType: c.responseMimeType } : {}),
      ...(c.responseJsonSchema !== undefined ? { responseJsonSchema: c.responseJsonSchema } : {}),
      ...(c.maxOutputTokens !== undefined ? { maxOutputTokens: c.maxOutputTokens } : {}),
      ...(c.temperature !== undefined ? { temperature: c.temperature } : {}),
      ...(c.candidateCount !== undefined ? { candidateCount: c.candidateCount } : {}),
      ...(c.thinkingConfig !== undefined ? { thinkingConfig: c.thinkingConfig } : {}),
    },
    ...(c.safetySettings !== undefined ? { safetySettings: c.safetySettings } : {}),
  };
}

/** The streaming endpoint of a publisher model (location `global` has no regional host). */
export function streamUrl(project: string, location: string, model: string): string {
  const host = location === "global" ? "aiplatform.googleapis.com" : `${location}-aiplatform.googleapis.com`;
  return `https://${host}/v1/projects/${encodeURIComponent(project)}/locations/${encodeURIComponent(location)}` +
    `/publishers/google/models/${encodeURIComponent(model)}:streamGenerateContent?alt=sse`;
}

export interface RestOptions {
  project: string;
  location: string;
  /** Read per call; never stored on the instance. */
  token: () => string;
  fetch?: typeof fetch;
  /** Retries on 429 / 503 before the stream starts (nothing generated, nothing billed). */
  retries?: number;
  /** Milliseconds before the first retry; doubled each time. */
  backoffMs?: number;
  sleep?: (ms: number) => Promise<void>;
}

const RETRY_STATUS = new Set([429, 503]);

/** Parses a server-sent-events body into its `data:` JSON payloads. */
export async function* sseEvents(body: ReadableStream<Uint8Array>): AsyncGenerator<GenerateContentResponse> {
  const decoder = new TextDecoder();
  let buffer = "";
  const flush = function* (block: string) {
    const data = block.split(/\r?\n/).filter((l) => l.startsWith("data:")).map((l) => l.slice(5).trim()).join("\n");
    if (data) yield JSON.parse(data) as GenerateContentResponse;
  };
  for await (const bytes of body as unknown as AsyncIterable<Uint8Array>) {
    buffer += decoder.decode(bytes, { stream: true });
    let end: number;
    while ((end = buffer.search(/\r?\n\r?\n/)) >= 0) {
      const block = buffer.slice(0, end);
      buffer = buffer.slice(end).replace(/^\r?\n\r?\n/, "");
      yield* flush(block);
    }
  }
  buffer += decoder.decode();
  if (buffer.trim()) yield* flush(buffer);
}

/**
 * StreamingModels over REST. Every HTTP status, retry and error message (Google's, truncated, never
 * the request or the token) is kept in `log`, so a report can say why a call failed.
 */
export class RestStreamingModels implements StreamingModels {
  readonly log: string[] = [];
  /** HTTP requests sent, retries included. */
  requests = 0;

  constructor(private readonly options: RestOptions) {}

  async generateContentStream(params: GenerateContentParameters): Promise<AsyncGenerator<GenerateContentResponse>> {
    const f = this.options.fetch ?? fetch;
    const signal = params.config?.abortSignal;
    const sleep = this.options.sleep ?? ((ms: number) => abortableSleep(ms, signal));
    // 1 + 2 + 4 s: under the judge's shortest abort time (JUDGE.abortMs.recipe, 10 s), so retries end before the deadline.
    const retries = this.options.retries ?? 3;
    let wait = this.options.backoffMs ?? 1000;
    const body = JSON.stringify(restBodyOf(params));
    for (let attempt = 0; ; attempt++) {
      this.requests++;
      const res = await f(streamUrl(this.options.project, this.options.location, params.model), {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${this.options.token()}` },
        body,
        signal: params.config?.abortSignal,
      });
      if (res.ok && res.body) {
        this.log.push(`HTTP ${res.status}`);
        return sseEvents(res.body);
      }
      const message = await errorMessage(res);
      this.log.push(`HTTP ${res.status}: ${message}`);
      if (RETRY_STATUS.has(res.status) && attempt < retries && !signal?.aborted) {
        await sleep(wait);
        if (signal?.aborted) throw new ApiError({ message: `HTTP ${res.status}: aborted while backing off`, status: res.status });
        wait *= 2;
        continue;
      }
      throw new ApiError({ message: `HTTP ${res.status}: ${message}`, status: res.status });
    }
  }
}

/** Resolves after `ms`, or at once when `signal` aborts (the caller then stops retrying). */
function abortableSleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal?.aborted) return resolve();
    const timer = setTimeout(done, ms);
    function done() {
      clearTimeout(timer);
      signal?.removeEventListener("abort", done);
      resolve();
    }
    signal?.addEventListener("abort", done, { once: true });
  });
}

async function errorMessage(res: Response): Promise<string> {
  try {
    const text = await res.text();
    try {
      const m = (JSON.parse(text) as { error?: { message?: unknown } }).error?.message;
      if (typeof m === "string") return m.slice(0, 300);
    } catch {
      // Not JSON: fall through to the raw text.
    }
    return text.slice(0, 300);
  } catch {
    return res.statusText;
  }
}
