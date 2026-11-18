/**
 * The owner's report emails (src/core/reportEmail.ts, reportEmailRender.ts, README "Report emails"):
 * both emails' content from fixture reports, the escaping of hostile model text and figures, sent
 * exactly once under reruns and races, sent later after a failure, a send that may have gone out,
 * missing secrets, the one combined email of an empty week, no address in any log line or stored
 * document, the console's and the command's view of it, and the admin command.
 */
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Timestamp } from "firebase-admin/firestore";
import { DEFAULT_IMAGE_SELECTION, DEFAULT_TEXT_SELECTION, PROMPT_REVIEW, REPORT_EMAIL } from "../src/constants";
import { RecordingMailer } from "../src/emulatorFakes";
import { MailError, type Logger, type PromptReviewer } from "../src/ports";
import { promptReviewViewOf, reviewRef, runPromptReview } from "../src/core/promptReview";
import { runWeeklyJobs, runWeeklyReport, weeklyReportRef, weeklyReportViewOf } from "../src/core/weeklyReport";
import { sendPromptReviewEmail, sendWeeklyEmails, sendWeeklyReportEmail, type ReportEmailEnv } from "../src/core/reportEmail";
import { esc, renderPromptReviewEmail, renderWeeklyReportEmail, shortSpan, untrusted } from "../src/core/reportEmailRender";
import { sampleEmptyReviewDoc, sampleHistory, sampleReviewDoc, sampleWeeklyDoc } from "../src/core/reportEmailSamples";
import { gmailMailerOf } from "../src/adapters/gmailSmtp";
import { parseWeeklyArgs, runWeeklyCommand } from "../src/admin/weeklyReportCli";
import { parseReviewArgs, runReviewCommand } from "../src/admin/promptReviewCli";
import { mailerFor, parseEmailTestArgs, runEmailTest } from "../src/admin/reportEmailCli";
import { AdminRefusal } from "../src/admin/accountRequests";
import { loadRuntimeConfig } from "../src/model";
import { db, resetEmulators, setConfig, type LogLine } from "./helpers";

/** Monday 2026-11-16 03:41 UTC: the run reports 2026-W46. */
const NOW = Date.parse("2026-11-16T03:41:00Z");
const WEEK = "2026-W46";
const PROJECT = "demo-foodtracker";
const CONSOLE = "https://demo-foodtracker.firebaseapp.com/";
const OWNER = "owner@example.com";

let logs: LogLine[];
let clock: number;
let mailer: RecordingMailer;
const logger: Logger = {
  info: (message, data) => logs.push({ level: "info", message, data }),
  warn: (message, data) => logs.push({ level: "warn", message, data }),
  error: (message, data) => logs.push({ level: "error", message, data }),
};
const env = (over: Partial<ReportEmailEnv> = {}): ReportEmailEnv => ({ db, logger, now: () => clock, project: PROJECT, mailer, ...over });
const lines = (m: string) => logs.filter((l) => l.message === m);
const reportDoc = async (week = WEEK) => (await weeklyReportRef(db, week).get()).data();
const reviewDoc = async (week = WEEK) => (await reviewRef(db, week).get()).data();
const opts = { week: WEEK, trigger: "schedule" as const };

beforeEach(async () => {
  await resetEmulators();
  logs = [];
  clock = NOW;
  mailer = new RecordingMailer();
});

/** Stores six weeks of sample reports (W41 to W46). */
async function storeHistory() {
  for (const d of sampleHistory(WEEK)) await weeklyReportRef(db, String(d.week)).set(d);
}

describe("the weekly app report's email", () => {
  test("subject, headline tiles, every section with its change, the trend, the console link, and a plain-text twin", async () => {
    await storeHistory();
    const out = await sendWeeklyReportEmail(env(), opts);
    expect(out).toMatchObject({ status: "sent", combined: false, subject: "BestBefore weekly report · 2026-W46 (9–15 Nov)" });
    const [m] = mailer.sent;
    expect(m.subject).toBe("BestBefore weekly report · 2026-W46 (9–15 Nov)");
    const html = m.html;
    // Tiles: revenue, spend, margin, purchases, each with its change against the week before.
    for (const s of ["Estimated net revenue", "USD 139.20", "+27.60", "Model spend", "USD 15.08", "Estimated margin", "USD 124.12", "Purchases", "vs W45"]) expect(html, s).toContain(s);
    for (const s of ["Sales", "Model spend and margin", "Recipes", "Free recipes", "Problems", "Balances", "Last 6 weeks"]) expect(html, s).toContain(">" + s + "</h2>");
    for (const s of ["Recipes sold", "Refunded by the stores", "Spend per recipe kept", "Requests", "Recipes charged", "Failed answers", "Pictures", "Free recipes granted", "Free recipes used", "Recipe reports", "Spend ceiling reached", "Daily cap reached", "Unspent recipes"]) expect(html, s).toContain(s);
    expect(html).toContain("over 80 % on Thu 12 Nov");
    // The trend, newest first: W46 to W41.
    expect(html.indexOf(">W46<")).toBeLessThan(html.indexOf(">W41<"));
    expect(html).toContain('href="' + CONSOLE + '"');
    // Email-safe: no script, no external resource, one link.
    expect(html).not.toMatch(/<script|<link|<img|<iframe|@import|url\(/i);
    expect(html.match(/<a /g)).toHaveLength(1);
    expect(m.text).toContain("Estimated net revenue   USD 139.20 (+27.60)");
    expect(m.text).toContain("Last 6 weeks");
    expect(m.text).toContain("Owner console: " + CONSOLE);
  });

  test("the caveats and the first report (no week before)", async () => {
    const d = sampleWeeklyDoc(WEEK);
    (d.figures as { sales: { capped: boolean } }).sales.capped = true;
    d.previous = null;
    const m = renderWeeklyReportEmail({ doc: d, history: [d], review: null, consoleUrl: CONSOLE, project: PROJECT });
    expect(m.html).toContain("Not counted in full");
    expect(m.html).toContain("More purchases than one run reads");
    expect(m.html).toContain("the first report, no week before to compare");
    expect(m.html).not.toContain("vs W45");
    expect(m.html).not.toContain("Last 1 weeks");
  });

  test("a subject across the year's end keeps both years", () => {
    expect(shortSpan("2026-W46")).toBe("9–15 Nov");
    expect(shortSpan("2026-W53")).toBe("28 Dec 2026 – 3 Jan 2027");
  });

  test("every stored value is escaped, figures included", () => {
    const d = sampleWeeklyDoc(WEEK);
    const f = d.figures as { problems: { reportReasons: Record<string, number>; ceilingDays: string[] } };
    f.problems.reportReasons = { "<script>alert(1)</script>": 3, "<img src=x onerror=alert(2)>": 1 };
    d.week = "2026-W46<b>";
    const m = renderWeeklyReportEmail({ doc: d, history: [d], review: null, consoleUrl: "https://x/\"><script>bad()</script>", project: "p\"><i>" });
    expect(m.html).not.toContain("<script>");
    expect(m.html).not.toContain("<img");
    expect(m.html).not.toContain("<b>");
    expect(m.html).not.toContain("<i>");
    expect(m.html).toContain("&lt;script&gt;alert(1)&lt;/script&gt;");
    expect(m.html).toContain('href="https://x/&quot;&gt;&lt;script&gt;bad()&lt;/script&gt;"');
  });
});

describe("the weekly prompt review's email", () => {
  test("answers, counts, the summary, each problem with its evidence and before → after in monospace, the cost and the reminder", async () => {
    await reviewRef(db, WEEK).set(sampleReviewDoc(WEEK));
    const out = await sendPromptReviewEmail(env(), opts);
    expect(out).toMatchObject({ status: "sent", subject: "BestBefore prompt review · 2026-W46" });
    const { html, text } = mailer.sent[0];
    for (const s of ["Answers analysed", ">412<", "+41", "Not ok", "Quality average", "4.31", "Cost of the run", "USD 0.059", "Judge failures", "Quality 3 or less", "Judge safety blocks", "Issue: vague amount"]) expect(html, s).toContain(s);
    expect(html).toContain("Most low scores this week are vague amounts");
    expect(html).toContain("1. Menus give vague amounts for side dishes");
    expect(html).toContain("vague_amount · target menu · 14 answers · confidence high");
    expect(html).toContain("Jx81kq2MvA, p0QzT7hw1e, aa91LmZ0qe");
    expect(html).toMatch(/<pre [^>]*font-family:ui-monospace[^>]*>Give amounts for the main course&#39;s ingredients\.<\/pre>/);
    expect(html).toContain("Not in the current prompt word for word");
    expect(html).toContain("Judge problems");
    expect(html).toContain("Ordinary flambé and tartare refused");
    expect(html).toContain("Advice only: nothing is changed automatically.");
    expect(text).toContain("Before: \"Give amounts for the main course's ingredients.\"");
    expect(text).toContain("Advice only: nothing is changed automatically.");
  });

  test("hostile model text is escaped, stripped of hidden characters, cut to its caps and never linked", () => {
    const d = sampleReviewDoc(WEEK);
    const evil = "<script>alert('x')</script><img src=x onerror=alert(1)></pre><a href=\"https://evil.example/p\">click</a> www.evil.example \u{202e}gnp.exe\u{200b}";
    const report = d.report as { summary: string; promptProblems: Record<string, unknown>[]; judgeProblems: Record<string, unknown>[] };
    report.summary = evil + "x".repeat(5_000);
    report.promptProblems[0] = { ...report.promptProblems[0], title: evil, cause: evil, before: evil + "</pre><b>", after: "y".repeat(5_000), issue: "<b>vague</b>", target: "menu\"><x", confidence: "<i>", exampleIds: [evil, "a", "b", "c", "d", "e", "f"], count: "9<9" };
    report.judgeProblems = Array.from({ length: 9 }, (_, i) => ({ ...report.promptProblems[1], title: "judge " + i }));
    d.model = "<b>model</b>";
    const { html, text } = renderPromptReviewEmail({ doc: d, consoleUrl: CONSOLE, project: PROJECT });
    for (const bad of ["<script", "<img", "<a href=\"https://evil", "</pre><b>", "<b>vague", "<i>", "<x", "<b>model"]) expect(html, bad).not.toContain(bad);
    expect(html).toContain("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;");
    // No live link anywhere in the model's text; only the console's.
    expect(html.match(/<a /g)).toHaveLength(1);
    expect(html).not.toContain("https://evil");
    expect(html).not.toContain("www.evil");
    expect(html).not.toMatch(/[\u{202a}-\u{202e}\u{200b}]/u);
    // Caps: the summary 800, after 1,200 code points; at most 5 problems a list and 5 example ids.
    expect(html).not.toContain("x".repeat(800));
    expect(html).toContain("x".repeat(500));
    expect(html).not.toContain("y".repeat(1_200));
    expect(html).toContain("y".repeat(1_100) );
    expect(html).toContain("judge 4");
    expect(html).not.toContain("judge 5");
    expect(html).not.toContain(", f<");
    // Codes keep letters, digits and _ only; a count that is not a number is 0.
    expect(html).toContain("bvagueb · target menux · 0 answers · confidence i");
    expect(text).not.toContain("https://evil");
    expect(untrusted("see https://a.example and www.b.example", 100)).not.toMatch(/https:\/\/|www\./);
    expect(esc("<&>\"'")).toBe("&lt;&amp;&gt;&quot;&#39;");
  });

  test("a week with too few answers: a short email of its own only when it has no weekly report", async () => {
    await reviewRef(db, WEEK).set(sampleEmptyReviewDoc(WEEK, 12));
    expect(await sendPromptReviewEmail(env(), opts)).toMatchObject({ status: "sent", subject: "BestBefore prompt review · 2026-W46 · not enough data" });
    expect(mailer.sent[0].html).toContain("12 judge answers last week, fewer than the 20 the review needs");
    expect(mailer.sent[0].html).not.toContain("Prompt problems");
  });

  test("skipped, failed or missing reviews have no email", async () => {
    expect(await sendPromptReviewEmail(env(), opts)).toMatchObject({ status: "skipped", reason: "NOTHING_TO_SEND" });
    await reviewRef(db, WEEK).set({ week: WEEK, status: "failed", reason: "MALFORMED_ANSWER" });
    expect(await sendPromptReviewEmail(env(), opts)).toMatchObject({ status: "skipped", reason: "NOTHING_TO_SEND" });
    expect(mailer.sent).toHaveLength(0);
  });
});

describe("sent exactly once", () => {
  test("a rerun sends nothing more", async () => {
    await storeHistory();
    await sendWeeklyReportEmail(env(), opts);
    expect(await sendWeeklyReportEmail(env(), opts)).toMatchObject({ status: "skipped", reason: "ALREADY_SENT" });
    expect(await sendWeeklyReportEmail(env(), { ...opts, trigger: "cli", force: true })).toMatchObject({ status: "skipped", reason: "ALREADY_SENT" });
    expect(mailer.sent).toHaveLength(1);
    const e = (await reportDoc())!.email;
    expect(e).toMatchObject({ state: "sent", attempts: 1, trigger: "schedule", via: "weekly_report", reason: null, maybeSent: false });
    expect(e.sentAt).toBeInstanceOf(Timestamp);
    expect(lines("report_email_sent")).toHaveLength(1);
    expect(lines("report_email_skipped").map((l) => l.data?.reason)).toEqual(["ALREADY_SENT", "ALREADY_SENT"]);
  });

  test("two runs at once: one email; the second finds the claim", async () => {
    await storeHistory();
    let open!: () => void;
    mailer.gate = new Promise<void>((r) => (open = r));
    const first = sendWeeklyReportEmail(env(), opts);
    while (mailer.calls === 0) await new Promise((r) => setTimeout(r, 5));
    const second = await sendWeeklyReportEmail(env(), { ...opts, trigger: "cli" });
    expect(second).toMatchObject({ status: "skipped", reason: "SENDING" });
    open();
    expect(await first).toMatchObject({ status: "sent" });
    const many = await Promise.all(Array.from({ length: 5 }, () => sendWeeklyReportEmail(env(), opts)));
    expect(many.every((o) => o.status === "skipped")).toBe(true);
    expect(mailer.sent).toHaveLength(1);
  });

  test("racing runs claiming at the same moment: exactly one sends", async () => {
    await storeHistory();
    const outs = await Promise.all(Array.from({ length: 6 }, (_, i) => sendWeeklyReportEmail(env(), { ...opts, trigger: i % 2 ? "cli" : "schedule" })));
    expect(outs.filter((o) => o.status === "sent")).toHaveLength(1);
    expect(mailer.calls).toBe(1);
  });

  test("a failed send is recorded and sent later; the alert line carries the reason only", async () => {
    await storeHistory();
    mailer.fail = new MailError("CONNECTION", false);
    expect(await sendWeeklyReportEmail(env(), opts)).toMatchObject({ status: "failed", reason: "CONNECTION", maybeSent: false });
    expect((await reportDoc())!.email).toMatchObject({ state: "failed", reason: "CONNECTION", maybeSent: false, attempts: 1 });
    expect(lines("report_email_failed")).toEqual([{ level: "error", message: "report_email_failed", data: { report: "weekly_report", week: WEEK, trigger: "schedule", reason: "CONNECTION", maybeSent: false, recorded: true } }]);
    mailer.fail = null;
    clock += 3_600_000;
    expect(await sendWeeklyReportEmail(env(), { ...opts, trigger: "cli" })).toMatchObject({ status: "sent" });
    expect((await reportDoc())!.email).toMatchObject({ state: "sent", attempts: 2, trigger: "cli", reason: null });
    expect(mailer.sent).toHaveLength(1);
  });

  test("a send that may have gone out is not sent again by itself; --force sends it", async () => {
    await storeHistory();
    mailer.fail = new MailError("TIMEOUT", true);
    await sendWeeklyReportEmail(env(), opts);
    mailer.fail = null;
    expect(await sendWeeklyReportEmail(env(), opts)).toMatchObject({ status: "skipped", reason: "UNCERTAIN" });
    expect(mailer.sent).toHaveLength(0);
    expect(await sendWeeklyReportEmail(env(), { ...opts, trigger: "cli", force: true })).toMatchObject({ status: "sent" });
    expect(mailer.sent).toHaveLength(1);
  });

  test("a mailer that throws something unexpected counts as maybe sent", async () => {
    await storeHistory();
    const odd = { send: async () => { throw new Error("boom " + OWNER); } };
    expect(await sendWeeklyReportEmail(env({ mailer: odd }), opts)).toMatchObject({ status: "failed", reason: "ERROR", maybeSent: true });
    expect(JSON.stringify(logs)).not.toContain(OWNER);
  });

  test("a claim never followed by an outcome (the instance died): left alone; --force only after it is stale", async () => {
    await storeHistory();
    await weeklyReportRef(db, WEEK).update({ email: { state: "sending", claimId: "x", claimedAt: Timestamp.fromMillis(NOW), attempts: 1, trigger: "schedule", via: "weekly_report", sentAt: null, failedAt: null, reason: null, maybeSent: false } });
    expect(await sendWeeklyReportEmail(env(), opts)).toMatchObject({ status: "skipped", reason: "SENDING" });
    expect(await sendWeeklyReportEmail(env(), { ...opts, force: true })).toMatchObject({ status: "skipped", reason: "SENDING" });
    clock = NOW + REPORT_EMAIL.staleSendingMs;
    expect(await sendWeeklyReportEmail(env(), opts)).toMatchObject({ status: "skipped", reason: "SENDING" });
    expect(await sendWeeklyReportEmail(env(), { ...opts, trigger: "cli", force: true })).toMatchObject({ status: "sent" });
    expect((await reportDoc())!.email).toMatchObject({ state: "sent", attempts: 2 });
  });

  test("nothing stored: nothing to send", async () => {
    expect(await sendWeeklyReportEmail(env(), opts)).toMatchObject({ status: "skipped", reason: "NOTHING_TO_SEND" });
    expect(mailer.calls).toBe(0);
  });
});

describe("missing secrets", () => {
  test("no mailer: the reports are still made and stored, report_email_failed NOT_CONFIGURED, nothing thrown; sent once it is configured", async () => {
    expect(gmailMailerOf("", "")).toBeNull();
    const noMail = env({ mailer: gmailMailerOf(undefined, undefined) });
    await runWeeklyJobs(
      {
        report: () => runWeeklyReport({ db, logger, now: () => clock, config: () => loadRuntimeConfig(db), project: PROJECT }, { trigger: "schedule" }),
        review: async () => undefined,
        emails: () => sendWeeklyEmails(noMail, { ...opts, weeklyReportEnabled: true, promptReviewEnabled: true }),
      },
      logger,
      { week: WEEK, project: PROJECT },
    );
    expect(await reportDoc()).toBeDefined();
    expect(lines("weekly_report_ready")).toHaveLength(1);
    expect(lines("report_email_failed").map((l) => l.data)).toEqual([{ report: "weekly_report", week: WEEK, trigger: "schedule", reason: "NOT_CONFIGURED", maybeSent: false, recorded: true }]);
    expect((await reportDoc())!.email).toMatchObject({ state: "failed", reason: "NOT_CONFIGURED" });
    expect(weeklyReportViewOf((await reportDoc())!)!.email).toContain("Email not sent (NOT_CONFIGURED");
    expect(await sendWeeklyReportEmail(env(), { ...opts, trigger: "cli" })).toMatchObject({ status: "sent" });
  });

  test("an emails job that throws is logged as report_email_failed, never thrown", async () => {
    await expect(runWeeklyJobs({ report: async () => undefined, review: async () => undefined, emails: () => { throw new Error("x"); } }, logger, { week: WEEK, project: PROJECT })).resolves.toBeUndefined();
    expect(lines("report_email_failed")[0].data).toMatchObject({ reason: "ERROR", maybeSent: true });
  });
});

describe("one email in an empty week", () => {
  class NoCall implements PromptReviewer {
    readonly model = PROMPT_REVIEW.model;
    calls = 0;
    async review(): Promise<never> {
      this.calls++;
      throw new Error("no call expected");
    }
  }
  const jobs = (m: RecordingMailer | null = mailer) => runWeeklyJobs(
    {
      report: () => runWeeklyReport({ db, logger, now: () => clock, config: () => loadRuntimeConfig(db), project: PROJECT }, { trigger: "schedule" }),
      review: () => runPromptReview({ db, reviewer: new NoCall(), logger, now: () => clock, config: () => loadRuntimeConfig(db, undefined, DEFAULT_IMAGE_SELECTION, DEFAULT_TEXT_SELECTION) }, { trigger: "schedule" }),
      emails: async () => {
        const c = await loadRuntimeConfig(db);
        return sendWeeklyEmails(env({ mailer: m }), { ...opts, weeklyReportEnabled: c.weeklyReportEnabled, promptReviewEnabled: c.promptReviewEnabled });
      },
    },
    logger,
    { week: WEEK, project: PROJECT },
  );

  test("too few judge answers: the review's note goes in the weekly report's email, and no second email", async () => {
    await jobs();
    expect(await reviewDoc()).toMatchObject({ status: "not_enough_data" });
    expect(mailer.sent).toHaveLength(1);
    expect(mailer.sent[0].subject).toBe("BestBefore weekly report · 2026-W46 (9–15 Nov)");
    expect(mailer.sent[0].html).toContain("Prompt review: 0 judge answers last week, fewer than the 20 the review needs");
    expect((await reportDoc())!.email).toMatchObject({ state: "sent" });
    expect((await reviewDoc())!.email).toMatchObject({ state: "sent", via: "weekly_report" });
    expect(promptReviewViewOf((await reviewDoc())!).email).toContain("Noted in the weekly report's email");
    expect(lines("report_email_sent").map((l) => l.data?.combined)).toEqual([true]);
    expect(lines("report_email_skipped").map((l) => [l.data?.report, l.data?.reason])).toEqual([["prompt_review", "ALREADY_SENT"]]);
    // Run again: nothing more.
    await jobs();
    expect(mailer.sent).toHaveLength(1);
  });

  test("the report's email fails: the note waits for it, and goes with it when it is sent", async () => {
    mailer.fail = new MailError("AUTH_FAILED", false);
    await jobs();
    expect(mailer.sent).toHaveLength(0);
    expect(mailer.calls).toBe(1);
    expect(lines("report_email_skipped").map((l) => [l.data?.report, l.data?.reason])).toEqual([["prompt_review", "WITH_WEEKLY_REPORT"]]);
    expect((await reviewDoc())!.email).toMatchObject({ state: "failed", reason: "AUTH_FAILED", via: "weekly_report" });
    mailer.fail = null;
    expect(await sendWeeklyReportEmail(env(), { ...opts, trigger: "cli" })).toMatchObject({ status: "sent", combined: true });
    expect(mailer.sent[0].html).toContain("Prompt review: 0 judge answers");
    expect((await reviewDoc())!.email).toMatchObject({ state: "sent" });
  });

  test("the weekly report off: the review's note is sent as a short email of its own", async () => {
    await setConfig({ weeklyReportEnabled: false });
    await jobs();
    expect(mailer.sent.map((m) => m.subject)).toEqual(["BestBefore prompt review · 2026-W46 · not enough data"]);
  });

  test("a skipped or failed review is one line in the weekly report's email, and has no email of its own", async () => {
    await storeHistory();
    await reviewRef(db, WEEK).set({ week: WEEK, status: "skipped", reason: "GENERATION_DISABLED", answers: 40 });
    await sendWeeklyEmails(env(), { ...opts, weeklyReportEnabled: true, promptReviewEnabled: true });
    expect(mailer.sent).toHaveLength(1);
    expect(mailer.sent[0].html).toContain("Prompt review skipped (GENERATION_DISABLED)");
    expect((await reviewDoc())!.email).toBeUndefined();
  });

  test("both turned off: nothing sent", async () => {
    await storeHistory();
    await reviewRef(db, WEEK).set(sampleReviewDoc(WEEK));
    expect(await sendWeeklyEmails(env(), { ...opts, weeklyReportEnabled: false, promptReviewEnabled: false })).toEqual([]);
    expect(mailer.calls).toBe(0);
  });

  test("a week with a review that is done: two emails, the report's first", async () => {
    await storeHistory();
    await reviewRef(db, WEEK).set(sampleReviewDoc(WEEK));
    await sendWeeklyEmails(env(), { ...opts, weeklyReportEnabled: true, promptReviewEnabled: true });
    expect(mailer.sent.map((m) => m.subject)).toEqual(["BestBefore weekly report · 2026-W46 (9–15 Nov)", "BestBefore prompt review · 2026-W46"]);
    expect(mailer.sent[0].html).not.toContain("Prompt review:");
  });
});

describe("never the address", () => {
  test("no address in any log line or stored document, whatever happened", async () => {
    await storeHistory();
    await reviewRef(db, WEEK).set(sampleReviewDoc(WEEK));
    mailer.fail = new MailError("REJECTED", false);
    await sendWeeklyEmails(env(), { ...opts, weeklyReportEnabled: true, promptReviewEnabled: true });
    mailer.fail = null;
    await sendWeeklyEmails(env(), { ...opts, weeklyReportEnabled: true, promptReviewEnabled: true });
    await sendWeeklyEmails(env({ mailer: null }), { week: "2026-W45", trigger: "cli", weeklyReportEnabled: true, promptReviewEnabled: true });
    const stored = JSON.stringify([await reportDoc(), await reviewDoc(), await reportDoc("2026-W45")]);
    for (const s of [JSON.stringify(logs), stored]) expect(s).not.toContain("@");
    // The emails themselves hold no address either (their only "@" is the stylesheet's @media).
    expect(JSON.stringify(mailer.sent).replaceAll("@media", "")).not.toContain("@");
  });
});

describe("the admin command", () => {
  const out: string[] = [];
  const cmdEnv = (m: RecordingMailer = mailer) => ({ db, now: () => clock, print: (l: string) => out.push(l), logger, config: () => loadRuntimeConfig(db), mailer: () => m });
  beforeEach(() => {
    out.length = 0;
  });

  test("arguments: --send, --to-self, --apply and --force", () => {
    const p = (a: string) => parseWeeklyArgs(a.split(" "), PROJECT);
    expect(p("report weekly --send")).toEqual({ project: PROJECT, run: false, apply: false, send: true });
    expect(p("report weekly --send --to-self /tmp/x.html --week 2026-W46")).toEqual({ project: PROJECT, week: WEEK, run: false, apply: false, send: true, toSelf: "/tmp/x.html" });
    expect(p("report weekly --send --apply --force")).toEqual({ project: PROJECT, run: false, apply: true, send: true, force: true });
    for (const bad of ["report weekly --send --run", "report weekly --to-self x.html", "report weekly --send --apply --to-self x.html", "report weekly --send --force", "report weekly --force", "report weekly --apply"]) expect(() => p(bad), bad).toThrow(AdminRefusal);
    expect(parseReviewArgs("judge review --send --apply".split(" "), PROJECT)).toMatchObject({ send: true, apply: true });
    expect(() => parseReviewArgs("judge review --to-self x.html".split(" "), PROJECT)).toThrow(AdminRefusal);
    expect(parseEmailTestArgs("report email-test --to-self a.html".split(" "), PROJECT)).toEqual({ project: PROJECT, toSelf: "a.html", apply: false });
    expect(() => parseEmailTestArgs("report email-test --apply --to-self a.html".split(" "), PROJECT)).toThrow(AdminRefusal);
  });

  test("--send without --apply: the state and a preview written to a file; nothing sent or written", async () => {
    await storeHistory();
    await reviewRef(db, WEEK).set(sampleEmptyReviewDoc(WEEK));
    const dir = mkdtempSync(join(tmpdir(), "report-email-"));
    const before = await reportDoc();
    await runWeeklyCommand(parseWeeklyArgs(["report", "weekly", "--send", "--to-self", join(dir, "weekly.html")], PROJECT), cmdEnv());
    expect(out[0]).toBe("Dry run for 2026-W46's email, nothing sent or written.");
    expect(out).toContain("  State: not emailed yet.");
    expect(out).toContain("  Subject: BestBefore weekly report · 2026-W46 (9–15 Nov)");
    expect(readFileSync(join(dir, "weekly.html"), "utf8")).toContain("Prompt review: 12 judge answers");
    expect(readFileSync(join(dir, "weekly.txt"), "utf8")).toMatch(/^Subject: BestBefore weekly report/);
    expect(await reportDoc()).toEqual(before);
    expect(mailer.calls).toBe(0);

    out.length = 0;
    await reviewRef(db, WEEK).set(sampleReviewDoc(WEEK));
    await runReviewCommand(parseReviewArgs(["judge", "review", "--send", "--to-self", join(dir, "review.html")], PROJECT), { ...cmdEnv(), reviewer: () => new NoReviewer(), reviewerModel: "m" });
    expect(out).toContain("  Subject: BestBefore prompt review · 2026-W46");
    expect(readFileSync(join(dir, "review.html"), "utf8")).toContain("Menus give vague amounts");
  });

  test("--send --apply sends once (the emulators record it), and the stored report then shows it", async () => {
    await storeHistory();
    await runWeeklyCommand(parseWeeklyArgs(["report", "weekly", "--send", "--apply"], PROJECT), cmdEnv());
    expect(out[0]).toBe("The weekly report's email for 2026-W46 was recorded (emulators: nothing sent): BestBefore weekly report · 2026-W46 (9–15 Nov)");
    await runWeeklyCommand(parseWeeklyArgs(["report", "weekly", "--send", "--apply"], PROJECT), cmdEnv());
    expect(out[1]).toBe("The weekly report's email for 2026-W46: not sent, it was sent already.");
    out.length = 0;
    await runWeeklyCommand(parseWeeklyArgs(["report", "weekly"], PROJECT), cmdEnv());
    expect(out.join("\n")).toMatch(/Emailed 16 Nov 2026, 03:41 UTC\./);
    expect(mailer.sent).toHaveLength(1);
  });

  test("a live project needs the secrets in the environment, refused before anything is claimed", () => {
    expect(() => mailerFor("bestbefore-f9ee1", {})).toThrow(AdminRefusal);
    expect(mailerFor("bestbefore-f9ee1", { REPORT_EMAIL: OWNER, REPORT_SMTP_PASSWORD: "abcd efgh ijkl mnop" })).not.toBeInstanceOf(RecordingMailer);
    expect(mailerFor(PROJECT, {})).toBeInstanceOf(RecordingMailer);
  });

  test("email-test: made-up figures, [test] subjects; reads and writes no report", async () => {
    const dir = mkdtempSync(join(tmpdir(), "report-email-"));
    await runEmailTest({ project: PROJECT, toSelf: join(dir, "t.html"), apply: false }, { now: () => clock, print: (l) => out.push(l), mailer: () => mailer });
    expect(out[0]).toBe("Would send: [test] BestBefore weekly report · 2026-W46 (9–15 Nov) (" + readFileSync(join(dir, "t.html"), "utf8").length + " bytes of HTML)");
    expect(readFileSync(join(dir, "t-prompt-review.html"), "utf8")).toContain("[test] BestBefore prompt review");
    expect(mailer.calls).toBe(0);
    await runEmailTest({ project: PROJECT, apply: true }, { now: () => clock, print: (l) => out.push(l), mailer: () => mailer });
    expect(mailer.sent.map((m) => m.subject)).toEqual(["[test] BestBefore weekly report · 2026-W46 (9–15 Nov)", "[test] BestBefore prompt review · 2026-W46"]);
    const all = await db.collection("weeklyReports").get();
    expect(all.size).toBe(0);
  });
});

class NoReviewer implements PromptReviewer {
  readonly model = "m";
  async review(): Promise<never> {
    throw new Error("not called");
  }
}
