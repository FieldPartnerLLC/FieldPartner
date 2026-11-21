import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { requireAgent } from "../auth.js";
import { config, features } from "../config.js";
import { askClaude, type ClaudeContent } from "../lib/claude.js";
import { consultSystemPrompt, markLessonsUsed, relevantLessons, renderLessons, tradeSkill } from "../lib/skills.js";
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
    return agentContext(await loadVisit(id));
  });

  /**
   * The live voice model (Gemini) asks Claude, as the company's senior expert, a hard question.
   * Claude gets the trade skill, the company's approved lessons, the visit so far and the latest
   * camera frame, and returns a short spoken answer.
   */
  app.post("/agent/visits/:id/consult", { preHandler: requireAgent, bodyLimit: 4 * 1024 * 1024 }, async (req) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(req.params);
    const body = z
      .object({
        question: z.string().min(1).max(2000),
        imageJpegBase64: z.string().max(3_000_000).optional(),
        recentTranscript: z.array(z.string().max(1000)).max(40).optional(),
      })
      .parse(req.body);
    const visit = await loadVisit(id);
    if (!features.claude()) {
      return { answer: null, reason: "claude_not_configured" };
    }
    const ctx = await agentContext(visit);
    const equipment = (ctx.job.equipment ?? {}) as Record<string, any>;
    const lastEquip = [...ctx.notes].reverse().find((n: any) => n.brand || n.model) as any;
    const brand = String(lastEquip?.brand ?? equipment.brand ?? "");
    const model = String(lastEquip?.model ?? equipment.model ?? "");
    const lessons = await relevantLessons(visit.company_id, ctx.job.trade, {
      brand,
      model,
      text: [body.question, ctx.job.complaint ?? "", ...(body.recentTranscript ?? []).slice(-6)].join(" "),
    });
    const facts = [
      `Job: ${ctx.job.title}. Customer says: ${ctx.job.complaint ?? "not recorded"}.`,
      brand || model ? `Equipment: ${[brand, model, lastEquip?.type ?? equipment.type].filter(Boolean).join(" ")}` : "Equipment: not recorded yet.",
      ctx.readings.length ? `Readings: ${ctx.readings.map((r: any) => `${r.name} ${r.value} ${r.unit ?? ""}`.trim()).join("; ")}` : "Readings: none yet.",
      ctx.notes.length ? `Notes: ${ctx.notes.map((n: any) => n.text ?? n.reason ?? [n.type, n.brand, n.model].filter(Boolean).join(" ")).filter(Boolean).join("; ")}` : "",
      body.recentTranscript?.length ? `Recent conversation:\n${body.recentTranscript.slice(-12).join("\n")}` : "",
      `Technician certifications: ${(ctx.tech.certifications ?? []).join(", ") || "none recorded"}.`,
    ]
      .filter(Boolean)
      .join("\n");
    const system = consultSystemPrompt({
      company: ctx.company.name ?? "the company",
      tradeName: ctx.trade?.name ?? ctx.job.trade,
      skill: tradeSkill(ctx.job.trade, ctx.trade),
      lessons: renderLessons(lessons),
      visitFacts: facts,
    });
    const content: ClaudeContent[] = [];
    // Same rule as the live camera: only before consent is signed, or with video consent.
    if (body.imageJpegBase64 && (visit.consent_choice == null || visit.consent_choice === "video_audio")) {
      content.push({ type: "image", source: { type: "base64", media_type: "image/jpeg", data: body.imageJpegBase64 } });
    }
    content.push({ type: "text", text: `Technician's question: ${body.question}` });
    let answer: string;
    try {
      answer = await askClaude({ system, content, maxTokens: 400, temperature: 0.2, timeoutMs: 30_000 });
    } catch (e) {
      req.log.warn({ err: e }, "consult failed");
      return { answer: null, reason: "claude_error" };
    }
    await markLessonsUsed(lessons.map((l) => l.id)).catch(() => {});
    await recordEvents(visit, [{ type: "consult", data: { question: body.question, answer, lessons: lessons.map((l) => l.id), model: config().CLAUDE_MODEL } }], "ai");
    return { answer, lessonsUsed: lessons.length };
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

/** Everything the agent (and the senior expert) needs to know about a visit. */
export async function agentContext(visit: Awaited<ReturnType<typeof loadVisit>>) {
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
}
