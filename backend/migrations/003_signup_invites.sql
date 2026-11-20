-- Self-service onboarding: a company signs up, its admin invites people with a short code.
create table if not exists invites (
  id uuid primary key default gen_random_uuid(),
  code text not null unique,
  company_id uuid not null references companies(id) on delete cascade,
  email text not null,
  name text not null,
  phone text,
  whatsapp text,
  role text not null check (role in ('tech','expert','admin')),
  trades text[] not null default '{}',
  created_by uuid references users(id) on delete set null,
  expires_at timestamptz not null,
  used_at timestamptz,
  used_by uuid references users(id) on delete set null,
  revoked_at timestamptz,
  created_at timestamptz not null default now()
);
create index if not exists invites_company_idx on invites(company_id);
