import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { requireUser, signToken, verifyPassword, hashPassword } from "../auth.js";
import { one, q } from "../db.js";
import { HttpError } from "../errors.js";

export async function companyPublic(companyId: string | null) {
  if (!companyId) return null;
  return one(
    `select id, name, slug, phone, email, address, license_numbers, wake_phrase, co_stop_ppm, retention_days, diagnostic_fee_cents, settings
     from companies where id = $1`,
    [companyId],
  );
}

export async function authRoutes(app: FastifyInstance) {
  app.post(
    "/auth/login",
    { config: { rateLimit: { max: 20, timeWindow: "1 minute" } } },
    async (req) => {
      const body = z.object({ email: z.string().email(), password: z.string().min(1) }).parse(req.body);
      const user = await one<any>("select * from users where lower(email) = lower($1)", [body.email.trim()]);
      if (!user || !user.active || !(await verifyPassword(body.password, user.password_hash))) {
        throw new HttpError(401, "bad_login", "Email or password is wrong.");
      }
      const token = await signToken(user.id);
      await q("insert into audit_log (user_id, action) values ($1, 'login')", [user.id]);
      return {
        token,
        user: { id: user.id, name: user.name, email: user.email, role: user.role, trades: user.trades, certifications: user.certifications, companyId: user.company_id },
        company: await companyPublic(user.company_id),
      };
    },
  );

  app.get("/me", { preHandler: requireUser }, async (req) => {
    const u = await one<any>("select id, name, email, role, trades, certifications, company_id, phone from users where id = $1", [req.user!.id]);
    const trades = await q("select id, name, profile from trade_profiles order by name");
    return { user: { ...u, companyId: u.company_id }, company: await companyPublic(u.company_id), trades };
  });

  app.post("/me/password", { preHandler: requireUser }, async (req) => {
    const body = z.object({ current: z.string(), next: z.string().min(10, "Use at least 10 characters") }).parse(req.body);
    const u = await one<any>("select password_hash from users where id = $1", [req.user!.id]);
    if (!u || !(await verifyPassword(body.current, u.password_hash))) throw new HttpError(400, "bad_password", "Current password is wrong.");
    await q("update users set password_hash = $2 where id = $1", [req.user!.id, await hashPassword(body.next)]);
    return { ok: true };
  });
}
