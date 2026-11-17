/**
 * The shared failure taxonomy (providers/types.ts ProviderFailureKind) for HTTP providers: one
 * mapping of status codes, so every new adapter classifies its answers the same way. An adapter
 * passes what it knows about the body (a provider's own "insufficient balance" code) but never its
 * text into an error message: a provider's text could quote the prompt.
 */
import type { ProviderFailureKind } from "./types";

/** HTTP statuses worth one more attempt after a pause, or the fallback: overload, a passing server error. */
export const CAPACITY_5XX: ReadonlySet<number> = new Set([500, 502, 503, 504]);

/**
 * The kind of a non-2xx answer. 401/403: the key (config). 402: out of credit. 429: capacity,
 * unless the provider said the balance or spending limit is exhausted (`exhausted`). 500/502/503/
 * 504: capacity. 404: config when it is the model that is unknown (`modelNotFound`), else infra.
 * Anything else: infra.
 */
export function httpFailureKind(status: number, opts: { exhausted?: boolean; modelNotFound?: boolean } = {}): ProviderFailureKind {
  if (status === 401 || status === 403) return "config";
  if (status === 402) return "out_of_credit";
  if (status === 429) return opts.exhausted ? "out_of_credit" : "capacity";
  if (CAPACITY_5XX.has(status)) return "capacity";
  if (status === 404 && opts.modelNotFound) return "config";
  return "infra";
}

/**
 * A `Retry-After` header as milliseconds (seconds or an HTTP date), capped at `maxMs`; undefined
 * when absent or unreadable. The core's own backoff applies when undefined; an adapter may pass
 * this on so a capacity retry waits at least as long as the provider asked.
 */
export function retryAfterMs(header: string | null | undefined, nowMs: number, maxMs = 60_000): number | undefined {
  if (!header) return undefined;
  const s = header.trim();
  if (/^\d+$/.test(s)) return Math.min(maxMs, Number(s) * 1000);
  const at = Date.parse(s);
  if (Number.isNaN(at)) return undefined;
  return Math.max(0, Math.min(maxMs, at - nowMs));
}
