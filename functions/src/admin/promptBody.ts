/**
 * Offline helper for replaying a request against the live model by hand (README "Replaying a
 * prompt"). Makes no network call and reads no credentials.
 *
 *   npm run prompt-body -- body  <request.json> [--model gemini-3.8-flash] [--thinking LOW]
 *   npm run prompt-body -- check <request.json> <answer.json>
 *
 * `body` prints the Vertex AI generateContent request body this server would send for the
 * request (system instruction, user turn, response schema, generation config, safety settings),
 * or the NOT_ENOUGH_ITEMS refusal. `check` runs the server's output checks on a model answer
 * (the JSON text of the candidate, or a whole generateContent response) and prints the verdict.
 * The request is a generateRecipe body; it is validated as if sent on its own `today`.
 *
 * The functions below are also the red-team runner's (admin/redteam), so both send the same body.
 */
import { readFileSync } from "node:fs";
import { parseArgs } from "node:util";
import { buildPrompt, newCanary, type Prompt } from "../adapters/prompt";
import { generationParamsFor } from "../adapters/gemini";
import { MAX_OUTPUT_TOKENS } from "../constants";
import { validateGenerateRequest, type GenerateRequest } from "../contract";
import { checkOutput, hasEnoughItems } from "../core/outputSafety";
import { costKeyOf } from "../model";
import { restBodyOf } from "./vertexRest";

/** A request as the server reads it, validated as if sent at noon UTC on its own `today`. */
export function requestFrom(raw: Record<string, unknown>): GenerateRequest {
  const body = { requestId: "00000000-0000-4000-8000-000000000000", ...raw };
  return validateGenerateRequest(body, Date.parse(`${String(raw.today)}T12:00:00Z`));
}

function readRequest(path: string): GenerateRequest {
  return requestFrom(JSON.parse(readFileSync(path, "utf8")) as Record<string, unknown>);
}

/** The candidate's JSON: either the answer itself or the text part of a generateContent response. */
export function answerOf(parsed: unknown): unknown {
  const text = (parsed as { candidates?: { content?: { parts?: { text?: string }[] } }[] })
    .candidates?.[0]?.content?.parts?.map((p) => p.text ?? "").join("");
  return text === undefined ? parsed : JSON.parse(text);
}

/** The Vertex AI REST body the generator sends for this request and prompt. */
export function vertexBody(req: GenerateRequest, prompt: Prompt, model: string, thinkingLevel: string, jailbreakFilter = false): Record<string, unknown> {
  const maxOutputTokens = MAX_OUTPUT_TOKENS[costKeyOf(req.kind, req.courses)];
  return restBodyOf(generationParamsFor(prompt, maxOutputTokens, { model, thinkingLevel, jailbreakFilter }));
}

function main(): void {
  const { positionals, values } = parseArgs({
    allowPositionals: true,
    options: { model: { type: "string", default: "gemini-3.8-flash" }, thinking: { type: "string", default: "LOW" } },
  });
  const [command, requestPath, answerPath] = positionals;
  if (!requestPath || (command !== "body" && command !== "check") || (command === "check" && !answerPath)) {
    process.stderr.write("usage: prompt-body body <request.json> [--model m] [--thinking LOW] | check <request.json> <answer.json>\n");
    process.exit(2);
  }
  const req = readRequest(requestPath);
  if (!hasEnoughItems(req)) {
    process.stdout.write(`${JSON.stringify({ refused: "NOT_ENOUGH_ITEMS" })}\n`);
    return;
  }
  const canary = newCanary();
  const prompt = buildPrompt(req, canary);
  if (command === "check") {
    const answer = answerOf(JSON.parse(readFileSync(answerPath, "utf8")));
    const verdict = checkOutput(answer, { req, canary, systemInstruction: prompt.systemInstruction });
    process.stdout.write(`${JSON.stringify(verdict.ok ? { ok: true } : verdict, null, 2)}\n`);
    return;
  }
  process.stdout.write(`${JSON.stringify(vertexBody(req, prompt, values.model!, values.thinking!), null, 2)}\n`);
}

if (require.main === module) main();
