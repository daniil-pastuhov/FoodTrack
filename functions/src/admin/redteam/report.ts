/** The red-team run's result file and its Markdown report (re-rendered by `npm run redteam -- report <dir>`). Pure. */
import type { Prices } from "../../model";
import { STATUS_ORDER, worstStatus, type Status } from "./evaluate";
import type { AttemptResult } from "./execute";

export interface CaseSummary {
  id: string;
  section: string;
  title: string;
  checklist: string;
  mode: "auto" | "manual";
  manualSteps?: string[];
  humanRead?: string;
}

export interface RunFile {
  version: 1;
  startedAt: string;
  finishedAt: string;
  project: string;
  location: string;
  model: string;
  thinkingLevel: string;
  judgeModel: string;
  prices: Prices;
  maxMicroUsd: number;
  expectedMicroUsd: number;
  upperMicroUsd: number;
  argv: string[];
  cases: CaseSummary[];
  results: AttemptResult[];
}

export interface Totals {
  calls: number;
  inputTokens: number;
  outputTokens: number;
  thinkingTokens: number;
  costMicroUsd: number;
  usageUnknown: number;
  statuses: Record<Status, number>;
}

export function totalsOfRun(results: readonly AttemptResult[]): Totals {
  const statuses = Object.fromEntries(STATUS_ORDER.map((s) => [s, 0])) as Record<Status, number>;
  const t: Totals = { calls: 0, inputTokens: 0, outputTokens: 0, thinkingTokens: 0, costMicroUsd: 0, usageUnknown: 0, statuses };
  for (const r of results) {
    statuses[r.status]++;
    t.costMicroUsd += r.costMicroUsd;
    if (r.usageUnknown) t.usageUnknown++;
    for (const u of [r.usage, r.judgeUsage]) {
      if (!u) continue;
      t.inputTokens += u.inputTokens;
      t.outputTokens += u.outputTokens;
      t.thinkingTokens += u.thinkingTokens;
    }
    if (r.usage || r.usageUnknown) t.calls++;
  }
  return t;
}

const cell = (s: string) => s.replace(/\|/g, "\\|").replace(/\n/g, " ");
const quote = (s: string) => `> ${s.replace(/\n/g, " ")}`;
const usd = (micro: number) => (micro / 1e6).toFixed(4);
const tokens = (r: AttemptResult) => (r.usage ? `${r.usage.inputTokens} / ${r.usage.outputTokens} / ${r.usage.thinkingTokens}` : r.usageUnknown ? "unknown" : "-");

function detail(r: AttemptResult, c: CaseSummary | undefined): string[] {
  const out = [
    `### ${r.id}${r.attempt > 1 ? ` (attempt ${r.attempt})` : ""}: ${c?.title ?? ""}`,
    "",
    `- Checklist: ${c?.checklist ?? ""}`,
    `- Server verdict: \`${r.verdict}\`${r.failureClass ? ` (${r.failureClass} failure)` : ""}${r.judgeVerdict ? `; judge: \`${r.judgeVerdict}\`` : ""}`,
    `- Classified ${r.status}: ${r.reason}`,
  ];
  for (const v of r.violations) {
    out.push(`- \`${v.type}\` (${v.severity}${v.heuristic ? ", heuristic" : ""})${v.note ? `: ${v.note}` : ""}`);
    for (const d of v.details.slice(0, 8)) out.push(`  ${quote(d)}`);
    if (v.details.length > 8) out.push(`  (${v.details.length - 8} more)`);
  }
  if (r.quotes.length) {
    out.push(`- Read: ${c?.humanRead ?? "the passages below"}`);
    for (const q of r.quotes) out.push(`  ${quote(q)}`);
  }
  if (r.status === "ERROR" && r.httpLog.length) for (const l of r.httpLog) out.push(`  ${quote(l)}`);
  out.push("");
  return out;
}

export function renderReport(run: RunFile): string {
  const t = totalsOfRun(run.results);
  const byId = new Map(run.cases.map((c) => [c.id, c]));
  const models = [...new Set(run.results.map((r) => r.model).filter(Boolean))];
  const lines = [
    `# Red-team report, ${run.startedAt.slice(0, 10)}`,
    "",
    `- Model: \`${run.model}\` (versions answering: ${models.length ? models.map((m) => `\`${m}\``).join(", ") : "none"}), thinking ${run.thinkingLevel}; judge model \`${run.judgeModel}\``,
    `- Project \`${run.project}\`, location \`${run.location}\`; run ${run.startedAt} to ${run.finishedAt}`,
    `- Command: \`npm run redteam -- ${run.argv.join(" ")}\``,
    `- Model calls: ${t.calls}; tokens in / out / thinking: ${t.inputTokens} / ${t.outputTokens} / ${t.thinkingTokens}${t.usageUnknown ? `; ${t.usageUnknown} call(s) with unknown usage booked at their bound` : ""}`,
    `- Cost at USD ${run.prices.inputUsdPerMTok} / ${run.prices.outputUsdPerMTok} per 1M tokens: USD ${usd(t.costMicroUsd)} (plan: expected ${usd(run.expectedMicroUsd)}, bound ${usd(run.upperMicroUsd)}, stop at ${usd(run.maxMicroUsd)})`,
    `- ${STATUS_ORDER.map((s) => `${s} ${t.statuses[s]}`).join(", ")}`,
    "",
    "PASS: expectation met. CAUGHT: the model misbehaved (or was refused) and the server's own checks refused the answer, a pass for the system.",
    "FAIL: the defence was breached, or an honest request was refused. NEEDS_HUMAN_READ: delivered, read the quoted passages.",
    "",
    "## Results by section",
    "",
  ];
  const sections = [...new Set(run.cases.map((c) => c.section))];
  for (const s of sections) {
    lines.push(`### Section ${s}`, "", "| id | title | status | attempts | server verdicts | judge | in / out / think | USD |", "|---|---|---|---|---|---|---|---|");
    for (const c of run.cases.filter((x) => x.section === s)) {
      const rs = run.results.filter((r) => r.id === c.id);
      if (!rs.length) continue;
      const status = worstStatus(rs.map((r) => r.status));
      const verdicts = [...new Set(rs.map((r) => r.verdict))].join(", ");
      const judges = [...new Set(rs.map((r) => r.judgeVerdict).filter(Boolean))].join(", ");
      lines.push(`| ${c.id} | ${cell(c.title)} | ${status} | ${rs.length} | ${cell(verdicts)} | ${judges} | ${rs.map(tokens).join("; ")} | ${usd(rs.reduce((x, r) => x + r.costMicroUsd, 0))} |`);
    }
    lines.push("");
  }
  for (const [status, heading] of [["FAIL", "Failures"], ["CAUGHT", "Caught by the server"], ["NEEDS_HUMAN_READ", "Needs a human read"], ["ERROR", "Errors"], ["SKIPPED", "Skipped (budget)"]] as const) {
    const rs = run.results.filter((r) => r.status === status);
    if (!rs.length) continue;
    lines.push(`## ${heading} (${rs.length})`, "");
    for (const r of rs) lines.push(...(status === "SKIPPED" ? [`- ${r.id} attempt ${r.attempt}: ${r.reason}`] : detail(r, byId.get(r.id))));
    lines.push("");
  }
  const manual = run.cases.filter((c) => c.mode === "manual");
  if (manual.length) {
    lines.push("## Manual items (not automated)", "");
    for (const c of manual) {
      lines.push(`### ${c.id}: ${c.title}`, "", `Expect: ${c.checklist}`, "");
      (c.manualSteps ?? []).forEach((s, i) => lines.push(`${i + 1}. ${s}`));
      lines.push("");
    }
  }
  return lines.join("\n");
}
