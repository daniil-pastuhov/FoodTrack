/**
 * The model lifecycle probes of the Vertex AI models (README "Model lifecycle"): `countTokens` on
 * the publisher model, in the project and location the adapter calls. Google documents it as free
 * ("There is no charge or quota restriction for using the CountTokens API", 3000 requests a
 * minute; docs/multimodal/get-token-count) and it needs `aiplatform.endpoints.predict`, which the
 * functions' Vertex AI User role already has. It generates nothing. A 404 ("Publisher Model ... was
 * not found or your project does not have access to it") means the id is not served there: retired,
 * wrong, or not offered in that location. Vertex reports no retirement date through any API (the
 * lifecycle page is the only source), so `retiresOn` is never set.
 *
 * The access token is only ever put in the Authorization header: never logged, stored or put in an
 * error.
 */
import { GoogleAuth } from "google-auth-library";
import type { ModelInfo } from "../providers/types";

let auth: GoogleAuth | undefined;
/** The runtime's Application Default Credentials (the functions' service account). */
async function adcToken(): Promise<string> {
  auth ??= new GoogleAuth({ scopes: ["https://www.googleapis.com/auth/cloud-platform"] });
  const t = await auth.getAccessToken();
  if (!t) throw new Error("no access token");
  return t;
}

export function countTokensUrl(project: string, location: string, model: string): string {
  const host = location === "global" ? "aiplatform.googleapis.com" : `${location}-aiplatform.googleapis.com`;
  return `https://${host}/v1/projects/${encodeURIComponent(project)}/locations/${encodeURIComponent(location)}` +
    `/publishers/google/models/${encodeURIComponent(model)}:countTokens`;
}

export interface VertexProbeOptions {
  project: string;
  location: string;
  model: string;
  signal: AbortSignal;
  /** Default: Application Default Credentials. */
  token?: () => Promise<string>;
  fetch?: typeof fetch;
  /** The admin command with a person's credentials needs a quota project (x-goog-user-project). */
  quotaProject?: string;
}

export async function vertexModelInfo(o: VertexProbeOptions): Promise<ModelInfo> {
  if (!o.project || !o.location) throw new Error("Vertex probe not configured");
  const res = await (o.fetch ?? fetch)(countTokensUrl(o.project, o.location, o.model), {
    method: "POST",
    headers: {
      authorization: `Bearer ${await (o.token ?? adcToken)()}`,
      "content-type": "application/json",
      ...(o.quotaProject ? { "x-goog-user-project": o.quotaProject } : {}),
    },
    body: JSON.stringify({ contents: [{ role: "user", parts: [{ text: "ok" }] }] }),
    redirect: "error",
    signal: o.signal,
  });
  if (res.status === 404) return { served: false };
  if (!res.ok) throw new Error("Vertex probe failed");
  return { served: true };
}

/** OpenAI's `GET /v1/models/{model}` (free metadata): 404 is gone; `shutdown_date` when announced. */
export async function openaiModelInfo(o: { model: string; apiKey: string; signal: AbortSignal; fetch?: typeof fetch }): Promise<ModelInfo> {
  const res = await (o.fetch ?? fetch)(`https://api.openai.com/v1/models/${encodeURIComponent(o.model)}`, {
    headers: { authorization: `Bearer ${o.apiKey}`, accept: "application/json" },
    redirect: "error",
    signal: o.signal,
  });
  if (res.status === 404) return { served: false };
  if (!res.ok) throw new Error("OpenAI probe failed");
  const body = (await res.json()) as { shutdown_date?: unknown };
  const d = body?.shutdown_date;
  // A date string, null when none is announced; anything else (absent, a number) reads as no field.
  if (d === null) return { served: true, retiresOn: null };
  if (typeof d === "string" && /^\d{4}-\d{2}-\d{2}/.test(d)) return { served: true, retiresOn: d.slice(0, 10) };
  return { served: true };
}
