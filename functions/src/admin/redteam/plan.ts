/**
 * The offline half of the red-team runner: every case through the server's real input validation
 * and NOT_ENOUGH_ITEMS check, the exact body the server would send (admin/promptBody.ts), and what
 * the run may cost, priced with the server's own constants and formulas. No network.
 *
 * Three figures per call:
 * - upper: core/generation.ts planFor's reservation, the bound the function itself reserves
 *   (input bytes as tokens, THINKING_FACTOR x maxOutputTokens at the output price, plus the
 *   judge's worst case when the case runs the judge);
 * - expected: the input bound converted to tokens with the bytes per token measured on the live
 *   2-course menu of 2026-11-14, plus output and thinking taken from what the live project
 *   logged (EXPECTED_OUTPUT below, the larger observations, doubled at stepByStep detail);
 * - the run's totals of both.
 */
import { readFileSync } from "node:fs";
import { buildPrompt, inputTokenBound, newCanary } from "../../adapters/prompt";
import { JUDGE_INSTRUCTION } from "../../adapters/gemini";
import { COST_KEYS, MAX_OUTPUT_TOKENS, THINKING_FACTOR, type CostKey } from "../../constants";
import { isNameRejection, type GenerateRequest } from "../../contract";
import { planFor } from "../../core/generation";
import { hasEnoughItems } from "../../core/outputSafety";
import { DEFAULT_RUNTIME_CONFIG, costKeyOf, microUsd, type Prices, type RuntimeConfig } from "../../model";
import { requestFrom, vertexBody } from "../promptBody";
import type { InputVerdict, LoadedCase } from "./cases";

/** What the live project logged (llm_call lines, 2026-11-14 and 2026-11-15, gemini-3.8-flash, thinking LOW). */
export const OBSERVED = {
  menu2: { inputTokens: [1673, 1811], outputTokens: [1930, 2643], thinkingTokens: [0, 892], estimatedMicroUsd: 22467 },
  menu3: { inputTokens: 1802, outputTokens: 2434 },
  recipe: { inputTokens: 1387, outputTokens: 384, thinkingTokens: 259 },
} as const;

/**
 * Expected output plus thinking tokens of one call, by kind: the larger observation of each
 * (recipe 384 + 259; 2-course menu 2,643 + 892), and for a 3-course menu the larger of what it
 * used (2,434 output, thinking not logged) and 1.5 x the 2-course figures (3 courses instead of 2),
 * i.e. 3,965 + 1,338. Those are 3-day menus (`menu3x2`, `menu3x3`); every other menu set, never
 * observed yet, is the 3-day 2-course figures scaled by its number of dishes (days x courses over
 * 6). A stepByStep request (up to 15 steps instead of 4 to 8) is assumed to write twice as much.
 */
export const EXPECTED_OUTPUT: Readonly<Record<CostKey, { output: number; thinking: number }>> = Object.fromEntries(
  COST_KEYS.map((key) => {
    if (key === "recipe") return [key, { output: OBSERVED.recipe.outputTokens, thinking: OBSERVED.recipe.thinkingTokens }];
    if (key === "menu3x3") {
      return [key, {
        output: Math.max(OBSERVED.menu3.outputTokens, Math.ceil(OBSERVED.menu2.outputTokens[1] * 1.5)),
        thinking: Math.ceil(OBSERVED.menu2.thinkingTokens[1] * 1.5),
      }];
    }
    const [d, c] = key.slice(4).split("x").map(Number);
    const scale = (d * c) / 6;
    return [key, { output: Math.ceil(OBSERVED.menu2.outputTokens[1] * scale), thinking: Math.ceil(OBSERVED.menu2.thinkingTokens[1] * scale) }];
  }),
) as Record<CostKey, { output: number; thinking: number }>;
export const STEP_BY_STEP_FACTOR = 2;
/** The judge's answer is one enum value: 32 visible tokens at most, plus LOW thinking; assumed 300 together. */
export const JUDGE_EXPECTED_OUTPUT = 300;

/** Bytes per input token, measured: the live 2-course menu's input bound over the most input tokens it was logged with. */
export function bytesPerToken(liveMenu: GenerateRequest): number {
  return inputTokenBound(liveMenu) / OBSERVED.menu2.inputTokens[1];
}

export interface CallEstimate {
  costKey: CostKey;
  inputBytes: number;
  /** The reservation bound (planFor), judge included when the case runs it. */
  upperMicroUsd: number;
  /** The generator's part of the bound alone (input bound + THINKING_FACTOR x maxOutputTokens). */
  upperGeneratorMicroUsd: number;
  expectedInputTokens: number;
  expectedOutputTokens: number;
  expectedMicroUsd: number;
}

export function runtimeConfigFor(judge: "off" | "shadow" | "enforce", prices: Prices): RuntimeConfig {
  return { ...DEFAULT_RUNTIME_CONFIG, outputJudge: judge, prices };
}

/** One call's three figures. */
export function estimateCall(req: GenerateRequest, judge: "off" | "shadow" | "enforce", prices: Prices, bpt: number): CallEstimate {
  const costKey = costKeyOf(req);
  const inputBytes = inputTokenBound(req);
  const plan = planFor(req, runtimeConfigFor(judge, prices));
  const upperGeneratorMicroUsd = microUsd(prices, inputBytes, THINKING_FACTOR * MAX_OUTPUT_TOKENS[costKey]);
  const out = EXPECTED_OUTPUT[costKey];
  const factor = req.config.detail === "stepByStep" ? STEP_BY_STEP_FACTOR : 1;
  const expectedInputTokens = Math.ceil(inputBytes / bpt);
  const expectedOutputTokens = (out.output + out.thinking) * factor;
  let expectedMicroUsd = microUsd(prices, expectedInputTokens, expectedOutputTokens);
  if (judge !== "off") {
    const judgeInput = Math.ceil(Buffer.byteLength(JUDGE_INSTRUCTION, "utf8") / bpt) + Math.ceil(out.output * factor * 1.1);
    expectedMicroUsd += microUsd(prices, judgeInput, JUDGE_EXPECTED_OUTPUT);
  }
  return {
    costKey,
    inputBytes,
    upperMicroUsd: plan.reservedMicroUsd,
    upperGeneratorMicroUsd,
    expectedInputTokens,
    expectedOutputTokens,
    expectedMicroUsd,
  };
}

export interface PlannedCase {
  c: LoadedCase;
  input: InputVerdict;
  /** The input verdict is what the case expects. */
  inputAsExpected: boolean;
  /** Set when the request was accepted. */
  req?: GenerateRequest;
  /** "off" unless the case (or --judge) runs the judge. */
  judge: "off" | "shadow" | "enforce";
  /** Model calls this case makes in the run (0: refused before the model, or manual). */
  attempts: number;
  estimate?: CallEstimate;
  /** Byte length of the REST body the server would send (built through promptBody.ts). */
  bodyBytes?: number;
}

/** The server's input verdict on a raw request: validation, then the NOT_ENOUGH_ITEMS pre-check. */
export function inputVerdictOf(raw: Record<string, unknown>): { verdict: InputVerdict; req?: GenerateRequest; message?: string } {
  let req: GenerateRequest;
  try {
    req = requestFrom(raw);
  } catch (e) {
    const details = (e as { details?: unknown }).details;
    if (isNameRejection(details)) return { verdict: details.rule, message: (e as Error).message };
    const reason = (details as { reason?: string } | undefined)?.reason;
    if (reason === "ALLERGEN_CONSENT_REQUIRED") return { verdict: reason, message: (e as Error).message };
    if (reason === "INVALID_REQUEST") return { verdict: "INVALID_REQUEST", message: (e as Error).message };
    throw e;
  }
  if (!hasEnoughItems(req)) return { verdict: "NOT_ENOUGH_ITEMS", req };
  return { verdict: "accepted", req };
}

export interface PlanOptions {
  prices: Prices;
  bytesPerToken: number;
  model: string;
  thinkingLevel: string;
  /** Model calls per case for the sections in `repeatSections` ("all": every section). */
  repeat: number;
  repeatSections: string[] | "all";
  /** Overrides every case's judge setting. */
  judge?: "off" | "shadow" | "enforce";
}

export function planCases(cases: readonly LoadedCase[], o: PlanOptions): PlannedCase[] {
  return cases.map((c): PlannedCase => {
    const judge = o.judge ?? c.judge ?? "off";
    if (c.mode === "manual" || !c.raw) return { c, input: "accepted", inputAsExpected: true, judge, attempts: 0 };
    const { verdict, req } = inputVerdictOf(c.raw);
    const inputAsExpected = verdict === c.expect.input;
    if (verdict !== "accepted" || !req) return { c, input: verdict, inputAsExpected, judge, attempts: 0 };
    const repeats = o.repeatSections === "all" || o.repeatSections.includes(c.section) ? o.repeat : 1;
    const body = vertexBody(req, buildPrompt(req, newCanary()), o.model, o.thinkingLevel);
    return {
      c,
      input: verdict,
      inputAsExpected,
      req,
      judge,
      attempts: inputAsExpected ? repeats : 0,
      estimate: estimateCall(req, judge, o.prices, o.bytesPerToken),
      bodyBytes: Buffer.byteLength(JSON.stringify(body), "utf8"),
    };
  });
}

export interface KindTotal {
  costKey: CostKey;
  cases: number;
  calls: number;
  upperPerCallMicroUsd: [number, number];
  expectedPerCallMicroUsd: [number, number];
  upperMicroUsd: number;
  expectedMicroUsd: number;
}

export interface PlanTotals {
  byKind: KindTotal[];
  calls: number;
  upperMicroUsd: number;
  expectedMicroUsd: number;
  /** The largest single call's bound: what one in-flight call can add at most. */
  largestUpperMicroUsd: number;
  refusedBeforeModel: number;
  manual: number;
  mismatches: PlannedCase[];
}

export function totalsOf(planned: readonly PlannedCase[]): PlanTotals {
  const byKind = COST_KEYS.map((costKey): KindTotal => {
    const rows = planned.filter((p) => p.estimate?.costKey === costKey && p.attempts > 0);
    const uppers = rows.map((p) => p.estimate!.upperMicroUsd);
    const expected = rows.map((p) => p.estimate!.expectedMicroUsd);
    return {
      costKey,
      cases: rows.length,
      calls: rows.reduce((s, p) => s + p.attempts, 0),
      upperPerCallMicroUsd: [uppers.length ? Math.min(...uppers) : 0, uppers.length ? Math.max(...uppers) : 0],
      expectedPerCallMicroUsd: [expected.length ? Math.min(...expected) : 0, expected.length ? Math.max(...expected) : 0],
      upperMicroUsd: rows.reduce((s, p) => s + p.attempts * p.estimate!.upperMicroUsd, 0),
      expectedMicroUsd: rows.reduce((s, p) => s + p.attempts * p.estimate!.expectedMicroUsd, 0),
    };
  });
  return {
    byKind,
    calls: byKind.reduce((s, k) => s + k.calls, 0),
    upperMicroUsd: byKind.reduce((s, k) => s + k.upperMicroUsd, 0),
    expectedMicroUsd: byKind.reduce((s, k) => s + k.expectedMicroUsd, 0),
    largestUpperMicroUsd: Math.max(0, ...planned.filter((p) => p.attempts > 0).map((p) => p.estimate!.upperMicroUsd)),
    refusedBeforeModel: planned.filter((p) => p.c.mode !== "manual" && p.input !== "accepted").length,
    manual: planned.filter((p) => p.c.mode === "manual").length,
    mismatches: planned.filter((p) => !p.inputAsExpected),
  };
}

/**
 * The default `--max-usd`: twice the expected total, but never so low that the run cannot finish
 * at the expected cost with `concurrency` calls in flight at their worst case (the stop counts
 * in-flight calls at their bound before starting another).
 */
export function defaultMaxMicroUsd(t: PlanTotals, concurrency: number): number {
  return Math.max(2 * t.expectedMicroUsd, t.expectedMicroUsd + concurrency * t.largestUpperMicroUsd);
}

export const usd = (micro: number, digits = 4) => `USD ${(micro / 1e6).toFixed(digits)}`;

/** "recipe", or "3-day 2-course menu"; `short` gives "3d2c" for the case table. */
function kindLabel(key: CostKey, short = false): string {
  if (key === "recipe") return "recipe";
  const [d, c] = key.slice(4).split("x");
  return short ? `${d}d${c}c` : `${d}-day ${c}-course menu`;
}

export interface PlanContext {
  project: string;
  location: string;
  model: string;
  thinkingLevel: string;
  judgeModel: string;
  prices: Prices;
  bytesPerToken: number;
  liveMenuBytes: number;
  repeat: number;
  repeatSections: string[] | "all";
  concurrency: number;
  casesPath: string;
  localCases: number;
  /** Totals of the recommended run (repeat 3 for A-C), shown when this plan is not it. */
  recommended?: PlanTotals;
}

const pad = (s: string | number, n: number) => String(s).padEnd(n);
const padL = (s: string | number, n: number) => String(s).padStart(n);

/** The plan as printed by `npm run redteam -- plan`. */
export function renderPlan(planned: readonly PlannedCase[], ctx: PlanContext): string {
  const t = totalsOf(planned);
  const lines: string[] = [];
  const auto = planned.filter((p) => p.c.mode !== "manual");
  lines.push(
    `Red-team plan: ${planned.length} cases from ${ctx.casesPath}${ctx.localCases ? ` (+${ctx.localCases} local)` : ""}`,
    `Model ${ctx.model} (thinking ${ctx.thinkingLevel}), judge model ${ctx.judgeModel}, project ${ctx.project}, location ${ctx.location}`,
    `Prices: USD ${ctx.prices.inputUsdPerMTok} input / ${ctx.prices.outputUsdPerMTok} output per 1M tokens (DEFAULT_PRICES, thinking billed as output)`,
    "",
    " id   sec  kind    input verdict              calls  body B  bound B   upper/call  expected/call  judge",
  );
  for (const p of planned) {
    const kind = p.c.mode === "manual" ? "-" : p.req ? kindLabel(costKeyOf(p.req), true) : "-";
    const verdict = p.c.mode === "manual" ? "manual" : p.inputAsExpected ? p.input : `${p.input} (EXPECTED ${p.c.expect.input})`;
    const e = p.estimate;
    lines.push(
      ` ${pad(p.c.id, 4)} ${pad(p.c.section, 4)} ${pad(kind, 7)} ${pad(verdict, 26)} ${padL(p.attempts, 5)}  ${padL(p.bodyBytes ?? "-", 6)}  ${padL(e?.inputBytes ?? "-", 6)}  ${padL(e ? (e.upperMicroUsd / 1e6).toFixed(4) : "-", 11)}  ${padL(e ? (e.expectedMicroUsd / 1e6).toFixed(4) : "-", 13)}  ${p.judge === "off" ? "" : p.judge}`,
    );
  }
  lines.push(
    "",
    `Cases: ${auto.length} automated (${auto.filter((p) => p.input === "accepted").length} call the model, ${t.refusedBeforeModel} are refused before any model call: cost 0), ${t.manual} manual.`,
    `Model calls in this run: ${t.calls}${ctx.repeat > 1 ? ` (--repeat ${ctx.repeat} for ${ctx.repeatSections === "all" ? "every section" : `sections ${ctx.repeatSections.join(", ")}`})` : ""}.`,
    "",
    "Cost by kind (USD)        calls   upper bound per call   expected per call    upper total   expected total",
  );
  for (const k of t.byKind) {
    if (!k.calls) continue;
    const range = (r: [number, number]) => (r[0] === r[1] ? (r[0] / 1e6).toFixed(4) : `${(r[0] / 1e6).toFixed(4)}-${(r[1] / 1e6).toFixed(4)}`);
    lines.push(
      `  ${pad(kindLabel(k.costKey), 22)} ${padL(k.calls, 5)}   ${padL(range(k.upperPerCallMicroUsd), 20)}   ${padL(range(k.expectedPerCallMicroUsd), 17)}   ${padL((k.upperMicroUsd / 1e6).toFixed(4), 11)}   ${padL((k.expectedMicroUsd / 1e6).toFixed(4), 14)}`,
    );
  }
  lines.push(
    `  ${pad("total", 22)} ${padL(t.calls, 5)}   ${padL("", 20)}   ${padL("", 17)}   ${padL((t.upperMicroUsd / 1e6).toFixed(4), 11)}   ${padL((t.expectedMicroUsd / 1e6).toFixed(4), 14)}`,
    "",
    `Expected total: ${usd(t.expectedMicroUsd)}. Hard upper bound (every call at its reservation bound): ${usd(t.upperMicroUsd)}.`,
    `Default --max-usd for this selection: ${usd(defaultMaxMicroUsd(t, ctx.concurrency), 2)} (2 x expected, or expected + ${ctx.concurrency} in-flight worst cases if larger).`,
  );
  if (ctx.recommended) {
    const r = ctx.recommended;
    lines.push(
      `Recommended run (--repeat 3: sections A-C three times, the rest once): ${r.calls} calls, expected ${usd(r.expectedMicroUsd)}, upper bound ${usd(r.upperMicroUsd)}, default --max-usd ${usd(defaultMaxMicroUsd(r, ctx.concurrency), 2)}.`,
    );
  }
  lines.push(
    "",
    "Assumptions behind the expected figure:",
    `- Input tokens = the server's input bound (UTF-8 bytes of instruction, data and schema) / ${ctx.bytesPerToken.toFixed(3)} bytes per token,`,
    `  measured on the live 2-course menu of 2026-11-14 (${ctx.liveMenuBytes} bytes, logged at up to ${OBSERVED.menu2.inputTokens[1]} input tokens).`,
    `- Output + thinking per call: recipe ${EXPECTED_OUTPUT.recipe.output} + ${EXPECTED_OUTPUT.recipe.thinking} (observed 384 / 259), 3-day 2-course menu ${EXPECTED_OUTPUT.menu3x2.output} + ${EXPECTED_OUTPUT.menu3x2.thinking}`,
    `  (the larger of 1930-2643 / 0-892 observed), 3-course ${EXPECTED_OUTPUT.menu3x3.output} + ${EXPECTED_OUTPUT.menu3x3.thinking} (1.5 x the 2-course figures; 2434 output observed);`,
    `  other menu sets: the 3-day 2-course figures x days x courses / 6 (not observed yet);`,
    `  x${STEP_BY_STEP_FACTOR} at stepByStep detail. Judge (when on): its instruction and the answer as input, ${JUDGE_EXPECTED_OUTPUT} output + thinking tokens.`,
    `- Check: a 2-course menu at these figures is ${usd(microUsd(ctx.prices, OBSERVED.menu2.inputTokens[1], EXPECTED_OUTPUT.menu3x2.output + EXPECTED_OUTPUT.menu3x2.thinking))}; the live one was logged at ${usd(OBSERVED.menu2.estimatedMicroUsd)}.`,
    `- Prices are the list prices from 2027-01-01; until then Google's introductory price is half, so the real bill is about half these figures.`,
    "",
    "What this money is, and what it is not:",
    "- The runner calls the model directly (Vertex AI generateContent as the person whose token it uses), not the app's",
    "  generateRecipe function. Its cost is on the project's Google Cloud bill and counts toward the billing budget",
    "  (1000 NOK, with the kill switch at budgetKillFraction), but not toward the app's own spend ceilings (USD 0.45 per",
    "  user and USD 1.20 a day global base, in Firestore stats), nor anyone's recipe balance; it cannot trigger refunds,",
    "  the prompt-rejection lock or the purchase blocklist, and nothing is written to Firestore.",
    `- The run stops starting calls once actual spend plus in-flight worst cases would pass --max-usd.`,
  );
  if (t.mismatches.length) {
    lines.push("", `PLAN ERROR: ${t.mismatches.length} case(s) got a different input verdict than expected: ${t.mismatches.map((p) => p.c.id).join(", ")}.`);
  }
  return lines.join("\n");
}

/** Reads a JSON request file through the same validation. */
export function requestFile(path: string): GenerateRequest {
  return requestFrom(JSON.parse(readFileSync(path, "utf8")) as Record<string, unknown>);
}
