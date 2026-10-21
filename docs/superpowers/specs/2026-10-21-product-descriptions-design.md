# Design — Descrizione in italiano sui giochi

**Data**: 2026-10-21
**Stato**: approvato in chat (2026-10-21)
**Rif**: `docs/superpowers/specs/2026-10-20-collector-lifecycle-design.md`, `docs/superpowers/specs/2026-10-18-gemini-collector-design.md`

## 1. Perché

Il collector raccoglie prezzo, data, immagine e URL, ma non dice **cos'è il gioco**. L'app non ha nulla da mostrare nella scheda di un gioco.

Il dato esiste già per quasi tutte le fonti e viene buttato via: `RawProductSchema` ha `descriptionRaw` (`types.ts:14`), gli adattatori lo riempiono — `shopify.ts:43` (`body_html`), `woocommerce.ts:41` (`description`), `wordpress.ts:52` (`content.rendered`), `html/extract.ts:131` (`og:description`) — e `normalize.ts:112` lo usa **solo** per estrarre la data, poi lo scarta.

Serve portarlo fino al database.

## 2. Verifica sulle fonti (recon 2026-10-21)

| Fonte | kind | Descrizione oggi | Fetch extra | Data |
|---|---|---|---|---|
| cranio · boardhouse · supernova | shopify | ✓ `body_html` 341–1296 car. su 5/5 campione | nessuna | pubblicazione |
| pendragon · littlerocket | woocommerce | ✓ `description` su 5/5 campione | nessuna | — |
| msedizioni | woocommerce | ⚠️ 0/5 `description`, 3/5 `short_description` | nessuna | — |
| ghenos | wordpress | ⚠️ 52% via API (216/412), 47% no | **serve fetch pagine (412)** | testo `Day one` |
| asmodee | html | ✓ `og:description` 143 car. | nessuna | solo pubblicazione |
| giochiuniti | html | ✓ JSON-LD **1759 car.** | nessuna | nessuna |
| dungeondice | headless | ⚠️ `og:description` 174 car. (testo copiato), meglio `[itemprop=description]` 1512 car. | nessuna | `available_date` stimata |
| dvgiochi · horribleguild · gateongames | html | ✗ servono 3 selettori nuovi | **nessuna** | nessuna (dvgiochi già da anchor) |
| philibert | html | da rimuovere | — | — |
| ravensburger · uplay · magicmerchant | disabilitate | fuori scope | — | — |

Due fatti determinanti:

- **`crawl.ts:48` scarica già ogni pagina prodotto** per tutte e sei le fonti `html`. Aggiungere un selettori non costa una richiesta in più.
- **L'unica fonte che richiede scraping nuovo è ghenos**, perché il suo adattatore legge solo l'API bulk (`wp/v2/product`) e non tocca mai le pagine.

Verifica fatta con l'anon key: su `run_log` e `user_action` la RLS restituisce righe vuote **senza errore**, quindi i conteggi vanno letti con `service_role` (da qui: 277 righe philibert, 7 preferiti in totale, **0 preferiti tra le philibert**).

**Conteggio chiave su ghenos**: letti tutti i 412 prodotti dall'API, `content.rendered` è vuoto su 196 e presente su 216 — ma di quei 216 solo **7** contengono `Day one`. La data di uscita vive nel campo Elementor della pagina prodotto, non nel contenuto WordPress: l'API la restituisce quindi su **7 prodotti su 412**. Conseguenza: scaricare la pagina è necessario per la data su ~405 prodotti, quindi fra fetch incondizionato (412) e condizionato (405) la differenza è di **7 richieste**. Non vale il costo di portare lo stato del database dentro l'adattatore.

Conteggi al 2026-10-21: 5413 listing, 4687 prodotti, 2341 listing senza prodotto collegato.

## 3. Decisioni (dall'utente)

- **La descrizione sta sul gioco** (`product`), non sul listing. È una proprietà di "cos'è questo gioco", non di "quanto costa in questo negozio".
- **Riempie solo se manca, non sovrascrive mai**. Una descrizione già salvata resta, anche se una fonte successiva ne porterebbe un'altra o nulla.
- **Stesso comportamento per tutte le fonti che raccolgono dai negozi**, senza eccezioni. L'unica fuori è `google-news`, deciso separatamente qui sotto.
- **Se l'API non dà data e descrizione, si scansiona comunque la pagina del prodotto**. A cadenza settimanale il costo non conta (ghenos: 412 richieste in più, ~1 minuto, contro i ~67 minuti del giro attuale).
- **La data di ghenos viene dal testo** (`Day one 27 novembre`), **mai** la data di pubblicazione che restituisce l'API WordPress — quella è la data in cui il negozio ha pubblicato l'articolo, non l'uscita del gioco.
- **Philibert va tolto**, **per intero**: le 277 listing, i prodotti che restano senza nessuna altra listing che li punti, e la riga nella tabella `source`. L'app legge quella tabella, quindi lasciarla significherebbe mostrare una fonte abilitata che non viene mai più aggiornata.
- **Le descrizioni generate da Gemini no**: `google-news` riempie `descriptionRaw` con una frase ricavata dal titolo. Quel testo **non** finisce in `product.description`. Vale lo stesso comportamento su tutte le fonti **che raccolgono dai negozi**; le notizie restano fuori, perché §6 vieta il testo generato e perché con la write-once una frasetta da titolo, se arriva prima del negozio, bloccherebbe per sempre quella vera.

## 4. Decisioni mie (da verificare in builder-check)

- **Colonna `product.description text`, nessuna colonna su `listing`.** La descrizione viene persistita solo sul gioco. Le listing lette dal database in `run.ts:56-67` portano quindi `description: null`, e quella vera arriva dalla riga corrente (`run.ts:68`): il prodotto prende la descrizione dalla fonte che l'ha fornita in questo giro.
- **La write-once va costruita, non esiste già.** La commit `95bbdfc` omette dal payload le chiavi con valore null, e questo protegge solo dall'azzera-re: PostgREST in `ON CONFLICT DO UPDATE` sovrascrive ogni colonna **presente** nel payload. Ma il prodotto viene canonizzato a partire dalla riga corrente (`run.ts:68`), che una descrizione ce l'ha sempre: senza un meccanismo esplicito la fonte che risponde per ultima vincerà giro dopo giro, e la frase dell'utente "una descrizione già salvata resta" non si avvererebbe.
- **Meccanismo scelto: due statement, nessuna lettura.** L'upsert del prodotto viene fatto **senza** `description` nel payload — così sul conflitto la colonna non viene toccata — e poi, solo se la descrizione in arrivo non è vuota dopo la trim, un `update product set description = :d where id = :id and (description is null or btrim(description) = '')`. L'inserimento viene riempito, il conflitto resta intatto, e la colonna non accetta mai testo vuoto. Un `SELECT` preventivo sarebbe equivalente ma costa un round-trip in più per prodotto.
- **"Manca" significa null oppure solo spazi**, dopo la conversione HTML → testo. Così una stringa di quattro caratteri o vuota di msedizioni non si appiccica alla colonna e non blocca per sempre il testo vero.
- **`Listing.description: string | null` nel tipo TypeScript** (`types.ts:33`), in memoria, non persistito. Serve per far passare il valore da `normalizeListing` a `canonicalizeProduct`.
- **HTML → testo**: strip dei tag + decode delle entità, fatto in `normalize.ts`. La descrizione salvata è testo leggibile, non markup — altrimenti l'app dovrebbe renderizzare HTML e giochiuniti restituirebbe letteralmente `&#039;`.
- **Tre selettori nuovi**, su pagine già scaricate: `dvgiochi` (prosa visibile 369 car., oggi il suo `extract` custom non passa nemmeno `description`), `horribleguild` (1284 car. in `#tab-description`, custom), `gateongames` (oltre 1500 car. di prosa visibile, nessun meta — oggi il condiviso restituisce `undefined`).
- **msedizioni: fallback su `short_description`** quando `description` è vuoto. Senza, questa fonte darebbe descrizione su un terzo dei prodotti e basta.
- **giochiuniti: decode delle entità** nel JSON-LD, dove `&#039;` arriva crudo.
- **dungeondice: usare `[itemprop=description]`** (1512 car.) invece dell'`og:description` (174 car. di testo copiato e incollato identico su ogni prodotto).
- **ghenos: fetch di tutte le 412 pagine prodotto**, incondizionato. Motivo: la data non sta nell'API (7/412), quindi il fetch serve comunque per quasi tutto il catalogo e risparmiare significherebbe controllare lo stato del database dall'interno dell'adattatore per guadagnare 7 richieste. **Testo della pagina = fonte di verità**; `content.rendered` resta il ripiego quando la pagina non ha descrizione. La data si legge dal testo (`Day one 27 novembre`), mai da `date`/`publishedAtRaw`.
- **`news` e `gemini` non scrivono descrizioni.** L'esclusione va fatta in `run.ts`, dove `source.kind` è a portata di mano, **dopo** `normalizeListing`: il campo `descriptionRaw` deve restare, perché `normalize.ts:112` lo usa ancora per estrarre la data.
- **philibert: cancellazione una tantum e completa**, non lasciata alla purge a 365 giorni. Zero preferiti tra le 277 righe, quindi niente di protetto viene toccato. Si cancellano le listing, **poi** i prodotti che a quel punto non hanno più nessuna listing che li punti (mai quelli ancora referenziati da altre fonti), **poi** la riga in `source`. Come la pulizia del ciclo di vita: SQL fatto a mano, non una regola che può ripartire.

## 5. Obiettivi

- Ogni gioco in database ha, quando la fonte la offre, una descrizione in italiano di testo leggibile.
- Una descrizione salvata non viene mai persa o sovrascritta da un giro successivo.
- Le fonti che oggi la raccolgono e la buttano via la iniziano a scrivere.

## 6. Non-obiettivi

- Non si descrive il gioco con un modello: la descrizione è **solo** ciò che il negozio scrive. Niente testo generato, niente riassunti — **`news` e `gemini` restano fuori da `product.description`**.
- Non si tocca il modo in cui l'app legge i dati. La colonna è nuova e nullable: l'app continua a funzionare prima della migrazione.
- Non si aggiungono colonne descrizione su `listing`.
- Non si aggiunge nessun fetch oltre a ghenos: le altre fonti `html` scaricano già le pagine.
- Non si recupera la descrizione dei giochi già esauriti o fuori catalogo: arriva al giro in cui la fonte la mostra.
- Non si aggiunge nessun controllo dello stato del database dentro gli adattatori.

## 7. Anti-obiettivi

- **Descrizione duplicata come testo copiato**: dungeondice genera per ogni prodotto lo stesso schema ("Scopri X di Y un fantastico gioco da tavolo…"). Prendere il `itemprop` più lungo invece dell'`og:description` evita di riempire il database di quattro righe identiche.
- **Markup HTML nel database**: le fonti shopify e wordpress restituiscono HTML. Se non si converte in testo, l'app mostra tag al posto delle frasi.
- **Una fonte che "risponde" con una descrizione vuota non deve azzerare quella esistente**: garantito dal fatto che l'upsert non porta `description` e che l'update è condizionato a `(description is null or btrim(description) = '')`.
- **La prima descrizione arriva e non se ne va più**: è il prezzo scelto con la write-once. Se la prima che arriva è un banale tagline da testa di lista, un testo migliore che arriva dopo non la sostituisce. L'unica difesa è non far entrare testo di bassa qualità in partenza — per questo fuori stanno le frasi generate da Gemini e i `meta name="description"` da tre righe da scheda categoria.
- **Una notizia che batte il negozio in corsa**: `google-news` gira per ultima nel seed ed è abilitata. Con la write-once basterebbe che un annuncio entrasse prima della prima lista del negozio per rendere permanente una frase ricavata dal titolo. Esclusa da `product.description`.
- **ghenos che viene scavalcato**: l'API WordPress restituisce `content.rendered` vuoto su 196 prodotti su 412. Se non si scansiona la pagina, quasi metà del catalogo resta senza descrizione — è il motivo per cui il fetch esiste.
- **Pulizia Philibert che cancella un preferito**: escluso, `user_action` tra le 277 righe è vuoto. Va però rilettura del conteggio con `service_role` prima dell'esecuzione, perché con l'anon key la RLS fa apparire tutto vuoto.

## 8. Vincoli

- Node 22 / TypeScript ESM (import con `.js`), vitest + `tsc --noEmit`.
- Da `collector/`: `node node_modules\typescript\bin\tsc --noEmit` e `node node_modules\vitest\vitest.mjs run`. `npx` è bloccato.
- Migrazione: **`0007_add_product_description.sql`**, `alter table product add column if not exists description text;` — aggiuntiva, non modificativa, reversibile di fatto.
- Nessun push senza richiesta esplicita.
- Commenti in italiano solo dove i vicini ne hanno già.
- `service_role` solo per letture di verifica: mai stampata, mai committata.
- Non committare il lavoro parallelo su Android / `.vite` / `collection.csv`.

## 9. Come si collega

- `supabase/migrations/0007_add_product_description.sql` — colonna `product.description`.
- `src/types.ts` — `Listing.description: string | null`.
- `src/normalize.ts` — `normalizeListing` passa `descriptionRaw` → testo (strip tag + decode entità), nel posto in cui oggi lo scarta.
- `src/merge.ts` — `canonicalizeProduct` porta `description` con `chooseCanonicalField`.
- `src/run.ts` — il mapping delle listing dal DB (`run.ts:56-67`) aggiunge `description: null`; dopo `normalizeListing` si azzera `description` per i kind `news` e `gemini`.
- `src/db.ts` — `upsertProducts` manda l'upsert senza `description` e poi l'update condizionato; nuovo metodo sullo store.
- `src/adapters/html/dvgiochi.ts`, `horribleguild.ts`, `gateongames.ts` — selettori di descrizione.
- `src/adapters/html/dungeondice.ts` — nessuna modifica: il fallback va nell'estrattore condiviso (vedi sotto), perché è `extractProduct` a scegliere fra OpenGraph e JSON-LD.
- `src/adapters/html/extract.ts` — fallback `div[itemprop=description]`, più il blocco di `meta name="description"` che resta fuori.
- `src/adapters/woocommerce.ts` — fallback `short_description`.
- `src/adapters/wordpress.ts` — fetch delle 412 pagine prodotto: descrizione dal testo, data da `Day one`, ripiego su `content.rendered`.
- `src/sources.seed.ts` — via `philibert`.
- SQL a mano — cancellazione completa di philibert (listing → prodotti orfani → riga `source`).
