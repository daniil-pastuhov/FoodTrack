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
import type { JudgeEntry, JudgeLog } from "../ports";
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
