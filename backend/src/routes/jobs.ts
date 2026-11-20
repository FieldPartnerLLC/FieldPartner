import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { assertCompanyAccess, requireUser } from "../auth.js";
import { one, q, tx } from "../db.js";
import { HttpError, notFound } from "../errors.js";

const jobInput = z.object({
  companyId: z.string().uuid().optional(),
  trade: z.string().min(1),
  title: z.string().min(1).max(200),
  complaint: z.string().max(4000).optional().nullable(),
  address: z.string().max(500).optional().nullable(),
  scheduledAt: z.string().datetime({ offset: true }).optional().nullable(),
  assignedTechId: z.string().uuid().optional().nullable(),
  accessNotes: z.string().max(2000).optional().nullable(),
  recordingPref: z.enum(["unknown", "video_ok", "audio_only", "no_recording"]).optional(),
  customer: z
    .object({
      id: z.string().uuid().optional(),
      name: z.string().min(1).max(200),
      phone: z.string().max(40).optional().nullable(),
      email: z.string().email().optional().nullable().or(z.literal("")),
      address: z.string().max(500).optional().nullable(),
    })
    .optional(),
});

const JOB_SELECT = `
  select j.*, cu.name as customer_name, cu.phone as customer_phone, cu.email as customer_email,
         u.name as tech_name,
         (select row_to_json(v) from (select id, state, started_at, ended_at, report_status from visits
            where job_id = j.id order by started_at desc limit 1) v) as last_visit
  from jobs j left join customers cu on cu.id = j.customer_id left join users u on u.id = j.assigned_tech_id`;

export async function jobRoutes(app: FastifyInstance) {
  app.get("/jobs", { preHandler: requireUser }, async (req) => {
    const qs = z
      .object({
        date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
        tz: z.string().optional(),
        scope: z.enum(["mine", "company"]).optional(),
        companyId: z.string().uuid().optional(),
      })
      .parse(req.query);
    const user = req.user!;
    const where: string[] = [];
    const params: unknown[] = [];
    if (user.role === "owner") {
      if (qs.companyId) {
        params.push(qs.companyId);
        where.push(`j.company_id = $${params.length}`);
      }
    } else {
      params.push(user.companyId);
      where.push(`j.company_id = $${params.length}`);
    }
    if (user.role === "tech" || qs.scope === "mine") {
      params.push(user.id);
      where.push(`(j.assigned_tech_id = $${params.length} or j.assigned_tech_id is null)`);
    }
    if (qs.date) {
      // Day boundaries in the caller's time zone; default US Eastern.
      params.push(qs.date, qs.tz ?? "America/New_York");
      where.push(
        `(j.scheduled_at is null or (j.scheduled_at at time zone $${params.length})::date = $${params.length - 1}::date
          or j.status = 'in_progress')`,
      );
    }
    where.push(`j.status <> 'cancelled'`);
    const rows = await q(`${JOB_SELECT} where ${where.join(" and ")} order by j.scheduled_at nulls last, j.created_at desc limit 200`, params);
    return { jobs: rows };
  });

  app.post("/jobs", { preHandler: requireUser }, async (req) => {
    const body = jobInput.parse(req.body);
    const user = req.user!;
    const companyId = user.role === "owner" ? body.companyId ?? user.companyId : user.companyId;
    if (!companyId) throw new HttpError(400, "company_required", "Pick a company for this job.");
    assertCompanyAccess(user, companyId);
    const trade = await one("select id from trade_profiles where id = $1", [body.trade]);
    if (!trade) throw new HttpError(400, "bad_trade", `Unknown trade: ${body.trade}`);
    // Technicians may create walk-in jobs, always assigned to themselves.
    const assigned = user.role === "tech" ? user.id : body.assignedTechId ?? null;
    if (assigned) {
      const tech = await one<any>("select company_id from users where id = $1 and active", [assigned]);
      if (!tech || (tech.company_id !== companyId && user.role !== "owner")) throw new HttpError(400, "bad_tech", "That technician is not in this company.");
    }
    const id = await tx(async (ex) => {
      let customerId: string | null = body.customer?.id ?? null;
      if (!customerId && body.customer) {
        const c = await ex.query<{ id: string }>(
          "insert into customers (company_id, name, phone, email, address) values ($1,$2,$3,$4,$5) returning id",
          [companyId, body.customer.name, body.customer.phone ?? null, body.customer.email || null, body.customer.address ?? body.address ?? null],
        );
        customerId = c.rows[0].id;
      }
      const r = await ex.query<{ id: string }>(
        `insert into jobs (company_id, customer_id, trade, title, complaint, address, scheduled_at, assigned_tech_id, access_notes, recording_pref)
         values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) returning id`,
        [
          companyId,
          customerId,
          body.trade,
          body.title,
          body.complaint ?? null,
          body.address ?? null,
          body.scheduledAt ?? null,
          assigned,
          body.accessNotes ?? null,
          body.recordingPref ?? "unknown",
        ],
      );
      return r.rows[0].id;
    });
    return { job: await one(`${JOB_SELECT} where j.id = $1`, [id]) };
  });

  app.get("/jobs/:id", { preHandler: requireUser }, async (req) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(req.params);
    const job = await one<any>(`${JOB_SELECT} where j.id = $1`, [id]);
    if (!job) throw notFound("Job not found.");
    assertCompanyAccess(req.user!, job.company_id);
    const history = job.customer_id
      ? await q(
          `select v.id, v.started_at, v.report->>'summary' as summary, j2.title from visits v join jobs j2 on j2.id = v.job_id
           where j2.customer_id = $1 and v.ended_at is not null order by v.started_at desc limit 5`,
          [job.customer_id],
        )
      : [];
    const trade = await one("select id, name, profile from trade_profiles where id = $1", [job.trade]);
    return { job, history, trade };
  });

  app.patch("/jobs/:id", { preHandler: requireUser }, async (req) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(req.params);
    const body = jobInput.partial().extend({ status: z.enum(["scheduled", "in_progress", "completed", "cancelled", "declined", "return_needed"]).optional() }).parse(req.body);
    const job = await one<any>("select * from jobs where id = $1", [id]);
    if (!job) throw notFound("Job not found.");
    assertCompanyAccess(req.user!, job.company_id);
    if (req.user!.role === "tech" && job.assigned_tech_id && job.assigned_tech_id !== req.user!.id) throw notFound("Job not found.");
    const map: Record<string, string> = {
      title: "title",
      complaint: "complaint",
      address: "address",
      scheduledAt: "scheduled_at",
      assignedTechId: "assigned_tech_id",
      accessNotes: "access_notes",
      recordingPref: "recording_pref",
      status: "status",
      trade: "trade",
    };
    const sets: string[] = [];
    const params: unknown[] = [id];
    for (const [k, col] of Object.entries(map)) {
      if ((body as any)[k] !== undefined) {
        if (req.user!.role === "tech" && (k === "assignedTechId" || k === "trade")) continue;
        params.push((body as any)[k]);
        sets.push(`${col} = $${params.length}`);
      }
    }
    if (sets.length) await q(`update jobs set ${sets.join(", ")} where id = $1`, params);
    return { job: await one(`${JOB_SELECT} where j.id = $1`, [id]) };
  });

  app.get("/trades", { preHandler: requireUser }, async () => ({ trades: await q("select id, name, profile from trade_profiles order by name") }));
}
