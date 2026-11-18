/**
 * The owner's command for the weekly prompt review (core/promptReview.ts, README "Weekly prompt
 * review"):
 *
 *   npm run admin -- judge review [--week YYYY-Www] [--project <id>]                  the stored report (default: the latest)
 *   npm run admin -- judge review --run [--week YYYY-Www] [--project <id>]            dry run: reads and counts, prints the plan and its worst case
 *   npm run admin -- judge review --run --apply [--week YYYY-Www] [--project <id>]    runs it: one model call, writes promptReviews/{week}
 *   npm run admin -- judge review --send [--to-self <file.html>] [--week YYYY-Www]     the email: its state, and a preview written to a file
 *   npm run admin -- judge review --send --apply [--force] [--week YYYY-Www]           sends it if it was not sent (admin/reportEmailCli.ts)
 *
 * The default week of --run is the last complete ISO week (the one Monday's run reviews). --apply
 * against a live project calls Vertex AI with the bearer token in VERTEX_TOKEN (e.g.
 * VERTEX_TOKEN=$(gcloud auth print-access-token)), as the red-team runner does; against the
 * emulators a fixed answer stands in for the model. A week already done is left alone (the run is
 * idempotent). Advice only: nothing here changes a prompt.
 */
import { parseArgs } from "node:util";
import type { Firestore } from "firebase-admin/firestore";
import { PROMPT_REVIEW } from "../constants";
import { emailRecordOf, emailStatusText } from "../core/emailState";
import { ownerConsoleUrl } from "../board/boardData";
import { sendPromptReviewEmail } from "../core/reportEmail";
import { renderPromptReviewEmail } from "../core/reportEmailRender";
import { SEND_OPTIONS, sendFlags } from "./weeklyReportCli";
import { outcomeLine, writePreview } from "./reportEmailCli";
import { lastCompleteWeek, planPromptReview, promptReviewViewOf, reviewRef, runPromptReview, weekRange, type PromptReviewEnv, type PromptReviewView, type ReviewOutcome, type ReviewProblem } from "../core/promptReview";
import type { RuntimeConfig } from "../model";
import type { Logger, Mailer, PromptReviewer } from "../ports";
import { AdminRefusal } from "./accountRequests";

export const REVIEW_USAGE = `usage:
  npm run admin -- judge review [--week YYYY-Www] [--project <id>]                 the stored report (default: the latest)
  npm run admin -- judge review --run [--week YYYY-Www] [--project <id>]           dry run: the plan and its worst case
  npm run admin -- judge review --run --apply [--week YYYY-Www] [--project <id>]   run it (one model call; live: VERTEX_TOKEN)
  npm run admin -- judge review --send [--to-self <file.html>] [--week YYYY-Www] [--project <id>]   the email's state; --to-self writes a preview
  npm run admin -- judge review --send --apply [--force] [--week YYYY-Www] [--project <id>]          send it now if not sent (live: REPORT_EMAIL, REPORT_SMTP_PASSWORD)
Advice only: nothing changes a prompt.`;

export interface ReviewArgs {
  project: string;
  week?: string;
  run: boolean;
  apply: boolean;
  send?: boolean;
  toSelf?: string;
  force?: boolean;
}

export function parseReviewArgs(argv: string[], defaultProject: string): ReviewArgs {
  let parsed;
  try {
    parsed = parseArgs({ args: argv, options: { week: { type: "string" }, run: { type: "boolean" }, apply: { type: "boolean" }, project: { type: "string" }, ...SEND_OPTIONS }, allowPositionals: true, strict: true });
  } catch (e) {
    throw new AdminRefusal((e as Error).message + "\n" + REVIEW_USAGE);
  }
  const { values, positionals } = parsed;
  if (positionals[0] !== "judge" || positionals[1] !== "review" || positionals.length !== 2) throw new AdminRefusal(REVIEW_USAGE);
  const send = sendFlags(values, REVIEW_USAGE);
  if (values.apply && !values.run && !values.send) throw new AdminRefusal("--apply goes with --run or --send.\n" + REVIEW_USAGE);
  if (values.week !== undefined && !weekRange(values.week)) throw new AdminRefusal("--week must be an ISO week, YYYY-Www (e.g. 2026-W46).\n" + REVIEW_USAGE);
  return {
    project: values.project ?? defaultProject,
    ...(values.week !== undefined ? { week: values.week } : {}),
    run: values.run === true,
    apply: values.apply === true,
    ...send,
  };
}

export interface ReviewCommandEnv {
  db: Firestore;
  now(): number;
  print(line: string): void;
  logger: Logger;
  config(): Promise<RuntimeConfig>;
  /** The model; only --run --apply calls it (built on first use, so a dry run needs no token). */
  reviewer(): PromptReviewer;
  reviewerModel: string;
  /** The mailer for --send --apply; built on first use. */
  mailer(): Mailer;
}

const usd = (micro: number) => "USD " + (micro / 1e6).toFixed(4);
const day = (iso: string | null | undefined) => (iso ? iso.slice(0, 10) : "?");

function problemLines(label: string, list: readonly ReviewProblem[]): string[] {
  if (!list.length) return [label + ": none"];
  const out = [label + ":"];
  list.forEach((p, i) => {
    out.push("  " + (i + 1) + ". " + p.title);
    out.push("     " + [p.issue, "target " + p.target, p.count + " answers", "confidence " + p.confidence].join(", "));
    if (p.cause) out.push("     Cause:  " + p.cause);
    out.push("     Before: " + JSON.stringify(p.before) + (p.beforeFound ? "" : "   (NOT found in the current prompt: check before applying)"));
    out.push("     After:  " + JSON.stringify(p.after));
    if (p.exampleIds.length) out.push("     Examples (judgeAnswers ids): " + p.exampleIds.join(", "));
  });
  return out;
}

/** A stored report as printed. Model text is printed as text (JSON-quoted where it is a proposal). */
export function renderReview(v: PromptReviewView, d: Record<string, unknown>): string[] {
  const lines = ["Weekly prompt review " + v.week + " (" + day(d.from as string) + " to " + day(new Date(Date.parse(String(d.to)) - 86_400_000).toISOString()) + ", UTC): " + v.status + (v.reason && v.status !== "done" ? " (" + v.reason + ")" : "")];
  const tokens = v.usage ? " (input " + v.usage.inputTokens + ", output " + v.usage.outputTokens + ", thinking " + v.usage.thinkingTokens + " tokens)" : "";
  lines.push("  answers " + (v.answers ?? "?") + (v.sampled !== null ? ", sampled " + v.sampled : "") + ", model " + (v.model ?? "?") + ", cost " + usd(v.costMicroUsd) + tokens + (v.finishedAt ? ", finished " + v.finishedAt : ""));
  if (v.email) lines.push("  " + v.email);
  if (v.status !== "done") return lines;
  lines.push("  Summary: " + (v.summary || "(none)"), "");
  lines.push(...problemLines("Prompt problems (the recipe writer's prompt)", v.promptProblems), "");
  lines.push(...problemLines("Judge problems (the second check is wrong)", v.judgeProblems), "");
  lines.push("Advice only: nothing was changed. Prompts are code (src/adapters/prompt.ts, src/adapters/gemini.ts JUDGE_INSTRUCTION).");
  return lines;
}

async function runReviewSend(args: ReviewArgs, env: ReviewCommandEnv): Promise<null> {
  const p = env.print;
  const week = args.week ?? lastCompleteWeek(env.now());
  if (args.apply) {
    const mailer = env.mailer();
    const out = await sendPromptReviewEmail({ db: env.db, logger: env.logger, now: env.now, project: args.project, mailer }, { week, trigger: "cli", force: args.force === true });
    p(outcomeLine(out, args.project.startsWith("demo-")));
    return null;
  }
  const doc = (await reviewRef(env.db, week).get()).data();
  if (!doc || (doc.status !== "done" && doc.status !== "not_enough_data")) {
    p("No prompt review with an email for " + week + (doc ? " (it is " + String(doc.status) + ")" : "") + ": nothing to send.");
    return null;
  }
  const mail = renderPromptReviewEmail({ doc, consoleUrl: ownerConsoleUrl(args.project), project: args.project });
  p("Dry run for " + week + "'s email, nothing sent or written.");
  p("  State: " + (emailStatusText(emailRecordOf(doc), "prompt_review") ?? "not emailed yet."));
  p("  Subject: " + mail.subject);
  if (doc.status === "not_enough_data") p("  Too few answers: its note goes in the weekly report's email; it is sent on its own only when there is no weekly report.");
  if (args.toSelf) {
    const w = writePreview(args.toSelf, mail);
    p("  Wrote " + w.html + " and " + w.text + ".");
  }
  p("To send it (once): the same command with --apply instead of --to-self" + (args.project.startsWith("demo-") ? "" : " --project " + args.project + ", with REPORT_EMAIL and REPORT_SMTP_PASSWORD set") + ".");
  return null;
}

export async function runReviewCommand(args: ReviewArgs, env: ReviewCommandEnv): Promise<ReviewOutcome | null> {
  const p = env.print;
  if (args.send) return runReviewSend(args, env);
  if (!args.run) {
    const snap = args.week
      ? await reviewRef(env.db, args.week).get().then((s) => (s.exists ? [s.data()!] : []))
      : await env.db.collection(PROMPT_REVIEW.collection).orderBy("week", "desc").limit(1).get().then((s) => s.docs.map((x) => x.data()));
    if (!snap.length) {
      p(args.week ? "No prompt review stored for " + args.week + "." : "No prompt review stored yet.");
      return null;
    }
    for (const l of renderReview(promptReviewViewOf(snap[0]), snap[0])) p(l);
    return null;
  }
  const week = args.week ?? lastCompleteWeek(env.now());
  if (!args.apply) {
    const config = await env.config();
    const existing = (await reviewRef(env.db, week).get()).data();
    const plan = await planPromptReview(env.db, week, config, env.reviewerModel);
    p("Dry run for " + week + ", nothing called or written." + (config.promptReviewEnabled ? "" : " The review is turned off (promptReviewEnabled): --apply would do nothing."));
    if (existing) p("  Stored already: " + String(existing.status) + (existing.status === "done" || existing.status === "not_enough_data" ? " (a run leaves it alone)" : " (a run would replace it)"));
    const a = plan.aggregates;
    p("  answers " + plan.answers + (plan.truncated ? " (read stopped at " + PROMPT_REVIEW.readMax + ")" : "") + ": not ok " + a.notOk + ", judge failed " + a.failed + ", quality <= " + PROMPT_REVIEW.lowQuality + " " + a.lowQuality + ", judge safety blocks " + a.judgeSafetyBlocks + ", quality avg " + (a.qualityAvg ?? "-"));
    p("  issues: " + (Object.entries(a.issues).filter(([, n]) => n > 0).sort((x, y) => y[1] - x[1]).map(([k, n]) => k + " " + n).join(", ") || "none"));
    if (plan.previous) p("  week before (" + plan.previous.week + "): answers " + plan.previous.total + ", not ok " + plan.previous.notOk + ", quality avg " + (plan.previous.qualityAvg ?? "-"));
    if (!plan.enough) {
      p("  Fewer than " + PROMPT_REVIEW.minAnswers + " answers: --apply would store \"not_enough_data\" without a model call (cost 0).");
      return null;
    }
    p("  sample " + plan.sample!.length + " answers (" + plan.sample!.filter((s) => s.text).length + " with text), groups: " + Object.entries(plan.sampleGroups!).map(([k, n]) => k + " " + n).join(", "));
    p("  input " + plan.inputBytes + " bytes (at most that many tokens), output + thinking at most " + PROMPT_REVIEW.tokenCeiling + " tokens; worst case " + usd(plan.worstMicroUsd!) + ", reserved against today's spend ceiling");
    p("To run it: the same command with --apply" + (args.project.startsWith("demo-") ? "" : " --project " + args.project + " (and VERTEX_TOKEN set)") + ".");
    return null;
  }
  const runEnv: PromptReviewEnv = { db: env.db, reviewer: env.reviewer(), logger: env.logger, now: env.now, config: env.config };
  const out = await runPromptReview(runEnv, { ...(args.week ? { week: args.week } : {}), trigger: "cli" });
  if (out.status === "off") {
    p("The weekly prompt review is turned off (promptReviewEnabled); nothing was read or spent. Turn it on with: npm run admin -- config set promptReviewEnabled true --apply");
    return out;
  }
  if (out.status === "already") p(week + " is already reviewed (" + String(out.doc.status) + "); the run is idempotent and left it alone:");
  if (out.status === "running") p(week + " is being reviewed right now (started under " + PROMPT_REVIEW.staleRunMs / 60_000 + " minutes ago); try again later.");
  else for (const l of renderReview(promptReviewViewOf(out.doc), out.doc)) p(l);
  return out;
}
