/**
 * Published list prices, USD per 1M tokens, checked Nov 21, 2026:
 *   Anthropic: platform.claude.com/docs/en/about-claude/pricing
 *   Google:    ai.google.dev/gemini-api/docs/pricing
 * Unknown models are priced at the most expensive known rate so the budget guard errs safe.
 */
export interface Rate {
  textIn: number;
  cachedIn?: number;
  audioIn?: number;
  imageIn?: number;
  textOut: number;
  audioOut?: number;
}

export const RATES: Record<string, Rate> = {
  "claude-haiku-5-5": { textIn: 0.1, cachedIn: 0.01, textOut: 0.5 },
  "claude-sonnet-5-5": { textIn: 2, cachedIn: 0.1, textOut: 10 },
  "claude-opus-5-5": { textIn: 4, cachedIn: 0.2, textOut: 20 },
  "gemini-3.1-flash-live-preview": { textIn: 0.75, audioIn: 3, imageIn: 1, textOut: 4.5, audioOut: 12 },
  "gemini-3.1-flash-lite": { textIn: 0.25, audioIn: 0.5, imageIn: 0.25, textOut: 1.5 },
  "gemini-3.5-flash-lite": { textIn: 0.3, imageIn: 0.3, textOut: 2.5 },
  "gemini-3.5-flash": { textIn: 1.5, imageIn: 1.5, textOut: 9 },
};

const FALLBACK: Rate = { textIn: 4, audioIn: 4, imageIn: 4, textOut: 20, audioOut: 20 };

export interface Usage {
  inputTokens?: number; // all input tokens, including audio, image and cached
  cachedTokens?: number;
  audioInTokens?: number;
  imageInTokens?: number;
  outputTokens?: number; // all output tokens, including audio
  audioOutTokens?: number;
}

export function rateFor(model: string): Rate {
  if (RATES[model]) return RATES[model];
  // Dated or suffixed ids, e.g. claude-haiku-5-5-20261001
  const base = Object.keys(RATES).find((k) => model.startsWith(k));
  return base ? RATES[base] : FALLBACK;
}

export function costUsd(model: string, u: Usage): number {
  const r = rateFor(model);
  const input = u.inputTokens ?? 0;
  const cached = Math.min(u.cachedTokens ?? 0, input);
  const audioIn = u.audioInTokens ?? 0;
  const imageIn = u.imageInTokens ?? 0;
  const textIn = Math.max(0, input - cached - audioIn - imageIn);
  const audioOut = u.audioOutTokens ?? 0;
  const textOut = Math.max(0, (u.outputTokens ?? 0) - audioOut);
  const dollars =
    textIn * r.textIn +
    cached * (r.cachedIn ?? r.textIn) +
    audioIn * (r.audioIn ?? r.textIn) +
    imageIn * (r.imageIn ?? r.textIn) +
    textOut * r.textOut +
    audioOut * (r.audioOut ?? r.textOut);
  return dollars / 1e6;
}
