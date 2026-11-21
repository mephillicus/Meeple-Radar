# Piano di Miglioramento — Meeple Radar (App Android)

> **Ambito:** modulo Android (`android/`, package `com.meepleradar`).
> **Documento gemello (backend/collector/CI):** `../PIANO_MIGLIORAMENTO_BACKEND.md`.
> **Ultimo aggiornamento:** 2026-11-21 — **Sprint 1 completato** (`64cd190`), **Sprint 2 in proposta**.
> **Stato baseline verificato:** AGP `9.4.1`, Kotlin `2.4.20`, Compose BOM `2026.09.00`, `compileSdk = 37`, `targetSdk = 36`, `minSdk = 26`, 127 unit test verdi, lint e release R8 verdi.

---

## Sintesi diagnostica

L'app ha una buona copertura di unit test sui flussi core e una UI Material 3 coerente, ma presenta 5 aree di debito tecnico e opportunità ad alto impatto:

1. **Storage locale su `SharedPreferences` JSON:** `SharedPreferencesLocalCacheStore` serializza l'intero catalogo (`listings`, `games`, `sources`, `products`) in 4 grandi stringhe JSON e ogni refresh riscarica tutte e 5 le tabelle (`select=*`) da Supabase.
2. **Duplicazione significativa in UI/ViewModel e file monolitici:** `NewsScreen`/`PreordersScreen`/`IncomingScreen` (+ rispettivi ViewModel) replicano filtri, drawer e ricerca; `DetailScreen.kt` (1397 righe) e `DetailViewModel.kt` (600 righe) concentrano troppe responsabilità.
3. **Network & Startup:** `AppContainer.kt` usa `OkHttpClient()` senza timeout né HTTP cache; `MainActivity.kt` blocca l'avvio per `800ms` con uno splash Compose su sfondo bianco fisso anche in dark mode.
4. **Release hardening & sicurezza locale:** `isMinifyEnabled = false` in `release`, `allowBackup="true"` senza regole di esclusione per lo store cifrato con Android Keystore, nessuna CI GitHub Actions per build/test Android.
5. **UX e funzionalità utente:** mancano pull-to-refresh nativo, ordinamento configurabile, ricerca/filtri nei Preferiti, gestione degli Ignorati, aggiornamento automatico dei pallini su "Miei" dopo un sync in altre tab, e notifiche background con `WorkManager`.

---

## Priorità P0 — Affidabilità, Sicurezza e Qualità Release

### [x] P0.1 — Hardening backup & Keystore (`AndroidManifest.xml`) — *Completato (`64cd190`)*
- **Problema:** `android:allowBackup="true"` in `app/src/main/AndroidManifest.xml` può includere nel backup cloud `meeple_radar_bgg_collection` (cifrato con chiave in `AndroidKeyStore`, non trasferibile tra dispositivi), causando errori di decifratura dopo un ripristino.
- **Intervento applicato:**
  - Creati `res/xml/backup_rules.xml` (`android:fullBackupContent`) e `res/xml/data_extraction_rules.xml` (`android:dataExtractionRules`) escludendo `meeple_radar_bgg_collection.xml` e `meeple_radar_db_cache.xml`.
  - Aggiunto `android:windowSoftInputMode="adjustResize"` su `MainActivity`.

### [x] P0.2 — Configurazione `OkHttpClient` con timeout e cache HTTP (`AppContainer.kt`) — *Completato (`64cd190`)*
- **Problema:** `AppContainer.kt` istanzia `OkHttpClient()` con i default (10s senza cache disco condivisa e senza retry controllato).
- **Intervento applicato:**
  - Configurati `connectTimeout(15s)`, `readTimeout(30s)`, `writeTimeout(30s)`, `callTimeout(45s)` e `Cache(File(appContext.cacheDir, "http_cache"), 20 MB)`.

### [x] P0.3 — Abilitare R8 / ProGuard in `release` (`app/build.gradle.kts`, `proguard-rules.pro`) — *Completato (`64cd190`)*
- **Problema:** `isMinifyEnabled = false` e `proguard-rules.pro` vuoto producono APK release più grandi e non offuscati.
- **Intervento applicato:**
  - Attivati `isMinifyEnabled = true` e `isShrinkResources = true` in `buildTypes.release`, aggiornato `proguard-rules.pro` e verificato con `:app:assembleRelease`.

### [x] P0.4 — CI GitHub Actions per Android (`.github/workflows/android-ci.yml`) — *Completato (`64cd190`)*
- **Problema:** Nessuna verifica automatica di build e unit test Android su push/PR.
- **Intervento applicato:**
  - Creato `.github/workflows/android-ci.yml` su `push`/`pull_request` (path `android/**`) con JDK 17 + 25, cache Gradle e `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.

---

## Priorità P1 — Performance, Storage Locale e Architettura

### P1.1 — Migrazione della cache locale da `SharedPreferences` a Room (SQLite)
- **File coinvolti:** `LocalCacheStore.kt`, `SeenStore.kt`, `FavoriteStore.kt`, `ListingRepository.kt`, `gradle/libs.versions.toml`, `app/build.gradle.kts`.
- **Problema:** Deserializzare interi array JSON da `SharedPreferences` scala male al crescere di `listings` e `products`, occupa molta memoria e costringe a ricaricare tutto in blocco.
- **Intervento:**
  - Introdurre Room con entità `ListingEntity`, `GameEntity`, `ProductEntity`, `SourceEntity`, `UserActionLocalEntity` e indici su `releaseDate`, `sourceId`, `gameId`, `bggId`.
  - Esporre query reattive (`Flow`) per Novità, Preordini, Lista, Preferiti e contatori, eliminando scansioni complete in memoria.
  - Migrazione trasparente al primo avvio (importazione una tantum da `SharedPreferences` esistenti per `SeenStore` e `FavoriteStore`).

### P1.2 — Sync incrementale con Supabase (`SupabaseApi.kt`, `ListingRepository.kt`)
- **Problema:** Ogni `forceRefresh` scarica l'intera tabella `listing`, `game`, `product`, `source` e tutta la storia `user_action`.
- **Intervento:**
  - Scaricare solo le righe modificate dopo `lastSyncAt` (`updated_at=gt.<iso>` / `last_seen_at=gt.<iso>` / `created_at=gt.<iso>`), mantenendo un full sync periodico (es. 1 volta a settimana o su richiesta) per riallineare eventuali cancellazioni da `purgeStale`.
  - Dipendenza backend: vedere `P1.2` / `P2.1` in `../PIANO_MIGLIORAMENTO_BACKEND.md`.

### P1.3 — Eliminazione duplicazioni tra Lista, Novità e Preordini
- **File coinvolti:** `NewsScreen.kt`, `PreordersScreen.kt`, `IncomingScreen.kt`, `NewsViewModel.kt`, `PreordersViewModel.kt`, `IncomingViewModel.kt`, `StateViews.kt`.
- **Intervento:**
  - Estrarre una classe immutabile `ListingFilterState` e un motore puro `ListingFilterEngine` (con unit test dedicati) condiviso dai 3 ViewModel.
  - Estrarre un composable riutilizzabile `ListingFilterDrawer` e una `CompactSearchBar` condivisa in `ui/components/`.
  - Riduzione stimata: ~900–1100 righe duplicate eliminate e coerenza garantita tra tutte le tab.

### P1.4 — Modularizzazione di `DetailScreen.kt` e `DetailViewModel.kt`
- **File coinvolti:** `ui/detail/DetailScreen.kt` (1397 righe), `ui/detail/DetailViewModel.kt` (600 righe).
- **Intervento:**
  - Spezzare `DetailScreen.kt` in file dedicati per sezione: `DetailHeaderSection.kt`, `DetailOffersSection.kt`, `DetailBggMetadataSection.kt`, `DetailBggDialogs.kt`.
  - Estrarre le operazioni di ricerca/associazione/salvataggio BGG in un `BggLinkingUseCase` separato e testabile.

### P1.5 — Riallineamento reattivo della schermata "Miei" (`MyCollectionScreen.kt`, `BggCollectionViewModel.kt`)
- **Problema:** `BggCollectionViewModel.loadListingData()` gira solo in `init` e dopo `fetch()` da BGG; se l'utente aggiorna Novità/Preordini/Lista durante la stessa sessione, i pallini verdi/blu su "Miei" non si aggiornano finché non riavvia l'app o ricarica da BGG. Inoltre `searchQuery`, `showBaseGames` e `showExpansionPills` vivono in `remember` invece che nel ViewModel.
- **Intervento:**
  - Osservare `repository.lastDbModifiedTimestampFlow()` in `BggCollectionViewModel` per ricalcolare automaticamente `listingsByBggId` e `expansionAvailabilityById` ogni volta che la cache Supabase cambia.
  - Spostare `searchQuery`, `showBaseGames` e `showExpansionPills` nel ViewModel così non si perdono aprendo il dettaglio di un gioco.

---

## Priorità P2 — UX, Material 3 e Funzionalità Utente

### [x] P2.1 — SplashScreen API nativa & Edge-to-Edge coerente (`MainActivity.kt`, `themes.xml`, `MeepleRadarNav.kt`, `DetailScreen.kt`) — *Completato (`64cd190`)*
- **Problema:** `MainActivity.kt` usa `delay(800L)` su `Color.White` fisso (abbagliante in dark mode e rallenta artificialmente lo startup).
- **Intervento applicato:**
  - Adottata `androidx.core:core-splashscreen:1.2.0` con `Theme.MeepleRadar.Starting` su `#0F172A`, `enableEdgeToEdge(...)`, rimozione `delay(800L)`, sistemati inset bottom bar (`MeepleRadarNav`), `DetailScreen` (`WindowInsets.safeDrawing`) e `SetupContent` (`imePadding` + scroll).

### P2.2 — Pull-to-refresh nativo e Ordinamento configurabile
- **Intervento:**
  - Avvolgere le liste (`News`, `Preorders`, `Incoming`, `Favorites`, `Miei`) in `PullToRefreshBox` di Material 3.
  - Aggiungere un selettore di ordinamento nel drawer o sotto la barra di ricerca:
    - Data di uscita (più recente / più vicina)
    - Prezzo più basso
    - Rating BGG decrescente
    - Alfabetico (A–Z)

### P2.3 — Potenziamento schermata Preferiti e gestione "Ignorati"
- **File coinvolti:** `FavoritesScreen.kt`, `FavoritesViewModel.kt`, nuova sezione/dialog `IgnoredGamesSheet`.
- **Intervento:**
  - Aggiungere ricerca, filtro disponibilità (Disponibile ora / Preordine / Esaurito) e badge "Nuovo in stock" / "Calo prezzo" nei Preferiti.
  - Aggiungere una vista per consultare i giochi segnati come **Ignorati** e ripristinarli con un tap (oggi un gioco ignorato per errore non è più recuperabile dall'app).

### P2.4 — Confronto prezzi e badge "Miglior prezzo"
- **Intervento:**
  - Evidenziare sulle `ListingCard` e in `DetailScreen` il negozio con il prezzo minimo (e, quando il backend abiliterà `price_history`, mostrare la variazione di prezzo rispetto allo storico).

---

## Priorità P3 — Notifiche, Background Sync e Testing Avanzato

### P3.1 — Background sync e notifiche locali con `WorkManager`
- **Intervento:**
  - Aggiungere `androidx.work:work-runtime-ktx` con un `PeriodicWorkRequest` (es. ogni 12 ore, solo su rete disponibile e batteria non scarica).
  - Notifiche locali opt-in (con canale dedicato e schermata Impostazioni + permesso runtime `POST_NOTIFICATIONS` su Android 13+):
    - Preferito passato in stato disponibile o con calo di prezzo.
    - Nuova espansione rilevata in Novità/Preordini per un gioco posseduto nella collezione BGG.
    - Riepilogo giornaliero delle novità aggiunte.

### P3.2 — Test UI Compose e Screenshot / Preview Coverage
- **Intervento:**
  - Aggiungere `@Preview` con Light/Dark mode sui componenti chiave (`ListingCard`, `UnseenFilterPill`, `ExpansionDotFilterPill`, `HideFilterChipsSection`).
  - Introdurre test strumentati / Robolectric Compose sui flussi critici: filtro già visti + dialog conferma occhio, filtro pallino espansioni su "Miei", flusso di associazione BGG.

---

## Roadmap operativa consigliata

| Fase | Stato | Obiettivo | Elementi |
|---|---|---|---|
| **Sprint 1** | ✅ Completato (`64cd190`) | Stabilità, sicurezza & CI | P0.1 (Backup/Keystore), P0.2 (OkHttp), P0.3 (R8), P0.4 (Android CI), P2.1 (SplashScreen + Edge-to-Edge) |
| **Sprint 2** | 🔄 In proposta | Refactoring & UX "Miei"/Liste | P1.3 (Filtro/Drawer condivisi), P1.5 (Sync reattivo "Miei"), P2.2 (Pull-to-refresh + Sort), P2.3 (Preferiti + Ignorati) |
| **Sprint 3** | ⬜ Da fare | Storage scalabile & Detail modulare | P1.1 (Room), P1.2 (Sync incrementale + RPC 0013/0014), P1.4 (Split DetailScreen/VM) |
| **Sprint 4** | ⬜ Da fare | Automazione & Alert | P3.1 (WorkManager + Notifiche `user_alert`), P2.4 (Storico prezzi `0015`), P3.2 (UI Test) |
