/**
 * Renders a Board (boardData.ts) as one self-contained HTML file (inline CSS, inline SVG, a few
 * lines of inline script for the hover readout and, on the hosted board, the stale warning; no
 * external request of any kind), as a short text summary and as the small JSON of those same
 * aggregates. Pure: no I/O. Everything a person reads says "recipes", never "credits".
 */
import { PACKS, STORES, type Board, type BoardDay, type Pack, type Store, type Totals } from "./boardData";

export const BILLING_URL = "https://console.cloud.google.com/billing";
export const firebaseUsageUrl = (project: string) => `https://console.firebase.google.com/project/${encodeURIComponent(project)}/usage`;

// --- Formatting ---------------------------------------------------------------------------------

export function esc(s: string): string {
  return s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);
}

const intFmt = new Intl.NumberFormat("en-US", { maximumFractionDigits: 0 });
export const int = (n: number) => intFmt.format(Math.round(n)).replace("-", "−");

export function usd(micro: number, digits = 2): string {
  const v = Math.abs(micro) / 1e6;
  const s = v.toLocaleString("en-US", { minimumFractionDigits: digits, maximumFractionDigits: digits });
  return `${micro < 0 ? "−" : ""}$${s}`;
}

export function compact(n: number): string {
  const a = Math.abs(n);
  if (a >= 1e9) return `${(n / 1e9).toFixed(1).replace(/\.0$/, "")}B`;
  if (a >= 1e6) return `${(n / 1e6).toFixed(1).replace(/\.0$/, "")}M`;
  if (a >= 1e4) return `${(n / 1e3).toFixed(1).replace(/\.0$/, "")}K`;
  return int(n);
}

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
/** "15 Nov" for 2026-11-15. */
export function shortDay(day: string): string {
  const [, m, d] = day.split("-").map(Number);
  return `${d} ${MONTHS[m - 1]}`;
}
const utcTime = (ms: number) => `${new Date(ms).toISOString().slice(0, 16).replace("T", " ")} UTC`;
const pct = (part: number, whole: number) => (whole > 0 ? Math.round((part / whole) * 100) : 0);

export const PACK_LABEL: Record<Pack, string> = {
  recipes_10: "10-recipe pack",
  recipes_50: "50-recipe pack",
  recipes_100: "100-recipe pack",
  other: "Other product",
};
const PACK_SHORT: Record<Pack, string> = { recipes_10: "10-pack", recipes_50: "50-pack", recipes_100: "100-pack", other: "Other" };
export const STORE_LABEL: Record<Store, string> = { appstore: "App Store", play: "Google Play", other: "Other store" };
/** Which app a store's purchases come from: Google Play only from Android, the App Store only from iOS. */
export const PLATFORM_LABEL: Record<Store, string> = { play: "Android (Google Play)", appstore: "iOS (App Store)", other: "Other store" };
/** Android first; "other" (an unknown stored value) only when it has purchases. */
const PLATFORM_ORDER: readonly Store[] = ["play", "appstore", "other"];
const PLATFORM_CLS: Record<Store, string> = { play: "s1", appstore: "s2", other: "other" };

// --- Charts -------------------------------------------------------------------------------------

interface Series {
  label: string;
  /** CSS class carrying the series colour (s1..s4, other). */
  cls: string;
  values: number[];
}

interface ChartSpec {
  title: string;
  unit: string;
  days: string[];
  series: Series[];
  line?: { label: string; values: number[] };
  /**
   * Days to flag above the plot with a ▲ and a text row in the readout (e.g. "Ceiling reached"),
   * named in the legend, so the flag does not depend on colour.
   */
  marks?: { label: string; on: boolean[] };
  axis(v: number): string;
  value(v: number): string;
  /** Integer quantities get whole-number ticks. */
  integer?: boolean;
  empty?: string;
}

/** A clean axis maximum and step: steps of 1, 2, 2.5 or 5 times a power of ten. */
export function niceScale(max: number, ticks = 4, integer = false): { max: number; step: number } {
  if (!(max > 0)) return { max: integer ? 4 : 1, step: integer ? 1 : 0.25 };
  const raw = max / ticks;
  const pow = 10 ** Math.floor(Math.log10(raw));
  let step = [1, 2, 2.5, 5, 10].map((m) => m * pow).find((s) => s >= raw)!;
  if (integer) step = Math.max(1, Math.ceil(step));
  return { max: Math.ceil(max / step - 1e-9) * step, step };
}

const f1 = (n: number) => (Math.round(n * 10) / 10).toString();

/** Top-rounded bar from the baseline (4px data end, square at the baseline). */
function barPath(x: number, y: number, w: number, h: number): string {
  const r = Math.min(4, w / 2, h);
  return `M${f1(x)},${f1(y + h)}V${f1(y + r)}Q${f1(x)},${f1(y)} ${f1(x + r)},${f1(y)}H${f1(x + w - r)}Q${f1(x + w)},${f1(y)} ${f1(x + w)},${f1(y + r)}V${f1(y + h)}Z`;
}

function tipAttr(header: string, rows: [string, string, string][]): string {
  const text = [header, ...rows.map((r) => r.join("\t"))].join("\n");
  const aria = [header, ...rows.map((r) => `${r[2]} ${r[1]}`)].join(", ");
  return `data-tip="${esc(text).replace(/\n/g, "&#10;").replace(/\t/g, "&#9;")}" aria-label="${esc(aria)}"`;
}

function columnSvg(spec: ChartSpec, width: number, height: number): string {
  const n = spec.days.length;
  const totals = spec.days.map((_, i) => spec.series.reduce((s, se) => s + Math.max(0, se.values[i]), 0));
  const dataMax = Math.max(0, ...totals, ...(spec.line?.values ?? []));
  const scale = niceScale(dataMax, 4, spec.integer);
  const tickLabels: string[] = [];
  for (let v = 0; v <= scale.max + 1e-9; v += scale.step) tickLabels.push(spec.axis(v));
  const left = Math.max(...tickLabels.map((l) => l.length)) * 7 + 12;
  // Room above the plot for the ▲ marks, clear of the data labels.
  const top = spec.marks?.on.some(Boolean) ? 40 : 26;
  const bottom = 24;
  const right = 22;
  const plotW = width - left - right;
  const plotH = height - top - bottom;
  const y = (v: number) => top + plotH - (v / scale.max) * plotH;
  const band = plotW / n;
  const barW = Math.max(2, Math.min(24, band * 0.66));
  const out: string[] = [];
  out.push(`<svg viewBox="0 0 ${width} ${height}" role="img" aria-label="${esc(spec.title)}" class="chart-svg">`);
  out.push(`<text class="unit" x="2" y="11">${esc(spec.unit)}</text>`);
  tickLabels.forEach((label, i) => {
    const ty = y(i * scale.step);
    out.push(`<line class="${i === 0 ? "baseline" : "grid"}" x1="${left}" x2="${width - right}" y1="${f1(ty)}" y2="${f1(ty)}"/>`);
    out.push(`<text class="tick" x="${left - 8}" y="${f1(ty + 4)}" text-anchor="end">${esc(label)}</text>`);
  });
  // X labels: about one per 56px, always including today (the last day).
  const every = Math.max(1, Math.ceil(56 / band));
  for (let i = n - 1; i >= 0; i -= every) {
    const cx = left + band * (i + 0.5);
    out.push(`<text class="tick" x="${f1(cx)}" y="${height - 6}" text-anchor="middle">${esc(shortDay(spec.days[i]))}</text>`);
  }
  for (let i = 0; i < n; i++) {
    const x0 = left + band * i;
    const bx = x0 + (band - barW) / 2;
    out.push(`<g class="band">`);
    let acc = 0;
    const visible = spec.series.filter((s) => s.values[i] > 0);
    visible.forEach((s, k) => {
      const v = s.values[i];
      const yTop = y(acc + v);
      const yBottom = y(acc);
      // 2px surface gap between stacked segments: every segment but the lowest stops 2px short.
      const h = Math.max(0, yBottom - yTop - (k > 0 ? 2 : 0));
      if (h > 0) {
        const isTop = k === visible.length - 1;
        out.push(isTop
          ? `<path class="bar ${s.cls}" d="${barPath(bx, yTop, barW, h)}"/>`
          : `<rect class="bar ${s.cls}" x="${f1(bx)}" y="${f1(yTop)}" width="${f1(barW)}" height="${f1(h)}"/>`);
      }
      acc += v;
    });
    const rows: [string, string, string][] = spec.series.map((s) => [s.cls, spec.value(s.values[i]), s.label]);
    if (spec.line) rows.push(["ref", spec.value(spec.line.values[i]), spec.line.label]);
    if (spec.marks?.on[i]) rows.push(["mark", "yes", spec.marks.label]);
    out.push(`<rect class="hit" x="${f1(x0)}" y="${top}" width="${f1(band)}" height="${plotH}" tabindex="0" ${tipAttr(shortDay(spec.days[i]), rows)}/>`);
    out.push(`</g>`);
  }
  if (spec.line) {
    const v = spec.line.values;
    let d = "";
    for (let i = 0; i < n; i++) {
      const x0 = left + band * i;
      d += `${i === 0 ? "M" : "L"}${f1(x0)},${f1(y(v[i]))}H${f1(x0 + band)}`;
    }
    out.push(`<path class="refline" d="${d}"/>`);
  }
  if (spec.marks) {
    for (let i = 0; i < n; i++) {
      if (!spec.marks.on[i]) continue;
      const cx = left + band * (i + 0.5);
      out.push(`<text class="mark" x="${f1(cx)}" y="${top - 24}" text-anchor="middle" aria-hidden="true">▲</text>`);
    }
  }
  // Selective direct labels: the highest day, and today when it is not right beside it.
  if (dataMax > 0 && spec.series.length) {
    const maxI = totals.indexOf(Math.max(...totals));
    const label = (i: number) => {
      if (totals[i] <= 0) return;
      const cx = left + band * (i + 0.5);
      out.push(`<text class="dlabel" x="${f1(cx)}" y="${f1(y(totals[i]) - 6)}" text-anchor="middle">${esc(spec.value(totals[i]))}</text>`);
    };
    if (maxI >= 0) label(maxI);
    if (n - 1 !== maxI && Math.abs(n - 1 - maxI) * band > 60) label(n - 1);
  }
  if (dataMax <= 0 && spec.empty) {
    out.push(`<text class="empty" x="${f1(left + plotW / 2)}" y="${f1(top + plotH / 2)}" text-anchor="middle">${esc(spec.empty)}</text>`);
  }
  out.push(`</svg>`);
  return out.join("");
}

function legend(spec: ChartSpec): string {
  const items = spec.series.map((s) => `<li><span class="key box ${s.cls}"></span>${esc(s.label)}</li>`);
  if (spec.line) items.push(`<li><span class="key line"></span>${esc(spec.line.label)}</li>`);
  if (spec.marks?.on.some(Boolean)) items.push(`<li><span class="key mark" aria-hidden="true">▲</span>${esc(spec.marks.label)}</li>`);
  return items.length > 1 ? `<ul class="legend">${items.join("")}</ul>` : "";
}

function chart(spec: ChartSpec): string {
  return `<figure class="chart"><figcaption>${esc(spec.title)}</figcaption>${legend(spec)}` +
    `<div class="w-s">${columnSvg(spec, 360, 220)}</div>` +
    `<div class="w-m">${columnSvg(spec, 560, 240)}</div>` +
    `<div class="w-l">${columnSvg(spec, 840, 280)}</div></figure>`;
}

// --- Pieces -------------------------------------------------------------------------------------

function tile(label: string, value: string, note: string, extra = ""): string {
  return `<div class="tile"><div class="t-label">${esc(label)}</div><div class="t-value">${value}</div>${extra}<div class="t-note">${note}</div></div>`;
}

function status(kind: "good" | "warn" | "crit", text: string): string {
  const icon = { good: "✓", warn: "!", crit: "■" }[kind];
  return `<span class="status"><span class="s-icon ${kind}" aria-hidden="true">${icon}</span>${esc(text)}</span>`;
}

function meter(part: number, whole: number, label: string): string {
  const p = whole > 0 ? Math.min(1, part / whole) : part > 0 ? 1 : 0;
  const level = p >= 1 ? "crit" : p >= 0.8 ? "warn" : "ok";
  return `<div class="meter" role="meter" aria-valuemin="0" aria-valuemax="${whole}" aria-valuenow="${part}" aria-label="${esc(label)}">` +
    `<div class="m-fill ${level}" style="width:${(p * 100).toFixed(1)}%"></div></div>`;
}

function totalsTable(caption: string, rows: [string, Totals][]): string {
  const body = rows.map(([name, t]) =>
    `<tr><th scope="row">${esc(name)}</th><td>${int(t.purchases)}</td><td>${int(t.credits)}</td><td>${int(t.refunded)}</td><td>${int(t.net)}</td></tr>`).join("");
  return `<table class="totals"><caption>${esc(caption)}</caption><thead><tr><th scope="col"></th><th scope="col">Packs</th>` +
    `<th scope="col">Recipes</th><th scope="col">Refunded</th><th scope="col">Net</th></tr></thead><tbody>${body}</tbody></table>`;
}

interface SharePart {
  label: string;
  cls: string;
  value: number;
}

/**
 * One 100 % bar per measure (packs, recipes, ...), split by the parts, each part also written out
 * as text with its count and share, so nothing depends on colour. A measure with nothing in it
 * shows an empty track and says so.
 */
function shareBars(title: string, parts: string[][], rows: { label: string; parts: SharePart[] }[], empty: string): string {
  const legendItems = parts.map(([label, cls]) => `<li><span class="key box ${cls}"></span>${esc(label)}</li>`).join("");
  const body = rows.map((r) => {
    const whole = r.parts.reduce((sum, x) => sum + Math.max(0, x.value), 0);
    const text = whole > 0
      ? r.parts.map((x) => `${x.label} ${int(x.value)} (${pct(x.value, whole)} %)`).join(" · ")
      : empty;
    const segs = whole > 0
      ? r.parts.filter((x) => x.value > 0).map((x) => `<span class="seg ${x.cls}" style="width:${((x.value / whole) * 100).toFixed(2)}%"></span>`).join("")
      : "";
    return `<div class="sp-row"><div class="sp-label">${esc(r.label)}</div>` +
      `<div class="sp-bar" role="img" aria-label="${esc(`${r.label}: ${text}`)}">${segs}</div>` +
      `<div class="sp-text">${esc(text)}</div></div>`;
  }).join("");
  return `<figure class="chart"><figcaption>${esc(title)}</figcaption><ul class="legend">${legendItems}</ul>${body}</figure>`;
}

function stat(label: string, value: string): string {
  return `<div class="stat"><div class="st-value">${value}</div><div class="st-label">${esc(label)}</div></div>`;
}

function dailyTable(days: BoardDay[]): string {
  const cols: [string, (d: BoardDay) => string][] = [
    ["Spend (est.)", (d) => usd(d.spentMicroUsd)],
    ["Still reserved", (d) => usd(d.reservedMicroUsd)],
    ["Ceiling", (d) => usd(d.ceilingMicroUsd)],
    ["Recipes used", (d) => int(d.generationUnits)],
    ["Written", (d) => int(d.succeeded)],
    ["Content failures", (d) => int(d.contentFailures)],
    ["Infra failures", (d) => int(d.infraFailures)],
    ["Quality refusals", (d) => int(d.qualityFailures)],
    ["Picture recipes", (d) => int(d.imageRequests)],
    ["Reports", (d) => int(d.reports)],
    ["Recipes refunded", (d) => int(d.refundedCredits)],
    ["Worst-case calls", (d) => int(d.unknownUsageCalls)],
    ["Tokens in", (d) => compact(d.inputTokens)],
    ["Tokens out", (d) => compact(d.outputTokens)],
    ["Thinking", (d) => compact(d.thinkingTokens)],
    ["10-packs", (d) => int(d.sales.recipes_10.count)],
    ["50-packs", (d) => int(d.sales.recipes_50.count)],
    ["100-packs", (d) => int(d.sales.recipes_100.count)],
    ["Recipes sold", (d) => int(d.salesCredits)],
    ["Net recipes (ceiling input)", (d) => int(d.netCreditsGranted)],
    ["Sandbox purchases", (d) => int(d.sandboxPurchases)],
  ];
  const head = `<tr><th scope="col">Day (UTC)</th>${cols.map(([h]) => `<th scope="col">${esc(h)}</th>`).join("")}</tr>`;
  const rows = [...days].reverse().map((d) =>
    `<tr><th scope="row">${esc(d.day)}</th>${cols.map(([, f]) => `<td>${f(d)}</td>`).join("")}</tr>`).join("");
  return `<div class="scroll" tabindex="0" role="region" aria-label="Daily numbers"><table class="daily"><thead>${head}</thead><tbody>${rows}</tbody></table></div>`;
}

// --- The page -----------------------------------------------------------------------------------

const CSS = `
:root{color-scheme:light;--page:#f9f9f7;--surface:#fcfcfb;--ink:#0b0b0b;--ink-2:#52514e;--muted:#6f6d68;--grid:#e1e0d9;--axis:#c3c2b7;
--border:rgba(11,11,11,.10);--s1:#2a78d6;--s2:#eb6834;--s3:#1baf7a;--s4:#eda100;--other:#898781;--track:#cde2fb;
--good:#0ca30c;--warn:#fab219;--crit:#d03b3b;--good-ink:#006300;--link:#1c5cab}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){color-scheme:dark;--page:#0d0d0d;--surface:#1a1a19;--ink:#f0efec;--ink-2:#c3c2b7;
--muted:#a3a29c;--grid:#2c2c2a;--axis:#383835;--border:rgba(255,255,255,.10);--s1:#3987e5;--s2:#d95926;--s3:#199e70;--s4:#c98500;--other:#898781;
--track:#0d366b;--good-ink:#0ca30c;--link:#86b6ef}}
:root[data-theme="dark"]{color-scheme:dark;--page:#0d0d0d;--surface:#1a1a19;--ink:#f0efec;--ink-2:#c3c2b7;--muted:#a3a29c;--grid:#2c2c2a;
--axis:#383835;--border:rgba(255,255,255,.10);--s1:#3987e5;--s2:#d95926;--s3:#199e70;--s4:#c98500;--other:#898781;--track:#0d366b;
--good-ink:#0ca30c;--link:#86b6ef}
*{box-sizing:border-box}
html{-webkit-text-size-adjust:100%}
body{margin:0;background:var(--page);color:var(--ink);font:15px/1.45 system-ui,-apple-system,"Segoe UI",sans-serif}
main{max-width:1040px;margin:0 auto;padding:20px 16px 40px}
h1{font-size:22px;margin:0 0 4px}
h2{font-size:17px;margin:0 0 12px}
.sub{color:var(--ink-2);margin:0 0 20px}
a{color:var(--link)}
section{background:var(--surface);border:1px solid var(--border);border-radius:12px;padding:16px;margin:0 0 16px;min-width:0}
.tiles{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px}
.tile{border:1px solid var(--border);border-radius:10px;padding:12px;min-width:0}
.t-label{color:var(--ink-2);font-size:13px}
.t-value{font-size:24px;font-weight:600;margin:2px 0 6px}
.t-note{color:var(--ink-2);font-size:13px}
.status{display:inline-flex;align-items:center;gap:8px}
.s-icon{display:inline-grid;place-items:center;width:22px;height:22px;border-radius:50%;font-size:13px;font-weight:700;color:#fff}
.s-icon.good{background:var(--good)}.s-icon.warn{background:var(--warn);color:#0b0b0b}.s-icon.crit{background:var(--crit)}
.meter{height:8px;border-radius:4px;background:var(--track);overflow:hidden;margin:0 0 6px}
.m-fill{height:100%;border-radius:4px}.m-fill.ok{background:var(--s1)}.m-fill.warn{background:var(--warn)}.m-fill.crit{background:var(--crit)}
.alert{border-left:4px solid var(--crit);background:var(--surface);padding:8px 12px;margin:0 0 16px;font-weight:600}
.alert.warn{border-color:var(--warn)}
.note{border-left:3px solid var(--axis);padding:4px 0 4px 12px;color:var(--ink-2);font-size:14px;margin:12px 0 0}
.stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;margin:12px 0 0}
.st-value{font-size:19px;font-weight:600}.st-label{color:var(--ink-2);font-size:13px}
figure.chart{margin:0}
figcaption{font-weight:600;margin:0 0 6px}
.legend{list-style:none;display:flex;flex-wrap:wrap;gap:4px 16px;margin:0 0 6px;padding:0;color:var(--ink-2);font-size:13px}
.legend li{display:inline-flex;align-items:center;gap:6px}
.key.box{width:10px;height:10px;border-radius:2px;display:inline-block;background:var(--s1)}
.key.line{width:16px;height:0;border-top:2px solid var(--ink-2);display:inline-block}
.split{margin:16px 0 0}
.sp-row{display:grid;grid-template-columns:112px 1fr;gap:2px 12px;align-items:center;margin:0 0 10px}
.sp-label{color:var(--ink-2);font-size:13px}
.sp-bar{display:flex;gap:2px;height:14px;border-radius:4px;overflow:hidden;background:var(--grid)}
.sp-text{grid-column:2;color:var(--ink-2);font-size:13px;font-variant-numeric:tabular-nums}
.seg{display:block;height:100%}.seg.s1{background:var(--s1)}.seg.s2{background:var(--s2)}.seg.other{background:var(--other)}
.key.s1{background:var(--s1)}.key.s2{background:var(--s2)}.key.s3{background:var(--s3)}.key.s4{background:var(--s4)}.key.other{background:var(--other)}
.chart-svg{display:block;width:100%;height:auto;overflow:visible}
.chart-svg text{font:11px system-ui,-apple-system,"Segoe UI",sans-serif;font-variant-numeric:tabular-nums}
.chart-svg .tick,.chart-svg .unit{fill:var(--muted)}
.chart-svg .dlabel{fill:var(--ink-2);font-weight:600}
.chart-svg .empty{fill:var(--muted);font-size:13px;paint-order:stroke;stroke:var(--surface);stroke-width:6px;stroke-linejoin:round}
.chart-svg .grid{stroke:var(--grid);stroke-width:1}
.chart-svg .baseline{stroke:var(--axis);stroke-width:1}
.chart-svg .mark{fill:var(--crit);font-size:11px}
.key.mark{color:var(--crit);font-size:11px;line-height:1}
.chart-svg .refline{fill:none;stroke:var(--ink-2);stroke-width:2;stroke-linejoin:round;stroke-linecap:round}
.bar.s1{fill:var(--s1)}.bar.s2{fill:var(--s2)}.bar.s3{fill:var(--s3)}.bar.s4{fill:var(--s4)}.bar.other{fill:var(--other)}
.hit{fill:transparent;outline:none}
.band:hover .bar,.band:focus-within .bar{opacity:.78}
.band:focus-within .hit{stroke:var(--axis);stroke-width:1}
.w-m,.w-l{display:none}
@media (min-width:600px){.w-s{display:none}.w-m{display:block}}
@media (min-width:920px){.w-m{display:none}.w-l{display:block}}
table{border-collapse:collapse;font-size:13px;font-variant-numeric:tabular-nums}
caption{text-align:left;font-weight:600;padding:0 0 6px}
th,td{padding:5px 8px;border-bottom:1px solid var(--grid);text-align:right;white-space:nowrap}
th:first-child{text-align:left}
thead th{color:var(--ink-2);font-weight:600;vertical-align:bottom}
.pair{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(360px,100%),1fr));gap:16px;margin:16px 0 0}
.pair .scroll{min-width:0}
.scroll{overflow-x:auto;max-width:100%}
.daily thead th{white-space:normal;min-width:64px}
.daily tbody th,.daily thead th:first-child{position:sticky;left:0;background:var(--surface)}
footer{color:var(--ink-2);font-size:13px}
footer p{margin:4px 0}
#tip{position:fixed;z-index:10;pointer-events:none;background:var(--surface);color:var(--ink);border:1px solid var(--border);
border-radius:8px;padding:8px 10px;font-size:13px;box-shadow:0 4px 16px rgba(0,0,0,.18);min-width:150px}
#tip .t-h{color:var(--ink-2);margin:0 0 4px}
#tip .row{display:flex;align-items:center;gap:8px}
#tip .row b{font-variant-numeric:tabular-nums}
#tip .row span:last-child{color:var(--ink-2)}
#tip .k{width:12px;height:0;border-top:2px solid var(--ink-2);display:inline-block}
#tip .k.s1{border-color:var(--s1)}#tip .k.s2{border-color:var(--s2)}#tip .k.s3{border-color:var(--s3)}#tip .k.s4{border-color:var(--s4)}#tip .k.other{border-color:var(--other)}
@media (forced-colors:active){.bar,.m-fill,.seg{forced-color-adjust:none}}
`;

// The hover / focus readout. Text goes in with textContent only.
const SCRIPT = `
(function(){var tip=document.getElementById("tip");if(!tip)return;
function fill(el){tip.textContent="";var lines=el.getAttribute("data-tip").split("\\n");
var h=document.createElement("div");h.className="t-h";h.textContent=lines[0];tip.appendChild(h);
for(var i=1;i<lines.length;i++){var p=lines[i].split("\\t");var r=document.createElement("div");r.className="row";
var k=document.createElement("span");k.className="k "+p[0];var b=document.createElement("b");b.textContent=p[1];
var l=document.createElement("span");l.textContent=p[2];r.appendChild(k);r.appendChild(b);r.appendChild(l);tip.appendChild(r);}
tip.hidden=false;}
function place(x,y){var w=tip.offsetWidth,h=tip.offsetHeight;var left=x+14;if(left+w>innerWidth-8)left=x-w-14;if(left<8)left=8;
var top=y-h-12;if(top<8)top=y+16;tip.style.left=left+"px";tip.style.top=top+"px";}
document.addEventListener("pointerover",function(e){var el=e.target.closest&&e.target.closest("[data-tip]");if(!el){tip.hidden=true;return;}fill(el);place(e.clientX,e.clientY);});
document.addEventListener("pointermove",function(e){if(!tip.hidden)place(e.clientX,e.clientY);});
document.addEventListener("focusin",function(e){var el=e.target.closest&&e.target.closest("[data-tip]");if(!el)return;fill(el);var r=el.getBoundingClientRect();place(r.left+r.width/2,r.top+16);});
document.addEventListener("focusout",function(){tip.hidden=true;});
document.addEventListener("pointerleave",function(){tip.hidden=true;});})();
`;

/** The hosted board (scheduled `redrawBoard`, README "The board", "Always on"). */
export interface HostedOptions {
  /** How often the function redraws, in hours, for the "redrawn every …" line. */
  everyHours: number;
  /** Past this age the page's own script shows the stale warning. */
  staleAfterHours: number;
  /**
   * The board's own address (the authenticated browser URL). The browser's refresh button asks
   * again for the short-lived download address Google redirected to, which has expired by then,
   * so the page offers this link instead.
   */
  reloadUrl?: string;
}

/**
 * The stale warning: reads the generation time from the page and, once the page is older than
 * the threshold, shows the banner. Text goes in with textContent only; no request is made.
 */
export const STALE_SCRIPT = `
(function(){var el=document.getElementById("stale");if(!el)return;
var at=Number(el.getAttribute("data-generated")),limit=Number(el.getAttribute("data-stale-hours"));
function check(){var h=(Date.now()-at)/3600000;if(h>limit){el.textContent="This board is "+Math.floor(h)+" hours old: the hourly redraw has stopped. Look for board_redraw_failed or board_bucket_public in the function's logs.";el.hidden=false;}}
check();setInterval(check,60000);})();
`;

function cappedAlert(b: Board): string {
  const c = b.purchasesCapped;
  if (!c) return "";
  return `<p class="alert warn" role="status">This period has ${esc(int(c.total))} purchases, more than the board reads in one run: ` +
    `only the newest ${esc(int(c.read))} were counted, back to ${esc(utcTime(c.oldestReadMs))}. Purchase figures before that are missing; ` +
    `spend, generations and the ceiling are complete.</p>`;
}

export function renderBoardHtml(b: Board, hosted?: HostedOptions): string {
  const t = b.todayRow;
  const dayList = b.days.map((d) => d.day);
  const packsShown = PACKS.filter((p) => p !== "other" || b.sales.byPack.other.purchases > 0);
  const packCls: Record<Pack, string> = { recipes_10: "s1", recipes_50: "s2", recipes_100: "s3", other: "other" };

  const glance = [
    tile("Recipe generation",
      b.generationEnabled ? status("good", "On") : status("crit", "Paused"),
      b.generationEnabled ? "Kill switch off: <code>generationEnabled</code> is true." : "Kill switch on: nobody can generate until <code>generationEnabled</code> is set back to true."),
    tile("Sandbox purchases",
      b.allowSandbox ? status("warn", "On") : status("good", "Off"),
      b.allowSandbox ? "Test purchases (TestFlight, licence testers) grant recipes. Turn off after testing." : "Test purchases grant no recipes."),
    tile("Estimated spend today",
      esc(usd(b.todayBookedMicroUsd)),
      `${pct(b.todayBookedMicroUsd, t.ceilingMicroUsd)} % of today's ceiling, ${esc(usd(t.ceilingMicroUsd))}` +
        (t.reservedMicroUsd > 0 ? ` (includes ${esc(usd(t.reservedMicroUsd))} reserved for calls in flight)` : ""),
      meter(b.todayBookedMicroUsd, t.ceilingMicroUsd, "Estimated spend today against the ceiling")),
    tile("Recipes used today",
      esc(int(t.generationUnits)),
      `of the daily cap, ${esc(int(b.dailyCap))} (<code>dailyCap</code>); ${esc(int(t.succeeded))} written so far`,
      meter(t.generationUnits, b.dailyCap, "Recipes used today against the daily cap")),
    tile("Recipes sold today",
      esc(int(t.salesCredits)),
      `${esc(int(t.salesCount))} ${t.salesCount === 1 ? "pack" : "packs"}, production only` +
        (t.sandboxPurchases > 0 ? `; plus ${esc(int(t.sandboxPurchases))} sandbox` : "")),
    tile("Free recipes (welcome grant)",
      esc(int(t.welcomeGrants)),
      `${t.welcomeGrants === 1 ? "grant" : "grants"} today (${esc(int(t.welcomeCredits))} recipes) of at most ${esc(int(b.welcome.perDay))} a day; ` +
        `${esc(int(b.period.welcomeGrants))} in the period (${esc(int(b.period.welcomeCredits))} recipes). ` +
        (b.welcome.recipes > 0 ? `${esc(int(b.welcome.recipes))} per sign-in. Not sales.` : "Off (<code>welcome.recipes</code> is 0)."),
      meter(t.welcomeGrants, b.welcome.perDay, "Welcome grants today against the daily limit")),
    tile("Unspent recipes",
      esc(int(b.holders.recipes)),
      `held by ${esc(int(b.holders.accounts))} ${b.holders.accounts === 1 ? "account" : "accounts"} (positive balances now)`),
    tile("Negative balances",
      esc(int(b.ledger.negativeBalanceTotal)),
      `recipes owed after refunds of used recipes; ${b.ledger.checkedAtMs ? `last summed by the sweeper ${esc(utcTime(b.ledger.checkedAtMs))}` : "not summed yet by the sweeper"}`),
  ].join("");

  const ceilingNote = b.days.some((d) => d.ceilingSource === "recorded")
    ? "past days: the ceiling their reservations were checked against; days without one, and today, from today's config"
    : "from today's config (the ceiling a day was checked against is recorded from now on)";
  const spendChart = chart({
    title: "Model spend per day",
    unit: "USD",
    days: dayList,
    series: [
      { label: "Spent (settled)", cls: "s1", values: b.days.map((d) => d.spentMicroUsd) },
      { label: "Reserved for calls in flight", cls: "s4", values: b.days.map((d) => d.reservedMicroUsd) },
    ],
    line: { label: "Daily ceiling", values: b.days.map((d) => d.ceilingMicroUsd) },
    marks: { label: "Ceiling reached: generation refused", on: b.days.map((d) => d.ceilingReached) },
    axis: (v) => usd(v),
    value: (v) => usd(v),
  });
  const welcomeChart = chart({
    title: "Free recipes: welcome grants per day",
    unit: "grants",
    days: dayList,
    series: [{ label: "Welcome grants", cls: "s3", values: b.days.map((d) => d.welcomeGrants) }],
    line: { label: `Daily limit now (welcome.perDay ${int(b.welcome.perDay)})`, values: b.days.map(() => b.welcome.perDay) },
    marks: { label: "Day's limit reached", on: b.days.map((d) => b.welcome.perDay > 0 && d.welcomeGrants >= b.welcome.perDay) },
    axis: (v) => int(v),
    value: (v) => int(v),
    integer: true,
    empty: "No welcome grants in this period",
  });
  const reachedDays = b.days.filter((d) => d.ceilingReached).map((d) => shortDay(d.day));
  const capDays = b.days.filter((d) => d.dailyCapReached).map((d) => shortDay(d.day));

  const salesChart = chart({
    title: "Packs sold per day (production)",
    unit: "packs",
    days: dayList,
    series: packsShown.map((p) => ({ label: PACK_LABEL[p], cls: packCls[p], values: b.days.map((d) => d.sales[p].count) })),
    axis: (v) => int(v),
    value: (v) => int(v),
    integer: true,
    empty: "No production purchases in this period",
  });

  const storesShown = PLATFORM_ORDER.filter((k) => k !== "other" || b.sales.byStore.other.purchases > 0);
  const storeChart = chart({
    title: "Purchases per day by platform (production)",
    unit: "packs",
    days: dayList,
    series: storesShown.map((k) => ({ label: PLATFORM_LABEL[k], cls: PLATFORM_CLS[k], values: b.days.map((d) => d.salesByStore[k].count) })),
    axis: (v) => int(v),
    value: (v) => int(v),
    integer: true,
    empty: "No production purchases in this period",
  });
  const storeSplit = shareBars(
    "Split by platform (production, this period)",
    storesShown.map((k) => [PLATFORM_LABEL[k], PLATFORM_CLS[k]]),
    [
      { label: "Packs", parts: storesShown.map((k) => ({ label: PLATFORM_LABEL[k], cls: PLATFORM_CLS[k], value: b.sales.byStore[k].purchases })) },
      { label: "Recipes granted", parts: storesShown.map((k) => ({ label: PLATFORM_LABEL[k], cls: PLATFORM_CLS[k], value: b.sales.byStore[k].credits })) },
      { label: "Net recipes", parts: storesShown.map((k) => ({ label: PLATFORM_LABEL[k], cls: PLATFORM_CLS[k], value: b.sales.byStore[k].net })) },
      ...packsShown.map((pk) => ({
        label: `${PACK_SHORT[pk]}s`,
        parts: storesShown.map((k) => ({ label: PLATFORM_LABEL[k], cls: PLATFORM_CLS[k], value: b.sales.byStoreAndPack[k][pk].purchases })),
      })),
    ],
    "No production purchases in this period",
  );
  const PLATFORM_SHORT: Record<Store, string> = { play: "Android", appstore: "iOS", other: "Other store" };
  const storePackRows = storesShown.flatMap((k) => packsShown
    .map((pk) => [`${PLATFORM_SHORT[k]}, ${PACK_SHORT[pk]}`, b.sales.byStoreAndPack[k][pk]] as [string, Totals]));

  const genChart = chart({
    title: "Answers per day",
    unit: "answers",
    days: dayList,
    series: [
      { label: "Written", cls: "s1", values: b.days.map((d) => d.succeeded) },
      { label: "Content failures", cls: "s2", values: b.days.map((d) => d.contentFailures) },
      { label: "Infrastructure failures", cls: "s3", values: b.days.map((d) => d.infraFailures) },
      { label: "Quality refusals", cls: "s4", values: b.days.map((d) => d.qualityFailures) },
    ],
    axis: (v) => int(v),
    value: (v) => int(v),
    integer: true,
    empty: "No generations in this period",
  });

  const periodSpend = b.period.spentMicroUsd + b.period.reservedMicroUsd;
  const usage = firebaseUsageUrl(b.project);
  const s = b.sales;
  const p = b.period;

  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:">
<title>Recipe packs board</title><style>${CSS}</style></head>
<body><main>
<h1>Recipe packs board</h1>
<p class="sub">${esc(b.project)} · ${esc(b.firstDay)} to ${esc(b.today)} (${b.days.length} UTC days) · generated ${esc(utcTime(b.generatedAtMs))}</p>
${hosted ? `<p class="sub" id="updated">Updated ${esc(utcTime(b.generatedAtMs))}, redrawn ${hosted.everyHours === 1 ? "hourly" : `every ${esc(int(hosted.everyHours))} hours`}. ` +
  `If that is more than ${esc(int(hosted.staleAfterHours))} hours ago, the redraw has stopped.` +
  `${hosted.reloadUrl ? ` <a id="reload" href="${esc(hosted.reloadUrl)}">Reload the board</a> (the browser's refresh button stops working after a while).` : ""}</p>
<p class="alert" id="stale" role="alert" data-generated="${b.generatedAtMs}" data-stale-hours="${hosted.staleAfterHours}" hidden></p>
` : ""}${cappedAlert(b)}
<section aria-labelledby="h-today"><h2 id="h-today">Today at a glance (${esc(b.today)}, UTC)</h2><div class="tiles">${glance}</div></section>

<section aria-labelledby="h-spend"><h2 id="h-spend">Model spend</h2>
<div class="pair"><div>${spendChart}</div><div>${welcomeChart}</div></div>
<p class="note">Ceiling reached (a reservation refused at the spend ceiling, <code>GENERATION_DISABLED</code>): ${reachedDays.length ? esc(reachedDays.join(", ")) : "no day in this period"}.
Daily recipe cap (<code>dailyCap</code>) reached: ${capDays.length ? esc(capDays.join(", ")) : "no day in this period"}.
Daily ceiling: ${esc(ceilingNote)}. Today's bar includes ${esc(usd(t.reservedMicroUsd))} still reserved at the worst case for calls in flight;
settling replaces it by the measured cost. Free recipes are not sales and do not raise the ceiling.</p>
<div class="stats">
${stat(`${b.month.label} to date`, esc(usd(b.month.toDateMicroUsd)))}
${stat(`At most by month end, if every remaining day (${b.month.remainingDays}) reached the ${usd(b.dailySpendMaxMicroUsd)} maximum`, esc(usd(b.month.worstCaseMicroUsd)))}
${stat("This period", esc(usd(periodSpend)))}
</div>
<p class="note">These amounts are the server's own estimate, from the token counts of each call and the price table
(${esc(usd(b.prices.inputUsdPerMTok * 1e6))} in / ${esc(usd(b.prices.outputUsdPerMTok * 1e6))} out per 1M tokens), booked on the day each call was reserved.
They are not the Google Cloud bill. The real bill and the budget are in the <a href="${BILLING_URL}">Cloud billing console</a>,
and Firestore and Functions usage on the <a href="${esc(usage)}">Firebase usage page</a>.
The ceiling is ${esc(usd(b.dailySpendBaseMicroUsd))} plus a share of the last 7 days' net recipes sold, at most ${esc(usd(b.dailySpendMaxMicroUsd))}
(today's <code>config/runtime</code>); a past day shows the ceiling its reservations were checked against when the server recorded it
(<code>stats/{day}.ceilingMicroUsd</code>), otherwise the same formula with today's config.</p>
</section>

<section aria-labelledby="h-sales"><h2 id="h-sales">Purchases</h2>
${salesChart}
<div class="stats">
${stat("Recipes granted", esc(int(s.total.credits)))}
${stat("Refunded since", esc(int(s.total.refunded)))}
${stat("Net recipes", esc(int(s.total.net)))}
${stat(`Estimated net revenue (config's conservative ${usd(b.netMicroUsdPerCredit)} a recipe; not the store payout)`, esc(usd(s.estimatedNetMicroUsd)))}
</div>
<div class="pair">
<div class="scroll">${totalsTable("By pack", packsShown.map((k) => [PACK_SHORT[k], s.byPack[k]] as [string, Totals]))}</div>
<div class="scroll">${totalsTable("By store", STORES.filter((k) => k !== "other" || s.byStore.other.purchases > 0).map((k) => [STORE_LABEL[k], s.byStore[k]] as [string, Totals]))}</div>
</div>
<div class="split">${storeChart}</div>
<div class="pair">
<div>${storeSplit}</div>
<div class="scroll">${totalsTable("By platform and pack", storePackRows)}</div>
</div>
<p class="note">Production purchases granted in the period, by the day they were granted. "Refunded since" counts store refunds of
those purchases up to now, whenever they came. Sandbox purchases are never counted as sales:
${esc(int(s.sandbox.purchases))} in the period, worth ${esc(int(s.sandbox.credits))} recipes.</p>
</section>

<section aria-labelledby="h-gen"><h2 id="h-gen">Generations</h2>
${genChart}
<div class="stats">
${stat("Recipes used (reserved)", esc(int(p.generationUnits)))}
${stat("Recipes refunded after a failure", esc(int(p.refundedCredits)))}
${stat("Calls booked at worst case (usage unknown)", esc(int(p.unknownUsageCalls)))}
${stat("Tokens in / out / thinking", `${esc(compact(p.inputTokens))} / ${esc(compact(p.outputTokens))} / ${esc(compact(p.thinkingTokens))}`)}
${stat("Average estimated cost per written answer", b.avgPerAnswerMicroUsd === null ? "–" : esc(usd(b.avgPerAnswerMicroUsd, 3)))}
${stat("Average estimated cost per recipe kept", b.avgPerRecipeMicroUsd === null ? "–" : esc(usd(b.avgPerRecipeMicroUsd, 3)))}
</div>
<p class="note">An answer is one recipe or one menu (a recipe costs ${esc(int(b.costs.recipe))}; a 3-day menu ${esc(int(b.costs.menu2))} or ${esc(int(b.costs.menu3))} recipes with 2 or 3 courses; menus of 1 to 4 days and 1 to 3 courses ${esc(int(b.costs.menuSets["1"]["1"]))} to ${esc(int(b.costs.menuSets["4"]["3"]))}).
Quality refusals are always refunded; failures are refunded or charged by the server's rules.</p>
</section>

<section aria-labelledby="h-daily"><h2 id="h-daily">Daily numbers</h2>${dailyTable(b.days)}</section>

<footer>
<p>Period ${esc(b.firstDay)} to ${esc(b.today)} (UTC) · project ${esc(b.project)} · generated ${esc(utcTime(b.generatedAtMs))} · ${esc(int(b.reads))} Firestore reads.</p>
<p>Read-only: built from <code>stats/</code>, <code>config/runtime</code>, the period's <code>purchases/</code> and one aggregate over account balances. Aggregates only; nothing on this page identifies a person or a purchase, and ${hosted
  ? "it is redrawn by the scheduled function <code>redrawBoard</code> into a private Cloud Storage bucket that only the owner can read"
  : "it was written to a local file, not uploaded"}.</p>
</footer>
</main><div id="tip" role="tooltip" hidden></div><script>${SCRIPT}${hosted ? STALE_SCRIPT : ""}</script></body></html>
`;
}

/** The short text the command prints. */
export function boardSummary(b: Board): string[] {
  const t = b.todayRow;
  const s = b.sales;
  const p = b.period;
  return [
    `Board for ${b.project}, ${b.firstDay} to ${b.today} (${b.days.length} UTC days), generated ${utcTime(b.generatedAtMs)}.`,
    `Generation ${b.generationEnabled ? "on" : "PAUSED (kill switch)"}; sandbox purchases ${b.allowSandbox ? "ON" : "off"}.`,
    `Today: estimated spend ${usd(b.todayBookedMicroUsd)} of a ${usd(t.ceilingMicroUsd)} ceiling (${pct(b.todayBookedMicroUsd, t.ceilingMicroUsd)} %); ` +
      `${int(t.generationUnits)} of ${int(b.dailyCap)} recipes used; ${int(t.salesCount)} packs sold (${int(t.salesCredits)} recipes).`,
    `Unspent recipes: ${int(b.holders.recipes)} on ${int(b.holders.accounts)} accounts. Negative balances: ${int(b.ledger.negativeBalanceTotal)}.`,
    `Spend per day against the ceiling: ${b.days.filter((d) => d.ceilingReached).length} days reached the ceiling` +
      (b.days.some((d) => d.ceilingReached) ? ` (${b.days.filter((d) => d.ceilingReached).map((d) => d.day).join(", ")})` : "") +
      `; ${b.days.filter((d) => d.dailyCapReached).length} days reached dailyCap; highest day ${usd(Math.max(0, ...b.days.map((d) => d.spentMicroUsd + d.reservedMicroUsd)))}.`,
    `Spend (server estimate, not the Cloud bill): period ${usd(p.spentMicroUsd + p.reservedMicroUsd)}; ${b.month.label} to date ${usd(b.month.toDateMicroUsd)}, ` +
      `at most ${usd(b.month.worstCaseMicroUsd)} by month end.`,
    `Sales (production): ${int(s.total.purchases)} packs, ${int(s.total.credits)} recipes granted, ${int(s.total.refunded)} refunded, ${int(s.total.net)} net; ` +
      `estimated net revenue ${usd(s.estimatedNetMicroUsd)} (config estimate, not the store payout). ` +
      `iOS (App Store) ${int(s.byStore.appstore.purchases)} packs, ${int(s.byStore.appstore.credits)} recipes; ` +
      `Android (Google Play) ${int(s.byStore.play.purchases)} packs, ${int(s.byStore.play.credits)} recipes. Sandbox: ${int(s.sandbox.purchases)} purchases, ${int(s.sandbox.credits)} recipes.`,
    `Free recipes (welcome grant, not sales): ${int(t.welcomeGrants)} grants today (${int(t.welcomeCredits)} recipes) of at most ${int(b.welcome.perDay)}; ` +
      `${int(p.welcomeGrants)} in the period (${int(p.welcomeCredits)} recipes); ${b.welcome.recipes > 0 ? `${int(b.welcome.recipes)} per sign-in` : "off"}.`,
    `Generations: ${int(p.succeeded)} written, ${int(p.contentFailures)} content failures, ${int(p.infraFailures)} infrastructure failures, ` +
      `${int(p.qualityFailures)} quality refusals; ${int(p.refundedCredits)} recipes refunded; ${int(p.unknownUsageCalls)} calls at worst case.`,
    `Pictures: ${int(p.imageRequests)} recipes asked with pictures, ${int(p.imagesDelivered)} pictures delivered, ${int(p.imageFailures)} not. ` +
      `Reports (recipe_reported): ${int(t.reports)} today, ${int(p.reports)} in the period.`,
    ...(b.purchasesCapped
      ? [`PURCHASES CAPPED: ${int(b.purchasesCapped.total)} in the period, only the newest ${int(b.purchasesCapped.read)} counted (back to ${utcTime(b.purchasesCapped.oldestReadMs)}).`]
      : []),
    `Firestore reads: ${int(b.reads)}.`,
  ];
}

/**
 * The same aggregates as the summary, as JSON (`board.json` beside the hosted board). Amounts are
 * micro-USD integers, as in `stats`; counts are recipes or packs. Nothing identifies a person.
 */
export function boardJson(b: Board): Record<string, unknown> {
  const t = b.todayRow;
  const s = b.sales;
  const p = b.period;
  const totals = (x: Totals) => ({ packs: x.purchases, recipes: x.credits, refunded: x.refunded, net: x.net });
  return {
    project: b.project,
    generatedAt: new Date(b.generatedAtMs).toISOString(),
    period: { firstDay: b.firstDay, lastDay: b.today, days: b.days.length },
    generationEnabled: b.generationEnabled,
    allowSandbox: b.allowSandbox,
    today: {
      day: b.today,
      estimatedSpendMicroUsd: b.todayBookedMicroUsd,
      ceilingMicroUsd: t.ceilingMicroUsd,
      recipesUsed: t.generationUnits,
      dailyCap: b.dailyCap,
      written: t.succeeded,
      packsSold: t.salesCount,
      recipesSold: t.salesCredits,
      sandboxPurchases: t.sandboxPurchases,
    },
    welcome: {
      recipesPerClaim: b.welcome.recipes,
      perDay: b.welcome.perDay,
      grantsToday: t.welcomeGrants,
      recipesToday: t.welcomeCredits,
      grantsInPeriod: p.welcomeGrants,
      recipesInPeriod: p.welcomeCredits,
    },
    unspentRecipes: { recipes: b.holders.recipes, accounts: b.holders.accounts },
    negativeBalanceTotal: b.ledger.negativeBalanceTotal,
    spendPerDay: b.days.map((d) => ({
      day: d.day,
      spentMicroUsd: d.spentMicroUsd,
      reservedMicroUsd: d.reservedMicroUsd,
      ceilingMicroUsd: d.ceilingMicroUsd,
      ceilingSource: d.ceilingSource,
      ceilingReached: d.ceilingReached,
      thresholdsLogged: d.spendThresholds,
      dailyCapReached: d.dailyCapReached,
      welcomeGrants: d.welcomeGrants,
    })),
    spend: {
      periodMicroUsd: p.spentMicroUsd + p.reservedMicroUsd,
      month: b.month.label,
      monthToDateMicroUsd: b.month.toDateMicroUsd,
      monthWorstCaseMicroUsd: b.month.worstCaseMicroUsd,
    },
    sales: {
      ...totals(s.total),
      estimatedNetRevenueMicroUsd: s.estimatedNetMicroUsd,
      byStore: { appstore: totals(s.byStore.appstore), play: totals(s.byStore.play) },
      byStoreAndPack: {
        appstore: Object.fromEntries(PACKS.map((k) => [k, totals(s.byStoreAndPack.appstore[k])])),
        play: Object.fromEntries(PACKS.map((k) => [k, totals(s.byStoreAndPack.play[k])])),
      },
      byPack: Object.fromEntries(PACKS.map((k) => [k, totals(s.byPack[k])])),
      sandbox: { purchases: s.sandbox.purchases, recipes: s.sandbox.credits },
      capped: b.purchasesCapped ? { total: b.purchasesCapped.total, read: b.purchasesCapped.read } : null,
    },
    generations: {
      written: p.succeeded,
      contentFailures: p.contentFailures,
      infraFailures: p.infraFailures,
      qualityRefusals: p.qualityFailures,
      pictureRecipes: p.imageRequests,
      picturesDelivered: p.imagesDelivered,
      pictureFailures: p.imageFailures,
      reports: p.reports,
      recipesUsed: p.generationUnits,
      recipesRefunded: p.refundedCredits,
      unknownUsageCalls: p.unknownUsageCalls,
    },
    firestoreReads: b.reads,
  };
}
