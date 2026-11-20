import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { requireRole } from "../auth.js";
import { features } from "../config.js";
import { HttpError } from "../errors.js";
import * as lk from "../lib/livekit.js";
import { markExpertJoined, recordEvents, visitForUser } from "../visits.js";
import { putObject, visitKey } from "../lib/storage.js";
import { q } from "../db.js";

export async function supervisorRoutes(app: FastifyInstance) {
  const supervise = requireRole("expert", "admin", "owner");

  /** Ticket for an expert to watch and talk into a live visit. */
  app.post("/visits/:id/expert-ticket", { preHandler: supervise }, async (req) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(req.params);
    const visit = await visitForUser(id, req.user!);
    if (visit.ended_at) throw new HttpError(409, "visit_ended", "This visit has ended.");
    if (!features.livekit()) throw new HttpError(503, "live_unconfigured", "Live video is not configured on the server.");
    const ticket = await lk.expertTicket({ visitId: visit.id, userId: req.user!.id, name: req.user!.name });
    await markExpertJoined(visit, req.user!.id);
    await q("insert into audit_log (user_id, action, target) values ($1,'expert_join',$2)", [req.user!.id, visit.id]);
    return ticket;
  });

  /** Expert snapshot of the live video, saved to the visit. */
  app.post("/visits/:id/snapshots", { preHandler: supervise }, async (req) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(req.params);
    const visit = await visitForUser(id, req.user!);
    const body = z.object({ jpegBase64: z.string().max(6_000_000), note: z.string().max(500).optional() }).parse(req.body);
    const key = visitKey(visit.company_id, visit.id, `snapshot-${Date.now()}.jpg`);
    const buf = Buffer.from(body.jpegBase64, "base64");
    await putObject(key, buf, "image/jpeg");
    await q("insert into media (visit_id, kind, s3_key, bytes, content_type, status, meta) values ($1,'snapshot',$2,$3,'image/jpeg','uploaded',$4)", [
      visit.id,
      key,
      buf.length,
      JSON.stringify({ by: req.user!.name, note: body.note ?? null }),
    ]);
    await recordEvents(visit, [{ type: "photo", actor: "expert", data: { key, note: body.note ?? null, by: req.user!.name } }], "expert");
    return { ok: true, key };
  });

  app.get("/supervisor/summary", { preHandler: supervise }, async (req) => {
    const user = req.user!;
    const params: unknown[] = [];
    let scope = "";
    if (user.role !== "owner") {
      params.push(user.companyId);
      scope = "and company_id = $1";
    }
    const [row] = await q<any>(
      `select
         count(*) filter (where ended_at is null ${scope}) as active,
         count(*) filter (where escalation_status = 'requested' ${scope}) as waiting_for_expert,
         count(*) filter (where cardinality(flags) > 0 and started_at > now() - interval '7 days' ${scope}) as flagged_7d,
         count(*) filter (where started_at > now() - interval '1 day' ${scope}) as visits_24h
       from visits`,
      params,
    );
    return { summary: row };
  });
}
