# Design — Fonte Gemini (novità dall'LLM)

**Data**: 2026-10-18
**Stato**: approvato in chat (2026-10-18)
**Rif**: design di sistema `docs/superpowers/specs/2026-10-17-meeple-radar-design.md`, plan `docs/superpowers/plans/2026-10-17-meeple-radar-05-produttori.md` (completato)

## 1. Perché

La sezione novità dell'app conosce solo ciò che vedono i 13 scraper: un gioco pubblicato da un editore non coperto, o annunciato tardi, è invisibile. Una ricerca Gemini (LLM con accesso web) sa rispondere "quali giochi in scatola escono nei prossimi due mesi?" con una lista strutturata. L'idea: Gemini dà lo **scheletro** delle uscite; il database che popolano gli scraper fornisce i **dati veri** (prezzo, data, foto). L'utente ha provato la ricerca manualmente e funziona.

## 2. Decisioni (dall'utente)

- La ricerca gira **ogni giorno**, come se fosse uno scraper: nel collector, in GitHub Actions, con `GEMINI_API_KEY` come segreto (l'utente ha già la chiave).
- Gemini produce la **lista strutturata**; i dati mancanti si prendono dal database esistente.
- I giochi che solo Gemini conosce **restano** comunque, con i dati del modello.
- Le foto della scatola vengono dai publisher (dati già nel DB), non dal modello.
- L'app ha già il pulsante "ignora" che toglie un gioco dalla lista delle novità: è il meccanismo per "segnalarlo come vecchio".
- Quando Gemini conosce il **codice articolo** del publisher, lo chiede e lo usa come chiave d'abbinamento (es. `BLAN01IT`).
- Quando Gemini non va, la ricerca **cascata su un secondo modello Gemini**, sulla stessa chiave, con lo **stesso prompt**: il primario è il modello veloce, il ripiego è il modello più capace.
- Ogni chiamata ha un **timeout di 3 minuti**: se la risposta non arriva, la richiesta viene annullata e si passa al modello successivo.
- **Una sola chiave**: non serve nessun altro fornitore. Le chiavi Groq e OpenRouter sono state tolte: un secondo vendor aggiunge quota da tenere d'occhio senza garantire nulla in più.
- **Niente ricerca web da parte dell'LLM.** È stata tolta anche la grounding con Google Search: il tier gratuito non ha quota per la ricerca, e un modello lasciato a sé risponde o vuoto o inventando. Al suo posto i dati arrivano **dentro il prompt**, scaricati dal collector: prima dai feed RSS di Google News (fonte `google-news`), poi da Gemini che estrae dai titoli i giochi di cui si annuncia l'uscita.
- La fonte `gemini` standalone è **disabilitata**: senza grounding non ha più nulla da dire. Il kind e l'adapter restano, così si può riabilitare se serve un estrattore senza input.

## 3. Decisioni mie (da verificare in builder-check)

- **Due modelli, una chiave**: primario `gemini-3.5-flash-lite` (`GEMINI_MODEL`), ripiego `gemini-3.6-flash` (`GEMINI_FALLBACK_MODEL`). Il vecchio `gemini-2.0-flash` è stato dismesso il 2026-06-01 e restituiva 404. Se i due valori coincidono il ripiego non viene aggiunto. Se il primario non esiste (404) la cascata se ne accorge al primo tentativo e passa al ripiego: il costo di un modello sbagliato è una chiamata, non un run perso.
- **Nessun tool, JSON mode attivo**: la richiesta non include `tools` e impone `responseMimeType: application/json`. Senza tool il JSON mode è disponibile e garantisce un output parseabile. La grounding con Google Search è stata tolta: storicamente non era combinabile col JSON mode (400, e per un periodo disattivava il grounding **in silenzio**, cookbook#1274) e, soprattutto, il free tier non ha quota per la ricerca. I dati al modello arrivano dal chiamante.
- **Chiave nell'header**: `x-goog-api-key` invece del parametro `?key=` nell'URL, così in caso di errore la chiave non finisce nei log.
- **Formato di risposta**: **oggetto** con una sola chiave `releases` (array), non un array nudo. Non è più un vincolo dei provider (ne restano solo uno, che non ha JSON mode), ma resta la forma chiesta al modello perché è più difficile che un generatore di prosa la infranga. Il parser accetta comunque entrambe le forme.
- **Timeout**: `AbortSignal.timeout(3 min)` su ogni chiamata. Con la ricerca web una risposta richiede da qualche secondo a un minuto; senza timeout, una richiesta appesa bloccherebbe l'intero collector.
- **Nessun nuovo workflow**: la fonte `gemini` è una source nel seed e gira con `npm run collect` (cron 5:00/17:00) come le altre; serve solo il segreto in più. Due chiamate al giorno sono idempotenti (upsert).
- **Nuovo kind**: il set chiuso `SOURCE_KINDS` si estende con `'gemini'` (richiede aggiornamento della spec di sistema).
- **Abbinamento**: per normalizzazione del titolo (minuscole, niente punteggiatura) con publisher come indizio. Il DB **non** ha i codici articolo (gli `external_id` slug), quindi il join è per titolo; il codice articolo è salvato come `external_id` della listing Gemini quando c'è, altrimenti uno slug del titolo. Se l'abbinamento è ambiguo, non abbina (restano i dati del modello).
- **url sintetica**: le listing Gemini non hanno un vero URL (la colonna è NOT NULL e lo schema richiede un url valido). Si usa un link di ricerca DuckDuckGo costruito dal titolo: cliccabile e onesto.
- **Mai cancellare**: il job Gemini fa solo upsert (update in place), mai delete. Così la chiave `listing:<id>` resta stabile e gli "ignora" dell'app persistono.
- **game_id**: quando una listing Gemini si abbina a un gioco del DB, si setta `game_id` — l'app raggruppa per chiave gioco e merge le offerte, quindi un gioco compare una sola volta (la listing Gemini si fonde nella scheda del gioco, con il suo prezzo indicativo accanto a quello vero). Serve a estendere il tipo `Listing` con `gameId` opzionale e a persisterlo nell'upsert.
- **Provenienza visibile**: la source si chiama "Gemini"; l'app mostra il nome della fonte accanto al prezzo, quindi l'utente vede quali dati sono indicativi. Questo copre l'anti-obiettivo "prezzi sbagliati" in modo onesto, senza nuove colonne.

## 4. Obiettivi

- Ogni giorno, automaticamente, produrre la lista strutturata delle uscite dei prossimi due mesi.
- Ogni entry: titolo, publisher, codice articolo (se noto), prezzo, data di uscita, breve descrizione (modalità/contesto).
- Arricchire ogni entry con i dati veri del DB quando il gioco esiste già.
- L'app la legge senza alcun cambiamento.

## 5. Non-obiettivi

- Nessuna ricerca per-game (una sola chiamata LLM per esecuzione, non per gioco).
- Nessun match BGG (Piano 02 rimandato).
- Nessuna verifica umana automatica dei dati del modello.
- Non sostituire gli scraper: restano la fonte di verità per prezzo/data/foto.

## 6. Anti-obiettivi

- **Dati del modello mostrati come veri**: mitigato dalla provenienza visibile (nome fonte) e dal join che sostituisce i dati indicativi con quelli veri quando disponibili.
- **Giochi inventati (allucinazioni)**: mitigato dal pulsante "ignora". Un gioco **con** data scade da solo (la lista novità mostra solo uscite future); un gioco **senza** data restano finché l'utente non lo ignora, o finché una run successiva non gli assegna una data.
- **"Ignora" che si perde**: mitigato dal non cancellare mai le listing Gemini (solo upsert).

## 7. Vincoli

- Node 22 / TypeScript ESM (import con `.js`), vitest + `tsc --noEmit`.
- Il collector gira in GitHub Actions su `ubuntu-latest` con Node 22.
- Segreti solo come variabili d'ambiente (`SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`, `GEMINI_API_KEY`, `JINA_API_KEY`). Basta `GEMINI_API_KEY` per far girare la fonte LLM.
- Commenti in italiano solo dove i vicini ne hanno già.
- Nessun push senza richiesta esplicita.
- La spec di sistema va allineata (nuovo kind `gemini`, nuova fonte).

## 8. Come si collega (decisioni architetturali)

- `SOURCE_KINDS` + `'gemini'`; `buildRegistry` ottiene un adapter `fetchGeminiProducts`.
- L'adapter ha bisogno del DB per il join: `buildRegistry(http?, gameFinder?)` riceve una funzione di lookup (il `run.ts` la costruisce dallo store). Gli altri adapter non cambiano.
- Lo store guadagna un metodo `findListingsByTitle(...)` (o equivalente). **Il join gira sul titolo delle listing**, non sui nomi di gioco: la tabella `game` è vuota finché non arriva il match BGG (Piano 02 rimandato), quindi l'unica destinazione popolata è `listing`. Quando la listing trovata ha un `game_id`, lo copia.
- `RawProduct`/`Listing` guadagnano `gameId` opzionale; `upsertListings` lo persiste nella colonna `game_id` (esiste già).
- `config.ts` legge `GEMINI_API_KEY`; i due nomi dei modelli e il flag `GEMINI_SEARCH` li legge invece `buildProviders` in `src/adapters/ai-providers.ts`, perché servono solo a quel modulo.
- La entry in seed: `{ id: 'gemini', name: 'Gemini', kind: 'gemini', baseUrl: 'https://generativelanguage.googleapis.com', enabled: true }`.
- Il prompt vive nel repo (`collector/prompts/...`) e viene letto dall'adapter.
- Il parser: estrae l'oggetto JSON dalla risposta (tolle i fence ```json, accetta anche un array nudo e lo smonta se è avvolto in `{"releases": [...]}`), valida che ogni entry abbia un titolo non vuoto, normalizza i campi.

## 9. Cosa resta al builder

- La formula esatta del prompt (deve chiedere JSON i mesi corrente e successivo, in italiano, con i campi fissi).
- Le regole di normalizzazione di titolo e publisher.
- La query di lookup e la gestione dei casi ambigui.
- La gestione degli errori (API down, parse fallito, entry parziali).
- I test (parser, join, seed) e l'allineamento della spec di sistema.

## 10. Cascata di modelli Gemini

Un solo modello rende la fonte fragile: un 429/503, un modello dismesso o una ricerca che non parte azzera le novità del giorno. La fonte prova quindi in sequenza **due modelli Gemini sulla stessa chiave**, con lo **stesso prompt**.

| Ordine | id | Modello | Env | Tentativi | Perché |
|---|---|---|---|---|---|
| 1 | `gemini` | `gemini-3.5-flash-lite` | `GEMINI_MODEL` | 5 | economico e veloce, basta per una lista di uscite |
| 2 | `gemini-fallback` | `gemini-3.6-flash` | `GEMINI_FALLBACK_MODEL` | 4 | più capace, fa da rete di sicurezza |

**Perché solo Gemini:** aggiungere un secondo vendor (Groq, OpenRouter) significava tenere d'occhio tre quote giornaliere e tre formati di API per un'incognita che il grounding già risolve: senza ricerca web il modello inventa, con la ricerca web restituisce quello che trova. Una sola chiave da custodire, un solo formato di chiamata, un solo fornitore da cui aspettarsi un'interruzione. Se il grounding smette di bastare, il secondo modello è il posto dove provare prima di reintrodurre un vendor.

**Regole della cascata** (`src/adapters/ai-providers.ts` + `src/adapters/gemini.ts`):

- **Priorità al primario**: 5 tentativi contro 4. Finché il primo modello risponde con un array non vuoto, il secondo non viene toccato.
- Si passa al modello successivo quando: la chiamata fallisce (404/401/429/503, timeout di 3 minuti), la risposta non è JSON interpretabile, oppure l'array restituito è **vuoto**.
- **Retry mirato, dentro al modello**: attesa esponenziale 5s, 10s, 20s, 40s (massimo 60s). Si ripete su 500/502/503/504, su 429 *da limite al minuto*, e su una risposta ricevuta ma vuota o troncata, che è non deterministica. Un 429 il cui corpo parla di un limite **giornaliero** (`PerDay`) non viene ritentato: riprovare sprecherebbe quota senza alcuna possibilità di successo, e la distinzione si fa leggendo il corpo dell'errore, non un header. Un 404/401 non viene ritentato affatto. Il retry vive nel modello e non nell'orchestrator, che altrimenti deciderebbe senza vedere status e corpo.
- Un array vuoto valido su **entrambi** i modelli restituisce `[]` senza errore (il run finisce in `zeroed`, che la monitoraggia già); se **nessuno** dei due risponde, la fonte fallisce con tutti gli errori in un unico messaggio.
- Se `GEMINI_MODEL` e `GEMINI_FALLBACK_MODEL` coincidono, il ripiego non viene aggiunto: la cascata si riduce a un singolo modello.
- Se il modello primario non esiste (404, es. un nome scritto male in env) il primo tentativo basta a capirlo e si passa al ripiego.

**Limite noto:** nessuna quota di ricerca da gestire, perché non c'è ricerca. Le due chiamate al giorno (cron 5:00/17:00) con qualche retry stanno nel free tier.

## 11. Dove finiscono i dati: Google News → Gemini

Il problema di fondo era che un LLM chiesto "cosa esce fra due mesi?" risponde o vuoto o inventando, perché non ha accesso a fatti recenti. La soluzione adottata non è dare la ricerca al modello, ma **darli dei fatti già raccolti**.

`google-news.ts` fa due passi:

1. **Scarica i titoli.** Da due tipi di fonte, tutte RSS 2.0 e tutte dentro la stessa finestra di 30 giorni: le cinque ricerche su **Google News** in italiano (`NEWS_QUERIES`, ognuna con l'operatore `when:` che limita l'età), e i **feed RSS dei siti** (`SITE_FEEDS`). I titoli più vecchi di 30 giorni vengono scartati, i doppioni eliminati (la stessa notizia compare in più ricerche e su più testate), si tiene al massimo 80 titoli, i più recenti.
2. **Li passa a Gemini** con un prompt che chiede di estrarre **solo** i giochi nominati esplicitamente, senza completare editori, prezzi o date che nei titoli non ci sono. La cascata a due modelli è la stessa della fonte Gemini.

I feed dei siti stanno in una classe separata dalle query perché **non si comportano come gli scraper**. `houseofgames.it`, per esempio, è un magazine senza prezzi: il suo pezzo `GIOCHI DA TAVOLO: TUTTE Le Nuove Uscite Di Ottobre` contiene venti giochi in un solo articolo, cosa che un estrattore HTML non sa spezzettare. Passandolo all'LLM invece il problema sparisce, perché quello legge il titolo e restituisce tante uscite quante ne nomina.

I titoli restituiscono il **nome** del gioco, raramente prezzo o data: quei campi arrivano poi dal `gameFinder`, che cerca la stessa cosa nel DB popolato dagli scraper e ne copia prezzo, data e foto. Se il gioco non esiste ancora nel DB, resta con i soli dati del titolo e la provenienza visibile ("Google News").

**Perché funziona dove il grounding non funzionava:** il filtro "solo giochi nominati nel titolo" è verificabile dall'input. Il modello non ha spazio per inventare, perché se un nome non c'è in un titolo la regola glielo vieta. Con il grounding la stessa regola non si poteva far rispettare, dato che i fatti li sceglieva il modello.

**Limite noto:** il segnale è rumoroso. Su 128 titoli raccolti dal vivo, Google News restituisce molte serate di gioco, offerte e annunci di fiere, e il feed di House of Games è per due terzi recensioni di giochi **già usciti**. Le regole `NON videogiochi, film, fiere o eventi` e `di cui un titolo annuncia un'uscita... in arrivo` nel prompt servono a scartarli, ma una quota di rumore resta: è il prezzo di non lasciare a un modello la scelta delle fonti. I campi prezzo/data resteranno spesso vuoti, perché i titoli non li contengono: quei campi arrivano dal `gameFinder` sul DB popolato dagli scraper.
