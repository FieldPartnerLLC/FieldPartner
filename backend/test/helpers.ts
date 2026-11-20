import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { FastifyInstance } from "fastify";

export const DATA = mkdtempSync(join(tmpdir(), "fp-test-"));

export function setTestEnv(extra: Record<string, string> = {}) {
  Object.assign(process.env, {
    NODE_ENV: "test",
    JWT_SECRET: "test-secret-test-secret-test-secret",
    AGENT_SERVICE_TOKEN: "agent-token-agent-token",
    PUBLIC_API_URL: "http://api.test",
    PORTAL_URL: "http://portal.test",
    DATA_DIR: DATA,
    ESCALATION_TIMEOUT_SECONDS: "1",
    LIVEKIT_URL: "",
    LIVEKIT_API_KEY: "",
    LIVEKIT_API_SECRET: "",
    S3_BUCKET: "",
    GOOGLE_API_KEY: "",
    TWILIO_ACCOUNT_SID: "",
    SMTP_HOST: "",
    ...extra,
  });
  // TEST_DATABASE_URL runs the same harness against real Postgres instead of the embedded one.
  if (process.env.TEST_DATABASE_URL) process.env.DATABASE_URL = process.env.TEST_DATABASE_URL;
  else delete process.env.DATABASE_URL;
}

export interface World {
  app: FastifyInstance;
  password: string;
  tokens: { tech: string; expert: string; owner: string };
  companyId: string;
}

export async function boot(): Promise<World> {
  setTestEnv();
  const { resetConfig } = await import("../src/config.js");
  resetConfig();
  const { initDb, migrate } = await import("../src/db.js");
  await initDb({ memory: true });
  await migrate();
  const { seedDemo } = await import("../src/seed.js");
  const accounts = await seedDemo("correct-horse-battery");
  const { buildServer } = await import("../src/server.js");
  const app = await buildServer({ logger: false });
  const login = async (email: string) => {
    const r = await app.inject({ method: "POST", url: "/auth/login", payload: { email, password: accounts.password } });
    if (r.statusCode !== 200) throw new Error(`login failed for ${email}: ${r.body}`);
    return r.json().token as string;
  };
  return {
    app,
    password: accounts.password,
    companyId: accounts.companyId,
    tokens: { tech: await login(accounts.tech), expert: await login(accounts.expert), owner: await login(accounts.owner) },
  };
}

export function auth(token: string) {
  return { authorization: `Bearer ${token}` };
}

export const AGENT = { authorization: "Bearer agent-token-agent-token" };
