-- Lessons the company learns from real visits. Claude proposes them from approved reports;
-- an expert or admin approves them before the AI ever uses one. Experts can also add their own.
create table if not exists skill_lessons (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null references companies(id) on delete cascade,
  trade text not null,
  equipment_type text,
  brand text,
  model text,
  symptom text not null,
  error_code text,
  cause text,
  fix text not null,
  parts text,
  source text not null default 'visit' check (source in ('visit','expert')),
  source_visit_id uuid references visits(id) on delete set null,
  status text not null default 'pending' check (status in ('pending','approved','rejected')),
  created_by uuid references users(id) on delete set null,
  reviewed_by uuid references users(id) on delete set null,
  reviewed_at timestamptz,
  times_used integer not null default 0,
  created_at timestamptz not null default now()
);
create index if not exists skill_lessons_lookup_idx on skill_lessons(company_id, trade, status);
