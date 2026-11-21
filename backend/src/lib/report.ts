import { GoogleGenAI } from "@google/genai";
import PDFDocument from "pdfkit";
import { config, features } from "../config.js";
import { askClaude, extractJson } from "./claude.js";
import { tradeSkill } from "./skills.js";

export interface VisitReport {
  customerComplaint: string;
  equipment: string;
  safetyReadings: string[];
  readings: string[];
  findings: string[];
  workPerformed: string[];
  partsUsed: string[];
  observations: string[];
  safetyEvents: string[];
  supervisorInvolvement: string;
  customerDecisions: string[];
  followUp: string[];
  summary: string;
}

export interface ReportInputs {
  companyName: string;
  trade: string;
  jobTitle: string;
  complaint: string | null;
  customerName: string | null;
  address: string | null;
  techName: string;
  startedAt: string;
  endedAt: string | null;
  equipment: Record<string, unknown>;
  events: { ts: string; type: string; actor: string; data: any }[];
  consents: { kind: string; choice: string | null; signer_name: string | null; signed_at: string }[];
}

const SECTIONS: (keyof VisitReport)[] = [
  "safetyReadings",
  "readings",
  "findings",
  "workPerformed",
  "partsUsed",
  "observations",
  "safetyEvents",
  "customerDecisions",
  "followUp",
];

export const REPORT_PROMPT = `Write a field service report from the visit record below.
Use ONLY facts present in the record. Never invent a reading, part, price, model number or action.
Where something was not recorded, write "Not recorded".
Plain language a homeowner can read. Short sentences. Technical values keep their units.
Return JSON with exactly these keys:
customerComplaint (string), equipment (string), safetyReadings (string[]), readings (string[]),
findings (string[]), workPerformed (string[]), partsUsed (string[]), observations (string[]),
safetyEvents (string[]), supervisorInvolvement (string), customerDecisions (string[]),
followUp (string[]), summary (string, 2 sentences max).`;

/** Compact, chronological text of the visit for the model. Transcript lines are capped. */
export function renderVisitRecord(input: ReportInputs): string {
  const lines: string[] = [];
  lines.push(`Company: ${input.companyName}`);
  lines.push(`Trade: ${input.trade}`);
  lines.push(`Job: ${input.jobTitle}`);
  lines.push(`Customer complaint on booking: ${input.complaint ?? "Not recorded"}`);
  lines.push(`Technician: ${input.techName}`);
  lines.push(`Visit: ${input.startedAt} to ${input.endedAt ?? "in progress"}`);
  lines.push(`Equipment on file: ${JSON.stringify(input.equipment)}`);
  for (const c of input.consents) {
    lines.push(`Signature: ${c.kind} choice=${c.choice ?? "-"} by ${c.signer_name ?? "-"} at ${c.signed_at}`);
  }
  let transcriptChars = 0;
  for (const e of input.events) {
    const t = e.ts;
    switch (e.type) {
      case "transcript": {
        const text = String(e.data?.text ?? "");
        if (transcriptChars > 40_000) continue;
        transcriptChars += text.length;
        lines.push(`[${t}] ${e.data?.speaker ?? e.actor}: ${text}`);
        break;
      }
      case "reading":
        lines.push(`[${t}] READING ${e.data?.name}: ${e.data?.value} ${e.data?.unit ?? ""}`.trim());
        break;
      case "equipment":
        lines.push(`[${t}] EQUIPMENT ${JSON.stringify(e.data)}`);
        break;
      case "note":
        lines.push(`[${t}] NOTE (${e.actor}): ${e.data?.text}`);
        break;
      case "hint":
        lines.push(`[${t}] OBSERVATION: ${e.data?.reason}`);
        break;
      case "safety":
        lines.push(`[${t}] SAFETY ${e.data?.level}: ${e.data?.reason}`);
        break;
      case "estimate":
        lines.push(`[${t}] ESTIMATE DRAFT: ${JSON.stringify(e.data)}`);
        break;
      case "escalation":
        lines.push(`[${t}] SUPERVISOR: ${e.data?.status ?? ""} ${e.data?.reason ?? ""}`);
        break;
      case "answer":
        lines.push(`[${t}] AI said: ${e.data?.text}`);
        break;
      case "disputed":
        lines.push(`[${t}] Technician disputed the AI: ${e.data?.text ?? ""}`);
        break;
      default:
        break;
    }
  }
  return lines.join("\n");
}

export function emptyReport(input: ReportInputs): VisitReport {
  const readings = input.events
    .filter((e) => e.type === "reading")
    .map((e) => `${e.data?.name}: ${e.data?.value} ${e.data?.unit ?? ""}`.trim());
  const safety = input.events.filter((e) => e.type === "safety").map((e) => `${e.data?.level}: ${e.data?.reason}`);
  const notes = input.events.filter((e) => e.type === "note").map((e) => String(e.data?.text ?? ""));
  return {
    customerComplaint: input.complaint ?? "Not recorded",
    equipment: Object.keys(input.equipment).length ? JSON.stringify(input.equipment) : "Not recorded",
    safetyReadings: [],
    readings,
    findings: notes,
    workPerformed: [],
    partsUsed: [],
    observations: input.events.filter((e) => e.type === "hint").map((e) => String(e.data?.reason ?? "")),
    safetyEvents: safety,
    supervisorInvolvement: input.events.some((e) => e.type === "escalation") ? "Supervisor was called." : "None",
    customerDecisions: input.consents.map((c) => `${c.kind}: ${c.choice ?? "signed"}`),
    followUp: [],
    summary: "Report drafted from recorded readings and notes. The technician must review it before sending.",
  };
}

/** Coerces whatever the model returned into a complete, well-typed report. */
export function normalizeReport(raw: any, fallback: VisitReport): VisitReport {
  const out: any = { ...fallback };
  if (!raw || typeof raw !== "object") return out;
  for (const key of Object.keys(fallback) as (keyof VisitReport)[]) {
    const v = raw[key];
    if (SECTIONS.includes(key)) {
      if (Array.isArray(v)) out[key] = v.filter((x) => x !== null && x !== undefined).map((x) => String(x)).filter((s) => s.trim().length > 0);
    } else if (typeof v === "string" && v.trim()) {
      out[key] = v.trim();
    }
  }
  return out;
}

/**
 * Drafts the report. Claude writes it when ANTHROPIC_API_KEY is set (it reads the trade skill so
 * terms and checks are right); Gemini is the fallback; with neither, a facts-only draft.
 */
export async function generateReport(input: ReportInputs, tradeProfile?: Record<string, any> | null): Promise<VisitReport> {
  const fallback = emptyReport(input);
  const record = renderVisitRecord(input);
  if (features.claude()) {
    try {
      const text = await askClaude({
        model: config().CLAUDE_REPORT_MODEL,
        system: `${REPORT_PROMPT}\nReturn only the JSON object, no other text.\n\nTrade reference (for terms and checks only, never as facts about this visit):\n${tradeSkill(input.trade, tradeProfile)}`,
        content: `VISIT RECORD:\n${record}`,
        maxTokens: 2000,
        temperature: 0.1,
        timeoutMs: 90_000,
      });
      const parsed = extractJson(text);
      if (parsed && typeof parsed === "object") return normalizeReport(parsed, fallback);
    } catch (e) {
      console.warn("[report] Claude draft failed, trying Gemini:", (e as Error).message);
    }
  }
  if (!features.gemini()) return fallback;
  const ai = new GoogleGenAI({ apiKey: config().GOOGLE_API_KEY });
  const res = await ai.models.generateContent({
    model: config().REPORT_MODEL,
    contents: [{ role: "user", parts: [{ text: `${REPORT_PROMPT}\n\nVISIT RECORD:\n${record}` }] }],
    config: { responseMimeType: "application/json", temperature: 0.2 },
  });
  let parsed: unknown = null;
  try {
    parsed = JSON.parse(res.text ?? "");
  } catch {
    parsed = null;
  }
  return normalizeReport(parsed, fallback);
}

export async function reportPdf(report: VisitReport, meta: { company: string; customer: string | null; address: string | null; tech: string; date: string; trade: string }): Promise<Buffer> {
  const doc = new PDFDocument({ size: "LETTER", margin: 54 });
  const chunks: Buffer[] = [];
  doc.on("data", (c: Buffer) => chunks.push(c));
  const done = new Promise<Buffer>((resolve) => doc.on("end", () => resolve(Buffer.concat(chunks))));

  doc.fontSize(18).text(`${meta.company} Service Report`);
  doc.moveDown(0.3).fontSize(10).fillColor("#444")
    .text(`${meta.trade} | ${meta.date} | Technician: ${meta.tech}`)
    .text(`Customer: ${meta.customer ?? "-"}  ${meta.address ?? ""}`);
  doc.moveDown().fillColor("#000").fontSize(11).text(report.summary);

  const section = (title: string, body: string | string[]) => {
    doc.moveDown(0.8).fontSize(12).text(title, { underline: true });
    doc.moveDown(0.2).fontSize(10);
    if (Array.isArray(body)) {
      if (body.length === 0) doc.text("Not recorded");
      for (const line of body) doc.text(`- ${line}`);
    } else {
      doc.text(body || "Not recorded");
    }
  };
  section("Customer complaint", report.customerComplaint);
  section("Equipment", report.equipment);
  section("Safety readings", report.safetyReadings);
  section("Readings", report.readings);
  section("Findings", report.findings);
  section("Work performed", report.workPerformed);
  section("Parts used", report.partsUsed);
  section("Observations", report.observations);
  section("Safety events", report.safetyEvents);
  section("Supervisor involvement", report.supervisorInvolvement);
  section("Customer decisions", report.customerDecisions);
  section("Follow-up needed", report.followUp);
  doc.end();
  return done;
}
