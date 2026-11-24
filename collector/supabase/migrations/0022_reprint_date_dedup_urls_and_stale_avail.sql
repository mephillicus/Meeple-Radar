-- 0022_reprint_date_dedup_urls_and_stale_avail.sql
-- Idempotent migration:
-- 1. Add nullable reprint_date columns to public.listing and public.product (additive, safe for Android).
-- 2. Deduplicate public.listing rows sharing the same (source_id, url), preserving user_action and user_alert links.
-- 3. Add service_role-only RPC public.clear_stale_partial_source_availability(integer) and run it once.
-- 4. Move Bookshelf's mistaken 2027-04-15 release_date to reprint_date on listing and product.

-- Step 1: Add reprint_date columns
alter table public.listing
  add column if not exists reprint_date date;

alter table public.product
  add column if not exists reprint_date date;

-- Step 2: Deduplicate public.listing rows sharing the same (source_id, url)
do $$
declare
  v_repointed_actions bigint := 0;
  v_repointed_alerts bigint := 0;
  v_deleted_listings bigint := 0;
begin
  drop table if exists tmp_listing_dups;

  create temp table tmp_listing_dups on commit drop as
  with ranked as (
    select
      id,
      first_value(id) over (
        partition by source_id, url
        order by last_seen_at desc nulls last, id desc
      ) as canonical_id,
      row_number() over (
        partition by source_id, url
        order by last_seen_at desc nulls last, id desc
      ) as rn
    from public.listing
    where url is not null
      and url <> ''
  )
  select
    id as dup_id,
    canonical_id
  from ranked
  where rn > 1;

  create index on tmp_listing_dups (dup_id);

  update public.user_action ua
  set listing_id = d.canonical_id
  from tmp_listing_dups d
  where ua.listing_id = d.dup_id;
  get diagnostics v_repointed_actions = row_count;

  update public.user_alert ual
  set listing_id = d.canonical_id
  from tmp_listing_dups d
  where ual.listing_id = d.dup_id;
  get diagnostics v_repointed_alerts = row_count;

  delete from public.listing
  where id in (select dup_id from tmp_listing_dups);
  get diagnostics v_deleted_listings = row_count;

  raise notice '0022_reprint_date_dedup_urls_and_stale_avail: deduplicated % listing(s) (re-pointed % user_action, % user_alert)',
    v_deleted_listings,
    v_repointed_actions,
    v_repointed_alerts;
end;
$$;

-- Step 3: Stale availability cleanup RPC for partial-feed sources (boardhouse, dungeondice)
create or replace function public.clear_stale_partial_source_availability(
  p_days integer default 14
)
returns integer
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_updated integer := 0;
begin
  update public.listing
  set availability = null
  where source_id in ('boardhouse', 'dungeondice')
    and last_seen_at < now() - make_interval(days => greatest(1, coalesce(p_days, 14)))
    and availability is not null;

  get diagnostics v_updated = row_count;
  return v_updated;
end;
$$;

revoke all on function public.clear_stale_partial_source_availability(integer) from public, anon, authenticated;
grant execute on function public.clear_stale_partial_source_availability(integer) to service_role;

do $$
begin
  perform public.clear_stale_partial_source_availability(14);
end;
$$;

-- Step 4: Clean up Bookshelf's mistaken 2027-04-15 reprint date
update public.listing
set reprint_date = '2027-04-15',
    release_date = null,
    availability = 'reprint'
where source_id = 'littlerocket'
  and external_id = '2748'
  and release_date = '2027-04-15';

update public.product
set reprint_date = '2027-04-15',
    release_date = null,
    updated_at = now()
where normalized_title = 'bookshelf'
  and release_date = '2027-04-15';
