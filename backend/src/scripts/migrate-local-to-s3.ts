/**
 * Copies every recording and file on the local disk (MEDIA_DIR, default DATA_DIR/media) to the
 * S3/R2 bucket under the same key, so visits recorded before S3 was turned on keep playing.
 *
 *   tsx src/scripts/migrate-local-to-s3.ts --dry-run   # list what would be copied
 *   tsx src/scripts/migrate-local-to-s3.ts             # copy and verify sizes
 *
 * Safe to run more than once: objects already in the bucket with the same size are skipped.
 * Local files are never deleted. After a clean run (failed 0), delete them by hand if you want.
 * Single PUT per file, so files must be under 5 GB (visit recordings are segmented far below that).
 */
import { createReadStream } from "node:fs";
import { readdir, stat } from "node:fs/promises";
import { join, relative, sep } from "node:path";
import { pathToFileURL } from "node:url";
import { PutObjectCommand } from "@aws-sdk/client-s3";
import { config, features } from "../config.js";
import { contentTypeFor, headS3, localPath, mediaRoot, s3 } from "../lib/storage.js";

export interface MigrateOptions {
  dryRun?: boolean;
  /** Also list keys the database points to that are on neither the disk nor the bucket. */
  checkDb?: boolean;
  log?: (line: string) => void;
}

export interface MigrateStats {
  scanned: number;
  uploaded: number;
  wouldUpload: number;
  skipped: number;
  failed: number;
  bytes: number;
  failures: { key: string; error: string }[];
  missing: string[];
}

async function* walk(dir: string): AsyncGenerator<string> {
  let entries;
  try {
    entries = await readdir(dir, { withFileTypes: true });
  } catch (e: any) {
    if (e?.code === "ENOENT") return;
    throw e;
  }
  for (const e of entries) {
    const p = join(dir, e.name);
    if (e.isDirectory()) yield* walk(p);
    else if (e.isFile()) yield p;
  }
}

export async function migrateLocalToS3(opts: MigrateOptions = {}): Promise<MigrateStats> {
  const log = opts.log ?? (() => {});
  if (!features.s3()) throw new Error("S3 is not configured. Set S3_BUCKET (and S3_ENDPOINT and keys for R2) first.");
  const root = mediaRoot();
  const stats: MigrateStats = { scanned: 0, uploaded: 0, wouldUpload: 0, skipped: 0, failed: 0, bytes: 0, failures: [], missing: [] };
  log(`${opts.dryRun ? "Dry run: " : ""}copying ${root} to bucket ${config().S3_BUCKET}`);

  for await (const file of walk(root)) {
    const key = relative(root, file).split(sep).join("/");
    stats.scanned++;
    try {
      const size = (await stat(file)).size;
      const remote = await headS3(key);
      if (remote === size) {
        stats.skipped++;
        continue;
      }
      if (opts.dryRun) {
        stats.wouldUpload++;
        stats.bytes += size;
        log(`would copy ${key} (${size} bytes${remote === null ? "" : `, bucket has ${remote}`})`);
        continue;
      }
      await s3().send(
        new PutObjectCommand({
          Bucket: config().S3_BUCKET,
          Key: key,
          Body: createReadStream(file),
          ContentLength: size,
          ContentType: contentTypeFor(key),
        }),
      );
      const after = await headS3(key);
      if (after !== size) throw new Error(`size check failed: local ${size}, bucket ${after ?? "missing"}`);
      stats.uploaded++;
      stats.bytes += size;
      log(`copied ${key} (${size} bytes)`);
    } catch (e: any) {
      stats.failed++;
      stats.failures.push({ key, error: String(e?.message ?? e) });
      log(`FAILED ${key}: ${String(e?.message ?? e)}`);
    }
  }

  if (opts.checkDb) {
    const { q } = await import("../db.js");
    const rows = await q<{ key: string }>(
      `select s3_key as key from media where status = 'uploaded' and kind <> 'cloud_recording'
       union select signature_key as key from consents where signature_key is not null`,
    );
    const { existsSync } = await import("node:fs");
    for (const { key } of rows) {
      if (!key || existsSync(localPath(key))) continue;
      if ((await headS3(key).catch(() => null)) === null) stats.missing.push(key);
    }
    for (const key of stats.missing) log(`missing everywhere: ${key}`);
  }

  log(
    `scanned ${stats.scanned}, ${opts.dryRun ? `would copy ${stats.wouldUpload}` : `copied ${stats.uploaded}`}, ` +
      `already in bucket ${stats.skipped}, failed ${stats.failed}, ${(stats.bytes / 1e9).toFixed(2)} GB` +
      (opts.checkDb ? `, missing everywhere ${stats.missing.length}` : ""),
  );
  return stats;
}

// Run from the command line (Render shell: cd /app && tsx src/scripts/migrate-local-to-s3.ts --dry-run).
if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const dryRun = process.argv.includes("--dry-run");
  const { initDb, closeDb } = await import("../db.js");
  await initDb();
  try {
    const stats = await migrateLocalToS3({ dryRun, checkDb: true, log: (l) => console.log(l) });
    process.exitCode = stats.failed ? 1 : 0;
  } finally {
    await closeDb();
  }
}
