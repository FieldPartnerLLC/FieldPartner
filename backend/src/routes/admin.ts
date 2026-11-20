import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { assertCompanyAccess, hashPassword, requireRole } from "../auth.js";
import { one, q } from "../db.js";
import { HttpError, notFound } from "../errors.js";

const userInput = z.object({
  companyId: z.string().uuid().optional().nullable(),
  email: z.string().email(),
  name: z.string().min(1).max(200),
  phone: z.string().max(40).optional().nullable(),
  whatsapp: z.string().max(40).optional().nullable(),
  role: z.enum(["tech", "expert", "admin", "owner"]),
  trades: z.array(z.string()).default([]),
  certifications: z.array(z.string()).default([]),
  escalationOrder: z.number().int().min(0).max(1000).default(100),
  password: z.string().min(10, "Use at least 10 characters"),
});

const USER_COLS = "id, company_id, email, name, phone, whatsapp, role, trades, certifications, escalation_order, active, created_at";

export async function adminRoutes(app: FastifyInstance) {
  const admin = requireRole("admin", "owner");
  const owner = requireRole("owner");

  app.get("/admin/users", { preHandler: admin }, async (req) => {
    const user = req.user!;
    const rows =
      user.role === "owner"
        ? await q(`select ${USER_COLS} from users order by company_id nulls first, name`)
        : await q(`select ${USER_COLS} from users where company_id = $1 order by name`, [user.companyId]);
    return { users: rows };
  });

  app.post("/admin/users", { preHandler: admin }, async (req) => {
    const body = userInput.parse(req.body);
    const user = req.user!;
    if (body.role === "owner" && user.role !== "owner") throw new HttpError(403, "forbidden", "Only an owner can add owners.");
    const companyId = user.role === "owner" ? body.companyId ?? null : user.companyId;
    if (body.role !== "owner" && !companyId) throw new HttpError(400, "company_required", "Pick a company for this user.");
    if (companyId) assertCompanyAccess(user, companyId);
    const exists = await one("select id from users where lower(email) = lower($1)", [body.email]);
    if (exists) throw new HttpError(409, "email_taken", "A user with that email already exists.");
    const row = await one(
      `insert into users (company_id, email, name, phone, whatsapp, role, trades, certifications, escalation_order, password_hash)
       values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) returning ${USER_COLS}`,
      [
        companyId,
        body.email.trim(),
        body.name,
        body.phone ?? null,
        body.whatsapp ?? null,
        body.role,
        body.trades,
        body.certifications,
        body.escalationOrder,
        await hashPassword(body.password),
      ],
    );
    return { user: row };
  });

  app.patch("/admin/users/:id", { preHandler: admin }, async (req) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(req.params);
    const body = userInput.partial().extend({ active: z.boolean().optional() }).parse(req.body);
    const target = await one<any>("select * from users where id = $1", [id]);
    if (!target) throw notFound("User not found.");
    if (target.company_id) assertCompanyAccess(req.user!, target.company_id);
    else if (req.user!.role !== "owner") throw notFound("User not found.");
    if (body.role === "owner" && req.user!.role !== "owner") throw new HttpError(403, "forbidden", "Only an owner can make owners.");
    const map: Record<string, string> = {
      name: "name",
      phone: "phone",
      whatsapp: "whatsapp",
      role: "role",
      trades: "trades",
      certifications: "certifications",
      escalationOrder: "escalation_order",
      active: "active",
    };
    const sets: string[] = [];
    const params: unknown[] = [id];
    for (const [k, col] of Object.entries(map)) {
      if ((body as any)[k] !== undefined) {
        params.push((body as any)[k]);
        sets.push(`${col} = $${params.length}`);
      }
    }
    if (body.password) {
      params.push(await hashPassword(body.password));
      sets.push(`password_hash = $${params.length}`);
    }
    if (sets.length) await q(`update users set ${sets.join(", ")} where id = $1`, params);
    return { user: await one(`select ${USER_COLS} from users where id = $1`, [id]) };
  });

  app.get("/admin/companies", { preHandler: admin }, async (req) => {
    const rows =
      req.user!.role === "owner"
        ? await q("select * from companies order by name")
        : await q("select * from companies where id = $1", [req.user!.companyId]);
    return { companies: rows };
  });

  const companyInput = z.object({
    name: z.string().min(1).max(200),
    slug: z.string().regex(/^[a-z0-9-]{2,60}$/),
    phone: z.string().max(40).optional().nullable(),
    email: z.string().email().optional().nullable(),
    address: z.string().max(500).optional().nullable(),
    licenseNumbers: z.string().max(500).optional().nullable(),
    wakePhrase: z.string().min(3).max(40).optional(),
    coStopPpm: z.number().int().min(9).max(200).optional(),
    retentionDays: z.number().int().min(1).max(3650).optional(),
    diagnosticFeeCents: z.number().int().min(0).optional().nullable(),
    settings: z.record(z.string(), z.unknown()).optional(),
  });

  app.post("/admin/companies", { preHandler: owner }, async (req) => {
    const b = companyInput.parse(req.body);
    const row = await one(
      `insert into companies (name, slug, phone, email, address, license_numbers, wake_phrase, co_stop_ppm, retention_days, diagnostic_fee_cents, settings)
       values ($1,$2,$3,$4,$5,$6,coalesce($7,'hey dispatch'),coalesce($8,35),coalesce($9,365),$10,coalesce($11,'{}'::jsonb)) returning *`,
      [
        b.name,
        b.slug,
        b.phone ?? null,
        b.email ?? null,
        b.address ?? null,
        b.licenseNumbers ?? null,
        b.wakePhrase?.toLowerCase() ?? null,
        b.coStopPpm ?? null,
        b.retentionDays ?? null,
        b.diagnosticFeeCents ?? null,
        b.settings ? JSON.stringify(b.settings) : null,
      ],
    );
    return { company: row };
  });

  app.patch("/admin/companies/:id", { preHandler: admin }, async (req) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(req.params);
    assertCompanyAccess(req.user!, id);
    const b = companyInput.partial().parse(req.body);
    const map: Record<string, string> = {
      name: "name",
      phone: "phone",
      email: "email",
      address: "address",
      licenseNumbers: "license_numbers",
      wakePhrase: "wake_phrase",
      coStopPpm: "co_stop_ppm",
      retentionDays: "retention_days",
      diagnosticFeeCents: "diagnostic_fee_cents",
    };
    const sets: string[] = [];
    const params: unknown[] = [id];
    for (const [k, col] of Object.entries(map)) {
      const v = (b as any)[k];
      if (v !== undefined) {
        params.push(k === "wakePhrase" ? String(v).toLowerCase() : v);
        sets.push(`${col} = $${params.length}`);
      }
    }
    if (b.settings) {
      params.push(JSON.stringify(b.settings));
      sets.push(`settings = settings || $${params.length}::jsonb`);
    }
    if (sets.length) await q(`update companies set ${sets.join(", ")} where id = $1`, params);
    return { company: await one("select * from companies where id = $1", [id]) };
  });

  app.put("/admin/trades/:id", { preHandler: owner }, async (req) => {
    const { id } = z.object({ id: z.string().regex(/^[a-z0-9_-]{2,40}$/) }).parse(req.params);
    const body = z.object({ name: z.string().min(1), profile: z.record(z.string(), z.unknown()) }).parse(req.body);
    await q(
      `insert into trade_profiles (id, name, profile) values ($1,$2,$3)
       on conflict (id) do update set name = excluded.name, profile = excluded.profile, updated_at = now()`,
      [id, body.name, JSON.stringify({ ...body.profile, id, name: body.name })],
    );
    return { ok: true };
  });
}
