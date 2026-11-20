import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { randomBytes } from "node:crypto";
import { hashPassword } from "./auth.js";
import { closeDb, initDb, migrate, one, q } from "./db.js";

const here = dirname(fileURLToPath(import.meta.url));

export function loadTradesFile(): { trades: any[] } {
  const candidates = [join(here, "..", "..", "shared", "trades.json"), join(here, "..", "shared", "trades.json")];
  for (const p of candidates) {
    try {
      return JSON.parse(readFileSync(p, "utf8"));
    } catch {
      /* try next */
    }
  }
  throw new Error("shared/trades.json not found");
}

/** Inserts built-in trade profiles. Never overwrites a profile an owner edited. */
export async function seedTrades(): Promise<void> {
  const { trades } = loadTradesFile();
  for (const t of trades) {
    await q("insert into trade_profiles (id, name, profile) values ($1,$2,$3) on conflict (id) do nothing", [t.id, t.name, JSON.stringify(t)]);
  }
}

export interface DemoAccounts {
  companyId: string;
  password: string;
  owner: string;
  expert: string;
  tech: string;
}

export async function seedDemo(password = process.env.SEED_PASSWORD || randomBytes(9).toString("base64url")): Promise<DemoAccounts> {
  await seedTrades();
  let company = await one<{ id: string }>("select id from companies where slug = 'demo'");
  if (!company) {
    company = await one<{ id: string }>(
      `insert into companies (name, slug, phone, wake_phrase, co_stop_ppm, settings)
       values ('Demo Field Services','demo','+15555550100','hey dispatch',35,'{"supervisorName":"Mitch"}') returning id`,
    );
  }
  const hash = await hashPassword(password);
  const users: [string, string, string, string | null][] = [
    ["owner@fieldpartner.local", "Owner", "owner", null],
    ["expert@fieldpartner.local", "Mitch (Expert)", "expert", company!.id],
    ["tech@fieldpartner.local", "Nick (Technician)", "tech", company!.id],
  ];
  for (const [email, name, role, companyId] of users) {
    await q(
      `insert into users (company_id, email, name, role, trades, password_hash, phone)
       values ($1,$2,$3,$4,$5,$6,$7)
       on conflict (email) do update set password_hash = excluded.password_hash`,
      [companyId, email, name, role, ["hvac", "electrical", "plumbing", "roofing", "cleaning", "auto", "appliance", "general"], hash, null],
    );
  }
  const tech = await one<{ id: string }>("select id from users where email = 'tech@fieldpartner.local'");
  const existingJobs = await one<{ n: string }>("select count(*)::text as n from jobs where company_id = $1", [company!.id]);
  if (Number(existingJobs?.n ?? 0) === 0) {
    const samples: [string, string, string][] = [
      ["hvac", "No heat, furnace not firing", "Furnace runs the inducer but burners never light. Started this morning."],
      ["electrical", "Kitchen outlets dead", "Two kitchen outlets stopped working after the microwave tripped something."],
      ["plumbing", "Water heater leaking", "Small puddle under the water heater in the basement."],
      ["auto", "Check engine light", "Check engine light on, car hesitates when accelerating."],
    ];
    let hour = 9;
    for (const [trade, title, complaint] of samples) {
      const c = await one<{ id: string }>(
        "insert into customers (company_id, name, phone, email, address) values ($1,$2,$3,$4,$5) returning id",
        [company!.id, `Test Customer ${hour}`, null, null, "123 Test Street"],
      );
      const when = new Date();
      when.setHours(hour++, 0, 0, 0);
      await q(
        `insert into jobs (company_id, customer_id, trade, title, complaint, address, scheduled_at, assigned_tech_id)
         values ($1,$2,$3,$4,$5,$6,$7,$8)`,
        [company!.id, c!.id, trade, title, complaint, "123 Test Street", when.toISOString(), tech!.id],
      );
    }
  }
  return {
    companyId: company!.id,
    password,
    owner: "owner@fieldpartner.local",
    expert: "expert@fieldpartner.local",
    tech: "tech@fieldpartner.local",
  };
}

if (process.argv[1] && process.argv[1].endsWith("seed.ts")) {
  await initDb();
  await migrate();
  const a = await seedDemo();
  console.log("\nDemo accounts (all use the same password):");
  console.log(`  Technician: ${a.tech}`);
  console.log(`  Expert:     ${a.expert}`);
  console.log(`  Owner:      ${a.owner}`);
  console.log(`  Password:   ${a.password}\n`);
  await closeDb();
}
