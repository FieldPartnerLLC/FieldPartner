import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { requireRole } from "../auth.js";
import { badRequest } from "../errors.js";
import { usageSummary } from "../lib/usage.js";

/**
 * AI spend this month: total, per visit, by model and by task. A company admin sees their own
 * company; the platform owner passes ?companyId=.
 */
export async function usageRoutes(app: FastifyInstance) {
  app.get("/usage", { preHandler: requireRole("admin", "owner") }, async (req) => {
    const qs = z.object({ companyId: z.string().uuid().optional() }).parse(req.query);
    const user = req.user!;
    const companyId = user.role === "owner" ? (qs.companyId ?? user.companyId) : user.companyId;
    if (!companyId) throw badRequest("Pick a company: /usage?companyId=...");
    return usageSummary(companyId);
  });
}
