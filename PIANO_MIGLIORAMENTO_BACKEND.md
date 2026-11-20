# Piano di Miglioramento — Meeple Radar (Backend, Collector, Supabase & CI)

> **Ambito:** `collector/`, `collector/supabase/migrations/`, `.github/workflows/` e igiene del repository root `Appgiochi/`.
> **Documento gemello (app Android):** `android/PIANO_MIGLIORAMENTO_APP.md`.
> **Stack attuale:** Node 24 + TypeScript (`tsx`, `vitest`, `cheerio`, `zod`), `@supabase/supabase-js`, GitHub Actions (`collect.yml`), Supabase PostgreSQL + PostgREST.

---

## Sintesi diagnostica

Il collector è ben strutturato per adattatori modulari e ha una suite Vitest dedicata, ma dall'analisi del codice emergono **bug attivi in produzione**, colli di bottiglia significativi e miglioramenti chiave su sicurezza, copertura fonti e automazione:

1. **Job `collection-sync` rotto e duplicato in CI:** `.github/workflows/collect.yml` lancia `npm run collect` (`collector/src/run.ts`) in due job paralleli, ma `main()` in `src/run.ts` non passa `collection` a `runCollector` → il secondo job rifà uno scraping completo dei negozi senza CSV BGG e **senza sincronizzare la collezione BGG**.
2. **Bug nel parser BGG di `collector/src/bgg/collection.ts`:** le regex non catturano `<name sortindex="1">...</name>` né `id="..."` sui link `/xmlapi2/thing` delle espansioni.
3. **Collo di bottiglia N+1 su Supabase e scraping sequenziale:** `run.ts` scansiona una fonte alla volta e per ogni singolo listing esegue 6+ round-trip HTTP sequenziali verso Supabase; inoltre `data/bgg-online-skipped.json` va perso ad ogni run CI.
4. **Resilienza scraper e fonti disabilitate:** `ravensburger`, `uplay` e `magicmerchant` sono ferme per Cloudflare/Jina; `http.ts` nasconde errori HTTP senza retry/backoff e non esistono alert per fonti a `0` risultati consecutivi.
5. **Database & Sicurezza:** mancano indici critici su `listing` e `user_action`, `user_action` cresce senza compattazione, mancano RPC sicure per alcune scritture dell'app, e mancano storico prezzi e pipeline notifiche (`notification_log` già presente ma inutilizzata).

---

## Priorità P0 — Bug Critici da Risolvere Subito

### P0.1 — Fix del workflow CI e separazione dello script `collection-sync`
- **File coinvolti:** `.github/workflows/collect.yml`, `collector/package.json`, `collector/src/run.ts`, nuovo `collector/src/run-collection.ts`.
- **Problema:**
  - In `collect.yml`, sia il job `collect` sia `collection-sync` eseguono `npm run collect`.
  - In `collector/src/run.ts`, `main()` non configura né passa `collection` a `runCollector(...)`: il job `collection-sync` quindi non sincronizza la collezione BGG e riesegue tutto lo scraping dei negozi in parallelo al primo job (con race condition su Supabase!).
  - I due cron `0 6 * * 6` e `0 7 * * *` causano una doppia esecuzione ogni sabato a distanza di 1 ora.
- **Soluzione:**
  - Creare uno script dedicato `npm run collect:collection` (`src/run-collection.ts`) che sincronizza **solo** `collection_item` da BGG + `runAutoFavorites`, senza riscansionare i negozi.
  - Eseguire `collection-sync` dopo `collect` (`needs: collect`) oppure su schedule dedicata, eliminando il cron ridondante del sabato.
  - Aggiungere `concurrency: { group: collect, cancel-in-progress: false }` nel workflow per evitare esecuzioni sovrapposte.

### P0.2 — Fix dei bug in `collector/src/bgg/collection.ts`
- **File coinvolti:** `collector/src/bgg/collection.ts` + relativi test in `collector/test/`.
- **Problemi verificati:**
  1. `NAME_RE = /<name>(?:<!\[CDATA\[)?\s*([^<]*?)\s*(?:\]\]>)?\s*<\/name>/i` non accetta attributi sul tag `<name>` (BGG restituisce `<name sortindex="1">Titolo</name>`).
     - **Fix:** `/<name\b[^>]*>(?:<!\[CDATA\[)?\s*([^<]*?)\s*(?:\]\]>)?\s*<\/name>/i`.
  2. `parseExpandsBggId` cerca `objectid="(\d+)"` dentro `<link type="boardgameexpansion" ... inbound="true"/>`, ma BGG XML API2 usa `id="(\d+)"`.
     - **Fix:** leggere `id="(\d+)"` (con eventuale fallback `objectid`).
  3. `fetchExpandsBggId` interroga `/xmlapi2/thing?id=${bggId}&type=boardgame`, che può escludere le espansioni (`boardgameexpansion`), e non gestisce il retry su HTTP `202` (`<message>`) né su `429`.
     - **Fix:** usare `type=boardgame,boardgameexpansion`, batch fino a 20 ID per chiamata (come sull'app Android), e gestione retry su `202`/`429`.

### P0.3 — Persistenza di `bgg-online-skipped.json` in CI (o su Supabase)
- **File coinvolti:** `.github/workflows/collect.yml`, `collector/src/bgg/skipped.ts`, `collector/src/bgg/enrich.ts`.
- **Problema:** Su GitHub Actions il runner è effimero: `data/bgg-online-skipped.json` viene ricreato vuoto ad ogni run, quindi l'arricchimento online (`ONLINE_BUDGET = 10`) riprova ogni giorno sempre gli stessi primi 10 titoli non trovati, bloccando i nuovi giochi.
- **Soluzione:**
  - Breve termine: persistere `collector/data/bgg-online-skipped.json` con `actions/cache` (o su tabella Supabase `bgg_skip_cache(normalized_title primary key, skipped_at, attempts)` con TTL di 30 giorni così i giochi appena aggiunti su BGG vengono riprovati in seguito).

### P0.4 — Igiene sicurezza credenziali e Secret Scanning
- **Stato verificato:** Nessun file di credenziali (`meeple-radar-firebase-adminsdk-*.json`, `.env`, `supabase.env`, `local.properties`) è mai stato committato o pushato su GitHub; il `.gitignore` root è stato rafforzato.
- **Intervento consigliato:**
  - Spostare comunque `meeple-radar-firebase-adminsdk-*.json` fuori dalla cartella del repository (es. in una cartella locale dedicata ai segreti) per evitare rischi umani.
  - Abilitare **GitHub Secret Scanning + Push Protection** nelle impostazioni del repository GitHub.
  - Aggiungere un job leggero con `gitleaks/gitleaks-action` su `push`/`pull_request` (e un workflow CI che esegue `npm run typecheck` e `npm test` su ogni modifica a `collector/**`).

---

## Priorità P1 — Performance Collector, Database e Qualità Dati

### P1.1 — Eliminazione query N+1 in `collector/src/run.ts` e `collector/src/db.ts`
- **Problema:** Per ogni singolo listing raccolto, `run.ts` (righe 84–138) fa in sequenza:
  `findListingsByTitle` → `upsertProducts([payload])` → 3× `fillProduct*` → `findListingsByTitle` → N× `linkListingToProduct`.
  Con ~1000 listing significa 5000–7000 round-trip HTTP per run, con maggior rischio di timeout/502.
- **Soluzione:**
  - Caricare in **un'unica query batch** (`in('normalized_title', batch)`) i listing esistenti per la fonte corrente.
  - Eseguire `upsertProducts` in **batch** (es. 100–200 prodotti per chiamata) anziché 1 per volta.
  - Raggruppare `linkListingToProduct` per `product_id` con `.in('id', listingIds)`.
  - Estendere `retryOperation` anche a `findListingsByTitle`, `fillProduct*`, `createGame`, `setProductGame`, `fillListingGame`.
  - Eseguire lo scraping delle fonti con un **pool concorrente limitato** (es. concorrenza 3–4 fonti in parallelo) invece del loop sequenziale `for (const source of sources)`.

### P1.2 — Indici PostgreSQL e pulizia/compattazione `user_action` (nuova migrazione `0012_*.sql`)
- **File coinvolti:** `collector/supabase/migrations/0012_indexes_and_maintenance.sql`.
- **Intervento:**
  - Creare indici mancanti usati ad ogni run e da tutte le query dell'app:
    - `create index if not exists idx_listing_normalized_title on public.listing (normalized_title);`
    - `create index if not exists idx_listing_product_id on public.listing (product_id);`
    - `create index if not exists idx_listing_game_id on public.listing (game_id);`
    - `create index if not exists idx_listing_release_date on public.listing (release_date);`
    - `create index if not exists idx_listing_last_seen_at on public.listing (last_seen_at);`
    - `create index if not exists idx_user_action_created_desc on public.user_action (created_at desc, id desc);`
    - `create index if not exists idx_product_updated_at on public.product (updated_at);`
  - Aggiungere una funzione SQL periodica (chiamata in `purgeStale`) per **compattare `user_action`**:
    - Deduplicare righe `seen` duplicate per la stessa chiave (`game_id`/`listing_id`/`product_id`).
    - Rimuovere righe `seen` orfane relative a listing già eliminati da `purgeStale`.

### P1.3 — Hardening HTTP client degli adapter (`collector/src/adapters/http.ts`)
- **Problema:** `textFetcher` torna `null` su qualunque HTTP ≠ 200, senza distinzione tra `404` (pagina inesistente), `403` (Cloudflare), `429` (rate limit) e `5xx` (errore temporaneo), e senza retry.
- **Soluzione:**
  - Introdurre retry con backoff esponenziale su `429`, `502`, `503`, `504` e timeout di rete (rispettando l'header `Retry-After` se presente).
  - Loggare metriche strutturate per fonte nel `RunReport`: `httpErrorsByStatus`, `discoveredCount`, `extractedCount`, `durationMs`.

### P1.4 — Miglioramento matching BGG e copertura metadati (`collector/src/bgg/`)
- **File coinvolti:** `collector/src/normalize.ts`, `collector/src/bgg/csv-ranks.ts`, `collector/src/bgg/enrich.ts`, `collector/src/bgg/online.ts`.
- **Intervento:**
  - Raffinare la normalizzazione per il matching BGG (strip di suffissi ricorrenti nei negozi italiani: `edizione italiana`, `ed. italiana`, `italiano`, `2a edizione`, `promo`, `esclusiva`, normalizzazione accenti/apostrofi tipografici).
  - Quando `fetchGeekItem` risolve un gioco online, salvare su `game` anche `image_url`, `description` (se mancante) ed `expands_bgg_id` (per espansioni), così l'app Android ha subito copertina, descrizione e collegamento espansione↔gioco base.
  - Aumentare leggermente `ONLINE_BUDGET` (es. 25–30 richieste per run) una volta che `skipped` è persistito, per smaltire rapidamente i prodotti non ancora collegati.

---

## Priorità P2 — Copertura Fonti, Osservabilità e Sicurezza API

### P2.1 — Recupero fonti disabilitate e monitoraggio selettori HTML
- **File coinvolti:** `collector/src/sources.seed.ts`, `collector/src/adapters/headless/fetch.ts`, `collector/src/adapters/relay.ts`.
- **Intervento:**
  - Verificare `ravensburger` e `uplay` ora che `JINA_API_KEY` è configurata nei secret di `collect.yml`, aggiungendo un rate-limiter dedicato sul relay Jina per non sforare la quota al minuto.
  - Aggiungere un **controllo di salute delle fonti** a fine run (leggendo gli ultimi 3 `run_log`): se una fonte abilitata torna `0` risultati per 2+ run consecutive o fallisce ripetutamente, emettere un avviso (es. apertura/aggiornamento automatico di una GitHub Issue `scraper-health` o notifica Telegram/Discord via webhook).
  - Valutare test di contratto HTML con fixture reali salvate per gli adapter principali (`asmodee`, `dvgiochi`, `horribleguild`, `gateongames`, `dungeondice`, `houseofgames`).

### P2.2 — Consolidare le scritture dell'app dietro RPC Supabase sicure
- **File coinvolti:** `collector/supabase/migrations/0010_add_upsert_product_for_game.sql` (modello già adottato), nuove RPC per `link_listing_to_bgg` e `update_game_bgg_stats`.
- **Problema:** L'app Android usa la chiave `anon` per fare `PATCH`/`POST` diretti su `game` e `listing` (`SupabaseApi.kt`).
- **Soluzione:**
  - Spostare tutte le mutazioni consentite all'app in RPC `security definer` con validazione rigorosa degli input (come già fatto per `upsert_product_for_game`), restringendo le policy RLS di scrittura diretta da `anon`.

### P2.3 — Supporto al sync incrementale per l'app Android
- **Intervento:**
  - Aggiungere colonna `updated_at timestamptz not null default now()` con trigger `set_updated_at` su `game` e `listing` (su `product` esiste già).
  - Permettere all'app Android di scaricare solo i delta dopo l'ultimo timestamp di sincronizzazione (coordinato con `P1.2` di `android/PIANO_MIGLIORAMENTO_APP.md`).

---

## Priorità P3 — Nuove Funzionalità Backend (Storico Prezzi & Notifiche)

### P3.1 — Storico prezzi e disponibilità (`listing_price_history`)
- **Intervento:**
  - Nuova tabella `listing_price_history(id, listing_id, price, currency, availability, recorded_at)` popolata automaticamente (via trigger su `listing` o durante `upsertListings`) **solo quando prezzo o disponibilità cambiano**.
  - Esporre una vista `listing_best_price` per consentire all'app di mostrare cali di prezzo, minimo storico e ritorno in stock.

### P3.2 — Motore notifiche (`notification_log` + FCM / webhook)
- **Intervento:**
  - La tabella `notification_log` esiste già in `0001_init.sql`: creare uno step post-raccolta che identifica:
    1. Giochi preferiti passati da `preorder`/`out_of_stock` a `available` o con calo di prezzo ≥ 10%.
    2. Nuove espansioni rilevate per giochi posseduti in `collection_item`.
  - Inviare notifiche tramite Firebase Cloud Messaging (usando la service account key solo come GitHub Secret, mai nel repo) oppure esporre gli eventi su una tabella `user_alert` letta dal `WorkManager` dell'app Android.

---

## Roadmap operativa consigliata (Backend)

| Fase | Obiettivo | Elementi |
|---|---|---|
| **Sprint 1** | Fix bug critici CI & BGG | P0.1 (Separazione `collection-sync` + cron + concurrency), P0.2 (Fix regex/query `collection.ts`), P0.3 (Persistenza `skipped`), P0.4 (CI test/typecheck + secret scanning) |
| **Sprint 2** | Velocità & Database | P1.1 (Batch upsert + concorrenza fonti), P1.2 (Migrazione indici + compattazione `user_action`), P1.3 (Retry/metriche HTTP) |
| **Sprint 3** | Qualità catalogo & Fonti | P1.4 (Matching BGG + metadati completi), P2.1 (Recupero `uplay`/`ravensburger` + alert fonti a 0), P2.2–P2.3 (RPC sicure + `updated_at` per sync incrementale) |
| **Sprint 4** | Funzionalità avanzate | P3.1 (Storico prezzi), P3.2 (Alert & Notifiche) |
