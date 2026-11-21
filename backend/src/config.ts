import { z } from "zod";

const bool = z
  .string()
  .optional()
  .transform((v) => v === "true" || v === "1");

const schema = z.object({
  NODE_ENV: z.string().default("development"),
  PORT: z.coerce.number().default(8080),
  PUBLIC_API_URL: z.string().default("http://localhost:8080"),
  PORTAL_URL: z.string().default("http://localhost:5173"),
  JWT_SECRET: z.string().min(16, "JWT_SECRET must be at least 16 characters"),
  AGENT_SERVICE_TOKEN: z.string().min(16, "AGENT_SERVICE_TOKEN must be at least 16 characters"),
  /** Postgres connection string. Leave empty to use the built-in embedded database in DATA_DIR. */
  DATABASE_URL: z.string().optional(),
  DATA_DIR: z.string().default("./data"),
  /** Where uploaded media goes when S3 is not configured. */
  MEDIA_DIR: z.string().optional(),

  LIVEKIT_URL: z.string().default(""),
  LIVEKIT_API_KEY: z.string().default(""),
  LIVEKIT_API_SECRET: z.string().default(""),
  LIVEKIT_AGENT_NAME: z.string().default("fieldpartner-agent"),

  S3_BUCKET: z.string().default(""),
  S3_REGION: z.string().default("us-east-1"),
  S3_ENDPOINT: z.string().optional(),
  S3_ACCESS_KEY_ID: z.string().optional(),
  S3_SECRET_ACCESS_KEY: z.string().optional(),
  CLOUD_RECORDING: bool,

  GOOGLE_API_KEY: z.string().optional(),
  // Backup report writer if Claude is down. Flash-Lite: $0.30 in / $2.50 out per 1M tokens.
  REPORT_MODEL: z.string().default("gemini-3.5-flash-lite"),

  // Claude: the "senior expert" during visits, report drafting and lesson writing.
  ANTHROPIC_API_KEY: z.string().optional(),
  ANTHROPIC_BASE_URL: z.string().default("https://api.anthropic.com"),
  // Routing (lib/router.ts): routine work goes to the fast model, risky or stuck work to the expert.
  CLAUDE_MODEL: z.string().default("claude-sonnet-5-5"), // expert: $2 in / $10 out per 1M tokens
  CLAUDE_FAST_MODEL: z.string().default("claude-haiku-5-5"), // fast: $0.10 in / $0.50 out per 1M tokens
  // Per company per calendar month, Claude plus Gemini. Past it, everything uses the fast model. 0 = no cap.
  AI_MONTHLY_BUDGET_USD: z.coerce.number().default(0),

  TWILIO_ACCOUNT_SID: z.string().optional(),
  TWILIO_AUTH_TOKEN: z.string().optional(),
  TWILIO_SMS_FROM: z.string().optional(),
  TWILIO_WHATSAPP_FROM: z.string().optional(),

  SMTP_HOST: z.string().optional(),
  SMTP_PORT: z.coerce.number().default(587),
  SMTP_USER: z.string().optional(),
  SMTP_PASS: z.string().optional(),
  SMTP_FROM: z.string().optional(),

  ESCALATION_TIMEOUT_SECONDS: z.coerce.number().default(120),
  // Anyone can create a company account from the portal. Set to false on a private server.
  SIGNUP_ENABLED: z
    .string()
    .default("true")
    .transform((v) => v.toLowerCase() !== "false"),
  INVITE_DAYS: z.coerce.number().int().min(1).max(90).default(14),
});

export type Config = z.infer<typeof schema>;

let cached: Config | null = null;

export function config(): Config {
  if (!cached) {
    const parsed = schema.safeParse(process.env);
    if (!parsed.success) {
      const issues = parsed.error.issues.map((i) => `${i.path.join(".")}: ${i.message}`).join("\n");
      throw new Error(`Invalid environment configuration:\n${issues}`);
    }
    cached = parsed.data;
  }
  return cached;
}

/** Test hook: re-read the environment. */
export function resetConfig(): void {
  cached = null;
}

export const features = {
  livekit: () => Boolean(config().LIVEKIT_URL && config().LIVEKIT_API_KEY && config().LIVEKIT_API_SECRET),
  s3: () => Boolean(config().S3_BUCKET),
  gemini: () => Boolean(config().GOOGLE_API_KEY),
  claude: () => Boolean(config().ANTHROPIC_API_KEY),
  twilio: () => Boolean(config().TWILIO_ACCOUNT_SID && config().TWILIO_AUTH_TOKEN),
  smtp: () => Boolean(config().SMTP_HOST),
};
