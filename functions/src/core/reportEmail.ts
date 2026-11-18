/**
 * Sending the owner's report emails, each exactly once (README "Report emails").
 *
 * The weekly app report (weeklyReports/{week}) and the weekly prompt review (promptReviews/{week})
 * are made and stored first; only then is each emailed, by the Monday job after both have finished
 * (runWeeklyJobs), or by hand with `npm run admin -- report weekly --send` / `judge review --send`.
 *
 * **Once.** A send is claimed in a transaction on the stored document (`email`, core/emailState.ts)
 * before anything goes out: a rerun, or a run racing this one, finds the claim and sends nothing.
 * The outcome is written after the send: `sent`, or `failed` with a fixed reason, which a later
 * run or the admin command may send again. A claim never followed by an outcome (the instance
 * died mid-send), and a failure that may have gone out anyway (`maybeSent`), are never sent again
 * by themselves: only by hand with --force. So at most one email per report, unless the owner
 * forces a second.
 *
 * **One email in an empty week.** A prompt review that found too few answers (`not_enough_data`)
 * is not emailed on its own: its one-paragraph note goes into the weekly report's email, and the
 * review's document is claimed in the same transaction (`via: "weekly_report"`). Only when there is
 * no weekly report to carry it (the report off or failed) is that note sent as a short email of its
 * own. A review that was skipped or failed gets a line in the weekly report's email (its failure is
 * alerted on as before) and no email of its own.
 *
 * **Never the address.** The mailer holds it (the secret REPORT_EMAIL); nothing here sees it, and no
 * log line or stored field carries it, nor any SMTP reply. A missing or empty secret is
 * `NOT_CONFIGURED`: the reports are still made and stored, and `report_email_failed` is logged
 * (alerted on: functions/alerts/policies/report-email-failed.json).
 */
import { randomUUID } from "node:crypto";
import { Timestamp, type DocumentReference, type Firestore } from "firebase-admin/firestore";
import { PROMPT_REVIEW, REPORT_EMAIL, WEEKLY_REPORT } from "../constants";
import { ownerConsoleUrl } from "../board/boardData";
import { MailError, type Logger, type Mailer, type ReportMail } from "../ports";
import { claimabilityOf, emailRecordOf, type Claimability, type EmailRecord, type EmailReport } from "./emailState";
import { reviewRef } from "./promptReview";
import { renderPromptReviewEmail, renderWeeklyReportEmail, type ReviewNote } from "./reportEmailRender";
import { weeklyReportRef } from "./weeklyReport";

export interface ReportEmailEnv {
  db: Firestore;
  logger: Logger;
  now(): number;
  project: string;
  /** null: the secrets are missing or empty ("not configured"). */
  mailer: Mailer | null;
}

/** WITH_WEEKLY_REPORT: an empty week's review note, which goes (or went) with the weekly report's email. */
export type EmailSkip = "NOTHING_TO_SEND" | "ALREADY_SENT" | "SENDING" | "UNCERTAIN" | "WITH_WEEKLY_REPORT";

export type EmailOutcome =
  | { report: EmailReport; week: string; status: "sent"; combined: boolean; subject: string }
  | { report: EmailReport; week: string; status: "skipped"; reason: EmailSkip }
  | { report: EmailReport; week: string; status: "failed"; reason: string; maybeSent: boolean };

const SKIP: Record<Exclude<Claimability, "ok">, EmailSkip> = { sent: "ALREADY_SENT", sending: "SENDING", uncertain: "UNCERTAIN" };

interface Opts {
  week: string;
  trigger: "schedule" | "cli";
  /** The admin command's --force: a claim older than REPORT_EMAIL.staleSendingMs, or a send that may have gone out, is sent again. */
  force?: boolean;
}

function newClaim(nowMs: number, trigger: string, attempts: number, via: EmailReport): EmailRecord {
  return {
    state: "sending",
    claimId: randomUUID(),
    claimedAt: Timestamp.fromMillis(nowMs),
    attempts: attempts + 1,
    trigger,
    via,
    sentAt: null,
    failedAt: null,
    reason: null,
    maybeSent: false,
  };
}

/** Writes the outcome on every claimed document; a document deleted since is left deleted. */
async function record(refs: readonly DocumentReference[], fields: Record<string, unknown>): Promise<boolean> {
  let ok = true;
  for (const ref of refs) {
    try {
      await ref.update(Object.fromEntries(Object.entries(fields).map(([k, v]) => ["email." + k, v])));
    } catch (e) {
      if ((e as { code?: unknown }).code !== 5) ok = false;
    }
  }
  return ok;
}

/**
 * Sends one claimed email and records the outcome on every claimed document. Never throws.
 * `render` runs after the claim (a document it cannot read is RENDER_FAILED, recorded like a failure).
 */
async function deliver(
  env: ReportEmailEnv,
  o: Opts & { report: EmailReport; refs: readonly DocumentReference[]; combined: boolean; render: () => Promise<ReportMail> },
): Promise<EmailOutcome> {
  const base = { report: o.report, week: o.week, trigger: o.trigger };
  const failed = async (reason: string, maybeSent: boolean): Promise<EmailOutcome> => {
    const recorded = await record(o.refs, { state: "failed", failedAt: Timestamp.fromMillis(env.now()), reason, maybeSent });
    // No address, no SMTP reply, no error text: the fixed reason only.
    env.logger.error("report_email_failed", { ...base, reason, maybeSent, recorded });
    return { report: o.report, week: o.week, status: "failed", reason, maybeSent };
  };
  let mail: ReportMail;
  try {
    mail = await o.render();
  } catch {
    return failed("RENDER_FAILED", false);
  }
  if (!env.mailer) return failed("NOT_CONFIGURED", false);
  try {
    await env.mailer.send(mail);
  } catch (e) {
    // A mailer that throws something else may have sent: treated as maybe.
    return e instanceof MailError ? failed(e.reason, e.maybeSent) : failed("ERROR", true);
  }
  const recorded = await record(o.refs, { state: "sent", sentAt: Timestamp.fromMillis(env.now()), reason: null, maybeSent: false });
  env.logger.info("report_email_sent", { ...base, combined: o.combined, recorded });
  return { report: o.report, week: o.week, status: "sent", combined: o.combined, subject: mail.subject };
}

function skipped(env: ReportEmailEnv, report: EmailReport, o: Opts, reason: EmailSkip): EmailOutcome {
  env.logger.info("report_email_skipped", { report, week: o.week, trigger: o.trigger, reason });
  return { report, week: o.week, status: "skipped", reason };
}

/** The review's note for the weekly report's email, from its stored document; null when it has none. */
export function reviewNoteOf(doc: Record<string, unknown> | undefined): ReviewNote | null {
  if (!doc || !["not_enough_data", "skipped", "failed"].includes(String(doc.status))) return null;
  return {
    status: String(doc.status),
    reason: typeof doc.reason === "string" ? doc.reason : null,
    answers: typeof doc.answers === "number" ? doc.answers : null,
    minAnswers: typeof doc.minAnswers === "number" ? doc.minAnswers : PROMPT_REVIEW.minAnswers,
  };
}

/** The stored reports up to `week`, newest first (the trend table). */
export async function readReportHistory(db: Firestore, week: string): Promise<Record<string, unknown>[]> {
  const snap = await db.collection(WEEKLY_REPORT.collection).where("week", "<=", week).orderBy("week", "desc").limit(REPORT_EMAIL.historyWeeks).get();
  return snap.docs.map((d) => d.data());
}

/** The weekly app report's email, built from what is stored (also the admin command's preview). */
export async function weeklyReportMail(db: Firestore, project: string, doc: Record<string, unknown>, review: ReviewNote | null): Promise<ReportMail> {
  const week = String(doc.week);
  let history: Record<string, unknown>[] = [doc];
  try {
    history = await readReportHistory(db, week);
    if (!history.length || history[0].week !== week) history = [doc, ...history];
  } catch {
    // The trend is a nicety: without it the email still goes.
  }
  return renderWeeklyReportEmail({ doc, history, review, consoleUrl: ownerConsoleUrl(project), project });
}

/**
 * The weekly app report's email for `week`, once. A prompt review of the same week that had too
 * few answers goes into it as a note (and is claimed with it); a skipped or failed one gets a line.
 */
export async function sendWeeklyReportEmail(env: ReportEmailEnv, o: Opts): Promise<EmailOutcome> {
  const ref = weeklyReportRef(env.db, o.week);
  const rref = reviewRef(env.db, o.week);
  const nowMs = env.now();
  type Claim = { ok: false; reason: EmailSkip } | { ok: true; doc: Record<string, unknown>; note: ReviewNote | null; noteClaimed: boolean };
  let claim: Claim;
  try {
    claim = await env.db.runTransaction(async (tx): Promise<Claim> => {
      const [s, rs] = await tx.getAll(ref, rref);
      const doc = s.data();
      if (!doc) return { ok: false, reason: "NOTHING_TO_SEND" };
      const prev = emailRecordOf(doc);
      const c = claimabilityOf(prev, nowMs, o.force === true);
      if (c !== "ok") return { ok: false, reason: SKIP[c] };
      const rec = newClaim(nowMs, o.trigger, prev?.attempts ?? 0, "weekly_report");
      tx.update(ref, { email: rec });
      const rdoc = rs.data();
      const note = reviewNoteOf(rdoc);
      let noteClaimed = false;
      if (note?.status === "not_enough_data") {
        const rprev = emailRecordOf(rdoc);
        if (claimabilityOf(rprev, nowMs, o.force === true) === "ok") {
          tx.update(rref, { email: { ...rec, attempts: (rprev?.attempts ?? 0) + 1 } });
          noteClaimed = true;
        }
      }
      // A not-enough-data note already sent (on its own, or with an earlier email) is not repeated.
      return { ok: true, doc, note: note?.status === "not_enough_data" && !noteClaimed ? null : note, noteClaimed };
    });
  } catch {
    env.logger.error("report_email_failed", { report: "weekly_report", week: o.week, trigger: o.trigger, reason: "CLAIM_FAILED", maybeSent: false, recorded: false });
    return { report: "weekly_report", week: o.week, status: "failed", reason: "CLAIM_FAILED", maybeSent: false };
  }
  if (!claim.ok) return skipped(env, "weekly_report", o, claim.reason);
  const { doc, note, noteClaimed } = claim;
  return deliver(env, {
    ...o,
    report: "weekly_report",
    refs: noteClaimed ? [ref, rref] : [ref],
    combined: noteClaimed,
    render: () => weeklyReportMail(env.db, env.project, doc, note),
  });
}

/**
 * The weekly prompt review's email for `week`, once: a review that is done gets its own email;
 * one with too few answers only when its note did not go in the weekly report's email (it is then
 * already recorded as sent). Skipped, failed or running reviews have no email.
 */
export async function sendPromptReviewEmail(env: ReportEmailEnv, o: Opts): Promise<EmailOutcome> {
  const ref = reviewRef(env.db, o.week);
  const nowMs = env.now();
  type Claim = { ok: false; reason: EmailSkip } | { ok: true; doc: Record<string, unknown> };
  let claim: Claim;
  try {
    claim = await env.db.runTransaction(async (tx): Promise<Claim> => {
      const doc = (await tx.get(ref)).data();
      if (!doc || (doc.status !== "done" && doc.status !== "not_enough_data")) return { ok: false, reason: "NOTHING_TO_SEND" };
      const prev = emailRecordOf(doc);
      const c = claimabilityOf(prev, nowMs, o.force === true);
      if (c !== "ok") return { ok: false, reason: SKIP[c] };
      // An empty week's note that was to go with the weekly report's email is resent with it,
      // never on its own as well (unless forced by hand).
      if (doc.status === "not_enough_data" && prev?.via === "weekly_report" && o.force !== true) return { ok: false, reason: "WITH_WEEKLY_REPORT" };
      tx.update(ref, { email: newClaim(nowMs, o.trigger, prev?.attempts ?? 0, "prompt_review") });
      return { ok: true, doc };
    });
  } catch {
    env.logger.error("report_email_failed", { report: "prompt_review", week: o.week, trigger: o.trigger, reason: "CLAIM_FAILED", maybeSent: false, recorded: false });
    return { report: "prompt_review", week: o.week, status: "failed", reason: "CLAIM_FAILED", maybeSent: false };
  }
  if (!claim.ok) return skipped(env, "prompt_review", o, claim.reason);
  const doc = claim.doc;
  return deliver(env, {
    ...o,
    report: "prompt_review",
    refs: [ref],
    combined: false,
    render: async () => renderPromptReviewEmail({ doc, consoleUrl: ownerConsoleUrl(env.project), project: env.project }),
  });
}

/**
 * The Monday job's emails, after both reports are stored: the weekly report's first (so an empty
 * week's review note goes in it), then the review's. A report turned off is not emailed even when
 * one is stored. Never throws.
 */
export async function sendWeeklyEmails(env: ReportEmailEnv, o: Opts & { weeklyReportEnabled: boolean; promptReviewEnabled: boolean }): Promise<EmailOutcome[]> {
  const out: EmailOutcome[] = [];
  if (o.weeklyReportEnabled) out.push(await sendWeeklyReportEmail(env, o));
  if (o.promptReviewEnabled) out.push(await sendPromptReviewEmail(env, o));
  return out;
}
