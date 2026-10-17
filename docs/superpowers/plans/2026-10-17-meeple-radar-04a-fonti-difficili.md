# Meeple Radar — Piano 04a: Fonti difficili (HTML + headless)

> **Per chi esegue:** REQUIRED SUB-SKILL: usa `superpowers:subagent-driven-development` (consigliato) o `superpowers:executing-plans` per implementare questo piano task per task. I passi hanno caselle (`- [ ]`).

**Goal:** portare il collector da 5 a 14 fonti abilitate, aggiungendo un motore di scansione HTML (scoperta via sitemap o crawling + estrazione da JSON-LD/Open Graph) e un adattatore headless per l'unica fonte dietro Cloudflare.

**Architecture:** un unico motore `collectHtmlProducts` riceve uno *spec* per fonte (come trovare gli URL prodotto) e una funzione `TextFetcher` che restituisce l'HTML di una pagina — ottenuto via `fetch` (fonti HTML) o via browser headless (fonti protette). Un estrattore generico legge il JSON-LD `Product`/`Offer` della pagina prodotto, con ripiego su Open Graph; se un campo non è affidabile resta assente (mai inventato). Il registry esistente (`Record<SourceKind, Adapter>`) guida `html` e `headless` verso lo spec della fonte, mantenendo invariato `run.ts`.

**Tech Stack:** Node.js 22, TypeScript, Vitest, `zod`, `cheerio` (parsing HTML), `patchright` (Playwright stealth), GitHub Actions.

**Spec:** `appgiochi/docs/superpowers/specs/2026-10-17-meeple-radar-design.md`

**Piani prerequisiti:** 00 (setup) e 01 (collector base) — già completati.

## Global Constraints

- Collector in `E:\Ai\appgiochi\collector`; comandi eseguiti da lì. Su Windows, se la execution policy blocca gli script npm, usa `npm.cmd` / `npx.cmd`.
- Node.js **22** (usa `fetch` globale; browser solo per le fonti `headless`).
- TypeScript in modalità `strict`; dati esterni validati con `zod` (`RawProductSchema`) prima dell'uso.
- Segreti solo da variabili d'ambiente; mai nel codice. Nessuna nuova variabile richiesta da questo piano.
- **Rispetto dei `robots.txt`** e bassa frequenza: richieste sequenziali con pausa (`delayMs`), User-Agent identificabile (`MeepleRadar/0.1 (+…)`), nessuna pagina di ricerca/feed/wishlist.
- **Meglio nessun valore che uno falso** (§13.A.9): un campo estratto solo se affidabile; altrimenti assente. Prezzi esteri nella valuta originale, senza conversioni.
- Commit frequenti, uno per task. Nessun push senza richiesta esplicita.

## Ricognizione (2026-10-17) — cosa cambia rispetto al design

Il design (§6, §14/M5) presumeva che Asmodee, DungeonDice e Magic Merchant richiedessero tutti il browser headless. La verifica diretta (documentata qui sotto) riduce l'headless a **una sola** fonte. Questo piano incorpora la correzione; il Task 11 allinea il documento di design.

| Fonte | Kind attuale | Scoperta verificata | Metodo deciso |
|---|---|---|---|
| uplay | `html` | sitemap index (`sitemap-0..8.xml`); prodotti `/it/gioco-da-tavolo-*.html` con JSON-LD `Product`+`Offer` e prezzo ✅ | HTML (sitemap) |
| asmodee | `headless` → `html` | `robots.txt` 200 con `Sitemap: https://www.asmodee.it/sitemap_index.xml`; `product-sitemap.xml` con `/product/*/` ✅ | HTML (sitemap) |
| giochiuniti | `html` | sitemap index → `https://www.igiochideigrandi.it/pub/sitemap-2-1.xml`, `-2-2.xml` | HTML (sitemap) |
| ravensburger | `html` | `ravensburger.org/sitemap.xml` = 200 ma **0 byte**; `ravensburger.it` ha `sitemap.xml` e sito IT | HTML (sitemap, **cambio baseUrl**) |
| stonemaier | `wordpress` | REST `wp/v2/smg_game` = 200, post type `smg_game` ✅ (nessun `/wp/v2/product`) | WordPress (`postType` custom) |
| gamesacademy | `wordpress` → `html` | nessun post type prodotto; sito HTML | HTML (crawling) |
| philibert | `html` | sitemap = 500; home 200 con JSON-LD/OG | HTML (crawling) |
| dungeondice | `headless` → `html` | home 200 via `curl`; sitemap = 404 | HTML (crawling) |
| magicmerchant | `headless` | home = **403** (Cloudflare) | Headless (browser) |

**Non è compito di questo piano:** le notifiche FCM/digest (Piano 04b, dipendono dai Piani 02–03) e Telegram (escluso in v1, §13.A.4).

## Review Focus (casi che i test devono fissare)

1. **Pagina prodotto che risponde 200 ma senza JSON-LD né Open Graph** → l'estrattore restituisce `null`, non lancia; se *tutte* le pagine di una fonte sono nulle, la fonte finisce in `zeroed` (alert), non produce spazzatura.
2. **`offers` in forma non banale** (array, o `AggregateOffer.lowPrice`, o `availability` come URL schema.org) → prezzo, valuta e disponibilità estratti correttamente; valuta originale per gli store esteri.
3. **URL canonicale assente** nella pagina → si usa l'URL della pagina stessa, così `externalId`/`url` restano validi e l'upsert resta idempotente.
4. **Sitemap senza `lastmod` o con `lastmod` uguali** → l'ordinamento "più recenti prima" non svuota il risultato; il taglio a `maxProducts` è un tetto, non un filtro.
5. **Sito protetto che risponde 403 o renderizza una pagina vuota** → il fetch restituisce `null`, la fonte va a 0 e viene segnalata; nessun crash dell'intera run.

---

### Task 1: Client HTTP testuale + utilità sitemap

**Files:**
- Modify: `appgiochi/collector/src/adapters/http.ts`
- Create: `appgiochi/collector/src/adapters/sitemap.ts`
- Test: `appgiochi/collector/test/adapters/sitemap.test.ts`

**Interfaces:**
- Consumes: `HttpClient`, `HttpResponse`, `defaultFetch`, `USER_AGENT` (Piano 01, Task 3).
- Produces:
  - `HttpResponse` esteso con `text(): Promise<string>`
  - `type TextFetcher = (url: string) => Promise<string | null>`
  - `textFetcher(http?: HttpClient): TextFetcher` — chiama `http`, restituisce il corpo se `status === 200`, altrimenti `null`
  - `type SitemapEntry = { loc: string; lastmod?: string }`
  - `parseSitemapEntries(xml: string): SitemapEntry[]`
  - `isSitemapIndex(xml: string): boolean`
  - `newestFirst(entries: SitemapEntry[]): SitemapEntry[]`
  - `fetchSitemapEntries(url: string, fetchText: TextFetcher, opts?: { maxSitemaps?: number }): Promise<SitemapEntry[]>`

- [ ] **Step 1: Scrivi i test che falliscono**

`test/adapters/sitemap.test.ts`:
```ts
import { expect, test } from 'vitest'
import { parseSitemapEntries, isSitemapIndex, newestFirst, fetchSitemapEntries } from '../../src/adapters/sitemap.js'

const URLSET = `<?xml version="1.0"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <url><loc>https://e.com/it/gioco-da-tavolo-catan.html</loc><lastmod>2026-10-17T05:05:24+00:00</lastmod></url>
  <url><loc>https://e.com/it</loc></url>
</urlset>`
const INDEX = `<?xml version="1.0"?><sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <sitemap><loc>https://e.com/sitemap-0.xml</loc></sitemap>
  <sitemap><loc>https://e.com/sitemap-1.xml</loc></sitemap>
</sitemapindex>`

test('parses urls and optional lastmod', () => {
  const e = parseSitemapEntries(URLSET)
  expect(e).toHaveLength(2)
  expect(e[0]).toMatchObject({ loc: 'https://e.com/it/gioco-da-tavolo-catan.html', lastmod: '2026-10-17T05:05:24+00:00' })
  expect(e[1].lastmod).toBeUndefined()
})

test('detects a sitemap index', () => {
  expect(isSitemapIndex(INDEX)).toBe(true)
  expect(isSitemapIndex(URLSET)).toBe(false)
})

test('sorts newest first and keeps entries without lastmod (Review Focus 4)', () => {
  const sorted = newestFirst([
    { loc: 'a', lastmod: '2026-01-01T00:00:00Z' },
    { loc: 'b' },
    { loc: 'c', lastmod: '2026-10-17T00:00:00Z' },
  ])
  expect(sorted.map((e) => e.loc)).toEqual(['c', 'a', 'b'])
})

test('follows a sitemap index into child sitemaps', async () => {
  const pages: Record<string, string> = { 'https://e.com/sitemap.xml': INDEX, 'https://e.com/sitemap-0.xml': URLSET, 'https://e.com/sitemap-1.xml': URLSET }
  const fetchText = async (u: string) => pages[u] ?? null
  const entries = await fetchSitemapEntries('https://e.com/sitemap.xml', fetchText)
  expect(entries.length).toBe(4)
})

test('returns [] when a sitemap is unreachable', async () => {
  expect(await fetchSitemapEntries('https://e.com/sitemap.xml', async () => null)).toEqual([])
})
```

- [ ] **Step 2: Esegui e verifica il fallimento**

Run: `npx vitest run test/adapters/sitemap.test.ts`
Expected: FAIL — modulo `sitemap.js` non trovato.

- [ ] **Step 3: Modifica `src/adapters/http.ts`**

Aggiungi `text(): Promise<string>` al tipo `HttpResponse` (il `Response` nativo lo fornisce già, quindi `defaultFetch` resta invariato). Aggiungi:
```ts
export type TextFetcher = (url: string) => Promise<string | null>

export function textFetcher(http: HttpClient = defaultFetch): TextFetcher {
  return async (url) => {
    const res = await http(url, { 'User-Agent': USER_AGENT })
    return res.status === 200 ? await res.text() : null
  }
}
```

- [ ] **Step 4: Implementa `src/adapters/sitemap.ts`**

`parseSitemapEntries`: estrai ogni blocco `<url>`/`<sitemap>` con `loc` e `lastmod` opzionale (regex tollerante a spazi e newline).
`isSitemapIndex`: `/<sitemapindex[\s>]/.test(xml)`.
`newestFirst`: ordina per `lastmod` decrescente; le voci senza `lastmod` in fondo, mantenendo l'ordine relativo.
`fetchSitemapEntries`: scarica `url`; se è un index, scarica i figli (fino a `maxSitemaps`, default 20) e unisci le loro voci; altrimenti restituisci le voci del file. Qualsiasi fetch `null` viene ignorato.

- [ ] **Step 5: Esegui e verifica il passaggio**

Run: `npx vitest run test/adapters/sitemap.test.ts`
Expected: PASS (5 test).

- [ ] **Step 6: Commit**

```bash
git add appgiochi/collector/src/adapters/http.ts appgiochi/collector/src/adapters/sitemap.ts appgiochi/collector/test/adapters/sitemap.test.ts
git commit -m "feat(collector): text fetcher and sitemap helpers"
```

---

### Task 2: Estrattore generico da pagina prodotto (JSON-LD + Open Graph)

**Files:**
- Modify: `appgiochi/collector/package.json` (aggiungi `cheerio`)
- Create: `appgiochi/collector/src/adapters/html/extract.ts`
- Create: `appgiochi/collector/test/fixtures/product-ldjson.html`
- Create: `appgiochi/collector/test/fixtures/product-og.html`
- Create: `appgiochi/collector/test/fixtures/product-empty.html`
- Test: `appgiochi/collector/test/adapters/extract.test.ts`

**Interfaces:**
- Consumes: `RawProduct`, `RawProductSchema` (Piano 01, Task 2).
- Produces: `extractProduct(html: string, pageUrl: string): RawProduct | null`

- [ ] **Step 1: Aggiungi `cheerio` alle dipendenze**

Run: `npm.cmd install cheerio`
Expected: `cheerio` compare in `dependencies` di `package.json`.

- [ ] **Step 2: Prepara le fixture**

`test/fixtures/product-ldjson.html` (ridotta, forma WooCommerce/Yoast con `offers` array):
```html
<!doctype html><html><head>
<link rel="canonical" href="https://www.uplay.it/it/gioco-da-tavolo-mystery-express.html" />
<script type="application/ld+json">{"@context":"https://schema.org","@graph":[
 {"@type":"Product","name":"Mystery Express","sku":"UPL-123","image":"https://x/m.jpg","offers":[
   {"@type":"Offer","price":"34.90","priceCurrency":"EUR","availability":"https://schema.org/InStock"}]}]}</script>
</head><body></body></html>
```
`test/fixtures/product-og.html` (senza JSON-LD):
```html
<!doctype html><html><head>
<meta property="og:title" content="Catan" /><meta property="og:image" content="https://x/c.jpg" />
<meta property="product:price:amount" content="39.99" /><meta property="product:price:currency" content="EUR" />
<meta property="product:availability" content="in stock" />
</head><body></body></html>
```
`test/fixtures/product-empty.html`: `<html><head><title>Solo titolo</title></head><body></body></html>`

- [ ] **Step 3: Scrivi i test che falliscono**

```ts
import { expect, test } from 'vitest'
import { readFileSync } from 'node:fs'
import { extractProduct } from '../../src/adapters/html/extract.js'

const read = (f: string) => readFileSync(new URL(`../fixtures/${f}`, import.meta.url), 'utf8')

test('extracts a product from JSON-LD (Review Focus 2)', () => {
  const p = extractProduct(read('product-ldjson.html'), 'https://www.uplay.it/it/gioco-da-tavolo-mystery-express.html')
  expect(p).toMatchObject({
    externalId: 'UPL-123',
    title: 'Mystery Express',
    url: 'https://www.uplay.it/it/gioco-da-tavolo-mystery-express.html',
    priceRaw: '34.90',
    currencyRaw: 'EUR',
    imageUrl: 'https://x/m.jpg',
  })
  expect(p?.availabilityRaw).toContain('InStock')
})

test('falls back to Open Graph when there is no JSON-LD', () => {
  const p = extractProduct(read('product-og.html'), 'https://e.com/p')
  expect(p).toMatchObject({ title: 'Catan', priceRaw: '39.99', currencyRaw: 'EUR', url: 'https://e.com/p' })
})

test('returns null when nothing usable is present (Review Focus 1)', () => {
  expect(extractProduct(read('product-empty.html'), 'https://e.com/p')).toBeNull()
})

test('uses the page URL when there is no canonical (Review Focus 3)', () => {
  const p = extractProduct(read('product-og.html'), 'https://e.com/prodotto/catan')
  expect(p?.url).toBe('https://e.com/prodotto/catan')
})
```

- [ ] **Step 4: Esegui e verifica il fallimento**

Run: `npx vitest run test/adapters/extract.test.ts`
Expected: FAIL — modulo `extract.js` non trovato.

- [ ] **Step 5: Implementa `src/adapters/html/extract.ts`**

Usando `cheerio`: raccogli tutti gli `<script type="application/ld+json">`, fai `JSON.parse` (ignora i blocchi non parsabili) e cerca il nodo con `@type` `Product`/`ProductGroup` (anche dentro `@graph`). Da lì: `title=name`, `externalId=sku ?? productID` (in mancanza, ricava dalla URL), `imageUrl=image` (stringa o primo elemento di array), `url=offers.url ?? canonical ?? pageUrl`. Per `offers` accetta oggetto o array e `lowPrice` (AggregateOffer): `priceRaw=price ?? lowPrice`, `currencyRaw=priceCurrency`, `availabilityRaw=availability`. In mancanza di JSON-LD utile, ripiega sulle meta Open Graph (`og:title`, `og:image`, `product:price:amount`, `product:price:currency`, `product:availability`). Restituisci `null` se non ottieni un `title` non vuoto; valida il risultato con `RawProductSchema.safeParse` e restituisci `null` se non passa.

- [ ] **Step 6: Esegui e verifica il passaggio**

Run: `npx vitest run test/adapters/extract.test.ts`
Expected: PASS (4 test).

- [ ] **Step 7: Commit**

```bash
git add appgiochi/collector
git commit -m "feat(collector): generic product extractor (JSON-LD + Open Graph)"
```

---

### Task 3: Motore di scansione HTML

**Files:**
- Create: `appgiochi/collector/src/adapters/html/crawl.ts`
- Test: `appgiochi/collector/test/adapters/crawl.test.ts`

**Interfaces:**
- Consumes: `TextFetcher` (Task 1), `extractProduct` (Task 2), `RawProduct` (Piano 01).
- Produces:
  - `type HtmlSourceSpec = { discover: (fetchText: TextFetcher) => Promise<string[]> }`
  - `collectHtmlProducts(args: { spec: HtmlSourceSpec; fetchText: TextFetcher; maxProducts?: number; delayMs?: number }): Promise<RawProduct[]>`

- [ ] **Step 1: Scrivi i test che falliscono**

```ts
import { expect, test } from 'vitest'
import { collectHtmlProducts } from '../../src/adapters/html/crawl.js'

const P1 = `<script type="application/ld+json">{"@type":"Product","name":"A","sku":"1","url":"https://s/p1",
  "offers":{"price":"10.00","priceCurrency":"EUR"}}</script>`

test('discovers product URLs then extracts them', async () => {
  const pages: Record<string, string> = { 'https://s/p1': P1, 'https://s/p2': P1.replace('"1"', '"2"').replace('p1', 'p2') }
  const fetchText = async (u: string) => pages[u] ?? null
  const spec = { discover: async () => ['https://s/p1', 'https://s/p2'] }
  const out = await collectHtmlProducts({ spec, fetchText, delayMs: 0 })
  expect(out.map((p) => p.externalId).sort()).toEqual(['1', '2'])
})

test('skips URLs that fail to load and does not throw', async () => {
  const out = await collectHtmlProducts({ spec: { discover: async () => ['https://s/missing'] }, fetchText: async () => null, delayMs: 0 })
  expect(out).toEqual([])
})

test('caps the number of product pages fetched (Review Focus 4)', async () => {
  let calls = 0
  const fetchText = async () => { calls++; return P1 }
  await collectHtmlProducts({ spec: { discover: async () => ['a', 'b', 'c', 'd'].map((x) => `https://s/${x}`) }, fetchText, maxProducts: 2, delayMs: 0 })
  expect(calls).toBe(2)
})

test('returns [] when every page yields nothing usable (Review Focus 1)', async () => {
  const fetchText = async () => '<html><head><title>404</title></head></html>'
  const out = await collectHtmlProducts({ spec: { discover: async () => ['https://s/x'] }, fetchText, delayMs: 0 })
  expect(out).toEqual([])
})

test('deduplicates product URLs by external id', async () => {
  const out = await collectHtmlProducts({ spec: { discover: async () => ['https://s/p1', 'https://s/p1?x=1'] }, fetchText: async () => P1, delayMs: 0 })
  expect(out).toHaveLength(1)
})
```

- [ ] **Step 2: Esegui e verifica il fallimento**

Run: `npx vitest run test/adapters/crawl.test.ts`
Expected: FAIL — modulo `crawl.js` non trovato.

- [ ] **Step 3: Implementa `src/adapters/html/crawl.ts`**

`collectHtmlProducts`: chiama `spec.discover(fetchText)`, deduplica gli URL preservando l'ordine, applica il tetto `maxProducts` (default 400), poi per ogni URL chiama `fetchText`; se `null` salta; altrimenti `extractProduct(html, url)`; accumula i non-null saltando le duplicazioni per `externalId`. Attendi `delayMs` (default 250) tra le richieste. Non lanciare per errori di rete per singola pagina.

- [ ] **Step 4: Esegui e verifica il passaggio**

Run: `npx vitest run test/adapters/crawl.test.ts`
Expected: PASS (5 test).

- [ ] **Step 5: Commit**

```bash
git add appgiochi/collector/src/adapters/html/crawl.ts appgiochi/collector/test/adapters/crawl.test.ts
git commit -m "feat(collector): HTML crawl engine (discover + extract)"
```

---

### Task 4: Rotte `html` per fonte nel registry + script di sonda

**Files:**
- Create: `appgiochi/collector/src/adapters/html/specs.ts`
- Modify: `appgiochi/collector/src/adapters/registry.ts`
- Create: `appgiochi/collector/scripts/probe-source.ts`
- Test: `appgiochi/collector/test/adapters/registry.test.ts`

**Interfaces:**
- Consumes: `HtmlSourceSpec`, `collectHtmlProducts` (Task 3), `textFetcher` (Task 1), `buildRegistry` (Piano 01, Task 9), `Source` (Piano 01).
- Produces:
  - `HTML_SPECS: Record<string, HtmlSourceSpec>` (inizialmente vuoto; popolato dai Task 6–8)
  - `buildRegistry(http?: HttpClient)` invariato nella firma; la voce `html` ora risolve lo spec per `source.id` e **lancia** se manca
  - `scripts/probe-source.ts`: `npx tsx scripts/probe-source.ts <sourceId>` esegue dal vivo lo spec di una fonte e stampa gli elementi trovati

- [ ] **Step 1: Scrivi il test che fallisce**

```ts
import { expect, test } from 'vitest'
import { buildRegistry } from '../../src/adapters/registry.js'

const src = (id: string, kind: any) => ({ id, name: id, kind, baseUrl: 'https://e.com', enabled: true })

test('html route throws a clear error for a source without a spec', async () => {
  const r = buildRegistry(async () => ({ status: 200, json: async () => ({}), text: async () => '' }))
  await expect(r.html(src('nope', 'html'))).rejects.toThrow(/spec HTML/i)
})
```

- [ ] **Step 2: Esegui e verifica il fallimento**

Run: `npx vitest run test/adapters/registry.test.ts`
Expected: FAIL — la rotta `html` restituisce `[]` invece di lanciare.

- [ ] **Step 3: Implementa**

`src/adapters/html/specs.ts`: `export const HTML_SPECS: Record<string, HtmlSourceSpec> = {}`.
`src/adapters/registry.ts`: la voce `html` diventa
```ts
html: (source) => {
  const spec = HTML_SPECS[source.id]
  if (!spec) throw new Error(`nessuno spec HTML per la fonte "${source.id}"`)
  return collectHtmlProducts({ spec, fetchText: textFetcher(http) })
},
```
`headless` resta `async () => []` (sostituita nel Task 9).
`scripts/probe-source.ts`: prende un `id` da `process.argv[2]`, costruisce il registry reale, trova la fonte in `SOURCES`, esegue l'adattatore e stampa `{ id, count, sample }`.

- [ ] **Step 4: Esegui e verifica il passaggio**

Run: `npx vitest run test/adapters/registry.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add appgiochi/collector
git commit -m "feat(collector): per-source HTML routes and probe script"
```

---

### Task 5: Adattatore WordPress con post type personalizzato (Stonemaier)

**Files:**
- Modify: `appgiochi/collector/src/adapters/wordpress.ts`
- Modify: `appgiochi/collector/src/adapters/registry.ts`
- Test: `appgiochi/collector/test/adapters/wordpress.test.ts` (aggiungi un caso)

**Interfaces:**
- Consumes: `fetchWordPressProducts` (Piano 01, Task 5).
- Produces: `fetchWordPressProducts(baseUrl: string, http?: HttpClient, postType?: string): Promise<RawProduct[]>` (default `'product'`); il registry usa `'smg_game'` per la fonte `stonemaier`.

- [ ] **Step 1: Scrivi il test che fallisce**

Aggiungi a `test/adapters/wordpress.test.ts`:
```ts
import fixture from '../fixtures/wordpress-smg_game.json' with { type: 'json' }

test('uses a custom post type when provided (stonemaier)', async () => {
  let calledUrl = ''
  const http = async (u: string) => { calledUrl = u; return { status: 200, json: async () => fixture } }
  const out = await fetchWordPressProducts('https://stonemaiergames.com', http, 'smg_game')
  expect(calledUrl).toContain('/wp-json/wp/v2/smg_game')
  expect(out[0].externalId).toBe('35684')
})
```
Fixture `test/fixtures/wordpress-smg_game.json` (ridotta dall'endpoint reale):
```json
[{"id":35684,"date":"2026-10-07T04:48:13","link":"https://stonemaiergames.com/games/braided-skies/","title":{"rendered":"Braided Skies"},"content":{"rendered":""}}]
```

- [ ] **Step 2: Esegui e verifica il fallimento**

Run: `npx vitest run test/adapters/wordpress.test.ts`
Expected: FAIL — il terzo parametro è ignorato, l'URL chiamato resta `.../product`.

- [ ] **Step 3: Implementa**

In `wordpress.ts` aggiungi il parametro `postType = 'product'` e usalo nel percorso (`/wp-json/wp/v2/${postType}`). In `registry.ts` la voce `wordpress` diventa:
```ts
wordpress: (source) => fetchWordPressProducts(source.baseUrl, http, WP_POST_TYPES[source.id] ?? 'product'),
```
con `const WP_POST_TYPES: Record<string, string> = { stonemaier: 'smg_game' }`.

- [ ] **Step 4: Esegui tutti i test**

Run: `npm test`
Expected: PASS (nessuna regressione sul caso WordPress esistente).

- [ ] **Step 5: Verifica dal vivo**

Run: `npx tsx scripts/probe-source.ts stonemaier`
Expected: `count > 0` con titoli reali (es. "Braided Skies"). Se il campo `link` restituisce la forma `?smg_game=<slug>`, normalizza l'URL in `https://stonemaiergames.com/games/<slug>/` prima di passarlo a `RawProductSchema`.

- [ ] **Step 6: Commit**

```bash
git add appgiochi/collector
git commit -m "feat(collector): WordPress adapter with custom post type (Stonemaier)"
```

---

### Task 6: Fonti HTML da sitemap — uplay e Asmodee

**Files:**
- Create: `appgiochi/collector/src/adapters/html/uplay.ts`
- Create: `appgiochi/collector/src/adapters/html/asmodee.ts`
- Modify: `appgiochi/collector/src/adapters/html/specs.ts`
- Modify: `appgiochi/collector/src/sources.seed.ts` (kind di `asmodee` → `html`)
- Test: `appgiochi/collector/test/adapters/specs-uplay-asmodee.test.ts`

**Interfaces:**
- Consumes: `HtmlSourceSpec` (Task 3), `fetchSitemapEntries`, `newestFirst` (Task 1).
- Produces: `HTML_SPECS.uplay`, `HTML_SPECS.asmodee`; entrambe le fonti con `kind: 'html'` e `enabled: false`.

- [ ] **Step 1: Scrivi i test che falliscono**

```ts
import { expect, test } from 'vitest'
import { HTML_SPECS } from '../../src/adapters/html/specs.js'

test('uplay discovers product pages from its sitemap index', async () => {
  const pages: Record<string, string> = {
    'https://www.uplay.it/sitemap.xml': `<sitemapindex><sitemap><loc>https://www.uplay.it/sitemap-0.xml</loc></sitemap></sitemapindex>`,
    'https://www.uplay.it/sitemap-0.xml': `<urlset>
      <url><loc>https://www.uplay.it/it</loc></url>
      <url><loc>https://www.uplay.it/it/gioco-da-tavolo-mystery-express.html</loc></url>
    </urlset>`,
  }
  const urls = await HTML_SPECS.uplay.discover(async (u) => pages[u] ?? null)
  expect(urls).toEqual(['https://www.uplay.it/it/gioco-da-tavolo-mystery-express.html'])
})

test('asmodee discovers product pages from its product sitemap', async () => {
  const pages: Record<string, string> = {
    'https://www.asmodee.it/product-sitemap.xml': `<urlset>
      <url><loc>https://www.asmodee.it/chi-siamo/</loc></url>
      <url><loc>https://www.asmodee.it/product/catchables/</loc></url>
    </urlset>`,
  }
  const urls = await HTML_SPECS.asmodee.discover(async (u) => pages[u] ?? null)
  expect(urls).toEqual(['https://www.asmodee.it/product/catchables/'])
})
```

- [ ] **Step 2: Esegui e verifica il fallimento**

Run: `npx vitest run test/adapters/specs-uplay-asmodee.test.ts`
Expected: FAIL — `HTML_SPECS.uplay`/`.asmodee` indefiniti.

- [ ] **Step 3: Implementa**

`uplay.ts`: `discover` = `fetchSitemapEntries('https://www.uplay.it/sitemap.xml', fetchText)` → `newestFirst` → mappa a `loc` → filtra `/\/gioco-da-tavolo-[\w-]+\.html$/`.
`asmodee.ts`: `discover` = `fetchSitemapEntries('https://www.asmodee.it/product-sitemap.xml', fetchText)` → `newestFirst` → filtra `/\/product\/[^/]+\/$/`.
Registra i due spec in `HTML_SPECS`; in `sources.seed.ts` porta `asmodee` a `kind: 'html'`.

- [ ] **Step 4: Esegui e verifica il passaggio**

Run: `npx vitest run test/adapters/specs-uplay-asmodee.test.ts; npm test`
Expected: PASS.

- [ ] **Step 5: Verifica dal vivo**

Run: `npx tsx scripts/probe-source.ts uplay; npx tsx scripts/probe-source.ts asmodee`
Expected: `count > 0` per entrambe. Se Asmodee non espone JSON-LD (la ricognizione non l'ha confermato), verifica che l'estrattore trovi almeno le meta Open Graph; se nemmeno quelle, aggiungi un parser minimo specifico in `asmodee.ts` e annota la scelta nel commento del file e nel Task 11.

- [ ] **Step 6: Commit**

```bash
git add appgiochi/collector
git commit -m "feat(collector): uplay and Asmodee HTML sources (sitemap)"
```

---

### Task 7: Fonti HTML da sitemap — Giochi Uniti e Ravensburger

**Files:**
- Create: `appgiochi/collector/src/adapters/html/giochiuniti.ts`
- Create: `appgiochi/collector/src/adapters/html/ravensburger.ts`
- Modify: `appgiochi/collector/src/adapters/html/specs.ts`
- Modify: `appgiochi/collector/src/sources.seed.ts` (baseUrl di `ravensburger` → `https://www.ravensburger.it`)
- Test: `appgiochi/collector/test/adapters/specs-gu-ravensburger.test.ts`

**Interfaces:**
- Consumes: come Task 6.
- Produces: `HTML_SPECS.giochiuniti`, `HTML_SPECS.ravensburger`.

- [ ] **Step 1: Scrivi i test che falliscono**

```ts
import { expect, test } from 'vitest'
import { HTML_SPECS } from '../../src/adapters/html/specs.js'

test('giochiuniti follows its sitemap into the igiochideigrandi catalog', async () => {
  const pages: Record<string, string> = {
    'https://www.giochiuniti.it/sitemap.xml': `<sitemapindex><sitemap><loc>https://www.igiochideigrandi.it/pub/sitemap-2-1.xml</loc></sitemap></sitemapindex>`,
    'https://www.igiochideigrandi.it/pub/sitemap-2-1.xml': `<urlset>
      <url><loc>https://www.igiochideigrandi.it/it/prodotto/esempio/</loc></url>
    </urlset>`,
  }
  const urls = await HTML_SPECS.giochiuniti.discover(async (u) => pages[u] ?? null)
  expect(urls.length).toBe(1)
})

test('ravensburger filters product pages from its italian sitemap', async () => {
  const pages: Record<string, string> = {
    'https://www.ravensburger.it/sitemap.xml': `<urlset>
      <url><loc>https://www.ravensburger.it/it-IT/start/</loc></url>
      <url><loc>https://www.ravensburger.it/it-IT/products/junior/123456/</loc></url>
    </urlset>`,
  }
  const urls = await HTML_SPECS.ravensburger.discover(async (u) => pages[u] ?? null)
  expect(urls.length).toBe(1)
})
```

> Nota per chi implementa: i pattern URL reali di questi due cataloghi **vanno verificati dal vivo** prima di fissare i test (la ricognizione ha confermato solo l'esistenza delle sitemap, non la forma delle pagine prodotto). Aggiorna i test e le regex con la forma reale, poi prosegui.

- [ ] **Step 2: Esegui e verifica il fallimento**

Run: `npx vitest run test/adapters/specs-gu-ravensburger.test.ts`
Expected: FAIL — spec indefiniti.

- [ ] **Step 3: Implementa**

`giochiuniti.ts`: `discover` = `fetchSitemapEntries('https://www.giochiuniti.it/sitemap.xml', fetchText)` → filtra le pagine prodotto di `igiochideigrandi.it` (pattern da confermare: `/it/prodotto/` oppure `/pub/prodotto/`).
`ravensburger.ts`: `discover` = `fetchSitemapEntries('https://www.ravensburger.it/sitemap.xml', fetchText)` → filtra `/it-[A-Z]{2}\/products\//` (pattern da confermare).
Registra gli spec; cambia `baseUrl` di `ravensburger` in `https://www.ravensburger.it`.

- [ ] **Step 4: Esegui e verifica il passaggio**

Run: `npx vitest run test/adapters/specs-gu-ravensburger.test.ts; npm test`
Expected: PASS.

- [ ] **Step 5: Verifica dal vivo**

Run: `npx tsx scripts/probe-source.ts giochiuniti; npx tsx scripts/probe-source.ts ravensburger`
Expected: `count > 0` per entrambe, con titoli reali e (dove presente) prezzo in EUR. Se la forma della pagina non è coperta dall'estrattore, aggiungi un parser minimo per fonte e annotalo.

- [ ] **Step 6: Commit**

```bash
git add appgiochi/collector
git commit -m "feat(collector): Giochi Uniti and Ravensburger HTML sources"
```

---

### Task 8: Fonti HTML da crawling — Philibert, DungeonDice, Games Academy

**Files:**
- Create: `appgiochi/collector/src/adapters/html/philibert.ts`
- Create: `appgiochi/collector/src/adapters/html/dungeondice.ts`
- Create: `appgiochi/collector/src/adapters/html/gamesacademy.ts`
- Modify: `appgiochi/collector/src/adapters/html/specs.ts`
- Modify: `appgiochi/collector/src/sources.seed.ts` (`dungeondice` → `html`, `gamesacademy` → `html`)
- Test: `appgiochi/collector/test/adapters/specs-crawl.test.ts`

**Interfaces:**
- Consumes: `HtmlSourceSpec` (Task 3).
- Produces: `HTML_SPECS.philibert`, `HTML_SPECS.dungeondice`, `HTML_SPECS.gamesacademy`.

- [ ] **Step 1: Scrivi il test che fallisce** (forma generica; i selettori reali si fissano al passo 3)

```ts
import { expect, test } from 'vitest'
import { HTML_SPECS } from '../../src/adapters/html/specs.js'

test('philibert collects product links from a listing page', async () => {
  const pages: Record<string, string> = {
    'https://www.philibertnet.com/it/1-giochi-da-tavolo': `<html><body>
      <a href="/it/jeux-de-societe/1234-catan.html">Catan</a>
      <a href="/it/content/9-chi-siamo">Chi siamo</a></body></html>`,
  }
  const urls = await HTML_SPECS.philibert.discover(async (u) => pages[u] ?? null)
  expect(urls).toEqual(['https://www.philibertnet.com/it/jeux-de-societe/1234-catan.html'])
})
```

> Nota: i generatori di scope URL per Philibert, DungeonDice e Games Academy **vanno ricavati dal vivo** (sitemap assente/500/404). Usa le pagine di categoria/novità come semi e seleziona i link prodotto; verifica `robots.txt` e rispetta i percorsi disabilitati.

- [ ] **Step 2: Esegui e verifica il fallimento**

Run: `npx vitest run test/adapters/specs-crawl.test.ts`
Expected: FAIL — `HTML_SPECS.philibert` indefinito.

- [ ] **Step 3: Implementa**

Per ciascuna: `discover` scarica una o più pagine di categoria/novità (semi) e restituisce i link ai prodotti (cheerio, con prefisso `baseUrl` per gli href relativi), deduplicati, ordinati dal più recente. Registra gli spec; in `sources.seed.ts` porta `dungeondice` e `gamesacademy` a `kind: 'html'`.

- [ ] **Step 4: Esegui e verifica il passaggio**

Run: `npx vitest run test/adapters/specs-crawl.test.ts; npm test`
Expected: PASS.

- [ ] **Step 5: Verifica dal vivo**

Run: `npx tsx scripts/probe-source.ts philibert; npx tsx scripts/probe-source.ts dungeondice; npx tsx scripts/probe-source.ts gamesacademy`
Expected: `count > 0` per tutte e tre. Se una risponde con `403` o richiede JavaScript, **non** forzarla qui: lasciala `enabled: false`, spostala a `headless` nel Task 9 e annotalo nel Task 11.

- [ ] **Step 6: Commit**

```bash
git add appgiochi/collector
git commit -m "feat(collector): Philibert, DungeonDice and Games Academy HTML sources"
```

---

### Task 9: Renderer headless (Playwright stealth) + Magic Merchant

**Files:**
- Modify: `appgiochi/collector/package.json` (aggiungi `patchright`)
- Create: `appgiochi/collector/src/adapters/headless/render.ts`
- Create: `appgiochi/collector/src/adapters/headless/specs.ts`
- Create: `appgiochi/collector/src/adapters/headless/magicmerchant.ts`
- Modify: `appgiochi/collector/src/adapters/registry.ts` (rotta `headless`)
- Test: `appgiochi/collector/test/adapters/headless.test.ts`

**Interfaces:**
- Consumes: `TextFetcher` (Task 1), `HtmlSourceSpec`, `collectHtmlProducts` (Task 3).
- Produces:
  - `createPlaywrightRenderer(opts?: { headless?: boolean }): Promise<{ fetchText: TextFetcher; close(): Promise<void> }>` — apre Chromium con `patchright`, restituisce l'HTML della pagina o `null` su errore/`403`
  - `getSharedRenderer(): Promise<TextFetcher>` — singleton pigro per la durata della run
  - `HEADLESS_SPECS.magicmerchant`; la rotta `headless` del registry risolve lo spec per `source.id` (come `html`) usando il renderer condiviso

- [ ] **Step 1: Aggiungi `patchright`**

Run: `npm.cmd install patchright; npx.cmd patchright install chromium`
Expected: `patchright` in `dependencies`; Chromium scaricato in locale.

- [ ] **Step 2: Scrivi il test che fallisce** (senza browser: si inietta un renderer finto)

```ts
import { expect, test } from 'vitest'
import { collectHtmlProducts } from '../../src/adapters/html/crawl.js'
import { HEADLESS_SPECS } from '../../src/adapters/headless/specs.js'

test('magic merchant extracts products from a rendered listing (Review Focus 5)', async () => {
  const listing = `<html><body><a href="/prodotto/catan">Catan</a></body></html>`
  const product = `<script type="application/ld+json">{"@type":"Product","name":"Catan","sku":"9","offers":{"price":"29.90","priceCurrency":"EUR"}}</script>`
  const render = async (u: string) => (u.endsWith('/prodotto/catan') ? product : listing)
  const out = await collectHtmlProducts({ spec: HEADLESS_SPECS.magicmerchant, fetchText: render, delayMs: 0 })
  expect(out[0]).toMatchObject({ title: 'Catan', priceRaw: '29.90' })
})
```

- [ ] **Step 3: Esegui e verifica il fallimento**

Run: `npx vitest run test/adapters/headless.test.ts`
Expected: FAIL — modulo `headless/specs.js` non trovato.

- [ ] **Step 4: Implementa**

`render.ts`: import dinamico (`await import('patchright')`) così gli altri lavori non richiedono browser; avvia Chromium headless con User-Agent reale, `newPage`, `goto(url, { waitUntil: 'domcontentloaded', timeout })`; se la risposta è `>= 400` o il corpo è vuoto → `null`; chiude la pagina; `close()` chiude browser e contesto. `getSharedRenderer` memorizza il `TextFetcher` in una variabile di modulo (browser chiuso dal termine del processo).
`magicmerchant.ts`: `discover` rende le pagine di categoria/novità e restituisce i link prodotto (pattern dal vivo).
`registry.ts`: la rotta `headless` = spec per `source.id` (lancia se manca) + `collectHtmlProducts({ spec, fetchText: await getSharedRenderer() })`. Se nel Task 8 è emersa un'altra fonte bisognosa di browser, spostala qui (kind → `headless`, spec in `HEADLESS_SPECS`).

- [ ] **Step 5: Esegui e verifica il passaggio**

Run: `npx vitest run test/adapters/headless.test.ts; npm test`
Expected: PASS (il test headless usa il renderer finto, nessun browser).

- [ ] **Step 6: Verifica dal vivo**

Run: `npx tsx scripts/probe-source.ts magicmerchant`
Expected: `count > 0`. Se Cloudflare blocca comunque l'IP, lascia `magicmerchant` con `enabled: false` e annota l'esito nel Task 11 (rete di sicurezza: esecuzione da IP residenziale, fuori ambito di questo piano).

- [ ] **Step 7: Commit**

```bash
git add appgiochi/collector
git commit -m "feat(collector): headless Playwright renderer and Magic Merchant source"
```

---

### Task 10: Abilitazione delle fonti verificate + CI

**Files:**
- Modify: `appgiochi/collector/src/sources.seed.ts` (imposta `enabled: true` solo alle fonti con `probe` a `count > 0`)
- Modify: `appgiochi/collector/test/sources.test.ts` (aggiorna la lista delle abilitate)
- Modify: `.github/workflows/collect.yml` (installazione del browser per l'headless)
- Modify: `appgiochi/collector/scripts/dry-run.ts` (se serve includere le nuove fonti abilitate)

**Interfaces:**
- Consumes: tutti gli adattatori dei Task 5–9; `SOURCES` (Piano 01).
- Produces: `SOURCES` con 14 fonti, di cui abilitate quelle verificate dal vivo; workflow che installa Chromium.

- [ ] **Step 1: Aggiorna il test delle fonti abilitate**

In `test/sources.test.ts` aggiorna l'atteso `enabled` alla lista reale risultante dalle verifiche dal vivo (esempio, da confermare con i probe):
```ts
test('only sources verified live are enabled', () => {
  const enabled = SOURCES.filter((s) => s.enabled).map((s) => s.id).sort()
  expect(enabled).toEqual(['asmodee', 'boardhouse', 'cranio', 'dungeondice', 'gamesacademy', 'ghenos', 'giochiuniti', 'kosmos', 'magicmerchant', 'pendragon', 'philibert', 'ravensburger', 'stonemaier', 'uplay'])
})
```
Se una fonte è rimasta `enabled: false` (es. Magic Merchant bloccata), **togliela** dall'elenco atteso.

- [ ] **Step 2: Esegui e verifica il fallimento**

Run: `npx vitest run test/sources.test.ts`
Expected: FAIL — la lista attuale contiene solo le 5 fonti del Piano 01.

- [ ] **Step 3: Imposta `enabled: true` nelle fonti verificate**

In `sources.seed.ts` abilita solo le fonti che al Task 5–9 hanno dato `count > 0`.

- [ ] **Step 4: Esegui tutti i test e il typecheck**

Run: `npm test; npm run typecheck`
Expected: tutti PASS, nessun errore di tipo.

- [ ] **Step 5: Aggiorna il workflow**

In `.github/workflows/collect.yml`, dopo `npm ci`, aggiungi un passo per il browser (serve solo alle fonti headless):
```yaml
      - name: Install browser (headless sources)
        run: npx patchright install --with-deps chromium
```
Mantieni `node-version: 22` e i segreti esistenti.

- [ ] **Step 6: Verifica end-to-end in CI**

Su GitHub: **Actions → collect → Run workflow** (commit nuovo).
Expected: `run_log.ok = true`, `perSource` con tutte le fonti abilitate a `> 0`, `zeroed: []`. Se una fonte abilitata resta a 0, disabilitala e ripeti.

- [ ] **Step 7: Commit**

```bash
git add appgiochi
git commit -m "feat(collector): enable verified sources and install headless browser in CI"
```

---

### Task 11: Allineare il documento di design alla ricognizione

**Files:**
- Modify: `appgiochi/docs/superpowers/specs/2026-10-17-meeple-radar-design.md` (§6, §7, §12, §13.A, §14)

**Interfaces:**
- Consumes: esiti dei Task 5–10.
- Produces: design coerente con l'implementazione.

- [ ] **Step 1: Aggiorna la matrice delle fonti (§6)** con i metodi effettivi (Asmodee/DungeonDice = HTML; stub di browser solo Magic Merchant se ancora necessario) e il cambio `ravensburger.org` → `ravensburger.it`.
- [ ] **Step 2: Aggiorna §7 e §12** (l'headless non è più il metodo predefinito per tutte le fonti protette; resta la mitigazione se l'IP è bloccato).
- [ ] **Step 3: Aggiorna §13.A.2 e §14** (criterio "14/14 fonti" e M5 riflettono lo stato reale).
- [ ] **Step 4: Commit**

```bash
git add appgiochi/docs
git commit -m "docs(collector): align design spec with source recon findings"
```

---

## Self-review (eseguito)

- **Copertura spec:** §6 (tutte le 14 fonti — Task 5–9; criterio v1 14/14 — Task 10); §11 monitoraggio "0 elementi = segnalato" (Task 3/4, invariato in `run.ts`); §13.A.9 date/prezzi solo se affidabili (Task 2); §13.A.7 valuta originale (Task 2, nessuna conversione); §12 rischi parser fragili/anti-bot (Task 9 + fallback documentato). Notifiche FCM = Piano 04b (fuori ambito).
- **Tipi coerenti:** `TextFetcher`, `SitemapEntry`, `HtmlSourceSpec`, `extractProduct`, `collectHtmlProducts` definiti una volta (Task 1–3) e riusati; `fetchWordPressProducts(…, postType?)` (Task 5) usato nel registry; firma di `buildRegistry` invariata, quindi `run.ts` e i suoi test non cambiano.
- **Review Focus coperto:** (1) Task 2 Step 3 + Task 3 Step 1; (2) Task 2 Step 3; (3) Task 2 Step 3; (4) Task 1 Step 1 + Task 3 Step 1; (5) Task 9 Step 2.
- **Proporzione:** firme, contratti e asserzioni; i corpi dei parser per fonte sono lasciati all'implementatore perché dipendono dal markup reale, verificato dal vivo in ogni task.

## Questioni aperte (per il revisore umano)

- **Selettori per fonte:** i pattern URL/selettori reali di Giochi Uniti, Ravensburger, Philibert, DungeonDice, Games Academy e Magic Merchant si fissano durante l'esecuzione con la sonda `scripts/probe-source.ts` (la ricognizione ha confermato sitemap/raggiungibilità, non il markup). Ogni task include un passo di verifica dal vivo.
- **Fonti che resistono:** se dopo Playwright una fonte resta bloccata, resta `enabled: false` e viene segnalata; il lavoro "esecuzione da IP residenziale" è fuori ambito.
- **Frequenza/peso:** il tetto `maxProducts` (400) e la pausa (250 ms) sono valori di partenza per stare nei 2.000 min/mese; da tarare in base ai tempi reali della run.
