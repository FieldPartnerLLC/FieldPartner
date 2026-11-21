import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { canSupervise, requireUser } from "../auth.js";
import { features } from "../config.js";
import { one, q } from "../db.js";
import { HttpError, notFound } from "../errors.js";
import * as lk from "../lib/livekit.js";
import { presignPut, visitKey } from "../lib/storage.js";
import type { VisitReport } from "../lib/report.js";
import {
  approveReport,
  buildReport,
  endEscalation,
  endVisit,
  proposeLessons,
  recordConsent,
  recordEvents,
  requestEscalation,
  startVisit,
  visitDetail,
  visitForUser,
} from "../visits.js";

const idParam = z.object({ id: z.string().uuid() });

const MEDIA_KINDS = ["video", "audio", "transcript", "snapshot", "signature"] as const;

export async function visitRoutes(app: FastifyInstance) {
  app.post("/jobs/:id/visits", { preHandler: requireUser }, async (req) => {
    const { id } = idParam.parse(req.params);
    const { visit, resumed } = await startVisit(id, req.user!);
    return { visit, resumed, live: { available: features.livekit() } };
  });

  app.get("/visits/:id", { preHandler: requireUser }, async (req) => {
    const { id } = idParam.parse(req.params);
    const visit = await visitForUser(id, req.user!);
    return visitDetail(visit, req.user!);
  });

  /** LiveKit ticket for the technician's phone. Also makes sure the AI agent is in the room. */
  app.post("/visits/:id/live", { preHandler: requireUser }, async (req) => {
    const { id } = idParam.parse(req.params);
    const visit = await visitForUser(id, req.user!);
    if (visit.tech_id !== req.user!.id) throw new HttpError(403, "not_tech", "Only the visit's technician can stream.");
    if (visit.ended_at) throw new HttpError(409, "visit_ended", "This visit has ended.");
    if (!features.livekit()) {
      throw new HttpError(503, "live_unconfigured", "Live video is not configured on the server (LIVEKIT_URL, LIVEKIT_API_KEY, LIVEKIT_API_SECRET).");
    }
    const job = await one<any>("select trade from jobs where id = $1", [visit.job_id]);
    const meta = { visitId: visit.id, companyId: visit.company_id, trade: job?.trade, role: "tech" };
    await lk.ensureRoom(visit.room_name);
    await lk.dispatchAgent(visit.room_name, meta);
    const ticket = await lk.techTicket({ visitId: visit.id, userId: req.user!.id, name: req.user!.name, metadata: meta });
    return { ...ticket, agentIdentityPrefix: "agent-" };
  });

  app.post("/visits/:id/consent", { preHandler: requireUser }, async (req) => {
    const { id } = idParam.parse(req.params);
    const visit = await visitForUser(id, req.user!);
    const body = z
      .object({
        kind: z.enum(["service_and_recording", "repair", "completion", "recording_revoked"]),
        choice: z.string().optional().nullable(),
        privateAreasOk: z.boolean().optional(),
        signerName: z.string().max(200).optional(),
        signerRole: z.string().max(100).optional(),
        language: z.enum(["en", "es"]).optional(),
        lat: z.number().optional(),
        lng: z.number().optional(),
        textVersion: z.string().max(50).optional(),
        signaturePngBase64: z.string().max(3_000_000).optional(),
        payload: z.record(z.string(), z.unknown()).optional(),
      })
      .parse(req.body);
    return recordConsent(visit, body);
  });

  app.post("/visits/:id/events", { preHandler: requireUser }, async (req) => {
    const { id } = idParam.parse(req.params);
    const visit = await visitForUser(id, req.user!);
    const body = z.object({ events: z.array(z.any()).max(500) }).parse(req.body);
    const actor = req.user!.role === "tech" ? "tech" : "expert";
    return recordEvents(visit, body.events, actor);
  });

  app.post("/visits/:id/escalate", { preHandler: requireUser }, async (req) => {
    const { id } = idParam.parse(req.params);
    const visit = await visitForUser(id, req.user!);
    const body = z.object({ reason: z.string().max(500).default("Technician asked for help") }).parse(req.body ?? {});
    return requestEscalation(visit, body.reason, "tech");
  });

  app.post("/visits/:id/escalation/end", { preHandler: requireUser }, async (req) => {
    const { id } = idParam.parse(req.params);
    const visit = await visitForUser(id, req.user!);
    await endEscalation(visit);
    return { ok: true };
  });

  /** Signed upload URL. The phone uploads straight to storage, then calls /media/:id/complete. */
  app.post("/visits/:id/uploads", { preHandler: requireUser }, async (req) => {
    const { id } = idParam.parse(req.params);
    const visit = await visitForUser(id, req.user!);
    const body = z
      .object({
        kind: z.enum(MEDIA_KINDS),
        filename: z.string().min(1).max(200),
        contentType: z.string().min(3).max(100),
        bytes: z.number().int().nonnegative().optional(),
        clientId: z.string().max(100).optional(),
      })
      .parse(req.body);
    if (body.clientId) {
      const existing = await one<any>("select * from media where visit_id = $1 and meta->>'clientId' = $2", [visit.id, body.clientId]);
      if (existing) {
        if (existing.status === "uploaded") return { mediaId: existing.id, alreadyUploaded: true };
        return { mediaId: existing.id, url: await presignPut(existing.s3_key, body.contentType), key: existing.s3_key };
      }
    }
    const key = visitKey(visit.company_id, visit.id, `${Date.now()}-${body.filename}`);
    const m = await one<{ id: string }>(
      "insert into media (visit_id, kind, s3_key, bytes, content_type, status, meta) values ($1,$2,$3,$4,$5,'pending',$6) returning id",
      [visit.id, body.kind, key, body.bytes ?? null, body.contentType, JSON.stringify({ clientId: body.clientId ?? null, filename: body.filename })],
    );
    return { mediaId: m!.id, url: await presignPut(key, body.contentType), key };
  });

  app.post("/media/:id/complete", { preHandler: requireUser }, async (req) => {
    const { id } = idParam.parse(req.params);
    const m = await one<any>("select * from media where id = $1", [id]);
    if (!m) throw notFound("Upload not found.");
    await visitForUser(m.visit_id, req.user!);
    const body = z.object({ bytes: z.number().int().nonnegative().optional() }).parse(req.body ?? {});
    await q("update media set status = 'uploaded', bytes = coalesce($2, bytes) where id = $1", [id, body.bytes ?? null]);
    return { ok: true };
  });

  app.post("/visits/:id/end", { preHandler: requireUser }, async (req) => {
    const { id } = idParam.parse(req.params);
    const visit = await visitForUser(id, req.user!);
    const ended = await endVisit(visit);
    // Report generation runs in the background; the phone polls GET /visits/:id/report.
    if (ended.report_status === "generating") {
      buildReport(ended.id).catch((e) => req.log.error({ err: e }, "report generation failed"));
    }
    return { visit: ended };
  });

  app.get("/visits/:id/report", { preHandler: requireUser }, async (req) => {
    const { id } = idParam.parse(req.params);
    const visit = await visitForUser(id, req.user!);
    return { status: visit.report_status, report: visit.report, token: visit.report_token };
  });

  app.post("/visits/:id/report/regenerate", { preHandler: requireUser }, async (req) => {
    const { id } = idParam.parse(req.params);
    const visit = await visitForUser(id, req.user!);
    if (!visit.ended_at) throw new HttpError(409, "visit_open", "End the visit first.");
    await q("update visits set report_status = 'generating' where id = $1", [visit.id]);
    const report = await buildReport(visit.id);
    return { status: "draft", report };
  });

  app.put("/visits/:id/report", { preHandler: requireUser }, async (req) => {
    const { id } = idParam.parse(req.params);
    const visit = await visitForUser(id, req.user!);
    const body = z.object({ report: z.record(z.string(), z.unknown()) }).parse(req.body);
    await q("update visits set report = $2, report_status = case when report_status in ('approved','sent') then report_status else 'draft' end where id = $1", [
      visit.id,
      JSON.stringify(body.report),
    ]);
    return { ok: true };
  });

  app.post("/visits/:id/report/approve", { preHandler: requireUser }, async (req) => {
    const { id } = idParam.parse(req.params);
    const visit = await visitForUser(id, req.user!);
    if (!visit.ended_at) throw new HttpError(409, "visit_open", "End the visit before approving the report.");
    const body = z
      .object({ report: z.record(z.string(), z.unknown()).optional(), sendSms: z.boolean().default(true), sendEmail: z.boolean().default(true) })
      .parse(req.body ?? {});
    const report = (body.report ?? visit.report) as VisitReport | null;
    if (!report) throw new HttpError(409, "no_report", "The report is not ready yet.");
    const result = await approveReport(visit, report, { sms: body.sendSms, email: body.sendEmail });
    // Claude proposes lessons from this visit in the background; an expert approves them later.
    proposeLessons(visit.id).catch(() => {});
    return result;
  });

  /** Live board, history and the review queue for supervisors. */
  app.get("/visits", { preHandler: requireUser }, async (req) => {
    const qs = z
      .object({
        active: z.enum(["true", "false"]).optional(),
        flagged: z.enum(["true", "false"]).optional(),
        techId: z.string().uuid().optional(),
        limit: z.coerce.number().int().min(1).max(200).default(50),
      })
      .parse(req.query);
    const user = req.user!;
    const params: unknown[] = [];
    const where: string[] = [];
    if (user.role !== "owner") {
      params.push(user.companyId);
      where.push(`v.company_id = $${params.length}`);
    }
    if (user.role === "tech") {
      params.push(user.id);
      where.push(`v.tech_id = $${params.length}`);
    } else if (qs.techId) {
      params.push(qs.techId);
      where.push(`v.tech_id = $${params.length}`);
    }
    if (qs.active === "true") where.push("v.ended_at is null");
    if (qs.flagged === "true") where.push("cardinality(v.flags) > 0");
    params.push(qs.limit);
    const rows = await q(
      `select v.id, v.state, v.started_at, v.ended_at, v.consent_choice, v.escalation_status, v.escalation_reason, v.flags,
              v.report_status, v.last_seen_at, j.title, j.trade, j.address, u.name as tech_name, c.name as company_name
       from visits v join jobs j on j.id = v.job_id join users u on u.id = v.tech_id join companies c on c.id = v.company_id
       ${where.length ? "where " + where.join(" and ") : ""}
       order by (v.escalation_status = 'requested') desc, v.started_at desc limit $${params.length}`,
      params,
    );
    return { visits: rows, canSupervise: canSupervise(user) };
  });
}
