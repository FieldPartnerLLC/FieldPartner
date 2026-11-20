import { StrictMode, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter, Link, Navigate, Route, Routes, useNavigate, useParams } from "react-router-dom";
import "@livekit/components-styles";
import "./style.css";
import { api, API_URL, setApiUrl, setToken, token, type VisitRow } from "./api";
import { LiveVisit } from "./LiveVisit";

function useInterval(fn: () => void, ms: number) {
  useEffect(() => {
    fn();
    const t = setInterval(fn, ms);
    return () => clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ms]);
}

function Login() {
  const nav = useNavigate();
  const [server, setServer] = useState(localStorage.getItem("fp_api") || API_URL);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [err, setErr] = useState("");
  return (
    <div className="center">
      <form
        className="card narrow"
        onSubmit={async (e) => {
          e.preventDefault();
          setErr("");
          try {
            setApiUrl(server);
            const r = await api("/auth/login", { body: { email, password } });
            if (r.user.role === "tech") throw new Error("This portal is for experts and admins.");
            setToken(r.token);
            localStorage.setItem("fp_user", JSON.stringify(r.user));
            nav("/");
          } catch (e: any) {
            setErr(e.message);
          }
        }}
      >
        <h1>FieldPartner</h1>
        <p className="muted">Expert portal</p>
        <label>Server<input value={server} onChange={(e) => setServer(e.target.value)} /></label>
        <label>Email<input type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoFocus /></label>
        <label>Password<input type="password" value={password} onChange={(e) => setPassword(e.target.value)} /></label>
        {err && <p className="error">{err}</p>}
        <button className="primary">Sign in</button>
      </form>
    </div>
  );
}

function Shell({ children }: { children: React.ReactNode }) {
  const user = JSON.parse(localStorage.getItem("fp_user") || "{}");
  return (
    <div>
      <header className="top">
        <Link to="/" className="brand">FieldPartner</Link>
        <nav>
          <Link to="/">Live</Link>
          <Link to="/history">History</Link>
          <Link to="/review">Review</Link>
          {(user.role === "admin" || user.role === "owner") && <Link to="/team">Team</Link>}
        </nav>
        <span className="muted">{user.name}</span>
        <button
          className="link"
          onClick={() => {
            setToken(null);
            location.href = "/login";
          }}
        >
          Sign out
        </button>
      </header>
      <main>{children}</main>
    </div>
  );
}

function since(ts: string | null) {
  if (!ts) return "-";
  const s = Math.round((Date.now() - Date.parse(ts)) / 1000);
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  return new Date(ts).toLocaleString();
}

function VisitTable({ visits, live }: { visits: VisitRow[]; live?: boolean }) {
  if (!visits.length) return <p className="muted">Nothing here right now.</p>;
  return (
    <table>
      <thead>
        <tr>
          <th>Technician</th>
          <th>Job</th>
          <th>State</th>
          <th>Expert</th>
          <th>Flags</th>
          <th>{live ? "Last seen" : "Started"}</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        {visits.map((v) => (
          <tr key={v.id} className={v.escalation_status === "requested" ? "urgent" : ""}>
            <td>{v.tech_name}</td>
            <td>
              <b>{v.title}</b>
              <div className="muted small">{v.trade} · {v.address ?? ""} · {v.company_name}</div>
            </td>
            <td>{v.state}{v.consent_choice ? ` · ${v.consent_choice}` : ""}</td>
            <td>{v.escalation_status === "requested" ? <b className="red">NEEDS HELP: {v.escalation_reason}</b> : v.escalation_status}</td>
            <td>{v.flags.join(", ")}</td>
            <td>{since(live ? v.last_seen_at ?? v.started_at : v.started_at)}</td>
            <td>
              {live && !v.ended_at && <Link className="button primary" to={`/visits/${v.id}/live`}>Join live</Link>}{" "}
              <Link className="button" to={`/visits/${v.id}`}>Details</Link>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function LiveBoard() {
  const [visits, setVisits] = useState<VisitRow[]>([]);
  const [summary, setSummary] = useState<any>({});
  const [lastUrgent, setLastUrgent] = useState(0);
  useInterval(async () => {
    const r = await api<{ visits: VisitRow[] }>("/visits?active=true");
    setVisits(r.visits);
    const s = await api("/supervisor/summary");
    setSummary(s.summary);
    const urgent = r.visits.filter((v) => v.escalation_status === "requested").length;
    if (urgent > lastUrgent) {
      try {
        new Audio("data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQAAAAA=").play();
      } catch {}
      document.title = `(${urgent}) Help needed - FieldPartner`;
    } else if (urgent === 0) document.title = "FieldPartner Expert Portal";
    setLastUrgent(urgent);
  }, 4000);
  return (
    <Shell>
      <div className="stats">
        <div><b>{summary.active ?? 0}</b><span>visits live</span></div>
        <div className={Number(summary.waiting_for_expert) ? "red" : ""}><b>{summary.waiting_for_expert ?? 0}</b><span>need an expert</span></div>
        <div><b>{summary.visits_24h ?? 0}</b><span>visits today</span></div>
        <div><b>{summary.flagged_7d ?? 0}</b><span>flagged this week</span></div>
      </div>
      <h2>Live visits</h2>
      <VisitTable visits={visits} live />
    </Shell>
  );
}

function History({ flagged }: { flagged?: boolean }) {
  const [visits, setVisits] = useState<VisitRow[]>([]);
  useEffect(() => {
    api<{ visits: VisitRow[] }>(`/visits?limit=200${flagged ? "&flagged=true" : ""}`).then((r) => setVisits(r.visits));
  }, [flagged]);
  return (
    <Shell>
      <h2>{flagged ? "Review queue: safety stops, escalations, disputes, declines" : "Visit history"}</h2>
      <VisitTable visits={visits} />
    </Shell>
  );
}

function VisitDetail() {
  const { id } = useParams();
  const [d, setD] = useState<any>(null);
  const [err, setErr] = useState("");
  useEffect(() => {
    api(`/visits/${id}`).then(setD).catch((e) => setErr(e.message));
  }, [id]);
  if (err) return <Shell><p className="error">{err}</p></Shell>;
  if (!d) return <Shell><p>Loading...</p></Shell>;
  const r = d.visit.report;
  return (
    <Shell>
      <h2>{d.job.title}</h2>
      <p className="muted">{d.job.trade} · {d.customer?.name ?? ""} · {d.job.address ?? ""} · Tech: {d.tech?.name}</p>
      <p>State: {d.visit.state} · Consent: {d.visit.consent_choice ?? "none yet"} · Report: {d.visit.report_status} · Flags: {d.visit.flags.join(", ") || "none"}</p>
      {!d.visit.ended_at && <Link className="button primary" to={`/visits/${id}/live`}>Join live</Link>}
      <div className="grid2">
        <section className="card">
          <h3>Recordings and files</h3>
          {d.media.length === 0 && <p className="muted">No files yet. Phones upload video over Wi-Fi after the visit.</p>}
          {d.media.map((m: any) => (
            <div key={m.id} className="media">
              <b>{m.kind}</b> <span className="muted small">{m.status} {m.bytes ? `${Math.round(m.bytes / 1024)} KB` : ""}</span>
              {m.url && (m.kind === "video" || m.kind === "cloud_recording") && <video src={m.url} controls width="100%" />}
              {m.url && m.kind === "audio" && <audio src={m.url} controls />}
              {m.url && (m.kind === "snapshot" || m.kind === "signature") && <img src={m.url} width="100%" />}
              {m.url && (m.kind === "transcript" || m.kind === "report_pdf") && <a href={m.url} target="_blank">Open</a>}
            </div>
          ))}
          <h3>Consents</h3>
          {d.consents.map((c: any) => (
            <p key={c.id}>{c.kind}: <b>{c.choice ?? "signed"}</b> by {c.signer_name ?? "-"} ({c.signer_role ?? "-"}) at {new Date(c.signed_at).toLocaleString()}</p>
          ))}
          {r && (
            <>
              <h3>Report</h3>
              <p>{r.summary}</p>
              <ul>{[...(r.findings ?? []), ...(r.workPerformed ?? [])].map((x: string, i: number) => <li key={i}>{x}</li>)}</ul>
            </>
          )}
        </section>
        <section className="card timeline">
          <h3>Timeline</h3>
          {d.events.map((e: any) => (
            <div key={e.id} className={`ev ev-${e.type}`}>
              <span className="muted small">{new Date(e.ts).toLocaleTimeString()}</span> <b>{e.type}</b> <span className="muted">{e.actor}</span>{" "}
              {e.data?.text ?? e.data?.reason ?? (e.data?.name ? `${e.data.name}: ${e.data.value} ${e.data.unit ?? ""}` : e.data?.state ?? e.data?.status ?? "")}
            </div>
          ))}
        </section>
      </div>
    </Shell>
  );
}

function Team() {
  const [users, setUsers] = useState<any[]>([]);
  const [form, setForm] = useState({ name: "", email: "", phone: "", whatsapp: "", role: "tech", password: "" });
  const [msg, setMsg] = useState("");
  const load = () => api("/admin/users").then((r) => setUsers(r.users));
  useEffect(() => {
    load();
  }, []);
  return (
    <Shell>
      <h2>Team</h2>
      <table>
        <thead><tr><th>Name</th><th>Email</th><th>Role</th><th>Phone</th><th>WhatsApp</th><th>Active</th></tr></thead>
        <tbody>
          {users.map((u) => (
            <tr key={u.id}>
              <td>{u.name}</td><td>{u.email}</td><td>{u.role}</td><td>{u.phone ?? ""}</td><td>{u.whatsapp ?? ""}</td>
              <td>
                <button className="link" onClick={async () => { await api(`/admin/users/${u.id}`, { method: "PATCH", body: { active: !u.active } }); load(); }}>
                  {u.active ? "Active" : "Disabled"}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <form
        className="card narrow"
        onSubmit={async (e) => {
          e.preventDefault();
          setMsg("");
          try {
            await api("/admin/users", { body: { ...form, phone: form.phone || null, whatsapp: form.whatsapp || null } });
            setMsg("Added.");
            setForm({ name: "", email: "", phone: "", whatsapp: "", role: "tech", password: "" });
            load();
          } catch (e: any) {
            setMsg(e.message);
          }
        }}
      >
        <h3>Add a person</h3>
        <label>Name<input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
        <label>Email<input value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} /></label>
        <label>Mobile (for SMS)<input value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} /></label>
        <label>WhatsApp (for expert pages)<input value={form.whatsapp} onChange={(e) => setForm({ ...form, whatsapp: e.target.value })} /></label>
        <label>Role
          <select value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value })}>
            <option value="tech">Technician</option>
            <option value="expert">Expert</option>
            <option value="admin">Admin</option>
          </select>
        </label>
        <label>Temporary password (10+ characters)<input value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} /></label>
        {msg && <p>{msg}</p>}
        <button className="primary">Add</button>
      </form>
    </Shell>
  );
}

function Guard({ children }: { children: React.ReactNode }) {
  return token() ? <>{children}</> : <Navigate to="/login" />;
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/" element={<Guard><LiveBoard /></Guard>} />
        <Route path="/history" element={<Guard><History /></Guard>} />
        <Route path="/review" element={<Guard><History flagged /></Guard>} />
        <Route path="/team" element={<Guard><Team /></Guard>} />
        <Route path="/visits/:id" element={<Guard><VisitDetail /></Guard>} />
        <Route path="/visits/:id/live" element={<Guard><LiveVisit /></Guard>} />
      </Routes>
    </BrowserRouter>
  </StrictMode>,
);
