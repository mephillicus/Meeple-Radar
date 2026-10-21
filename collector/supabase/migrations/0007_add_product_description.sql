-- 0007_add_product_description.sql

alter table product
  add column if not exists description text;
