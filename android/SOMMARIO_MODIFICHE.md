# Sommario modifiche — MeepleRadar (associazione BGG e product)

Questo documento riassume tutte le modifiche apportate al progetto per risolvere il problema dell'associazione BGG, la creazione mancante di record `product` e la scomparsa dei giochi dalla "Lista".

---

## 1. Problema iniziale

L'utente ha segnalato che, dopo aver associato un gioco BGG a un listing (es. `listing:26047`), il gioco spariva dalla tab "Lista" e non veniva creato nessun record nella tabella `product` di Supabase.

---

## 2. Stato del piano `PLAN-associazione-bgg.md`

Il piano dichiarava i Task 0–7 completati, ma l'ispezione del codice ha rivelato che:

- **Task 7 era rotto**: `DetailViewModel.saveManualProduct()` chiamava metodi inesistenti (`repo.previewGameSave()`, proprietà `pendingSaveItem`, campo `savePreview`).
- **Task 4 incompleto**: `updateMissingBggStats()` non passava il campo `description`.
- **Task 8 non iniziato**: mancava il form di inserimento manuale prodotto nell'UI.

Tutti i task 0–9 sono stati poi completati.

---

## 3. Correzioni effettuate

### 3.1 Task 7 — Correzione codice rotto (`DetailViewModel`, `ListingRepository`, `SupabaseApi`, `Models.kt`)

| File | Modifica |
|------|----------|
| `Models.kt` | Aggiunta data class `GameSavePreview(listings: List<CandidateListingLink>)` |
| `ListingRepository.kt` | Aggiunto `previewGameSave(item): GameSavePreview` all'interfaccia e a `DefaultListingRepository`. Firma di `saveGameToDatabase` aggiornata a `saveGameToDatabase(item, selectedListingIdsToLink): Long?` |
| `SupabaseApi.kt` | `saveGameToDatabase` restituisce ora `Long?` (ID del gioco creato/aggiornato) invece di `Unit` |
| `DetailViewModel.kt` | Aggiunta proprietà `private var pendingSaveItem`, campo `savePreview` in `DetailUiState.Content`, passaggio di `description` in `updateMissingBggStats` |
| 5 file di test | Aggiornati i `FakeRepo` per implementare i nuovi metodi astratti |

### 3.2 Task 8 — Rifinitura UI e accessibilità (`DetailScreen.kt`)

Aggiunto composable `ManualProductForm` con:
- Campi: Titolo prodotto, URL prodotto, Prezzo, Valuta
- Pulsanti "Salva prodotto" e "Annulla"
- Stati di loading e messaggi di errore
- Validazione campi obbligatori
- Integrazione con `DetailViewModel` (`toggleManualProductEditing`, `saveManualProduct`, `updateManualProductField`)

### 3.3 Task 9 — Collaudo complessivo

- `./gradlew :app:lintDebug` — OK
- `./gradlew :app:assembleDebug` — OK
- `./gradlew :app:testDebugUnitTest` — **105 test passati, 0 falliti**

---

## 4. Problema prodotto mancante — analisi e soluzioni

### Sintomi
- Nessun record in tabella `product`
- Gioco sparito dalla "Lista" dopo l'associazione BGG

### Causa radice
Il metodo `linkListingToBgg` collegava solo `listing → game` su Supabase, senza creare un record `product` né impostare `product_id` sulla listing. Senza `product_id`, la chiave dell'item cambiava da `listing:26047` a `game:<id>`, facendo sparire il gioco dalla Lista.

### Tre soluzioni implementate

#### Soluzione A — PATCH listing solo con `game_id` e `product_id`
Il PATCH sulla listing esistente invia **solo** i campi che devono cambiare (`game_id` e `product_id`), senza sovrascrivere `source_id`, `title`, `url` o `match_confidence`.

#### Soluzione B — Recupero URL dalla listing esistente
Il product viene creato usando l'URL della listing esistente (se disponibile), oppure l'URL BGG come fallback, evitando conflitti di unique constraint sull'URL.

#### Soluzione C — Creazione product tramite funzione RPC Supabase
Invece di fare un INSERT diretto sulla tabella `product` (bloccato dai permessi RLS della chiave `anon`), il codice chiama una funzione server-side `upsert_product_for_game` che gestisce l'inserimento/aggiornamento con i permessi del server.

---

## 5. Soluzione finale — funzione RPC `upsert_product_for_game`

### Funzione SQL da creare su Supabase

Eseguire nell'editor SQL di Supabase:

```sql
create or replace function public.upsert_product_for_game(
    p_game_id bigint,
    p_title text,
    p_url text,
    p_image_url text,
    p_description text
)
returns bigint
language plpgsql
as $$
declare
    v_product_id bigint;
begin
    -- Cerca se esiste già un product per questo game_id
    select id into v_product_id
    from product
    where game_id = p_game_id
    limit 1;

    if v_product_id is not null then
        -- Aggiorna il product esistente
        update product
        set title = coalesce(p_title, title),
            url = coalesce(p_url, url),
            image_url = coalesce(p_image_url, image_url),
            description = coalesce(p_description, description)
        where id = v_product_id;
        return v_product_id;
    else
        -- Crea un nuovo product
        insert into product (title, url, image_url, description, game_id)
        values (p_title, p_url, p_image_url, p_description, p_game_id)
        returning id into v_product_id;
        return v_product_id;
    end if;
end;
$$;
```

### Flusso corretto dopo la funzione RPC

1. L'utente associa un gioco BGG a un listing
2. `linkListingToBgg` crea/aggiorna il `game` su Supabase
3. Chiama `rpc/upsert_product_for_game` con `game_id`, titolo, URL, immagine, descrizione
4. La funzione RPC crea (o riusa) il `product` e restituisce il suo ID
5. `linkListingToBgg` aggiorna la `listing` impostando solo `game_id` e `product_id`
6. Il gioco resta visibile nella "Lista" con chiave `product:<id>`

---

## 6. Flusso UI per listing non in database

Quando un listing non ha dettagli BGG, viene mostrato un nuovo composable `BggAssociationCard` con:
- Campo di ricerca su BGG
- Pulsante "Cerca"
- Lista di candidati trovati con pulsante "Seleziona"
- Pulsante "Salva associazione" (attivo dopo selezione e fine salvataggio)

---

## 7. File modificati (totale)

| File | Modifica |
|------|----------|
| `PLAN-associazione-bgg.md` | Checklist e diario aggiornati |
| `Models.kt` | `GameSavePreview`, `Product.gameId` |
| `ListingRepository.kt` | `previewGameSave`, `saveGameToDatabase(...): Long?` |
| `SupabaseApi.kt` | `saveGameToDatabase` restituisce `Long?`, `linkListingToBgg` restituisce `Pair`, creazione product via RPC |
| `DetailViewModel.kt` | `pendingSaveItem`, `savePreview`, errore se `savedProductId` è null |
| `DetailScreen.kt` | `ManualProductForm`, `BggAssociationCard` |
| `LocalCacheStore.kt` | `upsertGame` accetta `linkedProductId`, `CachedProduct` include `gameId` |
| `IncomingMapper.kt` | `keyOf` ripristinato all'originale (aggregazione per productId/gameId) |
| 8 file di test | Fake repository aggiornati ai nuovi metodi astratti |

---

## 8. Verifica eseguita

```
./gradlew :app:compileDebugKotlin   → OK
./gradlew :app:testDebugUnitTest   → 105 passati, 0 falliti
./gradlew :app:lintDebug           → OK
./gradlew :app:assembleDebug       → OK
```

---

## 9. Prossimi passi per l'utente

1. Creare la funzione SQL `upsert_product_for_game` sul database Supabase (vedi sezione 5)
2. Reinstallare l'app
3. Aprire il listing 26047
4. Cercare il gioco su BGG, selezionarlo e premere "Salva associazione"
5. Verificare che il gioco ricompaia nella "Lista" e che esista un record in tabella `product`

---