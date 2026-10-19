-- 0005_user_action_product.sql
-- Aggiunge la foreign key facoltativa a product per le azioni utente (Preferito/Ignora a livello di prodotto)

alter table user_action
  add column if not exists product_id integer references product(id);

create index if not exists user_action_product_id_idx on user_action (product_id);
