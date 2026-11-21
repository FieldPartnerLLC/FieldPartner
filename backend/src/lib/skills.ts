import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { q } from "../db.js";

/**
 * Trade skills (shared/skills/<trade>/SKILL.md, Agent Skills format) plus the company's approved
 * lessons. This is what Claude reads before it answers as the senior expert or writes a report.
 */

const here = dirname(fileURLToPath(import.meta.url));
const SKILL_DIRS = [join(here, "..", "..", "..", "shared", "skills"), join(here, "..", "..", "shared", "skills")];
const cache = new Map<string, string | null>();

function stripFrontmatter(md: string): string {
  return md.replace(/^---\n[\s\S]*?\n---\n?/, "").trim();
}

function bullets(items: unknown): string {
  return Array.isArray(items) && items.length ? items.map((i) => `- ${typeof i === "string" ? i : (i as any)?.label ?? JSON.stringify(i)}`).join("\n") : "- none listed";
}

/** The skill text for a trade. Falls back to a skill built from the trade profile. */
export function tradeSkill(tradeId: string, profile?: Record<string, any> | null): string {
  const id = (tradeId || "general").toLowerCase().replace(/[^a-z0-9_-]/g, "");
  if (!cache.has(id)) {
    let found: string | null = null;
    for (const dir of SKILL_DIRS) {
      try {
        found = stripFrontmatter(readFileSync(join(dir, id, "SKILL.md"), "utf8"));
        break;
      } catch {
        /* next */
      }
    }
    cache.set(id, found);
  }
  const fromFile = cache.get(id);
  const p = profile ?? {};
  const rules = [
    `## Company rules for ${p.name ?? id}`,
    `Required checks before work:\n${bullets(p.preWorkReadings)}`,
    `Stop and call the expert if:\n${bullets(p.stopRules)}`,
    `Never guide the technician through:\n${bullets(p.neverGuide)}`,
    `Verify the work by:\n${bullets(p.verification)}`,
  ].join("\n\n");
  if (fromFile) return `${fromFile}\n\n${rules}`;
  return `# ${p.name ?? id} senior technician\n${p.description ?? ""}\n\n${rules}`;
}

export interface Lesson {
  id: string;
  trade: string;
  equipment_type: string | null;
  brand: string | null;
  model: string | null;
  symptom: string;
  error_code: string | null;
  cause: string | null;
  fix: string;
  parts: string | null;
  source: string;
}

const words = (s: string) =>
  new Set(
    s
      .toLowerCase()
      .split(/[^a-z0-9]+/)
      .filter((w) => w.length > 2),
  );

/**
 * The approved lessons most relevant to this situation. Brand, model and error code matches
 * count most, then shared words with the question and complaint.
 */
export async function relevantLessons(companyId: string, trade: string, hints: { brand?: string; model?: string; text?: string }, limit = 8): Promise<Lesson[]> {
  const rows = await q<Lesson>(
    `select id, trade, equipment_type, brand, model, symptom, error_code, cause, fix, parts, source
     from skill_lessons where company_id = $1 and trade = $2 and status = 'approved'
     order by created_at desc limit 500`,
    [companyId, trade],
  );
  if (!rows.length) return [];
  const brand = (hints.brand ?? "").toLowerCase().trim();
  const model = (hints.model ?? "").toLowerCase().trim();
  const textWords = words(hints.text ?? "");
  const scored = rows.map((l) => {
    let score = 0;
    if (brand && l.brand && l.brand.toLowerCase() === brand) score += 5;
    if (model && l.model && model.startsWith(l.model.toLowerCase().slice(0, 6))) score += 6;
    if (l.error_code && (hints.text ?? "").toLowerCase().includes(l.error_code.toLowerCase())) score += 8;
    const lw = words(`${l.symptom} ${l.cause ?? ""} ${l.equipment_type ?? ""} ${l.error_code ?? ""}`);
    for (const w of lw) if (textWords.has(w)) score += 1;
    return { l, score };
  });
  return scored
    .filter((s) => s.score > 0)
    .sort((a, b) => b.score - a.score)
    .slice(0, limit)
    .map((s) => s.l);
}

export function renderLessons(lessons: Lesson[]): string {
  if (!lessons.length) return "No approved company lessons match this situation yet.";
  return lessons
    .map((l, i) => {
      const unit = [l.brand, l.model, l.equipment_type].filter(Boolean).join(" ");
      return [
        `${i + 1}. ${unit || "Equipment not recorded"}${l.error_code ? `, code ${l.error_code}` : ""}`,
        `   Symptom: ${l.symptom}`,
        l.cause ? `   Cause: ${l.cause}` : null,
        `   Fix: ${l.fix}`,
        l.parts ? `   Parts: ${l.parts}` : null,
        `   (${l.source === "expert" ? "written by a company expert" : "from a past company visit, expert approved"})`,
      ]
        .filter(Boolean)
        .join("\n");
    })
    .join("\n");
}

export async function markLessonsUsed(ids: string[]): Promise<void> {
  if (!ids.length) return;
  await q("update skill_lessons set times_used = times_used + 1 where id = any($1::uuid[])", [ids]);
}

/** Prompt for the senior expert role. The answer is spoken by the live voice model, so it must be short. */
export function consultSystemPrompt(opts: { company: string; tradeName: string; skill: string; lessons: string; visitFacts: string }): string {
  return `You are the senior ${opts.tradeName} expert for ${opts.company}. A field technician is on a job right now,
wearing camera glasses. A live voice assistant relays your answer to them out loud, word for word.

How to answer:
- At most 5 short sentences, or up to 5 numbered steps. Plain spoken English, no markdown, no symbols.
- Lead with the most likely cause and the one next check that proves it.
- Use what is visible in the photo when one is attached. If the photo does not show what you need, say exactly what to point the camera at.
- Prefer the company's own approved lessons when they match. Say "on past jobs" when you use one.
- If you are not confident, say so and give the next test to narrow it down. Never invent model numbers, codes, values or prices.
- If any stop rule applies, start with "Stop." and say why, then tell them to call the expert.
- Never guide anything in the "never guide" list.

TRADE SKILL:
${opts.skill}

COMPANY LESSONS (approved):
${opts.lessons}

THIS VISIT SO FAR:
${opts.visitFacts}`;
}

export const LESSON_PROMPT = `You review a finished, technician-approved field service visit and extract reusable lessons
for the company's knowledge base. A lesson is a fact a future technician can act on: equipment, symptom,
root cause and the fix that worked.

Rules:
- Only use facts present in the record. Never invent a model, code, part or value.
- Skip routine work with nothing to learn (a filter change with no fault). Return an empty list then.
- At most 3 lessons. One per distinct fault.
- Each field is one short sentence. Leave unknown fields as null.

Return only JSON: {"lessons":[{"equipment_type":string|null,"brand":string|null,"model":string|null,
"symptom":string,"error_code":string|null,"cause":string|null,"fix":string,"parts":string|null}]}`;

export function normalizeLessons(raw: unknown): Omit<Lesson, "id" | "trade" | "source">[] {
  const list = Array.isArray((raw as any)?.lessons) ? (raw as any).lessons : Array.isArray(raw) ? raw : [];
  const str = (v: unknown, max = 400) => (typeof v === "string" && v.trim() && v.trim().toLowerCase() !== "null" ? v.trim().slice(0, max) : null);
  return list
    .map((x: any) => ({
      equipment_type: str(x?.equipment_type, 80),
      brand: str(x?.brand, 80),
      model: str(x?.model, 80),
      symptom: str(x?.symptom) ?? "",
      error_code: str(x?.error_code, 40),
      cause: str(x?.cause),
      fix: str(x?.fix) ?? "",
      parts: str(x?.parts),
    }))
    .filter((l: any) => l.symptom && l.fix)
    .slice(0, 3);
}
