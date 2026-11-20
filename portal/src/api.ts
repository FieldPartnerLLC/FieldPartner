export const API_URL: string =
  localStorage.getItem("fp_api") || (import.meta.env.VITE_API_URL as string | undefined) || `${location.protocol}//${location.hostname}:8080`;

export function setApiUrl(url: string) {
  localStorage.setItem("fp_api", url.replace(/\/$/, ""));
}

export function token(): string | null {
  return localStorage.getItem("fp_token");
}

export function setToken(t: string | null) {
  if (t) localStorage.setItem("fp_token", t);
  else localStorage.removeItem("fp_token");
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

export async function api<T = any>(path: string, opts: { method?: string; body?: unknown } = {}): Promise<T> {
  const base = localStorage.getItem("fp_api") || API_URL;
  const res = await fetch(`${base}${path}`, {
    method: opts.method ?? (opts.body ? "POST" : "GET"),
    headers: {
      "Content-Type": "application/json",
      ...(token() ? { Authorization: `Bearer ${token()}` } : {}),
    },
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });
  const data = await res.json().catch(() => ({}));
  if (res.status === 401) {
    setToken(null);
    if (!location.pathname.startsWith("/login")) location.href = "/login";
  }
  if (!res.ok) throw new ApiError(res.status, data.message ?? `Error ${res.status}`);
  return data as T;
}

export interface VisitRow {
  id: string;
  state: string;
  started_at: string;
  ended_at: string | null;
  consent_choice: string | null;
  escalation_status: string;
  escalation_reason: string | null;
  flags: string[];
  report_status: string;
  last_seen_at: string | null;
  title: string;
  trade: string;
  address: string | null;
  tech_name: string;
  company_name: string;
}
