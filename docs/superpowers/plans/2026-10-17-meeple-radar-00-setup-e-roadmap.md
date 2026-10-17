# Meeple Radar — Piano 00: rendere possibile l'Opzione A (setup) + roadmap

> **Per chi esegue:** questo è il piano 00 del progetto. Prepara i servizi gratuiti e definisce gli altri piani. Segui i passi a caselle (`- [ ]`).

**Goal:** predisporre l'infrastruttura gratuita dell'Opzione A (Supabase + GitHub Actions + Firebase) e la struttura del progetto, così che ogni piano successivo parta da basi funzionanti.

**Architecture:** Job Node.js programmati su GitHub Actions raccolgono i dati delle fonti con adattatori isolati, li normalizzano e li scrivono su Supabase (Postgres + API REST autogenerata), da cui l'app Android legge. Le push passano da Firebase Cloud Messaging.

**Tech Stack:** Node.js 20 + TypeScript, Vitest, Playwright (fonti difficili), Supabase (Postgres/PostgREST), GitHub Actions, Firebase Cloud Messaging, Kotlin + Jetpack Compose (app).

**Spec:** `appgiochi/docs/superpowers/specs/2026-10-17-meeple-radar-design.md`

## Global Constraints

- Costo **zero**: restare nei tier gratuiti (GitHub Actions 2.000 min/mese su repo privati; Supabase free; FCM free).
- Progetto in `E:\Ai\appgiochi`; collector in `appgiochi/collector`.
- Linguaggio collector: **Node.js / TypeScript**.
- Nessun server sempre attivo.
- Nessun commit/push senza richiesta esplicita (regola `AGENTS.md`).
- Rispetto `robots.txt` e frequenze basse; User-Agent identificabile.
- Dati sensibili solo nei **segreti** di GitHub, mai nel repo.

## Review Focus (rischi che i test delle task non coprono)

1. **Segreti committati per errore** (chiave `service_role` di Supabase) → la `.gitignore` deve escludere `.env`; i test non lo rilevano.
2. **Pausa del progetto Supabase free** dopo ~7 giorni di inattività dell'API → il job periodico tiene il progetto attivo; va verificato.
3. **Workflow GitHub disattivato per inattività del repo** (60 giorni) → prevedere `workflow_dispatch` manuale.
4. **Rate-limit/ban delle fonti** per frequenza troppo alta → cadenze conservatrici, un adattatore per volta.
5. **Chiave FCM (service account) mal configurata** → la push fallisce silenziosamente; prevedere log.

---

## Parte A — Setup dell'Opzione A

### A.1 Panoramica dei tre servizi

```
GitHub (codice + Actions)  -->  scrive  -->  Supabase (Postgres + API REST)  <-- legge --  App Android
        |                                            ^
        +-- invia push --> Firebase Cloud Messaging -+--> App Android
```

- **Supabase** = database + API che l'app legge. Gratis.
- **GitHub Actions** = dove girerà il collector Node.js a orari. Gratis.
- **Firebase** = invio delle notifiche push all'app. Gratis.

### A.2 Supabase (database + API)

- [ ] A.2.1 Crea un account su `https://supabase.com` e un **New project** (regione: Europe, es. Francoforte). Annota la password del DB.
- [ ] A.2.2 Trova la **Project URL**: in alto a destra clicca l'ingranaggio **Settings (⚙)** → **API** (nelle versioni recenti: **Project Settings → Data API**). La *Project URL* è un indirizzo tipo `https://abcdwxyz.supabase.co` (è anche il sottodominio che vedi nell'URL della dashboard). Nella stessa pagina, sotto **API keys**, copia la chiave **anon public** (per l'app) e la chiave **service_role** (per il collector — segreta: non va mai nell'app né nel repo).
- [ ] A.2.3 Apri **SQL Editor**, incolla il contenuto di `appgiochi/collector/supabase/migrations/0001_init.sql` e premi **Run**. Le **policy** sono già incluse nel file: non devi scriverne altre. Conferma che le tabelle appaiano in **Table Editor**.
- [ ] A.2.4 (solo verifica) Vai in **Authentication → Policies** (in alcune versioni **Database → Policies**): devono comparire le policy `read source`, `read listing`, `read game`, `read actions`, `app may write actions`, `read collection`. Se le vedi, è tutto a posto. La chiave `service_role` del collector **ignora** queste regole.
- [ ] A.2.5 Passa la **Project URL** e la **service_role key** al passo A.3.

> **Che cos'è una "policy"?** È una regola che dice *chi* può fare *cosa* su una tabella (leggere, inserire, ecc.). Le scrive la migrazione per te: non è lavoro manuale. Servono perché l'app usa la chiave pubblica `anon`, che per sicurezza può solo **leggere** (più l'inserimento in `user_action` per i tuoi Ignora/Preferito).

Migrazione iniziale (`appgiochi/collector/supabase/migrations/0001_init.sql`):

```sql
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

alter table source enable row level security;
alter table listing enable row level security;
alter table game enable row level security;
alter table user_action enable row level security;
alter table collection_item enable row level security;

-- lettura pubblica in sola lettura per l'app (ruolo anon); scritture solo via service_role
create policy "read source"   on source        for select using (true);
create policy "read listing"  on listing       for select using (true);
create policy "read game"     on game          for select using (true);
create policy "read actions"  on user_action   for select using (true);
create policy "app may write actions" on user_action for insert with check (true);
create policy "read collection" on collection_item for select using (true);
```

> Nota: `user_action` è scritto dall'app (ruolo anon) per Ignora/Preferito; in v1 a utente singolo è accettabile. Se in futuro si vuole protezione, si passa a Supabase Auth.

### A.3 GitHub (repo + Actions + segreti)

- [ ] A.3.1 Crea un repository su GitHub (privato va bene: 2.000 min/mese gratis; pubblico = minuti illimitati). Collega `E:\Ai` come remoto.
- [ ] A.3.2 In **Settings → Secrets and variables → Actions**, aggiungi:
  - `SUPABASE_URL`
  - `SUPABASE_SERVICE_ROLE_KEY`
  - (in seguito) `BGG_USERNAME`, `BGG_API_TOKEN`, `FCM_SERVICE_ACCOUNT`
- [ ] A.3.3 Verifica che `.env` sia in `.gitignore` (il collector userà `.env` in locale e i segreti su Actions).
- [ ] A.3.4 (Opzionale) In **Actions → General**, consenti `workflow_dispatch` per l'avvio manuale.

### A.4 Firebase (notifiche push)

- [ ] A.4.1 Crea un progetto su `https://console.firebase.google.com` (piano Spark, gratis).
- [ ] A.4.2 Aggiungi un'app **Android** con il package dell'app (es. `it.meepleradar.app`); scarica `google-services.json` (andrà in `app/android/app/`).
- [ ] A.4.3 In **Project settings → Service accounts**, genera una **nuova chiave privata** (JSON): sarà il segreto `FCM_SERVICE_ACCOUNT` per l'invio dal collector via `firebase-admin`.
- [ ] A.4.4 In **Cloud Messaging** annota che l'invio avviene via `firebase-admin` (nessuna server key legacy).

### A.5 BoardGameGeek

- [ ] A.5.1 Verifica i requisiti correnti dell'API (registrazione applicativa?) sulla pagina ufficiale dell'XML API 2.
- [ ] A.5.2 Annota l'**username BGG** dell'utente (collezione pubblica) come segreto `BGG_USERNAME`; se richiesto, registra l'app e salva il token in `BGG_API_TOKEN`.

### A.6 Cadenze proposte (dentro i limiti gratuiti)

| Job | Cadenza | Note |
|---|---|---|
| Negozi (Shopify/WooCommerce) | 2×/giorno | job leggero, ~2 min |
| Editori | 1×/giorno | |
| Fonti difficili (Playwright) | 1×/giorno | più pesante |
| Sync BGG + push | 1×/giorno | |

---

## Parte B — Roadmap dei piani

| Piano | Ambito | Fasi spec | Dipende da |
|---|---|---|---|
| **00** (questo) | Setup Opzione A + struttura | M0 | — |
| **01 — Collector** | Scheletro Node/TS, adattatori Shopify/WooCommerce/WordPress, normalizzazione, scrittura su Supabase, workflow Actions, monitoraggio | M0–M1 | 00 |
| **02 — BGG** | Sync collezione, arricchimento voto/rank, rilevazione espansioni, auto-preferiti | M2 | 01 |
| **03 — App Android** | Schermate, azioni Ignora/Preferito, offline, lettura da Supabase | M3 | 01 |
| **04 — Notifiche + fonti difficili** | FCM, digest, adattatori Playwright per Asmodee/DungeonDice/Magic Merchant, hardening | M4–M5 | 01, 02, 03 |

Criterio di "fatto" v1: tutte e 14 le fonti coperte.

## Parte C — Struttura dei file del progetto

```
appgiochi/
├─ docs/superpowers/specs/…design.md
├─ docs/superpowers/plans/…            (questi piani)
├─ collector/                          (Node.js/TypeScript)
│  ├─ package.json  tsconfig.json  vitest.config.ts  .env.example  .gitignore
│  ├─ supabase/migrations/0001_init.sql
│  ├─ src/
│  │  ├─ types.ts                      (Listing, Source, RawProduct, SourceAdapter)
│  │  ├─ config.ts                     (lettura env)
│  │  ├─ adapters/{shopify,woocommerce,wordpress}.ts
│  │  ├─ adapters/registry.ts
│  │  ├─ normalize.ts                  (normalizzazione + data uscita)
│  │  ├─ db.ts                         (client Supabase + upsert idempotente)
│  │  └─ run.ts                        (orchestratore)
│  └─ test/                            (Vitest + fixtures)
├─ .github/workflows/collect.yml
└─ app/                                (progetto Android Studio)
```

## Parte D — Prossimo passo

Esegui questo piano (Parte A è manuale, ~30 min) e poi il **Piano 01 — Collector**.
