/**
 * The online half of the red-team runner: each planned case through exactly what generateRecipe
 * does between reservation and settle (core/generation.ts runGenerator: the Gemini adapter, the
 * output checks, the quality gate, the judge), against whatever StreamingModels it is given (the
 * live REST models in `run`, canned answers in the tests). Nothing is reserved, settled or written
 * to Firestore.
 */
import { GeminiOutputJudge, GeminiRecipeGenerator, type GeminiOptions, type StreamingModels } from "../../adapters/gemini";
import type { Prompt } from "../../adapters/prompt";
import type { GenerateRequest, Menu, Recipe } from "../../contract";
import { judgeWorstCaseMicroUsd, planFor, runGenerator } from "../../core/generation";
import { judgePricesOf, microUsd, type Prices } from "../../model";
import {
  GenerationError,
  type GenerationLimits,
  type GenerationResult,
  type JudgeEntry,
  type JudgeLog,
  type JudgeResult,
  type LlmUsage,
  type Logger,
  type OutputJudge,
  type RecipeGenerator,
} from "../../ports";
import { classify, evaluate, quotesFor, verdictOf, type Status, type Violation } from "./evaluate";
import { runtimeConfigFor, type PlannedCase } from "./plan";

/** Records what the generator saw and answered, for the assertions and the report. */
class RecordingGenerator implements RecipeGenerator {
  raw?: unknown;
  usage?: LlmUsage;
  error?: string;
  systemInstruction?: string;
  called = false;
  constructor(private readonly inner: RecipeGenerator) {}
  async generate(request: GenerateRequest, prompt: Prompt, signal: AbortSignal, limits: GenerationLimits): Promise<GenerationResult> {
    this.called = true;
    this.systemInstruction = prompt.systemInstruction;
    try {
      const r = await this.inner.generate(request, prompt, signal, limits);
      this.raw = r.output;
      this.usage = r.usage;
      return r;
    } catch (e) {
      if (e instanceof GenerationError) {
        this.usage = e.usage;
        this.error = e.message;
      }
      throw e;
    }
  }
}

class RecordingJudge implements OutputJudge {
  verdict?: string;
  usage?: LlmUsage;
  called = false;
  constructor(private readonly inner: OutputJudge) {}
  get model(): string {
    return this.inner.model;
  }
  async judge(output: { recipe?: Recipe; menu?: Menu }, signal: AbortSignal): Promise<JudgeResult> {
    this.called = true;
    try {
      const r = await this.inner.judge(output, signal);
      this.verdict = r.verdict;
      this.usage = r.usage;
      return r;
    } catch (e) {
      if (e instanceof GenerationError) this.usage = e.usage;
      throw e;
    }
  }
}

export interface AttemptResult {
  id: string;
  section: string;
  attempt: number;
  status: Status;
  reason: string;
  /** "ok", an output rule, a generator verdict, an error, or the input verdict for a call never made. */
  verdict: string;
  failureClass?: string;
  judgeVerdict?: string;
  /** The judge's description (where, issues, quality, note), as kept in judgeAnswers. */
  judgeAnswer?: Pick<JudgeEntry, "where" | "issues" | "quality" | "note" | "latencyMs">;
  usage?: LlmUsage;
  judgeUsage?: LlmUsage;
  /** Booked as the server would: measured, or the bound when usage is unknown. */
  costMicroUsd: number;
  upperMicroUsd: number;
  usageUnknown?: boolean;
  violations: Violation[];
  quotes: string[];
  /** The model's raw answer (git-ignored results only). */
  raw?: unknown;
  httpLog: string[];
  durationMs: number;
  model?: string;
}

export interface ExecDeps {
  /** A fresh StreamingModels per attempt (so its HTTP log is that attempt's). */
  models(): StreamingModels & { log?: string[] };
  options: GeminiOptions;
  judgeModel: string;
  prices: Prices;
  now(): number;
}

const silent: Logger = { info: () => undefined, warn: () => undefined, error: () => undefined };

/** One model attempt of an accepted case, with every check the function makes and the case's assertions. */
export async function runAttempt(p: PlannedCase, attempt: number, deps: ExecDeps): Promise<AttemptResult> {
  const req = p.req!;
  const models = deps.models();
  const generator = new RecordingGenerator(new GeminiRecipeGenerator(deps.options, models));
  const judge = new RecordingJudge(new GeminiOutputJudge({ ...deps.options, model: deps.judgeModel }, models));
  const config = runtimeConfigFor(p.judge, deps.prices);
  const plan = planFor(req, config, deps.judgeModel);
  const started = deps.now();
  const logger: Logger = {
    ...silent,
    info: (message, data) => {
      if (message === "prompt_judge_verdict" && typeof data?.verdict === "string") judge.verdict = data.verdict;
    },
  };
  const kept: JudgeEntry[] = [];
  const judgeLog: JudgeLog = { record: async (e) => void kept.push(e) };
  const outcome = await runGenerator({ generator, judge, judgeLog, logger }, "redteam", req, plan.limits, config);
  const durationMs = deps.now() - started;

  const genBound = microUsd(deps.prices, p.estimate!.inputBytes, plan.limits.hardTokenCeiling);
  const genCost = generator.usage
    ? microUsd(deps.prices, generator.usage.inputTokens, generator.usage.outputTokens + generator.usage.thinkingTokens)
    : generator.called ? genBound : 0;
  const judgeCost = !judge.called
    ? 0
    : judge.usage
      ? microUsd(judgePricesOf(config, deps.judgeModel), judge.usage.inputTokens, judge.usage.outputTokens + judge.usage.thinkingTokens)
      : judgeWorstCaseMicroUsd(config, plan.limits.maxOutputTokens, deps.judgeModel);

  const verdict = verdictOf(outcome, judge.verdict);
  const violations: Violation[] = [];
  for (const a of p.c.expect.assertions ?? []) {
    const r = evaluate(a, { raw: generator.raw, req, usage: generator.usage, actualMicroUsd: genCost, upperMicroUsd: genBound, systemInstruction: generator.systemInstruction });
    if (r && r !== "n/a") violations.push(r);
  }
  const cls = classify(p.c, verdict, violations);
  const humanRead = p.c.expect.humanRead;
  return {
    id: p.c.id,
    section: p.c.section,
    attempt,
    status: cls.status,
    reason: cls.reason,
    verdict,
    ...(outcome.ok ? {} : { failureClass: outcome.failureClass }),
    ...(judge.verdict ? { judgeVerdict: judge.verdict } : {}),
    ...(kept[0] ? { judgeAnswer: { where: kept[0].where, issues: kept[0].issues, quality: kept[0].quality, note: kept[0].note, latencyMs: kept[0].latencyMs } } : {}),
    ...(generator.usage ? { usage: generator.usage } : {}),
    ...(judge.usage ? { judgeUsage: judge.usage } : {}),
    costMicroUsd: genCost + judgeCost,
    upperMicroUsd: p.estimate!.upperMicroUsd,
    ...(generator.called && !generator.usage ? { usageUnknown: true } : {}),
    violations,
    quotes: humanRead && generator.raw !== undefined ? quotesFor(humanRead, generator.raw) : [],
    ...(generator.raw !== undefined ? { raw: generator.raw } : {}),
    httpLog: [...(models.log ?? []), ...(generator.error ? [`generator: ${generator.error}`] : [])],
    durationMs,
    ...(generator.usage?.model ? { model: generator.usage.model } : {}),
  };
}

/** The result of a case that makes no model call: refused before the model, or manual. */
export function noCallResult(p: PlannedCase): AttemptResult {
  const base = { id: p.c.id, section: p.c.section, attempt: 1, costMicroUsd: 0, upperMicroUsd: 0, violations: [], quotes: [], httpLog: [], durationMs: 0 };
  if (p.c.mode === "manual") return { ...base, status: "MANUAL", reason: "needs the deployed function and an account", verdict: "manual" };
  return p.inputAsExpected
    ? { ...base, status: "PASS", reason: `refused before any model call (${p.input}), as expected`, verdict: p.input }
    : { ...base, status: "FAIL", reason: `input verdict ${p.input}, expected ${p.c.expect.input}`, verdict: p.input };
}

export interface RunOptions {
  concurrency: number;
  /** Hard stop: no call starts if actual spend + in-flight bounds + its bound would pass this. */
  maxMicroUsd: number;
  onResult?(r: AttemptResult, spentMicroUsd: number, done: number, total: number): void;
}

/** Every planned case: no-call results at once, model attempts through a small pool under the budget stop. */
export async function runAll(planned: readonly PlannedCase[], deps: ExecDeps, o: RunOptions): Promise<AttemptResult[]> {
  const results: AttemptResult[] = planned.filter((p) => p.attempts === 0).map(noCallResult);
  const jobs = planned.flatMap((p) => Array.from({ length: p.attempts }, (_, i) => ({ p, attempt: i + 1 })));
  let spent = 0;
  let inFlight = 0;
  let next = 0;
  let done = 0;
  const worker = async () => {
    while (next < jobs.length) {
      const job = jobs[next++];
      const bound = job.p.estimate!.upperMicroUsd;
      if (spent + inFlight + bound > o.maxMicroUsd) {
        const r: AttemptResult = {
          id: job.p.c.id, section: job.p.c.section, attempt: job.attempt, status: "SKIPPED",
          reason: `budget: spent ${spent} + in flight ${inFlight} + this call's bound ${bound} micro-USD > --max-usd`,
          verdict: "skipped", costMicroUsd: 0, upperMicroUsd: bound, violations: [], quotes: [], httpLog: [], durationMs: 0,
        };
        results.push(r);
        o.onResult?.(r, spent, ++done, jobs.length);
        continue;
      }
      inFlight += bound;
      let r: AttemptResult;
      try {
        r = await runAttempt(job.p, job.attempt, deps);
      } catch (e) {
        // runGenerator catches everything the model call throws; this is a bug in the runner itself.
        r = {
          id: job.p.c.id, section: job.p.c.section, attempt: job.attempt, status: "ERROR", reason: `runner error: ${String(e).slice(0, 200)}`,
          verdict: "GENERATOR_ERROR", costMicroUsd: bound, upperMicroUsd: bound, usageUnknown: true, violations: [], quotes: [], httpLog: [], durationMs: 0,
        };
      }
      inFlight -= bound;
      spent += r.costMicroUsd;
      results.push(r);
      o.onResult?.(r, spent, ++done, jobs.length);
    }
  };
  await Promise.all(Array.from({ length: Math.max(1, o.concurrency) }, worker));
  const order = new Map(planned.map((p, i) => [p.c.id, i]));
  return results.sort((a, b) => order.get(a.id)! - order.get(b.id)! || a.attempt - b.attempt);
}
