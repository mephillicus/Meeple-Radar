# Meeple Radar — App Android (Piano 03) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Costruire l'app Android di Meeple Radar (Kotlin + Jetpack Compose) che legge le uscite da Supabase e permette di sfogliare "In arrivo", vedere il dettaglio/prezzi per store e segnare Preferito/Ignora — un progetto Gradle standard, apribile direttamente in Android Studio.

**Architecture:** App a modulo singolo `:app`, pattern MVVM + Repository, DI manuale (`AppContainer`, niente Hilt/KSP). Rete verso Supabase (PostgREST) tramite un `HttpTransport` sottile su OkHttp + kotlinx.serialization, così la logica è testabile con fake JVM (nessun emulatore). La trasformazione dati→schermata è una funzione pura (`buildIncomingItems`) con test esaustivi.

**Tech Stack:** Kotlin 2.4.20, Jetpack Compose (BOM 2026.09.00, Material 3), Navigation Compose, Coil 3 (immagini), OkHttp 5, kotlinx.serialization, kotlinx-coroutines, AGP 9.4.1, Gradle 9.8.1, JDK 17.

**Spec:** `docs/superpowers/specs/2026-10-17-meeple-radar-design.md` (§5, §7, §9, §11, §13.A) — l'esecutore legge anche quello.

## Global Constraints

- Android nativo **Kotlin + Jetpack Compose Material 3**; `applicationId`/`namespace` = `com.meepleradar`.
- Toolchain: **Gradle 9.8.1** (wrapper), **AGP 9.4.1**, **Kotlin 2.4.20** (+ plugin `org.jetbrains.kotlin.plugin.compose` 2.4.20 e `org.jetbrains.kotlin.plugin.serialization` 2.4.20), **JDK 17** (`jvmToolchain(17)`, `sourceCompatibility/targetCompatibility = 17`).
- `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`. (SDK installato: platforms `android-36`, `android-37.0`; build-tools `36.0.0`.)
- Versioni librerie (AndroidX via **Compose BOM 2026.09.00**): `androidx.activity:activity-compose:1.13.0`, `androidx.lifecycle:lifecycle-runtime-compose:2.11.0`, `androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0`, `androidx.navigation:navigation-compose:2.10.2`, `io.coil-kt.coil3:coil-compose:3.6.3`, `com.squareup.okhttp3:okhttp:5.5.0`, `org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0`, `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0`, test: `junit:junit:4.13.2`, `org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0`.
- **Nessun** Hilt/Dagger/KSP/Room/kapt. DI manuale. Nessun test strumentato (niente emulatore): solo `testDebugUnitTest`.
- La app usa **solo la chiave `anon`** di Supabase (mai la `service_role`). URL e chiave arrivano da `android/local.properties` (git-ignored) → `BuildConfig.SUPABASE_URL` / `BuildConfig.SUPABASE_ANON_KEY`. `buildFeatures.buildConfig = true`.
- Nomi tabella/colonna PostgREST (schema `0001_init.sql`): `source(id,name,kind,base_url,adapter,enabled,last_scan_at)`, `game(id,name,kind,bgg_id,bgg_rating,bgg_rank,image_url)`, `listing(id,source_id,external_id,title,url,price,currency,availability,release_date,image_url,game_id,match_confidence,first_seen_at,last_seen_at)`, `user_action(id,game_id,listing_id,action,created_at)` con `action ∈ {ignored, favorite}`.
- Chiavi di raggruppamento delle card: `"game:<id>"` (listing con `game_id`) oppure `"listing:<id>"` (listing senza gioco).
- Prezzo "migliore": minimo tra le offerte con `currency == "EUR"`; mai convertire altre valute.
- BGG e notifiche FCM sono **fuori** da questo piano: i campi `bgg_*` possono essere `null` e vanno mostrati solo se presenti.
- Regola repo (`AGENTS.md`): niente commit/push senza richiesta esplicita. Il piano però richiede un commit per task (workflow TDD del repo): eseguili sul branch di lavoro, **non pushare**.

## Review Focus

1. **`release_date` nullo** (la maggior parte delle righe): un item senza data deve comparire lo stesso come "novità — data da confermare", senza mai far crashare il parsing della data.
2. **`game_id` nullo**: un listing non abbinato a un gioco deve comunque apparire (card singola), senza NPE.
3. **`price`/`currency` nulli o non-EUR**: nessun crash; il "prezzo migliore" si calcola solo su EUR e può essere assente (mostra "n.d.").
4. **Doppioni tra store dello stesso gioco**: listing diversi con lo stesso `game_id` devono confluire in **una** card con più offerte.
5. **`user_action` con `game_id` nullo** (azione su un listing): i Preferiti devono gestirlo senza NPE; il mapping chiave→id deve distinguere `game:`/`listing:`.
6. **Chiave anon mancante** in `BuildConfig`: la repository deve restituire uno stato d'errore leggibile, non lanciare a runtime all'avvio.

---

### Task 1: Scaffold del progetto Gradle + app Compose vuota

**Files:**
- Create: `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`, `android/gradle/libs.versions.toml`, `android/gradle/wrapper/gradle-wrapper.properties`, `android/gradlew`, `android/gradlew.bat`, `android/gradle/wrapper/gradle-wrapper.jar`
- Create: `android/app/build.gradle.kts`, `android/app/proguard-rules.pro`
- Create: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/res/values/{strings.xml,themes.xml}`
- Create: `android/app/src/main/java/com/meepleradar/MainActivity.kt`, `android/app/src/main/java/com/meepleradar/AppContainer.kt`
- Create: `android/local.properties` (git-ignored), Modify: `.gitignore`
- Test: `android/app/src/test/java/com/meepleradar/PlaceholderTest.kt` (verrà sostituito nei task successivi)

**Interfaces:**
- Produces: progetto Gradle che esegue `./gradlew :app:assembleDebug` e `:app:testDebugUnitTest`; `BuildConfig.SUPABASE_URL`, `BuildConfig.SUPABASE_ANON_KEY`; `AppContainer` (vuoto) e `MainActivity` (mostra una `Text`).

- [ ] **Step 1: Verifica che il parent esista**

Run: `Test-Path -LiteralPath android` (dalla root `E:\Ai\appgiochi`). Expected: `False` → crea `android/`.

- [ ] **Step 2: Crea il version catalog `android/gradle/libs.versions.toml`**

`[versions]` con `agp=9.4.1`, `kotlin=2.4.20`, `composeBom=2026.09.00`, `activityCompose=1.13.0`, `lifecycle=2.11.0`, `navigationCompose=2.10.2`, `coil=3.6.3`, `okhttp=5.5.0`, `serializationJson=1.11.0`, `coroutines=1.11.0`, `junit=4.13.2`. `[libraries]` e `[plugins]` (`android-application`, `kotlin-android`, `kotlin-compose`, `kotlin-serialization`) coerenti con le versioni qui sopra.

- [ ] **Step 3: Crea `settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`, `app/build.gradle.kts`, manifest e risorse**

`app/build.gradle.kts` applica i plugin, imposta `namespace`/`applicationId`/`compileSdk 36`/`minSdk 26`/`targetSdk 36`, `buildFeatures { compose = true; buildConfig = true }`, `compileOptions`/`kotlin { jvmToolchain(17) }`, e legge `local.properties` con `Properties()` per i due `buildConfigField`. `AndroidManifest.xml` dichiara `MainActivity` come launcher e `android:theme`. `themes.xml` usa `Theme.Material3.DayNight.NoActionBar`.

- [ ] **Step 4: Genera il wrapper Gradle**

Run (PowerShell, da `android/`):
```powershell
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-9.6.0-bin\42k10rwplmzkhuboz9kdazi7s\gradle-9.6.0\bin\gradle.bat" wrapper --gradle-version 9.8.1 --distribution-type bin
```
Expected: compaiono `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` e `gradle-wrapper.properties` con `distributionUrl=...gradle-9.8.1-bin.zip`.

- [ ] **Step 5: Aggiungi le regole .gitignore**

Aggiungi a `.gitignore`: `android/local.properties`, `android/.gradle/`, `android/.kotlin/`, `android/build/`, `android/app/build/`, `android/.idea/`, `*.apk`. **Non** ignorare `android/gradle/wrapper/gradle-wrapper.jar`.

- [ ] **Step 6: Crea `local.properties` con URL e chiave**

Scrivi `android/local.properties` con `sdk.dir=C\:\\Users\\Zorak\\AppData\\Local\\Android\\Sdk`, `SUPABASE_URL=https://cheboodgpnlmwmhdfgrz.supabase.co` e `SUPABASE_ANON_KEY=` (valore da inserire dall'utente; può restare vuoto per i test).

- [ ] **Step 7: Crea il placeholder di test**

```kotlin
package com.meepleradar
import org.junit.Assert.assertEquals
import org.junit.Test
class PlaceholderTest { @Test fun smoke() { assertEquals(4, 2 + 2) } }
```

- [ ] **Step 8: Build e test verdi**

Run: `.\gradlew.bat :app:assembleDebug` → Expected: `BUILD SUCCESSFUL`.
Run: `.\gradlew.bat :app:testDebugUnitTest` → Expected: `BUILD SUCCESSFUL` (1 test).
Se AGP/Gradle segnalano un'incompatibilità di versione, il messaggio indica le versioni accettate: aggiorna `agp`/`distributionUrl` e ripeti. (Fallback noto: AGP `8.13.2` + Gradle `8.14.6`.)

- [ ] **Step 9: Commit**

```bash
git add android .gitignore
git commit -m "feat(app): scaffold Android Compose project"
```

---

### Task 2: Modelli di dominio + `buildIncomingItems`

**Files:**
- Create: `android/app/src/main/java/com/meepleradar/data/model/Models.kt`
- Create: `android/app/src/main/java/com/meepleradar/data/repo/IncomingMapper.kt`
- Test: `android/app/src/test/java/com/meepleradar/data/repo/IncomingMapperTest.kt`
- Delete: `android/app/src/test/java/com/meepleradar/PlaceholderTest.kt`

**Interfaces:**
- Produces:
```kotlin
enum class Action { FAVORITE, IGNORED }
enum class ItemKind { UPCOMING, NEW_UNDATED }
data class Source(val id: String, val name: String)
data class Game(val id: Long, val name: String, val kind: String, val bggId: Int?, val bggRating: Double?, val bggRank: Int?, val imageUrl: String?)
data class Listing(val id: Long, val sourceId: String, val title: String, val url: String, val price: Double?, val currency: String?, val availability: String?, val releaseDate: java.time.LocalDate?, val imageUrl: String?, val gameId: Long?, val matchConfidence: Double?)
data class Price(val amount: Double, val currency: String)
data class Offer(val sourceId: String, val sourceName: String, val price: Price?, val url: String, val availability: String?)
data class IncomingItem(val key: String, val title: String, val imageUrl: String?, val releaseDate: java.time.LocalDate?, val kind: ItemKind, val game: Game?, val offers: List<Offer>)
fun keyOf(listing: Listing): String
fun buildIncomingItems(listings: List<Listing>, games: Map<Long, Game>, sources: Map<String, Source>, ignoredKeys: Set<String>, today: java.time.LocalDate): List<IncomingItem>
```

- [ ] **Step 1: Scrivi i test (falliscono)**

In `IncomingMapperTest`, copri la Review Focus 1–4 e 6:
```kotlin
@Test fun `listing senza data diventa NEW_UNDATED e non crasha`()
@Test fun `listing senza gameId resta una card singola con chiave listing id`()
@Test fun `prezzo migliore solo tra offerte EUR e null se assente`()
@Test fun `listing dello stesso game confluiscono in una card con piu offerte`()
@Test fun `items ignorati sono esclusi`()
@Test fun `releaseDate nel passato sono esclusi ma i senza-data restano`()
@Test fun `ordinamento upcoming per data crescente poi undated per titolo`()
```
Ogni test costruisce `Listing`/`Game`/`Source` inline e verifica campi, `key`, `kind`, `offers.size`, `best` prezzo, ordine.

- [ ] **Step 2: Esegui e verifica il fallimento**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.meepleradar.data.repo.IncomingMapperTest"`
Expected: FAIL (unresolved reference `buildIncomingItems`).

- [ ] **Step 3: Implementa i modelli e il mapper**

`keyOf(listing) = if (listing.gameId != null) "game:${listing.gameId}" else "listing:${listing.id}"`. `buildIncomingItems`: raggruppa per `keyOf`, scarta le chiavi in `ignoredKeys`, per ogni gruppo `title = game?.name ?: listing.title` (primo listing), `imageUrl = game?.imageUrl ?: offers-image`, `releaseDate = min dei non-null`, `kind = if (releaseDate != null) UPCOMING else NEW_UNDATED`; scarta i gruppi con `releaseDate != null && releaseDate < today`; ordina UPCOMING per data crescente, poi NEW_UNDATED per titolo; dentro ogni item, `offers` nell'ordine dei listing.

- [ ] **Step 4: Esegui e verifica il passaggio**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.meepleradar.data.repo.IncomingMapperTest"` → Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/meepleradar/data android/app/src/test
git commit -m "feat(app): domain models and incoming mapper"
```

---

### Task 3: Trasporto HTTP + client Supabase REST

**Files:**
- Create: `android/app/src/main/java/com/meepleradar/data/remote/HttpTransport.kt`
- Create: `android/app/src/main/java/com/meepleradar/data/remote/SupabaseApi.kt`
- Test: `android/app/src/test/java/com/meepleradar/data/remote/SupabaseRestApiTest.kt`

**Interfaces:**
- Consumes: modelli del Task 2.
- Produces:
```kotlin
interface HttpTransport {
  suspend fun get(url: String, headers: Map<String, String>): String
  suspend fun post(url: String, headers: Map<String, String>, body: String): String
}
class OkHttpTransport(client: okhttp3.OkHttpClient) : HttpTransport
interface SupabaseApi {
  suspend fun listings(): List<Listing>; suspend fun games(): List<Game>; suspend fun sources(): List<Source>
  suspend fun userActions(): List<UserActionRow>
  suspend fun insertAction(row: UserActionRow)
}
data class UserActionRow(val gameId: Long?, val listingId: Long?, val action: Action)
class SupabaseRestApi(baseUrl: String, anonKey: String, transport: HttpTransport, json: kotlinx.serialization.json.Json) : SupabaseApi
```

- [ ] **Step 1: Scrivi i test con un `FakeTransport` (falliscono)**

```kotlin
@Test fun `listings costruisce url select star con header apikey e bearer`()
@Test fun `listing con release_date null e price null viene mappato senza crash`()
@Test fun `price numerica e price stringa vengono entrambe lette`()
@Test fun `insertAction fa POST su user_action con Prefer return minimal e body corretto`()
@Test fun `http 4xx diventa un'eccezione con il codice`()
```
`FakeTransport` registra url/headers/body e restituisce JSON predefiniti; verifica che l'URL sia `"$base/rest/v1/listing?select=*"`, `headers["apikey"] == anonKey`, `headers["Authorization"] == "Bearer $anonKey"`.

- [ ] **Step 2: Esegui e verifica il fallimento**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.meepleradar.data.remote.SupabaseRestApiTest"` → Expected: FAIL.

- [ ] **Step 3: Implementa `HttpTransport`/`OkHttpTransport` e `SupabaseRestApi`**

`OkHttpTransport` usa OkHttp sincrono su `Dispatchers.IO`; lancia `IOException("HTTP $code")` se `!response.isSuccessful`. `SupabaseRestApi` costruisce le URL PostgREST (`/rest/v1/<table>?select=*`), invia `apikey`, `Authorization: Bearer`, `Accept: application/json`; per l'insert aggiunge `Content-Type: application/json` e `Prefer: return=minimal` e usa body `json.encodeToString(...)`. DTO `@Serializable` con `@SerialName` snake_case; `price` deserializzato con un `JsonPrimitive`-tolerant converter (accetta numero o stringa o null); `release_date` mappato con `LocalDate.parse` in try/catch → `null`.

- [ ] **Step 4: Esegui e verifica il passaggio**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.meepleradar.data.remote.SupabaseRestApiTest"` → Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/meepleradar/data/remote android/app/src/test
git commit -m "feat(app): supabase REST client over okhttp"
```

---

### Task 4: `ListingRepository`

**Files:**
- Create: `android/app/src/main/java/com/meepleradar/data/repo/ListingRepository.kt`
- Test: `android/app/src/test/java/com/meepleradar/data/repo/DefaultListingRepositoryTest.kt`

**Interfaces:**
- Consumes: `SupabaseApi` (Task 3), `buildIncomingItems`/`keyOf` (Task 2).
- Produces:
```kotlin
interface ListingRepository {
  suspend fun incoming(today: java.time.LocalDate = java.time.LocalDate.now()): List<IncomingItem>
  suspend fun favorites(today: java.time.LocalDate = java.time.LocalDate.now()): List<IncomingItem>
  suspend fun setAction(key: String, action: Action)
}
class DefaultListingRepository(private val api: SupabaseApi) : ListingRepository
```

- [ ] **Step 1: Scrivi i test con `FakeSupabaseApi` (falliscono)**

```kotlin
@Test fun `incoming combina listing game e source ed esclude gli ignorati`()
@Test fun `favorites ritorna solo i game preferiti anche con game_id presente`()
@Test fun `setAction con chiave game invia gameId con listingId null`()
@Test fun `setAction con chiave listing invia listingId con gameId null`()
@Test fun `favorites con azione su listing non lancia`()   // Review Focus 5
```
`FakeSupabaseApi` implementa `SupabaseApi` in memoria e registra le righe inserite.

- [ ] **Step 2: Esegui e verifica il fallimento**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.meepleradar.data.repo.DefaultListingRepositoryTest"` → Expected: FAIL.

- [ ] **Step 3: Implementa la repository**

`incoming`: prende `listings()/games()/sources()/userActions()`, costruisce `ignoredKeys` dalle azioni `IGNORED`, chiama `buildIncomingItems`. `favorites`: come `incoming` ma con `ignoredKeys` dalle azioni non-`FAVORITE` e poi filtra gli item la cui chiave è tra i `FAVORITE`. `setAction(key, action)`: parsa `key` (`game:`/`listing:`), costruisce `UserActionRow` e chiama `api.insertAction`.

- [ ] **Step 4: Esegui e verifica il passaggio**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.meepleradar.data.repo.DefaultListingRepositoryTest"` → Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/meepleradar/data/repo android/app/src/test
git commit -m "feat(app): listing repository"
```

---

### Task 5: `IncomingViewModel` (stati UI + azioni)

**Files:**
- Create: `android/app/src/main/java/com/meepleradar/ui/incoming/IncomingViewModel.kt`
- Test: `android/app/src/test/java/com/meepleradar/ui/incoming/IncomingViewModelTest.kt`

**Interfaces:**
- Consumes: `ListingRepository` (Task 4).
- Produces:
```kotlin
sealed interface IncomingUiState {
  object Loading : IncomingUiState
  data class Error(val message: String) : IncomingUiState
  data class Content(val items: List<IncomingItem>, val favorites: Set<String>) : IncomingUiState
}
class IncomingViewModel(private val repo: ListingRepository, private val today: java.time.LocalDate = java.time.LocalDate.now()) : androidx.lifecycle.ViewModel() {
  val state: kotlinx.coroutines.flow.StateFlow<IncomingUiState>
  fun refresh(); fun toggleFavorite(item: IncomingItem); fun ignore(item: IncomingItem)
}
```

- [ ] **Step 1: Scrivi i test con `kotlinx-coroutines-test` (falliscono)**

```kotlin
@Test fun `refresh passa da Loading a Content con gli item`()
@Test fun `errore della repository produce Error con messaggio leggibile`()   // Review Focus 6
@Test fun `toggleFavorite chiama setAction FAVORITE e aggiorna favorites`()
@Test fun `ignore chiama setAction IGNORED e rimuove l item`()
```
Usa `Dispatchers.setMain(StandardTestDispatcher())`; la repository fake è implementata in memoria.

- [ ] **Step 2: Esegui e verifica il fallimento**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.meepleradar.ui.incoming.IncomingViewModelTest"` → Expected: FAIL.

- [ ] **Step 3: Implementa il ViewModel**

`refresh()` imposta `Loading`, chiama `repo.incoming(today)` in `viewModelScope` con try/catch → `Error(e.message ?: "Errore di rete")`, altrimenti `Content(items, favorites=...)`. `toggleFavorite`/`ignore` chiamano `repo.setAction(item.key, ...)` e aggiornano lo stato localmente.

- [ ] **Step 4: Esegui e verifica il passaggio**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.meepleradar.ui.incoming.IncomingViewModelTest"` → Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/meepleradar/ui/incoming android/app/src/test
git commit -m "feat(app): incoming view model"
```

---

### Task 6: Tema + componenti + `IncomingScreen`

**Files:**
- Create: `android/app/src/main/java/com/meepleradar/ui/theme/{Color.kt,Type.kt,Theme.kt}`
- Create: `android/app/src/main/java/com/meepleradar/ui/components/{ListingCard.kt,StateViews.kt}`
- Create: `android/app/src/main/java/com/meepleradar/ui/incoming/IncomingScreen.kt`
- Test: `android/app/src/test/java/com/meepleradar/ui/incoming/IncomingFormattingTest.kt`

**Interfaces:**
- Consumes: `IncomingUiState`/`IncomingViewModel` (Task 5), `IncomingItem` (Task 2).
- Produces: `MeepleRadarTheme { }`, `@Composable fun IncomingScreen(vm: IncomingViewModel, onOpen: (IncomingItem) -> Unit)`, e le funzioni pure `priceLabel(offer: Offer): String` e `dateLabel(item: IncomingItem): String` (formattazione testabile).

- [ ] **Step 1: Scrivi i test di formattazione (falliscono)**

```kotlin
@Test fun `priceLabel mostra EUR con due decimali e n d se assente`()
@Test fun `priceLabel mostra valuta originale per non EUR senza convertire`()
@Test fun `dateLabel per item UPCOMING mostra la data, per NEW_UNDATED mostra data da confermare`()
```

- [ ] **Step 2: Esegui e verifica il fallimento**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.meepleradar.ui.incoming.IncomingFormattingTest"` → Expected: FAIL.

- [ ] **Step 3: Implementa tema, formattazione, componenti e schermata**

`priceLabel`/`dateLabel` (pure). `ListingCard`: `Card` Material 3 con `AsyncImage` (Coil), titolo, data (o "novità — data da confermare"), prezzo migliore, `RatingBadge` (mostrato solo se `game?.bggRating != null`), numero store, icone Preferito/Ignora. `IncomingScreen` raccoglie `vm.state` con `collectAsStateWithLifecycle`, mostra `Loading`/`Error`/`Content` (empty-state se lista vuota). `MeepleRadarTheme` con dark di default e colori dinamici (`dynamicDarkColorScheme` con fallback).

- [ ] **Step 4: Build e test verdi**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.meepleradar.ui.incoming.IncomingFormattingTest"` → Expected: PASS.
Run: `.\gradlew.bat :app:assembleDebug` → Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/meepleradar/ui android/app/src/test
git commit -m "feat(app): theme, components and incoming screen"
```

---

### Task 7: Navigazione + `AppContainer` + `MainActivity`

**Files:**
- Create: `android/app/src/main/java/com/meepleradar/ui/nav/MeepleRadarNav.kt`
- Modify: `android/app/src/main/java/com/meepleradar/AppContainer.kt`, `android/app/src/main/java/com/meepleradar/MainActivity.kt`
- Create: `android/app/src/main/java/com/meepleradar/MeepleRadarApp.kt`, Modify: `AndroidManifest.xml` (`android:name=".MeepleRadarApp"`)

**Interfaces:**
- Consumes: `DefaultListingRepository`, `SupabaseRestApi`, `OkHttpTransport`, `IncomingScreen`.
- Produces: `class AppContainer(context: Context)` con `val repository: ListingRepository`; `MeepleRadarApp` con `val container: AppContainer`; NavHost con route `incoming` e `favorites`, bottom bar Material 3.

- [ ] **Step 1: Implementa `AppContainer`**

Costruisce `Json { ignoreUnknownKeys = true }`, `OkHttpClient`, `OkHttpTransport`, `SupabaseRestApi(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, transport, json)`, `DefaultListingRepository`. Se `SUPABASE_ANON_KEY` è vuota, `AppContainer` resta valido ma `refresh()` produrrà lo stato `Error` (Review Focus 6).

- [ ] **Step 2: Implementa `MeepleRadarApp`, `MainActivity` e la navigazione**

`MeepleRadarApp` crea un `AppContainer` lazy. `MainActivity` con `setContent { MeepleRadarTheme { MeepleRadarNav(container) } }`. Il NavHost ha due destinazioni con una `NavigationBar`; gli id dei `ViewModel` sono creati con `viewModel(factory = ...)` che inietta il repository.

- [ ] **Step 3: Build verde**

Run: `.\gradlew.bat :app:assembleDebug` → Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main android/app/src/main/AndroidManifest.xml
git commit -m "feat(app): navigation, DI container and entry point"
```

---

### Task 8: Dettaglio gioco con confronto prezzi

**Files:**
- Create: `android/app/src/main/java/com/meepleradar/ui/detail/{DetailViewModel.kt,DetailScreen.kt}`
- Test: `android/app/src/test/java/com/meepleradar/ui/detail/DetailViewModelTest.kt`
- Modify: `android/app/src/main/java/com/meepleradar/ui/nav/MeepleRadarNav.kt` (route `detail/{key}` con `Uri.encode`)

**Interfaces:**
- Consumes: `ListingRepository.incoming` (Task 4), `IncomingItem` (Task 2).
- Produces: `class DetailViewModel(repo: ListingRepository, key: String, today: LocalDate = LocalDate.now())` con `state: StateFlow<DetailUiState>`; `sealed interface DetailUiState { object Loading; data class NotFound; data class Content(val item: IncomingItem, val offers: List<Offer>) }`.

- [ ] **Step 1: Scrivi i test (falliscono)**

```kotlin
@Test fun `content espone le offerte ordinate per prezzo crescente con gli n d in fondo`()
@Test fun `chiave inesistente produce NotFound`()
```

- [ ] **Step 2: Esegui e verifica il fallimento**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.meepleradar.ui.detail.DetailViewModelTest"` → Expected: FAIL.

- [ ] **Step 3: Implementa `DetailViewModel` e `DetailScreen`**

`DetailViewModel` carica `repo.incoming(today)`, trova l'item con `key` uguale e produce `Content` (offerte con prezzo EUR in testa, poi le altre, "n.d." in fondo) o `NotFound`. `DetailScreen` mostra titolo, immagine, rating BGG se presente, e per ogni offerta: nome store, `priceLabel`, disponibilità, pulsante "Apri" che lancia l'URL (`LocalUriHandler.openUri`).

- [ ] **Step 4: Esegui e verifica il passaggio**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.meepleradar.ui.detail.DetailViewModelTest"` → Expected: PASS.
Run: `.\gradlew.bat :app:assembleDebug` → Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/meepleradar/ui android/app/src/test
git commit -m "feat(app): detail screen with price comparison"
```

---

### Task 9: Schermata Preferiti

**Files:**
- Create: `android/app/src/main/java/com/meepleradar/ui/favorites/{FavoritesViewModel.kt,FavoritesScreen.kt}`
- Test: `android/app/src/test/java/com/meepleradar/ui/favorites/FavoritesViewModelTest.kt`
- Modify: `android/app/src/main/java/com/meepleradar/ui/nav/MeepleRadarNav.kt` (collega `favorites` a `FavoritesScreen`)

**Interfaces:**
- Consumes: `ListingRepository.favorites` (Task 4), `IncomingItem` (Task 2).
- Produces: `class FavoritesViewModel(repo, today = LocalDate.now())` con `state: StateFlow<IncomingUiState>` (riusa gli stati del Task 5).

- [ ] **Step 1: Scrivi i test (falliscono)**

```kotlin
@Test fun `favorites mostra solo gli item preferiti`()
@Test fun `lista vuota produce Content con lista vuota`()
@Test fun `errore rete produce Error`()
```

- [ ] **Step 2: Esegui e verifica il fallimento**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.meepleradar.ui.favorites.FavoritesViewModelTest"` → Expected: FAIL.

- [ ] **Step 3: Implementa ViewModel e schermata**

`FavoritesViewModel` riusa la logica di caricamento del Task 5 su `repo.favorites`. `FavoritesScreen` riusa `ListingCard` e mostra un empty-state quando la lista è vuota.

- [ ] **Step 4: Esegui e verifica il passaggio**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.meepleradar.ui.favorites.FavoritesViewModelTest"` → Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/meepleradar/ui android/app/src/test
git commit -m "feat(app): favorites screen"
```

---

### Task 10: Documentazione per Android Studio, lint e build finale

**Files:**
- Create: `android/README.md`
- Create: `android/local.properties.example`

**Interfaces:**
- Produces: istruzioni per aprire il progetto in Android Studio e inserire la chiave anon; build e lint verdi.

- [ ] **Step 1: Scrivi `android/local.properties.example`**

Contenuto con `sdk.dir`, `SUPABASE_URL=https://cheboodgpnlmwmhdfgrz.supabase.co`, `SUPABASE_ANON_KEY=<la tua chiave anon pubblica>`.

- [ ] **Step 2: Scrivi `android/README.md`**

Spiega: come ottenere la chiave `anon` da Supabase (Dashboard → Project Settings → API), copiarla in `local.properties`, aprire la cartella `android/` in Android Studio (File → Open), eseguire `./gradlew :app:assembleDebug` da CLI, e che BGG/FCM arriveranno in piani successivi. Nota che l'app usa solo la chiave pubblica.

- [ ] **Step 3: Lint e build finale**

Run: `.\gradlew.bat :app:lintDebug` → Expected: `BUILD SUCCESSFUL` (warning accettabili, nessun error).
Run: `.\gradlew.bat :app:testDebugUnitTest` → Expected: tutti i test PASS.
Run: `.\gradlew.bat :app:assembleDebug` → Expected: `BUILD SUCCESSFUL`; l'APK è in `app/build/outputs/apk/debug/`.

- [ ] **Step 4: Commit**

```bash
git add android/README.md android/local.properties.example
git commit -m "docs(app): android studio setup and usage"
```

---

## Self-review (eseguito)

- **Copertura spec:** §5/§7 stack (Kotlin/Compose/Material 3, MVVM+repository, lettura diretta Supabase) → Task 1,6,7; §7 modello dati/RLS (anon legge, scrive `user_action`) → Task 3,4,8; §9 schermate "In arrivo", "Dettaglio", "Preferiti", azioni Ignora/Preferito → Task 6,8,9,5. **Fuori ambito (dichiarato):** "Per te"/onboarding (dipendono da BGG, Plan 02), notifiche FCM (Plan 04b), cache offline (Room) — rimandate.
- **Tipi coerenti:** `keyOf`/`buildIncomingItems` (Task 2) usati in 4/5/8/9; `SupabaseApi`/`UserActionRow` (Task 3) usati in 4; `IncomingUiState` (Task 5) riusato in 9; `Offer`/`IncomingItem` definiti una sola volta.
- **Review Focus coperto:** (1) Task 2 test "senza data"; (2) Task 2 test "senza gameId"; (3) Task 2 test "prezzo EUR"; (4) Task 2 test "stesso game"; (5) Task 4 test "azione su listing"; (6) Task 5 test "errore" + Task 7 Step 1.
- **Proporzione:** firme, contratti e asserzioni; i corpi Compose sono lasciati all'implementatore (dipendono dal rendering), con i test della logica pura che li vincolano.

## Questioni aperte (per il revisore umano)

- **Chiave anon:** serve il valore reale (`SUPABASE_ANON_KEY`) per vedere dati veri in Task 7; senza, l'app funziona ma mostra `Error`. Va inserita in `android/local.properties` (git-ignored).
- **Versioni toolchain:** AGP 9.4.1/Gradle 9.8.1 sono le stabili correnti; se il build rifiuta la combinazione, Task 1 Step 8 documenta il fallback (AGP 8.13.2 + Gradle 8.14.6). L'ambiente ha già Gradle 9.6 in cache.
- **Scope v1 app:** online-first (niente cache offline); l'app mostra le novità e i preferiti; il "Per te" (espansioni dei posseduti) e le notifiche arrivano con BGG (Plan 02) e Plan 04b.
