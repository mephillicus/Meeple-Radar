-- 0017_backfill_price_history_and_view.sql
-- Backfill initial price/availability history for existing listings, seed missing baseline
-- on UPDATE in record_listing_price_history(), and append previous_availability to listing_best_price.

insert into public.listing_price_history (listing_id, price, currency, availability, recorded_at)
select
  l.id,
  l.price,
  l.currency,
  l.availability,
  coalesce(l.updated_at, l.last_seen_at, l.first_seen_at, now())
from public.listing l
where (l.price is not null or l.availability is not null)
  and not exists (
    select 1
    from public.listing_price_history h
    where h.listing_id = l.id
  );

create or replace function public.record_listing_price_history()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
  if tg_op = 'INSERT' then
    if new.price is not null or new.availability is not null then
      insert into public.listing_price_history (listing_id, price, currency, availability)
      values (new.id, new.price, new.currency, new.availability);
    end if;
  elsif tg_op = 'UPDATE' then
    if old.price is distinct from new.price or old.availability is distinct from new.availability then
      if (old.price is not null or old.availability is not null)
        and not exists (select 1 from public.listing_price_history where listing_id = old.id)
      then
        insert into public.listing_price_history (listing_id, price, currency, availability, recorded_at)
        values (
          old.id,
          old.price,
          old.currency,
          old.availability,
          coalesce(old.updated_at, old.last_seen_at, now() - interval '1 second')
        );
      end if;

      insert into public.listing_price_history (listing_id, price, currency, availability)
      values (new.id, new.price, new.currency, new.availability);
    end if;
  end if;

  return new;
end;
$$;

create or replace view public.listing_best_price
with (security_invoker = true)
as
with ranked as (
  select
    listing_id,
    price,
    currency,
    availability,
    recorded_at,
    row_number() over (partition by listing_id order by recorded_at desc, id desc) as rn,
    min(price) over (partition by listing_id) as min_price,
    lead(price) over (partition by listing_id order by recorded_at desc, id desc) as previous_price,
    lead(availability) over (partition by listing_id order by recorded_at desc, id desc) as previous_availability
  from public.listing_price_history
)
select
  listing_id,
  price as current_price,
  currency,
  availability as current_availability,
  min_price,
  previous_price,
  case
    when previous_price is not null
      and previous_price > 0
      and price is not null
      and price < previous_price
    then round(((previous_price - price) / previous_price) * 100, 2)
    else null
  end as price_drop_pct,
  recorded_at,
  previous_availability
from ranked
where rn = 1;

grant select on public.listing_best_price to anon, authenticated;
