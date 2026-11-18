/**
 * The prompt-injection red-team runner (test/injection-prompts.md "Running it").
 *
 *   npm run redteam -- plan   [selection]                     offline: validate, build, estimate cost
 *   VERTEX_TOKEN=$(gcloud auth print-access-token --account=you@example.com) \
 *   npm run redteam -- run --yes [selection] [--max-usd 1.50] [--concurrency 2] [--out dir]
 *   npm run redteam -- report <dir>                           re-render report.md from results.json
 *
 * selection: --only 1,4,26a  --section A,B,C  --repeat 3 [--repeat-sections A,B,C|all]  --judge off|shadow|enforce
 * model:     --project bestbefore-f9ee1 (reads .env.<project>: GEMINI_MODEL, GEMINI_THINKING_LEVEL,
 *            VERTEX_LOCATION, GEMINI_JUDGE_MODEL, GEMINI_JAILBREAK_FILTER), or --model --thinking --location --judge-model
 *
 * Only `run` makes network calls, only with --yes, and only to Vertex AI with the bearer token in
 * VERTEX_TOKEN, which is never printed or written. No key file or Application Default Credentials
 * are read. Nothing is written to Firestore; no app function is called.
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { parseArgs } from "node:util";
import { DEFAULT_PRICES, JUDGE } from "../../constants";
import { loadCases, selectCases, type LoadedCase } from "./cases";
import { runAll } from "./execute";
import { bytesPerToken, defaultMaxMicroUsd, planCases, renderPlan, requestFile, totalsOf, usd, type PlanOptions } from "./plan";
import { renderReport, totalsOfRun, type RunFile } from "./report";
import { buildPrompt, inputTokenBound, newCanary } from "../../adapters/prompt";
import { vertexBody } from "../promptBody";
import { RestStreamingModels } from "../vertexRest";

/** functions/, from lib/admin/redteam/cli.js. */
const ROOT = resolve(__dirname, "../../..");
const DEFAULT_PROJECT = "bestbefore-f9ee1";
const DEFAULT_REPEAT_SECTIONS = ["A", "B", "C"];

/** KEY=VALUE lines of a committed deploy-parameter file (no secrets in it). */
export function readEnvFile(path: string): Record<string, string> {
  if (!existsSync(path)) return {};
  const out: Record<string, string> = {};
  for (const line of readFileSync(path, "utf8").split(/\r?\n/)) {
    const m = /^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/.exec(line);
    if (m) out[m[1]] = m[2];
  }
  return out;
}

const list = (v: string | undefined) => (v ? v.split(",").map((s) => s.trim()).filter(Boolean) : undefined);

function fail(message: string): never {
  process.stderr.write(`redteam: ${message}\n`);
  process.exit(2);
}

function main(): void {
  const { positionals, values } = parseArgs({
    allowPositionals: true,
    options: {
      cases: { type: "string", default: "test/redteam/cases.json" },
      only: { type: "string" },
      section: { type: "string" },
      repeat: { type: "string", default: "1" },
      "repeat-sections": { type: "string" },
      judge: { type: "string" },
      "max-usd": { type: "string" },
      concurrency: { type: "string", default: "2" },
      out: { type: "string" },
      yes: { type: "boolean", default: false },
      project: { type: "string", default: DEFAULT_PROJECT },
      model: { type: "string" },
      thinking: { type: "string" },
      location: { type: "string" },
      "judge-model": { type: "string" },
      dump: { type: "string" },
    },
  });
  const command = positionals[0] ?? "plan";
  if (command === "report") {
    const dir = positionals[1] ?? fail("usage: redteam report <dir>");
    const run = JSON.parse(readFileSync(join(dir, "results.json"), "utf8")) as RunFile;
    writeFileSync(join(dir, "report.md"), renderReport(run));
    process.stdout.write(`${join(dir, "report.md")}\n`);
    return;
  }
  if (command !== "plan" && command !== "run") fail(`unknown command ${command} (plan, run, report)`);

  const env = readEnvFile(join(ROOT, `.env.${values.project}`));
  const model = values.model ?? env.GEMINI_MODEL ?? "gemini-3.8-flash";
  const thinkingLevel = values.thinking ?? env.GEMINI_THINKING_LEVEL ?? "LOW";
  const location = values.location ?? env.VERTEX_LOCATION ?? "global";
  // As the deployed function: the param's value, its default (JUDGE.model) when the file has none, GEMINI_MODEL when empty.
  const judgeModel = values["judge-model"] ?? (env.GEMINI_JUDGE_MODEL === undefined ? JUDGE.model : env.GEMINI_JUDGE_MODEL || model);
  const jailbreakFilter = env.GEMINI_JAILBREAK_FILTER === "true";
  const repeat = Number(values.repeat);
  if (!Number.isInteger(repeat) || repeat < 1 || repeat > 10) fail("--repeat must be 1 to 10");
  const concurrency = Number(values.concurrency);
  if (!Number.isInteger(concurrency) || concurrency < 1 || concurrency > 8) fail("--concurrency must be 1 to 8");
  const judge = values.judge as PlanOptions["judge"];
  if (judge !== undefined && !["off", "shadow", "enforce"].includes(judge)) fail("--judge must be off, shadow or enforce");
  const rs = values["repeat-sections"];
  const repeatSections: string[] | "all" = rs === "all" ? "all" : (list(rs) ?? DEFAULT_REPEAT_SECTIONS);

  const casesPath = resolve(ROOT, values.cases!);
  const localPath = casesPath.replace(/\.json$/, ".local.json");
  const all: LoadedCase[] = loadCases(casesPath, localPath);
  const selected = selectCases(all, list(values.only), list(values.section));
  if (!selected.length) fail("no case selected");

  const prices = { ...DEFAULT_PRICES };
  const liveMenu = requestFile(join(ROOT, "test/fixtures/live-menu-2026-11-14.json"));
  const bpt = bytesPerToken(liveMenu);
  const base: PlanOptions = { prices, bytesPerToken: bpt, model, thinkingLevel, judgeModel, repeat, repeatSections, judge };
  const planned = planCases(selected, base);
  const totals = totalsOf(planned);
  const recommended = repeat === 1 ? totalsOf(planCases(selected, { ...base, repeat: 3, repeatSections: DEFAULT_REPEAT_SECTIONS })) : undefined;
  const ctx = {
    project: values.project!, location, model, thinkingLevel, judgeModel, prices, bytesPerToken: bpt,
    liveMenuBytes: inputTokenBound(liveMenu), repeat, repeatSections, concurrency,
    casesPath: values.cases!, localCases: all.filter((c) => c.source === localPath).length,
    ...(recommended && recommended.calls !== totals.calls ? { recommended } : {}),
  };

  if (command === "plan") {
    process.stdout.write(`${renderPlan(planned, ctx)}\n`);
    if (values.dump) {
      mkdirSync(values.dump, { recursive: true });
      for (const p of planned) {
        if (p.req) writeFileSync(join(values.dump, `${p.c.id}.json`), `${JSON.stringify(vertexBody(p.req, buildPrompt(p.req, newCanary()), model, thinkingLevel, jailbreakFilter), null, 2)}\n`);
      }
      process.stdout.write(`\nRequest bodies written to ${values.dump}\n`);
    }
    if (totals.mismatches.length) process.exit(1);
    return;
  }

  // --- run ---------------------------------------------------------------------------------------
  if (totals.mismatches.length) fail(`the plan has input-verdict mismatches (${totals.mismatches.map((p) => p.c.id).join(", ")}); fix the cases first`);
  if (!values.yes) fail("run calls Vertex AI and costs money: run `npm run redteam -- plan` first, then add --yes");
  if (!process.env.VERTEX_TOKEN) fail("set VERTEX_TOKEN, e.g. VERTEX_TOKEN=$(gcloud auth print-access-token --account=you@example.com)");
  const maxMicroUsd = values["max-usd"] !== undefined ? Math.round(Number(values["max-usd"]) * 1e6) : defaultMaxMicroUsd(totals, concurrency);
  if (!Number.isFinite(maxMicroUsd) || maxMicroUsd <= 0) fail("--max-usd must be a positive amount");
  const startedAt = new Date().toISOString();
  const out = resolve(values.out ?? join(ROOT, "test/redteam/results", startedAt.replace(/[:.]/g, "-")));
  mkdirSync(out, { recursive: true });
  process.stderr.write(`redteam: ${totals.calls} model calls, expected ${usd(totals.expectedMicroUsd)}, stop at ${usd(maxMicroUsd, 2)}; results in ${out}\n`);

  const options = { project: values.project!, location, model, thinkingLevel, jailbreakFilter };
  const token = () => process.env.VERTEX_TOKEN ?? "";
  runAll(planned, {
    models: () => new RestStreamingModels({ project: options.project, location, token }),
    options,
    judgeModel,
    prices,
    now: () => Date.now(),
  }, {
    concurrency,
    maxMicroUsd,
    onResult: (r, spent, done, total) => {
      process.stderr.write(`[${done}/${total}] ${r.id}#${r.attempt} ${r.status} ${r.verdict} ${usd(r.costMicroUsd)} (spent ${usd(spent)})\n`);
    },
  }).then((results) => {
    const run: RunFile = {
      version: 1,
      startedAt,
      finishedAt: new Date().toISOString(),
      project: options.project,
      location,
      model,
      thinkingLevel,
      judgeModel,
      prices,
      maxMicroUsd,
      expectedMicroUsd: totals.expectedMicroUsd,
      upperMicroUsd: totals.upperMicroUsd,
      argv: process.argv.slice(2),
      cases: selected.map((c) => ({
        id: c.id, section: c.section, title: c.title, checklist: c.checklist, mode: c.mode ?? "auto",
        ...(c.manualSteps ? { manualSteps: c.manualSteps } : {}),
        ...(c.expect?.humanRead ? { humanRead: c.expect.humanRead.note } : {}),
      })),
      results,
    };
    writeFileSync(join(out, "results.json"), `${JSON.stringify(run, null, 2)}\n`);
    writeFileSync(join(out, "report.md"), renderReport(run));
    const t = totalsOfRun(results);
    process.stdout.write(`${Object.entries(t.statuses).map(([s, n]) => `${s} ${n}`).join(", ")}; ${usd(t.costMicroUsd)}\n${join(out, "report.md")}\n`);
    if (t.statuses.FAIL > 0) process.exitCode = 1;
  }).catch((e: unknown) => fail(`run failed: ${String(e).slice(0, 300)}`));
}

if (require.main === module) main();
