-- Meeple Radar — migrazione iniziale (Piano 00 §A.2)
-- Incolla tutto questo nell'SQL Editor di Supabase e premi Run.

create table if not exists source (
  id text primary key,
  name text not null,
  kind text not null,
  base_url text not null,
  adapter text not null,
  enabled boolean not null default true,
  last_scan_at timestamptz
);

create table if not exists game (
  id bigint generated always as identity primary key,
  name text not null,
  kind text not null default 'base',      -- 'base' | 'expansion'
  bgg_id integer,
  bgg_rating numeric,
  bgg_rank integer,
  image_url text,
  unique (bgg_id)
);

create table if not exists listing (
  id bigint generated always as identity primary key,
  source_id text not null references source(id),
  external_id text not null,
  title text not null,
  url text not null,
  price numeric,
  currency text,
  availability text,
  release_date date,
  image_url text,
  game_id bigint references game(id),
  match_confidence numeric,
  first_seen_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  unique (source_id, external_id)
);

create table if not exists user_action (
  id bigint generated always as identity primary key,
  game_id bigint references game(id),
  listing_id bigint references listing(id),
  action text not null,                    -- 'ignored' | 'favorite'
  created_at timestamptz not null default now()
);

create table if not exists collection_item (
  bgg_id integer primary key,
  name text not null,
  kind text not null default 'base',
  owned boolean not null default false,
  wishlist boolean not null default false,
  synced_at timestamptz not null default now()
);

create table if not exists notification_log (
  id bigint generated always as identity primary key,
  game_id bigint references game(id),
  kind text not null,
  sent_at timestamptz not null default now(),
  unique (game_id, kind)
);

create table if not exists run_log (
  id bigint generated always as identity primary key,
  started_at timestamptz not null default now(),
  finished_at timestamptz,
  ok boolean,
  details jsonb
);

-- Row Level Security: attiva il controllo degli accessi riga per riga.
alter table source enable row level security;
alter table listing enable row level security;
alter table game enable row level security;
alter table user_action enable row level security;
alter table collection_item enable row level security;

-- Policy = regole "chi può fare cosa".
-- L'app usa la chiave "anon" (pubblica): può SOLO leggere, tranne user_action dove può inserire.
-- Il collector usa la chiave "service_role" (segreta) che ignora queste regole.
create policy "read source"   on source        for select using (true);
create policy "read listing"  on listing       for select using (true);
create policy "read game"     on game          for select using (true);
create policy "read actions"  on user_action   for select using (true);
create policy "app may write actions" on user_action for insert with check (true);
create policy "read collection" on collection_item for select using (true);
