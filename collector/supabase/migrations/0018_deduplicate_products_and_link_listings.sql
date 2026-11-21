-- 0018_deduplicate_products_and_link_listings.sql
-- Product deduplication, unified title normalization (matching collector/src/normalize.ts 1:1),
-- same-game product merge RPCs, and orphan listing re-linking.

-- Step A: Canonical collector title normalization in SQL (1:1 with collector/src/normalize.ts)
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

-- Step B: Update app RPCs to use public.normalize_collector_title(p_title)
create or replace function public.upsert_product_for_game(
  p_game_id bigint,
  p_title text,
  p_url text,
  p_image_url text default null,
  p_description text default null
)
returns bigint
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_product_id bigint;
  v_normalized_title text;
  r_dup record;
begin
  if p_game_id is null or p_game_id <= 0 then
    raise exception 'p_game_id must be a positive ID';
  end if;
  if p_title is null or btrim(p_title) = '' then
    raise exception 'p_title must not be empty';
  end if;

  v_normalized_title := public.normalize_collector_title(p_title);

  if v_normalized_title = '' then
    raise exception 'p_title does not produce a valid normalized_title';
  end if;

  select id into v_product_id
  from public.product
  where game_id = p_game_id
  order by id
  limit 1;

  if v_product_id is null then
    select id into v_product_id
    from public.product
    where normalized_title = v_normalized_title
    limit 1;
  end if;

  if v_product_id is not null then
    for r_dup in
      select id
      from public.product
      where normalized_title = v_normalized_title
        and id <> v_product_id
    loop
      perform public.merge_product_into(r_dup.id, v_product_id);
    end loop;

    update public.product
    set normalized_title = v_normalized_title,
        title = p_title,
        url = coalesce(p_url, url),
        image_url = coalesce(p_image_url, image_url),
        description = coalesce(p_description, description),
        game_id = p_game_id,
        updated_at = now()
    where id = v_product_id;
    return v_product_id;
  end if;

  insert into public.product (normalized_title, title, url, image_url, description, game_id)
  values (v_normalized_title, p_title, p_url, p_image_url, p_description, p_game_id)
  on conflict (normalized_title) do update
    set title = excluded.title,
        url = coalesce(excluded.url, public.product.url),
        image_url = coalesce(excluded.image_url, public.product.image_url),
        description = coalesce(excluded.description, public.product.description),
        game_id = excluded.game_id,
        updated_at = now()
  returning id into v_product_id;

  return v_product_id;
end;
$$;

revoke all on function public.upsert_product_for_game(bigint, text, text, text, text) from public;
grant execute on function public.upsert_product_for_game(bigint, text, text, text, text) to anon, authenticated;

create or replace function public.upsert_meeple_app_listing(
  p_bgg_id integer,
  p_title text,
  p_url text,
  p_game_id bigint,
  p_product_id bigint,
  p_image_url text default null
)
returns bigint
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_listing_id bigint;
  v_title text;
  v_normalized_title text;
  v_url text;
  v_image_url text;
  v_external_id text;
begin
  if p_bgg_id is null or p_bgg_id <= 0 then
    raise exception 'p_bgg_id must be a positive integer';
  end if;

  v_title := nullif(btrim(p_title), '');
  if v_title is null then
    raise exception 'p_title must not be empty';
  end if;

  v_normalized_title := public.normalize_collector_title(p_title);

  v_url := nullif(btrim(p_url), '');
  if v_url is null then
    raise exception 'p_url must not be empty';
  end if;

  if p_game_id is null or p_game_id <= 0 then
    raise exception 'p_game_id must be a positive ID';
  end if;
  if p_product_id is null or p_product_id <= 0 then
    raise exception 'p_product_id must be a positive ID';
  end if;

  if not exists (select 1 from public.game where id = p_game_id) then
    raise exception 'No game found for p_game_id %', p_game_id;
  end if;
  if not exists (select 1 from public.product where id = p_product_id) then
    raise exception 'No product found for p_product_id %', p_product_id;
  end if;

  v_image_url := nullif(btrim(p_image_url), '');
  v_external_id := 'bgg:' || p_bgg_id::text;

  -- Prefer exact (source_id, external_id) match first to avoid unique-key collisions.
  select id into v_listing_id
  from public.listing
  where source_id = 'meeple_app'
    and external_id = v_external_id
  limit 1;

  -- Fallback to legacy meeple_app listing matched by URL.
  if v_listing_id is null then
    select id into v_listing_id
    from public.listing
    where source_id = 'meeple_app'
      and url = v_url
    order by id
    limit 1;
  end if;

  if v_listing_id is not null then
    update public.listing
    set external_id = v_external_id,
        title = v_title,
        normalized_title = nullif(v_normalized_title, ''),
        url = v_url,
        image_url = v_image_url,
        game_id = p_game_id,
        product_id = p_product_id,
        match_confidence = 1.0,
        last_seen_at = now()
    where id = v_listing_id;

    return v_listing_id;
  end if;

  insert into public.listing (
    source_id,
    external_id,
    title,
    normalized_title,
    url,
    image_url,
    game_id,
    product_id,
    match_confidence
  )
  values (
    'meeple_app',
    v_external_id,
    v_title,
    nullif(v_normalized_title, ''),
    v_url,
    v_image_url,
    p_game_id,
    p_product_id,
    1.0
  )
  on conflict (source_id, external_id) do update
    set title = excluded.title,
        normalized_title = excluded.normalized_title,
        url = excluded.url,
        image_url = excluded.image_url,
        game_id = excluded.game_id,
        product_id = excluded.product_id,
        match_confidence = excluded.match_confidence,
        last_seen_at = now()
  returning id into v_listing_id;

  return v_listing_id;
end;
$$;

revoke all on function public.upsert_meeple_app_listing(integer, text, text, bigint, bigint, text) from public;
grant execute on function public.upsert_meeple_app_listing(integer, text, text, bigint, bigint, text) to anon, authenticated;

-- Step C: Helper function public.merge_product_into(p_source_id bigint, p_target_id bigint)
create or replace function public.merge_product_into(
  p_source_id bigint,
  p_target_id bigint
)
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
  if p_source_id is null or p_target_id is null or p_source_id = p_target_id then
    return;
  end if;

  if not exists (select 1 from public.product where id = p_source_id) then
    return;
  end if;

  if not exists (select 1 from public.product where id = p_target_id) then
    return;
  end if;

  update public.product target
  set game_id = coalesce(target.game_id, source.game_id),
      description = coalesce(target.description, source.description),
      image_url = coalesce(target.image_url, source.image_url),
      release_date = coalesce(target.release_date, source.release_date),
      publisher = coalesce(target.publisher, source.publisher),
      price = coalesce(target.price, source.price),
      currency = coalesce(target.currency, source.currency),
      url = coalesce(target.url, source.url),
      updated_at = now()
  from public.product source
  where target.id = p_target_id
    and source.id = p_source_id;

  update public.listing
  set product_id = p_target_id
  where product_id = p_source_id;

  update public.user_action
  set product_id = p_target_id
  where product_id = p_source_id;

  update public.user_alert
  set product_id = p_target_id
  where product_id = p_source_id;

  delete from public.product
  where id = p_source_id;
end;
$$;

revoke all on function public.merge_product_into(bigint, bigint) from public;
grant execute on function public.merge_product_into(bigint, bigint) to service_role;

-- Step D: RPC public.merge_duplicate_products_for_game(p_game_id bigint)
create or replace function public.merge_duplicate_products_for_game(
  p_game_id bigint
)
returns bigint
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_canonical_id bigint;
  r record;
begin
  if p_game_id is null then
    return null;
  end if;

  select min(id) into v_canonical_id
  from public.product
  where game_id = p_game_id;

  if v_canonical_id is null then
    return null;
  end if;

  for r in
    select id
    from public.product
    where game_id = p_game_id
      and id <> v_canonical_id
    order by id
  loop
    perform public.merge_product_into(r.id, v_canonical_id);
  end loop;

  update public.listing
  set product_id = v_canonical_id
  where game_id = p_game_id
    and (product_id is null or product_id <> v_canonical_id);

  update public.listing
  set game_id = p_game_id
  where product_id = v_canonical_id
    and game_id is null;

  return v_canonical_id;
end;
$$;

revoke all on function public.merge_duplicate_products_for_game(bigint) from public;
grant execute on function public.merge_duplicate_products_for_game(bigint) to service_role;

-- Step E: One-time DB deduplication & orphan listing linking
do $$
declare
  r record;
  r_norm record;
  r_dup record;
  v_canonical_id bigint;
begin
  set local statement_timeout = '120s';

  -- 1. Merge all existing multi-product games
  for r in
    select distinct game_id
    from public.product
    where game_id is not null
    group by game_id
    having count(*) > 1
  loop
    perform public.merge_duplicate_products_for_game(r.game_id);
  end loop;

  -- 2. Materialize normalized titles ONCE per product row to avoid O(N^2) regex evaluations
  create temp table tmp_prod_norm on commit drop as
  select
    id,
    game_id,
    old_norm,
    case
      when raw_norm = 'adult sts' then 'adult shits'
      else raw_norm
    end as new_norm
  from (
    select
      id,
      game_id,
      normalized_title as old_norm,
      public.normalize_collector_title(coalesce(title, normalized_title)) as raw_norm
    from public.product
  ) s;

  create index on tmp_prod_norm (new_norm);
  create index on tmp_prod_norm (old_norm);

  -- 2a. Resolve collisions (new_norm with >1 row in tmp_prod_norm, or colliding with an existing old_norm)
  for r_norm in
    select t.new_norm
    from tmp_prod_norm t
    where t.new_norm <> ''
    group by t.new_norm
    having count(*) > 1
       or exists (
         select 1
         from tmp_prod_norm t2
         where t2.old_norm = t.new_norm
           and t2.new_norm <> t.new_norm
       )
    order by t.new_norm
  loop
    select t.id into v_canonical_id
    from tmp_prod_norm t
    join public.product p on p.id = t.id
    where t.new_norm = r_norm.new_norm
       or p.normalized_title = r_norm.new_norm
    order by (p.game_id is not null) desc, t.id asc
    limit 1;

    if v_canonical_id is not null then
      for r_dup in
        select t.id
        from tmp_prod_norm t
        join public.product p on p.id = t.id
        where (t.new_norm = r_norm.new_norm or p.normalized_title = r_norm.new_norm)
          and t.id <> v_canonical_id
        order by t.id asc
      loop
        perform public.merge_product_into(r_dup.id, v_canonical_id);
      end loop;

      update public.product
      set normalized_title = r_norm.new_norm,
          updated_at = now()
      where id = v_canonical_id
        and normalized_title is distinct from r_norm.new_norm;
    end if;
  end loop;

  -- 2b. Single set-based update for non-colliding product rows whose normalized_title changed
  update public.product p
  set normalized_title = t.new_norm,
      updated_at = now()
  from tmp_prod_norm t
  where p.id = t.id
    and t.new_norm <> ''
    and p.normalized_title is distinct from t.new_norm;

  -- 3. Recompute normalized_title on public.listing (computing normalize_collector_title ONCE per row)
  with computed as (
    select
      id,
      case
        when raw_norm = 'adult sts' then 'adult shits'
        else raw_norm
      end as new_norm
    from (
      select
        id,
        public.normalize_collector_title(title) as raw_norm
      from public.listing
    ) s
  )
  update public.listing l
  set normalized_title = c.new_norm
  from computed c
  where l.id = c.id
    and c.new_norm <> ''
    and l.normalized_title is distinct from c.new_norm;

  -- 4. Link all orphan listings (where product_id is null)
  -- 4a. First by game_id
  update public.listing l
  set product_id = p.id
  from public.product p
  where l.product_id is null
    and l.game_id is not null
    and p.game_id = l.game_id;

  -- 4b. Next by normalized_title
  update public.listing l
  set product_id = p.id
  from public.product p
  where l.product_id is null
    and l.normalized_title is not null
    and l.normalized_title = p.normalized_title;

  -- 4c. Insert missing products for any remaining orphan listings and link them
  insert into public.product (
    normalized_title,
    title,
    url,
    price,
    currency,
    release_date,
    description,
    image_url,
    game_id
  )
  select distinct on (l.normalized_title)
    l.normalized_title,
    l.title,
    l.url,
    l.price,
    l.currency,
    l.release_date,
    g.description,
    l.image_url,
    l.game_id
  from public.listing l
  left join public.game g on g.id = l.game_id
  where l.product_id is null
    and l.normalized_title is not null
    and l.normalized_title <> ''
  order by l.normalized_title, l.last_seen_at desc nulls last, l.id desc
  on conflict (normalized_title) do nothing;

  update public.listing l
  set product_id = p.id
  from public.product p
  where l.product_id is null
    and l.normalized_title is not null
    and l.normalized_title = p.normalized_title;

  -- 4d. Propagate game_id between linked product and listing (both directions)
  update public.listing l
  set game_id = p.game_id
  from public.product p
  where l.product_id = p.id
    and l.game_id is null
    and p.game_id is not null;

  update public.product p
  set game_id = sub.game_id,
      updated_at = now()
  from (
    select distinct on (l.product_id)
      l.product_id,
      l.game_id
    from public.listing l
    where l.product_id is not null
      and l.game_id is not null
    order by l.product_id, l.last_seen_at desc nulls last, l.id desc
  ) sub
  where p.id = sub.product_id
    and p.game_id is null;

  -- Final safety pass: merge any products that just inherited the same game_id
  for r in
    select distinct game_id
    from public.product
    where game_id is not null
    group by game_id
    having count(*) > 1
  loop
    perform public.merge_duplicate_products_for_game(r.game_id);
  end loop;

  update public.listing l
  set game_id = p.game_id
  from public.product p
  where l.product_id = p.id
    and l.game_id is null
    and p.game_id is not null;
end;
$$;
