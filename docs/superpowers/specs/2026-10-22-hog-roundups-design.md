# Design: roundups House of Games via scrape HTML (Google News disabilitato)

Data: 2026-10-22. Stato: approvato dall'utente (emendamento: solo articoli dal 2026-08-01 in poi).

## Perché

Google News non funziona: le run riportano titoli inutili (DJ SET, EA FC 27, Fortnite,
casino/esports), corpi di articolo spesso corti o mancanti e l'LLM deve inventare il resto.
L'unica fonte editoriale realmente utile è House of Games, che pubblica ogni mese un
articolo-elenco "giochi da tavolo: tutte le nuove uscite di {mese}" dalla struttura
estremamente regolare. Decisione utente: "google news non funziona, non usiamolo; facciamo
solo lo scrape html di houseofgames.it".

## Goal

1. Nuova source `houseofgames` (kind `roundups`): scopre gli articoli-elenco simili a
   quelli di esempio (ottobre e settembre 2026) e ne estrae giochi, date, publisher e
   riassunto **senza alcuna chiamata LLM nel parsing**.
2. I dati entrano nella pipeline write-once esistente su `product`: `description` (riassunto
   verbatim), `release_date` (solo se assente), **nuova colonna** `publisher` (solo se
   assente), `game_id` via `gameFinder` esistente (lookup su store, gratuito).
3. `google-news` → `enabled: false` (codice conservato, reversibile).
4. Filtro approvato: solo articoli con `datePublished >= 2026-08-01`.

## Non-goal

- Niente Google News, `NEWS_QUERIES` né feed HoG dentro `google-news`: la source viene
  disabilitata, il codice resta dov'è.
- Niente LLM nel parsing degli articoli (solo `gameFinder`, che è un lookup sullo store).
- Niente prezzo dagli articoli; niente sovrascritture: write-once ovunque, come oggi.
- Niente recensioni/preview singole: solo roundups mensili (regola sullo slug).
- Niente rimozione di `export-news.ts` né `google-news.ts` (restano, semplicemente non
  girano più con la source disabilitata).
- Niente altre DDL: la colonna `publisher` la esegue l'utente nella dashboard.

## Vincoli

- DDL (eseguita dall'utente, **PRIMA** della prima run reale):

```sql
alter table product add column publisher text;
```

- Nessun'altra modifica allo schema; scritture solo via `service_role` e lo store (`db.ts`).
- Cron settimanale esistente (sab 06:00 UTC): ~3-20 fetch di pagine per run (3 articoli
  oggi, +1 al mese).
- Prefisso log `[roundups]` su `console.error`, come le altre fonti.
- Regola invariabile: nessun testo generato entra in `product.description` (qui il
  riassunto è verbatim dal testo dell'articolo, non composto da nessuno).

## Come funziona (flusso)

1. **Discovery**: `fetchSitemapEntries(`${baseUrl}/post-sitemap.xml`, fetchText)` (riuso
   di `adapters/sitemap.ts`) → filtro regola slug: pathname contiene `giochi-da-tavolo-`
   E `nuove-uscite-di-` (esclude calendari videogiochi, recensioni, altre pagine) →
   pre-filtro `lastmod >= 2026-08-01` (safe: `lastmod >= datePublished` per costruzione,
   quindi non scarta nulla di eleggibile) → `newestFirst`.
2. **Floor autorevole**: per ogni articolo candidato, fetch HTML diretto (nessuna
   protezione sul sito) → `datePublished` dal JSON-LD `aioseo-schema` (regex) → se
   `< 2026-08-01` → log + skip.
3. **Parsing deterministico** (cheerio, in ordine di documento con stato del publisher
   corrente):
   - publisher = `<p><strong>TESTO</strong></p>` (p senza attributi con un solo figlio
     strong) — 16 sezioni nell'articolo di ottobre;
   - gioco = `<li><strong>Nome (data)</strong></li>` (li **senza attributi**, un solo figlio
     strong: sono esattamente le 87 voci reali; li di menu/sidebar hanno attributi o
     contengono `<a>` e restano fuori) → titolo + data grezza fra parentesi finali
     opzionali (85 con data, 2 senza);
   - riassunto = i `<p>` di solo testo successivi al gioco, fermati dal prossimo li, dal
     prossimo publisher o da un `<p>` che contiene `<a>`/`<img>` (niente promo
     dungeondice né immagini) → `htmlToText`, verbatim.
4. **Righe** `RawProduct` per gioco (specchio del percorso articoli news,
   `google-news.ts:437-467`): `externalId` = slug del titolo
   (`normalizeTitle().replace(/\s+/g,'-')`), `url` = articolo, `releaseDateRaw` =
   `extractReleaseDate(`${dataGrezza} ${anno}`)` con anno da `datePublished` (gestisce
   "Fine Ottobre"→1°, "1 OTTOBRE", "Anteprima Lucca"→null, due voci senza parentesi),
   `articleDescription` = riassunto, `publisher` = sezione corrente, poi
   `gameFinder(title, publisherHint)` → price/currency/releaseDate/imageUrl/gameId (il
   catalogo vince sull'articolo, come news). Errori: sitemap non raggiungibile → throw
   (la fonte finisce in `failures`); articolo non fetchabile o parsing vuoto → log +
   skip, la fonte non cade.
5. **run.ts**: `isEditorial = kind === 'news' || kind === 'roundups'` (riga editoriale
   spogliata nel `related`, payload dell'upsert prodotto senza price/currency/releaseDate,
   `fillProductReleaseDate` condizionale — tutto come news) + nuovo
   `fillProductPublisher(productId, publisher)` write-once + **skip di re-run per
   roundups**: prima dell'upsert batch, ogni riga il cui gioco ha già un listing della
   STESSA fonte (`findListingsByTitle`, già chiamato in pipeline) viene scartata → ai run
   successivi zero scritture; i giochi nuovi passano; dopo la purge la riga si rigenera
   benignamente.

## Note tecniche per il builder

- **CDATA**: `LOC_RE` in `adapters/sitemap.ts` non legge
  `<loc><![CDATA[…]]></loc>` (è il formato di AIOSEO su houseofgames.it): oggi l'entry
  viene persa silenziosamente e la fonte risulterebbe vuota → fix della regex + test.
- Varianti reali di data fra parentesi: `2 Ottobre`, `1 OTTOBRE`, `( 2 Ottobre)` con
  spazio, `MONDO(23 Ottobre)` senza spazio, `Fine Ottobre`, `Ottobre`, `Anteprima Lucca`
  (→ null), due voci senza parentesi (`RIP – ROAST IN PEACE`, `FAME & FABLE`).
- Entità HTML: `&#8211;`, `&#8217;`, `&amp;`: `decodeHtml`/`htmlToText` (`text.ts`)
  già le gestiscono.
- Fixture pronti in `collector/test/fixtures/`: `hog-roundup-article.html` (pagina di
  ottobre reale, UTF-8 senza BOM: 87 giochi, 16 publisher, `datePublished` 2026-10-02) e
  `hog-post-sitemap.xml` (11 entry: loc in CDATA e senza, lastmod mancante, negativi).
- `SOURCE_KINDS` + registry + `sources.test.ts` da aggiornare (18→19 fonti; `KINDS`
  locale + `'roundups'`; lista enabled: esce `google-news`, entra `houseofgames`).
- `RawProductSchema`: nuovo campo opzionale `publisher`; `ListingStore`: nuovo metodo
  `fillProductPublisher` (specchio di `fillProductReleaseDate`, filtro
  `.is('publisher', null)`).

## Scelte confermate dall'utente

1. Publisher persistito: colonna `product.publisher` write-once (DDL eseguita dall'utente;
   io non eseguo DDL).
2. Design approvato per andare al piano, CON emendamento: **solo articoli con
   `datePublished >= 2026-08-01`** ("fai la run solo sugli articoli dai mesi di agosto in
   poi, prima non mi serve").
3. Solo scrape HTML di houseofgames.it; Google News non si usa più.

## Scelte mie (da controllare)

- Discovery da `post-sitemap.xml` diretto (non dall'indice con 16 figli): basta per gli
  articoli recenti e costa un fetch solo.
- Skip di re-run = confronto con listing della stessa fonte dentro `run.ts` (nessuno stato
  dedicato, nessuna DDL): re-run = 0 scritture, resta un fetch+parse per articolo a
  settimana.
- `lastmod` come pre-filtro veloce (safe) e `datePublished` come giudice autorevole del
  floor.
- Riassunto viaggia solo in `articleDescription` (`listing.description` resta null, come
  news).
- `externalId` = slug del gioco (convenzione news: dedup naturale se lo stesso gioco
  compare in più roundups).
- Il mapping news non viene toccato (non popola `publisher`: fuori scope, la source è
  disabilitata).
- Soglia riassunto: esco dai `<p>` che contengono `<a>` o `<img>` anche a costo di troncare
  un paragrafo con un link inline (preferisco perdere una frase che inserire una promo).
- Nessun cap sul numero di articoli (3 oggi, +1 al mese).

## Cosa resta al builder

Piano di implementazione dettagliato (`superpowers:writing-plans`): interfacce esatte,
ordine dei task, test, messaggi di commit. Nessuna domanda aperta nota.
