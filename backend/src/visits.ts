import { randomBytes } from "node:crypto";
import { config, features } from "./config.js";
import { one, q, tx } from "./db.js";
import { HttpError, notFound } from "./errors.js";
import type { AuthUser } from "./auth.js";
import { assertCompanyAccess, canSupervise } from "./auth.js";
import * as lk from "./lib/livekit.js";
import { pagePhone, sendEmail, sendSms } from "./lib/notify.js";
import { askClaude, extractJson } from "./lib/claude.js";
import { lessonRoute } from "./lib/router.js";
import { generateReport, renderVisitRecord, reportPdf, type ReportInputs, type VisitReport } from "./lib/report.js";
import { LESSON_PROMPT, normalizeLessons } from "./lib/skills.js";
import { presignGet, putObject, visitKey } from "./lib/storage.js";

export const EVENT_TYPES = [
  "state",
  "transcript",
  "reading",
  "equipment",
  "note",
  "hint",
  "safety",
  "escalation",
  "pause",
  "resume",
  "wake",
  "question",
  "answer",
  "estimate",
  "photo",
  "disputed",
  "override",
  "consent",
  "device",
  "error",
  "consult",
] as const;
export type EventType = (typeof EVENT_TYPES)[number];

export const VISIT_STATES = [
  "pre_visit",
  "consent",
  "active",
  "assistant",
  "hint",
  "safety_alert",
  "expert_live",
  "paused",
  "no_camera",
  "wrap_up",
  "ended",
  "declined",
] as const;

/** Event types that put a visit on the supervisor's review queue. */
const FLAG_FOR: Partial<Record<EventType, (data: any) => string | null>> = {
  safety: (d) => (d?.level === "critical" || d?.level === "stop" ? "safety_stop" : null),
  disputed: () => "ai_disputed",
  override: () => "tech_override",
  escalation: (d) => (d?.status === "unanswered" ? "escalation_unanswered" : null),
};

export interface VisitRow {
  id: string;
  job_id: string;
  company_id: string;
  tech_id: string;
  room_name: string;
  state: string;
  consent_choice: string | null;
  private_areas_ok: boolean;
  started_at: string;
  ended_at: string | null;
  egress_id: string | null;
  escalation_status: string;
  escalation_reason: string | null;
  escalation_paged: any[];
  escalation_next_at: string | null;
  expert_id: string | null;
  report: VisitReport | null;
  report_status: string;
  report_token: string | null;
  flags: string[];
}

export async function loadVisit(id: string): Promise<VisitRow> {
  if (!/^[0-9a-f-]{36}$/i.test(id)) throw notFound("Visit not found.");
  const v = await one<VisitRow>("select * from visits where id = $1", [id]);
  if (!v) throw notFound("Visit not found.");
  return v;
}

/** Technicians reach only their own visits; supervisors reach their company's. */
export async function visitForUser(id: string, user: AuthUser): Promise<VisitRow> {
  const v = await loadVisit(id);
  assertCompanyAccess(user, v.company_id);
  if (user.role === "tech" && v.tech_id !== user.id) throw notFound("Visit not found.");
  return v;
}

export async function startVisit(jobId: string, user: AuthUser): Promise<{ visit: VisitRow; resumed: boolean }> {
  const job = await one<{ id: string; company_id: string; assigned_tech_id: string | null; status: string }>(
    "select id, company_id, assigned_tech_id, status from jobs where id = $1",
    [jobId],
  );
  if (!job) throw notFound("Job not found.");
  assertCompanyAccess(user, job.company_id);
  if (user.role === "tech" && job.assigned_tech_id && job.assigned_tech_id !== user.id) {
    throw new HttpError(403, "not_assigned", "This job is assigned to another technician.");
  }
  // A phone that lost signal and retries must land in the same visit, not open a second one.
  const open = await one<VisitRow>(
    "select * from visits where job_id = $1 and tech_id = $2 and ended_at is null order by started_at desc limit 1",
    [jobId, user.id],
  );
  if (open) return { visit: open, resumed: true };
  const visit = await tx(async (ex) => {
    const id = crypto.randomUUID();
    const rows = await ex.query<VisitRow>(
      "insert into visits (id, job_id, company_id, tech_id, room_name, state) values ($1,$2,$3,$4,$5,'pre_visit') returning *",
      [id, jobId, job.company_id, user.id, lk.roomNameForVisit(id)],
    );
    await ex.query("update jobs set status = 'in_progress', assigned_tech_id = coalesce(assigned_tech_id, $2) where id = $1", [
      jobId,
      user.id,
    ]);
    await ex.query("insert into visit_events (visit_id, type, actor, data) values ($1,'state','system',$2)", [
      id,
      JSON.stringify({ state: "pre_visit" }),
    ]);
    return rows.rows[0];
  });
  return { visit, resumed: false };
}

export interface IncomingEvent {
  id?: string;
  ts?: string;
  type: string;
  actor?: string;
  data?: Record<string, unknown>;
}

const ACTORS = new Set(["tech", "ai", "expert", "system", "customer"]);

/**
 * Stores a batch of events. Idempotent on the client event id, so a phone can
 * resend a whole batch after a timeout without creating duplicates.
 * Returns the ids that were newly stored.
 */
export async function recordEvents(visit: VisitRow, events: IncomingEvent[], defaultActor: string): Promise<{ stored: number; duplicates: number }> {
  if (!Array.isArray(events)) throw new HttpError(400, "bad_request", "events must be an array");
  if (events.length > 500) throw new HttpError(413, "too_many", "Send at most 500 events per request.");
  let stored = 0;
  let duplicates = 0;
  const newFlags = new Set<string>();
  let latestState: string | null = null;
  let equipment: Record<string, unknown> | null = null;
  await tx(async (ex) => {
    for (const e of events) {
      if (!e || typeof e.type !== "string" || !(EVENT_TYPES as readonly string[]).includes(e.type)) {
        throw new HttpError(400, "bad_event", `Unknown event type: ${String(e?.type)}`);
      }
      const actor = e.actor && ACTORS.has(e.actor) ? e.actor : defaultActor;
      const ts = e.ts && !Number.isNaN(Date.parse(e.ts)) ? new Date(e.ts).toISOString() : new Date().toISOString();
      const data = e.data && typeof e.data === "object" ? e.data : {};
      const clientId = typeof e.id === "string" && e.id.length <= 100 ? e.id : null;
      const res = await ex.query(
        `insert into visit_events (visit_id, ts, type, actor, data, client_event_id)
         values ($1,$2,$3,$4,$5,$6)
         on conflict (visit_id, client_event_id) where client_event_id is not null do nothing
         returning id`,
        [visit.id, ts, e.type, actor, JSON.stringify(data), clientId],
      );
      if (res.rows.length === 0) {
        duplicates++;
        continue;
      }
      stored++;
      const flag = FLAG_FOR[e.type as EventType]?.(data);
      if (flag) newFlags.add(flag);
      if (e.type === "state" && typeof (data as any).state === "string") latestState = (data as any).state;
      if (e.type === "equipment") equipment = { ...(equipment ?? {}), ...(data as object) };
    }
    if (newFlags.size) {
      await ex.query("update visits set flags = (select array(select distinct unnest(flags || $2::text[]))) where id = $1", [
        visit.id,
        [...newFlags],
      ]);
    }
    if (latestState && (VISIT_STATES as readonly string[]).includes(latestState) && !visit.ended_at) {
      await ex.query("update visits set state = $2 where id = $1", [visit.id, latestState]);
    }
    if (equipment) {
      await ex.query("update jobs set equipment = equipment || $2::jsonb where id = $1", [visit.job_id, JSON.stringify(equipment)]);
    }
    await ex.query("update visits set last_seen_at = now() where id = $1", [visit.id]);
  });
  return { stored, duplicates };
}

export async function recordConsent(
  visit: VisitRow,
  body: {
    kind: string;
    choice?: string | null;
    privateAreasOk?: boolean;
    signerName?: string;
    signerRole?: string;
    language?: string;
    lat?: number;
    lng?: number;
    textVersion?: string;
    signaturePngBase64?: string;
    payload?: Record<string, unknown>;
  },
): Promise<{ consentId: string; recording: "video_audio" | "audio_only" | "none"; cloudRecording: boolean }> {
  const kinds = ["service_and_recording", "repair", "completion", "recording_revoked"];
  if (!kinds.includes(body.kind)) throw new HttpError(400, "bad_consent", "Unknown consent kind.");
  if (body.kind === "service_and_recording") {
    const choices = ["video_audio", "audio_only", "no_recording", "declined_service"];
    if (!body.choice || !choices.includes(body.choice)) throw new HttpError(400, "bad_consent", "Choose a recording option.");
    if (body.choice !== "declined_service" && !body.signerName?.trim()) {
      throw new HttpError(400, "bad_consent", "The customer's name is required.");
    }
  }
  let signatureKey: string | null = null;
  if (body.signaturePngBase64) {
    const png = Buffer.from(body.signaturePngBase64, "base64");
    if (png.length > 2_000_000) throw new HttpError(413, "too_large", "Signature image is too large.");
    signatureKey = visitKey(visit.company_id, visit.id, `signature-${body.kind}-${Date.now()}.png`);
    try {
      await putObject(signatureKey, png, "image/png");
    } catch (e) {
      // Never lose a consent because storage hiccupped: keep the image inline instead.
      signatureKey = null;
      body.payload = { ...(body.payload ?? {}), signaturePngBase64: body.signaturePngBase64, storageError: (e as Error).message };
    }
  }
  const consentId = crypto.randomUUID();
  await q(
    `insert into consents (id, visit_id, kind, choice, private_areas_ok, signer_name, signer_role, signature_key, text_version, language, lat, lng, payload)
     values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13)`,
    [
      consentId,
      visit.id,
      body.kind,
      body.choice ?? null,
      body.privateAreasOk ?? null,
      body.signerName ?? null,
      body.signerRole ?? null,
      signatureKey,
      body.textVersion ?? null,
      body.language ?? "en",
      body.lat ?? null,
      body.lng ?? null,
      JSON.stringify(body.payload ?? {}),
    ],
  );

  let recording: "video_audio" | "audio_only" | "none" = "none";
  let cloudRecording = false;
  if (body.kind === "service_and_recording") {
    const choice = body.choice!;
    recording = choice === "video_audio" ? "video_audio" : choice === "audio_only" ? "audio_only" : "none";
    const state = choice === "declined_service" ? "declined" : recording === "video_audio" ? "active" : "no_camera";
    await q("update visits set consent_choice = $2, private_areas_ok = $3, state = $4 where id = $1", [
      visit.id,
      choice,
      Boolean(body.privateAreasOk),
      state,
    ]);
    if (choice === "declined_service") {
      await q("update jobs set status = 'declined' where id = $1", [visit.job_id]);
      await q("update visits set flags = array_append(flags, 'declined_service') where id = $1 and not ('declined_service' = any(flags))", [visit.id]);
    }
    if (recording !== "none" && !visit.egress_id) {
      try {
        const key = visitKey(visit.company_id, visit.id, recording === "audio_only" ? "cloud-recording.ogg" : "cloud-recording.mp4");
        const egressId = await lk.startCloudRecording(visit.room_name, key, recording === "audio_only");
        if (egressId) {
          cloudRecording = true;
          await q("update visits set egress_id = $2 where id = $1", [visit.id, egressId]);
          await q("insert into media (visit_id, kind, s3_key, status, meta) values ($1,'cloud_recording',$2,'pending',$3)", [
            visit.id,
            key,
            JSON.stringify({ egressId }),
          ]);
        }
      } catch (e) {
        await q("insert into visit_events (visit_id, type, actor, data) values ($1,'error','system',$2)", [
          visit.id,
          JSON.stringify({ where: "cloud_recording_start", message: (e as Error).message }),
        ]);
      }
    }
  }
  if (body.kind === "recording_revoked") {
    if (visit.egress_id) await lk.stopCloudRecording(visit.egress_id).catch(() => {});
    await q("update visits set consent_choice = 'no_recording', state = 'no_camera', egress_id = null where id = $1", [visit.id]);
  }
  await q("insert into visit_events (visit_id, type, actor, data) values ($1,'consent','customer',$2)", [
    visit.id,
    JSON.stringify({ kind: body.kind, choice: body.choice ?? null, consentId }),
  ]);
  return { consentId, recording, cloudRecording };
}

// ---------- Escalation ----------

interface ExpertRow {
  id: string;
  name: string;
  phone: string | null;
  whatsapp: string | null;
  email: string;
}

async function expertsFor(companyId: string, exclude: string[]): Promise<ExpertRow[]> {
  return q<ExpertRow>(
    `select id, name, phone, whatsapp, email from users
     where active and role in ('expert','admin','owner') and (company_id = $1 or role = 'owner')
       and not (id::text = any($2::text[]))
     order by escalation_order asc, case role when 'expert' then 0 when 'admin' then 1 else 2 end, name`,
    [companyId, exclude],
  );
}

const timers = new Map<string, NodeJS.Timeout>();

async function pageNext(visitId: string): Promise<{ paged: ExpertRow | null }> {
  const v = await loadVisit(visitId);
  if (v.escalation_status !== "requested" || v.ended_at) return { paged: null };
  const already = (v.escalation_paged ?? []).map((p: any) => p.userId);
  const [next] = await expertsFor(v.company_id, already);
  if (!next) {
    await q("update visits set escalation_status = 'unanswered', escalation_next_at = null where id = $1", [visitId]);
    await recordEvents(v, [{ type: "escalation", actor: "system", data: { status: "unanswered" } }], "system");
    return { paged: null };
  }
  const job = await one<{ title: string; address: string | null; trade: string }>("select title, address, trade from jobs where id = $1", [v.job_id]);
  const tech = await one<{ name: string }>("select name from users where id = $1", [v.tech_id]);
  const link = `${config().PORTAL_URL.replace(/\/$/, "")}/visits/${v.id}/live`;
  const body = `FieldPartner: ${tech?.name ?? "A technician"} needs help now.\n${job?.trade ?? ""} - ${job?.title ?? ""}\n${job?.address ?? ""}\nReason: ${v.escalation_reason ?? "not given"}\nJoin live: ${link}`;
  const attempts = next.phone || next.whatsapp ? await pagePhone(next.phone ?? next.whatsapp!, body, next.whatsapp) : [];
  if (!attempts.some((a) => a.ok)) {
    await sendEmail(next.email, `Help needed now: ${job?.title ?? "visit"}`, body);
  }
  const paged = [...(v.escalation_paged ?? []), { userId: next.id, name: next.name, at: new Date().toISOString() }];
  const nextAt = new Date(Date.now() + config().ESCALATION_TIMEOUT_SECONDS * 1000).toISOString();
  await q("update visits set escalation_paged = $2, escalation_next_at = $3 where id = $1", [visitId, JSON.stringify(paged), nextAt]);
  scheduleEscalationCheck(visitId);
  return { paged: next };
}

export function scheduleEscalationCheck(visitId: string): void {
  clearTimeout(timers.get(visitId));
  const t = setTimeout(() => {
    timers.delete(visitId);
    pageNext(visitId).catch((e) => console.error("[escalation] page failed", e));
  }, config().ESCALATION_TIMEOUT_SECONDS * 1000);
  t.unref?.();
  timers.set(visitId, t);
}

/** Catches escalations whose timer was lost to a server restart. */
export async function sweepEscalations(): Promise<number> {
  const due = await q<{ id: string }>(
    "select id from visits where escalation_status = 'requested' and escalation_next_at is not null and escalation_next_at < now() and ended_at is null",
  );
  for (const d of due) await pageNext(d.id).catch(() => {});
  return due.length;
}

export async function requestEscalation(visit: VisitRow, reason: string, actor: "tech" | "ai"): Promise<{ status: string; paged: string | null }> {
  if (visit.ended_at) throw new HttpError(409, "visit_ended", "This visit has ended.");
  if (visit.escalation_status === "requested" || visit.escalation_status === "joined") {
    return { status: visit.escalation_status, paged: null };
  }
  await q(
    "update visits set escalation_status = 'requested', escalation_reason = $2, escalated_at = now(), escalation_paged = '[]'::jsonb where id = $1",
    [visit.id, reason.slice(0, 500)],
  );
  await recordEvents(visit, [{ type: "escalation", actor, data: { status: "requested", reason } }], actor);
  const { paged } = await pageNext(visit.id);
  const after = await loadVisit(visit.id);
  return { status: after.escalation_status, paged: paged?.name ?? null };
}

export async function markExpertJoined(visit: VisitRow, expertUserId: string): Promise<void> {
  clearTimeout(timers.get(visit.id));
  timers.delete(visit.id);
  await q("update visits set escalation_status = 'joined', expert_id = $2, escalation_next_at = null where id = $1", [visit.id, expertUserId]);
  const expert = await one<{ name: string }>("select name from users where id = $1", [expertUserId]);
  await recordEvents(visit, [{ type: "escalation", actor: "expert", data: { status: "joined", expert: expert?.name } }], "expert");
}

export async function endEscalation(visit: VisitRow): Promise<void> {
  clearTimeout(timers.get(visit.id));
  timers.delete(visit.id);
  await q("update visits set escalation_status = 'ended', escalation_next_at = null where id = $1", [visit.id]);
  await recordEvents(visit, [{ type: "escalation", actor: "system", data: { status: "ended" } }], "system");
}

// ---------- End and report ----------

export async function endVisit(visit: VisitRow): Promise<VisitRow> {
  if (!visit.ended_at) {
    clearTimeout(timers.get(visit.id));
    timers.delete(visit.id);
    if (visit.egress_id) await lk.stopCloudRecording(visit.egress_id).catch(() => {});
    const finalState = visit.state === "declined" ? "declined" : "ended";
    await q(
      `update visits set ended_at = now(), state = $2, report_status = case when report_status = 'none' then 'generating' else report_status end,
       escalation_status = case when escalation_status in ('requested','joined') then 'ended' else escalation_status end,
       escalation_next_at = null where id = $1`,
      [visit.id, finalState],
    );
    await q("update jobs set status = case when status = 'in_progress' then 'completed' else status end where id = $1", [visit.job_id]);
    await q("insert into visit_events (visit_id, type, actor, data) values ($1,'state','system',$2)", [visit.id, JSON.stringify({ state: finalState })]);
    if (features.livekit()) {
      // Give the agent a moment to flush its last events before the room closes.
      setTimeout(() => lk.closeRoom(visit.room_name).catch(() => {}), 15_000).unref?.();
    }
  }
  return loadVisit(visit.id);
}

export async function reportInputs(visit: VisitRow): Promise<ReportInputs> {
  const row = await one<any>(
    `select j.title, j.trade, j.complaint, j.address, j.equipment, c.name as company_name, cu.name as customer_name, u.name as tech_name
     from jobs j join companies c on c.id = j.company_id left join customers cu on cu.id = j.customer_id join users u on u.id = $2
     where j.id = $1`,
    [visit.job_id, visit.tech_id],
  );
  const events = await q<any>("select ts, type, actor, data from visit_events where visit_id = $1 order by ts asc, id asc", [visit.id]);
  const consents = await q<any>("select kind, choice, signer_name, signed_at from consents where visit_id = $1 order by signed_at", [visit.id]);
  const iso = (d: any) => (d instanceof Date ? d.toISOString() : String(d));
  return {
    companyName: row?.company_name ?? "",
    trade: row?.trade ?? "",
    jobTitle: row?.title ?? "",
    complaint: row?.complaint ?? null,
    customerName: row?.customer_name ?? null,
    address: row?.address ?? null,
    techName: row?.tech_name ?? "",
    startedAt: iso(visit.started_at),
    endedAt: visit.ended_at ? iso(visit.ended_at) : null,
    equipment: row?.equipment ?? {},
    events: events.map((e) => ({ ...e, ts: iso(e.ts) })),
    consents: consents.map((c) => ({ ...c, signed_at: iso(c.signed_at) })),
  };
}

export async function buildReport(visitId: string): Promise<VisitReport> {
  const visit = await loadVisit(visitId);
  try {
    const inputs = await reportInputs(visit);
    const trade = await one<any>("select profile from trade_profiles where id = $1", [inputs.trade]);
    const report = await generateReport(inputs, trade?.profile ?? null, { companyId: visit.company_id, visitId: visit.id });
    await q("update visits set report = $2, report_status = 'draft' where id = $1 and report_status in ('generating','failed','none','draft')", [
      visitId,
      JSON.stringify(report),
    ]);
    return report;
  } catch (e) {
    await q("update visits set report_status = 'failed' where id = $1", [visitId]);
    throw e;
  }
}

export async function approveReport(
  visit: VisitRow,
  report: VisitReport,
  send: { sms: boolean; email: boolean },
): Promise<{ token: string; url: string; delivered: string[] }> {
  const token = visit.report_token ?? randomBytes(18).toString("base64url");
  const url = `${config().PUBLIC_API_URL.replace(/\/$/, "")}/r/${token}`;
  await q("update visits set report = $2, report_status = 'approved', report_token = $3 where id = $1", [visit.id, JSON.stringify(report), token]);
  const meta = await one<any>(
    `select c.name as company, cu.name as customer, cu.phone, cu.email, j.address, j.trade, u.name as tech
     from visits v join jobs j on j.id = v.job_id join companies c on c.id = v.company_id
     left join customers cu on cu.id = j.customer_id join users u on u.id = v.tech_id where v.id = $1`,
    [visit.id],
  );
  const delivered: string[] = [];
  const pdf = await reportPdf(report, {
    company: meta?.company ?? "",
    customer: meta?.customer ?? null,
    address: meta?.address ?? null,
    tech: meta?.tech ?? "",
    date: new Date(visit.started_at).toLocaleDateString("en-US"),
    trade: meta?.trade ?? "",
  });
  try {
    const key = visitKey(visit.company_id, visit.id, "service-report.pdf");
    await putObject(key, pdf, "application/pdf");
    await q("insert into media (visit_id, kind, s3_key, bytes, content_type, status) values ($1,'report_pdf',$2,$3,'application/pdf','uploaded')", [
      visit.id,
      key,
      pdf.length,
    ]);
  } catch {
    // The report is still viewable through its link even if the PDF copy failed to store.
  }
  if (send.sms && meta?.phone) {
    const r = await sendSms(meta.phone, `${meta.company}: your service report is ready: ${url}`);
    if (r.ok) delivered.push("sms");
  }
  if (send.email && meta?.email) {
    const r = await sendEmail(meta.email, `${meta.company} service report`, `Your service report is attached and online at ${url}`, [
      { filename: "service-report.pdf", content: pdf },
    ]);
    if (r.ok) delivered.push("email");
  }
  await q("update visits set report_status = $2 where id = $1", [visit.id, delivered.length ? "sent" : "approved"]);
  return { token, url, delivered };
}

export async function visitDetail(visit: VisitRow, user: AuthUser) {
  const job = await one<any>("select * from jobs where id = $1", [visit.job_id]);
  const customer = job?.customer_id ? await one<any>("select * from customers where id = $1", [job.customer_id]) : null;
  const tech = await one<any>("select id, name, phone from users where id = $1", [visit.tech_id]);
  const events = await q<any>("select id, ts, type, actor, data from visit_events where visit_id = $1 order by ts asc, id asc", [visit.id]);
  const consents = await q<any>("select * from consents where visit_id = $1 order by signed_at", [visit.id]);
  const media = await q<any>("select * from media where visit_id = $1 and status <> 'deleted' order by created_at", [visit.id]);
  const withUrls = await Promise.all(
    media.map(async (m) => ({ ...m, url: m.status === "uploaded" && canSupervise(user) ? await presignGet(m.s3_key).catch(() => null) : null })),
  );
  return { visit, job, customer, tech, events, consents, media: withUrls };
}

/**
 * After a report is approved, Claude proposes reusable lessons from the visit. They wait as
 * "pending" until an expert or admin approves them in the portal. Never throws.
 */
export async function proposeLessons(visitId: string): Promise<number> {
  if (!features.claude()) return 0;
  try {
    const visit = await loadVisit(visitId);
    const existing = await one<{ n: number }>("select count(*)::int as n from skill_lessons where source_visit_id = $1", [visitId]);
    if ((existing?.n ?? 0) > 0) return 0;
    const inputs = await reportInputs(visit);
    const report = visit.report ? JSON.stringify(visit.report) : "none";
    const text = await askClaude({
      tier: lessonRoute().tier,
      scope: { companyId: visit.company_id, visitId: visit.id },
      task: "lessons",
      system: LESSON_PROMPT,
      content: `TRADE: ${inputs.trade}\nAPPROVED REPORT:\n${report}\n\nVISIT RECORD:\n${renderVisitRecord(inputs)}`,
      maxTokens: 800,
      temperature: 0.1,
      timeoutMs: 90_000,
    });
    const lessons = normalizeLessons(extractJson(text));
    for (const l of lessons) {
      await q(
        `insert into skill_lessons (company_id, trade, equipment_type, brand, model, symptom, error_code, cause, fix, parts, source, source_visit_id, status)
         values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,'visit',$11,'pending')`,
        [visit.company_id, inputs.trade, l.equipment_type, l.brand, l.model, l.symptom, l.error_code, l.cause, l.fix, l.parts, visit.id],
      );
    }
    return lessons.length;
  } catch (e) {
    console.warn("[lessons] could not propose lessons:", (e as Error).message);
    return 0;
  }
}
