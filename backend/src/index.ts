import { config } from "./config.js";
import { initDb, migrate } from "./db.js";
import { buildServer } from "./server.js";
import { seedTrades } from "./seed.js";
import { sweepEscalations } from "./visits.js";

async function main() {
  const cfg = config();
  await initDb();
  const applied = await migrate();
  if (applied.length) console.log(`[db] applied migrations: ${applied.join(", ")}`);
  await seedTrades();
  const app = await buildServer();
  // Escalation pages survive restarts: anything due gets paged on a timer.
  setInterval(() => sweepEscalations().catch((e) => console.error("[escalation] sweep failed", e)), 30_000).unref();
  await app.listen({ port: cfg.PORT, host: "0.0.0.0" });
  const shutdown = async () => {
    await app.close();
    process.exit(0);
  };
  process.on("SIGINT", shutdown);
  process.on("SIGTERM", shutdown);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
