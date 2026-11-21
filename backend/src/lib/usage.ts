import { config } from "../config.js";
import { one, q } from "../db.js";
import { costUsd, type Usage } from "./pricing.js";

export interface UsageScope {
  companyId?: string | null;
  visitId?: string | null;
}

export interface UsageRecord {
  provider: "anthropic" | "google";
  model: string;
  task: string;
  usage: Usage;
}

/** Stores one metered call and returns its cost. Never throws: metering must not break a visit. */
export async function recordUsage(scope: UsageScope, rec: UsageRecord): Promise<number> {
  const cost = costUsd(rec.model, rec.usage);
  try {
    const u = rec.usage;
    await q(
      `insert into ai_usage (company_id, visit_id, provider, model, task, input_tokens, cached_tokens, output_tokens,
         audio_in_tokens, image_in_tokens, audio_out_tokens, cost_usd)
       values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)`,
      [
        scope.companyId ?? null,
        scope.visitId ?? null,
        rec.provider,
        rec.model,
        rec.task,
        Math.round(u.inputTokens ?? 0),
        Math.round(u.cachedTokens ?? 0),
        Math.round(u.outputTokens ?? 0),
        Math.round(u.audioInTokens ?? 0),
        Math.round(u.imageInTokens ?? 0),
        Math.round(u.audioOutTokens ?? 0),
        cost.toFixed(6),
      ],
    );
  } catch (e) {
    console.warn("[usage] could not record:", (e as Error).message);
  }
  return cost;
}

function monthStart(now = new Date()): string {
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1)).toISOString();
}

export async function monthToDateUsd(companyId: string): Promise<number> {
  const row = await one<{ usd: string | number | null }>(
    "select coalesce(sum(cost_usd), 0) as usd from ai_usage where company_id = $1 and created_at >= $2",
    [companyId, monthStart()],
  );
  return Number(row?.usd ?? 0);
}

/** True once a company has spent its AI_MONTHLY_BUDGET_USD this month. 0 means no budget set. */
export async function overBudget(companyId: string | null | undefined): Promise<boolean> {
  const budget = config().AI_MONTHLY_BUDGET_USD;
  if (!companyId || !budget || budget <= 0) return false;
  try {
    return (await monthToDateUsd(companyId)) >= budget;
  } catch {
    return false;
  }
}

export interface UsageSummary {
  month: string;
  budgetUsd: number | null;
  monthToDateUsd: number;
  visits: number;
  costPerVisitUsd: number;
  byModel: { provider: string; model: string; calls: number; usd: number }[];
  byTask: { task: string; calls: number; usd: number }[];
  recentVisits: { visitId: string; usd: number; at: string }[];
}

export async function usageSummary(companyId: string): Promise<UsageSummary> {
  const since = monthStart();
  const byModel = await q<any>(
    `select provider, model, count(*)::int as calls, coalesce(sum(cost_usd),0) as usd
       from ai_usage where company_id = $1 and created_at >= $2 group by provider, model order by usd desc`,
    [companyId, since],
  );
  const byTask = await q<any>(
    `select task, count(*)::int as calls, coalesce(sum(cost_usd),0) as usd
       from ai_usage where company_id = $1 and created_at >= $2 group by task order by usd desc`,
    [companyId, since],
  );
  const perVisit = await q<any>(
    `select visit_id, coalesce(sum(cost_usd),0) as usd, max(created_at) as at
       from ai_usage where company_id = $1 and created_at >= $2 and visit_id is not null
       group by visit_id order by max(created_at) desc`,
    [companyId, since],
  );
  const total = byModel.reduce((n: number, r: any) => n + Number(r.usd), 0);
  const visitTotal = perVisit.reduce((n: number, r: any) => n + Number(r.usd), 0);
  const budget = config().AI_MONTHLY_BUDGET_USD;
  return {
    month: since.slice(0, 7),
    budgetUsd: budget > 0 ? budget : null,
    monthToDateUsd: round(total),
    visits: perVisit.length,
    costPerVisitUsd: perVisit.length ? round(visitTotal / perVisit.length) : 0,
    byModel: byModel.map((r: any) => ({ provider: r.provider, model: r.model, calls: r.calls, usd: round(Number(r.usd)) })),
    byTask: byTask.map((r: any) => ({ task: r.task, calls: r.calls, usd: round(Number(r.usd)) })),
    recentVisits: perVisit.slice(0, 20).map((r: any) => ({ visitId: r.visit_id, usd: round(Number(r.usd)), at: new Date(r.at).toISOString() })),
  };
}

function round(n: number): number {
  return Math.round(n * 10000) / 10000;
}
