/**
 * Gemini + Claude working together: the agent consults Claude as the senior expert, Claude drafts
 * the report, proposes lessons from the visit, an expert approves them, and the next consult on
 * the same equipment gets that lesson. Claude is a local fake server here.
 */
import { createServer, type Server } from "node:http";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { AGENT, auth, boot, type World } from "./helpers.js";

let w: World;
let fake: Server;
const calls: any[] = [];

function reply(body: any): string {
  const system: string = body.system ?? "";
  const user = JSON.stringify(body.messages);
  if (system.includes("extract reusable lessons")) {
    return JSON.stringify({
      lessons: [
        {
          equipment_type: "Gas furnace",
          brand: "Carrier",
          model: "59SC5A",
          symptom: "Ignites then shuts off after 5 seconds",
          error_code: "34",
          cause: "Dirty flame sensor",
          fix: "Cleaned flame sensor with fine abrasive pad and verified 3 cycles",
          parts: null,
        },
      ],
    });
  }
  if (system.includes("Write a field service report")) {
    return "```json\n" + JSON.stringify({ summary: "Cleaned the flame sensor. Furnace runs normally.", findings: ["Flame sensor dirty"], workPerformed: ["Cleaned flame sensor"] }) + "\n```";
  }
  if (system.includes("senior")) {
    const usedLesson = system.includes("Dirty flame sensor");
    return usedLesson ? "On past jobs this was the flame sensor. Clean it and run three cycles." : `Check the flame sensor first.${user.includes("image") ? " I can see the burner." : ""}`;
  }
  return "ok";
}

beforeAll(async () => {
  fake = createServer((req, res) => {
    let raw = "";
    req.on("data", (c) => (raw += c));
    req.on("end", () => {
      const body = JSON.parse(raw || "{}");
      calls.push({ headers: req.headers, body });
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ content: [{ type: "text", text: reply(body) }] }));
    });
  });
  await new Promise<void>((r) => fake.listen(0, "127.0.0.1", () => r()));
  w = await boot();
  const port = (fake.address() as any).port;
  process.env.ANTHROPIC_API_KEY = "test-key";
  process.env.ANTHROPIC_BASE_URL = `http://127.0.0.1:${port}`;
  const { resetConfig } = await import("../src/config.js");
  resetConfig();
});

afterAll(async () => {
  delete process.env.ANTHROPIC_API_KEY;
  delete process.env.ANTHROPIC_BASE_URL;
  await w.app.close();
  const { closeDb } = await import("../src/db.js");
  await closeDb();
  fake.close();
});

async function hvacVisit() {
  const me = await w.app.inject({ method: "GET", url: "/me", headers: auth(w.tokens.tech) });
  const job = await w.app.inject({
    method: "POST",
    url: "/jobs",
    headers: auth(w.tokens.expert),
    payload: { trade: "hvac", title: "Furnace short cycling", complaint: "Furnace lights then shuts off", customer: { name: "Pat" } },
  });
  const jobId = job.json().job.id;
  await w.app.inject({ method: "PATCH", url: `/jobs/${jobId}`, headers: auth(w.tokens.expert), payload: { assignedTechId: me.json().user.id } });
  const v = await w.app.inject({ method: "POST", url: `/jobs/${jobId}/visits`, headers: auth(w.tokens.tech) });
  return v.json().visit ?? v.json();
}

describe("Claude as the senior expert", () => {
  it("reports claude in the health check", async () => {
    const r = await w.app.inject({ method: "GET", url: "/healthz" });
    expect(r.json().claude).toBe(true);
  });

  it("answers a consult with the trade skill, visit facts and the photo", async () => {
    const visit = await hvacVisit();
    await w.app.inject({
      method: "POST",
      url: `/agent/visits/${visit.id}/events`,
      headers: AGENT,
      payload: { events: [{ type: "equipment", data: { type: "Gas furnace", brand: "Carrier", model: "59SC5A100" } }] },
    });
    const r = await w.app.inject({
      method: "POST",
      url: `/agent/visits/${visit.id}/consult`,
      headers: AGENT,
      payload: { question: "It lights then shuts off, code 34. What now?", imageJpegBase64: Buffer.from("fake-jpeg").toString("base64") },
    });
    expect(r.statusCode).toBe(200);
    expect(r.json().answer).toMatch(/flame sensor/i);
    const last = calls.at(-1);
    expect(last.headers["x-api-key"]).toBe("test-key");
    expect(last.body.system).toMatch(/HVAC senior technician/);
    expect(last.body.system).toMatch(/Stop and call the expert if/);
    expect(last.body.system).toMatch(/Carrier/);
    expect(last.body.messages[0].content[0].type).toBe("image");
    const detail = await w.app.inject({ method: "GET", url: `/visits/${visit.id}`, headers: auth(w.tokens.expert) });
    expect(JSON.stringify(detail.json())).toMatch(/consult/);
  });

  it("does not send the photo after the customer chose audio only", async () => {
    const visit = await hvacVisit();
    await w.app.inject({
      method: "POST",
      url: `/visits/${visit.id}/consent`,
      headers: auth(w.tokens.tech),
      payload: { kind: "service_and_recording", choice: "audio_only", signerName: "Pat", signerRole: "owner", privateAreasOk: false },
    });
    const r = await w.app.inject({
      method: "POST",
      url: `/agent/visits/${visit.id}/consult`,
      headers: AGENT,
      payload: { question: "What is this part?", imageJpegBase64: Buffer.from("x").toString("base64") },
    });
    expect(r.statusCode).toBe(200);
    expect(calls.at(-1).body.messages[0].content.some((c: any) => c.type === "image")).toBe(false);
  });

  it("drafts the report, proposes a lesson, and uses it on the next job after an expert approves it", async () => {
    const visit = await hvacVisit();
    await w.app.inject({ method: "POST", url: `/visits/${visit.id}/end`, headers: auth(w.tokens.tech) });
    let rep: any;
    for (let i = 0; i < 50; i++) {
      rep = (await w.app.inject({ method: "GET", url: `/visits/${visit.id}/report`, headers: auth(w.tokens.tech) })).json();
      if (rep.status === "draft") break;
      await new Promise((r) => setTimeout(r, 50));
    }
    expect(rep.status).toBe("draft");
    expect(rep.report.summary).toMatch(/flame sensor/i);

    const ap = await w.app.inject({ method: "POST", url: `/visits/${visit.id}/report/approve`, headers: auth(w.tokens.tech), payload: { sendSms: false, sendEmail: false } });
    expect(ap.statusCode).toBe(200);

    let pending: any[] = [];
    for (let i = 0; i < 50; i++) {
      pending = (await w.app.inject({ method: "GET", url: "/lessons?status=pending", headers: auth(w.tokens.expert) })).json().lessons;
      if (pending.length) break;
      await new Promise((r) => setTimeout(r, 50));
    }
    expect(pending.length).toBe(1);
    expect(pending[0].brand).toBe("Carrier");

    // Technicians can't review lessons.
    const denied = await w.app.inject({ method: "GET", url: "/lessons", headers: auth(w.tokens.tech) });
    expect(denied.statusCode).toBe(403);

    // Pending lessons are never used.
    const next = await hvacVisit();
    await w.app.inject({ method: "POST", url: `/agent/visits/${next.id}/events`, headers: AGENT, payload: { events: [{ type: "equipment", data: { brand: "Carrier", model: "59SC5A080" } }] } });
    let c = await w.app.inject({ method: "POST", url: `/agent/visits/${next.id}/consult`, headers: AGENT, payload: { question: "Code 34 again, lights then drops out" } });
    expect(c.json().answer).not.toMatch(/past jobs/);

    const ok = await w.app.inject({ method: "PATCH", url: `/lessons/${pending[0].id}`, headers: auth(w.tokens.expert), payload: { status: "approved", parts: "None" } });
    expect(ok.statusCode).toBe(200);
    expect(ok.json().lesson.status).toBe("approved");
    expect(ok.json().lesson.parts).toBe("None");

    c = await w.app.inject({ method: "POST", url: `/agent/visits/${next.id}/consult`, headers: AGENT, payload: { question: "Code 34 again, lights then drops out" } });
    expect(c.json().answer).toMatch(/past jobs/);
    expect(c.json().lessonsUsed).toBe(1);
  });

  it("lets an expert write a lesson directly", async () => {
    const r = await w.app.inject({
      method: "POST",
      url: "/lessons",
      headers: auth(w.tokens.expert),
      payload: { trade: "plumbing", symptom: "Toilet runs every few minutes", cause: "Flapper warped", fix: "Replace the flapper and set chain slack to half an inch" },
    });
    expect(r.statusCode).toBe(200);
    expect(r.json().lesson.status).toBe("approved");
    expect(r.json().lesson.source).toBe("expert");
  });

  it("answers null (not an error) when Claude is not configured", async () => {
    const visit = await hvacVisit();
    process.env.ANTHROPIC_API_KEY = "";
    const { resetConfig } = await import("../src/config.js");
    resetConfig();
    const r = await w.app.inject({ method: "POST", url: `/agent/visits/${visit.id}/consult`, headers: AGENT, payload: { question: "Anything" } });
    expect(r.statusCode).toBe(200);
    expect(r.json().answer).toBeNull();
    process.env.ANTHROPIC_API_KEY = "test-key";
    resetConfig();
  });
});

describe("skills helpers", () => {
  it("builds a skill for trades without a SKILL.md from the profile", async () => {
    const { tradeSkill } = await import("../src/lib/skills.js");
    const s = tradeSkill("roofing", { name: "Roofing", description: "Roofs", stopRules: ["Wet roof"], preWorkReadings: [], neverGuide: [], verification: [] });
    expect(s).toMatch(/Roofing senior technician/);
    expect(s).toMatch(/Wet roof/);
  });

  it("reads JSON out of fenced or chatty replies", async () => {
    const { extractJson } = await import("../src/lib/claude.js");
    expect(extractJson('Here you go:\n```json\n{"a":1}\n```')).toEqual({ a: 1 });
    expect(extractJson('{"lessons":[]} thanks')).toEqual({ lessons: [] });
    expect(extractJson("no json")).toBeNull();
  });
});
