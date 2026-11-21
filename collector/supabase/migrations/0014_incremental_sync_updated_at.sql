-- 0014_incremental_sync_updated_at.sql
-- P2.3: Add updated_at columns, BEFORE UPDATE triggers, and indexes on game and listing
-- to support incremental sync from the Android app.

alter table public.game
  add column if not exists updated_at timestamptz not null default now();

alter table public.listing
  add column if not exists updated_at timestamptz not null default now();

create or replace function public.set_updated_at()
returns trigger
language plpgsql
set search_path = public, pg_temp
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

drop trigger if exists trg_game_set_updated_at on public.game;
create trigger trg_game_set_updated_at
  before update on public.game
  for each row
  execute function public.set_updated_at();

drop trigger if exists trg_listing_set_updated_at on public.listing;
create trigger trg_listing_set_updated_at
  before update on public.listing
  for each row
  execute function public.set_updated_at();

create index if not exists idx_game_updated_at on public.game (updated_at);
create index if not exists idx_listing_updated_at on public.listing (updated_at);
