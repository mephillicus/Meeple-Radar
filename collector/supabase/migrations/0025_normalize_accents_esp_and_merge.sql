-- 0025_normalize_accents_esp_and_merge.sql
-- Idempotent migration:
-- 1. Updates public.normalize_collector_title(p_title text) with 1:1 parity with collector/src/normalize.ts:
--    - Accent folding via translate() right after lower()
--    - Mid-title 'esp' / 'espansione' stripping (only when preceded and followed by a word)
-- 2. Updates public.is_non_boardgame_title(p_title text) adding 6 Uplay residue patterns:
--    unpainted miniatures, magnete flessibile, tote bag, nintendo switch / switch 2,
--    spindown d\d+, codex book folio.
-- 3. Recomputes product.normalized_title where changed and merges collisions via merge_product_into.
-- 4. Merges space-joined vs split duplicates (replace(normalized_title, ' ', '') with length >= 6).
-- 5. Recomputes listing.normalized_title via a single CTE where changed.
-- 6. Purges remaining non-boardgame residue rows matching is_non_boardgame_title while preserving
--    all active user favorites.

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
  v := translate(
    v,
    'àáâãäåèéêëìíîïòóôõöùúûüñçýÿ',
    'aaaaaaeeeeiiiiooooouuuuncyy'
  );
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

  for i in 1..2 loop
    v := regexp_replace(v, '(\S) +(?:espansione|esp) +(\S)', '\1 \2', 'g');
  end loop;

  return v;
end;
$$;

grant execute on function public.normalize_collector_title(text) to anon, authenticated, service_role;

-- Step 2: Canonical non-boardgame title classifier in SQL (1:1 with collector/src/normalize.ts)
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
      -- 14. Additional Uplay residue (unpainted minis, magnets, tote bags, Switch games, spindown dice, folios)
      or p_title ~* '\m(?:unpainted\s+miniatures|magnete\s+flessibile|tote\s+bag|nintendo\s+switch|switch\s+2|spindown\s+d\d+|codex\s+book\s+folio)\M'
    );
$$;

grant execute on function public.is_non_boardgame_title(text) to anon, authenticated, service_role;

-- Step 3: Recompute normalized_title, merge accent/esp & space-joined duplicates, sync listings, and purge residue
do $$
declare
  r_prod record;
  r_compact record;
  r_game record;
  v_existing_id bigint;
  v_updated_products bigint := 0;
  v_merged_norm_products bigint := 0;
  v_merged_compact_products bigint := 0;
  v_merged_games bigint := 0;
  v_updated_listings bigint := 0;
  v_deleted_listings bigint := 0;
  v_deleted_products bigint := 0;
  v_fav_game_ids bigint[];
  v_fav_product_ids bigint[];
  v_fav_listing_ids bigint[];
begin
  set local statement_timeout = '120s';

  -- 3a. Materialize only product rows whose normalized_title changes under the updated normalizer
  drop table if exists tmp_norm_products;

  create temp table tmp_norm_products on commit drop as
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
  ) s
  where s.new_norm <> ''
    and s.new_norm is distinct from s.old_norm;

  create index on tmp_norm_products (new_norm);
  create index on tmp_norm_products (id);

  -- Process rows with game_id set first so canonical metadata is preserved when two
  -- changed rows collapse onto the same new_norm.
  for r_prod in
    select id, new_norm
    from tmp_norm_products
    order by (game_id is not null) desc, id asc
  loop
    if not exists (select 1 from public.product where id = r_prod.id) then
      continue;
    end if;

    select p.id into v_existing_id
    from public.product p
    where p.normalized_title = r_prod.new_norm
      and p.id <> r_prod.id
    order by (p.game_id is not null) desc, p.id asc
    limit 1;

    if v_existing_id is not null then
      perform public.merge_product_into(r_prod.id, v_existing_id);
      v_merged_norm_products := v_merged_norm_products + 1;
    else
      update public.product
      set normalized_title = r_prod.new_norm,
          updated_at = now()
      where id = r_prod.id
        and normalized_title is distinct from r_prod.new_norm;
      v_updated_products := v_updated_products + 1;
    end if;
  end loop;

  -- 3b. Merge space-joined vs split duplicates (replace(normalized_title, ' ', '') with length >= 6)
  drop table if exists tmp_compact_dups;

  create temp table tmp_compact_dups on commit drop as
  with ranked as (
    select
      p.id,
      first_value(p.id) over (
        partition by replace(p.normalized_title, ' ', '')
        order by (p.game_id is not null) desc, p.id asc
      ) as canonical_id,
      count(*) over (
        partition by replace(p.normalized_title, ' ', '')
      ) as grp_cnt
    from public.product p
    where p.normalized_title is not null
      and length(replace(p.normalized_title, ' ', '')) >= 6
  )
  select id, canonical_id
  from ranked
  where grp_cnt > 1
    and id <> canonical_id
  order by canonical_id asc, id asc;

  for r_compact in
    select id, canonical_id
    from tmp_compact_dups
    order by canonical_id asc, id asc
  loop
    if exists (select 1 from public.product where id = r_compact.id)
       and exists (select 1 from public.product where id = r_compact.canonical_id) then
      perform public.merge_product_into(r_compact.id, r_compact.canonical_id);
      v_merged_compact_products := v_merged_compact_products + 1;
    end if;
  end loop;

  -- Safety pass: merge any game_id that gained a second product via coalesce in merge_product_into
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

  -- 3c. Update public.listing.normalized_title via a single CTE where changed
  with computed as (
    select
      id,
      public.normalize_collector_title(title) as new_norm
    from public.listing
  )
  update public.listing l
  set normalized_title = c.new_norm
  from computed c
  where l.id = c.id
    and c.new_norm <> ''
    and l.normalized_title is distinct from c.new_norm;
  get diagnostics v_updated_listings = row_count;

  -- 3d. Compute active user favorites to protect during residue purge
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

  -- Purge remaining non-boardgame residue listings (preserving active favorites)
  delete from public.listing l
  where public.is_non_boardgame_title(l.title)
    and not (l.id = any(v_fav_listing_ids))
    and not (l.product_id is not null and l.product_id::bigint = any(v_fav_product_ids))
    and not (l.game_id is not null and l.game_id = any(v_fav_game_ids));
  get diagnostics v_deleted_listings = row_count;

  -- Purge orphaned non-boardgame residue products (preserving active favorites)
  delete from public.product p
  where not exists (
    select 1
    from public.listing l
    where l.product_id = p.id
  )
    and public.is_non_boardgame_title(p.title)
    and not (p.id::bigint = any(v_fav_product_ids))
    and not (p.game_id is not null and p.game_id::bigint = any(v_fav_game_ids));
  get diagnostics v_deleted_products = row_count;

  raise notice '0025_normalize_accents_esp_and_merge: updated % product(s), merged % norm-dup product(s), merged % compact-dup product(s), merged % multi-product game(s), updated % listing(s), purged % listing(s) and % product(s) (protected favorites: % game(s), % product(s), % listing(s))',
    v_updated_products,
    v_merged_norm_products,
    v_merged_compact_products,
    v_merged_games,
    v_updated_listings,
    v_deleted_listings,
    v_deleted_products,
    cardinality(v_fav_game_ids),
    cardinality(v_fav_product_ids),
    cardinality(v_fav_listing_ids);
end;
$$;
