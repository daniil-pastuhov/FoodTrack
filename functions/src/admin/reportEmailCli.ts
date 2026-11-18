/**
 * The report emails from the admin command (README "Report emails"):
 *
 *   npm run admin -- report weekly --send [--week YYYY-Www] [--to-self <file.html>] [--project <id>]   dry run: the email's state and subject; --to-self writes it to a file
 *   npm run admin -- report weekly --send --apply [--force] [--week YYYY-Www] [--project <id>]         send it now if it was not sent (once, like the Monday job)
 *   npm run admin -- judge review --send ...                                                          the same for the prompt review
 *   npm run admin -- report email-test [--to-self <file.html>] [--apply] [--project <id>]             both emails from made-up figures, subject "[test] …"; reads and writes no report
 *
 * A real send needs the two secrets' values in the environment, read from Secret Manager by the
 * operator (never stored, printed or logged here):
 *
 *   REPORT_EMAIL=$(gcloud secrets versions access latest --secret=REPORT_EMAIL --project=bestbefore-f9ee1) \
 *   REPORT_SMTP_PASSWORD=$(gcloud secrets versions access latest --secret=REPORT_SMTP_PASSWORD --project=bestbefore-f9ee1) \
 *     npm run admin -- report email-test --apply --project bestbefore-f9ee1
 *
 * Against the emulators nothing is sent: the message is only recorded and its subject printed.
 */
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { parseArgs } from "node:util";
import { gmailMailerOf } from "../adapters/gmailSmtp";
import { ownerConsoleUrl } from "../board/boardData";
import { RecordingMailer } from "../emulatorFakes";
import { MailError, type Mailer, type ReportMail } from "../ports";
import { lastCompleteWeek } from "../core/promptReview";
import { renderPromptReviewEmail, renderWeeklyReportEmail } from "../core/reportEmailRender";
import { sampleHistory, sampleReviewDoc } from "../core/reportEmailSamples";
import type { EmailOutcome } from "../core/reportEmail";
import { AdminRefusal } from "./accountRequests";

export const SECRETS_HINT = "set REPORT_EMAIL and REPORT_SMTP_PASSWORD from Secret Manager, e.g.\n"
  + "  REPORT_EMAIL=$(gcloud secrets versions access latest --secret=REPORT_EMAIL --project=<id>) \\\n"
  + "  REPORT_SMTP_PASSWORD=$(gcloud secrets versions access latest --secret=REPORT_SMTP_PASSWORD --project=<id>) \\\n"
  + "    npm run admin -- ...";

/** The mailer for a command: the emulators' recorder, or Gmail with the secrets from the environment. */
export function mailerFor(project: string, env: Record<string, string | undefined> = process.env): Mailer {
  if (project.startsWith("demo-")) return new RecordingMailer();
  const m = gmailMailerOf(env.REPORT_EMAIL, env.REPORT_SMTP_PASSWORD);
  if (!m) throw new AdminRefusal("A real send needs the owner's address and the Gmail app password: " + SECRETS_HINT);
  return m;
}

/** Writes the email's HTML to `path` and its plain text beside it (.txt); returns both paths. */
export function writePreview(path: string, mail: ReportMail): { html: string; text: string } {
  const html = resolve(path);
  const text = html.replace(/\.html?$/i, "") + ".txt";
  mkdirSync(dirname(html), { recursive: true });
  writeFileSync(html, mail.html);
  writeFileSync(text, "Subject: " + mail.subject + "\n\n" + mail.text + "\n");
  return { html, text };
}

/** One line for an outcome of a send. */
export function outcomeLine(o: EmailOutcome, demo: boolean): string {
  const what = o.report === "weekly_report" ? "The weekly report's email" : "The prompt review's email";
  if (o.status === "sent") return what + " for " + o.week + (demo ? " was recorded (emulators: nothing sent)" : " was sent") + (o.combined ? ", with the prompt review's note" : "") + ": " + o.subject;
  if (o.status === "failed") return what + " for " + o.week + " was not sent: " + o.reason + (o.maybeSent ? " (it may have gone out: look in the inbox before --force)" : "") + ".";
  const why: Record<typeof o.reason, string> = {
    NOTHING_TO_SEND: "there is nothing to send (no stored report, or a review with no email)",
    ALREADY_SENT: "it was sent already",
    SENDING: "a send is in progress or was interrupted (--force, ten minutes after it started, sends it again)",
    UNCERTAIN: "an earlier send may have gone out (look in the inbox; --force sends it again)",
    WITH_WEEKLY_REPORT: "its note goes with the weekly report's email (report weekly --send; --force sends it on its own)",
  };
  return what + " for " + o.week + ": not sent, " + why[o.reason] + ".";
}

export const EMAIL_TEST_USAGE = `usage:
  npm run admin -- report email-test [--to-self <file.html>] [--project <id>]   render both emails from made-up figures; --to-self writes them to files
  npm run admin -- report email-test --apply [--project <id>]                  send both to the owner, subject "[test] …" (live: REPORT_EMAIL, REPORT_SMTP_PASSWORD)
Reads and writes no report.`;

export interface EmailTestArgs {
  project: string;
  toSelf?: string;
  apply: boolean;
}

export function parseEmailTestArgs(argv: string[], defaultProject: string): EmailTestArgs {
  let parsed;
  try {
    parsed = parseArgs({ args: argv, options: { "to-self": { type: "string" }, apply: { type: "boolean" }, project: { type: "string" } }, allowPositionals: true, strict: true });
  } catch (e) {
    throw new AdminRefusal((e as Error).message + "\n" + EMAIL_TEST_USAGE);
  }
  const { values, positionals } = parsed;
  if (positionals[0] !== "report" || positionals[1] !== "email-test" || positionals.length !== 2) throw new AdminRefusal(EMAIL_TEST_USAGE);
  if (values.apply && values["to-self"] !== undefined) throw new AdminRefusal("--to-self is the preview; it does not go with --apply.\n" + EMAIL_TEST_USAGE);
  return { project: values.project ?? defaultProject, ...(values["to-self"] !== undefined ? { toSelf: values["to-self"] } : {}), apply: values.apply === true };
}

/** The made-up test emails (the weekly report with its trend, and a prompt review with advice). */
export function testMails(project: string, nowMs: number): ReportMail[] {
  const week = lastCompleteWeek(nowMs);
  const consoleUrl = ownerConsoleUrl(project);
  const history = sampleHistory(week);
  return [
    renderWeeklyReportEmail({ doc: history[0], history, review: null, consoleUrl, project, subjectPrefix: "[test] " }),
    renderPromptReviewEmail({ doc: sampleReviewDoc(week), consoleUrl, project, subjectPrefix: "[test] " }),
  ];
}

export async function runEmailTest(args: EmailTestArgs, env: { now(): number; print(l: string): void; mailer(): Mailer }): Promise<void> {
  const mails = testMails(args.project, env.now());
  if (!args.apply) {
    for (const m of mails) env.print("Would send: " + m.subject + " (" + m.html.length + " bytes of HTML)");
    if (args.toSelf) {
      const p = writePreview(args.toSelf, mails[0]);
      const q = writePreview(args.toSelf.replace(/\.html?$/i, "") + "-prompt-review.html", mails[1]);
      env.print("Wrote " + p.html + ", " + p.text + ", " + q.html + " and " + q.text + ".");
    }
    env.print("Made-up figures only; nothing was read, written or sent. To send them: the same command with --apply.");
    return;
  }
  const mailer = env.mailer();
  for (const m of mails) {
    try {
      await mailer.send(m);
      env.print((mailer instanceof RecordingMailer ? "Recorded (emulators: nothing sent): " : "Sent: ") + m.subject);
    } catch (e) {
      env.print("Not sent: " + m.subject + ": " + (e instanceof MailError ? e.reason + (e.maybeSent ? " (it may have gone out)" : "") : "ERROR"));
      return;
    }
  }
}
