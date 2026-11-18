/**
 * The owner's report emails as HTML and plain text (README "Report emails"). Pure: built from the
 * stored documents (weeklyReports/{week}, promptReviews/{week}), never from live reads.
 *
 * Email-safe on purpose: tables for layout, inline styles (a small <style> only adds the dark
 * colours for clients that honour prefers-color-scheme), no external image, font, script or
 * stylesheet, one link (the owner console). Every value is HTML-escaped where it is put in, the
 * figures as much as the model's text. The prompt review's text is the model's, written from
 * recipes that came from user input: it is untrusted, so it is cut to its caps again, stripped of
 * control and direction-override characters, its links defused (no client turns them into links),
 * and only ever set as text, never as HTML.
 */
import { PROMPT_REVIEW, REPORT_EMAIL } from "../constants";
import type { ReportMail } from "../ports";
import { promptReviewViewOf, type ReviewProblem } from "./promptReview";
import { historyOf, renderWeeklyReport, weekSpanOf, weeklyReportViewOf, type WeeklyHistoryRow, type WeeklyReportRow } from "./weeklyReport";

// --- Text safety ---------------------------------------------------------------------------------

const ESC: Record<string, string> = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };
/** HTML-escapes any value (as text). */
export const esc = (v: unknown): string => String(v ?? "").replace(/[&<>"']/g, (c) => ESC[c]);

/** The word joiner: invisible, and it stops a mail client from making a link of what it splits. */
const WJ = "\u{2060}";

// C0/C1 controls but tab and newline, the bidirectional marks, overrides and isolates, and
// zero-width characters that could hide text.
// eslint-disable-next-line no-control-regex
const HIDDEN = /[\u{0}-\u{8}\u{b}-\u{1f}\u{7f}-\u{9f}\u{200b}-\u{200f}\u{202a}-\u{202e}\u{2060}-\u{2069}\u{feff}]/gu;

/**
 * Model text made safe to show: a string, hidden characters removed, cut to `max` code points
 * (with "…"), and links defused so no mail client makes them clickable. Not yet escaped.
 */
export function untrusted(v: unknown, max: number): string {
  const s = (typeof v === "string" ? v : "").replace(/\r\n?/g, "\n").replace(HIDDEN, "");
  const cps = Array.from(s);
  const cut = cps.length > max ? cps.slice(0, Math.max(0, max - 1)).join("") + "…" : s;
  return cut.replace(/:\/\//g, ":" + WJ + "//").replace(/\bwww\./gi, (m) => m.slice(0, 3) + WJ + ".");
}

/** A short code from model text (issue, target, confidence): letters, digits and _ only. */
const code = (v: unknown) => untrusted(v, REPORT_EMAIL.modelChars.code).replace(/[^A-Za-z0-9_]/g, "") || "-";

// --- Layout --------------------------------------------------------------------------------------

const FONT = "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";
const MONO = "ui-monospace,SFMono-Regular,Menlo,Consolas,'Liberation Mono',monospace";
const C = {
  bg: "#eef0eb", card: "#ffffff", line: "#dfe3da", text: "#1c2420", muted: "#5d6a62", tile: "#f5f7f2",
  accent: "#2d6a47", good: "#1d7a3c", bad: "#b3261e", warnBg: "#fdf4e3", warnLine: "#ecd5a6", pre: "#f4f6f1",
};

const STYLE = `
  body, table, td { -webkit-text-size-adjust: 100%; }
  a { color: ${C.accent}; }
  @media (prefers-color-scheme: dark) {
    .x-bg { background: #111412 !important; }
    .x-card { background: #1b1f1c !important; border-color: #2c332e !important; }
    .x-tile { background: #232825 !important; border-color: #2c332e !important; }
    .x-line { border-color: #2c332e !important; }
    .x-tx { color: #e4e9e5 !important; }
    .x-mu { color: #a3afa7 !important; }
    .x-acc { color: #8fd3a8 !important; }
    .x-good { color: #86d79f !important; }
    .x-bad { color: #f4a69e !important; }
    .x-warn { background: #2e2717 !important; border-color: #5a4a26 !important; color: #f0dfb8 !important; }
    .x-pre { background: #232825 !important; border-color: #2c332e !important; color: #e4e9e5 !important; }
    .x-btn { background: #8fd3a8 !important; }
    .x-btn a { color: #10231a !important; }
  }
  @media (max-width: 480px) {
    .x-pad { padding-left: 14px !important; padding-right: 14px !important; }
    .x-big { font-size: 19px !important; }
  }
`;

function page(title: string, preheader: string, body: string): string {
  return '<!doctype html><html lang="en"><head><meta charset="utf-8">'
    + '<meta name="viewport" content="width=device-width,initial-scale=1">'
    + '<meta name="color-scheme" content="light dark"><meta name="supported-color-schemes" content="light dark">'
    + "<title>" + esc(title) + "</title><style>" + STYLE + "</style></head>"
    + '<body class="x-bg" style="margin:0;padding:0;background:' + C.bg + ';">'
    + '<div style="display:none;max-height:0;overflow:hidden;opacity:0;mso-hide:all;">' + esc(preheader) + "</div>"
    + '<table role="presentation" class="x-bg" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:' + C.bg + ';"><tr><td align="center" style="padding:16px 8px;">'
    + '<table role="presentation" class="x-card" width="100%" cellpadding="0" cellspacing="0" border="0" style="max-width:640px;background:' + C.card + ";border:1px solid " + C.line + ";border-radius:12px;font-family:" + FONT + ";color:" + C.text + ';">'
    + body
    + "</table></td></tr></table></body></html>";
}

const pad = (inner: string, top = 0, bottom = 0) => '<tr><td class="x-pad" style="padding:' + top + "px 24px " + bottom + 'px 24px;">' + inner + "</td></tr>";

function header(kicker: string, title: string, sub: string): string {
  return pad(
    '<div class="x-acc" style="font-size:12px;font-weight:700;letter-spacing:.06em;text-transform:uppercase;color:' + C.accent + ';">' + esc(kicker) + "</div>"
    + '<h1 class="x-tx" style="margin:6px 0 4px;font-size:22px;line-height:1.25;font-weight:700;color:' + C.text + ';">' + esc(title) + "</h1>"
    + '<div class="x-mu" style="font-size:13px;line-height:1.4;color:' + C.muted + ';">' + esc(sub) + "</div>",
    22, 8,
  );
}

function h2(text: string): string {
  return '<h2 class="x-tx" style="margin:0 0 6px;font-size:15px;line-height:1.3;font-weight:700;color:' + C.text + ';">' + esc(text) + "</h2>";
}

const muted = (text: string, size = 13) => '<p class="x-mu" style="margin:6px 0 0;font-size:' + size + "px;line-height:1.45;color:" + C.muted + ';">' + esc(text) + "</p>";
const para = (html: string) => '<p class="x-tx" style="margin:6px 0 0;font-size:14px;line-height:1.5;color:' + C.text + ';">' + html + "</p>";

type Tone = "good" | "bad" | "neutral";
function changeSpan(change: string, tone: Tone): string {
  if (!change) return "";
  const color = tone === "good" ? C.good : tone === "bad" ? C.bad : C.muted;
  const cls = tone === "good" ? "x-good" : tone === "bad" ? "x-bad" : "x-mu";
  return '<span class="' + cls + '" style="font-size:12px;font-weight:600;color:' + color + ';white-space:nowrap;">' + esc(change) + "</span>";
}

/** Which way is better for a figure, by its row label: up, down, or neither. */
const BETTER_UP = new Set(["Estimated net revenue", "Purchases", "Recipes sold", "Estimated margin", "Requests", "Recipes charged", "Pictures", "Answers analysed", "Quality average"]);
const BETTER_DOWN = new Set(["Refunded by the stores", "Spend per recipe kept", "Failed answers", "Recipe reports", "Not ok", "Judge failures", "Quality 3 or less", "Judge safety blocks"]);
function toneOf(label: string, change: string): Tone {
  if (!change || change === "±0") return "neutral";
  const up = change.startsWith("+");
  if (BETTER_UP.has(label)) return up ? "good" : "bad";
  if (BETTER_DOWN.has(label) || label.startsWith("Issue: ")) return up ? "bad" : "good";
  return "neutral";
}

interface Tile { label: string; value: string; change: string; caption: string }
function tiles(list: Tile[], against: string | null): string {
  const cell = (t: Tile | undefined) => !t ? '<td width="50%" style="padding:4px;"></td>'
    : '<td width="50%" valign="top" style="padding:4px;"><table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" class="x-tile" style="background:' + C.tile + ";border:1px solid " + C.line + ';border-radius:10px;"><tr><td style="padding:12px 12px 11px;">'
      + '<div class="x-mu" style="font-size:12px;line-height:1.3;color:' + C.muted + ';">' + esc(t.label) + "</div>"
      + '<div class="x-tx x-big" style="margin-top:4px;font-size:22px;line-height:1.2;font-weight:700;color:' + C.text + ';white-space:nowrap;">' + esc(t.value) + "</div>"
      + '<div style="margin-top:3px;font-size:12px;line-height:1.3;">' + (t.change ? changeSpan(t.change, toneOf(t.label, t.change)) + (against ? '<span class="x-mu" style="color:' + C.muted + ';"> vs ' + esc(against) + "</span>" : "") : '<span class="x-mu" style="color:' + C.muted + ';">' + esc(t.caption) + "</span>") + "</div>"
      + "</td></tr></table></td>";
  let rows = "";
  for (let i = 0; i < list.length; i += 2) rows += "<tr>" + cell(list[i]) + cell(list[i + 1]) + "</tr>";
  return pad('<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">' + rows + "</table>", 10, 4);
}

function figureRows(rows: readonly WeeklyReportRow[]): string {
  let out = "";
  rows.forEach((r, i) => {
    const border = i === 0 ? "" : "border-top:1px solid " + C.line + ";";
    out += '<tr><td class="x-line" valign="top" style="' + border + 'padding:9px 8px 0 0;font-size:14px;line-height:1.35;"><span class="x-tx" style="color:' + C.text + ';">' + esc(r.label) + "</span></td>"
      + '<td class="x-line" valign="top" align="right" style="' + border + 'padding:9px 0 0 0;font-size:14px;line-height:1.35;white-space:nowrap;"><b class="x-tx" style="color:' + C.text + ';">' + esc(r.value) + "</b>" + (r.change ? " " + changeSpan(r.change, toneOf(r.label, r.change)) : "") + "</td></tr>"
      + '<tr><td colspan="2" style="padding:2px 0 9px 0;font-size:12px;line-height:1.4;">' + (r.note ? '<span class="x-mu" style="color:' + C.muted + ';">' + esc(r.note) + "</span>" : "") + "</td></tr>";
  });
  return '<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">' + out + "</table>";
}

function section(title: string, inner: string): string {
  return pad('<div class="x-line" style="border-top:1px solid ' + C.line + ';padding-top:16px;">' + h2(title) + inner + "</div>", 12, 4);
}

function callout(html: string): string {
  return '<div class="x-warn" style="margin:8px 0 0;padding:10px 12px;background:' + C.warnBg + ";border:1px solid " + C.warnLine + ';border-radius:8px;font-size:13px;line-height:1.45;color:#5b4410;">' + html + "</div>";
}

function pre(text: string): string {
  return '<pre class="x-pre" style="margin:4px 0 0;padding:10px 12px;background:' + C.pre + ";border:1px solid " + C.line + ";border-radius:8px;font-family:" + MONO + ";font-size:12.5px;line-height:1.5;color:" + C.text + ';white-space:pre-wrap;word-wrap:break-word;overflow-wrap:anywhere;">' + esc(text) + "</pre>";
}

function footer(consoleUrl: string, lines: string[]): string {
  const btn = '<table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin:4px 0 0;"><tr><td class="x-btn" style="background:' + C.accent + ';border-radius:8px;">'
    + '<a href="' + esc(consoleUrl) + '" style="display:inline-block;padding:10px 16px;font-size:14px;font-weight:600;color:#ffffff;text-decoration:none;">Open the owner console</a></td></tr></table>';
  return pad('<div class="x-line" style="border-top:1px solid ' + C.line + ';padding-top:16px;">' + btn + lines.map((l) => muted(l, 12)).join("") + "</div>", 14, 22);
}

// --- Shared wording ------------------------------------------------------------------------------

/** "9–15 Nov" in a subject (the year only when the week spans two). */
export function shortSpan(week: string): string {
  const s = weekSpanOf(week);
  const years = s.match(/\b\d{4}\b/g) ?? [];
  return years.length === 1 ? s.replace(/ \d{4}$/, "") : s;
}

const SUBJECT_PREFIX = REPORT_EMAIL.brand + " ";

// --- The weekly app report ----------------------------------------------------------------------

/** The prompt review's status, when it goes into the weekly report's email as a short note. */
export interface ReviewNote {
  status: string;
  reason: string | null;
  answers: number | null;
  minAnswers: number;
}

export function reviewNoteText(n: ReviewNote): string | null {
  const answers = n.answers === null ? "?" : n.answers.toLocaleString("en-GB");
  if (n.status === "not_enough_data") {
    return "Prompt review: " + answers + " judge answer" + (n.answers === 1 ? "" : "s") + " last week, fewer than the " + n.minAnswers + " the review needs, so no model was asked and nothing was spent. It runs again next Monday; no separate email this week.";
  }
  if (n.status === "skipped") return "Prompt review skipped (" + (n.reason ?? "?") + "): nothing was spent. Run it by hand with npm run admin -- judge review --run.";
  if (n.status === "failed") return "Prompt review failed (" + (n.reason ?? "?") + "); its own alert has the details.";
  return null;
}

const SECTIONS: { title: string; labels: string[] }[] = [
  { title: "Sales", labels: ["Estimated net revenue", "Purchases", "Recipes sold", "Refunded by the stores"] },
  { title: "Model spend and margin", labels: ["Model spend", "Spend per recipe kept", "Estimated margin"] },
  { title: "Recipes", labels: ["Requests", "Recipes charged", "Failed answers", "Pictures"] },
  { title: "Free recipes", labels: ["Free recipes granted", "Free recipes used"] },
  { title: "Problems", labels: ["Recipe reports", "Spend ceiling reached", "Daily cap reached"] },
  { title: "Balances", labels: ["Unspent recipes"] },
];

const TILE_CAPTION: Record<string, string> = {
  "Estimated net revenue": "net of refunds, an estimate",
  "Model spend": "the server's token estimate",
  "Estimated margin": "revenue minus model spend",
  Purchases: "packs bought",
};

function trendTable(history: readonly WeeklyHistoryRow[]): string {
  const head = ["Week", "Revenue", "Spend", "Margin", "Buys", "Requests"];
  const num = (s: string) => s.replace(/^(−?)USD /, "$1");
  const th = (t: string, i: number) => '<th class="x-mu x-line" align="' + (i ? "right" : "left") + '" style="padding:6px 4px;font-size:11px;font-weight:600;color:' + C.muted + ";border-bottom:1px solid " + C.line + ';white-space:nowrap;">' + esc(t) + "</th>";
  const td = (t: string, i: number, first: boolean) => '<td class="x-tx x-line" align="' + (i ? "right" : "left") + '" style="padding:6px 4px;font-size:12.5px;color:' + C.text + ";" + (first ? "font-weight:700;" : "") + "border-bottom:1px solid " + C.line + ';font-variant-numeric:tabular-nums;white-space:nowrap;">' + esc(t) + "</td>";
  const rows = history.map((h, r) => "<tr>" + [h.week.replace(/^\d{4}-/, ""), num(h.revenue), num(h.spend), num(h.margin), h.purchases, h.requests].map((t, i) => td(t, i, r === 0)).join("") + "</tr>").join("");
  return '<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"><tr>' + head.map(th).join("") + "</tr>" + rows + "</table>" + muted("USD; the newest week first; buys are purchases.", 12);
}

export interface WeeklyEmailInput {
  /** weeklyReports/{week}. */
  doc: Record<string, unknown>;
  /** The stored reports up to this week, newest first (this week's included), for the trend. */
  history: readonly Record<string, unknown>[];
  review: ReviewNote | null;
  consoleUrl: string;
  project: string;
  /** "[test] " for the test email. */
  subjectPrefix?: string;
}

export function renderWeeklyReportEmail(input: WeeklyEmailInput): ReportMail {
  const v = weeklyReportViewOf(input.doc);
  if (!v) throw new Error("the stored report has no figures this version can read");
  const week = v.week;
  const subject = (input.subjectPrefix ?? "") + SUBJECT_PREFIX + "weekly report · " + week + " (" + shortSpan(week) + ")";
  const by = new Map(v.rows.map((r) => [r.label, r]));
  const tileOf = (label: string): Tile => {
    const r = by.get(label);
    return { label, value: r?.value ?? "–", change: r?.change ?? "", caption: TILE_CAPTION[label] ?? "" };
  };
  const tileList = ["Estimated net revenue", "Model spend", "Estimated margin", "Purchases"].map(tileOf);
  const history = input.history.map(historyOf).filter((h): h is WeeklyHistoryRow => h !== null).slice(0, REPORT_EMAIL.historyWeeks);
  const note = input.review ? reviewNoteText(input.review) : null;
  const used = new Set<string>();
  let body = header(REPORT_EMAIL.brand + " · weekly report", week + " · " + v.span, "UTC, Monday to Sunday · " + (v.previousWeek ? "changes against " + v.previousWeek : "the first report, no week before to compare"));
  body += tiles(tileList, v.previousWeek ? v.previousWeek.replace(/^\d{4}-/, "") : null);
  for (const s of SECTIONS) {
    const rows = s.labels.map((l) => by.get(l)).filter((r): r is WeeklyReportRow => r !== undefined);
    rows.forEach((r) => used.add(r.label));
    if (rows.length) body += section(s.title, figureRows(rows));
  }
  const rest = v.rows.filter((r) => !used.has(r.label));
  if (rest.length) body += section("Other", figureRows(rest));
  if (v.caveats.length) body += section("Not counted in full", v.caveats.map((c) => callout(esc(c))).join(""));
  if (note) body += section("Prompt review", para(esc(note)));
  if (history.length > 1) body += section("Last " + history.length + " weeks", trendTable(history));
  body += footer(input.consoleUrl, [
    "Every figure is the server's own estimate, the owner board's arithmetic: revenue is the configured net per recipe sold, net of refunds, never a store payout; spend is worked out from token counts, never the Cloud bill.",
    "The full report: the console's card “Weekly report”, or npm run admin -- report weekly --week " + week + " --project " + input.project + ".",
    "Sent by the Monday job weeklyPromptReview to the owner's own address. To stop it, turn off “Make and email the weekly report” on the console.",
  ]);
  const preheader = tileList.map((t) => t.label.replace("Estimated ", "") + " " + t.value).join(" · ");
  const html = page(subject, preheader, body);

  const text: string[] = [subject, ""];
  text.push(...renderWeeklyReport(v));
  if (note) text.push("", note);
  if (history.length > 1) {
    text.push("", "Last " + history.length + " weeks (net revenue / model spend / margin / purchases / requests):");
    for (const h of history) text.push("  " + h.week + "  " + h.revenue + " / " + h.spend + " / " + h.margin + " / " + h.purchases + " / " + h.requests);
  }
  text.push("", "Owner console: " + input.consoleUrl, "Sent by the Monday job weeklyPromptReview. To stop it, turn off \"Make and email the weekly report\" on the console.");
  return { subject, html, text: text.join("\n") };
}

// --- The weekly prompt review --------------------------------------------------------------------

const MC = REPORT_EMAIL.modelChars;

const idsOf = (p: ReviewProblem) => (Array.isArray(p.exampleIds) ? p.exampleIds : []).slice(0, REPORT_EMAIL.maxExampleIds).map((x) => untrusted(x, MC.exampleId));
const countOf = (p: ReviewProblem) => (typeof p.count === "number" && Number.isFinite(p.count) ? Math.max(0, Math.round(p.count)) : 0);

function problemHtml(p: ReviewProblem, i: number): string {
  const ids = idsOf(p);
  const count = countOf(p);
  const meta = [code(p.issue), "target " + code(p.target), count.toLocaleString("en-GB") + " answer" + (count === 1 ? "" : "s"), "confidence " + code(p.confidence)].join(" · ");
  let out = '<div class="x-tile" style="margin:10px 0 0;padding:12px;background:' + C.tile + ";border:1px solid " + C.line + ';border-radius:10px;">'
    + '<div class="x-tx" style="font-size:14px;line-height:1.4;font-weight:700;color:' + C.text + ';">' + (i + 1) + ". " + esc(untrusted(p.title, MC.title)) + "</div>"
    + muted(meta, 12);
  if (p.cause) out += '<p class="x-tx" style="margin:8px 0 0;font-size:13px;line-height:1.5;color:' + C.text + ';"><b>Cause.</b> ' + esc(untrusted(p.cause, MC.cause)) + "</p>";
  out += '<div class="x-mu" style="margin:10px 0 0;font-size:12px;font-weight:700;color:' + C.muted + ';">Before</div>' + pre(untrusted(p.before, MC.before));
  if (p.beforeFound === false) out += callout("Not in the current prompt word for word: check it before applying.");
  out += '<div class="x-mu" style="margin:10px 0 0;font-size:12px;font-weight:700;color:' + C.muted + ';">After</div>' + pre(untrusted(p.after, MC.after));
  if (ids.length) out += muted("Examples (judgeAnswers ids): " + ids.join(", "), 12);
  return out + "</div>";
}

function problemText(p: ReviewProblem, i: number): string[] {
  const ids = idsOf(p);
  const out = ["  " + (i + 1) + ". " + untrusted(p.title, MC.title), "     " + [code(p.issue), "target " + code(p.target), countOf(p) + " answers", "confidence " + code(p.confidence)].join(", ")];
  if (p.cause) out.push("     Cause: " + untrusted(p.cause, MC.cause));
  out.push("     Before: " + JSON.stringify(untrusted(p.before, MC.before)) + (p.beforeFound === false ? "  (not in the current prompt word for word)" : ""));
  out.push("     After:  " + JSON.stringify(untrusted(p.after, MC.after)));
  if (ids.length) out.push("     Examples: " + ids.join(", "));
  return out;
}

const usd4 = (micro: number) => "USD " + (micro / 1e6).toFixed(micro > 0 && micro < 10_000 ? 4 : 3);
const int = (n: number) => Math.round(n).toLocaleString("en-GB");
const diff = (now: number, before: number | null | undefined, digits = 0) => {
  if (before === null || before === undefined || !Number.isFinite(before)) return "";
  const d = now - before;
  const shown = Math.abs(d).toFixed(digits);
  if (Number(shown) === 0) return "±0";
  return (d > 0 ? "+" : "−") + (digits ? shown : int(Math.abs(d)));
};

export interface ReviewEmailInput {
  doc: Record<string, unknown>;
  consoleUrl: string;
  project: string;
  subjectPrefix?: string;
}

export function renderPromptReviewEmail(input: ReviewEmailInput): ReportMail {
  const d = input.doc;
  const v = promptReviewViewOf(d);
  const week = v.week;
  if (v.status !== "done" && v.status !== "not_enough_data") throw new Error("a prompt review that is " + v.status + " has no email");
  type Counts = { week?: string; total?: number; notOk?: number; failed?: number; lowQuality?: number; qualityAvg?: number | null; judgeSafetyBlocks?: number; issues?: Record<string, number> };
  const a = (d.aggregates ?? {}) as Counts;
  const p = (d.previous ?? null) as Counts | null;
  const n = (x: unknown) => (typeof x === "number" && Number.isFinite(x) ? x : 0);
  const answers = v.answers ?? n(a.total);
  const subject = (input.subjectPrefix ?? "") + SUBJECT_PREFIX + "prompt review · " + week + (v.status === "not_enough_data" ? " · not enough data" : "");
  const prevWeek = typeof p?.week === "string" ? p.week : null;
  let body = header(REPORT_EMAIL.brand + " · prompt review", week + " · " + weekSpanOf(week), "The second check's answers of the week, read by " + (v.model ? untrusted(v.model, 60) : "a model") + (prevWeek ? " · changes against " + prevWeek : ""));
  const text: string[] = [subject, "", "Weekly prompt review " + week + " (" + shortSpan(week) + ", UTC)"];

  if (v.status === "not_enough_data") {
    const minAnswers = typeof d.minAnswers === "number" ? d.minAnswers : PROMPT_REVIEW.minAnswers;
    const line = reviewNoteText({ status: "not_enough_data", reason: null, answers, minAnswers })!.replace("no separate email this week.", "nothing else this week.");
    body += pad(para(esc(line)), 8, 8);
    body += footer(input.consoleUrl, ["Advice only: nothing is ever changed automatically."]);
    text.push(line, "", "Owner console: " + input.consoleUrl);
    return { subject, html: page(subject, line, body), text: text.join("\n") };
  }

  const tileList: Tile[] = [
    { label: "Answers analysed", value: int(answers), change: diff(answers, p?.total), caption: "the second check's answers" },
    { label: "Not ok", value: int(n(a.notOk)), change: diff(n(a.notOk), p?.notOk), caption: "verdicts other than ok" },
    { label: "Quality average", value: typeof a.qualityAvg === "number" ? a.qualityAvg.toFixed(2) : "–", change: typeof a.qualityAvg === "number" && typeof p?.qualityAvg === "number" ? diff(a.qualityAvg, p.qualityAvg, 2) : "", caption: "1 to 5" },
    { label: "Cost of the run", value: usd4(v.costMicroUsd), change: "", caption: v.usage ? int(v.usage.inputTokens) + " in, " + int(v.usage.outputTokens + v.usage.thinkingTokens) + " out tokens" : "booked against the day's ceiling" },
  ];
  body += tiles(tileList, prevWeek ? prevWeek.replace(/^\d{4}-/, "") : null);

  const countRows: WeeklyReportRow[] = [
    { label: "Judge failures", value: int(n(a.failed)), change: "", note: "the second check gave no verdict" },
    { label: "Quality 3 or less", value: int(n(a.lowQuality)), change: diff(n(a.lowQuality), p?.lowQuality), note: "" },
    { label: "Judge safety blocks", value: int(n(a.judgeSafetyBlocks)), change: diff(n(a.judgeSafetyBlocks), p?.judgeSafetyBlocks), note: "Vertex's filter refused the text, which says nothing about the recipe" },
  ];
  const issues = Object.entries(a.issues ?? {}).filter(([, c]) => typeof c === "number" && c > 0).sort((x, y) => y[1] - x[1]).slice(0, 8);
  for (const [k, c] of issues) countRows.push({ label: "Issue: " + code(k).replaceAll("_", " "), value: int(c), change: diff(c, p ? n(p.issues?.[k]) : null), note: "" });
  body += section("Counts", figureRows(countRows));
  const summary = untrusted(v.summary, MC.summary);
  body += section("Summary", para(esc(summary || "(none)")));
  const list = (title: string, help: string, items: readonly ReviewProblem[]) =>
    section(title, muted(help) + (items.length ? items.slice(0, REPORT_EMAIL.maxProblems).map(problemHtml).join("") : para("None this week.")));
  body += list("Prompt problems", "The recipe writer's prompt causes or allows these.", v.promptProblems);
  body += list("Judge problems", "The second check is wrong here (e.g. its safety filter on ordinary cooking).", v.judgeProblems);
  body += footer(input.consoleUrl, [
    "Advice only: nothing is changed automatically. Prompts are code (src/adapters/prompt.ts, and the judge's instruction in src/adapters/gemini.ts); a change goes through review, the red-team run and a deploy.",
    "The model's text above is shown as plain text, cut to its limits, with links disabled.",
    "Full text: npm run admin -- judge review --week " + week + " --project " + input.project + ". To stop it, turn off the weekly prompt review on the console.",
  ]);
  text.push("  answers " + int(answers) + ", not ok " + int(n(a.notOk)) + ", judge failed " + int(n(a.failed)) + ", quality <= 3 " + int(n(a.lowQuality)) + ", quality avg " + (typeof a.qualityAvg === "number" ? a.qualityAvg.toFixed(2) : "-") + ", cost " + usd4(v.costMicroUsd));
  if (issues.length) text.push("  issues: " + issues.map(([k, c]) => code(k) + " " + c).join(", "));
  text.push("", "Summary: " + (summary || "(none)"), "");
  for (const [title, items] of [["Prompt problems", v.promptProblems], ["Judge problems", v.judgeProblems]] as const) {
    text.push(title + (items.length ? ":" : ": none"));
    items.slice(0, REPORT_EMAIL.maxProblems).forEach((x, i) => text.push(...problemText(x, i)));
    text.push("");
  }
  text.push("Advice only: nothing is changed automatically.", "Owner console: " + input.consoleUrl);
  const preheader = int(answers) + " answers · " + v.promptProblems.length + " prompt and " + v.judgeProblems.length + " judge problems · " + usd4(v.costMicroUsd);
  return { subject, html: page(subject, preheader, body), text: text.join("\n") };
}
