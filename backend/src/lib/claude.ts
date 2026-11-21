import { config, features } from "../config.js";

/**
 * Minimal Claude Messages API client. Plain fetch so there is one less dependency and tests
 * can point ANTHROPIC_BASE_URL at a local fake.
 */
export type ClaudeContent =
  | { type: "text"; text: string }
  | { type: "image"; source: { type: "base64"; media_type: "image/jpeg" | "image/png"; data: string } };

export interface ClaudeRequest {
  system: string;
  content: ClaudeContent[] | string;
  model?: string;
  maxTokens?: number;
  temperature?: number;
  timeoutMs?: number;
}

export class ClaudeUnavailable extends Error {}

export async function askClaude(req: ClaudeRequest): Promise<string> {
  if (!features.claude()) throw new ClaudeUnavailable("ANTHROPIC_API_KEY is not set.");
  const cfg = config();
  const res = await fetch(`${cfg.ANTHROPIC_BASE_URL.replace(/\/$/, "")}/v1/messages`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-api-key": cfg.ANTHROPIC_API_KEY!,
      "anthropic-version": "2023-06-01",
    },
    body: JSON.stringify({
      model: req.model ?? cfg.CLAUDE_MODEL,
      max_tokens: req.maxTokens ?? 1024,
      temperature: req.temperature ?? 0.2,
      system: req.system,
      messages: [{ role: "user", content: req.content }],
    }),
    signal: AbortSignal.timeout(req.timeoutMs ?? 45_000),
  });
  const body: any = await res.json().catch(() => ({}));
  if (!res.ok) {
    const msg = body?.error?.message ?? `HTTP ${res.status}`;
    throw new Error(`Claude request failed: ${msg}`);
  }
  const text = (body?.content ?? [])
    .filter((b: any) => b?.type === "text")
    .map((b: any) => String(b.text))
    .join("\n")
    .trim();
  if (!text) throw new Error("Claude returned no text.");
  return text;
}

/** Pulls the first JSON object or array out of a model reply (tolerates code fences and prose). */
export function extractJson(text: string): unknown {
  const fenced = text.match(/```(?:json)?\s*([\s\S]*?)```/);
  const body = fenced ? fenced[1] : text;
  const start = body.search(/[{[]/);
  if (start < 0) return null;
  const open = body[start];
  const close = open === "{" ? "}" : "]";
  const end = body.lastIndexOf(close);
  if (end <= start) return null;
  try {
    return JSON.parse(body.slice(start, end + 1));
  } catch {
    return null;
  }
}
