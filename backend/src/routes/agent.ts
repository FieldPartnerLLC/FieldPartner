import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { requireAgent } from "../auth.js";
import { features } from "../config.js";
import { callClaude, extractJson, type ClaudeContent } from "../lib/claude.js";
import { applyBudget, consultRoute, readLabelRoute, type Route } from "../lib/router.js";
import { overBudget, recordUsage } from "../lib/usage.js";
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
        /** "consult" = diagnosis advice; "read" = read a nameplate, label or display from the photo. */
        kind: z.enum(["consult", "read"]).default("consult"),
      })
      .parse(req.body);
    const visit = await loadVisit(id);
    if (!features.claude()) {
      return { answer: null, reason: "claude_not_configured" };
    }
    const scope = { companyId: visit.company_id, visitId: visit.id };
    // Same rule as the live camera: only before consent is signed, or with video consent.
    const imageAllowed = Boolean(body.imageJpegBase64) && (visit.consent_choice == null || visit.consent_choice === "video_audio");
    const image: ClaudeContent[] = imageAllowed
      ? [{ type: "image", source: { type: "base64", media_type: "image/jpeg", data: body.imageJpegBase64! } }]
      : [];

    if (body.kind === "read") {
      if (!imageAllowed) return { answer: "I need the camera on to read that.", tier: "none" };
      const route = readLabelRoute();
      try {
        const reply = await callClaude({
          tier: route.tier,
          system: READ_PROMPT,
          content: [...image, { type: "text", text: `What to read: ${body.question}` }],
          maxTokens: 250,
          temperature: 0,
          timeoutMs: 20_000,
          scope,
          task: "read_label",
        });
        await recordEvents(visit, [{ type: "consult", data: { kind: "read", question: body.question, answer: reply.text, tier: route.tier, model: reply.model } }], "ai");
        return { answer: reply.text, tier: route.tier };
      } catch (e) {
        req.log.warn({ err: e }, "read failed");
        return { answer: null, reason: "claude_error" };
      }
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
    const content: ClaudeContent[] = [...image, { type: "text", text: `Technician's question: ${body.question}` }];

    // Fast model first for routine questions. It grades itself; unsure answers go to the expert.
    let route: Route = applyBudget(consultRoute(body.question, body.recentTranscript), await overBudget(visit.company_id));
    let answer: string | null = null;
    let usedModel = "";
    try {
      if (route.tier === "fast") {
        const fast = await callClaude({
          tier: "fast",
          system: `${system}\n\n${SELF_CHECK}`,
          content,
          maxTokens: 300,
          temperature: 0.2,
          timeoutMs: 15_000,
          scope,
          task: "consult_fast",
        });
        const graded = extractJson(fast.text) as { answer?: unknown; confident?: unknown } | null;
        const text = typeof graded?.answer === "string" ? graded.answer.trim() : "";
        if (text && graded?.confident === true) {
          answer = text;
          usedModel = fast.model;
        } else if (!(await overBudget(visit.company_id))) {
          route = { tier: "expert", reason: "fast model was not confident" };
        } else if (text) {
          answer = text;
          usedModel = fast.model;
        }
      }
      if (!answer) {
        const expert = await callClaude({
          tier: "expert",
          system,
          content,
          maxTokens: 400,
          temperature: 0.2,
          timeoutMs: 30_000,
          scope,
          task: "consult_expert",
        });
        answer = expert.text;
        usedModel = expert.model;
        route = { ...route, tier: "expert" };
      }
    } catch (e) {
      req.log.warn({ err: e }, "consult failed");
      return { answer: null, reason: "claude_error" };
    }
    await markLessonsUsed(lessons.map((l) => l.id)).catch(() => {});
    await recordEvents(
      visit,
      [{ type: "consult", data: { question: body.question, answer, lessons: lessons.map((l) => l.id), tier: route.tier, why: route.reason, model: usedModel } }],
      "ai",
    );
    return { answer, lessonsUsed: lessons.length, tier: route.tier, why: route.reason };
  });

  /**
   * The agent reports what Gemini used during the visit (live voice and camera, hazard watcher)
   * so the cost page shows the whole bill, not only Claude.
   */
  app.post("/agent/visits/:id/usage", { preHandler: requireAgent }, async (req) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(req.params);
    const visit = await loadVisit(id);
    const n = z.number().int().min(0).max(100_000_000).default(0);
    const body = z
      .object({
        items: z
          .array(
            z.object({
              model: z.string().min(1).max(80),
              task: z.string().min(1).max(40),
              inputTokens: n,
              cachedTokens: n,
              audioInTokens: n,
              imageInTokens: n,
              outputTokens: n,
              audioOutTokens: n,
            }),
          )
          .max(20),
      })
      .parse(req.body);
    let usd = 0;
    for (const it of body.items) {
      usd += await recordUsage(
        { companyId: visit.company_id, visitId: visit.id },
        { provider: "google", model: it.model, task: it.task, usage: it },
      );
    }
    return { recorded: body.items.length, usd };
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

const SELF_CHECK = `Before answering, judge yourself. Reply with only this JSON:
{"answer": "<your spoken answer>", "confident": true or false}
Set "confident" to false if the facts or photo do not clearly support one answer, if the fix involves
gas, high voltage, refrigerant handling or anything that could injure someone, or if a wrong answer
could damage equipment. A senior model will answer instead. Being honest here is more important than answering.`;

const READ_PROMPT = `You read equipment nameplates, labels, data plates, displays and error codes from one photo.
Reply in at most 3 short spoken sentences, plain words, no markdown.
Give exactly what is printed: brand, model number, serial number, manufacture date or year code, voltage,
amps, refrigerant and charge, or the code on the display, whichever are visible and asked for.
Spell model and serial numbers character by character groups as printed. If something is blurry, cut off or
glared, say "not readable" for it and say how to fix the shot (closer, angle, light). Never guess a character.`;
