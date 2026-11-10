-- Create or reuse the product attached to a game from the Android BGG flow.
-- The public app role can execute this function without direct product-table writes.
alter table public.product
  alter column game_id type bigint using game_id::bigint;

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
begin
  if p_game_id is null or p_game_id <= 0 then
    raise exception 'p_game_id must be a positive ID';
  end if;
  if p_title is null or btrim(p_title) = '' then
    raise exception 'p_title must not be empty';
  end if;

  -- Keep in sync with collector/src/normalize.ts normalizeTitle().
  v_normalized_title := lower(regexp_replace(btrim(p_title), '[^a-zA-Z0-9_[:space:]]', '', 'g'));
  v_normalized_title := regexp_replace(v_normalized_title, '[[:space:]]+', ' ', 'g');
  v_normalized_title := regexp_replace(v_normalized_title, '[[:space:]]ita$', '');
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
