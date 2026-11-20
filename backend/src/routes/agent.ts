import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { requireAgent } from "../auth.js";
import { one, q } from "../db.js";
import { notFound } from "../errors.js";
import { loadVisit, recordEvents, requestEscalation } from "../visits.js";

/**
 * Service API for the AI agent worker. Authenticated with AGENT_SERVICE_TOKEN,
 * never with a user token, and never reachable from the phones.
 */
export async function agentRoutes(app: FastifyInstance) {
  app.get("/agent/visits/:id/context", { preHandler: requireAgent }, async (req) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(req.params);
    const visit = await loadVisit(id);
    const ctx = await one<any>(
      `select j.id as job_id, j.title, j.trade, j.complaint, j.address, j.equipment, j.access_notes,
              cu.name as customer_name,
              c.name as company_name, c.wake_phrase, c.co_stop_ppm, c.settings as company_settings,
              u.id as tech_id, u.name as tech_name, u.certifications, u.trades as tech_trades
       from jobs j join companies c on c.id = j.company_id left join customers cu on cu.id = j.customer_id
       join users u on u.id = $2 where j.id = $1`,
      [visit.job_id, visit.tech_id],
    );
    if (!ctx) throw notFound("Visit context not found.");
    const trade = await one<any>("select id, name, profile from trade_profiles where id = $1", [ctx.trade]);
    const overrides = ctx.company_settings?.tradeOverrides?.[ctx.trade] ?? {};
    const readings = await q<any>(
      "select ts, data from visit_events where visit_id = $1 and type = 'reading' order by ts asc limit 200",
      [visit.id],
    );
    const notes = await q<any>(
      "select ts, actor, data from visit_events where visit_id = $1 and type in ('note','equipment','safety') order by ts asc limit 200",
      [visit.id],
    );
    return {
      visit: {
        id: visit.id,
        state: visit.state,
        consentChoice: visit.consent_choice,
        privateAreasOk: visit.private_areas_ok,
        escalationStatus: visit.escalation_status,
        ended: Boolean(visit.ended_at),
      },
      job: {
        id: ctx.job_id,
        title: ctx.title,
        trade: ctx.trade,
        complaint: ctx.complaint,
        address: ctx.address,
        equipment: ctx.equipment,
        accessNotes: ctx.access_notes,
        customerName: ctx.customer_name,
      },
      company: { name: ctx.company_name, wakePhrase: ctx.wake_phrase, coStopPpm: ctx.co_stop_ppm, supervisorName: ctx.company_settings?.supervisorName ?? null },
      tech: { id: ctx.tech_id, name: ctx.tech_name, certifications: ctx.certifications, trades: ctx.tech_trades },
      trade: trade ? { id: trade.id, name: trade.name, ...trade.profile, ...overrides } : null,
      readings: readings.map((r) => ({ ts: r.ts, ...r.data })),
      notes: notes.map((n) => ({ ts: n.ts, actor: n.actor, ...n.data })),
    };
  });

  app.post("/agent/visits/:id/events", { preHandler: requireAgent }, async (req) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(req.params);
    const visit = await loadVisit(id);
    const body = z.object({ events: z.array(z.any()).max(500) }).parse(req.body);
    return recordEvents(visit, body.events, "ai");
  });

  app.post("/agent/visits/:id/escalate", { preHandler: requireAgent }, async (req) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(req.params);
    const visit = await loadVisit(id);
    const body = z.object({ reason: z.string().max(500) }).parse(req.body);
    return requestEscalation(visit, body.reason, "ai");
  });
}
