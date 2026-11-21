import { config, features } from "../config.js";
import { recordUsage, type UsageScope } from "./usage.js";
import type { Tier } from "./router.js";

/**
 * Minimal Claude Messages API client. Plain fetch so there is one less dependency and tests
 * can point ANTHROPIC_BASE_URL at a local fake. Every call is metered into ai_usage.
 */
export type ClaudeContent =
  | { type: "text"; text: string }
  | { type: "image"; source: { type: "base64"; media_type: "image/jpeg" | "image/png"; data: string } };

export interface ClaudeRequest {
  system: string;
  content: ClaudeContent[] | string;
  /** Pick by tier (fast = Haiku, expert = Sonnet). An explicit model wins. */
  tier?: Tier;
  model?: string;
  maxTokens?: number;
  temperature?: number;
  timeoutMs?: number;
  /** Who to bill this call to, for the cost page and the budget guard. */
  scope?: UsageScope;
  task?: string;
}

export interface ClaudeReply {
  text: string;
  model: string;
  inputTokens: number;
  outputTokens: number;
  costUsd: number;
}

export class ClaudeUnavailable extends Error {}

export function modelForTier(tier: Tier = "expert"): string {
  const cfg = config();
  return tier === "fast" ? cfg.CLAUDE_FAST_MODEL : cfg.CLAUDE_MODEL;
}

export async function callClaude(req: ClaudeRequest): Promise<ClaudeReply> {
  if (!features.claude()) throw new ClaudeUnavailable("ANTHROPIC_API_KEY is not set.");
  const cfg = config();
  const model = req.model ?? modelForTier(req.tier);
  const res = await fetch(`${cfg.ANTHROPIC_BASE_URL.replace(/\/$/, "")}/v1/messages`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-api-key": cfg.ANTHROPIC_API_KEY!,
      "anthropic-version": "2023-06-01",
    },
    body: JSON.stringify({
      model,
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
  const u = body?.usage ?? {};
  const cacheRead = Number(u.cache_read_input_tokens ?? 0);
  const inputTokens = Number(u.input_tokens ?? 0) + Number(u.cache_creation_input_tokens ?? 0) + cacheRead;
  const outputTokens = Number(u.output_tokens ?? 0);
  const costUsd = await recordUsage(req.scope ?? {}, {
    provider: "anthropic",
    model,
    task: req.task ?? "other",
    usage: { inputTokens, cachedTokens: cacheRead, outputTokens },
  });
  const text = (body?.content ?? [])
    .filter((b: any) => b?.type === "text")
    .map((b: any) => String(b.text))
    .join("\n")
    .trim();
  if (!text) throw new Error("Claude returned no text.");
  return { text, model, inputTokens, outputTokens, costUsd };
}

/** Text-only convenience wrapper. */
export async function askClaude(req: ClaudeRequest): Promise<string> {
  return (await callClaude(req)).text;
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
