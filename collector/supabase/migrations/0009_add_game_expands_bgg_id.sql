-- 0009_add_game_expands_bgg_id.sql

alter table game
  add column if not exists expands_bgg_id bigint;
