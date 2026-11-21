-- 0013_app_write_rpcs.sql
-- P2.2 (ADDITIVE & SAFE): Security-definer RPCs for Android app write flows in SupabaseApi.kt:
--   1. public.update_game_bgg_stats
--   2. public.upsert_meeple_app_listing
--   3. public.link_listing_to_bgg
--   4. public.save_game_to_database
--
-- IMPORTANT (Decision A3):
-- Existing direct `anon` RLS write policies on `public.game` and `public.listing` are
-- intentionally NOT dropped or restricted in this migration because the Android app has
-- not yet been migrated to call these RPCs. Revocation of direct `anon` write policies
-- MUST happen in a follow-up migration only after the Android app migration ships.

-- 1. Update BGG stats (rating, rank, description) on an existing game by bgg_id.
create or replace function public.update_game_bgg_stats(
  p_bgg_id integer,
  p_bgg_rating numeric default null,
  p_bgg_rank integer default null,
  p_description text default null
)
returns bigint
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_game_id bigint;
  v_description text;
begin
  if p_bgg_id is null or p_bgg_id <= 0 then
    raise exception 'p_bgg_id must be a positive integer';
  end if;
  if p_bgg_rating is not null and (p_bgg_rating < 0 or p_bgg_rating > 10) then
    raise exception 'p_bgg_rating must be between 0 and 10';
  end if;
  if p_bgg_rank is not null and p_bgg_rank <= 0 then
    raise exception 'p_bgg_rank must be a positive integer';
  end if;

  v_description := nullif(btrim(p_description), '');

  if p_bgg_rating is null and p_bgg_rank is null and v_description is null then
    raise exception 'At least one of p_bgg_rating, p_bgg_rank, or p_description must be provided';
  end if;

  update public.game
  set bgg_rating = coalesce(p_bgg_rating, bgg_rating),
      bgg_rank = coalesce(p_bgg_rank, bgg_rank),
      description = coalesce(v_description, description)
  where bgg_id = p_bgg_id
  returning id into v_game_id;

  if v_game_id is null then
    raise exception 'No game found for bgg_id %', p_bgg_id;
  end if;

  return v_game_id;
end;
$$;

revoke all on function public.update_game_bgg_stats(integer, numeric, integer, text) from public;
grant execute on function public.update_game_bgg_stats(integer, numeric, integer, text) to anon, authenticated;

-- 2. Upsert a synthetic 'meeple_app' listing for a BGG game/product.
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
    v_url,
    v_image_url,
    p_game_id,
    p_product_id,
    1.0
  )
  on conflict (source_id, external_id) do update
    set title = excluded.title,
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

-- 3. Link an existing listing (or create a meeple_app listing when p_listing_id <= 0) to a BGG game & product.
create or replace function public.link_listing_to_bgg(
  p_listing_id bigint,
  p_bgg_id integer,
  p_title text,
  p_current_game_id bigint default null,
  p_kind text default 'base',
  p_bgg_rating numeric default null,
  p_bgg_rank integer default null,
  p_image_url text default null,
  p_description text default null
)
returns table (
  game_id bigint,
  product_id bigint
)
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_game_id bigint;
  v_product_id bigint;
  v_title text;
  v_image_url text;
  v_description text;
begin
  if p_bgg_id is null or p_bgg_id <= 0 then
    raise exception 'p_bgg_id must be a positive integer';
  end if;

  v_title := nullif(btrim(p_title), '');
  if v_title is null then
    raise exception 'p_title must not be empty';
  end if;

  if p_kind is null or p_kind not in ('base', 'expansion') then
    raise exception 'p_kind must be ''base'' or ''expansion''';
  end if;
  if p_bgg_rating is not null and (p_bgg_rating < 0 or p_bgg_rating > 10) then
    raise exception 'p_bgg_rating must be between 0 and 10';
  end if;
  if p_bgg_rank is not null and p_bgg_rank <= 0 then
    raise exception 'p_bgg_rank must be a positive integer';
  end if;

  v_image_url := nullif(btrim(p_image_url), '');
  v_description := nullif(btrim(p_description), '');

  if p_current_game_id is not null and p_current_game_id > 0 then
    update public.game
    set name = v_title,
        kind = p_kind,
        bgg_id = p_bgg_id,
        bgg_rating = p_bgg_rating,
        bgg_rank = p_bgg_rank,
        image_url = v_image_url,
        description = coalesce(v_description, description)
    where id = p_current_game_id
    returning id into v_game_id;

    if v_game_id is null then
      raise exception 'No game found for p_current_game_id %', p_current_game_id;
    end if;
  else
    insert into public.game (
      name,
      kind,
      bgg_id,
      bgg_rating,
      bgg_rank,
      image_url,
      description
    )
    values (
      v_title,
      p_kind,
      p_bgg_id,
      p_bgg_rating,
      p_bgg_rank,
      v_image_url,
      v_description
    )
    on conflict (bgg_id) do update
      set name = excluded.name,
          kind = excluded.kind,
          bgg_rating = excluded.bgg_rating,
          bgg_rank = excluded.bgg_rank,
          image_url = excluded.image_url,
          description = coalesce(excluded.description, public.game.description)
    returning id into v_game_id;
  end if;

  select p.id into v_product_id
  from public.product p
  where p.game_id = v_game_id
  order by p.id
  limit 1;

  if v_product_id is null then
    v_product_id := public.upsert_product_for_game(
      v_game_id,
      v_title,
      null,
      v_image_url,
      v_description
    );
  end if;

  if p_listing_id is not null and p_listing_id > 0 then
    update public.listing
    set game_id = v_game_id,
        product_id = v_product_id
    where id = p_listing_id;

    if not found then
      raise exception 'No listing found for p_listing_id %', p_listing_id;
    end if;
  else
    perform public.upsert_meeple_app_listing(
      p_bgg_id,
      v_title,
      'https://boardgamegeek.com/boardgame/' || p_bgg_id::text,
      v_game_id,
      v_product_id,
      v_image_url
    );
  end if;

  return query select v_game_id, v_product_id;
end;
$$;

revoke all on function public.link_listing_to_bgg(bigint, integer, text, bigint, text, numeric, integer, text, text) from public;
grant execute on function public.link_listing_to_bgg(bigint, integer, text, bigint, text, numeric, integer, text, text) to anon, authenticated;

-- 4. Save a BGG game to the database, upsert its product, link selected listings, and upsert the meeple_app listing.
create or replace function public.save_game_to_database(
  p_bgg_id integer,
  p_title text,
  p_kind text default 'base',
  p_bgg_rating numeric default null,
  p_bgg_rank integer default null,
  p_image_url text default null,
  p_game_description text default null,
  p_product_description text default null,
  p_listing_ids_to_link bigint[] default '{}'::bigint[]
)
returns bigint
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_game_id bigint;
  v_product_id bigint;
  v_title text;
  v_image_url text;
  v_game_description text;
  v_product_description text;
  v_item_url text;
begin
  if p_bgg_id is null or p_bgg_id <= 0 then
    raise exception 'p_bgg_id must be a positive integer';
  end if;

  v_title := nullif(btrim(p_title), '');
  if v_title is null then
    raise exception 'p_title must not be empty';
  end if;

  if p_kind is null or p_kind not in ('base', 'expansion') then
    raise exception 'p_kind must be ''base'' or ''expansion''';
  end if;
  if p_bgg_rating is not null and (p_bgg_rating < 0 or p_bgg_rating > 10) then
    raise exception 'p_bgg_rating must be between 0 and 10';
  end if;
  if p_bgg_rank is not null and p_bgg_rank <= 0 then
    raise exception 'p_bgg_rank must be a positive integer';
  end if;

  v_image_url := nullif(btrim(p_image_url), '');
  v_game_description := nullif(btrim(p_game_description), '');
  v_product_description := nullif(btrim(p_product_description), '');
  v_item_url := 'https://boardgamegeek.com/boardgame/' || p_bgg_id::text;

  insert into public.game (
    name,
    kind,
    bgg_id,
    bgg_rating,
    bgg_rank,
    image_url,
    description
  )
  values (
    v_title,
    p_kind,
    p_bgg_id,
    p_bgg_rating,
    p_bgg_rank,
    v_image_url,
    v_game_description
  )
  on conflict (bgg_id) do update
    set name = excluded.name,
        kind = excluded.kind,
        bgg_rating = coalesce(excluded.bgg_rating, public.game.bgg_rating),
        bgg_rank = coalesce(excluded.bgg_rank, public.game.bgg_rank),
        image_url = coalesce(excluded.image_url, public.game.image_url),
        description = coalesce(excluded.description, public.game.description)
  returning id into v_game_id;

  v_product_id := public.upsert_product_for_game(
    v_game_id,
    v_title,
    v_item_url,
    v_image_url,
    coalesce(v_product_description, v_game_description)
  );

  if p_listing_ids_to_link is not null and cardinality(p_listing_ids_to_link) > 0 then
    update public.listing
    set game_id = v_game_id,
        product_id = v_product_id
    where id = any(p_listing_ids_to_link)
      and id > 0;
  end if;

  perform public.upsert_meeple_app_listing(
    p_bgg_id,
    v_title,
    v_item_url,
    v_game_id,
    v_product_id,
    v_image_url
  );

  return v_game_id;
end;
$$;

revoke all on function public.save_game_to_database(integer, text, text, numeric, integer, text, text, text, bigint[]) from public;
grant execute on function public.save_game_to_database(integer, text, text, numeric, integer, text, text, text, bigint[]) to anon, authenticated;
