import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { auth, boot, type World } from "./helpers.js";

let w: World;
beforeAll(async () => {
  w = await boot();
});
afterAll(async () => {
  await w.app.close();
});

const post = (url: string, payload: unknown, headers: Record<string, string> = {}) => w.app.inject({ method: "POST", url, payload: payload as any, headers });

describe("a new business signs up, invites a technician, and the technician joins from the app", () => {
  it("runs the whole flow", async () => {
    // 1. The business owner creates the company account on the portal.
    const signup = await post("/auth/signup", { companyName: "Hudson Valley HVAC", name: "Mitch Rosa", email: "mitch@hvhvac.test", password: "furnace-2026-ok", phone: "+18455550100" });
    expect(signup.statusCode).toBe(200);
    const admin = signup.json();
    expect(admin.user.role).toBe("admin");
    expect(admin.company.name).toBe("Hudson Valley HVAC");
    expect(admin.company.wake_phrase).toBe("hey dispatch");

    // Same email again is refused.
    expect((await post("/auth/signup", { companyName: "Other", name: "X", email: "MITCH@hvhvac.test", password: "furnace-2026-ok" })).statusCode).toBe(409);

    // 2. The admin invites a technician.
    const inv = await post("/admin/invites", { name: "Jordan Tech", email: "jordan@hvhvac.test", phone: "+18455550111", trades: ["hvac"] }, auth(admin.token));
    expect(inv.statusCode).toBe(200);
    const code: string = inv.json().invite.code;
    expect(code).toMatch(/^[A-Z2-9]{4}-[A-Z2-9]{4}$/);
    expect(inv.json().message).toContain(code);

    // 3. The technician opens the app, types the code (any case, with or without the dash).
    const sloppy = ` ${code.toLowerCase().replace("-", " ")} `;
    const peek = await w.app.inject({ method: "GET", url: `/auth/invite/${encodeURIComponent(sloppy.trim())}` });
    expect(peek.statusCode).toBe(200);
    expect(peek.json()).toMatchObject({ companyName: "Hudson Valley HVAC", name: "Jordan Tech", email: "jordan@hvhvac.test", role: "tech" });

    expect((await post("/auth/accept-invite", { code, password: "short" })).statusCode).toBe(400);
    const joined = await post("/auth/accept-invite", { code: sloppy, password: "jordan-pass-2026" });
    expect(joined.statusCode).toBe(200);
    expect(joined.json().user).toMatchObject({ role: "tech", email: "jordan@hvhvac.test", trades: ["hvac"] });
    expect(joined.json().company.name).toBe("Hudson Valley HVAC");

    // The code works once.
    expect((await post("/auth/accept-invite", { code, password: "jordan-pass-2026" })).statusCode).toBe(409);

    // 4. The technician can sign in later and works only inside their company.
    const login = await post("/auth/login", { email: "jordan@hvhvac.test", password: "jordan-pass-2026" });
    expect(login.statusCode).toBe(200);
    const techToken = login.json().token;
    const job = await post("/jobs", { trade: "hvac", title: "No heat", complaint: "Cold house", address: "1 Main St" }, auth(techToken));
    expect(job.statusCode).toBe(200);
    expect(job.json().job.company_id).toBe(admin.company.id);
    const visit = await post(`/jobs/${job.json().job.id}/visits`, {}, auth(techToken));
    expect(visit.statusCode).toBe(200);

    // The new company cannot see the demo company's jobs, and the demo tech cannot see theirs.
    const theirJobs = (await w.app.inject({ method: "GET", url: "/jobs", headers: auth(techToken) })).json().jobs;
    expect(theirJobs.every((j: any) => j.company_id === admin.company.id)).toBe(true);
    expect((await w.app.inject({ method: "GET", url: `/jobs/${job.json().job.id}`, headers: auth(w.tokens.tech) })).statusCode).toBe(404);

    // The admin sees the used invite and the tech on the team.
    const list = (await w.app.inject({ method: "GET", url: "/admin/invites", headers: auth(admin.token) })).json().invites;
    expect(list[0].used_at).toBeTruthy();
    const team = (await w.app.inject({ method: "GET", url: "/admin/users", headers: auth(admin.token) })).json().users;
    expect(team.map((u: any) => u.email).sort()).toEqual(["jordan@hvhvac.test", "mitch@hvhvac.test"]);
  });

  it("refuses bad, revoked and expired codes, and techs cannot invite", async () => {
    expect((await w.app.inject({ method: "GET", url: "/auth/invite/ZZZZ-ZZZZ" })).statusCode).toBe(404);
    const s = (await post("/auth/signup", { companyName: "Roof Pros", name: "Ana", email: "ana@roof.test", password: "roofing-2026-ok" })).json();
    const inv = (await post("/admin/invites", { name: "Lee", email: "lee@roof.test" }, auth(s.token))).json().invite;
    expect((await w.app.inject({ method: "DELETE", url: `/admin/invites/${inv.id}`, headers: auth(s.token) })).statusCode).toBe(200);
    expect((await post("/auth/accept-invite", { code: inv.code, password: "lee-password-1" })).statusCode).toBe(404);

    const inv2 = (await post("/admin/invites", { name: "Lee", email: "lee@roof.test" }, auth(s.token))).json().invite;
    const { q } = await import("../src/db.js");
    await q("update invites set expires_at = now() - interval '1 day' where id = $1", [inv2.id]);
    expect((await post("/auth/accept-invite", { code: inv2.code, password: "lee-password-1" })).statusCode).toBe(410);

    expect((await post("/admin/invites", { name: "Z", email: "z@x.test" }, auth(w.tokens.tech))).statusCode).toBe(403);
    // Inviting someone who already has an account is refused.
    expect((await post("/admin/invites", { name: "Ana", email: "ana@roof.test" }, auth(s.token))).statusCode).toBe(409);
  });

  it("can be closed on a private server", async () => {
    const { resetConfig } = await import("../src/config.js");
    process.env.SIGNUP_ENABLED = "false";
    resetConfig();
    expect((await post("/auth/signup", { companyName: "Nope", name: "N", email: "n@n.test", password: "nope-nope-nope" })).statusCode).toBe(403);
    delete process.env.SIGNUP_ENABLED;
    resetConfig();
  });
});
