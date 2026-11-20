-- FieldPartner schema v1

create table if not exists companies (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  slug text not null unique,
  phone text,
  email text,
  address text,
  license_numbers text,
  wake_phrase text not null default 'hey dispatch',
  co_stop_ppm integer not null default 35,
  retention_days integer not null default 365,
  diagnostic_fee_cents integer,
  settings jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create table if not exists users (
  id uuid primary key default gen_random_uuid(),
  company_id uuid references companies(id) on delete cascade,
  email text not null unique,
  name text not null,
  phone text,
  whatsapp text,
  role text not null check (role in ('tech','expert','admin','owner')),
  trades text[] not null default '{}',
  certifications text[] not null default '{}',
  escalation_order integer not null default 100,
  password_hash text not null,
  active boolean not null default true,
  created_at timestamptz not null default now()
);
create index if not exists users_company_idx on users(company_id);

create table if not exists trade_profiles (
  id text primary key,
  name text not null,
  profile jsonb not null,
  updated_at timestamptz not null default now()
);

create table if not exists customers (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null references companies(id) on delete cascade,
  name text not null,
  phone text,
  email text,
  address text,
  notes text,
  created_at timestamptz not null default now()
);
create index if not exists customers_company_idx on customers(company_id);

create table if not exists jobs (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null references companies(id) on delete cascade,
  customer_id uuid references customers(id) on delete set null,
  trade text not null references trade_profiles(id),
  title text not null,
  complaint text,
  address text,
  scheduled_at timestamptz,
  status text not null default 'scheduled' check (status in ('scheduled','in_progress','completed','cancelled','declined','return_needed')),
  assigned_tech_id uuid references users(id) on delete set null,
  equipment jsonb not null default '{}'::jsonb,
  access_notes text,
  recording_pref text not null default 'unknown' check (recording_pref in ('unknown','video_ok','audio_only','no_recording')),
  created_at timestamptz not null default now()
);
create index if not exists jobs_company_sched_idx on jobs(company_id, scheduled_at);
create index if not exists jobs_tech_idx on jobs(assigned_tech_id, scheduled_at);

create table if not exists visits (
  id uuid primary key default gen_random_uuid(),
  job_id uuid not null references jobs(id) on delete cascade,
  company_id uuid not null references companies(id) on delete cascade,
  tech_id uuid not null references users(id),
  room_name text not null unique,
  state text not null default 'pre_visit',
  consent_choice text check (consent_choice in ('video_audio','audio_only','no_recording','declined_service')),
  private_areas_ok boolean not null default false,
  started_at timestamptz not null default now(),
  ended_at timestamptz,
  egress_id text,
  escalation_status text not null default 'none' check (escalation_status in ('none','requested','joined','unanswered','ended')),
  escalated_at timestamptz,
  report jsonb,
  report_status text not null default 'none' check (report_status in ('none','generating','draft','approved','sent','failed')),
  report_token text unique,
  flags text[] not null default '{}',
  created_at timestamptz not null default now()
);
create index if not exists visits_company_idx on visits(company_id, started_at desc);
create index if not exists visits_active_idx on visits(company_id) where ended_at is null;

create table if not exists consents (
  id uuid primary key default gen_random_uuid(),
  visit_id uuid not null references visits(id) on delete cascade,
  kind text not null check (kind in ('service_and_recording','repair','completion','recording_revoked')),
  choice text,
  private_areas_ok boolean,
  signer_name text,
  signer_role text,
  signature_key text,
  text_version text,
  language text not null default 'en',
  lat double precision,
  lng double precision,
  payload jsonb not null default '{}'::jsonb,
  signed_at timestamptz not null default now()
);
create index if not exists consents_visit_idx on consents(visit_id);

create table if not exists visit_events (
  id bigserial primary key,
  visit_id uuid not null references visits(id) on delete cascade,
  ts timestamptz not null default now(),
  type text not null,
  actor text not null default 'system' check (actor in ('tech','ai','expert','system','customer')),
  data jsonb not null default '{}'::jsonb
);
create index if not exists visit_events_visit_idx on visit_events(visit_id, ts);

create table if not exists media (
  id uuid primary key default gen_random_uuid(),
  visit_id uuid not null references visits(id) on delete cascade,
  kind text not null check (kind in ('video','audio','transcript','snapshot','signature','report_pdf','cloud_recording')),
  s3_key text not null,
  bytes bigint,
  content_type text,
  status text not null default 'pending' check (status in ('pending','uploaded','failed','deleted')),
  meta jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);
create index if not exists media_visit_idx on media(visit_id);

create table if not exists audit_log (
  id bigserial primary key,
  user_id uuid,
  action text not null,
  target text,
  ts timestamptz not null default now(),
  meta jsonb not null default '{}'::jsonb
);

create table if not exists schema_migrations (
  name text primary key,
  applied_at timestamptz not null default now()
);
