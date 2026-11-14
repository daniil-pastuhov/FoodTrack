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
 */
import { readFileSync } from "node:fs";
import { parseArgs } from "node:util";
import { buildPrompt, newCanary } from "../adapters/prompt";
import { safetySettingsFor, thinkingConfigFor } from "../adapters/gemini";
import { MAX_OUTPUT_TOKENS } from "../constants";
import { validateGenerateRequest, type GenerateRequest } from "../contract";
import { checkOutput, hasEnoughItems } from "../core/outputSafety";
import { costKeyOf } from "../model";

function readRequest(path: string): GenerateRequest {
  const raw = JSON.parse(readFileSync(path, "utf8")) as { today?: string; requestId?: string };
  const body = { requestId: "00000000-0000-4000-8000-000000000000", ...raw };
  return validateGenerateRequest(body, Date.parse(`${raw.today}T12:00:00Z`));
}

/** The candidate's JSON: either the answer itself or the text part of a generateContent response. */
function readAnswer(path: string): unknown {
  const parsed = JSON.parse(readFileSync(path, "utf8")) as { candidates?: { content?: { parts?: { text?: string }[] } }[] };
  const text = parsed.candidates?.[0]?.content?.parts?.map((p) => p.text ?? "").join("");
  return text === undefined ? parsed : JSON.parse(text);
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
    const verdict = checkOutput(readAnswer(answerPath), { req, canary, systemInstruction: prompt.systemInstruction });
    process.stdout.write(`${JSON.stringify(verdict.ok ? { ok: true } : verdict, null, 2)}\n`);
    return;
  }
  const body = {
    contents: [{ role: "user", parts: [{ text: prompt.userText }] }],
    systemInstruction: { parts: [{ text: prompt.systemInstruction }] },
    generationConfig: {
      responseMimeType: "application/json",
      responseJsonSchema: prompt.schema,
      maxOutputTokens: MAX_OUTPUT_TOKENS[costKeyOf(req.kind, req.courses)],
      temperature: 0.8,
      candidateCount: 1,
      thinkingConfig: thinkingConfigFor(values.model!, values.thinking!),
    },
    safetySettings: safetySettingsFor(false),
  };
  process.stdout.write(`${JSON.stringify(body, null, 2)}\n`);
}

main();
