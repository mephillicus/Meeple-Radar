-- 0006_delete_cascade.sql
-- Senza CASCADE i DELETE di listing/product vengono rifiutati da user_action
-- e l'intero giro del collector abortisce prima di raccogliere.

alter table user_action
  drop constraint if exists user_action_listing_id_fkey;
alter table user_action
  add constraint user_action_listing_id_fkey
  foreign key (listing_id) references listing(id) on delete cascade;

alter table user_action
  drop constraint if exists user_action_product_id_fkey;
alter table user_action
  add constraint user_action_product_id_fkey
  foreign key (product_id) references product(id) on delete cascade;
