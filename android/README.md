# Meeple Radar — app Android

App Android (Kotlin + Jetpack Compose, Material 3) che mostra le prossime uscite di giochi da tavolo
raccolte dal collector e pubblicate su Supabase, con confronto prezzi tra store, preferiti e "ignora".

Stato v1: **online-first** (nessuna cache offline). Il rating BGG e le notifiche FCM arriveranno con i
piani successivi (Plan 02 e Plan 04b); fino ad allora le colonne BGG sono `null` e i badge non vengono mostrati.

## Requisiti

- Android Studio (versione recente, con supporto AGP 9.x) e JDK 17
- Android SDK con **platform 37** installato e `compileSdk = 37` (`minSdk 26`, `targetSdk 36`)
- Toolchain: **AGP 9.4.1** + **Gradle 9.8.1** (wrapper incluso), Kotlin 2.4.20

Il progetto usa la versione di Gradle del wrapper: non serve installare Gradle a mano.

## 1. Ottenere la chiave `anon` e creare `local.properties`

1. Apri la dashboard di Supabase del progetto Meeple Radar.
2. Vai in **Project Settings -> API**.
3. Copia la chiave **`anon public`** (attenzione: **non** la `service_role`).
4. Copia `local.properties.example` in `local.properties` nella cartella `android/` e compila:
   - `sdk.dir` = percorso dell'SDK Android (Android Studio -> SDK Manager -> *Android SDK Location*)
   - `SUPABASE_ANON_KEY` = la chiave `anon public` copiata sopra
   - `SUPABASE_URL` e' gia' impostato

`local.properties` e' **ignorato da git**: la chiave non finisce nel repository. Questa app usa solo la
chiave pubblica `anon` (le tabelle sono in lettura pubblica via RLS); la `service_role` resta confinata
al collector e a GitHub Actions.

Senza `SUPABASE_ANON_KEY` l'app compila e si avvia, ma la schermata "In arrivo" mostra uno stato di
errore con il pulsante "Riprova".

## 2. Aprire il progetto in Android Studio

`File -> Open...` e seleziona la **cartella `android/`** (non la radice del repository): e' li' che si
trovano `settings.gradle.kts` e il modulo `app`. Attendi la sincronizzazione Gradle e poi esegui la
configurazione `app` su un emulatore o dispositivo (API 26+).

## 3. Build da riga di comando

Da `android/`:

```bash
# Windows
gradlew.bat :app:assembleDebug

# macOS / Linux / Git Bash
./gradlew :app:assembleDebug
```

L'APK di debug viene scritto in `app/build/outputs/apk/debug/app-debug.apk`.

Altri comandi utili:

```bash
./gradlew :app:testDebugUnitTest   # test unitari (JUnit 4 + kotlinx-coroutines-test)
./gradlew :app:lintDebug           # Android Lint
```

## Struttura

```
app/src/main/java/com/meepleradar/
  MeepleRadarApp.kt          Application: crea l'AppContainer
  AppContainer.kt            costruzione manuale delle dipendenze (OkHttp, Supabase, repository)
  MainActivity.kt            entry point Compose
  data/model/                modelli di dominio (IncomingItem, Offer, Price, ...)
  data/remote/               trasporto HTTP e client REST Supabase (PostgREST)
  data/repo/                 IncomingMapper (raggruppa listing -> item) e ListingRepository
  ui/theme/                  tema Material 3 (dark di default, colori dinamici su API 31+)
  ui/components/             ListingCard, RatingBadge, viste Loading/Error/Empty, header
  ui/nav/                    NavHost: "In arrivo", "Preferiti", "detail/{key}", bottom bar
  ui/incoming/               schermata "In arrivo" + formattazione (priceLabel/dateLabel)
  ui/detail/                 dettaglio gioco con confronto prezzi tra store
  ui/favorites/              schermata "Preferiti"
```

## Limiti noti (v1)

- **Togliere un preferito e' solo locale**: l'API REST usata dall'app non espone una DELETE, quindi
  l'elemento scompare subito dalla schermata ma ricompare dopo il prossimo refresh (i preferiti sono
  additivi). Ignorare invece e' persistente.
- Nessuna cache offline: senza rete le schermate mostrano lo stato di errore.
- Il pulsante "Apri" di ogni offerta delega al browser di sistema; l'apertura e' silenziosamente
  ignorata se nessuna app puo' gestire l'URL.
