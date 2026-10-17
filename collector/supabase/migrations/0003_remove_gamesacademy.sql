-- Meeple Radar — rimozione della fonte Games Academy / Funside (richiesta 2026-10-17).
-- Incolla tutto questo nell'SQL Editor di Supabase e premi Run.
--
-- Le chiavi esterne sono dichiarate senza ON DELETE (quindi NO ACTION): l'ordine di
-- cancellazione deve essere user_action -> listing -> source.

delete from user_action
where listing_id in (select id from listing where source_id = 'gamesacademy');

delete from listing
where source_id = 'gamesacademy';

delete from source
where id = 'gamesacademy';
