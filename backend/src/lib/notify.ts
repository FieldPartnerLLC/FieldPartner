import nodemailer from "nodemailer";
import { config, features } from "../config.js";

export type Channel = "whatsapp" | "sms" | "email";

export interface SentMessage {
  channel: Channel;
  to: string;
  body: string;
  subject?: string;
  ok: boolean;
  error?: string;
  at: string;
}

/**
 * Every message goes through here. When a provider isn't configured the message
 * is recorded but not sent, so development and tests never page real phones.
 */
export const outbox: SentMessage[] = [];

function record(m: Omit<SentMessage, "at">): SentMessage {
  const msg = { ...m, at: new Date().toISOString() };
  outbox.push(msg);
  if (outbox.length > 500) outbox.shift();
  return msg;
}

export function normalizePhone(raw: string): string | null {
  const digits = raw.replace(/[^\d+]/g, "");
  if (digits.startsWith("+") && digits.length >= 11) return digits;
  const only = digits.replace(/\D/g, "");
  if (only.length === 10) return `+1${only}`;
  if (only.length === 11 && only.startsWith("1")) return `+${only}`;
  return null;
}

async function twilioSend(from: string, to: string, body: string): Promise<void> {
  const c = config();
  const url = `https://api.twilio.com/2010-04-01/Accounts/${c.TWILIO_ACCOUNT_SID}/Messages.json`;
  const auth = Buffer.from(`${c.TWILIO_ACCOUNT_SID}:${c.TWILIO_AUTH_TOKEN}`).toString("base64");
  const res = await fetch(url, {
    method: "POST",
    headers: { Authorization: `Basic ${auth}`, "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ From: from, To: to, Body: body }),
    signal: AbortSignal.timeout(15_000),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`Twilio ${res.status}: ${text.slice(0, 200)}`);
  }
}

export async function sendWhatsApp(rawTo: string, body: string): Promise<SentMessage> {
  const to = normalizePhone(rawTo);
  if (!to) return record({ channel: "whatsapp", to: rawTo, body, ok: false, error: "invalid phone number" });
  if (!features.twilio() || !config().TWILIO_WHATSAPP_FROM) {
    return record({ channel: "whatsapp", to, body, ok: false, error: "whatsapp not configured" });
  }
  try {
    await twilioSend(config().TWILIO_WHATSAPP_FROM!, `whatsapp:${to}`, body);
    return record({ channel: "whatsapp", to, body, ok: true });
  } catch (e) {
    return record({ channel: "whatsapp", to, body, ok: false, error: (e as Error).message });
  }
}

export async function sendSms(rawTo: string, body: string): Promise<SentMessage> {
  const to = normalizePhone(rawTo);
  if (!to) return record({ channel: "sms", to: rawTo, body, ok: false, error: "invalid phone number" });
  if (!features.twilio() || !config().TWILIO_SMS_FROM) {
    return record({ channel: "sms", to, body, ok: false, error: "sms not configured" });
  }
  try {
    await twilioSend(config().TWILIO_SMS_FROM!, to, body);
    return record({ channel: "sms", to, body, ok: true });
  } catch (e) {
    return record({ channel: "sms", to, body, ok: false, error: (e as Error).message });
  }
}

let transport: ReturnType<typeof nodemailer.createTransport> | null = null;

export async function sendEmail(
  to: string,
  subject: string,
  body: string,
  attachments: { filename: string; content: Buffer }[] = [],
): Promise<SentMessage> {
  if (!features.smtp()) return record({ channel: "email", to, subject, body, ok: false, error: "email not configured" });
  const c = config();
  transport ??= nodemailer.createTransport({
    host: c.SMTP_HOST,
    port: c.SMTP_PORT,
    secure: c.SMTP_PORT === 465,
    auth: c.SMTP_USER ? { user: c.SMTP_USER, pass: c.SMTP_PASS } : undefined,
  });
  try {
    await transport.sendMail({ from: c.SMTP_FROM, to, subject, text: body, attachments });
    return record({ channel: "email", to, subject, body, ok: true });
  } catch (e) {
    return record({ channel: "email", to, subject, body, ok: false, error: (e as Error).message });
  }
}

/** WhatsApp first; if it fails, SMS. Returns every attempt. */
export async function pagePhone(phone: string, body: string, whatsapp?: string | null): Promise<SentMessage[]> {
  const attempts: SentMessage[] = [];
  const wa = await sendWhatsApp(whatsapp || phone, body);
  attempts.push(wa);
  if (!wa.ok) attempts.push(await sendSms(phone, body));
  return attempts;
}
