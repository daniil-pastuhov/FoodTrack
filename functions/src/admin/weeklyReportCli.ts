/**
 * The owner's command for the weekly app report (core/weeklyReport.ts, README "Weekly app report"):
 *
 *   npm run admin -- report weekly [--week YYYY-Www] [--project <id>]                  the stored report (default: the latest)
 *   npm run admin -- report weekly --run [--week YYYY-Www] [--project <id>]            dry run: works it out and prints it, writes nothing
 *   npm run admin -- report weekly --run --apply [--week YYYY-Www] [--project <id>]    stores weeklyReports/{week} if it is not there yet
 *
 * The default week of --run is the last complete ISO week (the one Monday's run reports). No model
 * call: Firestore reads only. A week already stored is left alone (idempotent). The command logs to
 * stderr, never to Cloud Logging, so it sends no email: the email comes from the scheduled run.
 */
import { parseArgs } from "node:util";
import type { Firestore } from "firebase-admin/firestore";
import { WEEKLY_REPORT } from "../constants";
import { lastCompleteWeek, weekRange } from "../core/promptReview";
import { renderWeeklyReport, runWeeklyReport, weeklyReportRef, weeklyReportViewOf, type WeeklyReportOutcome } from "../core/weeklyReport";
import type { RuntimeConfig } from "../model";
import type { Logger } from "../ports";
import { AdminRefusal } from "./accountRequests";

export const WEEKLY_USAGE = `usage:
  npm run admin -- report weekly [--week YYYY-Www] [--project <id>]                 the stored report (default: the latest)
  npm run admin -- report weekly --run [--week YYYY-Www] [--project <id>]           dry run: work it out and print it
  npm run admin -- report weekly --run --apply [--week YYYY-Www] [--project <id>]   store it (no email: the Monday run sends that)
No model call: Firestore reads only.`;

export interface WeeklyArgs {
  project: string;
  week?: string;
  run: boolean;
  apply: boolean;
}

export function parseWeeklyArgs(argv: string[], defaultProject: string): WeeklyArgs {
  let parsed;
  try {
    parsed = parseArgs({ args: argv, options: { week: { type: "string" }, run: { type: "boolean" }, apply: { type: "boolean" }, project: { type: "string" } }, allowPositionals: true, strict: true });
  } catch (e) {
    throw new AdminRefusal((e as Error).message + "\n" + WEEKLY_USAGE);
  }
  const { values, positionals } = parsed;
  if (positionals[0] !== "report" || positionals[1] !== "weekly" || positionals.length !== 2) throw new AdminRefusal(WEEKLY_USAGE);
  if (values.apply && !values.run) throw new AdminRefusal("--apply goes with --run.\n" + WEEKLY_USAGE);
  if (values.week !== undefined && !weekRange(values.week)) throw new AdminRefusal("--week must be an ISO week, YYYY-Www (e.g. 2026-W46).\n" + WEEKLY_USAGE);
  return {
    project: values.project ?? defaultProject,
    ...(values.week !== undefined ? { week: values.week } : {}),
    run: values.run === true,
    apply: values.apply === true,
  };
}

export interface WeeklyCommandEnv {
  db: Firestore;
  now(): number;
  print(line: string): void;
  logger: Logger;
  config(): Promise<RuntimeConfig>;
}

function printDoc(p: (l: string) => void, d: Record<string, unknown>): void {
  const v = weeklyReportViewOf(d);
  if (!v) {
    p("The stored report for " + String(d.week) + " has no figures this version can read.");
    return;
  }
  for (const l of renderWeeklyReport(v)) p(l);
}

export async function runWeeklyCommand(args: WeeklyArgs, env: WeeklyCommandEnv): Promise<WeeklyReportOutcome | null> {
  const p = env.print;
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
      p("The email's fields (weekly_report_ready):");
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
  else p("Stored weeklyReports/" + week + " (no email: only the scheduled run's log line is alerted on).");
  printDoc(p, out.doc);
  return out;
}
