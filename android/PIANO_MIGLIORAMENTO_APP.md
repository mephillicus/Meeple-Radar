# Piano di Miglioramento — Meeple Radar (App Android)

> **Ambito:** modulo Android (`android/`, package `com.meepleradar`).
> **Documento gemello (backend/collector/CI):** `../PIANO_MIGLIORAMENTO_BACKEND.md`.
> **Ultimo aggiornamento:** 2026-11-21 — **Sprint 1 (`64cd190`), Sprint 2 (`f831107`), Sprint 3 (`3772de4`) e Sprint 4 completati**.
> **Stato baseline verificato:** AGP `9.4.1`, Kotlin `2.4.20`, Compose BOM `2026.09.00`, Room `2.8.5` + KSP `2.3.14`, WorkManager `2.12.0`, `compileSdk = 37`, `targetSdk = 36`, `minSdk = 26`, 159 unit test verdi, lint, debug e release (R8) build verdi.

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

### [x] P1.1 — Migrazione della cache locale da `SharedPreferences` a Room (SQLite) — *Completato (Sprint 3)*
- **File coinvolti:** `data/local/db/CatalogEntities.kt`, `CatalogDao.kt`, `MeepleRadarDatabase.kt`, `RoomLocalCacheStore.kt`, `LocalCacheStore.kt`, `AppContainer.kt`, `backup_rules.xml`, `data_extraction_rules.xml`, `gradle/libs.versions.toml`, `app/build.gradle.kts`.
- **Intervento applicato:**
  - Aggiunti Room `2.8.5` e KSP `2.3.14` (compatibili con AGP `9.4.1` built-in Kotlin).
  - Create entità indicizzate `ListingEntity`, `GameEntity`, `ProductEntity` (con tutti i campi inclusi `url`, `price`, `currency`, `publisher`, `updatedAt`), `SourceEntity`, `SyncMetaEntity` e `CatalogDao` con transazioni `@Transaction` atomiche (`replaceAllCatalog`, `applyDelta`, `upsertGameAndOptionalListing`).
  - Implementato `RoomLocalCacheStore` con migrazione trasparente one-shot dalla cache legacy `SharedPreferences` (`meeple_radar_db_cache`, poi ripulita), mirror scalare non bloccante per lo startup ed esclusione di `meeple_radar_cache.db` dalle regole di backup.

### [x] P1.2 — Sync incrementale con Supabase (`0014`) e RPC atomiche (`0013`) — *Completato (Sprint 3)*
- **File coinvolti:** `data/remote/SupabaseApi.kt`, `data/repo/ListingRepository.kt`, `data/model/Models.kt`, `SupabaseRestApiTest.kt`, `DefaultListingRepositoryTest.kt`.
- **Intervento applicato:**
  - Migrate le scritture `updateGameBggStats`, `linkListingToBgg` e `saveGameToDatabase` sulle RPC atomiche `0013` (`POST /rest/v1/rpc/...`) con fallback automatico ai flussi legacy solo in caso di HTTP 404 / `PGRST202`.
  - Aggiunti endpoint delta `listingsUpdatedSince`, `gamesUpdatedSince`, `productsUpdatedSince` (`updated_at=gt.<iso>` URL-encoded) e campo `updatedAt` sui modelli.
  - Implementata policy ibrida in `DefaultListingRepository`: delta sync incrementale con watermark server-side (`max(updated_at)`) + full sync automatico se la cache è vuota o più vecchia di 7 giorni + fallback silente a full sync su HTTP 400.

### [x] P1.3 — Eliminazione duplicazioni tra Lista, Novità e Preordini — *Completato (Sprint 2)*
- **File coinvolti:** `ListingFilterEngine.kt`, `ListingFilterDrawer.kt`, `StateViews.kt`, `NewsScreen.kt`, `PreordersScreen.kt`, `IncomingScreen.kt`, `NewsViewModel.kt`, `PreordersViewModel.kt`, `IncomingViewModel.kt`.
- **Intervento applicato:**
  - Estratti `ListingFilterState`, `ListingSortOption` e `filterAndSortListings` in `ui/components/ListingFilterEngine.kt` con suite di unit test (`ListingFilterEngineTest.kt`).
  - Estratti `ListingFilterDrawerOverlay` / `ListingFilterDrawerContent` in `ui/components/ListingFilterDrawer.kt` e `CompactSearchBar` + `BlockVector` in `ui/components/StateViews.kt`.
  - Rimossa la duplicazione tra `NewsScreen`, `PreordersScreen`, `IncomingScreen` e rispettivi ViewModel.

### [x] P1.4 — Modularizzazione di `DetailScreen.kt` e `DetailViewModel.kt` — *Completato (Sprint 3)*
- **File coinvolti:** `ui/detail/DetailScreen.kt`, `DetailHeaderSection.kt`, `DetailOffersSection.kt`, `DetailBggMetadataSection.kt`, `DetailBggAssociationSection.kt`, `DetailViewModel.kt`, `BggLinkingUseCase.kt`, `BggLinkingUseCaseTest.kt`.
- **Intervento applicato:**
  - Spezzato `DetailScreen.kt` (da ~1.400 righe) in 5 file coesi per sezione (`DetailScreen`, `DetailHeaderSection`, `DetailOffersSection`, `DetailBggMetadataSection`, `DetailBggAssociationSection`) senza modifiche grafiche.
  - Estratto `BggLinkingUseCase` per risoluzione BGG, persistenza selezioni manuali, linking, refresh statistiche BGG e salvataggio prodotto manuale, con suite dedicata `BggLinkingUseCaseTest.kt`.

### [x] P1.5 — Riallineamento reattivo della schermata "Miei" (`MyCollectionScreen.kt`, `BggCollectionViewModel.kt`) — *Completato (Sprint 2)*
- **Problema:** `BggCollectionViewModel.loadListingData()` girava solo in `init` e dopo `fetch()` da BGG; `searchQuery`, `showBaseGames` e `showExpansionPills` vivevano in `remember`.
- **Intervento applicato:**
  - `BggCollectionViewModel` osserva `repository.lastDbModifiedTimestampFlow()` (`drop(1).distinctUntilChanged()`) per aggiornare automaticamente `listingsByBggId` e `expansionAvailabilityById` ad ogni aggiornamento della cache.
  - Spostati `searchQuery`, `showBaseGames` e `showExpansionPills` (+ `resetFilters()`) in `BggCollectionViewModel` con unit test dedicati.

---

## Priorità P2 — UX, Material 3 e Funzionalità Utente

### [x] P2.1 — SplashScreen API nativa & Edge-to-Edge coerente (`MainActivity.kt`, `themes.xml`, `MeepleRadarNav.kt`, `DetailScreen.kt`) — *Completato (`64cd190`)*
- **Problema:** `MainActivity.kt` usa `delay(800L)` su `Color.White` fisso (abbagliante in dark mode e rallenta artificialmente lo startup).
- **Intervento applicato:**
  - Adottata `androidx.core:core-splashscreen:1.2.0` con `Theme.MeepleRadar.Starting` su `#0F172A`, `enableEdgeToEdge(...)`, rimozione `delay(800L)`, sistemati inset bottom bar (`MeepleRadarNav`), `DetailScreen` (`WindowInsets.safeDrawing`) e `SetupContent` (`imePadding` + scroll).

### [x] P2.2 — Pull-to-refresh nativo e Ordinamento configurabile — *Completato (Sprint 2)*
- **Intervento applicato:**
  - Avvolte tutte e 5 le schermate lista (`Lista`, `Novità`, `Preordini`, `Preferiti`, `Miei`) in `PullToRefreshBox` di Material 3 con stato `isRefreshing` non distruttivo.
  - Aggiunta la sezione **"Ordina per"** nel drawer condiviso (`Predefinito`, `A-Z`, `Prezzo ↑`, `Data ↑`, `Data ↓`, `Voto BGG ↓`), nascondendo `AlphabetFastScroller` quando l'ordinamento non è alfabetico.

### [x] P2.3 — Potenziamento schermata Preferiti e gestione "Ignorati" — *Completato (Sprint 2)*
- **File coinvolti:** `FavoritesScreen.kt`, `FavoritesViewModel.kt`, `IgnoredGamesSheet.kt`, `ListingRepository.kt`, `IncomingScreen.kt`, `IncomingViewModel.kt`.
- **Intervento applicato:**
  - Potenziata `FavoritesScreen` con pillola `[🚫 N]` per gli ignorati, pulsante refresh, `CompactSearchBar`, chip disponibilità (`Tutti`, `Disponibili`, `Preordini`, `Non disp.`) e `PullToRefreshBox`.
  - Creato `IgnoredGamesSheet` (`ModalBottomSheet`) con pulsante **"Ripristina"** per ogni gioco ignorato, accessibile dall'header di Preferiti e dal drawer di Lista.
  - Aggiunti `ignoredItems(today)` e `restoreIgnored(key)` in `ListingRepository` + fix di `latestActionsByKey` affinché `Action.SEEN` non mascheri mai `FAVORITE`/`IGNORED`.

### [x] P2.4 — Confronto prezzi, badge calo prezzo e storico (`0015_price_history.sql`) — *Completato (Sprint 4)*
- **File coinvolti:** `Models.kt`, `SupabaseApi.kt`, `IncomingMapper.kt`, `ListingRepository.kt`, `ListingCard.kt`, `DetailViewModel.kt`, `DetailScreen.kt`, `DetailOffersSection.kt`, `SupabaseRestApiTest.kt`, `DetailViewModelTest.kt`.
- **Intervento applicato:**
  - Integrati gli endpoint `listing_best_price` e `listing_price_history` con fallback silente su HTTP 404/400 se la migrazione `0015` non è ancora applicata.
  - Aggiunto micro-badge verde `-XX%` (per ribassi ≥ 5%) su `ListingCard` accanto al miglior prezzo.
  - Arricchita `OfferRow` in `DetailOffersSection.kt` con prezzo precedente barrato, badge `-XX%` e chip `MINIMO STORICO`, più la card compatta **"Storico prezzi"** in `DetailScreen.kt` (visibile con ≥ 2 snapshot).

---

## Priorità P3 — Notifiche, Background Sync e Testing Avanzato

### [x] P3.1 — Background sync (`WorkManager`), Centro Avvisi (`0016_user_alerts.sql`) e Impostazioni Notifiche — *Completato (Sprint 4)*
- **File coinvolti:** `libs.versions.toml`, `app/build.gradle.kts`, `AndroidManifest.xml`, `NotificationPreferencesStore.kt`, `AlertNotificationEvaluator.kt`, `CatalogSyncWorker.kt`, `AppContainer.kt`, `MeepleRadarApp.kt`, `MainActivity.kt`, `MeepleRadarNav.kt`, `AlertsViewModel.kt`, `AlertsAndSettingsSheet.kt`, `FavoritesScreen.kt`, `ListingFilterDrawer.kt`, `IncomingScreen.kt`, `AlertNotificationEvaluatorTest.kt`.
- **Intervento applicato:**
  - Aggiunto `androidx.work:work-runtime-ktx:2.12.0` e permesso `POST_NOTIFICATIONS` con richiesta runtime su Android 13+ all'attivazione del toggle master.
  - Implementati `CatalogSyncWorker` (sync periodico ogni 12h su rete attiva e batteria non scarica), `SyncWorkScheduler`, `NotificationHelper` (canale `meeple_radar_alerts` + deep link `EXTRA_OPEN_DETAIL_KEY` verso `detail/{key}`) e `AlertNotificationEvaluator` (logica pura testata su JVM con deduplica degli alert già notificati).
  - Creato `AlertsAndSettingsSheet` (`ModalBottomSheet` a 2 tab **Avvisi (N)** con "Segna letti" / `[Apri ›]` e **Impostazioni** con master switch + 4 categorie notifiche), accessibile sia dalla pillola `[🔔 N]` in `FavoritesScreen` sia dalla voce **"Avvisi e Notifiche"** nel drawer di `IncomingScreen`.

### [x] P3.2 — `@Preview` Light/Dark e copertura Unit Test Sprint 4 — *Completato (Sprint 4)*
- **File coinvolti:** `ListingCard.kt`, `DetailOffersSection.kt`, `AlertsAndSettingsSheet.kt`, `StateViews.kt`, `SupabaseRestApiTest.kt`, `AlertNotificationEvaluatorTest.kt`, `DetailViewModelTest.kt`.
- **Intervento applicato:**
  - Aggiunte `@Preview` Light & Dark sui componenti chiave (`ListingCard`, `OfferRow` + `PriceHistoryCard`, `AlertsAndSettingsSheetContent` tab Avvisi e Impostazioni, `UnseenFilterPill` / `MarkAllSeenButton` / `CompactSearchBar`).
  - Aggiunti unit test dedicati per `listingBestPrices`, `listingPriceHistory`, `userAlerts`, `markAlertRead`/`markAllAlertsRead`, `AlertNotificationEvaluator` e caricamento storico prezzi in `DetailViewModel` (totale: 159/159 unit test verdi).

---

## Roadmap operativa consigliata

| Fase | Stato | Obiettivo | Elementi |
|---|---|---|---|
| **Sprint 1** | ✅ Completato (`64cd190`) | Stabilità, sicurezza & CI | P0.1 (Backup/Keystore), P0.2 (OkHttp), P0.3 (R8), P0.4 (Android CI), P2.1 (SplashScreen + Edge-to-Edge) |
| **Sprint 2** | ✅ Completato (`f831107`) | Refactoring & UX "Miei"/Liste | P1.3 (Filtro/Drawer condivisi), P1.5 (Sync reattivo "Miei"), P2.2 (Pull-to-refresh + Sort), P2.3 (Preferiti + Ignorati) |
| **Sprint 3** | ✅ Completato (`3772de4`) | Storage scalabile & Detail modulare | P1.1 (Room), P1.2 (Sync incrementale + RPC 0013/0014), P1.4 (Split DetailScreen/VM + BggLinkingUseCase) |
| **Sprint 4** | ✅ Completato | Automazione, Alert & Storico Prezzi | P2.4 (Storico prezzi `0015` + badge calo/minimo), P3.1 (WorkManager + Notifiche `0016` + Centro Avvisi), P3.2 (`@Preview` Light/Dark + Unit Test) |
