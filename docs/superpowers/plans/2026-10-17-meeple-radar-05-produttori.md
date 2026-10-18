# Meeple Radar — Piano 05: tutti i produttori

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** aggiungere 6 nuove fonti editoriali italiane al collector (Studio Supernova, Little Rocket Games, MS Edizioni, dV Games, Horrible Guild, GateOnGames), portando il seed da 11 a 17 fonti e le abilitate da 7 a 13.

**Architecture:** nessuna nuova piattaforma: le 3 fonti che espongono un'API JSON usano gli adattatori esistenti (`shopify`, `woocommerce`). Le 3 fonti con REST chiuso ma markup leggibile usano il kind `html` (spec di crawling come Philibert/Asmodee). Due estensioni minime all'architettura HTML servono a estrarre titolo/prezzo da markup custom e a portare la data di uscita da una pagina di elenco al singolo prodotto.

**Tech Stack:** Node 22, TypeScript (ESM, import con `.js`), zod, cheerio, vitest; GitHub Actions → Supabase.

**Spec:** `docs/superpowers/specs/2026-10-17-meeple-radar-design.md`

## Global Constraints

- Test: `npm.cmd test` (da `collector\`); typecheck: `npm.cmd run typecheck`. Entrambi devono restare verdi a ogni task.
- Windows PowerShell 5.1: separare i comandi con `;`, mai `&&`; `npm.cmd` non `npm`; percorsi con spazi tra virgolette.
- `SOURCE_KINDS` è chiuso: `shopify | woocommerce | wordpress | html | headless`. Non si aggiungono kind.
- Una fonte `html`/`headless` **deve** avere uno spec in `HTML_SPECS`/`HEADLESS_SPECS`, altrimenti il registry lancia `nessuno spec ... per la fonte`.
- `enabled: true` **solo** dopo una sonda dal vivo che restituisce `count > 0`; altrimenti resta `enabled: false` e la si segnala.
- Prezzi: dalle API (Shopify `variants[0].price`, WooCommerce Store API `prices.price` + `currency_minor_unit`). Gli editori spesso **non** hanno prezzo → `price` resta `null`, mai inventato.
- La data di uscita si ricava dal testo (`extractReleaseDate`) o da un valore ISO strutturato; se non c'è, resta `null`.
- Commit conventional (es. `feat(collector): ...`), uno per task. Niente push senza richiesta esplicita.
- Nessuna migrazione SQL in questo piano (si aggiungono fonti, non si rimuovono).
- Commenti solo in italiano e solo dove i file vicini ne hanno già; il codice segue il file che si modifica.

## Contesto di ricognizione (2026-10-17, verificato dal vivo)

| Fonte | Piattaforma | Accesso | Esito |
|---|---|---|---|
| Studio Supernova | Shopify (`23.227.38.x`) | `https://www.studiosupernova.it/products.json` ✅ (prezzo, `published_at`) | **shopify**. Il dominio senza `www` ha TLS rotto: usare `www` |
| Little Rocket Games | WooCommerce | `/wp-json/wc/store/v1/products` ✅ (5 item, prezzi) | **woocommerce** |
| MS Edizioni | WooCommerce | `/wp-json/wc/store/v1/products` ✅ (5 item, prezzi) | **woocommerce** |
| dV Games | PHP custom, niente JSON-LD/OG | `/prossime-uscite/` elenca i prodotti con data completa nel markup (es. `27 OTTOBRE 2026`), prodotto su `/catalogo/<slug>/` (nome nel primo `<h2>`, nessun prezzo) | **html** + extract custom + data dalla pagina di elenco |
| Horrible Guild | WooCommerce, REST 404 | prodotti su `/eu/product/<slug>/`, titolo in `.product_title`, prezzo in `.summary .price`; elenco in `/eu/shop/` (14 link) | **html** + extract custom |
| GateOnGames | WordPress senza shop REST | catalogo su `/prodotti/localizzazioni/` e `/prodotti/originali-gog/`; pagine prodotto a un solo segmento con `og:title` ✅ | **html** (extract generico) |
| Ergo Ludo Editions | IONOS | `503` su HTTP, TLS fallito su HTTPS | **non aggiungibile**: sito non raggiungibile |

## Review Focus

1. **Link di menu scambiati per prodotti** (`/chi-siamo/`, `/contattaci/`, `/privacy-policy/`, `/rulebook/`): creerebbero listing spazzatura con titolo di pagina. Il filtro URL di GateOnGames deve avere una deny-list, con test che prova che quei link vengono scartati.
2. **Pagina prodotto senza `og:`/JSON-LD** (markup custom, come dV Games): l'estrattore generico restituisce `null` e il prodotto si perde in silenzio. Gli extract custom devono costruire il prodotto con `buildProduct` (che rifiuta i titoli vuoti), con test su fixture di markup reale.
3. **Data presente solo nella pagina di elenco** (dV Games): senza il passaggio `DiscoveredProduct.releaseDate` → `releaseDateRaw` → `releaseDate` la data si perde. Test dedicato in Task 2 e in Task 4.
4. **Pagine di locale straniere** (`/us/`, `/de/`, `/fr/`, `/es/`): prezzi in USD o duplicati. Horrible Guild si scopre solo da `/eu/`, GateOnGames solo dagli slug italiani: test che prova che gli URL stranieri non passano il filtro.
5. **Prodotto senza prezzo**: catalogo editore (dV Games, GateOnGames) → `price` deve poter essere `null` senza eccezioni né `0`. Test su `normalizeListing`/probe.

---

### Task 1: Estrazione custom per uno spec HTML

**Files:**
- Modify: `collector/src/adapters/html/crawl.ts`
- Modify: `collector/src/adapters/html/extract.ts`
- Test: `collector/test/adapters/crawl.test.ts`

**Interfaces:**
- Consumes: `extractProduct(html, url): RawProduct | null` (già in `extract.ts`), `TextFetcher`.
- Produces:
  - `HtmlSourceSpec = { discover: (fetchText: TextFetcher) => Promise<string[]>; extract?: (html: string, url: string) => RawProduct | null }`
  - `buildProduct(fields: { title?: string; url: string; externalId?: string; price?: string; currency?: string; availability?: string; image?: string; description?: string }): RawProduct | null` esportata da `extract.ts`.
  - `collectHtmlProducts({ spec, fetchText, maxProducts?, delayMs? })` invariata nella firma; usa `spec.extract` quando presente, altrimenti `extractProduct`.

- [ ] **Step 1: Test che fallisce** — in `crawl.test.ts` aggiungere due test: uno spec con `extract: (html, url) => ({ externalId: 'custom', title: 'Custom', url })` restituisce quel prodotto (titolo `Custom`, non quello del JSON-LD presente nella pagina); uno spec **senza** `extract` continua a usare il percorso generico (prodotto dal JSON-LD `P1`).

- [ ] **Step 2: Esegui e verifica che fallisca**

Run: `npm.cmd test test/adapters/crawl.test.ts`
Expected: FAIL (il primo test riceve il titolo dal JSON-LD, `A`, invece di `Custom`).

- [ ] **Step 3: Implementa** — in `extract.ts` aggiungi `export` a `buildProduct`; in `crawl.ts` estendi il tipo `HtmlSourceSpec` con `extract?` e in `collectHtmlProducts` sostituisci la chiamata con `const product = spec.extract ? spec.extract(html, urls[i]) : extractProduct(html, urls[i])`.

- [ ] **Step 4: Esegui e verifica che passi**

Run: `npm.cmd test test/adapters/crawl.test.ts`
Expected: PASS (6 test).

- [ ] **Step 5: Commit** — `git add collector/src/adapters/html/crawl.ts collector/src/adapters/html/extract.ts collector/test/adapters/crawl.test.ts; git commit -m "feat(collector): allow per-source product extraction for html specs"`

---

### Task 2: Data di uscita dalla pagina di elenco

**Files:**
- Modify: `collector/src/types.ts`, `collector/src/normalize.ts`, `collector/src/adapters/html/crawl.ts`, `collector/src/adapters/html/links.ts`
- Modify (solo `toItems(...)`): `collector/src/adapters/html/{philibert,asmodee,giochiuniti,uplay,ravensburger,dungeondice}.ts`, `collector/src/adapters/headless/magicmerchant.ts` (7 spec)
- Test: `collector/test/normalize.test.ts`, `collector/test/adapters/crawl.test.ts`, `collector/test/adapters/specs-crawl.test.ts`, `collector/test/adapters/specs-uplay-asmodee.test.ts`, `collector/test/adapters/specs-gu-ravensburger.test.ts` (`headless.test.ts` non va toccato: passa lo spec a `collectHtmlProducts`)

**Interfaces:**
- Consumes: `collectLinks(fetchText, seedUrls, matches): Promise<string[]>`.
- Produces:
  - `RawProductSchema` con `releaseDateRaw: z.string().optional()` aggiunto; `RawProduct` lo eredita.
  - `normalizeListing` → `releaseDate: isoOrNull(raw.releaseDateRaw, raw.descriptionRaw)` dove `isoOrNull(preferred, text)` restituisce `preferred` se è una data ISO valida, altrimenti `extractReleaseDate(text)`.
  - `DiscoveredProduct = { url: string; releaseDate?: string }` esportato da `crawl.ts`.
  - `HtmlSourceSpec.discover: (fetchText: TextFetcher) => Promise<DiscoveredProduct[]>`.
  - `toItems(urls: string[]): DiscoveredProduct[]` esportata da `links.ts`.
  - `collectHtmlProducts` copia `releaseDateRaw` dal `DiscoveredProduct` al prodotto solo se il prodotto non ne ha già uno.

- [ ] **Step 1: Test che falliscono** — in `normalize.test.ts`: `normalizeListing('x', { externalId:'1', title:'X', url:'https://e.com/x', releaseDateRaw:'2026-10-27' })` ha `releaseDate === '2026-10-27'` (non `'2026-10-01'`, la trappola del parsing testuale) e `releaseDateRaw:'non-una-data'` con descrizione `'<p>In uscita: 2026-04</p>'` ricade su `'2026-04-01'`. In `crawl.test.ts`: uno spec che restituisce `[{ url: 'https://s/p1', releaseDate: '2026-11-13' }]` produce un prodotto con `releaseDateRaw === '2026-11-13'`.

- [ ] **Step 2: Esegui e verifica che falliscano**

Run: `npm.cmd test test/normalize.test.ts test/adapters/crawl.test.ts`
Expected: FAIL (`releaseDate` è `2026-10-01` / `releaseDateRaw` è `undefined`).

- [ ] **Step 3: Implementa** — aggiungi il campo allo schema, `isoOrNull` in `normalize.ts`, `DiscoveredProduct` + merge in `crawl.ts`, `toItems` in `links.ts`. In `collectHtmlProducts` la chiamata dell'estrattore diventa `spec.extract ? spec.extract(html, item.url) : extractProduct(html, item.url)` (dove `item` è l'elemento di `DiscoveredProduct[]`). Aggiorna i 7 spec esistenti (avvolgendo l'esito di `collectLinks`/`fetchSitemapEntries` in `toItems`) e i 3 file di test che confrontano l'output di `discover` con array di URL: ora l'atteso è `[{ url: '...' }]` (`specs-crawl.test.ts`, `specs-uplay-asmodee.test.ts`, `specs-gu-ravensburger.test.ts`).

- [ ] **Step 4: Esegui e verifica che passi**

Run: `npm.cmd test; npm.cmd run typecheck`
Expected: PASS, 45+ test verdi, typecheck senza errori.

- [ ] **Step 5: Commit** — `git add collector/src collector/test; git commit -m "feat(collector): carry a structured release date from listing pages"`

---

### Task 3: Studio Supernova, Little Rocket Games, MS Edizioni (API)

**Files:**
- Modify: `collector/src/sources.seed.ts`, `collector/test/sources.test.ts`

**Interfaces:**
- Consumes: `fetchShopifyProducts(baseUrl, http?)`, `fetchWooCommerceProducts(baseUrl, currency, http?)` — nessuna modifica.
- Produces: tre voci in `SOURCES` con id `supernova` (kind `shopify`), `littlerocket` (kind `woocommerce`), `msedizioni` (kind `woocommerce`), tutte `enabled: true`.

- [ ] **Step 1: Test che fallisce** — in `sources.test.ts`: `SOURCES` ha lunghezza **14** e l'elenco ordinato delle abilitate è `['asmodee','boardhouse','cranio','ghenos','giochiuniti','littlerocket','msedizioni','pendragon','philibert','supernova']`.

- [ ] **Step 2: Esegui e verifica che fallisca**

Run: `npm.cmd test test/sources.test.ts`
Expected: FAIL (11 fonti, 7 abilitate).

- [ ] **Step 3: Implementa** — in fondo all'array `SOURCES`, con lo stesso allineamento a colonne delle altre righe:

```ts
  { id: 'supernova',     name: 'Studio Supernova',      kind: 'shopify',     baseUrl: 'https://www.studiosupernova.it',   enabled: true },   // il dominio senza www ha TLS rotto
  { id: 'littlerocket',  name: 'Little Rocket Games',   kind: 'woocommerce', baseUrl: 'https://www.littlerocketgames.com', enabled: true },
  { id: 'msedizioni',    name: 'MS Edizioni',           kind: 'woocommerce', baseUrl: 'https://www.msedizioni.it',        enabled: true },
```

- [ ] **Step 4: Esegui e verifica che passi**

Run: `npm.cmd test test/sources.test.ts`
Expected: PASS (3 test).

- [ ] **Step 5: Sonda dal vivo** (rete, 3 comandi separati; `MEEPLE_MAX_PRODUCTS` è impostato a 40 dallo script)

Run: `npx tsx scripts/probe-source.ts supernova; npx tsx scripts/probe-source.ts littlerocket; npx tsx scripts/probe-source.ts msedizioni`
Expected: `"count"` > 0 per tutte e tre, con `sample[0].title` non vuoto; per `supernova` anche `priceRaw` valorizzato. Se una fonte dà `count: 0`, **non** abilitarla: rimuovila (o mettila `enabled: false`) e segnalalo nel commit.

- [ ] **Step 6: Commit** — `git add collector/src/sources.seed.ts collector/test/sources.test.ts; git commit -m "feat(collector): add Studio Supernova, Little Rocket and MS Edizioni sources"`

---

### Task 4: dV Games (html + extract custom + data dall'elenco)

**Files:**
- Create: `collector/src/adapters/html/dvgiochi.ts`
- Modify: `collector/src/adapters/html/specs.ts`, `collector/src/sources.seed.ts`, `collector/test/sources.test.ts`
- Test: `collector/test/adapters/specs-publishers.test.ts` (nuovo)

**Interfaces:**
- Consumes: `DiscoveredProduct`, `HtmlSourceSpec` (Task 2), `buildProduct` (Task 1).
- Produces: `export const dvgiochiSpec: HtmlSourceSpec` con
  - `discover`: legge `https://www.dvgiochi.com/prossime-uscite/`, e per ogni `a[href]` il cui URL assoluto rispetta `/^https:\/\/www\.dvgiochi\.com\/catalogo\/[a-z0-9-]+\/$/` restituisce `{ url, releaseDate }` dove `releaseDate` è la data trovata **all'inizio** del testo dell'ancora (`/^(\d{1,2})\s+(GENNAIO|FEBBRAIO|…|DICEMBRE)\s+(\d{4})/`, es. `27 OTTOBRE 2026` → `2026-10-27`); ancore senza data all'inizio (es. `DA DEFINIRE …`) → campo assente. L'ancora contiene anche una descrizione dopo la data, quindi il pattern va ancorato.
  - `extract`: titolo = attributo `title` dell'`<img>` dentro il primo `<h2>` (fallback `alt`), trim; se manca o è vuoto, primo nodo di testo dell'`<h2>` (`$('h2').first().contents().first().text()`) senza il suffisso ` | DV Games` (case-insensitive), trim; titolo vuoto → `null`. Prodotto costruito con `buildProduct({ title, url })`.
    - Evidenza dal vivo (7/7 pagine, 2026-10-17): l'`<h2>` è `{linea o categoria} | {nome} | DV Games` (6 casi: `Giochi per tutti | Villa | DV Games`, `BANG! Game System | Black Flag! (ITA) | DV Games`) oppure solo `{nome}` (1 caso: `Combo Creatures: Piccoli e scattanti`); l'`<img>` interno porta **sempre** il nome pulito in `title`/`alt` (`Villa`, `Black Flag! (ITA)`, `Combo Creatures: Piccoli e scattanti`). Prendere il primo nodo di testo non basta: conserva la categoria (`Giochi per tutti | Villa`).
- Registrazione: `dvgiochi: dvgiochiSpec` in `HTML_SPECS`; seed `{ id: 'dvgiochi', name: 'dV Games', kind: 'html', baseUrl: 'https://www.dvgiochi.com', enabled: true }` (abilitare **dopo** la sonda dello Step 5).

- [ ] **Step 1: Test che falliscono** — in `specs-publishers.test.ts`: (a) da una fixture con due ancore prodotto (una con `27 OTTOBRE 2026` seguita da descrizione, una senza data) e un'ancora di menu, `discover` restituisce esattamente i due prodotti con `releaseDate` `'2026-10-27'` sulla prima e assente sulla seconda; (b) `extract` sulla fixture del markup reale `<h2>BANG! Game System | Black Flag! (ITA) | DV Games<br /> <img src="…/infobox.gif" alt="Black Flag! (ITA)" title="Black Flag! (ITA)" …></h2>` restituisce titolo `'Black Flag! (ITA)'`, `externalId` `'black-flag-ita'` e `priceRaw` `undefined`; (b2) `extract` su una fixture con `<h2>Combo Creatures: Piccoli e scattanti</h2>` (senza prefisso, senza img) restituisce titolo `'Combo Creatures: Piccoli e scattanti'`; (c) `extract` su una pagina senza `<h2>` restituisce `null` (non lancia).

- [ ] **Step 2: Esegui e verifica che falliscano**

Run: `npm.cmd test test/adapters/specs-publishers.test.ts`
Expected: FAIL (modulo `dvgiochi.js` inesistente).

- [ ] **Step 3: Implementa** — i mesi in italiano (`GENNAIO…DICEMBRE`) mappati a `01…12` con zeri iniziali; `discover` con cheerio su una pagina sola e pattern della data ancorato all'inizio; `extract` col titolo dall'`<img>` del primo `<h2>` (fallback: primo nodo di testo senza ` | DV Games`). Registra lo spec e aggiungi la voce al seed.

- [ ] **Step 4: Esegui e verifica che passino**

Run: `npm.cmd test; npm.cmd run typecheck`
Expected: PASS.

- [ ] **Step 5: Sonda dal vivo**

Run: `npx tsx scripts/probe-source.ts dvgiochi`
Expected: `count` ≥ 3 con titoli non vuoti e **puliti** (senza prefisso di categoria, senza ` | DV Games`); `sample[0]` dal `/prossime-uscite/`. Se `count: 0`, indaga i selettori (il titolo viene dall'`<img>` dentro il primo `<h2>` della pagina prodotto) e **non** abilitare la fonte finché non produce ≥ 3 prodotti.

- [ ] **Step 6: Aggiorna il test dei seed e committa** — aggiungi `dvgiochi` all'elenco ordinato delle abilitate (15 fonti, 11 abilitate).

Run: `npm.cmd test test/sources.test.ts`
Expected: PASS.
Commit: `git add collector/src collector/test; git commit -m "feat(collector): add dV Games source with release dates from its upcoming page"`

---

### Task 5: Horrible Guild (html + extract custom)

**Files:**
- Create: `collector/src/adapters/html/horribleguild.ts`
- Modify: `collector/src/adapters/html/specs.ts`, `collector/src/sources.seed.ts`, `collector/test/sources.test.ts`
- Test: `collector/test/adapters/specs-publishers.test.ts`

**Interfaces:**
- Consumes: `collectLinks`, `toItems`, `buildProduct`.
- Produces: `export const horribleguildSpec: HtmlSourceSpec` con
  - `discover`: `toItems(await collectLinks(fetchText, ['https://horribleguild.com/eu/shop/'], (url) => /^https:\/\/horribleguild\.com\/eu\/product\/[a-z0-9-]+\/$/.test(url)))`
  - `extract`: titolo = `.product_title` (primo) testo; prezzo = primo numero in `.summary .price .woocommerce-Price-amount bdi` (es. `24.90€` → `24.90`) con `currency` `'EUR'`; prodotto con `buildProduct`.
- Registrazione: `horribleguild: horribleguildSpec`; seed `{ id: 'horribleguild', name: 'Horrible Guild', kind: 'html', baseUrl: 'https://horribleguild.com', enabled: true }`.

- [ ] **Step 1: Test che falliscono** — in `specs-publishers.test.ts`: (a) da una fixture con link `/eu/shop/` che contiene due link `/eu/product/...`, un link `/us/product/...` e un link `/eu/cart/`, `discover` restituisce **solo** i due `/eu/product/`; (b) `extract` sulla fixture con `<h2 class="single-post-title product_title entry-title" itemprop="name">Railroad Ink Challenge</h2>` e `<span class="woocommerce-Price-amount amount"><bdi>24.90<span>€</span></bdi></span>` dentro `.summary` restituisce titolo `'Railroad Ink Challenge'`, `priceRaw` `'24.90'`, `currencyRaw` `'EUR'`; (c) `extract` su una pagina con titolo ma senza prezzo restituisce il prodotto con `priceRaw` `undefined` (Review Focus 5).

- [ ] **Step 2: Esegui e verifica che falliscano**

Run: `npm.cmd test test/adapters/specs-publishers.test.ts`
Expected: FAIL.

- [ ] **Step 3: Implementa** — spec come da interfacce, registrazione, seed.

- [ ] **Step 4: Esegui e verifica che passino**

Run: `npm.cmd test; npm.cmd run typecheck`
Expected: PASS.

- [ ] **Step 5: Sonda dal vivo** — se il prezzo esce `undefined` su prodotti che a schermo hanno un prezzo, ispeziona il markup reale e correggi il selettore del prezzo (e la fixture) prima di abilitare.

Run: `npx tsx scripts/probe-source.ts horribleguild`
Expected: `count` ≥ 3, titoli non vuoti, almeno un `priceRaw` valorizzato; `currencyRaw` `EUR`.

- [ ] **Step 6: Aggiorna il test dei seed e committa** — elenco abilitate con `horribleguild` (16 fonti, 12 abilitate).

Run: `npm.cmd test test/sources.test.ts`
Expected: PASS.
Commit: `git add collector/src collector/test; git commit -m "feat(collector): add Horrible Guild source"`

---

### Task 6: GateOnGames (html, estrazione generica)

**Files:**
- Create: `collector/src/adapters/html/gateongames.ts`
- Modify: `collector/src/adapters/html/specs.ts`, `collector/src/sources.seed.ts`, `collector/test/sources.test.ts`
- Test: `collector/test/adapters/specs-publishers.test.ts`

**Interfaces:**
- Consumes: `collectLinks`, `toItems`.
- Produces: `export const gateongamesSpec: HtmlSourceSpec` con `discover` che usa le due sorgenti
  `['https://www.gateongames.com/prodotti/localizzazioni/', 'https://www.gateongames.com/prodotti/originali-gog/']`
  e il filtro
  `/^https:\/\/www\.gateongames\.com\/[a-z0-9][a-z0-9-]*\/$/` **meno** una deny-list `NON_PRODOTTI = new Set(['chi-siamo','contattaci','privacy-policy','corti-in-gioco','english-catalogue','rulebook','speciali-mini-crimes','homepage'])` (Review Focus 1). Nessun `extract`: si usa quello generico (`og:title`).
- Registrazione: `gateongames: gateongamesSpec`; seed `{ id: 'gateongames', name: 'GateOnGames', kind: 'html', baseUrl: 'https://www.gateongames.com', enabled: true }`.

- [ ] **Step 1: Test che falliscono** — in `specs-publishers.test.ts`: (a) da una fixture con link a `/alpaca/`, `/mini-crimes/`, `/chi-siamo/`, `/privacy-policy/`, `/de/prodotti/gog-originalen/` e `/prodotti/localizzazioni/`, `discover` restituisce **solo** `['https://www.gateongames.com/alpaca/', 'https://www.gateongames.com/mini-crimes/']`; (b) deduplica un link presente in entrambe le pagine sorgente.

- [ ] **Step 2: Esegui e verifica che falliscano**

Run: `npm.cmd test test/adapters/specs-publishers.test.ts`
Expected: FAIL.

- [ ] **Step 3: Implementa** — spec con deny-list, registrazione, seed.

- [ ] **Step 4: Esegui e verifica che passino**

Run: `npm.cmd test; npm.cmd run typecheck`
Expected: PASS.

- [ ] **Step 5: Sonda dal vivo**

Run: `npx tsx scripts/probe-source.ts gateongames`
Expected: `count` ≥ 3 con titoli non vuoti e nessun titolo di pagina di menu (`Chi Siamo`, `Contattaci`, …). Se compare spazzatura, allunga la deny-list e ripeti.

- [ ] **Step 6: Aggiorna il test dei seed e committa** — elenco abilitate con `gateongames` (17 fonti, 13 abilitate).

Run: `npm.cmd test test/sources.test.ts`
Expected: PASS.
Commit: `git add collector/src collector/test; git commit -m "feat(collector): add GateOnGames source"`

---

### Task 7: Allineare la design spec

**Files:**
- Modify: `docs/superpowers/specs/2026-10-17-meeple-radar-design.md`

**Interfaces:** nessuna (solo documentazione).

- [ ] **Step 1: Aggiorna la tabella §6 "Editori / produttori"** — aggiungi 6 righe con piattaforma, accesso e stato `abilitata`: Studio Supernova (Shopify, `GET /products.json`, `www` obbligatorio), Little Rocket Games (WooCommerce, `wp-json/wc/store/v1/products`), MS Edizioni (WooCommerce, idem), dV Games (PHP custom, `/prossime-uscite/` → `/catalogo/<slug>/`, niente prezzo, data dall'elenco), Horrible Guild (WooCommerce, REST chiuso → crawl `/eu/product/<slug>/`, prezzo presente), GateOnGames (WordPress senza shop, catalogo `/prodotti/localizzazioni/` + `/prodotti/originali-gog/`, niente prezzo). Aggiorna i conteggi: **17 fonti totali, 13 abilitate, 4 disabilitate** (Cloudflare).
- [ ] **Step 2: Allinea le altre occorrenze dei contatori** — §obiettivi (`G1` 17 fonti), §7 diagramma `[17 fonti]`, §13.A.2 (`13 delle 17 fonti`), §14 criterio di "fatto" (`13 delle 17`), e la riga sull'esecuzione che cita Philibert come catalogo più grande se serve coerenza.
- [ ] **Step 3: Aggiungi la nota su Ergo Ludo Editions** — riga nella tabella §6: sito non raggiungibile al 2026-10-17 (HTTP `503` / TLS fallito), escluso dalla v1 finché non torna online.
- [ ] **Step 4: Verifica** — nessun contatore `11`/`12`/`14` residuo riferito alle fonti.

Run: `Select-String -LiteralPath docs\superpowers\specs\2026-10-17-meeple-radar-design.md -Pattern 'fonti' | ForEach-Object { "$($_.LineNumber): $($_.Line.Trim())" }`
Expected: tutte le righe parlano di 17/13.

- [ ] **Step 5: Commit** — `git add docs/superpowers/specs/2026-10-17-meeple-radar-design.md; git commit -m "docs: design spec aligned with the 17 sources"`
