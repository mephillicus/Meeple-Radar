# Design — Ciclo di vita dei dati del collector

**Data**: 2026-10-20
**Stato**: approvato in chat (2026-10-20)
**Rif**: design di sistema `docs/superpowers/specs/2026-10-17-meeple-radar-design.md`, design fonte `docs/superpowers/specs/2026-10-18-gemini-collector-design.md`

## 1. Perché

Il database cresce e non si schiarisce. Al 2026-10-20 ci sono 5309 listing e 2572 prodotti, di cui **2237 listing non collegate a nessun prodotto**: 2030 resti del vecchio crawl boardhouse (che prima restituiva il catalogo intero, ora solo i preordini) e 188 di philibert, che in un giro non ha risposto. In più il collector inserisce righe per giochi già usciti, che non interessano più a una app le cui novità mostrano solo uscite future.

Serve una politica: cosa resta, cosa va via, e quando.

## 2. Decisioni (dall'utente)

- **Un solo giro a settimana**, il sabato mattina, al posto dei due cron giornalieri di adesso.
- **Cancellazione per data, a finestra scorrevole di 365 giorni**: una riga **non viene inserita** e se esiste già **viene cancellata** quando la sua `release_date` è più vecchia di **365 giorni** rispetto a oggi. Restano quindi i giochi usciti da meno di un anno e tutti quelli ancora da uscire.
- **Righe senza data**: vengono cancellate se non aggiornate (`last_seen_at`) da **365 giorni**. Prima di quella soglia restano, e vengono completate al giro successivo.
- **I preferiti non si cancellano mai**: una riga con un `user_action` di tipo `favorite` è esente da ogni cancellazione, anche se è datata.
- **Gli "ignora" vanno via con la riga**: un `user_action` di tipo `ignored` che punta a una riga cancellata viene cancellato a sua volta.
- **Ricerca prima di inserire**: prima di scrivere un prodotto si cerca nel database; se esiste **non si reinserisce**, se esiste ma **mancano dati** — la data, il prezzo — **si aggiorna**.
- **Un gioco che sparisce da una fonte resta** in database. Non è una regola di cancellazione: una fonte può omettere un prodotto per un giro (esaurito, temporaneamente fuori lista) senza che il dato sia sbagliato.
- **Le 2237 righe orfane vengono cancellate una volta sola, adesso**, e **non diventa una regola** del ciclo di vita. Così non si rischia di cancellare le righe di una fonte che in quel sabato non ha risposto.

## 3. Decisioni mie (da verificare in builder-check)

- **Ora del giro**: cron `0 6 * * 6` = sabato 06:00 UTC = sabato 08:00 ora italiana. I due cron attuali (`0 5,17 * * *`) vengono rimossi; `workflow_dispatch` resta, per poter lanciare a mano.
- **Soglia della cancellazione**: una riga va via se `release_date < oggi - 365 giorni`, oppure se `release_date` è nullo **e** `last_seen_at < oggi - 365 giorni`. Una finestra scorrevole, non legata all'anno solare: un gioco uscito tre mesi fa resta, uno uscito quattordici mesi fa no.
- **Le preferite sono esenti**: la cancellazione salta ogni riga che ha un `user_action` di tipo `favorite`. La cancellazione di un `ignored` trascina via l'azione: senza `ON DELETE CASCADE` su `user_action` il DELETE viene rifiutato dalla foreign key e il giro intero abortisce, quindi serve una migrazione.
- **Scope della cancellazione**: sia `listing` sia `product`. Un prodotto la cui data è vecchia non deve restare appeso a nuove listing. Le listing cancellate portano via il loro `product_id`, quindi non restano riferimenti sospesi.
- **Ordine del giro**: prima **cancello** tutte le righe datate vecchie, **poi** raccolgo. Così una fonte che in quel sabato riproponesse un gioco già uscito non lo fa rientrare.
- **"Non inserito"**: l'adattatore smista i prodotti appena raccolti e scarta quelli con data vecchia **prima** di toccare il database. È la stessa soglia della cancellazione, applicata in ingresso.
- **Semantica "riempi se manca"**: l'upsert attuale sovrascrive tutto, compresi i `null`. La regola diventa: **il valore in arrivo vince solo se non nullo**; se arriva `null` e nel database c'è un valore, quello **resta**. Vale per `title`, `url`, `price`, `currency`, `release_date`, `image_url`, `game_id`. `last_seen_at` viene sempre aggiornato, perché è l'unico campo che dice "questa riga l'ho vista".
- **La ricerca prima di inserire esiste in parte già**: le listing fanno upsert su `(source_id, external_id)` e i prodotti su `normalized_title`, quindi lo stesso articolo non entra due volte. Quello che manca è la semantica "riempi se manca" qui sopra, non la deduplica.
- **Pulizia una tantum**: fatta a mano con SQL nel Supabase, non con un flag nel codice. Non deve ripartire mai. Il predicato esclude le listing favorite: `product_id is null` e `id not in (select listing_id from user_action where action = 'favorite' and listing_id is not null)`, perché l'`ON DELETE CASCADE` della migrazione 0006 cancellerebbe con la listing anche il preferito che ci punta. I prodotti che restano senza nessuna listing **non** vengono toccati in questa pulizia: è una decisione separata.
- **Un prodotto con data vecchia che ha ancora listing vive non si cancella**: la cancellazione dei prodotti parte solo da quelli a cui **nessuna listing sopravvissuta** punta. Le listing che puntano a un prodotto datato vecchio e che sono esse stesse vecchie vengono cancellate prima; quelle senza data restano, e il prodotto resta con loro.
- **Un giro fallito fallisce il job**, ma una fonte che restituisce zero **non lo fa fallire**: è un avviso. Con un giro a settimana, far fallire il job per philibert che un sabato non risponde significherebbe perdere il giro intero. Il re-run manuale resta possibile con `workflow_dispatch`.

## 4. Obiettivi

- Il database contiene solo giochi **non ancora usciti**.
- Un articolo non viene mai scritto due volte, e un giro non perde dati già raccolti solo perché una fonte ha risposto vuoto.
- Il giro parte da solo una volta a settimana e basta.

## 5. Non-obiettivi

- Non si cancella una riga perché la fonte ha smesso di mostrarla. Resta finché non invecchia di data o di `last_seen_at`.
- Non si cancella una riga solo perché non è collegata a un prodotto.
- Non si tocca un `user_action` da solo: le azioni vengono cancellate **solo** insieme alla riga a cui appartengono.
- Non cambia il modo in cui l'app legge i dati.

## 6. Anti-obiettivi

- **Un preferito che sparisce**: è l'unico danno che non si può tornare indietro, ed è per questo che le preferite sono esenti da ogni cancellazione. Resta un caso limite: se una preferita ha una data vecchia, resta in database con una data passata. È il prezzo scelto per non perdere mai la scelta dell'utente.
- **Un "ignora" che sparisce con la sua riga**: voluto. L'app esclude un gioco se è marcato con `product:<id>` **oppure** con `listing:<id>` (`IncomingMapper.kt:39`). Cancellare una riga fa sparire il suo segno. Le righe con data futura — quelle che nelle novità contano davvero — non vengono mai cancellate, quindi l'ignore lì è al sicuro.
- **Una fonte che non risponde**: il rischio vero non è più la cancellazione, che riguarda solo righe vecchie, ma il fatto che **un giro a settimana** moltiplica per sette il tempo di un guasto. Mitigato tenendo `workflow_dispatch` per il re-run manuale, e non facendo fallire il job per una fonte che ha restituito zero.
- **Dati persi perché un campo è vuoto**: mitigato dalla semantica "riempi se manca". Una fonte che per un giro non ha il prezzo non azzera quello precedente.

## 7. Vincoli

- Node 22 / TypeScript ESM (import con `.js`), vitest + `tsc --noEmit`.
- Il collector gira in GitHub Actions su `ubuntu-latest` con Node 22.
- Segreti solo come variabili d'ambiente (`SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`, `GEMINI_API_KEY`, `JINA_API_KEY`).
- Commenti in italiano solo dove i vicini ne hanno già.
- Nessun push senza richiesta esplicita.
- La spec di sistema va allineata (cadenza settimanale, politica di cancellazione).
- Serve **una migrazione**: `ON DELETE CASCADE` su `user_action.listing_id` e `user_action.product_id`. Non serve su `listing.product_id`: i prodotti si cancellano solo quando nessuna listing sopravvissuta li referenzia, quindi la foreign key non viene mai violata.

## 8. Come si collega

- `supabase/migrations/` — migrazione per il `CASCADE` sulle foreign key di `user_action`.
- `src/db.ts` — `upsertListings` e `upsertProducts` prendono la semantica "riempi se manca"; serve un metodo che cancelli le righe vecchie saltando le preferite.
- `src/run.ts` — all'apertura cancella le righe vecchie; nello smistamento scarta i prodotti datati oltre la finestra prima di scrivere.
- `src/normalize.ts` — nessun cambiamento: la soglia usa `release_date` già normalizzato.
- `.github/workflows/collect.yml` — un solo cron, sabato mattina; via quello giornaliero.
- L'app Android non cambia.
