/**
 * The owner's board (README "The board"): pure aggregation of what the server already keeps
 * (`stats/{day}`, `stats/ledger`, `config/runtime`, the period's `purchases/`, one aggregation over
 * `users`) into the figures the board and its text summary show. No Firestore here: the reader
 * (boardReader.ts) hands over plain values, and nothing that identifies a person or a purchase
 * (uid, token, order or transaction id) ever reaches this module (see `purchaseRow`).
 */
import { REVENUE_WINDOW_DAYS } from "../constants";
import { dailySpendCeiling, utcDay, type RuntimeConfig } from "../model";

const DAY_MS = 86_400_000;

/** The numeric fields of `stats/{yyyymmdd}` the board reads; a missing or non-numeric one is 0. */
export const STAT_FIELDS = [
  "spentMicroUsd",
  "reservedMicroUsd",
  "generations",
  "generationUnits",
  "llmCalls",
  "inputTokens",
  "outputTokens",
  "thinkingTokens",
  "succeeded",
  "contentFailures",
  "infraFailures",
  "qualityFailures",
  "refundedCredits",
  "unknownUsageCalls",
  "netCreditsGranted",
  "sandboxGrants",
  "sandboxCredits",
  "welcomeGrants",
  "welcomeCredits",
] as const;
export type StatField = (typeof STAT_FIELDS)[number];
export type DayStats = Record<StatField, number>;

export const PACKS = ["recipes_10", "recipes_50", "recipes_100", "other"] as const;
export type Pack = (typeof PACKS)[number];
export const STORES = ["appstore", "play", "other"] as const;
export type Store = (typeof STORES)[number];

/** One purchase as the board sees it: no uid, token, order id or transaction id. */
export interface PurchaseRow {
  store: Store;
  /** Anything but "production" is treated as sandbox, so it can never count as a sale. */
  env: "production" | "sandbox";
  pack: Pack;
  credits: number;
  refundedCredits: number;
  grantedAtMs: number;
}

export interface BoardInput {
  project: string;
  generatedAtMs: number;
  /** Days in the period, today (UTC) included. */
  days: number;
  config: RuntimeConfig;
  /** Raw `stats/{day}` data keyed by YYYY-MM-DD, for every day `boardStatsDays` lists (absent: no doc). */
  stats: ReadonlyMap<string, Record<string, unknown> | undefined>;
  /** Purchases granted in the period (by `grantedAt`). */
  purchases: readonly PurchaseRow[];
  ledger: { negativeBalanceTotal: number; checkedAtMs: number | null };
  /** Accounts with a positive balance and the sum of those balances (unspent recipes). */
  holders: { accounts: number; recipes: number };
  /** Firestore reads the run cost (documents + aggregation), for the footer and the summary. */
  reads: number;
  /**
   * Set when the period held more purchases than the reader's cap: only the newest `read` were
   * read (back to `oldestReadMs`), out of `total`. Purchase figures before that are missing.
   */
  purchasesCapped?: { total: number; read: number; oldestReadMs: number } | null;
}

export interface Totals {
  purchases: number;
  credits: number;
  refunded: number;
  net: number;
}

export interface BoardDay extends DayStats {
  day: string;
  /**
   * The day's spend ceiling: for a past day the one its last reservation was checked against
   * (`stats/{day}.ceilingMicroUsd`) when recorded, else, like today's, dailySpendCeiling over
   * today's config and that day's 7-day net recipes.
   */
  ceilingMicroUsd: number;
  /** Where `ceilingMicroUsd` came from. */
  ceilingSource: "recorded" | "computed";
  /** A reservation was refused at the spend ceiling that day (`stats/{day}.spendAlerts["100"]`). */
  ceilingReached: boolean;
  /** The 50 % / 80 % marks the server logged that day (`spend_threshold`). */
  spendThresholds: number[];
  /** A reservation was refused at `dailyCap` that day (`stats/{day}.dailyCapReachedAt`). */
  dailyCapReached: boolean;
  /** Production recipes granted, net of refunds, over the day and the REVENUE_WINDOW_DAYS - 1 before it. */
  netCredits7d: number;
  /** Production purchases granted that day, per pack (count and recipes). */
  sales: Record<Pack, { count: number; credits: number }>;
  /** The same production purchases per store (Google Play is Android, the App Store iOS). */
  salesByStore: Record<Store, { count: number; credits: number }>;
  salesCount: number;
  salesCredits: number;
  sandboxPurchases: number;
  sandboxPurchaseCredits: number;
}

export interface Board {
  project: string;
  generatedAtMs: number;
  firstDay: string;
  today: string;
  days: BoardDay[];
  todayRow: BoardDay;
  generationEnabled: boolean;
  allowSandbox: boolean;
  dailyCap: number;
  costs: RuntimeConfig["costs"];
  prices: RuntimeConfig["prices"];
  netMicroUsdPerCredit: number;
  /** The welcome grant's setting (config/runtime.welcome): free recipes per claim, grants a day. */
  welcome: RuntimeConfig["welcome"];
  dailySpendMaxMicroUsd: number;
  dailySpendBaseMicroUsd: number;
  /** What the breaker compares with today's ceiling: spent plus the worst cases still reserved. */
  todayBookedMicroUsd: number;
  month: {
    label: string;
    toDateMicroUsd: number;
    remainingDays: number;
    /** Month to date + what is left of today's ceiling + every later day at the maximum ceiling. */
    worstCaseMicroUsd: number;
  };
  sales: {
    byPack: Record<Pack, Totals>;
    byStore: Record<Store, Totals>;
    /** Production purchases per store, then per pack (the same rows as byStore and byPack). */
    byStoreAndPack: Record<Store, Record<Pack, Totals>>;
    total: Totals;
    /** total.net x netMicroUsdPerCredit: the config's conservative estimate, not a store payout. */
    estimatedNetMicroUsd: number;
    sandbox: { purchases: number; credits: number };
  };
  period: DayStats;
  /** Period spend / succeeded answers; null without any. */
  avgPerAnswerMicroUsd: number | null;
  /** Period spend / recipes kept (generationUnits - refundedCredits); null without any. */
  avgPerRecipeMicroUsd: number | null;
  holders: BoardInput["holders"];
  ledger: BoardInput["ledger"];
  reads: number;
  purchasesCapped: { total: number; read: number; oldestReadMs: number } | null;
}

export const BOARD_MAX_DAYS = 90;
/**
 * At most this many purchases are read per run (newest first), so a busy period cannot make the
 * hourly redraw expensive: 2,001 reads at most for them, about USD 0.0012 a run.
 */
export const BOARD_PURCHASES_CAP = 2000;

const num = (v: unknown) => (typeof v === "number" && Number.isFinite(v) ? v : 0);

export function dayStats(data: Record<string, unknown> | undefined): DayStats {
  const out = {} as DayStats;
  for (const f of STAT_FIELDS) out[f] = num(data?.[f]);
  return out;
}

const startOfDay = (ms: number) => Math.floor(ms / DAY_MS) * DAY_MS;

/** The period's days, oldest first, ending today (UTC). */
export function periodDays(nowMs: number, days: number): string[] {
  const today = startOfDay(nowMs);
  return Array.from({ length: days }, (_, i) => utcDay(today - (days - 1 - i) * DAY_MS));
}

/** [start, end) of the period in ms: midnight UTC of its first day to midnight after today. */
export function periodRange(nowMs: number, days: number): { startMs: number; endMs: number } {
  const today = startOfDay(nowMs);
  return { startMs: today - (days - 1) * DAY_MS, endMs: today + DAY_MS };
}

/**
 * Every `stats/{day}` the board needs: the period, the month to date, and the revenue window
 * before the earliest of them (each day's ceiling looks back REVENUE_WINDOW_DAYS - 1 days).
 */
export function boardStatsDays(nowMs: number, days: number): string[] {
  const today = startOfDay(nowMs);
  const d = new Date(today);
  const monthStart = Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 1);
  const first = Math.min(today - (days - 1) * DAY_MS, monthStart) - (REVENUE_WINDOW_DAYS - 1) * DAY_MS;
  const out: string[] = [];
  for (let t = first; t <= today; t += DAY_MS) out.push(utcDay(t));
  return out;
}

/**
 * Keeps only the fields the board shows from a `purchases/{id}` document. Everything else (uid,
 * formerUid, purchaseToken, orderId, transactionId, accountToken, buyerKeys) is dropped here, and
 * an unknown product or store becomes "other" so a stored string never reaches the page.
 */
export function purchaseRow(data: Record<string, unknown>): PurchaseRow | null {
  const granted = data.grantedAt as { toMillis?: () => number } | null | undefined;
  const grantedAtMs = typeof granted?.toMillis === "function" ? granted.toMillis() : null;
  if (grantedAtMs === null || !Number.isFinite(grantedAtMs)) return null;
  const store: Store = data.store === "play" || data.store === "appstore" ? data.store : "other";
  const pack: Pack = (PACKS as readonly unknown[]).includes(data.productId) && data.productId !== "other" ? (data.productId as Pack) : "other";
  return {
    store,
    env: data.env === "production" ? "production" : "sandbox",
    pack,
    credits: num(data.credits),
    refundedCredits: num(data.refundedCredits),
    grantedAtMs,
  };
}

const emptyTotals = (): Totals => ({ purchases: 0, credits: 0, refunded: 0, net: 0 });
const addTo = (t: Totals, p: PurchaseRow) => {
  t.purchases += 1;
  t.credits += p.credits;
  t.refunded += p.refundedCredits;
  t.net += p.credits - p.refundedCredits;
};
const record = <K extends string, V>(keys: readonly K[], make: () => V) =>
  Object.fromEntries(keys.map((k) => [k, make()])) as Record<K, V>;

export function buildBoard(input: BoardInput): Board {
  const { config } = input;
  const statsOf = (day: string) => dayStats(input.stats.get(day));
  const dayIndex = (day: string) => Date.parse(`${day}T00:00:00Z`);
  const netWindow = (day: string) => {
    let sum = 0;
    for (let i = 0; i < REVENUE_WINDOW_DAYS; i++) sum += statsOf(utcDay(dayIndex(day) - i * DAY_MS)).netCreditsGranted;
    return sum;
  };
  const { startMs, endMs } = periodRange(input.generatedAtMs, input.days);
  const inPeriod = input.purchases.filter((p) => p.grantedAtMs >= startMs && p.grantedAtMs < endMs);

  const todayDay = utcDay(input.generatedAtMs);
  const days: BoardDay[] = periodDays(input.generatedAtMs, input.days).map((day) => {
    const netCredits7d = netWindow(day);
    const raw = input.stats.get(day);
    const recorded = num(raw?.ceilingMicroUsd);
    const useRecorded = day !== todayDay && typeof raw?.ceilingMicroUsd === "number" && recorded >= 0;
    const marks = (raw?.spendAlerts ?? {}) as Record<string, unknown>;
    return {
      day,
      ...statsOf(day),
      ceilingMicroUsd: useRecorded ? recorded : dailySpendCeiling(config, netCredits7d),
      ceilingSource: useRecorded ? "recorded" : "computed",
      ceilingReached: marks["100"] !== undefined,
      spendThresholds: [50, 80].filter((p) => marks[`${p}`] !== undefined),
      dailyCapReached: raw?.dailyCapReachedAt !== undefined,
      netCredits7d,
      sales: record(PACKS, () => ({ count: 0, credits: 0 })),
      salesByStore: record(STORES, () => ({ count: 0, credits: 0 })),
      salesCount: 0,
      salesCredits: 0,
      sandboxPurchases: 0,
      sandboxPurchaseCredits: 0,
    };
  });
  const byDay = new Map(days.map((d) => [d.day, d]));

  const byPack = record(PACKS, emptyTotals);
  const byStore = record(STORES, emptyTotals);
  const byStoreAndPack = record(STORES, () => record(PACKS, emptyTotals));
  const total = emptyTotals();
  const sandbox = { purchases: 0, credits: 0 };
  for (const p of inPeriod) {
    const d = byDay.get(utcDay(p.grantedAtMs))!;
    if (p.env !== "production") {
      sandbox.purchases += 1;
      sandbox.credits += p.credits;
      d.sandboxPurchases += 1;
      d.sandboxPurchaseCredits += p.credits;
      continue;
    }
    addTo(byPack[p.pack], p);
    addTo(byStore[p.store], p);
    addTo(byStoreAndPack[p.store][p.pack], p);
    addTo(total, p);
    d.sales[p.pack].count += 1;
    d.sales[p.pack].credits += p.credits;
    d.salesByStore[p.store].count += 1;
    d.salesByStore[p.store].credits += p.credits;
    d.salesCount += 1;
    d.salesCredits += p.credits;
  }

  const period = dayStats(undefined);
  for (const d of days) for (const f of STAT_FIELDS) period[f] += d[f];

  const today = utcDay(input.generatedAtMs);
  const todayRow = byDay.get(today)!;
  const todayBookedMicroUsd = todayRow.spentMicroUsd + todayRow.reservedMicroUsd;
  const t = new Date(startOfDay(input.generatedAtMs));
  const year = t.getUTCFullYear();
  const month = t.getUTCMonth();
  const daysInMonth = new Date(Date.UTC(year, month + 1, 0)).getUTCDate();
  let toDate = 0;
  for (let day = 1; day <= t.getUTCDate(); day++) {
    const s = statsOf(utcDay(Date.UTC(year, month, day)));
    toDate += s.spentMicroUsd + s.reservedMicroUsd;
  }
  const remainingDays = daysInMonth - t.getUTCDate();
  const worstCaseMicroUsd = toDate + Math.max(0, todayRow.ceilingMicroUsd - todayBookedMicroUsd) + remainingDays * config.dailySpendMaxMicroUsd;

  const kept = period.generationUnits - period.refundedCredits;
  return {
    project: input.project,
    generatedAtMs: input.generatedAtMs,
    firstDay: days[0].day,
    today,
    days,
    todayRow,
    generationEnabled: config.generationEnabled,
    allowSandbox: config.allowSandbox,
    dailyCap: config.dailyCap,
    costs: config.costs,
    prices: config.prices,
    netMicroUsdPerCredit: config.netMicroUsdPerCredit,
    welcome: config.welcome,
    dailySpendMaxMicroUsd: config.dailySpendMaxMicroUsd,
    dailySpendBaseMicroUsd: config.dailySpendBaseMicroUsd,
    todayBookedMicroUsd,
    month: {
      label: new Date(Date.UTC(year, month, 1)).toLocaleString("en-GB", { month: "long", year: "numeric", timeZone: "UTC" }),
      toDateMicroUsd: toDate,
      remainingDays,
      worstCaseMicroUsd,
    },
    sales: {
      byPack,
      byStore,
      byStoreAndPack,
      total,
      estimatedNetMicroUsd: total.net * config.netMicroUsdPerCredit,
      sandbox,
    },
    period,
    avgPerAnswerMicroUsd: period.succeeded > 0 ? period.spentMicroUsd / period.succeeded : null,
    avgPerRecipeMicroUsd: kept > 0 ? period.spentMicroUsd / kept : null,
    holders: input.holders,
    ledger: input.ledger,
    reads: input.reads,
    purchasesCapped: input.purchasesCapped ?? null,
  };
}
