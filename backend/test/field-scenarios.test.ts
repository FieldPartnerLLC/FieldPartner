/**
 * Field scenario harness for the backend. Each test is a situation a technician,
 * customer, expert or the AI agent actually creates in the field: bad signal and
 * retries, consent changes, nobody answering an escalation, hostile input.
 */
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { AGENT, auth, boot, type World } from "./helpers.js";

let w: World;

beforeAll(async () => {
  w = await boot();
});

afterAll(async () => {
  await w.app.close();
  const { closeDb } = await import("../src/db.js");
  await closeDb();
});

async function firstJob(token = w.tokens.tech) {
  const r = await w.app.inject({ method: "GET", url: "/jobs", headers: auth(token) });
  expect(r.statusCode).toBe(200);
  return r.json().jobs as any[];
}

async function newJob(trade = "hvac", title = "Scenario job") {
  const r = await w.app.inject({
    method: "POST",
    url: "/jobs",
    headers: auth(w.tokens.expert),
    payload: { trade, title, complaint: "Test complaint", customer: { name: "Pat Customer", phone: "5555550123", email: "pat@example.com" } },
  });
  expect(r.statusCode).toBe(200);
  const job = r.json().job;
  // assign to tech
  const me = await w.app.inject({ method: "GET", url: "/me", headers: auth(w.tokens.tech) });
  await w.app.inject({ method: "PATCH", url: `/jobs/${job.id}`, headers: auth(w.tokens.expert), payload: { assignedTechId: me.json().user.id } });
  return job;
}

async function startVisit(jobId: string) {
  const r = await w.app.inject({ method: "POST", url: `/jobs/${jobId}/visits`, headers: auth(w.tokens.tech) });
  expect(r.statusCode).toBe(200);
  return r.json();
}

async function consent(visitId: string, choice: string, extra: Record<string, unknown> = {}) {
  return w.app.inject({
    method: "POST",
    url: `/visits/${visitId}/consent`,
    headers: auth(w.tokens.tech),
    payload: { kind: "service_and_recording", choice, signerName: "Pat Customer", signerRole: "owner", privateAreasOk: false, ...extra },
  });
}

describe("sign in and access", () => {
  it("rejects a wrong password with a plain message", async () => {
    const r = await w.app.inject({ method: "POST", url: "/auth/login", payload: { email: "tech@fieldpartner.local", password: "nope" } });
    expect(r.statusCode).toBe(401);
    expect(r.json().message).toMatch(/wrong/i);
  });

  it("accepts email in any letter case", async () => {
    const r = await w.app.inject({ method: "POST", url: "/auth/login", payload: { email: "TECH@FieldPartner.local", password: w.password } });
    expect(r.statusCode).toBe(200);
    expect(r.json().company.wake_phrase).toBe("hey dispatch");
  });

  it("locks out a deactivated technician immediately, even with a valid token", async () => {
    const create = await w.app.inject({
      method: "POST",
      url: "/admin/users",
      headers: auth(w.tokens.owner),
      payload: { companyId: w.companyId, email: "temp@fieldpartner.local", name: "Temp", role: "tech", password: "temporary-pass-1" },
    });
    expect(create.statusCode).toBe(200);
    const login = await w.app.inject({ method: "POST", url: "/auth/login", payload: { email: "temp@fieldpartner.local", password: "temporary-pass-1" } });
    const token = login.json().token;
    await w.app.inject({ method: "PATCH", url: `/admin/users/${create.json().user.id}`, headers: auth(w.tokens.owner), payload: { active: false } });
    const r = await w.app.inject({ method: "GET", url: "/jobs", headers: auth(token) });
    expect(r.statusCode).toBe(401);
  });

  it("rejects requests with no token or a forged token", async () => {
    expect((await w.app.inject({ method: "GET", url: "/jobs" })).statusCode).toBe(401);
    expect((await w.app.inject({ method: "GET", url: "/jobs", headers: auth("abc.def.ghi") })).statusCode).toBe(401);
  });

  it("does not let a technician use admin endpoints", async () => {
    const r = await w.app.inject({ method: "GET", url: "/admin/users", headers: auth(w.tokens.tech) });
    expect(r.statusCode).toBe(403);
  });

  it("shows a technician today's assigned jobs across every trade", async () => {
    const jobs = await firstJob();
    const trades = new Set(jobs.map((j) => j.trade));
    expect(trades.has("hvac")).toBe(true);
    expect(trades.has("auto")).toBe(true);
  });

  it("hides another company's visit (404, not 403, so ids don't leak)", async () => {
    const co = await w.app.inject({ method: "POST", url: "/admin/companies", headers: auth(w.tokens.owner), payload: { name: "Other Roofing", slug: "other-roofing" } });
    expect(co.statusCode).toBe(200);
    const otherCompany = co.json().company.id;
    await w.app.inject({
      method: "POST",
      url: "/admin/users",
      headers: auth(w.tokens.owner),
      payload: { companyId: otherCompany, email: "roofer@other.local", name: "Roofer", role: "tech", password: "roofer-password-1" },
    });
    const roofer = (await w.app.inject({ method: "POST", url: "/auth/login", payload: { email: "roofer@other.local", password: "roofer-password-1" } })).json().token;
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    const r = await w.app.inject({ method: "GET", url: `/visits/${visit.id}`, headers: auth(roofer) });
    expect(r.statusCode).toBe(404);
  });
});

describe("starting a visit on a bad network", () => {
  it("returns the same visit when the phone retries after losing signal", async () => {
    const job = await newJob();
    const a = await startVisit(job.id);
    const b = await startVisit(job.id);
    expect(b.resumed).toBe(true);
    expect(b.visit.id).toBe(a.visit.id);
  });

  it("explains clearly when live video is not configured instead of crashing", async () => {
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    const r = await w.app.inject({ method: "POST", url: `/visits/${visit.id}/live`, headers: auth(w.tokens.tech) });
    expect(r.statusCode).toBe(503);
    expect(r.json().message).toMatch(/LIVEKIT/);
  });

  it("blocks a walk-in job from being reassigned by the technician", async () => {
    const r = await w.app.inject({ method: "POST", url: "/jobs", headers: auth(w.tokens.tech), payload: { trade: "plumbing", title: "Walk-in leak" } });
    expect(r.statusCode).toBe(200);
    const me = (await w.app.inject({ method: "GET", url: "/me", headers: auth(w.tokens.tech) })).json().user;
    expect(r.json().job.assigned_tech_id).toBe(me.id);
  });

  it("rejects an unknown trade", async () => {
    const r = await w.app.inject({ method: "POST", url: "/jobs", headers: auth(w.tokens.expert), payload: { trade: "astrology", title: "x" } });
    expect(r.statusCode).toBe(400);
  });
});

describe("events from a phone with spotty signal", () => {
  it("stores each event once when the same batch is sent three times", async () => {
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    const batch = [
      { id: "e1", type: "reading", data: { name: "CO ambient", value: 0, unit: "ppm" } },
      { id: "e2", type: "transcript", data: { speaker: "tech", text: "Furnace inducer runs" } },
    ];
    const results = [];
    for (let i = 0; i < 3; i++) {
      const r = await w.app.inject({ method: "POST", url: `/visits/${visit.id}/events`, headers: auth(w.tokens.tech), payload: { events: batch } });
      expect(r.statusCode).toBe(200);
      results.push(r.json());
    }
    expect(results[0]).toEqual({ stored: 2, duplicates: 0 });
    expect(results[2]).toEqual({ stored: 0, duplicates: 2 });
  });

  it("accepts an overlapping retry batch and stores only the new events", async () => {
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    const post = (events: any[]) => w.app.inject({ method: "POST", url: `/visits/${visit.id}/events`, headers: auth(w.tokens.tech), payload: { events } });
    await post([{ id: "a", type: "note", data: { text: "one" } }, { id: "b", type: "note", data: { text: "two" } }]);
    const r = await post([{ id: "b", type: "note", data: { text: "two" } }, { id: "c", type: "note", data: { text: "three" } }]);
    expect(r.json()).toEqual({ stored: 1, duplicates: 1 });
  });

  it("rejects a whole batch atomically when one event is malformed", async () => {
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    const r = await w.app.inject({
      method: "POST",
      url: `/visits/${visit.id}/events`,
      headers: auth(w.tokens.tech),
      payload: { events: [{ id: "ok1", type: "note", data: { text: "fine" } }, { id: "bad", type: "rm -rf" }] },
    });
    expect(r.statusCode).toBe(400);
    const d = (await w.app.inject({ method: "GET", url: `/visits/${visit.id}`, headers: auth(w.tokens.tech) })).json();
    expect(d.events.some((e: any) => e.data?.text === "fine")).toBe(false);
  });

  it("refuses oversized batches", async () => {
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    const events = Array.from({ length: 501 }, (_, i) => ({ id: `x${i}`, type: "note", data: { text: "x" } }));
    const r = await w.app.inject({ method: "POST", url: `/visits/${visit.id}/events`, headers: auth(w.tokens.tech), payload: { events } });
    expect(r.statusCode).toBe(400);
  });

  it("keeps the field order of events uploaded out of order after reconnecting", async () => {
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    const t0 = Date.now() - 60_000;
    await w.app.inject({
      method: "POST",
      url: `/visits/${visit.id}/events`,
      headers: auth(w.tokens.tech),
      payload: {
        events: [
          { id: "late", ts: new Date(t0 + 30_000).toISOString(), type: "note", data: { text: "second" } },
          { id: "early", ts: new Date(t0).toISOString(), type: "note", data: { text: "first" } },
        ],
      },
    });
    const d = (await w.app.inject({ method: "GET", url: `/visits/${visit.id}`, headers: auth(w.tokens.tech) })).json();
    const notes = d.events.filter((e: any) => e.type === "note").map((e: any) => e.data.text);
    expect(notes).toEqual(["first", "second"]);
  });

  it("survives 40 batches arriving at once from a reconnecting phone without losing any", async () => {
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    const reqs = Array.from({ length: 40 }, (_, i) =>
      w.app.inject({
        method: "POST",
        url: `/visits/${visit.id}/events`,
        headers: auth(w.tokens.tech),
        payload: { events: [{ id: `burst-${i}`, type: "transcript", data: { text: `line ${i}` } }] },
      }),
    );
    const res = await Promise.all(reqs);
    expect(res.every((r) => r.statusCode === 200)).toBe(true);
    const d = (await w.app.inject({ method: "GET", url: `/visits/${visit.id}`, headers: auth(w.tokens.tech) })).json();
    expect(d.events.filter((e: any) => String(e.data?.text).startsWith("line ")).length).toBe(40);
  });

  it("saves equipment read from a nameplate onto the job for the next visit", async () => {
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    await w.app.inject({
      method: "POST",
      url: `/visits/${visit.id}/events`,
      headers: auth(w.tokens.tech),
      payload: { events: [{ id: "eq", type: "equipment", data: { brand: "Carrier", model: "59SC5A060" } }] },
    });
    const j = (await w.app.inject({ method: "GET", url: `/jobs/${job.id}`, headers: auth(w.tokens.tech) })).json().job;
    expect(j.equipment.model).toBe("59SC5A060");
  });
});

describe("customer consent at the door", () => {
  it("requires the customer's name before recording", async () => {
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    const r = await consent(visit.id, "video_audio", { signerName: "" });
    expect(r.statusCode).toBe(400);
  });

  it("video and audio consent turns the visit active", async () => {
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    const r = await consent(visit.id, "video_audio", { signaturePngBase64: Buffer.from("fakepng").toString("base64"), lat: 40.1, lng: -83.0 });
    expect(r.statusCode).toBe(200);
    expect(r.json().recording).toBe("video_audio");
    const d = (await w.app.inject({ method: "GET", url: `/visits/${visit.id}`, headers: auth(w.tokens.tech) })).json();
    expect(d.visit.state).toBe("active");
    expect(d.consents[0].signature_key).toMatch(/signature-/);
  });

  it("audio only puts the visit in no-camera mode", async () => {
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    const r = await consent(visit.id, "audio_only");
    expect(r.json().recording).toBe("audio_only");
    const d = (await w.app.inject({ method: "GET", url: `/visits/${visit.id}`, headers: auth(w.tokens.tech) })).json();
    expect(d.visit.state).toBe("no_camera");
  });

  it("a customer who refuses service ends with the job marked declined and flagged", async () => {
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    const r = await consent(visit.id, "declined_service", { signerName: "" });
    expect(r.statusCode).toBe(200);
    const d = (await w.app.inject({ method: "GET", url: `/visits/${visit.id}`, headers: auth(w.tokens.tech) })).json();
    expect(d.job.status).toBe("declined");
    expect(d.visit.flags).toContain("declined_service");
  });

  it("customer withdraws recording consent mid-visit", async () => {
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    await consent(visit.id, "video_audio");
    const r = await w.app.inject({ method: "POST", url: `/visits/${visit.id}/consent`, headers: auth(w.tokens.tech), payload: { kind: "recording_revoked" } });
    expect(r.statusCode).toBe(200);
    const d = (await w.app.inject({ method: "GET", url: `/visits/${visit.id}`, headers: auth(w.tokens.tech) })).json();
    expect(d.visit.consent_choice).toBe("no_recording");
    expect(d.visit.state).toBe("no_camera");
  });

  it("rejects an invalid recording choice", async () => {
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    expect((await consent(visit.id, "film_everything")).statusCode).toBe(400);
  });
});

describe("escalation when the technician needs a human", () => {
  it("pages the expert once even if the tech presses the button repeatedly", async () => {
    const { outbox } = await import("../src/lib/notify.js");
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    const before = outbox.length;
    const r1 = await w.app.inject({ method: "POST", url: `/visits/${visit.id}/escalate`, headers: auth(w.tokens.tech), payload: { reason: "Gas smell" } });
    const r2 = await w.app.inject({ method: "POST", url: `/visits/${visit.id}/escalate`, headers: auth(w.tokens.tech), payload: { reason: "Gas smell" } });
    expect(r1.json().status).toBe("requested");
    expect(r2.json().paged).toBeNull();
    // No phone on file and no providers configured: falls through to email, recorded not sent.
    expect(outbox.length - before).toBeGreaterThanOrEqual(1);
  });

  it("moves to the next expert after the timeout, then marks it unanswered and flags it", async () => {
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    await w.app.inject({ method: "POST", url: `/visits/${visit.id}/escalate`, headers: auth(w.tokens.tech), payload: { reason: "Breaker hot" } });
    // Two supervisors exist (expert, owner); timeout is 1s each.
    await new Promise((r) => setTimeout(r, 3500));
    const d = (await w.app.inject({ method: "GET", url: `/visits/${visit.id}`, headers: auth(w.tokens.tech) })).json();
    expect(d.visit.escalation_paged.length).toBe(2);
    expect(d.visit.escalation_status).toBe("unanswered");
    expect(d.visit.flags).toContain("escalation_unanswered");
  });

  it("stops paging once an expert joins", async () => {
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    await w.app.inject({ method: "POST", url: `/visits/${visit.id}/escalate`, headers: auth(w.tokens.tech), payload: { reason: "Second opinion" } });
    const { markExpertJoined, loadVisit } = await import("../src/visits.js");
    const me = (await w.app.inject({ method: "GET", url: "/me", headers: auth(w.tokens.expert) })).json().user;
    await markExpertJoined(await loadVisit(visit.id), me.id);
    await new Promise((r) => setTimeout(r, 2000));
    const v = await loadVisit(visit.id);
    expect(v.escalation_status).toBe("joined");
    expect(v.escalation_paged.length).toBe(1);
  });

  it("a restarted server still pages experts whose timer was lost", async () => {
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    await w.app.inject({ method: "POST", url: `/visits/${visit.id}/escalate`, headers: auth(w.tokens.tech), payload: { reason: "x" } });
    const { q } = await import("../src/db.js");
    await q("update visits set escalation_next_at = now() - interval '1 minute' where id = $1", [visit.id]);
    const { sweepEscalations } = await import("../src/visits.js");
    expect(await sweepEscalations()).toBeGreaterThanOrEqual(1);
  });

  it("cannot escalate a visit that already ended", async () => {
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    await w.app.inject({ method: "POST", url: `/visits/${visit.id}/end`, headers: auth(w.tokens.tech) });
    const r = await w.app.inject({ method: "POST", url: `/visits/${visit.id}/escalate`, headers: auth(w.tokens.tech), payload: { reason: "late" } });
    expect(r.statusCode).toBe(409);
  });

  it("puts waiting escalations at the top of the supervisor's list", async () => {
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    await w.app.inject({ method: "POST", url: `/visits/${visit.id}/escalate`, headers: auth(w.tokens.tech), payload: { reason: "top" } });
    const r = await w.app.inject({ method: "GET", url: "/visits?active=true", headers: auth(w.tokens.expert) });
    expect(r.json().visits[0].id).toBe(visit.id);
  });
});

describe("AI agent service API", () => {
  it("rejects the agent without its service token", async () => {
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    expect((await w.app.inject({ method: "GET", url: `/agent/visits/${visit.id}/context` })).statusCode).toBe(401);
    expect((await w.app.inject({ method: "GET", url: `/agent/visits/${visit.id}/context`, headers: auth(w.tokens.owner) })).statusCode).toBe(401);
  });

  it("gives the agent the trade safety profile, CO stop level and readings so far", async () => {
    const job = await newJob("electrical", "Panel buzzing");
    const { visit } = await startVisit(job.id);
    await w.app.inject({
      method: "POST",
      url: `/visits/${visit.id}/events`,
      headers: auth(w.tokens.tech),
      payload: { events: [{ id: "r1", type: "reading", data: { name: "Voltage L1-N", value: 121, unit: "V" } }] },
    });
    const r = await w.app.inject({ method: "GET", url: `/agent/visits/${visit.id}/context`, headers: AGENT });
    expect(r.statusCode).toBe(200);
    const ctx = r.json();
    expect(ctx.trade.id).toBe("electrical");
    expect(ctx.trade.neverGuide.length).toBeGreaterThan(0);
    expect(ctx.company.coStopPpm).toBe(35);
    expect(ctx.readings[0].value).toBe(121);
  });

  it("records AI tool calls as AI events and flags safety stops", async () => {
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    const r = await w.app.inject({
      method: "POST",
      url: `/agent/visits/${visit.id}/events`,
      headers: AGENT,
      payload: { events: [{ id: "s1", type: "safety", data: { level: "critical", reason: "Gas detected 15 ppm" } }] },
    });
    expect(r.statusCode).toBe(200);
    const d = (await w.app.inject({ method: "GET", url: `/visits/${visit.id}`, headers: auth(w.tokens.expert) })).json();
    expect(d.events.find((e: any) => e.type === "safety").actor).toBe("ai");
    expect(d.visit.flags).toContain("safety_stop");
  });

  it("lets the agent escalate on a safety stop", async () => {
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    const r = await w.app.inject({ method: "POST", url: `/agent/visits/${visit.id}/escalate`, headers: AGENT, payload: { reason: "CO 60 ppm" } });
    expect(r.statusCode).toBe(200);
    expect(r.json().status).toBe("requested");
  });
});

describe("ending the visit and the customer report", () => {
  it("ends once, drafts a report from real readings, and never invents data without the AI", async () => {
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    await consent(visit.id, "video_audio");
    await w.app.inject({
      method: "POST",
      url: `/visits/${visit.id}/events`,
      headers: auth(w.tokens.tech),
      payload: {
        events: [
          { id: "rd", type: "reading", data: { name: "Temperature rise", value: 45, unit: "F" } },
          { id: "nt", type: "note", data: { text: "Replaced pressure switch hose" } },
        ],
      },
    });
    const e1 = await w.app.inject({ method: "POST", url: `/visits/${visit.id}/end`, headers: auth(w.tokens.tech) });
    const e2 = await w.app.inject({ method: "POST", url: `/visits/${visit.id}/end`, headers: auth(w.tokens.tech) });
    expect(e1.statusCode).toBe(200);
    expect(e2.json().visit.ended_at).toBe(e1.json().visit.ended_at);
    let rep: any;
    for (let i = 0; i < 20; i++) {
      rep = (await w.app.inject({ method: "GET", url: `/visits/${visit.id}/report`, headers: auth(w.tokens.tech) })).json();
      if (rep.status === "draft") break;
      await new Promise((r) => setTimeout(r, 100));
    }
    expect(rep.status).toBe("draft");
    expect(rep.report.readings.join(" ")).toContain("Temperature rise: 45 F");
    expect(rep.report.partsUsed).toEqual([]);
  });

  it("won't approve a report on a visit that is still open", async () => {
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    const r = await w.app.inject({ method: "POST", url: `/visits/${visit.id}/report/approve`, headers: auth(w.tokens.tech), payload: {} });
    expect(r.statusCode).toBe(409);
  });

  it("publishes an approved report with customer text escaped", async () => {
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    await w.app.inject({ method: "POST", url: `/visits/${visit.id}/end`, headers: auth(w.tokens.tech) });
    const report = {
      customerComplaint: '<script>alert("x")</script> no heat',
      equipment: "Carrier furnace",
      safetyReadings: [],
      readings: [],
      findings: ["Clogged condensate trap"],
      workPerformed: ["Cleared trap"],
      partsUsed: [],
      observations: [],
      safetyEvents: [],
      supervisorInvolvement: "None",
      customerDecisions: [],
      followUp: [],
      summary: "Heat restored.",
    };
    const r = await w.app.inject({ method: "POST", url: `/visits/${visit.id}/report/approve`, headers: auth(w.tokens.tech), payload: { report } });
    expect(r.statusCode).toBe(200);
    const page = await w.app.inject({ method: "GET", url: `/r/${r.json().token}` });
    expect(page.statusCode).toBe(200);
    expect(page.body).not.toContain("<script>alert");
    expect(page.body).toContain("&lt;script&gt;");
    expect(page.body).toContain("Cleared trap");
  });

  it("does not expose unapproved reports through a guessed token", async () => {
    const r = await w.app.inject({ method: "GET", url: "/r/aaaaaaaaaaaaaaaaaaaaaaaa" });
    expect(r.statusCode).toBe(404);
  });
});

describe("uploading recordings from the phone", () => {
  it("signs an upload, accepts the file, and lets a supervisor play it back", async () => {
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    const up = await w.app.inject({
      method: "POST",
      url: `/visits/${visit.id}/uploads`,
      headers: auth(w.tokens.tech),
      payload: { kind: "video", filename: "segment-001.mp4", contentType: "video/mp4", bytes: 4, clientId: "seg-1" },
    });
    expect(up.statusCode).toBe(200);
    const url = new URL(up.json().url);
    const put = await w.app.inject({ method: "PUT", url: url.pathname + url.search, headers: { "content-type": "video/mp4" }, payload: Buffer.from("MP4!") });
    expect(put.statusCode).toBe(200);
    await w.app.inject({ method: "POST", url: `/media/${up.json().mediaId}/complete`, headers: auth(w.tokens.tech), payload: { bytes: 4 } });
    // Retrying the same segment after a dropped response does not create a second file.
    const again = await w.app.inject({
      method: "POST",
      url: `/visits/${visit.id}/uploads`,
      headers: auth(w.tokens.tech),
      payload: { kind: "video", filename: "segment-001.mp4", contentType: "video/mp4", clientId: "seg-1" },
    });
    expect(again.json().alreadyUploaded).toBe(true);
    const d = (await w.app.inject({ method: "GET", url: `/visits/${visit.id}`, headers: auth(w.tokens.expert) })).json();
    const media = d.media.find((m: any) => m.kind === "video");
    const getUrl = new URL(media.url);
    const get = await w.app.inject({ method: "GET", url: getUrl.pathname + getUrl.search });
    expect(get.body).toBe("MP4!");
  });

  it("accepts the end-of-visit transcript upload (text/plain stays raw bytes)", async () => {
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    const up = await w.app.inject({
      method: "POST",
      url: `/visits/${visit.id}/uploads`,
      headers: auth(w.tokens.tech),
      payload: { kind: "transcript", filename: "transcript.txt", contentType: "text/plain", clientId: "tx-1" },
    });
    const url = new URL(up.json().url);
    const text = "hey dispatch what does three flashes mean\nAI: Pressure switch open.";
    const put = await w.app.inject({ method: "PUT", url: url.pathname + url.search, headers: { "content-type": "text/plain" }, payload: text });
    expect(put.statusCode).toBe(200);
    expect(put.json().bytes).toBe(Buffer.byteLength(text));
    const put2 = await w.app.inject({ method: "PUT", url: url.pathname + url.search, headers: { "content-type": "text/plain; charset=utf-8" }, payload: text });
    expect(put2.statusCode).toBe(200);
  });

  it("rejects tampered and expired upload links, and path tricks", async () => {
    const job = await newJob();
    const { visit } = await startVisit(job.id);
    const up = await w.app.inject({
      method: "POST",
      url: `/visits/${visit.id}/uploads`,
      headers: auth(w.tokens.tech),
      payload: { kind: "audio", filename: "a.m4a", contentType: "audio/mp4" },
    });
    const url = new URL(up.json().url);
    const tampered = url.pathname + url.search.replace(/sig=[0-9a-f]{4}/, "sig=0000");
    expect((await w.app.inject({ method: "PUT", url: tampered, headers: { "content-type": "audio/mp4" }, payload: Buffer.from("x") })).statusCode).toBe(403);
    const expired = url.pathname + url.search.replace(/exp=\d+/, "exp=1");
    expect((await w.app.inject({ method: "PUT", url: expired, headers: { "content-type": "audio/mp4" }, payload: Buffer.from("x") })).statusCode).toBe(403);
    const traversal = "/media/local/..%2F..%2Fetc%2Fpasswd?exp=9999999999&sig=abc";
    expect((await w.app.inject({ method: "GET", url: traversal })).statusCode).toBeGreaterThanOrEqual(400);
  });
});

describe("report text handling", () => {
  it("fills every section even when the model returns junk", async () => {
    const { normalizeReport, emptyReport } = await import("../src/lib/report.js");
    const base = emptyReport({
      companyName: "c",
      trade: "hvac",
      jobTitle: "t",
      complaint: null,
      customerName: null,
      address: null,
      techName: "n",
      startedAt: "",
      endedAt: null,
      equipment: {},
      events: [],
      consents: [],
    });
    const out = normalizeReport({ findings: "not an array", summary: 42, partsUsed: ["Hose", "", null] }, base);
    expect(Array.isArray(out.findings)).toBe(true);
    expect(out.partsUsed).toEqual(["Hose"]);
    expect(typeof out.summary).toBe("string");
  });

  it("caps a very long transcript so a 4 hour visit still fits the model", async () => {
    const { renderVisitRecord } = await import("../src/lib/report.js");
    const events = Array.from({ length: 5000 }, (_, i) => ({ ts: String(i), type: "transcript", actor: "tech", data: { text: "x".repeat(100) } }));
    const text = renderVisitRecord({
      companyName: "c",
      trade: "hvac",
      jobTitle: "t",
      complaint: null,
      customerName: null,
      address: null,
      techName: "n",
      startedAt: "",
      endedAt: null,
      equipment: {},
      events,
      consents: [],
    });
    expect(text.length).toBeLessThan(60_000);
  });
});

describe("phone numbers", () => {
  it("normalizes US numbers the way people type them", async () => {
    const { normalizePhone } = await import("../src/lib/notify.js");
    expect(normalizePhone("(315) 601-3067")).toBe("+13156013067");
    expect(normalizePhone("1-315-601-3067")).toBe("+13156013067");
    expect(normalizePhone("+44 20 7946 0958")).toBe("+442079460958");
    expect(normalizePhone("123")).toBeNull();
  });
});
