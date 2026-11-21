import type { ReportInputs } from "./report.js";

/**
 * Picks the cheapest model that can do the job.
 *
 *   fast   = Claude Haiku 5.5   ($0.10 in / $0.50 out per 1M tokens)
 *   expert = Claude Sonnet 5.5  ($2 in / $10 out), 20x the price
 *
 * Simple, low-risk work goes to fast. Safety risk, a fix that already failed, or a messy visit
 * goes to expert. For consults, fast also grades its own answer; if it is not confident the
 * question moves up to expert. Gemini Live stays the voice and eyes and never does this work.
 */
export type Tier = "fast" | "expert";

export interface Route {
  tier: Tier;
  reason: string;
}

// Anything that can hurt someone or cost a house goes straight to the senior model.
const RISK =
  /\b(gas|leak\w*|smell\w*|carbon monoxide|co alarm|co reading|ppm|burn\w*|smok\w*|spark\w*|arc\w*|shock\w*|electrocut\w*|live wire|energi[sz]ed|hot wire|480|277|three.phase|3.phase|service panel|main panel|meter base|melt\w*|scorch\w*|heat exchanger|crack\w*|flue|back.?draft\w*|rollout|refrigerant burn|frostbite|asbestos|flood\w*|sewage|water heater relief|t&p|pressure relief|explo\w*|fire)\b/i;

// The tech already tried something and it did not work, or is going in circles.
const STUCK = /\b(still|again|didn'?t work|did not work|not working after|tried (that|it|everything)|same (problem|issue|code)|no change|keeps? (tripping|failing|happening)|what else)\b/i;

export function consultRoute(question: string, recentTranscript: string[] = []): Route {
  if (RISK.test(question)) return { tier: "expert", reason: "safety risk in the question" };
  if (STUCK.test(question)) return { tier: "expert", reason: "first fix did not work" };
  const lastFew = recentTranscript.slice(-6).join(" ");
  if (RISK.test(lastFew)) return { tier: "expert", reason: "safety risk in the conversation" };
  if (question.length > 400) return { tier: "expert", reason: "long multi-part question" };
  return { tier: "fast", reason: "routine question" };
}

/** Reads a nameplate, label or code off one photo. Always fast: it is transcription, not judgment. */
export function readLabelRoute(): Route {
  return { tier: "fast", reason: "reading text from a photo" };
}

/** Lesson extraction is structured summarizing of an already approved report. */
export function lessonRoute(): Route {
  return { tier: "fast", reason: "summarizing an approved report" };
}

export function reportRoute(input: ReportInputs): Route {
  const types = new Set(input.events.map((e) => e.type));
  if (types.has("safety")) return { tier: "expert", reason: "visit had a safety stop" };
  if (types.has("escalation")) return { tier: "expert", reason: "supervisor was involved" };
  if (input.events.some((e) => e.type === "consult" && e.data?.tier === "expert")) return { tier: "expert", reason: "visit needed the senior expert" };
  if (types.has("disputed") || types.has("override")) return { tier: "expert", reason: "disputed or overridden step" };
  const transcriptChars = input.events.filter((e) => e.type === "transcript").reduce((n, e) => n + String(e.data?.text ?? "").length, 0);
  if (input.events.length > 150 || transcriptChars > 20_000) return { tier: "expert", reason: "long visit" };
  return { tier: "fast", reason: "routine visit" };
}

/** Over the monthly budget everything drops to fast, so a runaway month cannot get expensive. */
export function applyBudget(route: Route, overBudget: boolean): Route {
  if (overBudget && route.tier === "expert") return { tier: "fast", reason: `${route.reason}; monthly AI budget reached, using fast model` };
  return route;
}
