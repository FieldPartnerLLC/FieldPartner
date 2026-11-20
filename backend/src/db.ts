import pg from "pg";
import { mkdirSync, readFileSync, readdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { config } from "./config.js";

/**
 * Two drivers behind one interface:
 *  - Postgres (DATABASE_URL set): production.
 *  - PGlite (embedded Postgres, no install): local runs and tests. Same SQL.
 */
export interface Executor {
  query<T = any>(text: string, params?: unknown[]): Promise<{ rows: T[] }>;
}

interface Driver extends Executor {
  exec(sql: string): Promise<void>;
  transaction<T>(fn: (ex: Executor) => Promise<T>): Promise<T>;
  close(): Promise<void>;
  kind: "postgres" | "pglite";
}

let driver: Driver | null = null;
let initializing: Promise<Driver> | null = null;

function pgDriver(url: string): Driver {
  const pool = new pg.Pool({ connectionString: url, max: 20, idleTimeoutMillis: 30_000, connectionTimeoutMillis: 10_000 });
  pool.on("error", (err) => console.error("[db] idle client error", err));
  return {
    kind: "postgres",
    async query(text, params = []) {
      const r = await pool.query(text, params as any[]);
      return { rows: r.rows };
    },
    async exec(sql) {
      await pool.query(sql);
    },
    async transaction(fn) {
      const client = await pool.connect();
      try {
        await client.query("begin");
        const out = await fn({
          query: async (text, params = []) => ({ rows: (await client.query(text, params as any[])).rows }),
        });
        await client.query("commit");
        return out;
      } catch (e) {
        await client.query("rollback").catch(() => {});
        throw e;
      } finally {
        client.release();
      }
    },
    async close() {
      await pool.end();
    },
  };
}

async function pgliteDriver(dataDir: string | null): Promise<Driver> {
  const { PGlite } = await import("@electric-sql/pglite");
  let dbDir: string | undefined;
  if (dataDir) {
    dbDir = resolve(dataDir, "db");
    mkdirSync(dbDir, { recursive: true });
  }
  const lite = new PGlite(dbDir);
  await lite.waitReady;
  // PGlite runs one statement at a time; serialize so concurrent requests
  // never interleave inside someone else's transaction.
  let chain: Promise<unknown> = Promise.resolve();
  const serial = <T>(fn: () => Promise<T>): Promise<T> => {
    const run = chain.then(fn, fn);
    chain = run.catch(() => {});
    return run;
  };
  return {
    kind: "pglite",
    query(text, params = []) {
      return serial(async () => ({ rows: (await lite.query<any>(text, params as any[])).rows }));
    },
    exec(sql) {
      return serial(async () => {
        await lite.exec(sql);
      });
    },
    transaction(fn) {
      return serial(() =>
        lite.transaction(async (t) =>
          fn({ query: async (text, params = []) => ({ rows: (await t.query<any>(text, params as any[])).rows }) }),
        ),
      );
    },
    async close() {
      await lite.close();
    },
  };
}

export async function initDb(opts: { memory?: boolean } = {}): Promise<Driver> {
  if (driver) return driver;
  if (!initializing) {
    initializing = (async () => {
      const url = config().DATABASE_URL;
      const d = url ? pgDriver(url) : await pgliteDriver(opts.memory ? null : config().DATA_DIR);
      driver = d;
      return d;
    })();
  }
  return initializing;
}

function current(): Driver {
  if (!driver) throw new Error("Database not initialized. Call initDb() first.");
  return driver;
}

export async function closeDb(): Promise<void> {
  if (driver) await driver.close();
  driver = null;
  initializing = null;
}

export function dbKind(): string {
  return driver?.kind ?? "uninitialized";
}

export async function q<T = any>(text: string, params: unknown[] = []): Promise<T[]> {
  return (await current().query<T>(text, params)).rows;
}

export async function one<T = any>(text: string, params: unknown[] = []): Promise<T | null> {
  const rows = await q<T>(text, params);
  return rows[0] ?? null;
}

export async function tx<T>(fn: (ex: Executor) => Promise<T>): Promise<T> {
  return current().transaction(fn);
}

const here = dirname(fileURLToPath(import.meta.url));

export function migrationsDir(): string {
  return join(here, "..", "migrations");
}

export async function migrate(): Promise<string[]> {
  const d = current();
  await d.exec("create table if not exists schema_migrations (name text primary key, applied_at timestamptz not null default now())");
  const applied: string[] = [];
  const files = readdirSync(migrationsDir()).filter((f) => f.endsWith(".sql")).sort();
  for (const file of files) {
    const done = await one("select name from schema_migrations where name = $1", [file]);
    if (done) continue;
    await d.exec(readFileSync(join(migrationsDir(), file), "utf8"));
    await q("insert into schema_migrations(name) values ($1)", [file]);
    applied.push(file);
  }
  return applied;
}
