import { closeDb, initDb, migrate } from "./db.js";

await initDb();
const applied = await migrate();
console.log(applied.length ? `Applied: ${applied.join(", ")}` : "Database is up to date.");
await closeDb();
