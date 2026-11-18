/**
 * Every answer of the output judge, kept for the owner to analyse and improve the recipe writer's
 * prompt (README "Prompt injection defences" item 5, "What is kept of the judge's answers").
 *
 * One `judgeAnswers/{random id}` document per judged generation, shadow and enforce, ok or not,
 * and judge failures with their reason. No uid, hashed or not, and no user input: the request's
 * kind, menu shape, picture level and language, who wrote the answer and who judged it, the
 * prompts' versions, the verdict and its description, latency and tokens. The judged text only
 * while config/runtime `outputJudgeKeepText` is on (off by default: the privacy policy says a
 * result is kept 7 days). Deleted by the TTL policy on `expiresAt`, JUDGE_LOG.keepDays (90) after
 * it was written. Clients can't read it (firestore.rules denies everything).
 *
 * Written after the verdict, never awaited by the request: a failed or slow write costs the user
 * nothing (logged as `judge_log_failed`), and it reads nothing.
 */
import { Timestamp, type Firestore } from "firebase-admin/firestore";
import { JUDGE, JUDGE_LOG } from "../constants";
import { JUDGE_ISSUES, JUDGE_WHERE, type JudgeEntry, type JudgeLog } from "../ports";
import { cleanReportText } from "./reports";
import { redactLinks } from "./outputSafety";

/** The judge's note as kept: control characters out, links and numbers redacted, cut to JUDGE.noteChars. */
export function cleanJudgeNote(note: string | undefined): string | null {
  if (!note) return null;
  const s = cleanReportText(redactLinks(cleanReportText(note, 4 * JUDGE.noteChars)), JUDGE.noteChars);
  return s || null;
}

export class FirestoreJudgeLog implements JudgeLog {
  private readonly inFlight = new Set<Promise<unknown>>();

  constructor(private readonly db: Firestore, private readonly now: () => number) {}

  record(entry: JudgeEntry): Promise<void> {
    const at = this.now();
    const doc = {
      ...entry,
      ...(entry.text !== undefined ? { text: entry.text.slice(0, JUDGE_LOG.textMaxChars) } : {}),
      at: Timestamp.fromMillis(at),
      expiresAt: Timestamp.fromMillis(at + JUDGE_LOG.keepDays * 24 * 60 * 60 * 1000),
    };
    const p = this.db.collection(JUDGE_LOG.collection).doc().set(doc).then(() => undefined);
    this.inFlight.add(p);
    void p.finally(() => this.inFlight.delete(p)).catch(() => undefined);
    return p;
  }

  /** Waits for the writes still in flight (tests). */
  async settled(): Promise<void> {
    await Promise.allSettled([...this.inFlight]);
  }
}

// --- Counting them (admin judge summary, the weekly prompt review) -------------------------------

/** One stored answer as read back: the document's fields, `at` as an ISO time, without `expiresAt`. */
export type JudgeRow = Record<string, unknown> & { at: string };

export interface JudgeSummary {
  total: number;
  verdicts: Record<string, number>;
  issues: Record<string, number>;
  where: Record<string, number>;
  quality: Record<string, number>;
  latencyMs: { p50: number; p95: number; max: number } | null;
  tokens: { input: number; output: number; thinking: number } | null;
  by: Record<string, Record<string, { total: number; notOk: number; failed: number; qualityAvg: number | null; issues: number }>>;
}

export const JUDGE_SUMMARY_GROUPS = ["language", "judgeModel", "judgeVersion", "promptHash", "textModel", "mode", "kind"] as const;

const bump = (m: Record<string, number>, k: string, n = 1) => {
  m[k] = (m[k] ?? 0) + n;
};

function pct(sorted: number[], p: number): number {
  return sorted[Math.min(sorted.length - 1, Math.max(0, Math.ceil(p * sorted.length) - 1))];
}

export function summariseJudgeAnswers(rows: readonly JudgeRow[]): JudgeSummary {
  const verdicts: Record<string, number> = {};
  const issues: Record<string, number> = Object.fromEntries(JUDGE_ISSUES.map((i) => [i, 0]));
  const where: Record<string, number> = {};
  const quality: Record<string, number> = { 1: 0, 2: 0, 3: 0, 4: 0, 5: 0, none: 0 };
  const latencies: number[] = [];
  const tokens = { input: 0, output: 0, thinking: 0 };
  let withTokens = 0;
  const groups: Record<string, Record<string, { total: number; notOk: number; failed: number; qSum: number; qN: number; issues: number }>> = {};
  for (const r of rows) {
    const verdict = typeof r.verdict === "string" ? r.verdict : "failed";
    bump(verdicts, verdict);
    const is = Array.isArray(r.issues) ? r.issues.filter((i): i is string => typeof i === "string") : [];
    for (const i of is) bump(issues, i);
    if (typeof r.where === "string" && (JUDGE_WHERE as readonly string[]).includes(r.where)) bump(where, r.where);
    const q = typeof r.quality === "number" && Number.isInteger(r.quality) && r.quality >= 1 && r.quality <= 5 ? r.quality : null;
    bump(quality, q === null ? "none" : String(q));
    if (typeof r.latencyMs === "number") latencies.push(r.latencyMs);
    const u = r.usage as { inputTokens?: number; outputTokens?: number; thinkingTokens?: number } | null | undefined;
    if (u) {
      withTokens++;
      tokens.input += u.inputTokens ?? 0;
      tokens.output += u.outputTokens ?? 0;
      tokens.thinking += u.thinkingTokens ?? 0;
    }
    for (const g of JUDGE_SUMMARY_GROUPS) {
      const key = r[g] === undefined || r[g] === null ? "-" : String(r[g]);
      const m = (groups[g] ??= {});
      const e = (m[key] ??= { total: 0, notOk: 0, failed: 0, qSum: 0, qN: 0, issues: 0 });
      e.total++;
      if (verdict === "failed") e.failed++;
      else if (verdict !== "ok") e.notOk++;
      if (q !== null) {
        e.qSum += q;
        e.qN++;
      }
      e.issues += is.length;
    }
  }
  latencies.sort((a, b) => a - b);
  const by: JudgeSummary["by"] = {};
  for (const [g, m] of Object.entries(groups)) {
    by[g] = Object.fromEntries(Object.entries(m).map(([k, e]) => [k, { total: e.total, notOk: e.notOk, failed: e.failed, qualityAvg: e.qN ? Math.round((e.qSum / e.qN) * 100) / 100 : null, issues: e.issues }]));
  }
  return {
    total: rows.length,
    verdicts,
    issues,
    where,
    quality,
    latencyMs: latencies.length ? { p50: pct(latencies, 0.5), p95: pct(latencies, 0.95), max: latencies[latencies.length - 1] } : null,
    tokens: withTokens ? { input: Math.round(tokens.input / withTokens), output: Math.round(tokens.output / withTokens), thinking: Math.round(tokens.thinking / withTokens) } : null,
    by,
  };
}
