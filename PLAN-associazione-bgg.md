# Piano incrementale — revisione associazioni BGG e prodotti Android

> **Scopo:** guidare un assistente AI nell'analisi e modifica dell'app Android esistente, un task per volta, mantenendo una traccia verificabile e consentendo di interrompere e riprendere il lavoro.
>
> **Istruzione per l'AI:** esegui esclusivamente il task richiesto dall'utente o, se non ne indica uno, il primo task non completato. Non procedere automaticamente al task successivo. Alla fine aggiorna la sezione **Avanzamento** e fermati, riepilogando modifiche e verifiche. Non dichiarare eseguiti test che non hai realmente lanciato.

## Contesto verificato nel repository

L'app esiste già in `android/` ed è sviluppata in Kotlin con Jetpack Compose, MVVM e repository. Aprire in Android Studio la cartella `android/`, non la root del repository. I riferimenti principali attualmente individuati sono:

- `android/app/src/main/java/com/meepleradar/data/model/Models.kt` — modelli `Listing`, `Product`, `Game`, `IncomingItem`.
- `android/app/src/main/java/com/meepleradar/data/repo/IncomingMapper.kt` — aggregazione listing/prodotto/game.
- `android/app/src/main/java/com/meepleradar/data/repo/ListingRepository.kt` — repository e cache locale.
- `android/app/src/main/java/com/meepleradar/data/remote/SupabaseApi.kt` — API PostgREST e operazioni di salvataggio/associazione.
- `android/app/src/main/java/com/meepleradar/data/remote/BoardGameImageLookup.kt` — ricerca e dettagli BGG.
- `android/app/src/main/java/com/meepleradar/ui/detail/DetailViewModel.kt` e `DetailScreen.kt` — dettagli, selezione BGG e azioni di aggiornamento/salvataggio.
- `android/app/src/main/java/com/meepleradar/ui/mycollection/BggCollectionViewModel.kt` e `MyCollectionScreen.kt` — schermata “Miei” e pillole delle espansioni.
- `android/app/src/main/java/com/meepleradar/ui/nav/MeepleRadarNav.kt` — navigazione.
- `android/README.md` — setup e comandi di build/test.

Questi percorsi sono punti di partenza, non un vincolo: prima di ogni modifica l'AI deve riaprire i file interessati nel branch corrente e verificare che nomi, codice e test siano ancora aggiornati.

### Rilievi iniziali da tenere in considerazione

Dall'ispezione statica del codice (non da una prova runtime) risultava che:

1. Esiste già il dettaglio con ricerca BGG automatica/manuale e matita per correggere un'associazione.
2. L'azione esistente di aggiornamento dei dati BGG tratta voto e rank, ma non completa ancora tutti i casi richiesti per la descrizione in `game`.
3. L'associazione può riusare o modificare record `game`: va verificato con test che un BGG ID già presente venga riutilizzato correttamente e che non si generino duplicati o sovrascritture indesiderate.
4. In “Miei” le pillole di possesso aprono/chiudono liste di espansioni; il flusso di navigazione verso dettaglio locale o associazione richiesto va verificato e completato.
5. Il salvataggio attuale non equivale ancora necessariamente alla creazione manuale del prodotto richiesto: verificare schema e relazioni reali prima di implementarlo.
6. L'app usa una chiave Supabase anon. Prima di aggiungere scritture, verificare RLS, policy e architettura autorizzativa. Non inserire mai `service_role` nell'app e non rendere pubbliche scritture anonime indiscriminate.

## Regole di esecuzione per l'AI

1. **Un task alla volta.** Modifica solo quanto necessario a completare il task selezionato. Non fare refactor estranei.
2. **Prima capire, poi cambiare.** Leggi implementazione, test e schema interessati; descrivi brevemente il comportamento attuale e quello atteso.
3. **Test prima o insieme al codice.** Aggiungi test unitari per la logica modificata. Per API PostgREST usa fake transport; per ViewModel usa fake repository/lookup.
4. **Preservare i dati.** Non sostituire valori non vuoti già presenti se la richiesta riguarda solo i dati mancanti, salvo decisione esplicita e testata.
5. **Associazione idempotente.** Ripetere l'operazione non deve creare record duplicati. Un BGG ID già presente in `game` va riutilizzato e le relazioni devono essere coerenti.
6. **Gestire stati ed errori.** Caricamento, nessun risultato, errore, successo e retry devono essere distinguibili; non mostrare successo prima che il server abbia confermato il salvataggio.
7. **Sicurezza Supabase.** Verificare permessi e RLS prima delle scritture. Se il modello anon non è sufficientemente sicuro, fermarsi e documentare la necessità di un endpoint/RPC autorizzato anziché introdurre policy permissive.
8. **Checkpoint.** Al termine del task aggiornare questa checklist e il diario. Se il lavoro viene interrotto, lasciare lo stato reale, includendo test falliti e file ancora da completare.
9. **Niente supposizioni sul runtime.** Se non è disponibile Android Studio/emulatore/Supabase di test, indicare il limite e lasciare una prova manuale riproducibile.
10. **Nessuna credenziale nel repository.** Non leggere, riportare o committare chiavi/token presenti in file locali; non modificare `local.properties` per inserirvi segreti.

## Definizioni funzionali da implementare

Le tabelle primarie sono `listing`, `product` e `game` (nomi SQL minuscoli). Ai fini di questo piano, un dato richiesto è mancante se è `null`, vuoto o composto solo da spazi. I campi BGG da controllare in `game` sono `bgg_rank`, `bgg_rating` (voto) e `description`.

- **Caso A — associato e completo:** mantenere la visualizzazione dettagli attuale e la matita per modificare l'associazione BGG.
- **Caso B — associato ma incompleto:** mantenere dettaglio e matita; aggiungere in fondo un'azione per recuperare e salvare i campi BGG mancanti in `game`.
- **Caso C — listing e product presenti, associazione BGG/game assente:** tentare il match automatico; lasciare sempre disponibile ricerca manuale e scelta utente. Dopo la selezione, mostrare un'azione esplicita per associare. Se il BGG ID esiste già in `game`, riusarlo e completarne i campi mancanti; altrimenti creare `game` con i dati disponibili e collegare le righe necessarie.
- **Caso D — solo “Miei”:** al tap su una pillola associata mostrare il dettaglio locale normale. Se i dati locali richiesti non esistono, aprire un flusso di associazione: match automatico e ricerca manuale BGG; dopo la selezione permettere l'inserimento manuale dei dati prodotto e creare/collegare `product`, `listing` e `game` senza duplicare un `game` con lo stesso BGG ID. Al tap successivo aprire il dettaglio locale.

> “Informazioni complete” deve essere interpretato secondo la regola di prodotto condivisa: per il Caso B il set minimo di completezza è esplicitamente rank + voto + descrizione. Non trattare altri campi BGG come obbligatori senza accordo o requisito aggiuntivo.

## Piano dei task

### Task 0 — Baseline e mappa dei flussi

- [ ] Aprire `android/` in Android Studio e attendere Gradle Sync.
- [ ] Verificare JDK/SDK richiesti leggendo `android/README.md` e configurazione Gradle attuale.
- [ ] Eseguire i test unitari e la build debug; annotare l'esito senza correggere problemi non correlati.
- [ ] Tracciare il percorso UI delle pillole fino al dettaglio e le operazioni API coinvolte.
- [ ] Verificare schema SQL corrente per chiavi, colonne, foreign key, unique constraint e policy RLS di `listing`, `product`, `game`.
- [ ] Riportare nel diario l'ambiente e qualsiasi dipendenza esterna non disponibile.

**Accettazione:** stato iniziale riproducibile, file e flussi coinvolti identificati, nessuna credenziale copiata nei log.

### Task 1 — Specifica dei casi e test di classificazione

- [ ] Tradurre i casi A–D in una funzione/modello di stato testabile senza UI.
- [ ] Testare item associato completo, associato incompleto, non associato e item “Miei” senza corrispondenza locale.
- [ ] Includere `game_id`/`product_id` nulli, più listing per stesso prodotto/gioco e campi null/vuoti.
- [ ] Mantenere separati i concetti di “dettaglio BGG trovato” e “associazione persistita nel database”.

**File probabili:** `IncomingMapper.kt`, modelli e test di `data/repo`. Modificare solo se necessario.

**Accettazione:** test della classificazione verdi e comportamento documentato nel codice.

### Task 2 — Contratto di persistenza e sicurezza

- [ ] Verificare le migrazioni e lo schema realmente applicato, non dedurre le colonne dal solo modello Android.
- [ ] Stabilire i campi necessari per creare un prodotto manuale e come si crea il relativo listing.
- [ ] Verificare policy/RLS per lettura e scrittura delle tre tabelle e le relazioni tra loro.
- [ ] Definire operazione idempotente: trovare il `game` per BGG ID, riusarlo o crearlo e aggiornare collegamenti in modo coerente.
- [ ] Se più scritture devono essere atomiche, valutare RPC/endpoint transazionale sicuro; non simulare atomicità con richieste indipendenti.
- [ ] Aggiungere/aggiornare migrazioni solo se necessarie e coerenti con lo schema del progetto; non applicarle a un database remoto senza autorizzazione esplicita.

**Accettazione:** contratto dati e modello autorizzativo chiariti prima delle modifiche che scrivono su Supabase.

### Task 3 — API/repository: lettura e upsert idempotente di `game`

- [ ] Aggiungere un'operazione che recuperi un `game` per `bgg_id` in modo esplicito, oppure implementare il lookup all'interno di un upsert sicuro.
- [ ] Se il record esiste, collegare al suo ID e valorizzare solo i campi mancanti concordati.
- [ ] Se non esiste, creare il record con nome, tipo, BGG ID, voto, rank, immagine e descrizione disponibili.
- [ ] Gestire conflitti di concorrenza/unique constraint sul BGG ID e rileggere il record vincente.
- [ ] Aggiornare cache/repository dopo una risposta valida; non aggiornare cache se il server fallisce.
- [ ] Testare richieste/URL/headers/body e tutti i rami con fake transport/API.

**File probabili:** `SupabaseApi.kt`, `ListingRepository.kt`, test `data/remote` e `data/repo`.

**Accettazione:** test verdi per create, riuso, integrazione dei soli dati mancanti, errore permessi e retry senza duplicati.

### Task 4 — Completamento rank, voto e descrizione mancanti

- [ ] Calcolare i campi mancanti di `game` secondo la regola null/vuoto.
- [ ] Recuperare i dettagli dal BGG ID già associato, senza affidarsi a una ricerca per titolo se l'ID è valido.
- [ ] Aggiornare in database esclusivamente i valori mancanti, preservando quelli già compilati.
- [ ] Aggiornare l'oggetto mostrato e la cache dopo il successo.
- [ ] Esporre errore e retry; impedire richieste duplicate mentre il caricamento è in corso.
- [ ] Aggiornare test per tutte le combinazioni dei tre campi e per risposta BGG parziale/vuota.

**File probabili:** `DetailViewModel.kt`, `DetailScreen.kt`, API/repository e test esistenti.

**Accettazione:** il pulsante compare se manca almeno uno dei tre campi; non appare quando tutti sono completi; dopo un aggiornamento riuscito i campi riflettono il database.

### Task 5 — Associazione BGG per elementi di listing/prodotto

- [ ] Conservare scheda dettagli e matita per i giochi già associati.
- [ ] Per item non associati eseguire il match automatico già disponibile e mostrare candidati con scelta esplicita.
- [ ] Mantenere ricerca manuale in caso di match assente o errato.
- [ ] Non persistere un'associazione solo perché il lookup ha trovato un candidato: richiedere selezione/azione esplicita coerente con la UI.
- [ ] Al salvataggio collegare il listing e, se presente, il prodotto al `game` corretto secondo lo schema reale.
- [ ] Se il `game` BGG esiste, riusarlo e popolare solo i dati mancanti; altrimenti crearlo.
- [ ] Dopo il salvataggio ricaricare o aggiornare lo stato così la schermata diventa quella normale.

**Accettazione:** copertura test per match automatico, scelta manuale, cambio associazione, ID BGG già presente e listing senza prodotto.

### Task 6 — Navigazione delle pillole “Miei” e dettaglio locale

- [ ] Mappare ogni pillola BGG a un eventuale item locale utilizzando l'ID BGG e i dati di listing/prodotto disponibili.
- [ ] Se i record locali sono presenti, aprire il dettaglio locale con una chiave stabile e dati aggiornati.
- [ ] Se i record non sono presenti o l'associazione manca, aprire il flusso di associazione specifico “Miei”, non un dettaglio con dati fittizi presentato come locale.
- [ ] Verificare comportamento per giochi base, espansioni, listing assenti e più listing per BGG ID.
- [ ] Non cambiare il comportamento dei controlli che aprono/chiudono le sezioni “Possedute/Non possedute”. Se occorre cambiare il gesto di una pillola, specificarlo e testarlo senza rendere inaccessibili le liste.

**File probabili:** `BggCollectionViewModel.kt`, `MyCollectionScreen.kt`, `MeepleRadarNav.kt`, repository.

**Accettazione:** test della risoluzione BGG→item locale e prova di navigazione con item presente/assente.

### Task 7 — Flusso di inserimento manuale prodotto da “Miei”

- [ ] Definire un form con i campi obbligatori realmente richiesti dallo schema (almeno i campi confermati dal Task 2); validare URL e titolo.
- [ ] Dopo la scelta BGG rendere disponibile l'inserimento prodotto manuale come richiesto.
- [ ] Salvare prodotto, listing e associazione game rispettando vincoli, foreign key e permessi.
- [ ] Rendere l'operazione ripetibile senza duplicare prodotto/listing/game; usare una chiave naturale/constraint prevista dallo schema, non un URL BGG inventato per un prodotto negozio.
- [ ] Gestire esito parziale/errore, conferma e aggiornamento della collezione locale.
- [ ] Aggiungere test di validazione, payload, esito positivo, retry e conflitti.

**Accettazione:** nuovo prodotto visibile come dettaglio locale al successivo tap; record e relazioni verificabili nell'ambiente di test.

### Task 8 — Rifinitura UI e accessibilità degli stati

- [ ] Uniformare testi e pulsanti dei casi A–D; distinguere “Cerca”, “Seleziona” e “Salva associazione”.
- [ ] Mostrare loading, nessun risultato, errore, conferma e azione retry.
- [ ] Impedire doppio tap sui salvataggi e mantenere il pulsante accessibile con tastiera/screen reader.
- [ ] Verificare layout su schermi piccoli e contenuti lunghi.

**Accettazione:** prova manuale di tutti gli stati UI senza navigazione bloccata o falso successo.

### Task 9 — Collaudo complessivo e documentazione

- [ ] Eseguire `:app:testDebugUnitTest`, `:app:lintDebug` e `:app:assembleDebug` dalla cartella `android/`.
- [ ] Provare con dati di test i casi A, B, C e D, compresi BGG senza risultati, rete non disponibile e permessi insufficienti.
- [ ] Verificare su Supabase di test le righe e le foreign key create/modificate.
- [ ] Aggiornare `android/README.md` solo se setup, permessi o flusso utente sono cambiati.
- [ ] Registrare eventuali controlli che richiedono credenziali/ambiente e non sono stati eseguiti.

**Accettazione:** test e build con esito riportato, nessun segreto incluso, checklist e diario aggiornati.

## Comandi di verifica

Eseguirli dalla directory `android/` usando il wrapper e l'OS appropriato, come descritto in `android/README.md`:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

Su Windows usare `gradlew.bat` al posto di `./gradlew`. Se Gradle Sync/build fallisce per una configurazione locale, registrare il messaggio essenziale (senza segreti), ambiente e passaggi già tentati; non modificare toolchain o dipendenze in modo non correlato.

## Avanzamento e diario di lavoro

L'AI deve mantenere qui le caselle dei task e aggiungere una voce al diario alla fine di ogni sessione. Non cancellare note precedenti: se una conclusione cambia, aggiungere una rettifica datata.

### Stato corrente

- **Task corrente:** Task 0 — baseline e mappa dei flussi.
- **Ultimo aggiornamento:** piano creato; nessuna modifica al codice applicativo eseguita da questo piano.
- **Test/build eseguiti durante l'esecuzione di questo piano:** nessuno.
- **Prossima azione:** aprire `android/` in Android Studio e completare Task 0.

### Checklist

- [X] Task 0 — Baseline e mappa dei flussi
- [X] Task 1 — Specifica dei casi e test di classificazione
- [X] Task 2 — Contratto di persistenza e sicurezza
- [X] Task 3 — API/repository: lettura e upsert idempotente di `game`
- [X] Task 4 — Completamento rank, voto e descrizione mancanti
- [X] Task 5 — Associazione BGG per elementi di listing/prodotto
- [X] Task 6 — Navigazione delle pillole “Miei” e dettaglio locale
- [X] Task 7 — Flusso di inserimento manuale prodotto da “Miei”
- [ ] Task 8 — Rifinitura UI e accessibilità degli stati
- [ ] Task 9 — Collaudo complessivo e documentazione

### Diario

| Data | Task | Modifiche/file | Test/build realmente eseguiti | Esito e note | Prossimo passo |
|---|---|---|---|---|---|
| 2026-11-10 | Creazione piano | `android/PLAN-associazione-bgg.md` | Nessuno | Piano predisposto; app esistente verificata staticamente, non eseguita in Android Studio | Task 0 |

### Modello da copiare per ogni sessione

```text
#### AAAA-MM-GG — Task N
- Obiettivo:
- Modifiche effettuate (file):
- Scelte/assunzioni confermate:
- Test/build lanciati e risultato:
- Verifica manuale/ambiente richiesto:
- Problemi aperti:
- Stato task: non iniziato / in corso / completato / bloccato
- Prossimo passo preciso:
```

## Fine di ogni sessione AI

Prima di fermarti:

1. aggiorna checklist, **Stato corrente** e Diario;
2. elenca i file modificati e riassumi il comportamento cambiato;
3. riporta i comandi realmente eseguiti e il risultato, oppure spiega perché non erano disponibili;
4. segnala rischi o decisioni ancora da confermare;
5. indica un solo prossimo task e fermati senza iniziarlo.
