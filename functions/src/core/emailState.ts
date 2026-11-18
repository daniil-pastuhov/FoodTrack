/**
 * What the stored reports record about their email (README "Report emails"): one `email` map on
 * `weeklyReports/{week}` and `promptReviews/{week}`, written only in transactions
 * (core/reportEmail.ts), so a rerun or a racing run cannot send a report twice. It never holds an
 * address, a server reply or the message itself.
 *
 *   state "sending": claimed by a run that is sending it now, or that stopped before it could record
 *                    the outcome. Never sent again by itself; after REPORT_EMAIL.staleSendingMs the
 *                    admin command's --force may.
 *   state "sent":    sent (`sentAt`); never again. `via` "weekly_report" on a prompt review means
 *                    its short note went in the weekly report's email.
 *   state "failed":  not sent (`reason`); the next run or the admin command may send it. With
 *                    `maybeSent` (the connection broke while the message was being handed over) only
 *                    with --force, after a look in the inbox.
 */
import { Timestamp } from "firebase-admin/firestore";
import { REPORT_EMAIL } from "../constants";

export type EmailReport = "weekly_report" | "prompt_review";

export interface EmailRecord {
  state: "sending" | "sent" | "failed";
  claimId: string;
  claimedAt: Timestamp;
  attempts: number;
  trigger: string;
  /** The email it went in: its own, or (a prompt review's note) the weekly report's. */
  via: EmailReport;
  sentAt: Timestamp | null;
  failedAt: Timestamp | null;
  reason: string | null;
  maybeSent: boolean;
}

export function emailRecordOf(doc: Record<string, unknown> | undefined): EmailRecord | null {
  const e = doc?.email as Partial<EmailRecord> | undefined;
  if (!e || typeof e !== "object" || !["sending", "sent", "failed"].includes(String(e.state))) return null;
  return {
    state: e.state as EmailRecord["state"],
    claimId: typeof e.claimId === "string" ? e.claimId : "",
    claimedAt: e.claimedAt instanceof Timestamp ? e.claimedAt : Timestamp.fromMillis(0),
    attempts: typeof e.attempts === "number" ? e.attempts : 0,
    trigger: typeof e.trigger === "string" ? e.trigger : "",
    via: e.via === "weekly_report" ? "weekly_report" : "prompt_review",
    sentAt: e.sentAt instanceof Timestamp ? e.sentAt : null,
    failedAt: e.failedAt instanceof Timestamp ? e.failedAt : null,
    reason: typeof e.reason === "string" ? e.reason : null,
    maybeSent: e.maybeSent === true,
  };
}

/** Whether a run may claim the email now: "ok", or why not. */
export type Claimability = "ok" | "sent" | "sending" | "uncertain";

export function claimabilityOf(rec: EmailRecord | null, nowMs: number, force: boolean): Claimability {
  if (!rec) return "ok";
  if (rec.state === "sent") return "sent";
  if (rec.state === "sending") return force && nowMs - rec.claimedAt.toMillis() >= REPORT_EMAIL.staleSendingMs ? "ok" : "sending";
  if (rec.maybeSent) return force ? "ok" : "uncertain";
  return "ok";
}

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const when = (t: Timestamp | null) => {
  if (!t) return "?";
  const d = t.toDate();
  const hm = String(d.getUTCHours()).padStart(2, "0") + ":" + String(d.getUTCMinutes()).padStart(2, "0");
  return d.getUTCDate() + " " + MONTHS[d.getUTCMonth()] + " " + d.getUTCFullYear() + ", " + hm + " UTC";
};

/** One line for the console and the admin command; null when no email was tried yet. */
export function emailStatusText(rec: EmailRecord | null, report: EmailReport): string | null {
  if (!rec) return null;
  const cmd = report === "weekly_report" ? "npm run admin -- report weekly --send" : "npm run admin -- judge review --send";
  if (rec.state === "sent") return (rec.via === "weekly_report" && report === "prompt_review" ? "Noted in the weekly report's email, " : "Emailed ") + when(rec.sentAt) + ".";
  if (rec.state === "sending") return "Email started " + when(rec.claimedAt) + " and not recorded since: it may have gone out. Look in the inbox; " + cmd + " --apply --force sends it again.";
  if (rec.maybeSent) return "Email may have gone out (" + (rec.reason ?? "ERROR") + ", " + when(rec.failedAt) + "). Look in the inbox; " + cmd + " --apply --force sends it again.";
  return "Email not sent (" + (rec.reason ?? "ERROR") + ", " + when(rec.failedAt) + "); " + cmd + " --apply sends it.";
}
