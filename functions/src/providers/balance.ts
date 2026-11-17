/**
 * The provider balance probe (README "Adding a provider", "Provider balances"): for every provider
 * that implements `balance` and has a threshold in `config/runtime.providerBalanceAlerts`, asks
 * its balance and logs `provider_balance_low` (error) when it is under the threshold, so a prepaid
 * account is topped up before calls start failing as out of credit. Run hourly by the scheduled
 * function checkProviderBalances; with no threshold set (the default) it calls nothing.
 *
 * Generic on purpose: no provider implements it yet (BFL's `GET https://api.bfl.ai/v1/credits` is the
 * first candidate, in the FLUX adapter). Each probe is bounded by `timeoutMs`; a probe that fails
 * logs `provider_balance_unavailable` (warning) with a fixed reason, never the provider's text.
 */
import type { Logger } from "../ports";
import type { ProviderContext, ProviderDescriptor } from "./types";

export interface BalanceCheckEnv {
  providers: readonly ProviderDescriptor[];
  /** Thresholds by provider id (RuntimeConfig.providerBalanceAlerts). */
  thresholds: Readonly<Record<string, number>>;
  context(d: ProviderDescriptor): ProviderContext;
  logger: Logger;
  timeoutMs?: number;
}

export interface BalanceReport {
  provider: string;
  outcome: "ok" | "low" | "unavailable";
  amount?: number;
  unit?: string;
  threshold: number;
}

export async function checkProviderBalances(env: BalanceCheckEnv): Promise<BalanceReport[]> {
  const out: BalanceReport[] = [];
  const seen = new Set<string>();
  for (const d of env.providers) {
    const threshold = Object.prototype.hasOwnProperty.call(env.thresholds, d.id) ? env.thresholds[d.id] : undefined;
    // A provider id may be both a text and a picture provider (one account): probed once.
    if (!d.balance || threshold === undefined || seen.has(d.id)) continue;
    seen.add(d.id);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), env.timeoutMs ?? 10_000);
    try {
      const b = await d.balance(env.context(d), controller.signal);
      if (!Number.isFinite(b.amount)) throw new Error("unreadable");
      const low = b.amount < threshold;
      const unit = String(b.unit).slice(0, 20);
      out.push({ provider: d.id, outcome: low ? "low" : "ok", amount: b.amount, unit, threshold });
      if (low) env.logger.error("provider_balance_low", { provider: d.id, amount: b.amount, unit, threshold });
    } catch {
      out.push({ provider: d.id, outcome: "unavailable", threshold });
      env.logger.warn("provider_balance_unavailable", { provider: d.id, reason: controller.signal.aborted ? "TIMEOUT" : "FAILED" });
    } finally {
      clearTimeout(timer);
    }
  }
  return out;
}
