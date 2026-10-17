# Meeple Radar — Piano 01: Collector (fondazione)

> **Per chi esegue:** REQUIRED SUB-SKILL: usa `superpowers:subagent-driven-development` (consigliato) o `superpowers:executing-plans` per implementare questo piano task per task. I passi hanno caselle (`- [ ]`).

**Goal:** costruire il collector Node.js/TypeScript che raccoglie, normalizza e salva su Supabase le uscite di un primo gruppo di fonti, eseguito a orari da GitHub Actions.

**Architecture:** Adattatori isolati (uno per piattaforma) trasformano le risposte delle fonti in `RawProduct`; un normalizzatore produce `Listing` (con data di uscita solo se affidabile); uno store idempotente scrive su Supabase; un orchestratore coordina il tutto e registra un `run_log`. Tutto testabile iniettando un client HTTP finto e uno store finto.

**Tech Stack:** Node.js 20, TypeScript, Vitest, `@supabase/supabase-js`, `zod`, GitHub Actions.

**Spec:** `appgiochi/docs/superpowers/specs/2026-10-17-meeple-radar-design.md`

**Piano 00 (prerequisito):** `appgiochi/docs/superpowers/plans/2026-10-17-meeple-radar-00-setup-e-roadmap.md`

## Global Constraints

- Collector in `E:\Ai\appgiochi\collector`; comandi eseguiti da lì.
- Node.js 20 (usa `fetch` globale; nessuna dipendenza HTTP extra).
- TypeScript in modalità `strict`.
- Dati esterni validati con `zod` prima dell'uso (le fonti possono cambiare).
- Segreti solo da variabili d'ambiente (`SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`); mai nel codice.
- Rispetto `robots.txt`; aggiungere un `User-Agent` identificabile (`MeepleRadar/0.1 (+contatto)`).
- Nessun commit/push senza richiesta esplicita (`AGENTS.md`).

## Review Focus (casi che i test devono fissare)

1. **Fonte che risponde 200 ma vuota o con HTML inatteso** → l'adattatore deve restituire `[]` e non lanciare, così `run_log` segnala l'anomalia.
2. **Prezzo in unità minori** (Store API WooCommerce: `price` intero + `currency_minor_unit`) → conversione corretta.
3. **Data di uscita assente/non affidabile** → `releaseDate` resta `null` (mai inventata).
4. **Secondo giro di raccolta** → nessun duplicato: l'upsert è idempotente su `(source_id, external_id)`.
5. **Una fonte in errore non blocca le altre** → l'orchestratore isola i fallimenti per fonte.

---

### Task 1: Scaffold del progetto collector

**Files:**
- Create: `appgiochi/collector/package.json`, `tsconfig.json`, `vitest.config.ts`, `.gitignore`, `.env.example`
- Create: `appgiochi/collector/src/index.ts`
- Create: `appgiochi/collector/test/smoke.test.ts`

**Interfaces:**
- Consumes: niente.
- Produces: `VERSION: string` da `src/index.ts`; comandi `npm test`, `npm run typecheck`, `npm run collect`.

- [ ] **Step 1: Crea `package.json`**

```json
{
  "name": "meeple-radar-collector",
  "private": true,
  "type": "module",
  "engines": { "node": ">=20" },
  "scripts": {
    "test": "vitest run",
    "typecheck": "tsc --noEmit",
    "collect": "tsx src/run.ts"
  },
  "dependencies": {
    "@supabase/supabase-js": "^2.45.0",
    "zod": "^3.23.8"
  },
  "devDependencies": {
    "@types/node": "^20.14.0",
    "tsx": "^4.16.0",
    "typescript": "^5.5.0",
    "vitest": "^2.0.0"
  }
}
```

- [ ] **Step 2: Crea `tsconfig.json`, `vitest.config.ts`, `.gitignore`, `.env.example`**

`tsconfig.json`: `target ES2022`, `module NodeNext`, `moduleResolution NodeNext`, `strict true`, `outDir dist`, `include ["src","test"]`.
`vitest.config.ts`: `export default { test: { environment: 'node' } }`.
`.gitignore`: `node_modules`, `dist`, `.env`.
`.env.example`: `SUPABASE_URL=`, `SUPABASE_SERVICE_ROLE_KEY=`.

- [ ] **Step 3: Scrivi il test di fumo che fallisce**

`test/smoke.test.ts`:
```ts
import { expect, test } from 'vitest'
import { VERSION } from '../src/index.js'

test('exports a version string', () => {
  expect(VERSION).toBe('0.1.0')
})
```

- [ ] **Step 4: Esegui e verifica il fallimento**

Run: `npm install` poi `npx vitest run test/smoke.test.ts`
Expected: FAIL — `VERSION` non esiste / modulo non trovato.

- [ ] **Step 5: Implementa `VERSION`**

`src/index.ts`: `export const VERSION = '0.1.0'`.

- [ ] **Step 6: Esegui e verifica il passaggio**

Run: `npx vitest run test/smoke.test.ts`
Expected: PASS (1 test).

- [ ] **Step 7: Commit**

```bash
git add appgiochi/collector
git commit -m "chore(collector): scaffold Node/TS project with Vitest"
```

---

### Task 2: Tipi di dominio e validazione con zod

**Files:**
- Create: `appgiochi/collector/src/types.ts`
- Test: `appgiochi/collector/test/types.test.ts`

**Interfaces:**
- Consumes: niente.
- Produces:
  - `SOURCE_KINDS = ['shopify','woocommerce','wordpress','html','headless'] as const`; `type SourceKind`
  - `type RawProduct = { externalId: string; title: string; url: string; priceRaw?: string; currencyRaw?: string; minorUnitRaw?: number; availabilityRaw?: string; descriptionRaw?: string; imageUrl?: string; publishedAtRaw?: string }`
  - `type Listing = { sourceId: string; externalId: string; title: string; url: string; price: number | null; currency: string | null; availability: string | null; releaseDate: string | null; imageUrl: string | null }`
  - `type Source = { id: string; name: string; kind: SourceKind; baseUrl: string; enabled: boolean }`
  - `RawProductSchema: z.ZodType<RawProduct>` (con `externalId`, `title`, `url` obbligatori).

- [ ] **Step 1: Scrivi il test che fallisce**

```ts
import { expect, test } from 'vitest'
import { RawProductSchema } from '../src/types.js'

test('accepts a minimal raw product', () => {
  const parsed = RawProductSchema.parse({ externalId: '1', title: 'X', url: 'https://e.com/x' })
  expect(parsed.externalId).toBe('1')
})

test('rejects a raw product without a url', () => {
  expect(() => RawProductSchema.parse({ externalId: '1', title: 'X' })).toThrow()
})
```

- [ ] **Step 2: Esegui e verifica il fallimento**

Run: `npx vitest run test/types.test.ts`
Expected: FAIL — `RawProductSchema` non definito.

- [ ] **Step 3: Implementa `src/types.ts`**

Definisci i tipi e gli schemi zod sopra; `RawProductSchema` con campo `url` validato come `z.string().url()`.

- [ ] **Step 4: Esegui e verifica il passaggio**

Run: `npx vitest run test/types.test.ts`
Expected: PASS (2 test).

- [ ] **Step 5: Commit**

```bash
git add appgiochi/collector/src/types.ts appgiochi/collector/test/types.test.ts
git commit -m "feat(collector): domain types and zod schemas"
```

---

### Task 3: Adattatore Shopify

**Files:**
- Create: `appgiochi/collector/src/adapters/http.ts` (tipo `HttpClient` + default `globalFetch`)
- Create: `appgiochi/collector/src/adapters/shopify.ts`
- Create: `appgiochi/collector/test/fixtures/shopify-products.json`
- Test: `appgiochi/collector/test/adapters/shopify.test.ts`

**Interfaces:**
- Consumes: `RawProduct` (Task 2).
- Produces:
  - `type HttpResponse = { status: number; json(): Promise<unknown> }`
  - `type HttpClient = (url: string, headers?: Record<string,string>) => Promise<HttpResponse>`
  - `fetchShopifyProducts(baseUrl: string, http?: HttpClient): Promise<RawProduct[]>`

- [ ] **Step 1: Prepara la fixture**

`test/fixtures/shopify-products.json` (estratto reale, ridotto):
```json
{ "products": [ {
  "id": 16087837376843,
  "title": "Ancient Empires - Deluxe Pack",
  "handle": "ancient-empires-deluxe-pack",
  "body_html": "<p>In uscita il 15/03/2026</p>",
  "published_at": "2026-01-10T09:00:00-05:00",
  "images": [ { "src": "https://cdn.shopify.com/x.jpg" } ],
  "variants": [ { "price": "89.90", "available": true } ]
} ] }
```

- [ ] **Step 2: Scrivi il test che fallisce**

```ts
import { expect, test } from 'vitest'
import { fetchShopifyProducts } from '../../src/adapters/shopify.js'
import fixture from '../fixtures/shopify-products.json'

const http = async () => ({ status: 200, json: async () => fixture })

test('maps shopify products to RawProduct', async () => {
  const out = await fetchShopifyProducts('https://shop.example', http)
  expect(out).toHaveLength(1)
  expect(out[0]).toMatchObject({
    externalId: '16087837376843',
    title: 'Ancient Empires - Deluxe Pack',
    url: 'https://shop.example/products/ancient-empires-deluxe-pack',
    priceRaw: '89.90',
    currencyRaw: 'EUR',
    imageUrl: 'https://cdn.shopify.com/x.jpg',
  })
})
```

- [ ] **Step 3: Esegui e verifica il fallimento**

Run: `npx vitest run test/adapters/shopify.test.ts`
Expected: FAIL — modulo non trovato.

- [ ] **Step 4: Implementa**

`src/adapters/http.ts`: esporta `type HttpClient`, `HttpResponse`, e `const defaultFetch: HttpClient = (url, headers) => fetch(url, { headers })`.
`src/adapters/shopify.ts`: pagina `GET {baseUrl}/products.json?limit=250&page=N` finché la risposta contiene prodotti; mappa ogni prodotto a `RawProduct` con `externalId = String(id)`, `title`, `url = {baseUrl}/products/{handle}`, `priceRaw = variants[0].price`, `currencyRaw` da configurazione (`EUR` di default per gli store IT), `availabilityRaw` da `variants[0].available`, `descriptionRaw = body_html`, `imageUrl = images[0].src`, `publishedAtRaw = published_at`. Valida ogni item con `RawProductSchema`; scarta gli invalidi.

- [ ] **Step 5: Esegui e verifica il passaggio**

Run: `npx vitest run test/adapters/shopify.test.ts`
Expected: PASS.

- [ ] **Step 6: Aggiungi il test per la fonte vuota (Review Focus 1)**

```ts
test('returns [] on an unexpected payload', async () => {
  const bad = async () => ({ status: 200, json: async () => ({ nope: true }) })
  expect(await fetchShopifyProducts('https://shop.example', bad)).toEqual([])
})
```

- [ ] **Step 7: Esegui e verifica il passaggio**

Run: `npx vitest run test/adapters/shopify.test.ts`
Expected: PASS (2 test).

- [ ] **Step 8: Commit**

```bash
git add appgiochi/collector
git commit -m "feat(collector): Shopify adapter"
```

---

### Task 4: Adattatore WooCommerce (Store API)

**Files:**
- Create: `appgiochi/collector/src/adapters/woocommerce.ts`
- Create: `appgiochi/collector/test/fixtures/woocommerce-products.json`
- Test: `appgiochi/collector/test/adapters/woocommerce.test.ts`

**Interfaces:**
- Consumes: `RawProduct` (Task 2), `HttpClient` (Task 3).
- Produces: `fetchWooCommerceProducts(baseUrl: string, currency: string, http?: HttpClient): Promise<RawProduct[]>`

- [ ] **Step 1: Prepara la fixture** (estratto Store API, ridotto)

```json
[ { "id": 5854, "name": "Shijin", "permalink": "https://pendragongamestudio.com/product/shijin/",
    "prices": { "price": "1299", "currency_code": "EUR", "currency_minor_unit": 2 },
    "is_in_stock": true, "images": [ { "src": "https://x/p.jpg" } ],
    "description": "<p>Disponibile dal 20/11/2026</p>" } ]
```

- [ ] **Step 2: Scrivi il test che fallisce**

```ts
import { expect, test } from 'vitest'
import { fetchWooCommerceProducts } from '../../src/adapters/woocommerce.js'
import fixture from '../fixtures/woocommerce-products.json'

const http = async () => ({ status: 200, json: async () => fixture })

test('maps woocommerce store products to RawProduct', async () => {
  const out = await fetchWooCommerceProducts('https://pendragongamestudio.com', 'EUR', http)
  expect(out[0]).toMatchObject({
    externalId: '5854',
    title: 'Shijin',
    url: 'https://pendragongamestudio.com/product/shijin/',
    priceRaw: '1299',
    minorUnitRaw: 2,
    currencyRaw: 'EUR',
  })
})
```

- [ ] **Step 3: Esegui e verifica il fallimento**

Run: `npx vitest run test/adapters/woocommerce.test.ts`
Expected: FAIL — modulo non trovato.

- [ ] **Step 4: Implementa**

Pagina `GET {baseUrl}/wp-json/wc/store/v1/products?per_page=100&page=N`; mappa `externalId=String(id)`, `title=name`, `url=permalink`, `priceRaw=prices.price`, `minorUnitRaw=prices.currency_minor_unit`, `currencyRaw=prices.currency_code ?? currency`, `availabilityRaw` da `is_in_stock`, `descriptionRaw=description`, `imageUrl=images[0].src`. Valida con `RawProductSchema`.

- [ ] **Step 5: Esegui e verifica il passaggio**

Run: `npx vitest run test/adapters/woocommerce.test.ts`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add appgiochi/collector
git commit -m "feat(collector): WooCommerce Store API adapter"
```

---

### Task 5: Adattatore WordPress (post type `product`)

**Files:**
- Create: `appgiochi/collector/src/adapters/wordpress.ts`
- Create: `appgiochi/collector/test/fixtures/wordpress-products.json`
- Test: `appgiochi/collector/test/adapters/wordpress.test.ts`

**Interfaces:**
- Consumes: `RawProduct` (Task 2), `HttpClient` (Task 3).
- Produces: `fetchWordPressProducts(baseUrl: string, http?: HttpClient): Promise<RawProduct[]>`

> Nota: `wp/v2/product` non espone il prezzo; `releaseDate` resta `null` finché il normalizzatore non trova una data affidabile nel testo (Task 6). Coerente con §13.A.9.

- [ ] **Step 1: Prepara la fixture** (estratto WP core REST, ridotto)

```json
[ { "id": 435844, "link": "https://www.ghenosgames.com/prodotto/x/",
    "title": { "rendered": "Nuovo Gioco" }, "content": { "rendered": "<p>In uscita: 2026-04</p>" },
    "date": "2026-10-16T14:52:51" } ]
```

- [ ] **Step 2: Scrivi il test che fallisce**

```ts
import { expect, test } from 'vitest'
import { fetchWordPressProducts } from '../../src/adapters/wordpress.js'
import fixture from '../fixtures/wordpress-products.json'

const http = async () => ({ status: 200, json: async () => fixture })

test('maps wp product post type to RawProduct without price', async () => {
  const out = await fetchWordPressProducts('https://www.ghenosgames.com', http)
  expect(out[0]).toMatchObject({
    externalId: '435844',
    title: 'Nuovo Gioco',
    url: 'https://www.ghenosgames.com/prodotto/x/',
    descriptionRaw: '<p>In uscita: 2026-04</p>',
  })
  expect(out[0].priceRaw).toBeUndefined()
})
```

- [ ] **Step 3: Esegui e verifica il fallimento**

Run: `npx vitest run test/adapters/wordpress.test.ts`
Expected: FAIL — modulo non trovato.

- [ ] **Step 4: Implementa**

Pagina `GET {baseUrl}/wp-json/wp/v2/product?per_page=100&page=N`; mappa `externalId=String(id)`, `title=title.rendered` (decodifica entità HTML), `url=link`, `descriptionRaw=content.rendered`. Nessun prezzo.

- [ ] **Step 5: Esegui e verifica il passaggio**

Run: `npx vitest run test/adapters/wordpress.test.ts`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add appgiochi/collector
git commit -m "feat(collector): WordPress product adapter"
```

---

### Task 6: Normalizzazione (prezzo in euro + data di uscita)

**Files:**
- Create: `appgiochi/collector/src/normalize.ts`
- Test: `appgiochi/collector/test/normalize.test.ts`

**Interfaces:**
- Consumes: `RawProduct`, `Listing` (Task 2).
- Produces:
  - `toPrice(priceRaw?: string, minorUnit?: number): number | null`
  - `extractReleaseDate(text?: string): string | null` (restituisce `YYYY-MM-DD` o `null`)
  - `normalizeListing(sourceId: string, raw: RawProduct): Listing`

- [ ] **Step 1: Scrivi i test che falliscono**

```ts
import { expect, test } from 'vitest'
import { toPrice, extractReleaseDate, normalizeListing } from '../src/normalize.js'

test('converts minor units to a decimal price', () => {
  expect(toPrice('1299', 2)).toBe(12.99)
  expect(toPrice('89.90')).toBe(89.9)
  expect(toPrice(undefined)).toBeNull()
})

test('extracts an Italian release date', () => {
  expect(extractReleaseDate('<p>In uscita il 15/03/2026</p>')).toBe('2026-03-15')
  expect(extractReleaseDate('<p>In uscita: 2026-04</p>')).toBe('2026-04-01')
  expect(extractReleaseDate('<p>Nessuna data</p>')).toBeNull()
})

test('normalizes a raw product, never inventing a date', () => {
  const l = normalizeListing('cranio', {
    externalId: '1', title: 'X', url: 'https://e.com/x', descriptionRaw: '<p>Nessuna data</p>',
  })
  expect(l).toMatchObject({ sourceId: 'cranio', price: null, currency: null, releaseDate: null })
})
```

- [ ] **Step 2: Esegui e verifica il fallimento**

Run: `npx vitest run test/normalize.test.ts`
Expected: FAIL — modulo non trovato.

- [ ] **Step 3: Implementa `src/normalize.ts`**

`toPrice`: se `minorUnit` è definito, `Number(priceRaw)/10**minorUnit`; altrimenti `Number(priceRaw)`; `NaN`→`null`.
`extractReleaseDate`: cerca pattern `dd/mm/yyyy` → ISO; `yyyy-mm` → primo giorno del mese; altrimenti `null`. Non dedurre null'altro.
`normalizeListing`: compone `Listing` con `price`, `currency`, `releaseDate` (da `descriptionRaw`), `imageUrl`; `availability` pass-through.

- [ ] **Step 4: Esegui e verifica il passaggio**

Run: `npx vitest run test/normalize.test.ts`
Expected: PASS (3 test).

- [ ] **Step 5: Commit**

```bash
git add appgiochi/collector/src/normalize.ts appgiochi/collector/test/normalize.test.ts
git commit -m "feat(collector): normalize listings (price, release date)"
```

---

### Task 7: Store Supabase idempotente

**Files:**
- Create: `appgiochi/collector/src/db.ts`
- Test: `appgiochi/collector/test/db.test.ts`

**Interfaces:**
- Consumes: `Listing` (Task 2).
- Produces:
  - `interface ListingStore { upsertListings(rows: Listing[]): Promise<void>; markSourceScanned(sourceId: string): Promise<void>; logRun(ok: boolean, details: unknown): Promise<void> }`
  - `createSupabaseStore(url: string, serviceKey: string): ListingStore`

- [ ] **Step 1: Scrivi il test che fallisce** (usa un finto client Supabase per non toccare la rete)

```ts
import { expect, test, vi } from 'vitest'
import { createSupabaseStore } from '../src/db.js'

test('upserts on the (source_id, external_id) conflict target', async () => {
  const upsert = vi.fn().mockResolvedValue({ error: null })
  const from = vi.fn(() => ({ upsert }))
  const store = createSupabaseStore({ from } as any, () => '2026-10-17T00:00:00Z')
  await store.upsertListings([
    { sourceId: 'cranio', externalId: '1', title: 'X', url: 'https://e.com/x',
      price: 1, currency: 'EUR', availability: null, releaseDate: null, imageUrl: null },
  ])
  expect(from).toHaveBeenCalledWith('listing')
  expect(upsert.mock.calls[0][1]).toMatchObject({ onConflict: 'source_id,external_id' })
})
```

- [ ] **Step 2: Esegui e verifica il fallimento**

Run: `npx vitest run test/db.test.ts`
Expected: FAIL — modulo non trovato.

- [ ] **Step 3: Implementa `src/db.ts`**

- `createSupabaseStore(client, now = () => new Date().toISOString())` accetta un client (reale o finto).
- `upsertListings` mappa `Listing`→righe snake_case e chiama `client.from('listing').upsert(rows, { onConflict: 'source_id,external_id' })`, lanciando su `error`.
- `markSourceScanned` aggiorna `source.last_scan_at`.
- `logRun` inserisce in `run_log`.
- Esporta anche `createSupabaseClient(url, key)` che costruisce il client reale e lo passa a `createSupabaseStore`.

- [ ] **Step 4: Esegui e verifica il passaggio**

Run: `npx vitest run test/db.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add appgiochi/collector/src/db.ts appgiochi/collector/test/db.test.ts
git commit -m "feat(collector): idempotent Supabase listing store"
```

---

### Task 8: Orchestratore e report di esecuzione

**Files:**
- Create: `appgiochi/collector/src/run.ts`
- Test: `appgiochi/collector/test/run.test.ts`

**Interfaces:**
- Consumes: `Source` (Task 2), `RawProduct`, `normalizeListing` (Task 6), `ListingStore` (Task 7).
- Produces:
  - `type Adapter = (source: Source) => Promise<RawProduct[]>`
  - `type RunReport = { ok: boolean; perSource: Record<string, number>; failures: Record<string, string> }`
  - `runCollector(args: { sources: Source[]; adapters: Record<SourceKind, Adapter>; store: ListingStore }): Promise<RunReport>`

- [ ] **Step 1: Scrivi il test che fallisce**

```ts
import { expect, test, vi } from 'vitest'
import { runCollector } from '../src/run.js'

const src = (id: string, kind: any) => ({ id, name: id, kind, baseUrl: 'https://e.com', enabled: true })

test('collects enabled sources and isolates a failing one', async () => {
  const store = { upsertListings: vi.fn().mockResolvedValue(undefined),
    markSourceScanned: vi.fn(), logRun: vi.fn() } as any
  const adapters = {
    shopify: async () => [{ externalId: '1', title: 'A', url: 'https://e.com/a' }],
    woocommerce: async () => [{ externalId: '2', title: 'B', url: 'https://e.com/b' }],
    wordpress: async () => [], html: async () => { throw new Error('boom') }, headless: async () => [],
  } as any
  const report = await runCollector({ sources: [src('s1','shopify'), src('s2','html')], adapters, store })
  expect(report.perSource['s1']).toBe(1)
  expect(report.failures['s2']).toBe('boom')
  expect(report.ok).toBe(false)
  expect(store.upsertListings).toHaveBeenCalledTimes(1)
})
```

- [ ] **Step 2: Esegui e verifica il fallimento**

Run: `npx vitest run test/run.test.ts`
Expected: FAIL — modulo non trovato.

- [ ] **Step 3: Implementa `src/run.ts`**

Per ogni `source.enabled`: chiama l'adattatore in un `try/catch`; normalizza; `upsertListings`; `markSourceScanned`; conta in `perSource`. In `catch`, registra in `failures`. `ok = failures vuoto`. Alla fine `store.logRun(ok, report)`. Esporta anche un `main()` che legge `config`, costruisce il registry (Task 9), lo store Supabase e chiama `runCollector`, con `process.exitCode = ok ? 0 : 1`.

- [ ] **Step 4: Esegui e verifica il passaggio**

Run: `npx vitest run test/run.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add appgiochi/collector/src/run.ts appgiochi/collector/test/run.test.ts
git commit -m "feat(collector): orchestrator with per-source isolation"
```

---

### Task 9: Config, registry e seed delle fonti

**Files:**
- Create: `appgiochi/collector/src/config.ts`
- Create: `appgiochi/collector/src/adapters/registry.ts`
- Create: `appgiochi/collector/src/sources.seed.ts`
- Test: `appgiochi/collector/test/sources.test.ts`

**Interfaces:**
- Consumes: `Source`, `SourceKind` (Task 2); adattatori (Task 3–5); `Adapter` (Task 8).
- Produces:
  - `loadConfig(env: NodeJS.ProcessEnv): { supabaseUrl: string; supabaseServiceKey: string }` (lancia se mancanti)
  - `buildRegistry(http?: HttpClient): Record<SourceKind, Adapter>`
  - `SOURCES: Source[]` — le 14 fonti del txt con `kind` corretto.

- [ ] **Step 1: Scrivi il test che fallisce**

```ts
import { expect, test } from 'vitest'
import { SOURCES } from '../src/sources.seed.js'
import { buildRegistry } from '../src/adapters/registry.js'

test('seeds all 14 sources with a known kind', () => {
  expect(SOURCES).toHaveLength(14)
  const kinds = new Set(SOURCES.map(s => s.kind))
  for (const k of kinds) expect(['shopify','woocommerce','wordpress','html','headless']).toContain(k)
})

test('registry provides an adapter for every kind', () => {
  const r = buildRegistry()
  for (const k of ['shopify','woocommerce','wordpress','html','headless'] as const) {
    expect(typeof r[k]).toBe('function')
  }
})
```

- [ ] **Step 2: Esegui e verifica il fallimento**

Run: `npx vitest run test/sources.test.ts`
Expected: FAIL — moduli non trovati.

- [ ] **Step 3: Implementa**

`config.ts`: legge le env richieste, lancia con messaggio chiaro se assenti.
`sources.seed.ts`: le 14 fonti del file txt. Kinds per la ricognizione: Cranio `shopify`, Kosmos `shopify`, BoardHouse `shopify`; Pendragon `woocommerce`; Ghenos `wordpress`; Stonemaier `wordpress`; Giochi Uniti `html`; Ravensburger `html`; uplay `html`; Philibert `html`; Games Academy `wordpress`; Asmodee `headless`; DungeonDice `headless`; Magic Merchant `headless`.
`registry.ts`: mappa `shopify → fetchShopifyProducts(baseUrl, http)`, `woocommerce → fetchWooCommerceProducts(baseUrl, currencyFromSource, http)`, `wordpress → fetchWordPressProducts(baseUrl, http)`; per ora `html` e `headless` restituiscono `[]` (stub — implementate nel Piano 04).

- [ ] **Step 4: Esegui e verifica il passaggio**

Run: `npx vitest run test/sources.test.ts`
Expected: PASS (2 test).

- [ ] **Step 5: Esegui tutti i test e il typecheck**

Run: `npm test; npm run typecheck`
Expected: tutti PASS, nessun errore di tipo.

- [ ] **Step 6: Commit**

```bash
git add appgiochi/collector
git commit -m "feat(collector): config, adapter registry, 14-source seed"
```

---

### Task 10: Workflow GitHub Actions

**Files:**
- Create: `.github/workflows/collect.yml` (la radice del repo dedicato è la cartella `appgiochi`, quindi il workflow sta in `appgiochi/.github/workflows/collect.yml`)

**Interfaces:**
- Consumes: `npm run collect` (Task 8–9); segreti `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY` (Piano 00, A.3).
- Produces: un workflow programmato che esegue il collector.

- [ ] **Step 1: Scrivi il workflow che fallisce**

```yaml
name: collect
on:
  schedule:
    - cron: "0 5,17 * * *"
  workflow_dispatch: {}
jobs:
  collect:
    runs-on: ubuntu-latest
    defaults: { run: { working-directory: collector } }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: 20 }
      - run: npm ci
      - run: npm run collect
        env:
          SUPABASE_URL: ${{ secrets.SUPABASE_URL }}
          SUPABASE_SERVICE_ROLE_KEY: ${{ secrets.SUPABASE_SERVICE_ROLE_KEY }}
```

- [ ] **Step 2: Verifica il fallimento senza segreti (locale)**

Run: `$env:SUPABASE_URL=""; npx tsx src/run.ts` (da `appgiochi\collector`)
Expected: errore chiaro "SUPABASE_URL mancante" (da `loadConfig`).

- [ ] **Step 3: Verifica con segreti configurati**

Su GitHub: **Actions → collect → Run workflow**. 
Expected: il job completa; in Supabase compaiono righe in `listing` e una riga `ok: true` in `run_log`.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/collect.yml
git commit -m "ci(collector): scheduled GitHub Actions workflow"
```

---

### Task 11: Monitoraggio — segnalare le fonti a zero

**Files:**
- Modify: `appgiochi/collector/src/run.ts`
- Test: `appgiochi/collector/test/run.monitor.test.ts`

**Interfaces:**
- Consumes: `RunReport` (Task 8).
- Produces: `RunReport` esteso con `zeroed: string[]`; `main()` esce con codice 1 se una fonte abilitata restituisce 0 elementi.

- [ ] **Step 1: Scrivi il test che fallisce**

```ts
import { expect, test, vi } from 'vitest'
import { runCollector } from '../src/run.js'

const s = (id: string) => ({ id, name: id, kind: 'shopify', baseUrl: 'https://e.com', enabled: true })

test('flags a source that returns zero items', async () => {
  const store = { upsertListings: vi.fn(), markSourceScanned: vi.fn(), logRun: vi.fn() } as any
  const adapters = { shopify: async () => [], woocommerce: async()=>[], wordpress: async()=>[], html: async()=>[], headless: async()=>[] } as any
  const report = await runCollector({ sources: [s('empty')], adapters, store })
  expect(report.zeroed).toContain('empty')
})
```

- [ ] **Step 2: Esegui e verifica il fallimento**

Run: `npx vitest run test/run.monitor.test.ts`
Expected: FAIL — `zeroed` non definito.

- [ ] **Step 3: Implementa**

Aggiungi `zeroed: string[]` a `RunReport` (fonti abilitate con 0 elementi); in `main()` imposta `process.exitCode = 1` se `zeroed.length > 0` o `failures` non vuoto.

- [ ] **Step 4: Esegui tutti i test e verifica**

Run: `npm test; npm run typecheck`
Expected: tutti PASS.

- [ ] **Step 5: Commit**

```bash
git add appgiochi/collector
git commit -m "feat(collector): flag sources returning zero items"
```

---

## Self-review (eseguito)

- **Copertura spec:** §6 fonti (Task 3–5, 9, 10 e stub html/headless per Piano 04); §7 collector+Supabase (Task 7, 10); §11 monitoraggio (Task 11); §13.A.9 date affidabili (Task 6). BGG (Piano 02), app (Piano 03), notifiche/fonti difficili (Piano 04).
- **Tipi coerenti:** `RawProduct`/`Listing`/`Source` definiti una volta (Task 2) e riusati; `HttpClient` da Task 3 usato in 4–5; `ListingStore`/`Adapter`/`RunReport` coerenti tra Task 7–11.
- **Review Focus coperto:** (1) Task 3 Step 6; (2) Task 6; (3) Task 6; (4) Task 7; (5) Task 8.
- **Proporzione:** compiti con firme e test, non corpi completi.
