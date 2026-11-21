-- 0012_indexes_and_maintenance.sql
-- P1.2: Missing query indexes + user_action 'seen' compaction RPC.
-- P1.4: Ensure public.game.description exists for reproducible schema & BGG enrichment.
-- Note: listing_normalized_title_idx already exists from 0004_add_product.sql.

-- 1. Missing indexes on listing, user_action, and product
create index if not exists idx_listing_product_id on public.listing (product_id);
create index if not exists idx_listing_game_id on public.listing (game_id);
create index if not exists idx_listing_release_date on public.listing (release_date);
create index if not exists idx_listing_last_seen_at on public.listing (last_seen_at);
create index if not exists idx_user_action_created_desc on public.user_action (created_at desc, id desc);
create index if not exists idx_product_updated_at on public.product (updated_at);

-- 2. Ensure game.description column exists (used by Android app & BGG enrichment)
alter table public.game
  add column if not exists description text;

-- 3. Compact duplicate 'seen' rows in public.user_action.
-- Keeps the most recent row by (created_at desc, id desc) per (game_id, listing_id, product_id).
-- NEVER touches 'favorite', 'unfavorite', or 'ignored' rows.
create or replace function public.compact_user_action_seen()
returns integer
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_deleted integer := 0;
begin
  with ranked as (
    select
      id,
      row_number() over (
        partition by game_id, listing_id, product_id
        order by created_at desc, id desc
      ) as rn
    from public.user_action
    where action = 'seen'
  ),
  deleted as (
    delete from public.user_action ua
    using ranked r
    where ua.id = r.id
      and ua.action = 'seen'
      and r.rn > 1
    returning ua.id
  )
  select count(*)::integer into v_deleted from deleted;

  return v_deleted;
end;
$$;

revoke all on function public.compact_user_action_seen() from public;
grant execute on function public.compact_user_action_seen() to service_role;
