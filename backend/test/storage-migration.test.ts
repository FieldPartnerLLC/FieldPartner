import { mkdirSync, writeFileSync, rmSync } from "node:fs";
import { dirname, join } from "node:path";
import { Readable } from "node:stream";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { DATA, setTestEnv } from "./helpers.js";

/** In-memory stand-in for an S3/R2 bucket: HeadObject and PutObject are all the move needs. */
class FakeBucket {
  objects = new Map<string, Buffer>();
  puts = 0;
  down = false;
  async send(cmd: any): Promise<any> {
    if (this.down) throw Object.assign(new Error("connect ECONNREFUSED"), { name: "Error" });
    const name = cmd.constructor.name;
    const key: string = cmd.input.Key;
    if (name === "HeadObjectCommand") {
      const obj = this.objects.get(key);
      if (!obj) throw Object.assign(new Error("NotFound"), { name: "NotFound", $metadata: { httpStatusCode: 404 } });
      return { ContentLength: obj.length };
    }
    if (name === "PutObjectCommand") {
      const body = cmd.input.Body;
      let buf: Buffer;
      if (Buffer.isBuffer(body)) buf = body;
      else if (body instanceof Readable) {
        const chunks: Buffer[] = [];
        for await (const c of body) chunks.push(Buffer.from(c));
        buf = Buffer.concat(chunks);
      } else buf = Buffer.from(body);
      this.objects.set(key, buf);
      this.puts++;
      return {};
    }
    throw new Error(`unexpected command ${name}`);
  }
}

const MEDIA = join(DATA, "media");
const files: Record<string, string> = {
  "companies/c1/visits/v1/video-000.mp4": "v".repeat(5000),
  "companies/c1/visits/v1/audio-000.m4a": "a".repeat(1200),
  "companies/c1/visits/v1/signature.png": "png-bytes",
  "companies/c2/visits/v9/report.pdf": "%PDF-1.7 report",
};

let bucket: FakeBucket;
let storage: typeof import("../src/lib/storage.js");
let migrateLocalToS3: typeof import("../src/scripts/migrate-local-to-s3.js").migrateLocalToS3;

beforeAll(async () => {
  setTestEnv({ S3_BUCKET: "fp-media", S3_REGION: "auto", S3_ENDPOINT: "https://acct.r2.cloudflarestorage.com", S3_ACCESS_KEY_ID: "k", S3_SECRET_ACCESS_KEY: "s" });
  const { resetConfig } = await import("../src/config.js");
  resetConfig();
  const { initDb, migrate } = await import("../src/db.js");
  await initDb({ memory: true });
  await migrate();
  storage = await import("../src/lib/storage.js");
  ({ migrateLocalToS3 } = await import("../src/scripts/migrate-local-to-s3.js"));
});

afterAll(async () => {
  storage.setS3ClientForTests(null);
  const { closeDb } = await import("../src/db.js");
  await closeDb();
});

beforeEach(() => {
  rmSync(MEDIA, { recursive: true, force: true });
  for (const [key, body] of Object.entries(files)) {
    const p = join(MEDIA, key);
    mkdirSync(dirname(p), { recursive: true });
    writeFileSync(p, body);
  }
  bucket = new FakeBucket();
  storage.setS3ClientForTests(bucket as any);
});

describe("moving recordings from the Render disk to R2", () => {
  it("dry run lists every file and copies nothing", async () => {
    const lines: string[] = [];
    const s = await migrateLocalToS3({ dryRun: true, log: (l) => lines.push(l) });
    expect(s).toMatchObject({ scanned: 4, wouldUpload: 4, uploaded: 0, skipped: 0, failed: 0 });
    expect(bucket.objects.size).toBe(0);
    expect(lines.some((l) => l.includes("would copy companies/c1/visits/v1/video-000.mp4 (5000 bytes)"))).toBe(true);
  });

  it("copies every file under the same key with the same bytes", async () => {
    const s = await migrateLocalToS3();
    expect(s).toMatchObject({ scanned: 4, uploaded: 4, failed: 0 });
    for (const [key, body] of Object.entries(files)) expect(bucket.objects.get(key)?.toString()).toBe(body);
    expect(s.bytes).toBe(Object.values(files).reduce((n, b) => n + b.length, 0));
  });

  it("is safe to run again: files already in the bucket are skipped", async () => {
    await migrateLocalToS3();
    const putsAfterFirst = bucket.puts;
    const s = await migrateLocalToS3();
    expect(s).toMatchObject({ scanned: 4, uploaded: 0, skipped: 4, failed: 0 });
    expect(bucket.puts).toBe(putsAfterFirst);
  });

  it("re-copies a file whose bucket copy has the wrong size", async () => {
    bucket.objects.set("companies/c1/visits/v1/video-000.mp4", Buffer.from("truncated"));
    const s = await migrateLocalToS3();
    expect(s).toMatchObject({ uploaded: 4, failed: 0 });
    expect(bucket.objects.get("companies/c1/visits/v1/video-000.mp4")?.length).toBe(5000);
  });

  it("reports failures instead of stopping, and never deletes local files", async () => {
    bucket.down = true;
    const s = await migrateLocalToS3();
    expect(s.failed).toBe(4);
    expect(s.failures[0].error).toContain("ECONNREFUSED");
    bucket.down = false;
    const again = await migrateLocalToS3();
    expect(again).toMatchObject({ uploaded: 4, failed: 0 });
  });

  it("refuses to run when S3 is not configured", async () => {
    const { resetConfig } = await import("../src/config.js");
    process.env.S3_BUCKET = "";
    resetConfig();
    await expect(migrateLocalToS3()).rejects.toThrow(/S3 is not configured/);
    process.env.S3_BUCKET = "fp-media";
    resetConfig();
  });

  it("lists database keys that are on neither the disk nor the bucket", async () => {
    const { seedDemo } = await import("../src/seed.js");
    await seedDemo("correct-horse-battery");
    const { one, q } = await import("../src/db.js");
    const job = await one<any>("select id, company_id, assigned_tech_id from jobs where assigned_tech_id is not null limit 1");
    const visit = await one<any>("insert into visits (job_id, company_id, tech_id, room_name) values ($1,$2,$3,$4) returning id", [
      job.id,
      job.company_id,
      job.assigned_tech_id,
      "room-migration-test",
    ]);
    await q("insert into media (visit_id, kind, s3_key, status) values ($1,'video','companies/c1/visits/v1/video-000.mp4','uploaded')", [visit.id]);
    await q("insert into media (visit_id, kind, s3_key, status) values ($1,'audio','companies/c1/visits/v1/lost.m4a','uploaded')", [visit.id]);
    const s = await migrateLocalToS3({ checkDb: true });
    expect(s.missing).toEqual(["companies/c1/visits/v1/lost.m4a"]);
  });
});

describe("serving files during the move", () => {
  const key = "companies/c1/visits/v1/video-000.mp4";

  it("serves a file that is only on the disk from the disk", async () => {
    expect(await storage.resolveGetLocation(key)).toBe("local");
    const url = await storage.presignGet(key);
    expect(url).toContain("/media/local/companies/c1/visits/v1/video-000.mp4?exp=");
  });

  it("serves from the bucket once the file has been copied", async () => {
    await migrateLocalToS3();
    expect(await storage.resolveGetLocation(key)).toBe("s3");
  });

  it("serves from the bucket without an extra lookup when there is no local copy", async () => {
    expect(await storage.resolveGetLocation("companies/c1/visits/v1/new-after-switch.mp4")).toBe("s3");
    expect(bucket.puts).toBe(0);
  });

  it("falls back to the disk when the bucket can't be reached", async () => {
    bucket.down = true;
    expect(await storage.resolveGetLocation(key)).toBe("local");
  });

  it("still reads the local copy through the signed local route", async () => {
    const buf = await storage.readLocal(key);
    expect(buf.length).toBe(5000);
  });
});
