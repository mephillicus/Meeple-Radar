# Design: articoli di novità analizzati per intero

Data: 2026-10-22. Stato: bozza per approvazione dell'utente.

## Perché

L'adapter news (`collector/src/adapters/google-news.ts`) oggi legge solo i titoli: un'unica
chiamata Gemini con fino a 80 titoli restituisce i giochi, ma senza date, senza editori e con
una descrizione inventata dai titoli (poi azzerata da `GENERATED_TEXT_KINDS` in `run.ts:54`).
I prodotti news arrivano quasi vuoti (17 prodotti nell'ultima run, description tutte null).
Molti articoli (es. House of Games, feed già in `SITE_FEEDS`) contengono invece il testo
completo: mese di uscita, editore, MSRP, descrizione scritta dall'editore.

## Goal

1. Per i titoli che annunciano uscite o anteprime (NON recensioni), analizzare l'articolo per
   intero ed estrarre per ogni gioco: nome, editore (se citato), data di uscita indicativa
   (anche solo il mese), citazione testuale verbatim (opzionale).
2. Usare questi dati SOLO per riempire buchi: `product.description` write-once come oggi;
   `product.release_date` compilata solo se assente sul prodotto.
3. Il percorso attuale (solo titoli) resta per i titoli non analizzati: nessuna regression
   nella scoperta dei giochi.
4. Robustezza: un articolo non raggiungibile o una risposta LLM non interpretabile non deve
   far fallire la fonte news (log + skip, come il breaker di ghenos).

## Non-goal

- Niente elaborazione di recensioni.
- Niente nuove fonti né nuovi scraper: si usano i feed già in `NEWS_QUERIES` e `SITE_FEEDS`.
- Niente prezzo dagli articoli: il prezzo resta esclusivo dei negozi.
- Niente sovrascrittura di dati esistenti (specialmente del negozio): né description né
  release_date vengono mai rimpiazzati.
- Niente backfill delle run passate: le run future arricchiscono i buchi che troveranno.

## Vincoli

- Cron settimanale (sab 06:00 UTC) su GitHub Actions, budget ~1h per run (l'ultima: 48 min):
  serve un cap di articoli per run.
- Gemini free tier: 1 chiamata per articolo oltre a quella attuale di titoli; pacing tra le
  chiamate, cascata provider esistente (`buildProviders`).
- Scritture solo via `service_role` e lo store (`db.ts`), come oggi.
- Regola invariabile: nessun testo composto o parafrasato dall'LLM entra mai in
  `product.description`. Solo citazioni verbatim verificate dal testo dell'articolo.

## Come funziona (flusso)

1. `fetchNewsHeadlines` (invariato) → `NewsItem[]`.
2. **Selezione articoli** (scelta mia, euristica testabile): per ogni item, sul titolo:
   - skip se parole negative (`recensione`, `review`, `provato`, `verdetto`, `voto`, ...);
   - candidato se positive (`uscita`, `annuncio`, `arriva`, `anteprima`, `edizione italiana`,
     `kickstarter`, ...);
   - per i feed dei siti (`SITE_FEEDS`) candidato di default salvo negative;
   - ordinati dal più recente, **cap 12 articoli per run**. Il cap conta i **corpi
     recuperati con successo**: un fetch fallito o un interstitial Google non brucia un
     posto (log + skip). Gli item che hanno già `content:encoded` hanno priorità di
     fetch.
3. **Corpo articolo**: `parseRss` esteso a tenere `content:encoded` (House of Games lo
   include già: gratis). Senza corpo → fetch del link via relay Jina con fallback diretto
   (stesso pattern delle altre fonti), testo piatto con `htmlToText`, cap ~100k caratteri.
   I link di Google News sono redirect stub: se il fetch restituisce testo inutilizzabile,
   l'item viene scartato dalla parte arricchita e resta sul percorso solo-titoli. La
   deduplica dei titoli (`fetchNewsHeadlines`) deve preferire la copia con corpo/diretta
   su quella dello stub Google News quando lo stesso articolo compare in entrambi i feed
   (oggi le query arrivano prima dei `SITE_FEEDS`, quindi vince lo stub: da correggere).
4. **Una chiamata LLM per articolo**: testo + data di oggi → JSON
   `{games:[{title, publisher?, releaseDate?, descriptionQuote?}]}` con regole: solo giochi
   nominati nell'articolo; `descriptionQuote` = frase VERBATIM copiata dall'articolo, niente
   parafrasi; `releaseDate` solo se scritta.
5. **Verifica verbatim** (scelta mia, implementa la regola dell'utente): normalizza gli spazi
   su entrambi i lati e verifica che la citazione compaia nel testo dell'articolo; se no,
   scartata. Questa è la garanzia meccanica di "la descrizione può entrare se non generata".
6. **Percorso della citazione**: il RawProduct dell'articolo porta un nuovo campo opzionale
   `articleDescription` (la citazione già verificata). Poiché `GENERATED_TEXT_KINDS` azzera
   `row.description` per le fonti news, la citazione viaggia fuori da lì: `run.ts` chiama
   `fillProductDescription(productId, quote)` dopo l'upsert (write-once come oggi).
   `listing.description` resta null: nessun impatto sull'UI dei listing.
7. **Date**: le righe news (sia dal percorso articoli sia dal percorso solo-titoli) NON
   portano `release_date` dentro `upsertProducts` (che sovrascriverebbe il valore del
   negozio: l'upsert sostituisce le colonne presenti nel payload, `db.ts:138`). Le date
   derive dai titoli passano quindi anch'esse al nuovo metodo store
   `fillProductReleaseDate(productId, iso)` =
   `UPDATE product SET release_date = ? WHERE id = ? AND release_date IS NULL` (specchio di
   `fillProductDescription`). Così il negozio vince sempre e la data dalla notizia entra
   solo sul buco. Questo estende la regola "mai sovrascrivere" anche al percorso titoli
   esistente, dove oggi una data di titolo può rimpiazzare quella del negozio (scelta mia,
   allineata alla decisione 3 dell'utente; segnalata sotto).
8. **Dedup**: stesso gioco scoperto sia dall'articolo sia dal percorso titoli → vince
   l'entry dell'articolo (più ricca); i titoli non analizzati restano sul percorso attuale.

## Note tecniche per il builder

- `extractReleaseDate` (`normalize.ts:35`) gestisce già mesi in italiano/inglese → ISO con
  giorno 1. Attenzione: `isoOrNull` accetta come `preferred` solo ISO complete; l'adapter deve
  quindi passare in `releaseDateRaw` l'ISO già estratto con `extractReleaseDate` sul testo
  restituito dall'LLM, non la stringa libera (es. "novembre 2026").
- `NewsItem`: estendere con `body?: string` (da `content:encoded`) e/o `articleUrl`.
- Classifier e verbatim check come funzioni pure e testabili (`verifyQuote(quote, articleText):
  string | null`). Nuovo modulo suggerito: `adapters/articles.ts`.
- `AiProvider.call` accetta già una stringa: riusare `buildProviders` per la cascata.
- `run.ts`: serve un modo per far arrivare `articleDescription` dal RawProduct al fill (es.
  mappa per indice di riga). `Listing` e `normalizeListing` restano invariati.
- Test attesi: parseRss con body, classifier (recensioni escluse, preview incluse),
  `verifyQuote` (accetta verbatim, rifiuta parafrasi), date da "novembre 2026", fill
  condizionale, wiring `run.ts` (data news non nel payload dell'upsert; fill chiamati solo
  sui buchi).

## Scelte confermate dall'utente

1. Aprire gli articoli di tutte le fonti quando titolo/URL suggerisce novità.
2. Recensioni escluse; anteprime incluse come novità.
3. Dati esistenti mai sovrascritti (specialmente del negozio): solo riempire i buchi.
4. Prezzo mai dagli articoli.
5. Descrizione può entrare se non generata → citazione verbatim verificata per substring.

## Scelte mie (da controllare)

- Selezione con euristica sul titolo (trasparente, testabile) invece di una chiamata LLM
  extra.
- Cap 12 articoli per run, contato sui **corpi recuperati con successo**: un articolo non
  fetchabile resta sul percorso solo-titoli e non brucia un posto.
- Deduplica dei titoli corretta per preferire la copia con `content:encoded`/link diretto
  su quella Google News stub.
- Data indicativa anche solo mensile: già supportata dal normalizer.
- Nuovo campo `articleDescription` su `RawProduct` + fill dedicato, invece di rimuovere
  `news` da `GENERATED_TEXT_KINDS` (quel guard protegge anche dal percorso solo-titoli).
- Data news: solo fill condizionale write-once, **mai** dentro l'upsert del prodotto, sia
  per gli articoli sia per i titoli. Estende al percorso titoli la regola "il negozio
  vince" (oggi una data di titolo può sovrascrivere quella del negozio in upsert).
- `publisher` resta nel prompt di estrazione (contratto identico a quello attuale dei
  titoli, `google-news.ts:197`): serve a disambiguare il gioco nell'LLM e arriva al
  `publisherHint` del `gameFinder` (matching limitato ma gratuito). Nessuna colonna o campo
  nuovo: il `product` non ha publisher e non se ne aggiunge.

## Cosa resta al builder

Piano di implementazione dettagliato (`superpowers:writing-plans`): interfacce esatte, ordine
dei task, test. Nessuna domanda aperta nota.
