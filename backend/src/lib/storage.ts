import { GetObjectCommand, HeadObjectCommand, PutObjectCommand, S3Client, DeleteObjectCommand } from "@aws-sdk/client-s3";
import { getSignedUrl } from "@aws-sdk/s3-request-presigner";
import { createHmac, timingSafeEqual } from "node:crypto";
import { mkdirSync, existsSync, rmSync } from "node:fs";
import { writeFile, readFile } from "node:fs/promises";
import { dirname, join, resolve, normalize } from "node:path";
import { config, features } from "../config.js";
import { HttpError } from "../errors.js";

/**
 * Media storage. S3 (or any S3-compatible store) in production; a local folder
 * when S3 isn't configured, so a single laptop can run the whole system.
 * Either way the phone gets a short-lived signed URL and uploads directly.
 */

let client: S3Client | null = null;

/** Tests swap in a fake client; pass null to go back to the real one built from config. */
export function setS3ClientForTests(c: S3Client | null): void {
  client = c;
}

export function s3(): S3Client {
  if (!client) {
    const c = config();
    client = new S3Client({
      region: c.S3_REGION,
      endpoint: c.S3_ENDPOINT || undefined,
      forcePathStyle: Boolean(c.S3_ENDPOINT),
      credentials:
        c.S3_ACCESS_KEY_ID && c.S3_SECRET_ACCESS_KEY
          ? { accessKeyId: c.S3_ACCESS_KEY_ID, secretAccessKey: c.S3_SECRET_ACCESS_KEY }
          : undefined,
    });
  }
  return client;
}

export function storageKind(): "s3" | "local" {
  return features.s3() ? "s3" : "local";
}

export function mediaRoot(): string {
  return resolve(config().MEDIA_DIR ?? join(config().DATA_DIR, "media"));
}

/** Visit media lives under companies/<company>/visits/<visit>/ so retention and deletes are per visit. */
export function visitKey(companyId: string, visitId: string, name: string): string {
  const safe = name.replace(/[^a-zA-Z0-9._-]/g, "_");
  return `companies/${companyId}/visits/${visitId}/${safe}`;
}

function sign(method: string, key: string, exp: number): string {
  return createHmac("sha256", config().JWT_SECRET).update(`${method}\n${key}\n${exp}`).digest("hex");
}

export function verifyLocalSignature(method: string, key: string, exp: string | undefined, sig: string | undefined): void {
  const expNum = Number(exp);
  if (!sig || !Number.isFinite(expNum) || expNum < Date.now() / 1000) {
    throw new HttpError(403, "url_expired", "This link has expired.");
  }
  const expected = Buffer.from(sign(method, key, expNum));
  const got = Buffer.from(sig);
  if (expected.length !== got.length || !timingSafeEqual(expected, got)) {
    throw new HttpError(403, "bad_signature", "Invalid link.");
  }
}

export function localPath(key: string): string {
  const root = mediaRoot();
  const p = normalize(join(root, key));
  if (!p.startsWith(root)) throw new HttpError(400, "bad_key", "Invalid file path.");
  return p;
}

function localUrl(method: "PUT" | "GET", key: string, expiresSeconds: number): string {
  const exp = Math.floor(Date.now() / 1000) + expiresSeconds;
  const base = config().PUBLIC_API_URL.replace(/\/$/, "");
  return `${base}/media/local/${key.split("/").map(encodeURIComponent).join("/")}?exp=${exp}&sig=${sign(method, key, exp)}`;
}

export async function presignPut(key: string, contentType: string, expiresSeconds = 3600): Promise<string> {
  if (storageKind() === "local") return localUrl("PUT", key, expiresSeconds);
  return getSignedUrl(s3(), new PutObjectCommand({ Bucket: config().S3_BUCKET, Key: key, ContentType: contentType }), {
    expiresIn: expiresSeconds,
  });
}

/** Size of an object in the bucket, or null when it isn't there. Other S3 errors are thrown. */
export async function headS3(key: string): Promise<number | null> {
  try {
    const r = await s3().send(new HeadObjectCommand({ Bucket: config().S3_BUCKET, Key: key }));
    return Number(r.ContentLength ?? 0);
  } catch (e: any) {
    if (e?.name === "NotFound" || e?.name === "NoSuchKey" || e?.$metadata?.httpStatusCode === 404) return null;
    throw e;
  }
}

/**
 * Where a GET should be served from. After S3/R2 is turned on, files recorded earlier are still on
 * the local disk until scripts/migrate-local-to-s3.ts copies them. While a local copy exists and the
 * bucket doesn't have the object yet (or can't be reached), the local copy is served, so old
 * recordings keep playing during the move. Once the local copy is gone, no extra S3 call is made.
 */
export async function resolveGetLocation(key: string): Promise<"s3" | "local"> {
  if (storageKind() === "local") return "local";
  if (!existsSync(localPath(key))) return "s3";
  try {
    return (await headS3(key)) === null ? "local" : "s3";
  } catch {
    return "local";
  }
}

export async function presignGet(key: string, expiresSeconds = 900): Promise<string> {
  if ((await resolveGetLocation(key)) === "local") return localUrl("GET", key, expiresSeconds);
  return getSignedUrl(s3(), new GetObjectCommand({ Bucket: config().S3_BUCKET, Key: key }), { expiresIn: expiresSeconds });
}

export function contentTypeFor(key: string): string {
  const k = key.toLowerCase();
  if (k.endsWith(".mp4")) return "video/mp4";
  if (k.endsWith(".m4a")) return "audio/mp4";
  if (k.endsWith(".png")) return "image/png";
  if (k.endsWith(".jpg") || k.endsWith(".jpeg")) return "image/jpeg";
  if (k.endsWith(".pdf")) return "application/pdf";
  if (k.endsWith(".json")) return "application/json";
  if (k.endsWith(".txt")) return "text/plain";
  return "application/octet-stream";
}

export async function putObject(key: string, body: Buffer, contentType: string): Promise<void> {
  if (storageKind() === "local") {
    const p = localPath(key);
    mkdirSync(dirname(p), { recursive: true });
    await writeFile(p, body);
    return;
  }
  await s3().send(new PutObjectCommand({ Bucket: config().S3_BUCKET, Key: key, Body: body, ContentType: contentType }));
}

export async function readLocal(key: string): Promise<Buffer> {
  const p = localPath(key);
  if (!existsSync(p)) throw new HttpError(404, "not_found", "File not found.");
  return readFile(p);
}

export async function deleteObject(key: string): Promise<void> {
  if (storageKind() === "local") {
    rmSync(localPath(key), { force: true });
    return;
  }
  await s3().send(new DeleteObjectCommand({ Bucket: config().S3_BUCKET, Key: key }));
}
