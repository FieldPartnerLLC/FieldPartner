import { randomInt } from "node:crypto";
import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { hashPassword, requireRole, signToken } from "../auth.js";
import { config } from "../config.js";
import { one, q, tx } from "../db.js";
import { HttpError, notFound } from "../errors.js";
import { sendEmail, sendWhatsApp, type SentMessage } from "../lib/notify.js";
import { companyPublic } from "./auth.js";

// No 0/O, 1/I/L: codes are read aloud and typed on a phone.
const CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

export function newInviteCode(): string {
  let s = "";
  for (let i = 0; i < 8; i++) s += CODE_ALPHABET[randomInt(CODE_ALPHABET.length)];
  return s;
}

/** Accepts "abcd-2345", " ABCD 2345 " etc. */
export function normalizeCode(raw: string): string {
  return raw.toUpperCase().replace(/[^A-Z0-9]/g, "");
}

function slugify(name: string): string {
  const base = name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 40);
  return base.length >= 2 ? base : "company";
}

async function uniqueSlug(name: string): Promise<string> {
  const base = slugify(name);
  for (let i = 0; i < 50; i++) {
    const slug = i === 0 ? base : `${base}-${i + 1}`;
    if (!(await one("select 1 from companies where slug = $1", [slug]))) return slug;
  }
  return `${base}-${Date.now()}`;
}

function userView(u: any) {
  return { id: u.id, name: u.name, email: u.email, role: u.role, trades: u.trades, certifications: u.certifications, companyId: u.company_id };
}

const password = z.string().min(10, "Use at least 10 characters").max(200);

export async function onboardingRoutes(app: FastifyInstance) {
  const admin = requireRole("admin", "owner");

  /** A business creates its account. The person signing up becomes the company admin. */
  app.post("/auth/signup", { config: { rateLimit: { max: 10, timeWindow: "1 hour" } } }, async (req) => {
    if (!config().SIGNUP_ENABLED) throw new HttpError(403, "signup_closed", "New company sign-up is turned off on this server. Ask your administrator.");
    const b = z
      .object({
        companyName: z.string().trim().min(2).max(200),
        name: z.string().trim().min(1).max(200),
        email: z.string().trim().email(),
        password,
        phone: z.string().max(40).optional().nullable(),
        trades: z.array(z.string()).max(20).default([]),
      })
      .parse(req.body);
    if (await one("select 1 from users where lower(email) = lower($1)", [b.email])) {
      throw new HttpError(409, "email_taken", "An account with that email already exists. Sign in instead.");
    }
    const hash = await hashPassword(b.password);
    const slug = await uniqueSlug(b.companyName);
    const { company, user } = await tx(async (ex) => {
      const c = (await ex.query("insert into companies (name, slug, phone, email) values ($1,$2,$3,$4) returning id", [b.companyName, slug, b.phone ?? null, b.email])).rows[0];
      const u = (
        await ex.query(
          `insert into users (company_id, email, name, phone, role, trades, password_hash, escalation_order)
           values ($1,$2,$3,$4,'admin',$5,$6,10) returning *`,
          [c.id, b.email, b.name, b.phone ?? null, b.trades, hash],
        )
      ).rows[0];
      return { company: c, user: u };
    });
    await q("insert into audit_log (user_id, action, meta) values ($1, 'signup', $2)", [user.id, JSON.stringify({ companyId: company.id })]).catch(() => {});
    return { token: await signToken(user.id), user: userView(user), company: await companyPublic(company.id) };
  });

  /** Admin invites a technician, expert or another admin. Returns the code to hand over. */
  app.post("/admin/invites", { preHandler: admin }, async (req) => {
    const b = z
      .object({
        companyId: z.string().uuid().optional(),
        name: z.string().trim().min(1).max(200),
        email: z.string().trim().email(),
        phone: z.string().max(40).optional().nullable(),
        whatsapp: z.string().max(40).optional().nullable(),
        role: z.enum(["tech", "expert", "admin"]).default("tech"),
        trades: z.array(z.string()).max(20).default([]),
        send: z.boolean().default(true),
      })
      .parse(req.body);
    const user = req.user!;
    const companyId = user.role === "owner" ? b.companyId ?? user.companyId : user.companyId;
    if (!companyId) throw new HttpError(400, "company_required", "Pick a company for this invite.");
    if (user.role !== "owner" && companyId !== user.companyId) throw notFound("Company not found.");
    if (await one("select 1 from users where lower(email) = lower($1)", [b.email])) {
      throw new HttpError(409, "email_taken", "That person already has an account. They can sign in.");
    }
    // One open invite per email: a new one replaces the old.
    await q("update invites set revoked_at = now() where lower(email) = lower($1) and used_at is null and revoked_at is null", [b.email]);
    let code = newInviteCode();
    while (await one("select 1 from invites where code = $1", [code])) code = newInviteCode();
    const invite = await one<any>(
      `insert into invites (code, company_id, email, name, phone, whatsapp, role, trades, created_by, expires_at)
       values ($1,$2,$3,$4,$5,$6,$7,$8,$9, now() + make_interval(days => $10)) returning *`,
      [code, companyId, b.email, b.name, b.phone ?? null, b.whatsapp ?? null, b.role, b.trades, user.id, config().INVITE_DAYS],
    );
    const company = await one<any>("select name from companies where id = $1", [companyId]);
    const pretty = `${code.slice(0, 4)}-${code.slice(4)}`;
    const message =
      `${company?.name ?? "Your company"} invited you to FieldPartner.\n` +
      `1. Install the FieldPartner app.\n2. Tap "I have an invite code".\n3. Server: ${config().PUBLIC_API_URL}\n4. Code: ${pretty}\n` +
      `The code works once and expires in ${config().INVITE_DAYS} days.`;
    const delivered: SentMessage[] = [];
    if (b.send) {
      delivered.push(await sendEmail(b.email, `Your FieldPartner invite code: ${pretty}`, message));
      const wa = b.whatsapp || b.phone;
      if (wa) delivered.push(await sendWhatsApp(wa, message));
    }
    return {
      invite: { ...invite, code: pretty },
      message,
      delivered: delivered.filter((d) => d.ok).map((d) => d.channel),
    };
  });

  app.get("/admin/invites", { preHandler: admin }, async (req) => {
    const user = req.user!;
    const rows = await q(
      `select id, code, company_id, email, name, role, trades, expires_at, used_at, revoked_at, created_at
       from invites where ($1::uuid is null or company_id = $1) order by created_at desc limit 200`,
      [user.role === "owner" ? null : user.companyId],
    );
    return { invites: rows.map((r: any) => ({ ...r, code: `${r.code.slice(0, 4)}-${r.code.slice(4)}` })) };
  });

  app.delete("/admin/invites/:id", { preHandler: admin }, async (req) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(req.params);
    const inv = await one<any>("select * from invites where id = $1", [id]);
    if (!inv || (req.user!.role !== "owner" && inv.company_id !== req.user!.companyId)) throw notFound("Invite not found.");
    await q("update invites set revoked_at = now() where id = $1 and used_at is null", [id]);
    return { ok: true };
  });

  async function openInvite(raw: string) {
    const code = normalizeCode(raw);
    const inv = await one<any>("select i.*, c.name as company_name from invites i join companies c on c.id = i.company_id where i.code = $1", [code]);
    if (!inv || inv.revoked_at) throw new HttpError(404, "bad_code", "That invite code is not valid. Check it with your manager.");
    if (inv.used_at) throw new HttpError(409, "code_used", "That invite code was already used. Sign in with your email and password.");
    if (new Date(inv.expires_at).getTime() < Date.now()) throw new HttpError(410, "code_expired", "That invite code has expired. Ask your manager for a new one.");
    return inv;
  }

  /** The app shows who the invite is for before the tech picks a password. */
  app.get("/auth/invite/:code", { config: { rateLimit: { max: 30, timeWindow: "1 minute" } } }, async (req) => {
    const { code } = z.object({ code: z.string().min(4).max(20) }).parse(req.params);
    const inv = await openInvite(code);
    return { companyName: inv.company_name, name: inv.name, email: inv.email, role: inv.role };
  });

  /** Creates the account from the invite and signs the person in. */
  app.post("/auth/accept-invite", { config: { rateLimit: { max: 20, timeWindow: "1 minute" } } }, async (req) => {
    const b = z.object({ code: z.string().min(4).max(20), password, name: z.string().trim().min(1).max(200).optional() }).parse(req.body);
    const inv = await openInvite(b.code);
    if (await one("select 1 from users where lower(email) = lower($1)", [inv.email])) {
      throw new HttpError(409, "email_taken", "An account with this email already exists. Sign in instead.");
    }
    const hash = await hashPassword(b.password);
    const user = await tx(async (ex) => {
      const claimed = await ex.query("update invites set used_at = now() where id = $1 and used_at is null and revoked_at is null returning id", [inv.id]);
      if (claimed.rows.length === 0) throw new HttpError(409, "code_used", "That invite code was already used.");
      const u = (
        await ex.query(
          `insert into users (company_id, email, name, phone, whatsapp, role, trades, password_hash)
           values ($1,$2,$3,$4,$5,$6,$7,$8) returning *`,
          [inv.company_id, inv.email, b.name ?? inv.name, inv.phone, inv.whatsapp, inv.role, inv.trades, hash],
        )
      ).rows[0];
      await ex.query("update invites set used_by = $2 where id = $1", [inv.id, u.id]);
      return u;
    });
    await q("insert into audit_log (user_id, action, meta) values ($1, 'invite_accepted', $2)", [user.id, JSON.stringify({ inviteId: inv.id })]).catch(() => {});
    return { token: await signToken(user.id), user: userView(user), company: await companyPublic(user.company_id) };
  });
}
