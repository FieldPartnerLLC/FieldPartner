-- Every paid AI call: who, which model, what for, tokens and dollars. Drives the cost page,
-- the per-visit cost on reports and the monthly budget guard.
create table if not exists ai_usage (
  id uuid primary key default gen_random_uuid(),
  company_id uuid references companies(id) on delete cascade,
  visit_id uuid references visits(id) on delete set null,
  provider text not null check (provider in ('anthropic','google')),
  model text not null,
  task text not null,
  input_tokens integer not null default 0,
  cached_tokens integer not null default 0,
  output_tokens integer not null default 0,
  audio_in_tokens integer not null default 0,
  image_in_tokens integer not null default 0,
  audio_out_tokens integer not null default 0,
  cost_usd numeric(12,6) not null default 0,
  created_at timestamptz not null default now()
);
create index if not exists ai_usage_company_month on ai_usage (company_id, created_at);
create index if not exists ai_usage_visit on ai_usage (visit_id);
