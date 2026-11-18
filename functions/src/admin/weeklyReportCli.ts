/**
 * The owner's command for the weekly app report (core/weeklyReport.ts, README "Weekly app report"):
 *
 *   npm run admin -- report weekly [--week YYYY-Www] [--project <id>]                  the stored report (default: the latest)
 *   npm run admin -- report weekly --run [--week YYYY-Www] [--project <id>]            dry run: works it out and prints it, writes nothing
 *   npm run admin -- report weekly --run --apply [--week YYYY-Www] [--project <id>]    stores weeklyReports/{week} if it is not there yet
 *   npm run admin -- report weekly --send [--to-self <file.html>] [--week YYYY-Www]     the email: its state, and a preview written to a file
 *   npm run admin -- report weekly --send --apply [--force] [--week YYYY-Www]           sends it if it was not sent (admin/reportEmailCli.ts)
 *
 * The default week of --run and --send is the last complete ISO week (the one Monday's run
 * reports). No model call: Firestore reads only. A week already stored is left alone (idempotent).
 * Storing a report sends no email; the Monday job emails it, or --send --apply (once per report:
 * core/reportEmail.ts). The command logs to stderr, never to Cloud Logging.
 */
import { parseArgs } from "node:util";
import type { Firestore } from "firebase-admin/firestore";
import { WEEKLY_REPORT } from "../constants";
import { emailRecordOf, emailStatusText } from "../core/emailState";
import { lastCompleteWeek, reviewRef, weekRange } from "../core/promptReview";
import { reviewNoteOf, sendWeeklyReportEmail, weeklyReportMail } from "../core/reportEmail";
import { renderWeeklyReport, runWeeklyReport, weeklyReportRef, weeklyReportViewOf, type WeeklyReportOutcome } from "../core/weeklyReport";
import type { RuntimeConfig } from "../model";
import type { Logger, Mailer } from "../ports";
import { AdminRefusal } from "./accountRequests";
import { outcomeLine, writePreview } from "./reportEmailCli";

export const WEEKLY_USAGE = `usage:
  npm run admin -- report weekly [--week YYYY-Www] [--project <id>]                 the stored report (default: the latest)
  npm run admin -- report weekly --run [--week YYYY-Www] [--project <id>]           dry run: work it out and print it
  npm run admin -- report weekly --run --apply [--week YYYY-Www] [--project <id>]   store it (no email: the Monday run sends that)
  npm run admin -- report weekly --send [--to-self <file.html>] [--week YYYY-Www] [--project <id>]   the email's state; --to-self writes a preview
  npm run admin -- report weekly --send --apply [--force] [--week YYYY-Www] [--project <id>]          send it now if not sent (live: REPORT_EMAIL, REPORT_SMTP_PASSWORD)
No model call: Firestore reads only.`;

export interface WeeklyArgs {
  project: string;
  week?: string;
  run: boolean;
  apply: boolean;
  send?: boolean;
  toSelf?: string;
  force?: boolean;
}

/** --send, --to-self and --force, checked the same way for both reports' commands. */
export function sendFlags(values: { run?: boolean; apply?: boolean; send?: boolean; "to-self"?: string; force?: boolean }, usage: string): Pick<WeeklyArgs, "send" | "toSelf" | "force"> {
  if (values.send && values.run) throw new AdminRefusal("--send and --run are separate steps: store with --run --apply, then --send.\n" + usage);
  if (values["to-self"] !== undefined && !values.send) throw new AdminRefusal("--to-self goes with --send.\n" + usage);
  if (values["to-self"] !== undefined && values.apply) throw new AdminRefusal("--to-self is the preview; it does not go with --apply.\n" + usage);
  if (values.force && !(values.send && values.apply)) throw new AdminRefusal("--force goes with --send --apply.\n" + usage);
  return {
    ...(values.send ? { send: true } : {}),
    ...(values["to-self"] !== undefined ? { toSelf: values["to-self"] } : {}),
    ...(values.force ? { force: true } : {}),
  };
}

export const SEND_OPTIONS = { send: { type: "boolean" }, "to-self": { type: "string" }, force: { type: "boolean" } } as const;

export function parseWeeklyArgs(argv: string[], defaultProject: string): WeeklyArgs {
  let parsed;
  try {
    parsed = parseArgs({ args: argv, options: { week: { type: "string" }, run: { type: "boolean" }, apply: { type: "boolean" }, project: { type: "string" }, ...SEND_OPTIONS }, allowPositionals: true, strict: true });
  } catch (e) {
    throw new AdminRefusal((e as Error).message + "\n" + WEEKLY_USAGE);
  }
  const { values, positionals } = parsed;
  if (positionals[0] !== "report" || positionals[1] !== "weekly" || positionals.length !== 2) throw new AdminRefusal(WEEKLY_USAGE);
  const send = sendFlags(values, WEEKLY_USAGE);
  if (values.apply && !values.run && !values.send) throw new AdminRefusal("--apply goes with --run or --send.\n" + WEEKLY_USAGE);
  if (values.week !== undefined && !weekRange(values.week)) throw new AdminRefusal("--week must be an ISO week, YYYY-Www (e.g. 2026-W46).\n" + WEEKLY_USAGE);
  return {
    project: values.project ?? defaultProject,
    ...(values.week !== undefined ? { week: values.week } : {}),
    run: values.run === true,
    apply: values.apply === true,
    ...send,
  };
}

export interface WeeklyCommandEnv {
  db: Firestore;
  now(): number;
  print(line: string): void;
  logger: Logger;
  config(): Promise<RuntimeConfig>;
  /** The mailer for --send --apply (the emulators' recorder, or Gmail with the secrets from the environment); built on first use. */
  mailer(): Mailer;
}

function printDoc(p: (l: string) => void, d: Record<string, unknown>): void {
  const v = weeklyReportViewOf(d);
  if (!v) {
    p("The stored report for " + String(d.week) + " has no figures this version can read.");
    return;
  }
  for (const l of renderWeeklyReport(v)) p(l);
}

async function runWeeklySend(args: WeeklyArgs, env: WeeklyCommandEnv): Promise<null> {
  const p = env.print;
  const week = args.week ?? lastCompleteWeek(env.now());
  if (args.apply) {
    const mailer = env.mailer();
    const out = await sendWeeklyReportEmail({ db: env.db, logger: env.logger, now: env.now, project: args.project, mailer }, { week, trigger: "cli", force: args.force === true });
    p(outcomeLine(out, args.project.startsWith("demo-")));
    return null;
  }
  const [doc, review] = await Promise.all([weeklyReportRef(env.db, week).get().then((s) => s.data()), reviewRef(env.db, week).get().then((s) => s.data())]);
  if (!doc) {
    p("No weekly report stored for " + week + ": nothing to send. Store it first: npm run admin -- report weekly --run --apply --week " + week + ".");
    return null;
  }
  const note = reviewNoteOf(review);
  const mail = await weeklyReportMail(env.db, args.project, doc, note);
  p("Dry run for " + week + "'s email, nothing sent or written.");
  p("  State: " + (emailStatusText(emailRecordOf(doc), "weekly_report") ?? "not emailed yet."));
  p("  Subject: " + mail.subject);
  if (note) p("  With the prompt review's note (" + note.status + ")" + (note.status === "not_enough_data" && emailRecordOf(review)?.state === "sent" ? ", unless it was sent already" : "") + ".");
  if (args.toSelf) {
    const w = writePreview(args.toSelf, mail);
    p("  Wrote " + w.html + " and " + w.text + ".");
  }
  p("To send it (once): the same command with --apply instead of --to-self" + (args.project.startsWith("demo-") ? "" : " --project " + args.project + ", with REPORT_EMAIL and REPORT_SMTP_PASSWORD set") + ".");
  return null;
}

export async function runWeeklyCommand(args: WeeklyArgs, env: WeeklyCommandEnv): Promise<WeeklyReportOutcome | null> {
  const p = env.print;
  if (args.send) return runWeeklySend(args, env);
  if (!args.run) {
    const docs = args.week
      ? await weeklyReportRef(env.db, args.week).get().then((s) => (s.exists ? [s.data()!] : []))
      : await env.db.collection(WEEKLY_REPORT.collection).orderBy("week", "desc").limit(1).get().then((s) => s.docs.map((x) => x.data()));
    if (!docs.length) {
      p(args.week ? "No weekly report stored for " + args.week + "." : "No weekly report stored yet.");
      return null;
    }
    printDoc(p, docs[0]);
    return null;
  }
  const week = args.week ?? lastCompleteWeek(env.now());
  const runEnv = { db: env.db, logger: env.logger, now: env.now, config: env.config, project: args.project };
  if (!args.apply) {
    const [config, existing] = await Promise.all([env.config(), weeklyReportRef(env.db, week).get()]);
    const out = await runWeeklyReport(runEnv, { week, trigger: "cli", write: false });
    p("Dry run for " + week + ", nothing written." + (config.weeklyReportEnabled ? "" : " The report is turned off (weeklyReportEnabled): --apply would do nothing."));
    if (existing.exists) p("  Stored already: --apply would leave it alone.");
    if (out.status === "failed") {
      p("  Could not be worked out: " + out.reason + ".");
      return out;
    }
    if (out.status === "done") {
      printDoc(p, out.doc);
      p("The report in short lines (stored as `lines`):");
      for (const [k, v] of Object.entries(out.lines)) p("  " + k + ": " + v);
      p("  (reads: " + String((out.doc.figures as { reads?: unknown }).reads ?? "?") + " for the week" + ((out.doc.previous as { source?: string } | null)?.source === "computed" ? ", as many again for the week before, which has no stored report" : "") + ")");
      p("To store it: the same command with --apply" + (args.project.startsWith("demo-") ? "" : " --project " + args.project) + ".");
    }
    return out;
  }
  const out = await runWeeklyReport(runEnv, { week, trigger: "cli" });
  if (out.status === "off") {
    p("The weekly report is turned off (weeklyReportEnabled); nothing was read or written. Turn it on with: npm run admin -- config set weeklyReportEnabled true --apply");
    return out;
  }
  if (out.status === "failed") {
    p("The report for " + week + " could not be made: " + out.reason + ".");
    return out;
  }
  if (out.status === "already") p(week + " is already reported; the run is idempotent and left it alone:");
  else p("Stored weeklyReports/" + week + ". Not emailed: the Monday job sends it, or report weekly --send --apply.");
  printDoc(p, out.doc);
  return out;
}
