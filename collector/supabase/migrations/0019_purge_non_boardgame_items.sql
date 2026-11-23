-- 0019_purge_non_boardgame_items.sql
-- Idempotent cleanup of non-boardgame listings and orphaned products in Supabase,
-- mirroring collector/src/normalize.ts (isNonBoardgameTitle) 1:1 while strictly
-- preserving all active user favorites, Organizers/Inserts, Upgrade Kits/Metal Coins/
-- Deluxe Resources, and real board games.

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
      -- 3. Puzzles & Toys (BRIO / GraviTrax / CreArt / tiptoi)
      or p_title ~* '\m(?:puzzle\s+\d+\s*pezzi|puzzle\s+[23]d|puzzle\s+da\s+viaggio|boardgame\s+puzzle|ravensburger\s+puzzle|gravitrax|treno\s+(?:della|ad\s+alta|merci)|binari|pista\s+di\s+biglie|action\s+figure|creart|tiptoi)\M'
      -- 4. TCG Packs / Displays / Starter Decks
      or p_title ~* '\m(?:booster\s+(?:pack|box|display|set)|extra\s+booster|prerelease\s+box|spotlight\s+deck|starter\s+deck|structure\s+deck|theme\s+deck|display\s+\d+\s*buste|elite\s+trainer|tin\s+box)\M'
      or p_title ~* '\mbooster\s*\(\d+\)'
      -- 5. Painting Minis & RPG / GDR
      or p_title ~* '\m(?:nolzur[''’]?s?\s+marvelous\s+minis|deep\s+cuts\s+minis|gdr|gioco\s+di\s+ruolo|schermo\s+del\s+(?:master|gm|narratore)|kit\s+del\s+game\s+master|manuale\s+(?:base|del|gdr|rpg)|modulo\s+avventura|avventura\s+gdr|rpg)\M'
      -- 6. Dice Towers, Dice Trays, Dice Bags & Dice Sets
      --    (keeps real dice games like "Ra: The Dice Game", "7 Wonders Dice", "Florenza - Dice Game",
      --     and promos like "Ratti di Wistar - The Dice Tower Invention Card")
      or p_title ~* '\m(?:torre\s+(?:lancia\s+)?dadi|dice\s+tray|vassoio?\s+(?:lancia|porta)?\s*dadi|dice\s+bag|dice\s+pouch|sacchetto\s+(?:porta)?dadi|dice\s+set|dice\s+block|set\s+\d+\s+dadi|dadi\s+custom)\M'
      or (
        p_title ~* '\mdice\s+tower\M'
        and p_title !~* '\mdice\s+tower\s+(?:invention|promo|card)\M'
      )
    );
$$;

grant execute on function public.is_non_boardgame_title(text) to anon, authenticated, service_role;

-- Step 2: Single-pass set-based cleanup preserving all active user favorites
do $$
declare
  v_fav_game_ids bigint[];
  v_fav_product_ids bigint[];
  v_fav_listing_ids bigint[];
  v_deleted_listings bigint := 0;
  v_deleted_products bigint := 0;
begin
  -- Compute active favorites from user_action, respecting the latest
  -- favorite/unfavorite action per target key (matching collector/src/db.ts).
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

  -- Delete non-boardgame listings that are not protected by any active favorite.
  -- Child rows in listing_price_history, user_action, and user_alert cascade via ON DELETE CASCADE.
  delete from public.listing l
  where public.is_non_boardgame_title(l.title)
    and not (l.id = any(v_fav_listing_ids))
    and not (l.product_id is not null and l.product_id::bigint = any(v_fav_product_ids))
    and not (l.game_id is not null and l.game_id = any(v_fav_game_ids));
  get diagnostics v_deleted_listings = row_count;

  -- Delete orphaned non-boardgame products with no remaining listings and no active favorite.
  -- Child rows in user_action and user_alert cascade via ON DELETE CASCADE.
  delete from public.product p
  where public.is_non_boardgame_title(p.title)
    and not exists (
      select 1
      from public.listing l
      where l.product_id = p.id
    )
    and not (p.id::bigint = any(v_fav_product_ids))
    and not (p.game_id is not null and p.game_id::bigint = any(v_fav_game_ids));
  get diagnostics v_deleted_products = row_count;

  raise notice '0019_purge_non_boardgame_items: deleted % listing(s) and % product(s) (protected favorites: % game(s), % product(s), % listing(s))',
    v_deleted_listings,
    v_deleted_products,
    cardinality(v_fav_game_ids),
    cardinality(v_fav_product_ids),
    cardinality(v_fav_listing_ids);
end;
$$;
