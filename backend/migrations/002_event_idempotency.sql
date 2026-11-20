-- Phones retry uploads on bad networks; a client-generated id makes each event land once.
alter table visit_events add column if not exists client_event_id text;
create unique index if not exists visit_events_client_id_idx on visit_events(visit_id, client_event_id) where client_event_id is not null;

-- Escalation paging progress: who has been paged and when the next page is due.
alter table visits add column if not exists escalation_reason text;
alter table visits add column if not exists escalation_paged jsonb not null default '[]'::jsonb;
alter table visits add column if not exists escalation_next_at timestamptz;
alter table visits add column if not exists expert_id uuid references users(id);
alter table visits add column if not exists last_seen_at timestamptz;
