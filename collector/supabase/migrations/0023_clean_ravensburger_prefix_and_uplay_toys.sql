-- 0023_clean_ravensburger_prefix_and_uplay_toys.sql
-- Idempotent cleanup extending 0021_purge_ravensburger_non_games_and_uplay_merch.sql:
-- 1. Updates public.is_non_boardgame_title(p_title text) adding \mpuzzle\s+\d+\s*pz\M.
-- 2. Purges Ravensburger kids/travel games, non-boardgame titles, and Uplay toys/merch
--    (by non-boardgame publisher or toy title regex) while strictly preserving all active
--    user favorites.
-- 3. Strips leading ^Ravensburger\s+ prefix from listing and product titles, recomputing
--    normalized_title and merging any resulting duplicate products via merge_product_into.

-- Step 1: Canonical non-boardgame title classifier in SQL (1:1 with collector/src/normalize.ts)
create or replace function public.is_non_boardgame_title(p_title text)
returns boolean
language sql
immutable
set search_path = public, pg_temp
as $$
  select
    p_title is not null
    and btrim(p_title) <> ''
    and (
      -- 1. Merch & Gadget (apparel, accessories, stickers, cards, vouchers, stationery)
      p_title ~* '\m(?:t-?shirt|magliett[ae]|felp[ae]|cappellin[oi]|tazz[ae]|mug|shopper|portachiavi|spill[ae]|poster|peluche|plush|funko\s+pop|lampada|calendario?\s+dell[''’]?avvento|set\s+sticker|sticker|adesiv[oi]|bigliett[oi]\s+auguri|gift\s*card|buono\s+regalo|set\s+cancelleria)\M'
      -- 2. Sleeves, Bustine, Binder, Playmat, Deck Box
      or p_title ~* '\m(?:bustin[ae]|buste\s+protettive|box\s+\d+\s*buste|blister\s+\d+\s*buste|sleeves?|foderin[ae]|binder|album\s+porta\s*carte|portamazz[oi]|deck\s*box|play\s*mat|playmat|tappetin[oi])\M'
      -- 3. Puzzles & Toys (BRIO / GraviTrax / CreArt / tiptoi / 3D & Ravensburger puzzles)
      --    (must NOT match real board games like "The Voynich Puzzle" or "My Puzzle Adventure")
      or p_title ~* '\m(?:puzzle\s+\d+\s*pezzi|puzzle\s+[23]d|3d\s+puzzle|puzzle\s+ball|puzzle\s+da\s+viaggio|boardgame\s+puzzle|ravensburger\s+puzzle|puzzle\s+ravensburger|maxi\s+puzzle|\d+\s+puzzle|puzzle\s+\d+p|geopuzzle|gravitrax|treno\s+(?:della|ad\s+alta|merci)|binari|pista\s+di\s+biglie|action\s+figure|creart|tiptoi)\M'
      or p_title ~* '\mpuzzle\s+\d+\s*pz\M'
      -- 4. TCG Packs / Displays / Starter Decks
      or p_title ~* '\m(?:booster\s+(?:pack|box|display|set)|extra\s+booster|prerelease\s+box|spotlight\s+deck|starter\s+deck|structure\s+deck|theme\s+deck|display\s+\d+\s*buste|elite\s+trainer|tin\s+box)\M'
      or p_title ~* '\mbooster\s*\(\d+\)'
      -- 5. Painting Minis & RPG / GDR
      or p_title ~* '\m(?:nolzur[''’]?s?\s+marvelous\s+minis|deep\s+cuts\s+minis|gdr|gioco\s+di\s+ruolo|schermo\s+del\s+(?:master|gm|narratore)|kit\s+del\s+game\s+master|manuale\s+(?:base|del|gdr|rpg)|modulo\s+avventura|avventura\s+gdr|rpg)\M'
      -- 6. Dice Towers, Dice Trays, Dice Bags & Dice Accessories
      --    (keeps real dice games like "Ra: The Dice Game", "7 Wonders Dice", "Florenza - Dice Game",
      --     "Dadi & Affetta", "Dice Forge", "Dice Town", "Dice Throne", "Dicequest", "Sushi Dice",
      --     "Dice Scrapers", and promos like "Ratti di Wistar - The Dice Tower Invention Card")
      or p_title ~* '\m(?:torre\s+(?:lancia\s+)?dadi|dice\s+tray|vassoio?\s+(?:lancia|porta)?\s*dadi|dice\s+bag|dice\s+pouch|sacchetto\s+(?:porta)?dadi|dice\s+set|dice\s+block|set\s+\d+\s+dadi|set\s+(?:di\s+)?dadi|dadi\s+custom|\d+d\d+|dice\s+(?:case|pack|tube|tin)|with\s+\d+\s+dice|adventure\s+dice)\M'
      or (
        p_title ~* '\mdice\s+tower\M'
        and p_title !~* '\mdice\s+tower\s+(?:invention|promo|card)\M'
      )
      -- 7. Pantasy & Modular Building Bricks
      or p_title ~* '\m(?:pan\d{4,}[a-z]*|pantasy|edifici\s+modulari)\M'
      -- 8. Lemax Villages & 4.5V Power Adapters
      or p_title ~* '\m(?:lemax|(?:adattatore|alimentatore)\s+4\.5v)\M'
      -- 9. LEGO Sets (keeps LEGO board games such as Monkey Palace & Destiny's Bounty)
      or (
        p_title ~* '\mlego\M'
        and p_title !~* '(?:gioco\s+da\s+tavolo|board\s+game|monkey\s+palace|destiny[''’]?s\s+bounty)'
      )
      -- 10. Funko variants
      or p_title ~* '\m(?:funko|bitty\s+pop|pocket\s+pop|pop\s+dlx|pop\s+keychain)\M'
      -- 11. Statuettes / Blind Box / Bag Clip
      or p_title ~* '\m(?:blind\s+box|bag\s+clip|figurina|q\s+posket)\M'
      or p_title ~* '\(\s*figura\s*\)'
      -- 12. Brick construction SKUs
      or p_title ~* '\mkp\d{4,}[a-z]*\M'
      or p_title ~* '^\s*\d{5}\s*-\s+'
      -- 13. TCG bundles & piece counts
      or p_title ~* '\m(?:bundle\s+\d+\s*buste|\d+\s+pezzi)\M'
    );
$$;

grant execute on function public.is_non_boardgame_title(text) to anon, authenticated, service_role;

-- Step 2: Single-pass set-based cleanup & Ravensburger prefix normalization
do $$
declare
  v_fav_game_ids bigint[];
  v_fav_product_ids bigint[];
  v_fav_listing_ids bigint[];
  v_deleted_listings bigint := 0;
  v_deleted_products bigint := 0;
  v_stripped_listings bigint := 0;
  v_merged_products bigint := 0;
  v_updated_products bigint := 0;
  v_existing_id bigint;
  r_rav record;
begin
  set local statement_timeout = '120s';

  -- 2a. Compute active favorites from user_action, respecting the latest
  --     favorite/unfavorite action per target key (matching 0021 & collector/src/db.ts).
  with latest_fav_actions as (
    select distinct on (
      coalesce(ua.game_id, -1),
      coalesce(ua.product_id, -1),
      coalesce(ua.listing_id, -1)
    )
      ua.game_id,
      ua.product_id,
      ua.listing_id,
      ua.action
    from public.user_action ua
    where ua.action in ('favorite', 'unfavorite')
    order by
      coalesce(ua.game_id, -1),
      coalesce(ua.product_id, -1),
      coalesce(ua.listing_id, -1),
      ua.created_at desc,
      ua.id desc
  ),
  active_favorites as (
    select game_id, product_id, listing_id
    from latest_fav_actions
    where action = 'favorite'
  )
  select
    coalesce(array_agg(distinct game_id) filter (where game_id is not null), '{}'::bigint[]),
    coalesce(array_agg(distinct product_id::bigint) filter (where product_id is not null), '{}'::bigint[]),
    coalesce(array_agg(distinct listing_id) filter (where listing_id is not null), '{}'::bigint[])
  into v_fav_game_ids, v_fav_product_ids, v_fav_listing_ids
  from active_favorites;

  -- Also protect products whose game_id is an active favorite so listings linked
  -- only via product_id -> product.game_id are preserved as well.
  if cardinality(v_fav_game_ids) > 0 then
    select coalesce(
      array(
        select distinct pid
        from (
          select unnest(v_fav_product_ids) as pid
          union
          select p.id::bigint as pid
          from public.product p
          where p.game_id is not null
            and p.game_id::bigint = any(v_fav_game_ids)
        ) t
      ),
      '{}'::bigint[]
    )
    into v_fav_product_ids;
  end if;

  -- 2b. Clean Uplay toys & Ravensburger kids/travel games (preserving active favorites)
  -- Child rows in listing_price_history, user_action, and user_alert cascade via ON DELETE CASCADE.
  delete from public.listing l
  where (
    (
      l.source_id = 'ravensburger'
      and (
        coalesce(l.url, '') ilike '%/games-for-kids/%'
        or coalesce(l.url, '') ilike '%/travel-games/%'
      )
    )
    or public.is_non_boardgame_title(l.title)
    or (
      l.source_id = 'uplay'
      and (
        exists (
          select 1
          from public.product p
          where p.id = l.product_id
            and lower(coalesce(p.publisher, '')) in (
              'two dots',
              'toylab',
              'zuru',
              'waboba',
              'bg import',
              'playmobil',
              'mga entertainment',
              'giochi preziosi',
              'bburago',
              'jada',
              'stopuzzle',
              'sd toys',
              'ubisoft',
              'semic',
              'cinereplicas',
              'cosmic group',
              'giunti',
              'the-pokemon-company'
            )
        )
        or l.title ~* '\m(?:nerf|play-?doh|lol\s+surprise|radiocomandat[oa]|die-?cast|scala\s+1:\d+|cartellina\s+trasparente|borraccia|lunch\s+box|masking\s+tape|portafoglio\s+bifold|calamita|orologio\s+da\s+tavolo|fashion\s+doll|vestito\s+deluxe|trapano\s+con\s+funzioni|trolls\s+dj|top\s+wing|torre\s+di\s+raperonzolo|super\s+navicella)\M'
      )
    )
  )
    and not (l.id = any(v_fav_listing_ids))
    and not (l.product_id is not null and l.product_id::bigint = any(v_fav_product_ids))
    and not (l.game_id is not null and l.game_id = any(v_fav_game_ids));
  get diagnostics v_deleted_listings = row_count;

  -- Delete orphaned products (no remaining listings, not protected by favorites)
  -- matching non-boardgame titles, non-boardgame publishers, Uplay toy titles,
  -- or Ravensburger kids/travel URLs.
  -- Child rows in user_action and user_alert cascade via ON DELETE CASCADE.
  delete from public.product p
  where not exists (
    select 1
    from public.listing l
    where l.product_id = p.id
  )
    and (
      public.is_non_boardgame_title(p.title)
      or lower(coalesce(p.publisher, '')) in (
        'two dots',
        'toylab',
        'zuru',
        'waboba',
        'bg import',
        'playmobil',
        'mga entertainment',
        'giochi preziosi',
        'bburago',
        'jada',
        'stopuzzle',
        'sd toys',
        'ubisoft',
        'semic',
        'cinereplicas',
        'cosmic group',
        'giunti',
        'the-pokemon-company'
      )
      or (
        coalesce(p.url, '') ilike '%ravensburger%'
        and (
          coalesce(p.url, '') ilike '%/games-for-kids/%'
          or coalesce(p.url, '') ilike '%/travel-games/%'
        )
      )
      or (
        coalesce(p.url, '') ilike '%uplay.it%'
        and p.title ~* '\m(?:nerf|play-?doh|lol\s+surprise|radiocomandat[oa]|die-?cast|scala\s+1:\d+|cartellina\s+trasparente|borraccia|lunch\s+box|masking\s+tape|portafoglio\s+bifold|calamita|orologio\s+da\s+tavolo|fashion\s+doll|vestito\s+deluxe|trapano\s+con\s+funzioni|trolls\s+dj|top\s+wing|torre\s+di\s+raperonzolo|super\s+navicella)\M'
      )
    )
    and not (p.id::bigint = any(v_fav_product_ids))
    and not (p.game_id is not null and p.game_id::bigint = any(v_fav_game_ids));
  get diagnostics v_deleted_products = row_count;

  -- 2c. Strip leading ^Ravensburger\s+ prefix from public.listing.title and recompute normalized_title
  with stripped as (
    select
      l.id,
      btrim(regexp_replace(l.title, '^ravensburger\s+', '', 'i')) as new_title
    from public.listing l
    where l.title ~* '^ravensburger\s+\S'
  )
  update public.listing l
  set title = s.new_title,
      normalized_title = coalesce(nullif(public.normalize_collector_title(s.new_title), ''), l.normalized_title)
  from stripped s
  where l.id = s.id
    and s.new_title <> '';
  get diagnostics v_stripped_listings = row_count;

  -- 2d. Materialize stripped Ravensburger product titles ONCE per row and merge/update
  drop table if exists tmp_rav_norm;

  create temp table tmp_rav_norm on commit drop as
  select
    p.id,
    p.game_id,
    s.new_title,
    public.normalize_collector_title(s.new_title) as new_norm
  from public.product p
  cross join lateral (
    select btrim(regexp_replace(p.title, '^ravensburger\s+', '', 'i')) as new_title
  ) s
  where p.title ~* '^ravensburger\s+\S'
    and s.new_title <> '';

  create index on tmp_rav_norm (new_norm);
  create index on tmp_rav_norm (id);

  -- Process each Ravensburger-prefixed product once (O(K log N) via unique index on product.normalized_title).
  -- Preferring rows with game_id set ensures canonical metadata is retained if two rows in
  -- tmp_rav_norm collapse to the same new_norm (the first updates product.normalized_title,
  -- and any subsequent row with the same new_norm finds it via the unique index and merges into it).
  for r_rav in
    select id, new_title, new_norm
    from tmp_rav_norm
    where new_norm <> ''
    order by (game_id is not null) desc, id asc
  loop
    if not exists (select 1 from public.product where id = r_rav.id) then
      continue;
    end if;

    select p.id into v_existing_id
    from public.product p
    where p.normalized_title = r_rav.new_norm
      and p.id <> r_rav.id
    order by p.id asc
    limit 1;

    if v_existing_id is not null then
      perform public.merge_product_into(r_rav.id, v_existing_id);
      v_merged_products := v_merged_products + 1;
    else
      update public.product
      set title = r_rav.new_title,
          normalized_title = r_rav.new_norm,
          updated_at = now()
      where id = r_rav.id;
      v_updated_products := v_updated_products + 1;
    end if;
  end loop;

  raise notice '0023_clean_ravensburger_prefix_and_uplay_toys: deleted % listing(s) and % product(s), stripped % listing(s), updated % product(s), merged % duplicate product(s) (protected favorites: % game(s), % product(s), % listing(s))',
    v_deleted_listings,
    v_deleted_products,
    v_stripped_listings,
    v_updated_products,
    v_merged_products,
    cardinality(v_fav_game_ids),
    cardinality(v_fav_product_ids),
    cardinality(v_fav_listing_ids);
end;
$$;
