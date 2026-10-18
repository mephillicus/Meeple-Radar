# Meeple Radar — Documento di design

*Data: 2026-10-17 · Stato: revisionato dopo builder-check · Titolo di lavoro: "Meeple Radar"*

## 1. Intento e perché

Chi colleziona giochi da tavolo oggi deve **cacciare** le informazioni: 6 negozi online, i siti di 8 editori e vari canali Telegram, ognuno con novità, preordini e offerte presentati in modo diverso. Il risultato è fatica e, soprattutto, **uscite mancate** — in modo particolare le *espansioni* dei giochi già posseduti, che sono facili da non notare.

Meeple Radar ribalta il flusso: invece di andare a cercare, l'utente **riceve** un unico posto ordinato che dice *cosa sta per uscire, quando e a che prezzo in Italia*, con accanto il voto di BoardGameGeek e la possibilità di dire "mi interessa" o "ignora". Collegando il profilo BGG, le espansioni dei giochi posseduti finiscono automaticamente tra i preferiti.

**Chi lo usa:** l'utente stesso, appassionato con una collezione già avviata su BGG. Uso personale, un solo utente, nessun comparto social.

**Cosa significa successo:** l'utente non apre più i 6 negozi a mano; ogni uscita rilevante per la sua collezione gli arriva davanti o via notifica; i dati (data, prezzo, voto) sono corretti e aggiornati.

## 2. Obiettivi (goals)

- G1 — Aggregare le uscite dalle fonti degli editori e dei negozi mappati (18 fonti: 13 editori + 5 negozi, dopo la rimozione di Kosmos, Stonemaier Games e Games Academy; la 13ª scheda di ricognizione, Ergo Ludo Editions, è riportata in tabella ma esclusa dalla v1 perché il sito non era raggiungibile il 2026-10-17).
- G2 — Mostrarle in un'unica interfaccia curata e accattivante, con **data di uscita** e **prezzo in euro (Italia)**.
- G3 — Mostrare il **voto BGG** (e rank) quando il titolo esiste su BGG.
- G4 — Su ogni voce: azione **Ignora** o **Preferito**.
- G5 — Collegare il profilo BGG, scaricare la collezione e **mettere automaticamente tra i preferiti le espansioni dei giochi posseduti**.
- G6 — **Notificare** i nuovi titoli rilevanti (novità e, in particolare, espansioni seguite), così che nulla sfugga più.
- G7 — Funzionare comunque bene offline (dati già scaricati) e aggiornarsi senza intervento manuale.

## 3. Non-obiettivi (non-goals)

- Non è un e-commerce: nessun carrello, nessun acquisto, nessun pagamento. Solo link "vai allo store".
- Non copre *tutti* i giochi del mondo: solo le fonti del txt (più eventuali aggiunte future).
- Non è un tracker di partite né un gestore di collezione: la collezione BGG serve **solo** per riconoscere le espansioni.
- Nessuna funzione social, multi-utente o condivisione.
- v1 senza storico prezzi / grafici di andamento (solo prezzo corrente).
- Nessuna localizzazione oltre l'italiano, nessuna valuta oltre l'euro (vedi §13.A.7 per gli store esteri).

## 4. Anti-obiettivi (cosa renderebbe il progetto un fallimento)

- **Dati stantii, inventati o prezzi sbagliati**: la fiducia crolla al primo errore. Meglio "non lo so" che un valore falso.
- **Troppe notifiche o falsi positivi**: se l'utente le disattiva, l'app perde il suo scopo principale.
- **Raccolta fragile e silenziosa**: se un sito cambia e il parser si rompe senza che nessuno se ne accorga, l'app mente per omissione.
- **Interfaccia lenta o brutta**: l'app deve essere un piacere da aprire.
- **Abbinamenti BGG errati**: preferiti automatici sbagliati sono peggio di nessun automatismo.

## 5. Vincoli

- Sviluppo in **Android Studio**; stack **Kotlin + Jetpack Compose**, Material 3.
- Progetto ospitato in `E:\Ai\appgiochi`. Sviluppo su Windows / PowerShell.
- Fonti: le 18 fonti configurate in `collector/src/sources.seed.ts` (il file txt di ricognizione è stato rimosso dal repo).
- **Hosting (deciso): Opzione A "niente server"** — raccolta con **GitHub Actions** programmate, dati e API su **Supabase**, notifiche push con **Firebase Cloud Messaging**. Nessun server sempre attivo.
- **Linguaggio del collector: Node.js (TypeScript)**, con **fetcher dedicati** per le fonti protette (sitemap+HTML e `curl` per le fonti Cloudflare).
- Integrazione **BoardGameGeek XML API 2** (`boardgamegeek.com/xmlapi2`).
- Nessun login nell'app oltre al **nome utente BGG** (collezione pubblica). Nessun dato sensibile.
- Prezzi in **EUR** per gli store italiani; valuta originale per gli store esteri (§13.A.7). Mercato di riferimento **Italia**.
- Non si effettuano commit/push senza richiesta esplicita (regola di `AGENTS.md`).
- Rispetto dei termini d'uso e dei `robots.txt` delle fonti; raccolta a bassa frequenza, con User-Agent identificabile.

## 6. Ricognizione delle fonti (fatta il 2026-10-17)

Ho sondato le fonti. **Buona notizia: la maggior parte espone dati strutturati**; poche bloccano gli accessi automatici. La matrice riporta il metodo di accesso **effettivamente implementato e verificato dal vivo** (sonda `collector/scripts/probe-source.ts`), non più quello ipotizzato.

### Editori / produttori

| Fonte | Piattaforma rilevata | Accesso effettivo | Stato |
|---|---|---|---|
| Cranio Creations | **Shopify** | `GET /products.json` ✅ | abilitata |
| Ghenos Games | WordPress + WooCommerce | `GET /wp-json/wp/v2/product` ✅ | abilitata |
| Pendragon Game Studio | WooCommerce | `GET /wp-json/wc/store/v1/products` ✅ | abilitata |
| Giochi Uniti | Sitemap index (Magento; prodotti su `igiochideigrandi.it`) | sitemap → parsing HTML/JSON-LD ✅ (40) | abilitata |
| Ravensburger IT | Custom — base **`ravensburger.it`** (non più `.org`, la cui sitemap era vuota) | sitemap urlset (~2.476 prodotti) → parsing HTML/JSON-LD; Cloudflare: raggiungibile da **IP residenziale** (via `curl`), **bloccata dagli IP datacenter di Actions** | disabilitata |
| Asmodee Italia | Custom — `product-sitemap.xml` | sitemap → parsing HTML/JSON-LD ✅ (40) | abilitata |
| Studio Supernova | **Shopify** | `GET /products.json` ✅ (334); il dominio senza `www` ha il TLS rotto → base obbligatoria **`www.studiosupernova.it`** | abilitata |
| Little Rocket Games | WooCommerce | `GET /wp-json/wc/store/v1/products` ✅ (81) | abilitata |
| MS Edizioni | WooCommerce | `GET /wp-json/wc/store/v1/products` ✅ (225) | abilitata |
| dV Games | PHP custom | elenco `/prossime-uscite/` (data di uscita nel testo del link) → pagine `/catalogo/<slug>/`; nessun prezzo; titolo dall'immagine dell'`<h2>` ✅ (7) | abilitata |
| Horrible Guild | WooCommerce con REST chiuso | crawl HTML di `/eu/product/<slug>/` (il sito `/us/` non va) ✅ (19); prezzo presente; titolo da `.product_title` | abilitata |
| GateOnGames | WordPress senza REST shop | cataloghi `/prodotti/localizzazioni/` + `/prodotti/originali-gog/`; slug mono-segmento + deny-list; nessun prezzo; titolo generico da `og:title` ✅ (40) | abilitata |
| Gemini | LLM (Generative Language API) | `POST /v1beta/models/{model}:generateContent` con strict JSON schema ✅ (indicativo) | abilitata |
| Ergo Ludo Editions | n.d. | sito non raggiungibile in ricognizione (HTTP 503 / TLS fallito) | esclusa dalla v1 |

### Negozi

| Fonte | Piattaforma rilevata | Accesso effettivo | Stato |
|---|---|---|---|
| BoardHouse Store | **Shopify** | `GET /products.json` ✅ | abilitata |
| uplay.it | Sitemap index (8 sitemap prodotti) | sitemap → parsing HTML/JSON-LD; **Cloudflare**: raggiungibile da IP residenziale, **bloccata dagli IP datacenter di Actions** | disabilitata |
| Philibert | PrestaShop | sito IT `/it/` rotto (HTTP 500); si usa il sito EN `/en/` (prezzi EUR) → crawl HTML ✅ (40) | abilitata |
| DungeonDice | Anti-bot (Cloudflare) | tag Open Graph; raggiungibile da IP residenziale (via `curl`), **bloccata dagli IP datacenter di Actions** | disabilitata |
| Magic Merchant | Anti-bot (Cloudflare, **managed challenge**) | blocca `fetch`, browser **e** `curl`; spec pronto ma fonte **disabilitata** | disabilitata |

**Conseguenza per il design:** conviene un livello di **"adattatori" per fonte** (uno per piattaforma/metodo), così aggiungere o riparare una fonte non tocca il resto. Le fonti **Shopify** restano il caso più economico; sitemap+HTML coprono editori e negozi. Il discriminante reale è **Cloudflare**: le 4 fonti protette (uplay, Ravensburger, DungeonDice, Magic Merchant) sono raggiungibili **da IP residenziale** ma **bloccate dagli IP datacenter dei runner di Actions**. Per il kind `headless` esiste un fetcher **`curl`** in sottoprocesso (`src/adapters/headless/fetch.ts`) che in locale supera Cloudflare (Ravensburger/DungeonDice, non Magic Merchant), ma da CI non basta: quindi queste fonti restano **disabilitate** con lo spec pronto, pronte a riaccendersi da un'esecuzione non datacenter (es. self-hosted runner / PC di casa). Verifica end-to-end CI: **14/18 fonti abilitate**, `zeroed: []` (dopo la rimozione di Kosmos, Stonemaier Games e Games Academy).

**Nota Telegram:** i canali Telegram non offrono un'API pulita per la lettura di *altri* canali (un bot non può leggere canali di terzi). Servirebbe un client MTProto (es. Telethon). Propongo di **escludere Telegram da v1** e valutarlo in seguito.

## 7. Architettura (deciso: Opzione A — "niente server")

La raccolta non avviene sul telefono: 17 siti eterogenei, blocchi anti-bot e parser da aggiornare rendono l'app inaffidabile. Adottiamo l'Opzione A, senza server sempre attivo.

```
[17 fonti] --(adattatori)--> [ GitHub Actions  (Node.js, cron) ]
                                     |                      |
                          [ Supabase: Postgres + API ]     [ BGG XML API 2 ]
                                     |
                          [ Firebase Cloud Messaging ] --> [ App Android Compose ]
                                     |
                            (l'app legge anche l'API Supabase direttamente)
```

**Componenti:**

1. **Collector — job Node.js su GitHub Actions**
   - Linguaggio: **Node.js / TypeScript**.
   - Esecuzione: **workflow programmati** (es. più volte al giorno per gli store, giornaliero per gli editori). 2.000 min/mese gratuiti; la run è dominata dal catalogo della fonte abilitata più grande.
   - **Fetcher dedicato per le fonti protette**: il kind `headless` usa un fetcher **`curl`** in sottoprocesso (`src/adapters/headless/fetch.ts`). In locale supera Cloudflare, ma **gli IP datacenter dei runner di Actions vengono sfidati** → le 4 fonti Cloudflare restano **disabilitate** (spec pronti per un'esecuzione non datacenter). Nessun browser (Playwright è stato rimosso: bloccato dalle stesse challenge).
   - Adattatori per fonte isolati tra loro: Shopify, WooCommerce, WordPress, Sitemap+HTML, HTML-crawl, fetcher protetto (`curl`), LLM (`gemini`).
   - Normalizzazione, deduplica, abbinamento a BGG, calcolo delle novità, invio push.
   - Limiti noti: nessun processo sempre attivo; i cron di Actions possono ritardare di alcuni minuti; gli IP dei runner possono essere a volte bloccati (mitigato da fetcher dedicato e retry).

2. **Dati e API — Supabase**
   - **Postgres** gestito + **API REST autogenerata** (PostgREST): l'app Android legge direttamente, senza backend scritto a mano.
   - Row Level Security per proteggere i dati; per logica su misura, **Edge Functions** (gratuite).
   - Alternativa: Turso/Neon se si preferisce un database puro con API propria.

3. **Notifiche — Firebase Cloud Messaging**
   - Il job calcola i nuovi elementi rilevanti, scrive su Supabase e invia la push via FCM.
   - Fallback: l'app può mostrare un digest locale dai dati in cache.

4. **App Android**
   - Kotlin, Jetpack Compose, Material 3, tema dinamico, dark mode.
   - Client **Retrofit/OkHttp** verso l'API Supabase; **Room** per cache offline; WorkManager per refresh leggero.
   - FCM per le push.

**Modello dati (bozza, su Postgres):**

- `source` — id, nome, tipo, url, adattatore, abilitata, ultima scansione.
- `listing` — fonte, id esterno, titolo, url, prezzo, valuta, disponibilità, data uscita, immagine, ultimo avvistamento.
- `game` (canonica) — nome, tipo (base/espansione), `bgg_id`, voto, rank, immagine.
- `listing_game` — abbinamento listing→gioco canonico (con punteggio di confidenza).
- `user_action` — gioco/listing → `ignored` | `favorite`.
- `collection_item` — `bgg_id`, posseduto/wishlist, tipo.
- `notification_log` — cosa è stato già notificato (per non ripetere).

**Abbinamento & arricchimento BGG:**
- Deduplica i listing delle varie fonti nello stesso gioco canonico (per titolo/BGG id).
- Trova il gioco su BGG (search API o id già presente nella pagina) e salva voto/rank.
- Riconosce le espansioni sia dal campo BGG (`boardgameexpansion`) sia dai link "espande" del gioco base: così le espansioni dei titoli posseduti si identificano con certezza.

## 8. Integrazione BoardGameGeek

- API: **XML API 2** — `collection` (collezione dell'utente), `thing` (dettagli/voti), `search`.
- Autenticazione: **nome utente BGG** (collezione pubblica) **+ un token di applicazione** inviato come header `Authorization: Bearer <token>`. BGG richiede la registrazione dell'app; nessuna password utente, nessun OAuth.
- Dal `collection` con `stats=1` si ricava l'insieme dei giochi posseduti e il loro tipo; incrociando con i link "espande" si trovano le espansioni dei titoli posseduti → **preferito automatico**.
- Vincoli (verificati 2026-10): l'XML API richiede **registrazione dell'app** e **`Authorization: Bearer <token>`** su quasi tutti gli usi; le richieste vanno a `boardgamegeek.com` **senza `www`**. Scaricare la *propria* collezione senza token è permesso solo restando loggati, ma il collector è server-side e fa più tipi di chiamata, quindi si registra l'app. La registrazione è **gratuita** (non-commerciale) ma può richiedere **una settimana o più**. Tutte le chiamate passano dal collector, che mette in **cache** i risultati e li distanzia. In uso pubblico va mostrato il logo **"Powered by BGG"**.

## 9. Esperienza utente (schermate principali)

1. **In arrivo** (home) — timeline/lista delle uscite imminenti; card ricche con copertina, titolo, editore, **data**, **prezzo €**, badge **voto BGG**; gesto/bottoni **Ignora** / **Preferito**. Filtri per data, prezzo e "solo espansioni dei miei giochi".
2. **Preferiti** — la watchlist personale.
3. **Per te** — espansioni dei giochi posseduti, suggerite automaticamente.
4. **Dettaglio gioco** — descrizione, voto/rank BGG, confronto prezzi per store, link agli store.
5. **Impostazioni** — nome utente BGG, stato sincronizzazione, notifiche, fonti abilitate, tema.
6. **Onboarding** — inserimento username BGG + scelta di cosa seguire.

**Design:** Material 3, colori dinamici, dark di default, copertine grandi, animazioni fluide, empty-state curati. L'obiettivo dichiarato dall'utente è un'**interfaccia accattivante**.

## 10. Notifiche

- **Digest** periodico (default: settimanale) con le novità filtrate.
- **Push immediata** per: nuova espansione di un gioco posseduto/preferito; prezzo che scende sotto una soglia (opzionale).
- Ogni notifica è tracciata per non ripetersi. L'utente può silenziare per fonte o per gioco.

## 11. Requisiti non funzionali

- Offline-first: l'app mostra i dati in cache senza rete; refresh in background.
- Prestazioni: lista fluida anche con centinaia di titoli; immagini con cache.
- Correttezza dei dati prima della ricchezza: meglio pochi campi giusti.
- Monitoraggio del collector: se un adattatore restituisce 0 elementi o va in errore, va segnalato (log su Actions + riga di stato su Supabase).

## 12. Rischi e mitigazioni

| Rischio | Impatto | Mitigazione |
|---|---|---|
| Anti-bot / IP datacenter (uplay, Ravensburger, DungeonDice) | Fonti non raccoglibili da CI | Raggiungibili da IP residenziale: **disabilitate** finché non si usa un'esecuzione non datacenter (self-hosted runner / PC di casa), con **spec pronti**. Il monitoraggio le segnala come escluse |
| Anti-bot (Magic Merchant) | Fonte non raccoglibile | **Managed challenge** JS che blocca anche `curl`: resta `enabled: false` con spec pronto; rivalutabile da un IP non sfidato |
| Parser fragili | Dati mancanti senza accorgersene | Adattatori isolati + test + alert quando un parse cala a 0 |
| Prezzi/data incoerenti tra fonti | Sfiducia | Normalizzazione + regole di priorità + indicare "prezzo da <fonte>" |
| Abbinamenti BGG errati | Preferiti automatici sbagliati | Punteggio di confidenza; sotto soglia: non auto-preferire ma proporre |
| Cambio regole/registrazione BGG | Sync collezione non funziona | Cache + messaggio chiaro; punto di manutenzione noto |
| Limiti dei tier gratuiti (Actions 2.000 min/mese, Supabase, FCM) | Job saltati | Cadenza bassa e job leggeri + monitoraggio; piani a basso costo solo se necessario |
| Cron di Actions ritardati o sospesi su repo inattivo | Raccolta salta | `workflow_dispatch` manuale + "keep-alive" del repo + fallback |
| Aspetti legali/ToS della raccolta | Blocco/rimozione | Uso personale, bassa frequenza, rispetto `robots.txt`, User-Agent identificabile |

## 13. Decisioni

### 13.A Prese dal costruttore (mie) + tue

**Tue (concordate):**

- **13.A.1 Hosting = Opzione A "niente server"**: GitHub Actions (Node.js) + Supabase + Firebase Cloud Messaging. Gratuito, nessun server sempre attivo.
- **13.A.2 v1 abilita 14 delle 18 fonti** (verificate end-to-end in CI). Le **4 fonti dietro Cloudflare** (uplay, Ravensburger, DungeonDice, Magic Merchant) sono raggiungibili da IP residenziale ma **bloccate dagli IP datacenter dei runner di Actions** → restano `enabled: false` con spec pronti, riaccendibili da un'esecuzione non datacenter (self-hosted runner / PC di casa). Magic Merchant in più ha una *managed challenge* JS che blocca anche `curl`.
- **13.A.3 Notifiche push con Firebase** (progetto Google da predisporre), con fallback locale.

**Mie (verificabili — correggimi se qualcuna non ti va):**

- **13.A.4 Telegram escluso da v1.** Serve un client MTProto per leggere canali di terzi: complessità alta, valore marginale. Da rivalutare dopo.
- **13.A.5 Prezzo mostrato:** tutte le offerte per store, con la **migliore** evidenziata.
- **13.A.6 Frequenza notifiche di default:** digest settimanale + push immediata per le espansioni dei posseduti.
- **13.A.7 Prezzo e valuta:** prezzo in € per gli store che vendono verso l'Italia; per gli store esteri (es. Stonemaier in USD, fonti tedesche) si mostra uscita/disponibilità e, se presente, il prezzo nella **valuta originale con etichetta**, senza conversioni inventate.
- **13.A.8 Auto-preferire:** solo per i titoli **posseduti** (non wishlist).
- **13.A.9 Data di uscita quando la fonte non la pubblica:** la data si estrae solo da campi/metadati affidabili; in loro assenza il titolo appare come "novità — data da confermare" e **non** in una timeline datata. Coerente con l'anti-obiettivo "meglio nessun valore che uno falso".
- **13.A.10 Cosa si raccoglie:** i prodotti con data di uscita (anche futura) = "in arrivo" con flag **preordine**; le offerte/ribassi sono una vista/etichetta separata, non la timeline principale.
- **13.A.11 Quali novità generano notifica (anti-rumore):** di default solo ciò che **combacia con i tuoi interessi** — espansioni dei posseduti, titoli nei preferiti, e (opzionale) novità dagli editori abilitati. Non "tutto ciò che appare".
- **13.A.12 Nome di lavoro:** "Meeple Radar"; identità visiva definita in M4.

## 14. Fasi proposte (il piano dettagliato seguirà)

**Criterio di "fatto" per la v1 (rivisto): 14 delle 18 fonti abilitate e verificate end-to-end in CI** (13.A.2); le 4 fonti Cloudflare (uplay, Ravensburger, DungeonDice, Magic Merchant) restano disabilitate con spec pronti perché i runner di Actions non le raggiungono. Le fasi sotto organizzano il lavoro; le fonti difficili non sono un extra opzionale.

- **M0 — Spike e struttura:** scheletro del collector Node/TS, workflow GitHub Actions, progetto Supabase, primo adattatore Shopify + uno WooCommerce; verificare i requisiti correnti dell'API BGG.
- **M1 — Collector base:** scheletro, scheduling, normalizzazione, deduplica, monitoraggio.
- **M2 — BGG:** sync collezione, arricchimento voto/rank, rilevazione espansioni.
- **M3 — API + app base:** lettura da Supabase, home "In arrivo", dettaglio, azioni Ignora/Preferito, offline.
- **M4 — Notifiche + UI:** FCM, digest, design system, animazioni.
- **M5 — Tutte le fonti + hardening:** gli adattatori rimanenti, casi limite, test. Le 4 fonti Cloudflare restano disabilitate (spec pronti) finché non si dispone di un'esecuzione non datacenter.

## 15. Cosa resta al costruttore

- Schema DB esatto (tabelle Postgres, indici, RLS) e contratto di lettura per l'app.
- Layout dei workflow GitHub Actions e strategia di retry/log.
- Strategia di matching/deduplica e soglie di confidenza.
- Pattern architetturale dell'app (MVVM + repository) e librerie di grafica.
- Piano di verifica dei requisiti correnti dell'API BGG e gestione della credenziale.
- Piano di test (unit per gli adattatori e il matching, UI test per gli schermi principali).
