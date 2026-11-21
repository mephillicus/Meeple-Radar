-- 0016_user_alerts.sql
-- P3.2: User alerts table for back-in-stock, price-drop, and new-expansion notifications.

create table if not exists public.user_alert (
  id bigserial primary key,
  alert_type text not null check (alert_type in ('back_in_stock', 'price_drop', 'new_expansion')),
  game_id bigint references public.game(id) on delete cascade,
  product_id bigint references public.product(id) on delete cascade,
  listing_id bigint references public.listing(id) on delete cascade,
  title text not null,
  payload jsonb not null default '{}'::jsonb,
  dedupe_key text not null unique,
  read_at timestamptz,
  created_at timestamptz not null default now()
);

create index if not exists idx_user_alert_created_desc
  on public.user_alert (created_at desc);

create index if not exists idx_user_alert_unread
  on public.user_alert (read_at)
  where read_at is null;

alter table public.user_alert enable row level security;

drop policy if exists "read user_alert" on public.user_alert;
create policy "read user_alert"
  on public.user_alert
  for select
  to anon, authenticated
  using (true);

drop policy if exists "update user_alert" on public.user_alert;
create policy "update user_alert"
  on public.user_alert
  for update
  to anon, authenticated
  using (true)
  with check (true);

grant select, update on public.user_alert to anon, authenticated;
