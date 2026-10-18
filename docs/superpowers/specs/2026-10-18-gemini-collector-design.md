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
- Quando Gemini non va, la ricerca **cascata su altri due LLM gratuiti** — Groq e poi OpenRouter — con lo **stesso prompt**; Gemini resta il primario e ha la priorità.
- Ogni chiamata ha un **timeout di 5 minuti**: se la risposta non arriva, la richiesta viene annullata e si passa al provider successivo.
- Su OpenRouter si usano **solo i modelli free** (il router `openrouter/free`); un modello a pagamento non deve mai essere invocato per caso.

## 3. Decisioni mie (da verificare in builder-check)

- **Modello**: `gemini-3.6-flash` (GA corrente, economico e veloce; sovrascrivibile con la env `GEMINI_MODEL`). Il vecchio `gemini-2.0-flash` è stato dismesso il 2026-06-01 e restituiva 404. La catena di fallback aggiunge `openai/gpt-oss-120b` su Groq e `openrouter/free` su OpenRouter (sovrascrivibili con `GROQ_MODEL` / `OPENROUTER_MODEL`).
- **Formato di risposta**: **oggetto** con una sola chiave `releases` (array), non un array nudo. Motivo pratico: la JSON mode di Groq/OpenRouter valida la risposta come *oggetto* e rifiuta un array di primo livello con `json_validate_failed`. Il parser accetta comunque entrambe le forme, quindi un array torna comunque interpretato.
- **Timeout**: `AbortSignal.timeout(5 min)` su ogni chiamata. Senza, una richiesta appesa bloccherebbe l'intero collector.
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
- Segreti solo come variabili d'ambiente (`SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`, `GEMINI_API_KEY`, `GROQ_API_KEY`, `OPENROUTER_API_KEY`). Le ultime due sono **opzionali**: se mancano, quei provider vengono semplicemente saltati.
- Commenti in italiano solo dove i vicini ne hanno già.
- Nessun push senza richiesta esplicita.
- La spec di sistema va allineata (nuovo kind `gemini`, nuova fonte).

## 8. Come si collega (decisioni architetturali)

- `SOURCE_KINDS` + `'gemini'`; `buildRegistry` ottiene un adapter `fetchGeminiProducts`.
- L'adapter ha bisogno del DB per il join: `buildRegistry(http?, gameFinder?)` riceve una funzione di lookup (il `run.ts` la costruisce dallo store). Gli altri adapter non cambiano.
- Lo store guadagna un metodo `findListingsByTitle(...)` (o equivalente). **Il join gira sul titolo delle listing**, non sui nomi di gioco: la tabella `game` è vuota finché non arriva il match BGG (Piano 02 rimandato), quindi l'unica destinazione popolata è `listing`. Quando la listing trovata ha un `game_id`, lo copia.
- `RawProduct`/`Listing` guadagnano `gameId` opzionale; `upsertListings` lo persiste nella colonna `game_id` (esiste già).
- `config.ts` legge `GEMINI_API_KEY`; le chiavi dei provider di fallback (`GROQ_API_KEY`, `OPENROUTER_API_KEY`) le legge invece `buildProviders` in `src/adapters/ai-providers.ts`, perché servono solo a quel modulo.
- La entry in seed: `{ id: 'gemini', name: 'Gemini', kind: 'gemini', baseUrl: 'https://generativelanguage.googleapis.com', enabled: true }`.
- Il prompt vive nel repo (`collector/prompts/...`) e viene letto dall'adapter.
- Il parser: estrae l'oggetto JSON dalla risposta (tolle i fence ```json, accetta anche un array nudo e lo smonta se è avvolto in `{"releases": [...]}`), valida che ogni entry abbia un titolo non vuoto, normalizza i campi.

## 9. Cosa resta al builder

- La formula esatta del prompt (deve chiedere JSON i mesi corrente e successivo, in italiano, con i campi fissi).
- Le regole di normalizzazione di titolo e publisher.
- La query di lookup e la gestione dei casi ambigui.
- La gestione degli errori (API down, parse fallito, entry parziali).
- I test (parser, join, seed) e l'allineamento della spec di sistema.

## 10. Cascata di provider LLM (fallback gratuiti)

Un solo LLM rende la fonte fragile: un 429/503 o un modello dismesso azzera le novità del giorno. La fonte quindi prova in sequenza più provider **gratuiti**, con lo **stesso prompt**.

| Ordine | Provider | Endpoint | Default | Limite free (ott 2026) |
|---|---|---|---|---|
| 1 | Gemini | `generativelanguage.googleapis.com/v1beta/models` | `gemini-3.6-flash` (`GEMINI_MODEL`) | free tier AI Studio |
| 2 | Groq | `api.groq.com/openai/v1` | `openai/gpt-oss-120b` (`GROQ_MODEL`) | 30 RPM / 1000 richieste al giorno, niente carta |
| 3 | OpenRouter | `openrouter.ai/api/v1` | `openrouter/free` (`OPENROUTER_MODEL`) | 20 RPM / 50 richieste al giorno (1000 con 10$ una tantum) |

**Perché questa coppia:** Groq offre il margine più ampio (1000 richieste al giorno) con JSON mode nativo e risposte in ~4 s; OpenRouter chiude la catena con il router `openrouter/free`, che a ogni chiamata sceglie tra ~20 modelli `:free` ed è quindi immune al 429/403 di un singolo provider. Entrambi parlano il formato OpenAI (`chat/completions`), quindi la chiamata è la stessa cambia solo `baseURL`, `model` e `Authorization`.

**Regole della cascata** (`src/adapters/ai-providers.ts` + `src/adapters/gemini.ts`):

- **Priorità a Gemini**: il primario insiste su **3 tentativi**, i fallback su 2. Finché Gemini risponde con un array non vuoto, nessun altro provider viene toccato.
- Si passa al provider successivo quando: la chiamata fallisce (404/401/429/503, timeout di 5 minuti), la risposta non è JSON interpretabile, oppure l'array restituito è **vuoto**.
- **Retry mirato, dentro al provider**: ogni tentativo ripete la chiamata con attesa esponenziale (2s, 4s). Si ripete su 503 e su 429 *con* `retry-after` (limite transitorio) **e** su una risposta ricevuta ma vuota o troncata, che è non deterministica. Un 429 *senza* `retry-after` è un limite giornaliero: riprovare sprecherebbe quota senza alcuna possibilità di successo, quindi si passa subito al provider successivo. Un 404/401 non viene ritentato affatto. Il retry vive nel provider (dove status HTTP e `retry-after` sono disponibili) e non nell'orchestrator della cascata, che altrimenti deciderebbe facendo regex sul testo dell'errore e moltiplicherebbe le attese.
- Un array vuoto valido su **tutti** i provider restituisce `[]` senza errore (il run finisce in `zeroed`, che la monitoraggia già); se **nessun** provider risponde, la fonte fallisce con tutti gli errori in un unico messaggio.
- Solo i provider con chiave impostata entrano in cascata: la fonte continua a funzionare anche con la sola `GEMINI_API_KEY`.
- Su OpenRouter un modello che non è `openrouter/free` né suffisso `:free` viene **rifiutato** con un log, così una sovrascrittura per sbaglio non può mai invocare un modello a pagamento.

**Limite noto:** la quota free di OpenRouter è 50 richieste al giorno (le richieste fallite contano lo stesso) e viene esaurita facilmente; è l'ultimo anello della catena, quindi il problema si manifesta solo se Gemini e Groq sono entrambi fuori. Con 10$ una tantum la quota sale a 1000/giorno.
