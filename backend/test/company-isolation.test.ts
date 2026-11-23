/**
 * Two companies on one server. Company A (the demo company) runs a full visit with a customer,
 * upload, report, lesson and invite. Company B (signed up from the portal) has an admin, an expert
 * and a technician. Every route that takes a job, visit, media, report, user, invite, company or
 * lesson id is called by every B account against A's ids: B must get 403 or 404 and never see any
 * of A's data. Then the server is closed to new signups and invites must still work.
 *
 * Covers field checklist J93 ("a second company can't see the first company's visits") and the
 * SIGNUP_ENABLED=false switch, so signup can be locked without a manual two-company test.
 */
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { AGENT, auth, boot, type World } from "./helpers.js";

let w: World;

// Every piece of A's data carries this marker, so any leak into a B response is caught by a text scan.
const SECRET = "ALPHASECRET";

const A: {
  jobId: string;
  visitId: string;
  customerId: string;
  mediaId: string;
  techId: string;
  expertId: string;
  inviteId: string;
  lessonId: string;
  reportToken: string;
} = {} as any;

const B: { companyId: string; admin: string; expert: string; tech: string; techId: string; jobId: string; expertPhone: string } = {} as any;

const inject = (method: string, url: string, token?: string, payload?: unknown) =>
  w.app.inject({ method: method as any, url, headers: token ? auth(token) : {}, payload: payload as any });

beforeAll(async () => {
  w = await boot();

  // ---- Company A: the demo company runs a whole visit. ----
  const me = (t: string) => inject("GET", "/me", t).then((r) => r.json().user);
  A.techId = (await me(w.tokens.tech)).id;
  A.expertId = (await me(w.tokens.expert)).id;

  const job = await inject("POST", "/jobs", w.tokens.expert, {
    trade: "hvac",
    title: `${SECRET} furnace job`,
    complaint: `${SECRET} complaint`,
    address: `${SECRET} 1 Private Lane`,
    assignedTechId: A.techId,
    customer: { name: `${SECRET} Customer`, phone: "5555550199", email: "alpha-secret@example.com" },
  });
  expect(job.statusCode).toBe(200);
  A.jobId = job.json().job.id;
  A.customerId = job.json().job.customer_id;
  expect(A.customerId).toBeTruthy();

  const visit = await inject("POST", `/jobs/${A.jobId}/visits`, w.tokens.tech);
  expect(visit.statusCode).toBe(200);
  A.visitId = visit.json().visit.id;

  expect(
    (
      await inject("POST", `/visits/${A.visitId}/consent`, w.tokens.tech, {
        kind: "service_and_recording",
        choice: "video_audio",
        signerName: `${SECRET} Signer`,
        signerRole: "owner",
        privateAreasOk: false,
      })
    ).statusCode,
  ).toBe(200);
  expect(
    (
      await inject("POST", `/visits/${A.visitId}/events`, w.tokens.tech, {
        events: [
          { type: "reading", data: { name: "CO", value: 2, unit: "ppm", note: SECRET } },
          { type: "note", data: { text: `${SECRET} note` } },
        ],
      })
    ).statusCode,
  ).toBe(200);

  const up = await inject("POST", `/visits/${A.visitId}/uploads`, w.tokens.tech, { kind: "audio", filename: "a.m4a", contentType: "audio/mp4", bytes: 10 });
  expect(up.statusCode).toBe(200);
  A.mediaId = up.json().mediaId;

  expect((await inject("POST", `/visits/${A.visitId}/end`, w.tokens.tech)).statusCode).toBe(200);
  const report = { summary: `${SECRET} summary`, findings: [`${SECRET} finding`], workPerformed: [], partsUsed: [], followUp: [] };
  expect((await inject("PUT", `/visits/${A.visitId}/report`, w.tokens.tech, { report })).statusCode).toBe(200);
  const approved = await inject("POST", `/visits/${A.visitId}/report/approve`, w.tokens.tech, { sendSms: false, sendEmail: false });
  expect(approved.statusCode).toBe(200);
  A.reportToken = (await inject("GET", `/visits/${A.visitId}/report`, w.tokens.tech)).json().token;
  expect(A.reportToken).toBeTruthy();

  const lesson = await inject("POST", "/lessons", w.tokens.expert, {
    trade: "hvac",
    symptom: `${SECRET} lesson symptom`,
    fix: `${SECRET} lesson fix`,
    brand: "Carrier",
  });
  expect(lesson.statusCode).toBe(200);
  A.lessonId = lesson.json().lesson.id;

  const inv = await inject("POST", "/admin/invites", w.tokens.owner, {
    companyId: w.companyId,
    name: `${SECRET} Invitee`,
    email: "alpha-invitee@example.com",
    send: false,
  });
  expect(inv.statusCode).toBe(200);
  A.inviteId = inv.json().invite.id;

  // A second A visit stays open, so the live board and summary have something to leak.
  const job2 = await inject("POST", "/jobs", w.tokens.expert, { trade: "electrical", title: `${SECRET} live job`, assignedTechId: A.techId });
  expect((await inject("POST", `/jobs/${job2.json().job.id}/visits`, w.tokens.tech)).statusCode).toBe(200);

  // ---- Company B: signs up from the portal, invites an expert and a technician. ----
  const signup = await inject("POST", "/auth/signup", undefined, {
    companyName: "Bravo Plumbing",
    name: "Bea Admin",
    email: "bea@bravo.test",
    password: "bravo-admin-2026",
  });
  expect(signup.statusCode).toBe(200);
  B.admin = signup.json().token;
  B.companyId = signup.json().company.id;
  expect(B.companyId).not.toBe(w.companyId);

  const join = async (name: string, email: string, role: string, phone?: string) => {
    const i = await inject("POST", "/admin/invites", B.admin, { name, email, role, phone, send: false });
    expect(i.statusCode).toBe(200);
    const a = await inject("POST", "/auth/accept-invite", undefined, { code: i.json().invite.code, password: `${role}-bravo-2026` });
    expect(a.statusCode).toBe(200);
    return a.json();
  };
  B.expertPhone = "+16145550142";
  B.expert = (await join("Bo Expert", "bo@bravo.test", "expert", B.expertPhone)).token;
  const t = await join("Ty Tech", "ty@bravo.test", "tech");
  B.tech = t.token;
  B.techId = t.user.id;

  const bJob = await inject("POST", "/jobs", B.admin, { trade: "plumbing", title: "Bravo job", customer: { name: "Bravo Customer" } });
  expect(bJob.statusCode).toBe(200);
  B.jobId = bJob.json().job.id;

  const bLesson = await inject("POST", "/lessons", B.expert, { trade: "hvac", symptom: "BRAVOLESSON symptom", fix: "BRAVOLESSON fix", brand: "Carrier" });
  expect(bLesson.statusCode).toBe(200);
});

afterAll(async () => {
  await w.app.close();
  const { closeDb } = await import("../src/db.js");
  await closeDb();
});

function bAccounts(): [string, string][] {
  return [
    ["admin", B.admin],
    ["expert", B.expert],
    ["tech", B.tech],
  ];
}

describe("company B cannot reach company A by id", () => {
  const attempts = (): [string, string, unknown?][] => [
    ["GET", `/jobs/${A.jobId}`],
    ["PATCH", `/jobs/${A.jobId}`, { title: "hijacked", status: "cancelled" }],
    ["POST", `/jobs/${A.jobId}/visits`],
    ["GET", `/visits/${A.visitId}`],
    ["POST", `/visits/${A.visitId}/live`],
    ["POST", `/visits/${A.visitId}/consent`, { kind: "recording_revoked" }],
    ["POST", `/visits/${A.visitId}/events`, { events: [{ type: "note", data: { text: "x" } }] }],
    ["POST", `/visits/${A.visitId}/escalate`, { reason: "x" }],
    ["POST", `/visits/${A.visitId}/escalation/end`],
    ["POST", `/visits/${A.visitId}/uploads`, { kind: "audio", filename: "b.m4a", contentType: "audio/mp4" }],
    ["POST", `/media/${A.mediaId}/complete`, { bytes: 1 }],
    ["POST", `/visits/${A.visitId}/end`],
    ["GET", `/visits/${A.visitId}/report`],
    ["POST", `/visits/${A.visitId}/report/regenerate`],
    ["PUT", `/visits/${A.visitId}/report`, { report: { summary: "overwritten" } }],
    ["POST", `/visits/${A.visitId}/report/approve`, { sendSms: false, sendEmail: false }],
    ["POST", `/visits/${A.visitId}/expert-ticket`],
    ["POST", `/visits/${A.visitId}/snapshots`, { jpegBase64: "AAAA" }],
    ["PATCH", `/admin/users/${A.techId}`, { active: false }],
    ["PATCH", `/admin/users/${A.expertId}`, { name: "renamed" }],
    ["PATCH", `/admin/companies/${w.companyId}`, { wakePhrase: "hijacked" }],
    ["DELETE", `/admin/invites/${A.inviteId}`],
    ["PATCH", `/lessons/${A.lessonId}`, { status: "rejected" }],
  ];

  for (const [role] of [["admin"], ["expert"], ["tech"]] as const) {
    it(`B's ${role} gets 403 or 404 on every A id and sees none of A's data`, async () => {
      const token = bAccounts().find(([r]) => r === role)![1];
      for (const [method, url, payload] of attempts()) {
        const r = await inject(method, url, token, payload);
        expect([403, 404], `${role} ${method} ${url} -> ${r.statusCode} ${r.body.slice(0, 200)}`).toContain(r.statusCode);
        expect(r.body, `${role} ${method} ${url} leaked A data`).not.toContain(SECRET);
      }
    });
  }

  it("A's data is unchanged after all of B's attempts", async () => {
    const job = (await inject("GET", `/jobs/${A.jobId}`, w.tokens.expert)).json().job;
    expect(job.title).toBe(`${SECRET} furnace job`);
    expect(job.status).not.toBe("cancelled");
    const detail = (await inject("GET", `/visits/${A.visitId}`, w.tokens.expert)).json();
    expect(detail.visit.report.summary).toBe(`${SECRET} summary`);
    expect(detail.visit.escalation_status).not.toBe("requested");
    expect(detail.events.some((e: any) => e.data?.text === "x")).toBe(false);
    const users = (await inject("GET", "/admin/users", w.tokens.owner)).json().users;
    expect(users.find((u: any) => u.id === A.techId).active).toBe(true);
    expect(users.find((u: any) => u.id === A.expertId).name).toBe("Mitch (Expert)");
    const company = (await inject("GET", "/admin/companies", w.tokens.owner)).json().companies.find((c: any) => c.id === w.companyId);
    expect(company.wake_phrase).toBe("hey dispatch");
    const invites = (await inject("GET", "/admin/invites", w.tokens.owner)).json().invites;
    expect(invites.find((i: any) => i.id === A.inviteId).revoked_at).toBeNull();
    const lessons = (await inject("GET", "/lessons?status=approved", w.tokens.expert)).json().lessons;
    expect(lessons.find((l: any) => l.id === A.lessonId).status).toBe("approved");
  });
});

describe("company B's lists show only company B", () => {
  it("jobs, visits, live board and summary", async () => {
    for (const [role, token] of bAccounts()) {
      const jobs = await inject("GET", "/jobs", token);
      expect(jobs.statusCode).toBe(200);
      expect(jobs.json().jobs.every((j: any) => j.company_id === B.companyId), role).toBe(true);
      expect(jobs.body).not.toContain(SECRET);
      const company = await inject("GET", `/jobs?scope=company&companyId=${w.companyId}`, token);
      expect(company.body, `${role} jobs?companyId=A`).not.toContain(SECRET);

      for (const qs of ["", "?active=true", "?flagged=true", `?techId=${A.techId}`]) {
        const v = await inject("GET", `/visits${qs}`, token);
        expect(v.statusCode).toBe(200);
        expect(v.body, `${role} /visits${qs}`).not.toContain(SECRET);
        expect(v.json().visits.length, `${role} /visits${qs}`).toBe(0);
      }
    }
    const summary = (await inject("GET", "/supervisor/summary", B.expert)).json().summary;
    expect(Number(summary.active)).toBe(0);
    expect(Number(summary.visits_24h)).toBe(0);
    // A really has an open visit, so the zero above is scoping, not an empty server.
    expect(Number((await inject("GET", "/supervisor/summary", w.tokens.expert)).json().summary.active)).toBeGreaterThan(0);
  });

  it("team, companies, invites and lessons", async () => {
    const users = (await inject("GET", "/admin/users", B.admin)).json().users;
    expect(users.map((u: any) => u.email).sort()).toEqual(["bea@bravo.test", "bo@bravo.test", "ty@bravo.test"]);
    const companies = (await inject("GET", "/admin/companies", B.admin)).json().companies;
    expect(companies.map((c: any) => c.id)).toEqual([B.companyId]);
    const invites = await inject("GET", "/admin/invites", B.admin);
    expect(invites.body).not.toContain(SECRET);
    expect(invites.json().invites.every((i: any) => i.company_id === B.companyId)).toBe(true);
    for (const status of ["pending", "approved", "rejected"]) {
      const l = await inject("GET", `/lessons?status=${status}`, B.expert);
      expect(l.body).not.toContain(SECRET);
    }
    // Techs cannot use admin or reviewer lists at all.
    expect((await inject("GET", "/admin/users", B.tech)).statusCode).toBe(403);
    expect((await inject("GET", "/lessons", B.tech)).statusCode).toBe(403);
  });

  it("the AI only learns from its own company's lessons", async () => {
    const { relevantLessons } = await import("../src/lib/skills.js");
    const forA = await relevantLessons(w.companyId, "hvac", { brand: "Carrier" });
    expect(JSON.stringify(forA)).toContain(SECRET);
    expect(JSON.stringify(forA)).not.toContain("BRAVOLESSON");
    const forB = await relevantLessons(B.companyId, "hvac", { brand: "Carrier" });
    expect(JSON.stringify(forB)).toContain("BRAVOLESSON");
    expect(JSON.stringify(forB)).not.toContain(SECRET);
  });
});

describe("company B cannot pull A's records in through its own requests", () => {
  it("cannot attach A's customer to a B job", async () => {
    for (const [role, token] of bAccounts()) {
      const r = await inject("POST", "/jobs", token, { trade: "plumbing", title: "Sneaky", customer: { id: A.customerId, name: "anything" } });
      expect([400, 404], `${role} -> ${r.statusCode} ${r.body.slice(0, 200)}`).toContain(r.statusCode);
      expect(r.body).not.toContain(SECRET);
    }
    const jobs = (await inject("GET", "/jobs", B.admin)).json().jobs;
    expect(jobs.some((j: any) => j.customer_id === A.customerId)).toBe(false);
  });

  it("cannot assign A's technician to a B job", async () => {
    const create = await inject("POST", "/jobs", B.admin, { trade: "plumbing", title: "Assign A tech", assignedTechId: A.techId });
    expect(create.statusCode).toBe(400);
    const patch = await inject("PATCH", `/jobs/${B.jobId}`, B.admin, { assignedTechId: A.techId });
    expect([400, 404], `PATCH -> ${patch.statusCode} ${patch.body.slice(0, 200)}`).toContain(patch.statusCode);
    const job = (await inject("GET", `/jobs/${B.jobId}`, B.admin)).json().job;
    expect(job.assigned_tech_id).not.toBe(A.techId);
    expect(job.tech_name ?? "").not.toContain("Nick");
  });

  it("cannot create jobs, users or invites inside A by naming A's company", async () => {
    const job = await inject("POST", "/jobs", B.admin, { companyId: w.companyId, trade: "plumbing", title: "Wrong company" });
    expect(job.statusCode).toBe(200);
    expect(job.json().job.company_id).toBe(B.companyId);

    const user = await inject("POST", "/admin/users", B.admin, {
      companyId: w.companyId,
      email: "planted@bravo.test",
      name: "Planted",
      role: "tech",
      password: "planted-pass-2026",
    });
    expect(user.statusCode).toBe(200);
    expect(user.json().user.company_id).toBe(B.companyId);
    expect((await inject("POST", "/admin/users", B.admin, { email: "o@bravo.test", name: "O", role: "owner", password: "owner-pass-2026" })).statusCode).toBe(403);

    // A non-owner's companyId is ignored: the invite lands in their own company.
    const inv = await inject("POST", "/admin/invites", B.admin, { companyId: w.companyId, name: "Wrong", email: "wrong@bravo.test", send: false });
    expect(inv.statusCode).toBe(200);
    expect(inv.json().invite.company_id).toBe(B.companyId);
    const peek = await inject("GET", `/auth/invite/${encodeURIComponent(inv.json().invite.code)}`);
    expect(peek.json().companyName).toBe("Bravo Plumbing");
  });

  it("cannot promote anyone to owner", async () => {
    const promote = await inject("PATCH", `/admin/users/${B.techId}`, B.admin, { role: "owner" });
    expect(promote.statusCode).toBe(403);
  });

  it("is not paged when A's technician asks for help", async () => {
    const { outbox } = await import("../src/lib/notify.js");
    const before = outbox.length;
    const job = await inject("POST", "/jobs", w.tokens.expert, { trade: "hvac", title: `${SECRET} escalation job`, assignedTechId: A.techId });
    const visit = (await inject("POST", `/jobs/${job.json().job.id}/visits`, w.tokens.tech)).json().visit;
    const esc = await inject("POST", `/visits/${visit.id}/escalate`, w.tokens.tech, { reason: `${SECRET} help` });
    expect(esc.statusCode).toBe(200);
    // Let the escalation timer (1 s in tests) page the next expert too.
    await new Promise((r) => setTimeout(r, 2500));
    const sent = outbox.slice(before);
    expect(sent.some((m) => m.to.includes("6145550142")), JSON.stringify(sent)).toBe(false);
  });

  it("the agent context for A's visit has no B data and B's visit has no A data", async () => {
    const a = await inject("GET", `/agent/visits/${A.visitId}/context`, undefined);
    expect(a.statusCode).toBe(401);
    const ctxA = await w.app.inject({ method: "GET", url: `/agent/visits/${A.visitId}/context`, headers: AGENT });
    expect(ctxA.statusCode).toBe(200);
    expect(ctxA.body).not.toContain("Bravo");
    const bJob = await inject("POST", "/jobs", B.tech, { trade: "plumbing", title: "Bravo walk-in" });
    expect(bJob.statusCode).toBe(200);
    const bVisit = (await inject("POST", `/jobs/${bJob.json().job.id}/visits`, B.tech)).json().visit;
    const ctxB = await w.app.inject({ method: "GET", url: `/agent/visits/${bVisit.id}/context`, headers: AGENT });
    expect(ctxB.statusCode).toBe(200);
    expect(ctxB.body).not.toContain(SECRET);
  });

  it("A's public report link shows only the approved report, and a wrong token shows nothing", async () => {
    const ok = await inject("GET", `/r/${A.reportToken}`);
    expect(ok.statusCode).toBe(200);
    expect(ok.body).toContain(`${SECRET} summary`);
    expect(ok.body).not.toContain("alpha-secret@example.com");
    expect(ok.body).not.toContain("5555550199");
    const bad = await inject("GET", `/r/${A.reportToken.slice(0, -2)}xx`);
    expect(bad.statusCode).toBe(404);
  });
});

describe("closing signup on the server", () => {
  it("blocks new companies but invites, joining and sign-in keep working", async () => {
    const { resetConfig } = await import("../src/config.js");
    process.env.SIGNUP_ENABLED = "false";
    resetConfig();
    try {
      const closed = await inject("POST", "/auth/signup", undefined, { companyName: "Late Co", name: "L", email: "late@late.test", password: "late-pass-2026" });
      expect(closed.statusCode).toBe(403);
      expect(closed.json().code ?? closed.json().error).toBeTruthy();

      const inv = await inject("POST", "/admin/invites", B.admin, { name: "New Hire", email: "newhire@bravo.test", role: "tech", send: false });
      expect(inv.statusCode).toBe(200);
      const peek = await inject("GET", `/auth/invite/${encodeURIComponent(inv.json().invite.code)}`);
      expect(peek.statusCode).toBe(200);
      expect(peek.json().companyName).toBe("Bravo Plumbing");
      const joined = await inject("POST", "/auth/accept-invite", undefined, { code: inv.json().invite.code, password: "new-hire-2026" });
      expect(joined.statusCode).toBe(200);
      expect(joined.json().company.id).toBe(B.companyId);

      const login = await inject("POST", "/auth/login", undefined, { email: "newhire@bravo.test", password: "new-hire-2026" });
      expect(login.statusCode).toBe(200);
      const adminLogin = await inject("POST", "/auth/login", undefined, { email: "bea@bravo.test", password: "bravo-admin-2026" });
      expect(adminLogin.statusCode).toBe(200);
      expect((await inject("GET", "/jobs", login.json().token)).statusCode).toBe(200);
    } finally {
      delete process.env.SIGNUP_ENABLED;
      resetConfig();
    }
  });
});
