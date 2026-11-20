import bcrypt from "bcryptjs";
import { SignJWT, jwtVerify } from "jose";
import type { FastifyReply, FastifyRequest } from "fastify";
import { config } from "./config.js";
import { one } from "./db.js";
import { HttpError } from "./errors.js";

export type Role = "tech" | "expert" | "admin" | "owner";

export interface AuthUser {
  id: string;
  role: Role;
  companyId: string | null;
  name: string;
  email: string;
}

declare module "fastify" {
  interface FastifyRequest {
    user?: AuthUser;
    isAgent?: boolean;
  }
}

const TOKEN_TTL = "30d";

function secret(): Uint8Array {
  return new TextEncoder().encode(config().JWT_SECRET);
}

export async function hashPassword(pw: string): Promise<string> {
  return bcrypt.hash(pw, 11);
}

export async function verifyPassword(pw: string, hash: string): Promise<boolean> {
  return bcrypt.compare(pw, hash);
}

export async function signToken(userId: string): Promise<string> {
  return new SignJWT({})
    .setProtectedHeader({ alg: "HS256" })
    .setSubject(userId)
    .setIssuedAt()
    .setExpirationTime(TOKEN_TTL)
    .sign(secret());
}

async function userFromToken(token: string): Promise<AuthUser> {
  let sub: string | undefined;
  try {
    const { payload } = await jwtVerify(token, secret(), { algorithms: ["HS256"] });
    sub = payload.sub;
  } catch {
    throw new HttpError(401, "invalid_token", "Your session expired. Sign in again.");
  }
  if (!sub) throw new HttpError(401, "invalid_token", "Your session expired. Sign in again.");
  // Checked on every request so a deactivated user is locked out at once.
  const row = await one<{ id: string; role: Role; company_id: string | null; name: string; email: string; active: boolean }>(
    "select id, role, company_id, name, email, active from users where id = $1",
    [sub],
  );
  if (!row || !row.active) throw new HttpError(401, "inactive", "This account is not active.");
  return { id: row.id, role: row.role, companyId: row.company_id, name: row.name, email: row.email };
}

/** Fastify preHandler: any signed-in user. */
export async function requireUser(req: FastifyRequest, _reply: FastifyReply): Promise<void> {
  const header = req.headers.authorization ?? "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : "";
  if (!token) throw new HttpError(401, "no_token", "Sign in required.");
  req.user = await userFromToken(token);
}

export function requireRole(...roles: Role[]) {
  return async (req: FastifyRequest, reply: FastifyReply): Promise<void> => {
    await requireUser(req, reply);
    if (!roles.includes(req.user!.role)) {
      throw new HttpError(403, "forbidden", "You don't have access to this.");
    }
  };
}

/** Fastify preHandler for the AI agent worker's service calls. */
export async function requireAgent(req: FastifyRequest, _reply: FastifyReply): Promise<void> {
  const header = req.headers.authorization ?? "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : "";
  if (!token || !timingSafeEqual(token, config().AGENT_SERVICE_TOKEN)) {
    throw new HttpError(401, "agent_auth", "Agent token rejected.");
  }
  req.isAgent = true;
}

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/** Owners see every company. Everyone else sees only their own. */
export function assertCompanyAccess(user: AuthUser, companyId: string): void {
  if (user.role === "owner") return;
  if (user.companyId !== companyId) {
    throw new HttpError(404, "not_found", "Not found.");
  }
}

export function canSupervise(user: AuthUser): boolean {
  return user.role === "expert" || user.role === "admin" || user.role === "owner";
}
