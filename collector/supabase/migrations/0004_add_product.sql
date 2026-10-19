-- 0004_add_product.sql

-- Create canonical product table
create table if not exists product (
  id serial primary key,
  normalized_title text not null unique,
  title text,
  url text,
  price numeric,
  currency text,
  release_date date,
  image_url text,
  game_id integer,
  sources jsonb,
  updated_at timestamptz default now()
);

-- Add normalized_title and product_id to listing
alter table listing
  add column if not exists normalized_title text;

alter table listing
  add column if not exists product_id integer references product(id);

-- Index for faster lookups by normalized_title
create index if not exists listing_normalized_title_idx on listing (normalized_title);
