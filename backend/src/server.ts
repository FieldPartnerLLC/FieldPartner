import Fastify, { type FastifyInstance } from "fastify";
import cors from "@fastify/cors";
import rateLimit from "@fastify/rate-limit";
import { ZodError } from "zod";
import { HttpError } from "./errors.js";
import { authRoutes } from "./routes/auth.js";
import { jobRoutes } from "./routes/jobs.js";
import { visitRoutes } from "./routes/visits.js";
import { supervisorRoutes } from "./routes/supervisor.js";
import { adminRoutes } from "./routes/admin.js";
import { agentRoutes } from "./routes/agent.js";
import { publicRoutes } from "./routes/public.js";
import { dbKind } from "./db.js";
import { features } from "./config.js";
import { storageKind } from "./lib/storage.js";

export async function buildServer(opts: { logger?: boolean } = {}): Promise<FastifyInstance> {
  const app = Fastify({
    logger: opts.logger ?? true,
    bodyLimit: 8 * 1024 * 1024,
    trustProxy: true,
  });

  await app.register(cors, { origin: true, credentials: true });
  await app.register(rateLimit, { max: 600, timeWindow: "1 minute", allowList: (req) => req.url.startsWith("/agent/") });

  // Raw bodies for the LiveKit webhook signature check and local media uploads.
  app.addContentTypeParser("application/webhook+json", { parseAs: "string" }, (_req, body, done) => done(null, body));
  // Uploads must stay raw bytes. Drop the built-in text/plain string parser so the buffer parser below wins.
  app.removeContentTypeParser("text/plain");
  app.addContentTypeParser(/^(video|audio|image|application\/octet-stream|text\/plain)/, { parseAs: "buffer", bodyLimit: 2 * 1024 * 1024 * 1024 }, (_req, body, done) =>
    done(null, body),
  );

  app.setErrorHandler((err, req, reply) => {
    if (err instanceof HttpError) {
      return reply.status(err.status).send({ error: err.code, message: err.message, details: err.details });
    }
    if (err instanceof ZodError) {
      return reply.status(400).send({
        error: "validation",
        message: err.issues.map((i) => `${i.path.join(".") || "body"}: ${i.message}`).join("; "),
      });
    }
    const status = (err as any).statusCode ?? 500;
    if (status < 500) return reply.status(status).send({ error: "bad_request", message: (err as Error).message });
    req.log.error(err);
    return reply.status(500).send({ error: "server_error", message: "Something went wrong on the server. Try again." });
  });

  app.get("/healthz", async () => ({
    ok: true,
    db: dbKind(),
    storage: storageKind(),
    livekit: features.livekit(),
    gemini: features.gemini(),
    twilio: features.twilio(),
    email: features.smtp(),
  }));

  await app.register(authRoutes);
  await app.register(jobRoutes);
  await app.register(visitRoutes);
  await app.register(supervisorRoutes);
  await app.register(adminRoutes);
  await app.register(agentRoutes);
  await app.register(publicRoutes);
  return app;
}
