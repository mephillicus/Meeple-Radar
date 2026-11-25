-- 0024_normalize_ampersand_and_merge_dups.sql
-- Idempotent migration:
-- 1. Updates public.normalize_collector_title(p_title text) to normalize space-surrounded
--    ampersands (\s+&\s+) to ' e ' right before non-word punctuation removal (1:1 with collector/src/normalize.ts),
--    preserving tight acronyms like "D&D" -> "dd".
-- 2. Merges any multi-product game_ids via public.merge_duplicate_products_for_game(game_id).
-- 3. Recomputes normalized_title for product rows containing '&' (using a small temp table
--    of only affected rows) and merges collisions via public.merge_product_into.
-- 4. Recomputes normalized_title on listing rows containing '&' to stay in sync.

-- Step 1: Canonical collector title normalization in SQL (1:1 with collector/src/normalize.ts)
create or replace function public.normalize_collector_title(p_title text)
returns text
language plpgsql
immutable
set search_path = public, pg_temp
as $$
declare
  v text;
  i integer;
begin
  v := lower(coalesce(p_title, ''));
  v := regexp_replace(v, '\ms#!t(s?)\M', 'shit\1', 'g');
  v := regexp_replace(v, '\msh!t(s?)\M', 'shit\1', 'g');
  v := regexp_replace(v, U&'[\002D\2013\2014/:]+', ' ', 'g');
  v := regexp_replace(v, '\s+&\s+', ' e ', 'g');
  v := regexp_replace(
    v,
    U&'[^A-Za-z0-9_\0009-\000D\0020\00A0\1680\2000-\200A\2028\2029\202F\205F\3000\FEFF]',
    '',
    'g'
  );
  v := btrim(
    regexp_replace(
      v,
      U&'[\0009-\000D\0020\00A0\1680\2000-\200A\2028\2029\202F\205F\3000\FEFF]+',
      ' ',
      'g'
    )
  );
  v := regexp_replace(v, '\m(\d+) minutes\M', '\1 minute', 'g');

  for i in 1..3 loop
    v := regexp_replace(
      v,
      ' +(?:edizione italiana|ed italiana|italiano|ita|il gioco di carte|gioco di carte|il gioco da tavolo|gioco da tavolo|card game|board game)$',
      ''
    );
  end loop;

  return v;
end;
$$;

grant execute on function public.normalize_collector_title(text) to anon, authenticated, service_role;

-- Step 2: Merge duplicate products for same game_id and deduplicate '&' vs 'e' titles
do $$
declare
  r_game record;
  r_amp record;
  v_existing_id bigint;
  v_merged_games bigint := 0;
  v_merged_products bigint := 0;
  v_updated_products bigint := 0;
  v_updated_listings bigint := 0;
begin
  set local statement_timeout = '120s';

  -- 2a. Merge any multi-product game_ids first
  for r_game in
    select game_id
    from public.product
    where game_id is not null
    group by game_id
    having count(*) > 1
    order by game_id
  loop
    perform public.merge_duplicate_products_for_game(r_game.game_id);
    v_merged_games := v_merged_games + 1;
  end loop;

  -- 2b. Materialize only product rows containing '&' whose normalized_title changes
  drop table if exists tmp_amp_products;

  create temp table tmp_amp_products on commit drop as
  select
    s.id,
    s.game_id,
    s.old_norm,
    s.new_norm
  from (
    select
      p.id,
      p.game_id,
      p.normalized_title as old_norm,
      public.normalize_collector_title(p.title) as new_norm
    from public.product p
    where p.title like '%&%'
  ) s
  where s.new_norm <> ''
    and s.new_norm is distinct from s.old_norm;

  create index on tmp_amp_products (new_norm);
  create index on tmp_amp_products (id);

  -- Process rows with game_id set first so canonical metadata is preserved when two
  -- '&' rows collapse onto the same new_norm.
  for r_amp in
    select id, new_norm
    from tmp_amp_products
    order by (game_id is not null) desc, id asc
  loop
    if not exists (select 1 from public.product where id = r_amp.id) then
      continue;
    end if;

    select p.id into v_existing_id
    from public.product p
    where p.normalized_title = r_amp.new_norm
      and p.id <> r_amp.id
    order by (p.game_id is not null) desc, p.id asc
    limit 1;

    if v_existing_id is not null then
      perform public.merge_product_into(r_amp.id, v_existing_id);
      v_merged_products := v_merged_products + 1;
    else
      update public.product
      set normalized_title = r_amp.new_norm,
          updated_at = now()
      where id = r_amp.id
        and normalized_title is distinct from r_amp.new_norm;
      v_updated_products := v_updated_products + 1;
    end if;
  end loop;

  -- 2c. Recompute normalized_title on listing rows containing '&'
  update public.listing l
  set normalized_title = s.new_norm
  from (
    select
      id,
      public.normalize_collector_title(title) as new_norm
    from public.listing
    where title like '%&%'
  ) s
  where l.id = s.id
    and s.new_norm <> ''
    and l.normalized_title is distinct from s.new_norm;
  get diagnostics v_updated_listings = row_count;

  -- 2d. Final safety pass: merge any game_id that gained a second product via coalesce in merge_product_into
  for r_game in
    select game_id
    from public.product
    where game_id is not null
    group by game_id
    having count(*) > 1
    order by game_id
  loop
    perform public.merge_duplicate_products_for_game(r_game.game_id);
    v_merged_games := v_merged_games + 1;
  end loop;

  raise notice '0024_normalize_ampersand_and_merge_dups: merged % multi-product game(s), updated % product(s), merged % duplicate product(s), updated % listing(s)',
    v_merged_games,
    v_updated_products,
    v_merged_products,
    v_updated_listings;
end;
$$;
