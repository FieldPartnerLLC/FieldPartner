import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { requireRole } from "../auth.js";
import { one, q } from "../db.js";
import { badRequest, notFound } from "../errors.js";

/**
 * Company knowledge base review. Claude proposes lessons from approved visits; experts and admins
 * approve, edit or reject them, and can write their own. Only approved lessons reach the AI.
 */
const reviewers = requireRole("expert", "admin", "owner");

function companyOf(user: { companyId: string | null }): string {
  if (!user.companyId) throw badRequest("This account is not part of a company. Sign in as the company's admin or expert.");
  return user.companyId;
}

const lessonFields = z.object({
  trade: z.string().min(1).max(40),
  equipment_type: z.string().max(80).nullish(),
  brand: z.string().max(80).nullish(),
  model: z.string().max(80).nullish(),
  symptom: z.string().min(3).max(400),
  error_code: z.string().max(40).nullish(),
  cause: z.string().max(400).nullish(),
  fix: z.string().min(3).max(600),
  parts: z.string().max(400).nullish(),
});

export async function lessonRoutes(app: FastifyInstance) {
  app.get("/lessons", { preHandler: reviewers }, async (req) => {
    const { status, trade } = z
      .object({ status: z.enum(["pending", "approved", "rejected"]).default("pending"), trade: z.string().max(40).optional() })
      .parse(req.query);
    const companyId = companyOf(req.user!);
    const rows = await q<any>(
      `select l.*, j.title as job_title, u.name as reviewed_by_name
       from skill_lessons l
       left join visits v on v.id = l.source_visit_id left join jobs j on j.id = v.job_id
       left join users u on u.id = l.reviewed_by
       where l.company_id = $1 and l.status = $2 and ($3::text is null or l.trade = $3)
       order by l.created_at desc limit 200`,
      [companyId, status, trade ?? null],
    );
    const counts = await q<{ status: string; n: number }>("select status, count(*)::int as n from skill_lessons where company_id = $1 group by status", [companyId]);
    return { lessons: rows, counts: Object.fromEntries(counts.map((c) => [c.status, c.n])) };
  });

  /** An expert writes a lesson directly. It is approved immediately. */
  app.post("/lessons", { preHandler: reviewers }, async (req) => {
    const b = lessonFields.parse(req.body);
    const row = await one<any>(
      `insert into skill_lessons (company_id, trade, equipment_type, brand, model, symptom, error_code, cause, fix, parts, source, status, created_by, reviewed_by, reviewed_at)
       values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,'expert','approved',$11,$11,now()) returning *`,
      [companyOf(req.user!), b.trade, b.equipment_type ?? null, b.brand ?? null, b.model ?? null, b.symptom, b.error_code ?? null, b.cause ?? null, b.fix, b.parts ?? null, req.user!.id],
    );
    return { lesson: row };
  });

  /** Approve, reject, or correct a lesson. Edits and approval can be sent together. */
  app.patch("/lessons/:id", { preHandler: reviewers }, async (req) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(req.params);
    const b = lessonFields.partial().extend({ status: z.enum(["pending", "approved", "rejected"]).optional() }).parse(req.body);
    const existing = await one<any>("select * from skill_lessons where id = $1 and company_id = $2", [id, companyOf(req.user!)]);
    if (!existing) throw notFound("Lesson not found.");
    const merged = { ...existing, ...Object.fromEntries(Object.entries(b).filter(([, v]) => v !== undefined)) };
    const row = await one<any>(
      `update skill_lessons set trade=$2, equipment_type=$3, brand=$4, model=$5, symptom=$6, error_code=$7, cause=$8, fix=$9, parts=$10, status=$11,
         reviewed_by = case when $11 <> status then $12::uuid else reviewed_by end,
         reviewed_at = case when $11 <> status then now() else reviewed_at end
       where id = $1 returning *`,
      [id, merged.trade, merged.equipment_type, merged.brand, merged.model, merged.symptom, merged.error_code, merged.cause, merged.fix, merged.parts, merged.status, req.user!.id],
    );
    return { lesson: row };
  });
}
