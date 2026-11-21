-- 0015_price_history.sql
-- P3.1: Price & availability history table, trigger on public.listing, and best-price view.

create table if not exists public.listing_price_history (
  id bigserial primary key,
  listing_id bigint not null references public.listing(id) on delete cascade,
  price numeric(10,2),
  currency text,
  availability text,
  recorded_at timestamptz not null default now()
);

create index if not exists idx_listing_price_history_listing_recorded
  on public.listing_price_history (listing_id, recorded_at desc);

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
      insert into public.listing_price_history (listing_id, price, currency, availability)
      values (new.id, new.price, new.currency, new.availability);
    end if;
  end if;

  return new;
end;
$$;

drop trigger if exists trg_listing_record_price_history on public.listing;
create trigger trg_listing_record_price_history
  after insert or update on public.listing
  for each row
  execute function public.record_listing_price_history();

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
    lead(price) over (partition by listing_id order by recorded_at desc, id desc) as previous_price
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
  recorded_at
from ranked
where rn = 1;

alter table public.listing_price_history enable row level security;

drop policy if exists "read listing_price_history" on public.listing_price_history;
create policy "read listing_price_history"
  on public.listing_price_history
  for select
  to anon, authenticated
  using (true);

grant select on public.listing_price_history to anon, authenticated;
grant select on public.listing_best_price to anon, authenticated;
