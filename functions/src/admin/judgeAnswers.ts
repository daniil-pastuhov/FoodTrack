/**
 * The owner's read-only view of the judge's answers (core/judgeLog.ts, README "Prompt injection
 * defences" item 5):
 *
 *   npm run admin -- judge summary [--from YYYY-MM-DD] [--to YYYY-MM-DD] [--project <id>]
 *   npm run admin -- judge export  [--from YYYY-MM-DD] [--to YYYY-MM-DD] [--out <file.jsonl>] [--project <id>]
 *
 * Days are UTC and both ends included; the default is the last 30 days. `summary` counts verdicts,
 * issues, where, quality, latency and tokens, overall and by language, judge model, judge version,
 * generator prompt hash, text model and mode. `export` writes one JSON object per answer per line
 * for analysis elsewhere. Nothing is written to Firestore. A note is model text about untrusted
 * content: the summary never prints notes, and the export carries them JSON-escaped.
 */
import { writeFileSync } from "node:fs";
import { parseArgs } from "node:util";
import { Timestamp, type Firestore } from "firebase-admin/firestore";
import { JUDGE_LOG } from "../constants";
import { JUDGE_SUMMARY_GROUPS as GROUPS, summariseJudgeAnswers, type JudgeRow, type JudgeSummary } from "../core/judgeLog";
import { AdminRefusal } from "./accountRequests";

// The summary is the weekly prompt review's too (core/promptReview.ts), so it lives in core/judgeLog.ts.
export { summariseJudgeAnswers, type JudgeRow, type JudgeSummary };

export const JUDGE_USAGE = `usage:
  npm run admin -- judge summary [--from YYYY-MM-DD] [--to YYYY-MM-DD] [--project <id>]
  npm run admin -- judge export  [--from YYYY-MM-DD] [--to YYYY-MM-DD] [--out <file.jsonl>] [--project <id>]
UTC days, both included; default the last 30 days. Read-only.`;

const DAY_MS = 24 * 60 * 60 * 1000;
const DAY = /^\d{4}-\d{2}-\d{2}$/;
/** Answers read at most per command (a page is 500). */
export const JUDGE_READ_MAX = 50_000;

export interface JudgeArgs {
  command: "summary" | "export";
  project: string;
  /** Start of the first day and end of the last (exclusive), ms. */
  fromMs: number;
  toMs: number;
  out?: string;
}

function dayStart(s: string, flag: string): number {
  const ms = DAY.test(s) ? Date.parse(`${s}T00:00:00Z`) : NaN;
  if (!Number.isFinite(ms) || new Date(ms).toISOString().slice(0, 10) !== s) throw new AdminRefusal(`${flag} must be a day, YYYY-MM-DD.\n${JUDGE_USAGE}`);
  return ms;
}

export function parseJudgeArgs(argv: string[], nowMs: number, defaultProject: string): JudgeArgs {
  let parsed;
  try {
    parsed = parseArgs({ args: argv, options: { from: { type: "string" }, to: { type: "string" }, out: { type: "string" }, project: { type: "string" } }, allowPositionals: true, strict: true });
  } catch (e) {
    throw new AdminRefusal(`${(e as Error).message}\n${JUDGE_USAGE}`);
  }
  const { values, positionals } = parsed;
  const command = positionals[1];
  if (positionals[0] !== "judge" || (command !== "summary" && command !== "export") || positionals.length !== 2) throw new AdminRefusal(JUDGE_USAGE);
  if (values.out !== undefined && command !== "export") throw new AdminRefusal("--out belongs to judge export.");
  const today = Math.floor(nowMs / DAY_MS) * DAY_MS;
  const toMs = values.to !== undefined ? dayStart(values.to, "--to") + DAY_MS : today + DAY_MS;
  const fromMs = values.from !== undefined ? dayStart(values.from, "--from") : toMs - 30 * DAY_MS;
  if (fromMs >= toMs) throw new AdminRefusal("--from must not be after --to.");
  return { command, project: values.project ?? defaultProject, fromMs, toMs, ...(values.out !== undefined ? { out: values.out } : {}) };
}

/** The answers written in [fromMs, toMs), oldest first; one range query on `at`, in pages. */
export async function readJudgeAnswers(db: Firestore, fromMs: number, toMs: number, max = JUDGE_READ_MAX): Promise<{ rows: JudgeRow[]; truncated: boolean }> {
  const rows: JudgeRow[] = [];
  let q = db.collection(JUDGE_LOG.collection)
    .where("at", ">=", Timestamp.fromMillis(fromMs))
    .where("at", "<", Timestamp.fromMillis(toMs))
    .orderBy("at")
    .limit(500);
  for (;;) {
    const snap = await q.get();
    for (const d of snap.docs) {
      const data = { ...d.data() };
      delete data.expiresAt;
      const at = data.at instanceof Timestamp ? data.at.toDate().toISOString() : String(data.at);
      rows.push({ ...data, at });
      if (rows.length >= max) return { rows, truncated: true };
    }
    if (snap.size < 500) return { rows, truncated: false };
    q = q.startAfter(snap.docs[snap.docs.length - 1]);
  }
}

const share = (n: number, total: number) => (total ? `${n} (${((100 * n) / total).toFixed(1)}%)` : String(n));

/** The summary as printed: counts and codes only, never a note. */
export function renderJudgeSummary(s: JudgeSummary, args: Pick<JudgeArgs, "fromMs" | "toMs">, truncated = false): string[] {
  const day = (ms: number) => new Date(ms).toISOString().slice(0, 10);
  const lines = [`Judge answers ${day(args.fromMs)} to ${day(args.toMs - DAY_MS)} (UTC): ${s.total}${truncated ? ` (stopped at ${JUDGE_READ_MAX}; narrow the days)` : ""}`];
  if (!s.total) return lines;
  const list = (m: Record<string, number>) => Object.entries(m).filter(([, n]) => n > 0).sort((a, b) => b[1] - a[1]).map(([k, n]) => `${k} ${share(n, s.total)}`).join(", ") || "none";
  lines.push(`Verdicts: ${list(s.verdicts)}`);
  lines.push(`Issues (answers naming each): ${list(s.issues)}`);
  lines.push(`Where: ${list(s.where)}`);
  lines.push(`Quality: ${["1", "2", "3", "4", "5", "none"].map((k) => `${k}: ${s.quality[k] ?? 0}`).join("  ")}`);
  if (s.latencyMs) lines.push(`Latency ms: p50 ${s.latencyMs.p50}, p95 ${s.latencyMs.p95}, max ${s.latencyMs.max}`);
  if (s.tokens) lines.push(`Tokens per answer (mean): input ${s.tokens.input}, output ${s.tokens.output}, thinking ${s.tokens.thinking}`);
  for (const g of GROUPS) {
    const m = s.by[g];
    if (!m) continue;
    lines.push("", `By ${g}:`);
    for (const [k, e] of Object.entries(m).sort((a, b) => b[1].total - a[1].total).slice(0, 20)) {
      lines.push(`  ${k.padEnd(24)} ${String(e.total).padStart(6)}  not ok ${String(e.notOk).padStart(5)}  failed ${String(e.failed).padStart(5)}  quality avg ${e.qualityAvg ?? "-"}  issues ${e.issues}`);
    }
  }
  return lines;
}

export interface JudgeEnv {
  db: Firestore;
  now(): number;
  print(line: string): void;
  write?(path: string, text: string): void;
}

export async function runJudgeAnswers(args: JudgeArgs, env: JudgeEnv): Promise<{ rows: number }> {
  const { rows, truncated } = await readJudgeAnswers(env.db, args.fromMs, args.toMs);
  if (args.command === "summary") {
    for (const l of renderJudgeSummary(summariseJudgeAnswers(rows), args, truncated)) env.print(l);
    return { rows: rows.length };
  }
  const text = rows.map((r) => JSON.stringify(r)).join("\n") + (rows.length ? "\n" : "");
  if (args.out) {
    (env.write ?? ((p, t) => writeFileSync(p, t)))(args.out, text);
    env.print(`${rows.length} answers written to ${args.out}${truncated ? ` (stopped at ${JUDGE_READ_MAX}; narrow the days)` : ""}`);
  } else {
    env.print(text.trimEnd());
  }
  return { rows: rows.length };
}
