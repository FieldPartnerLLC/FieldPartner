import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { one, q } from "../db.js";
import { notFound } from "../errors.js";
import * as lk from "../lib/livekit.js";
import { readLocal, putObject, verifyLocalSignature } from "../lib/storage.js";
import { loadVisit, markExpertJoined } from "../visits.js";

function esc(s: unknown): string {
  return String(s ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

export async function publicRoutes(app: FastifyInstance) {
  /** Customer-facing report page, reached from the text or email link. */
  app.get("/r/:token", async (req, reply) => {
    const { token } = z.object({ token: z.string().min(10).max(64) }).parse(req.params);
    const row = await one<any>(
      `select v.report, v.started_at, c.name as company, c.phone as company_phone, j.trade, j.address, u.name as tech
       from visits v join companies c on c.id = v.company_id join jobs j on j.id = v.job_id join users u on u.id = v.tech_id
       where v.report_token = $1 and v.report_status in ('approved','sent')`,
      [token],
    );
    if (!row) throw notFound("Report not found.");
    const r = row.report ?? {};
    const list = (items: unknown) =>
      Array.isArray(items) && items.length ? `<ul>${items.map((i) => `<li>${esc(i)}</li>`).join("")}</ul>` : "<p>Not recorded</p>";
    const html = `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(row.company)} service report</title>
<style>body{font:16px/1.5 -apple-system,Segoe UI,Roboto,sans-serif;max-width:720px;margin:24px auto;padding:0 16px;color:#111}
h1{font-size:22px;margin:0 0 4px}h2{font-size:16px;margin:24px 0 6px;border-bottom:1px solid #ddd;padding-bottom:4px}.meta{color:#555;font-size:14px}</style>
</head><body>
<h1>${esc(row.company)} service report</h1>
<div class="meta">${esc(row.trade)} | ${esc(new Date(row.started_at).toLocaleDateString("en-US"))} | Technician: ${esc(row.tech)}<br>${esc(row.address)}</div>
<p>${esc(r.summary)}</p>
<h2>Your concern</h2><p>${esc(r.customerComplaint)}</p>
<h2>Equipment</h2><p>${esc(r.equipment)}</p>
<h2>What we found</h2>${list(r.findings)}
<h2>Work performed</h2>${list(r.workPerformed)}
<h2>Parts used</h2>${list(r.partsUsed)}
<h2>Readings</h2>${list([...(r.safetyReadings ?? []), ...(r.readings ?? [])])}
<h2>Other observations</h2>${list(r.observations)}
<h2>Safety</h2>${list(r.safetyEvents)}
<h2>Recommended follow-up</h2>${list(r.followUp)}
<p class="meta">Questions? Call ${esc(row.company_phone ?? row.company)}.</p>
</body></html>`;
    reply.header("content-type", "text/html; charset=utf-8").header("cache-control", "no-store");
    return html;
  });

  /** Local storage endpoints, used only when S3 is not configured. */
  app.put("/media/local/*", async (req) => {
    const key = decodeURIComponent((req.params as any)["*"]);
    const { exp, sig } = req.query as any;
    verifyLocalSignature("PUT", key, exp, sig);
    const body = req.body as Buffer;
    if (!Buffer.isBuffer(body)) throw notFound("Upload body missing.");
    await putObject(key, body, String(req.headers["content-type"] ?? "application/octet-stream"));
    return { ok: true, bytes: body.length };
  });

  app.get("/media/local/*", async (req, reply) => {
    const key = decodeURIComponent((req.params as any)["*"]);
    const { exp, sig } = req.query as any;
    verifyLocalSignature("GET", key, exp, sig);
    const buf = await readLocal(key);
    const type = key.endsWith(".mp4") ? "video/mp4" : key.endsWith(".m4a") ? "audio/mp4" : key.endsWith(".png") ? "image/png" : key.endsWith(".jpg") ? "image/jpeg" : key.endsWith(".pdf") ? "application/pdf" : key.endsWith(".json") ? "application/json" : "application/octet-stream";
    reply.header("content-type", type);
    return buf;
  });

  /** LiveKit server webhooks: expert joins, recordings finishing. */
  app.post("/livekit/webhook", async (req, reply) => {
    const raw = typeof req.body === "string" ? req.body : JSON.stringify(req.body);
    let event;
    try {
      event = await lk.receiveWebhook(raw, req.headers.authorization);
    } catch {
      return reply.status(401).send({ error: "bad_signature" });
    }
    const room = event.room?.name ?? event.egressInfo?.roomName ?? "";
    const visitId = lk.visitIdFromRoom(room);
    if (!visitId) return { ok: true };
    const visit = await loadVisit(visitId).catch(() => null);
    if (!visit) return { ok: true };
    if (event.event === "participant_joined" && event.participant && lk.identities.isExpert(event.participant.identity)) {
      const userId = event.participant.identity.slice("expert-".length);
      if (visit.escalation_status !== "joined") await markExpertJoined(visit, userId).catch(() => {});
    }
    if (event.event === "egress_ended" && event.egressInfo) {
      const files = event.egressInfo.fileResults ?? [];
      for (const f of files) {
        await q("update media set status = 'uploaded', bytes = $3 where visit_id = $1 and kind = 'cloud_recording' and meta->>'egressId' = $2", [
          visit.id,
          event.egressInfo.egressId,
          Number(f.size ?? 0),
        ]);
      }
      if (!files.length) {
        await q("update media set status = 'failed' where visit_id = $1 and kind = 'cloud_recording' and meta->>'egressId' = $2", [visit.id, event.egressInfo.egressId]);
      }
    }
    if (event.event === "participant_joined" || event.event === "participant_left") {
      await q("update visits set last_seen_at = now() where id = $1", [visit.id]);
    }
    return { ok: true };
  });
}
