# Roundups House of Games Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sorgente `houseofgames` che scopre i roundups mensili "nuove uscite" da houseofgames.it via sitemap e ne estrae giochi, date, publisher e riassunto senza LLM, riempiendo `product` (descrizione/data/publisher) write-once, mentre `google-news` viene disabilitata.

**Architecture:** Nuovo adapter deterministico `adapters/roundups.ts` (kind `roundups`): `fetchSitemapEntries` riusato → filtro slug+floor → parse cheerio con stato publisher → righe `RawProduct` nel formato già usato dal percorso articoli news. `run.ts` tratta `news` e `roundups` come righe editoriali (prezzo/data fuori dall'upsert, fill condizionali) e aggiunge il fill `publisher` + uno skip di re-run che scarta le righe il cui gioco ha già un listing della stessa fonte. Nessuna chiamata LLM nel nuovo codice (il `gameFinder` esistente è un lookup sullo store).

**Tech Stack:** TypeScript ESM, zod, cheerio, vitest, Supabase-js. Shell PowerShell 5.1.

**Spec:** `docs/superpowers/specs/2026-10-22-hog-roundups-design.md` (il piano argomenta da lì: la spec viaggia col piano; gli executor leggono entrambi)

## Global Constraints

- Comandi sempre da `collector/` con `workdir`: test `node node_modules\vitest\vitest.mjs run <file>`, typecheck `node node_modules\typescript\bin\tsc --noEmit`. Niente `npx` (bloccato). PowerShell 5.1: niente `&&`, usare `;` o `if ($?)`.
- Commit convenzionali in italiano **senza accenti**, con `git add <path mirati>`: **mai** `git add -A` né `git commit -a` (il worktree ha modifiche non nostre della sessione parallela: non committarle, non toccarle: `collector/src/adapters/shopify.ts`, `collector/src/normalize.ts`, relativi test/fixture).
- Non committare: `.vite/`, `collection.csv`, export/, file di temp.
- Commenti nel codice solo dove il file li ha già (gli adapter hanno un blocco commento in testa; `db.ts`/`run.ts` hanno commenti inline sui vincoli). Log su `console.error` con prefisso `[roundups]`, in italiano.
- Nessuna DDL: la colonna `publisher` sull'app gioiello la esegue l'utente (`alter table product add column publisher text;`) PRIMA della prima run reale; questo piano non tocca il DB reale.
- Nessuna chiamata LLM nel nuovo codice; nessuna scrittura DB diretta: solo tramite `ListingStore` (`db.ts`).
- NON modificare: `google-news.ts` (mapping news intoccabile), `export-news.*`, `normalize.ts` (si Usa `extractReleaseDate` com'è), file della sessione Android.
- Fixture già presenti (dati reali, non riscriverli): `test/fixtures/hog-roundup-article.html` (pagina ottobre 2026 reale: 87 giochi, 16 publisher, `datePublished` 2026-10-02, UTF-8 senza BOM) e `test/fixtures/hog-post-sitemap.xml` (11 entry: loc in CDATA e plain, lastmod mancante, negativi).

## Review Focus

Cinque condizioni d'ingresso che la spec implica ma che nessun test banale copre, e dove vive il test che le pinna:

1. **Sitemap reale in formato CDATA (AIOSEO)**: `<loc><![CDATA[...]]></loc>` oggi viene perso da `LOC_RE` → la fonte sarebbe vuota in silenzio. Test: Task 2 (loc e lastmod in CDATA, più i casi plain esistenti che devono restare verdi).
2. **Floor 2026-08-01 a due livelli**: `lastmod` basso scarta prima del fetch (nessun articolo pre-floor mai scaricato) e `lastmod` assente NON scarta (safe: il giudice autorevole è `datePublished` nel JSON-LD, che scarta dopo il fetch). Test: Task 3 (`filterRoundupEntries`) e Task 4 (nessun fetch per candidati pre-floor; pagina con `datePublished` pre-floor scartata).
3. **Li di menu/sidebar fuori dall'articolo**: nella pagina reale ci sono 13 `<li>` di menu prima del contenuto e 7 dopo, alcuni con attributi. Il parser deve restituire esattamente gli 87 giochi e mai voci come `LIBROGAME`. Test: Task 3 (conteggio 87, nessun titolo di menu, caso sintetico `<li class=...>` scartato).
4. **Riassunto che finisce nelle promo**: dopo ogni gioco ci sono immagini e link dungeondice con affiliazione: il riassunto deve contenere il testo del paragrafo (`Porta le tue battaglie...`) e mai `dungeondice`/`Cliccando`; si ferma al prossimo publisher. Test: Task 3 (fixture reale + mini-HTML sintetici con `<a>`/`<img>`).
5. **Re-run senza scritture vs giochi nuovi e kind non-roundups**: la skip del re-run deve fermare i giochi già raccolti dalla STESSA fonte, far passare quelli nuovi, e non toccare shopify/news (che non hanno quell'id). Publisher write-once non sovrascrive mai. Test: Task 1 (fill `.is('publisher', null)`) e Task 6 (skip, kind-gate, payload editoriale).

---

### Task 1: Publisher plumbing (schema RawProduct + fill write-once)

**Files:**
- Modify: `collector/src/types.ts:6-21` (`RawProductSchema`)
- Modify: `collector/src/db.ts:18-30` (interfaccia `ListingStore`) e ~173-183 (dopo `fillProductReleaseDate`)
- Test: `collector/test/types.test.ts`, `collector/test/db.test.ts:388-399` (specchiarre il test di `fillProductReleaseDate`)

**Interfaces:**
- Consuma: niente di nuovo (zod già in `types.ts`, pattern fill già in `db.ts`).
- Produce (usato dai task 3, 4, 6): `RawProductSchema` con campo opzionale `publisher: string`; `ListingStore.fillProductPublisher(productId: number, publisher: string): Promise<void>`.

- [ ] **Step 1: Test RED — schema e fill**

In `test/types.test.ts`:

```ts
test('accepts an optional publisher', () => {
  const parsed = RawProductSchema.parse({ externalId: '1', title: 'X', url: 'https://e.com/x', publisher: 'Asmodee' })
  expect(parsed.publisher).toBe('Asmodee')
  const without = RawProductSchema.parse({ externalId: '1', title: 'X', url: 'https://e.com/x' })
  expect(without.publisher).toBeUndefined()
})
```

In `test/db.test.ts`, nuovo test copia fedele di `fillProductReleaseDate` (righe 388-399) con i valori:

```ts
test('fillProductPublisher aggiorna solo la colonna ancora vuota', async () => {
  const is = vi.fn().mockReturnValue({ error: null })
  const eq = vi.fn(() => ({ is }))
  const update = vi.fn((payload: { publisher: string }) => ({ eq }))
  const from = vi.fn(() => ({ update }))
  const store = createSupabaseStore({ from } as any, () => '2026-10-22T00:00:00Z')
  await store.fillProductPublisher(7, 'Asmodee')
  expect(from).toHaveBeenCalledWith('product')
  expect(update.mock.calls[0][0]).toEqual({ publisher: 'Asmodee' })
  expect(eq).toHaveBeenCalledWith('id', 7)
  expect(is).toHaveBeenCalledWith('publisher', null)
})
```

- [ ] **Step 2: Verifica RED**

Run: `node node_modules\vitest\vitest.mjs run test\types.test.ts test\db.test.ts`
Atteso: FAIL (campo `publisher` ignoto da zod in entrambi; `fillProductPublisher` inesistente su `ListingStore` → errore di compile/assegnazione).

- [ ] **Step 3: Implementa**

`types.ts`: dentro `RawProductSchema`, dopo `articleDescription`, aggiungi

```ts
// Editore della sezione dell'articolo roundup (fonte houseofgames): write-once su product.publisher.
publisher: z.string().optional(),
```

`db.ts`: in `ListingStore` dopo `fillProductReleaseDate`:

```ts
fillProductPublisher(productId: number, publisher: string): Promise<void>
```

nell'implementazione, copia fedele di `fillProductReleaseDate` con payload `{ publisher }` e filtro `.is('publisher', null)`, commento analogo ("unico scrittore della colonna ... write-once").

- [ ] **Step 4: Verifica GREEN**

Run: `node node_modules\vitest\vitest.mjs run test\types.test.ts test\db.test.ts`
Atteso: PASS.

- [ ] **Step 5: Commit**

```powershell
git add collector/src/types.ts collector/src/db.ts collector/test/types.test.ts collector/test/db.test.ts
git commit -m "feat: colonna publisher write-once su product"
```

---

### Task 2: `parseSitemapEntries` legge loc e lastmod in CDATA

**Files:**
- Modify: `collector/src/adapters/sitemap.ts:6-7`
- Test: `collector/test/adapters/sitemap.test.ts`

**Interfaces:**
- Consuma: `SitemapEntry { loc: string; lastmod?: string }` (invariato).
- Produce: stesso `parseSitemapEntries(xml)` ma capace del formato reale AIOSEO (`<![CDATA[...]]>`), usato dal task 4.

- [ ] **Step 1: Test RED**

Nuovo test in `test/adapters/sitemap.test.ts` (la sitemap di houseofgames.it è fatta così: CDATA anche su `lastmod`):

```ts
test('parses entries with loc and lastmod wrapped in CDATA (AIOSEO)', () => {
  const xml = `<?xml version="1.0"?><urlset>
    <url>
      <loc><![CDATA[https://www.houseofgames.it/giochi-da-tavolo-le-nuove-uscite-di-settembre-2026/]]></loc>
      <lastmod><![CDATA[2026-09-03T06:11:02+00:00]]></lastmod>
    </url>
  </urlset>`
  expect(parseSitemapEntries(xml)).toEqual([
    { loc: 'https://www.houseofgames.it/giochi-da-tavolo-le-nuove-uscite-di-settembre-2026/', lastmod: '2026-09-03T06:11:02+00:00' },
  ])
})
```

- [ ] **Step 2: Verifica RED**

Run: `node node_modules\vitest\vitest.mjs run test\adapters\sitemap.test.ts`
Atteso: FAIL (`[]` invece dell'entry: `LOC_RE` si ferma al `<` di `<![CDATA[`).

- [ ] **Step 3: Implementa**

In `sitemap.ts` aggiorna `LOC_RE` e `LASTMOD_RE` accettando l'avvolgimento opzionale, es.:

```ts
const LOC_RE = /<loc>\s*(?:<!\[CDATA\[)?\s*([^<]*?)\s*(?:\]\]>)?\s*<\/loc>/i
```

e analogamente per `LASTMOD_RE`. `[^<]` cattura l'URL (che non contiene `<`), il gruppo opzionale consuma i delimitatori CDATA. Non toccare il ramo Jina.

- [ ] **Step 4: Verifica GREEN**

Run: `node node_modules\vitest\vitest.mjs run test\adapters\sitemap.test.ts`
Atteso: PASS (i test plain preesistenti restano verdi).

- [ ] **Step 5: Commit**

```powershell
git add collector/src/adapters/sitemap.ts collector/test/adapters/sitemap.test.ts
git commit -m "fix: sitemap parser accetta loc e lastmod in cdata"
```

---

### Task 3: Funzioni pure di discovery e parsing roundups

**Files:**
- Create: `collector/src/adapters/roundups.ts`
- Test: `collector/test/adapters/roundups.test.ts`
- Fixtures: già presenti (Global Constraints)

**Interfaces:**
- Consuma: `SitemapEntry`/`parseSitemapEntries` da `adapters/sitemap.ts` (già fixato), `extractReleaseDate` da `normalize.ts` (solo nelle sue test-date, qui non serve), cheerio (`import * as cheerio from 'cheerio'`), `htmlToText`/`decodeHtml` da `text.ts`.
- Produce (usato dal task 4):

```ts
export const ROUNDUP_FLOOR_DATE = '2026-08-01'
export type RoundupGame = { title: string; dateRaw?: string; publisher?: string; summary?: string }
export function isRoundupUrl(loc: string): boolean
export function filterRoundupEntries(entries: SitemapEntry[], floor?: string): SitemapEntry[]
export function extractDatePublished(html: string): string | null
export function parseRoundupArticle(html: string): RoundupGame[]
```

**Comportamenti esatti decisi dalla spec:**
- `isRoundupUrl`: `new URL(loc).pathname` (URL invalido o host che non finisce per `houseofgames.it` → false) deve contenere sia `giochi-da-tavolo-` sia `nuove-uscite-di-`.
- `filterRoundupEntries`: tiene solo slug validi; `lastmod` assente → tenuto (safe); `lastmod.slice(0, 10) < floor` → scartato. `floor` default `ROUNDUP_FLOOR_DATE`. Mantiene l'ordinamento in input (l'ordinamento è di `newestFirst`, nel task 4).
- `extractDatePublished`: prima occorrenza di `"datePublished": "..."` (regex `/"datePublished"\s*:\s*"([^"]+)"/`) → `slice(0, 10)`; nessuna occorrenza → null.
- `parseRoundupArticle`: cheerio `load(html)`, iterazione `$('p, li')` in ordine di documento con stato `publisherCorrente`:
  - **publisher**: `p` il cui unico figlio elemento è un `strong` e senza altro testo → `publisherCorrente = text.trim()`;
  - **gioco**: `li` **senza attributi** con un solo figlio `strong` → split con `/^(.*?)\s*\(([^()]*)\)\s*$/u` su `strong.text()`: match → `title`/`dateRaw` (trim); no match → `title` intero, `dateRaw` omesso. Push di `{title, dateRaw?, publisher: publisherCorrente, summary?}` (publisher omesso se `undefined`);
  - **summary**: per il gioco corrente, ogni `p` NON publisher, senza discendenti `a`/`img`, con testo non vuoto → accumula (`htmlToText` per pezzo, pezzi uniti da spazio singolo); un `p` con `a`/`img` o l'inizio di un nuovo gioco/publisher chiude lo accumulo.
  - Nessun cap di lunghezza sul riassunto.

- [ ] **Step 1: Test RED**

Nuovo `test/adapters/roundups.test.ts`. Test per nome e asserzioni (scriverli tutti in questo file):

```ts
import { readFileSync } from 'node:fs'
import { expect, test } from 'vitest'
import { parseSitemapEntries } from '../../src/adapters/sitemap.js'
import {
  ROUNDUP_FLOOR_DATE, isRoundupUrl, filterRoundupEntries, extractDatePublished, parseRoundupArticle,
} from '../../src/adapters/roundups.js'

const sitemapXml = readFileSync(new URL('../fixtures/hog-post-sitemap.xml', import.meta.url), 'utf8')
const articleHtml = readFileSync(new URL('../fixtures/hog-roundup-article.html', import.meta.url), 'utf8')

test('isRoundupUrl accetta i roundups mensili e rifiuta tutto il resto', () => {
  expect(isRoundupUrl('https://www.houseofgames.it/giochi-da-tavolo-tutte-le-nuove-uscite-di-ottobre-lucca-comics-e-combo-festival-2026/')).toBe(true)
  expect(isRoundupUrl('https://www.houseofgames.it/giochi-da-tavolo-le-nuove-uscite-di-settembre-2026/')).toBe(true)
  expect(isRoundupUrl('https://www.houseofgames.it/giochi-da-tavolo-le-nuove-uscite-di-dicembre-perfette-per-natale/')).toBe(true)
  expect(isRoundupUrl('https://www.houseofgames.it/uscite-videogiochi-2025-calendario-completo-per-ps5-xbox-nintendo-switch-e-pc/')).toBe(false)
  expect(isRoundupUrl('https://www.houseofgames.it/recensione-gioco-da-tavolo-catan/')).toBe(false)
  expect(isRoundupUrl('https://www.houseofgames.it/giochi-da-tavolo-catan-recensione/')).toBe(false)
  expect(isRoundupUrl('https://www.houseofgames.it/dungeon-crawler-cose/')).toBe(false)
  expect(isRoundupUrl('non-un-url')).toBe(false)
})

test('filterRoundupEntries tiene gli slug validi con lastmod >= floor e i lastmod mancanti', () => {
  const kept = filterRoundupEntries(parseSitemapEntries(sitemapXml)).map((e) => e.loc)
  expect(kept).toEqual([
    'https://www.houseofgames.it/giochi-da-tavolo-tutte-le-nuove-uscite-di-ottobre-lucca-comics-e-combo-festival-2026/',
    'https://www.houseofgames.it/giochi-da-tavolo-le-nuove-uscite-di-settembre-2026/',
    'https://www.houseofgames.it/giochi-da-tavolo-le-nuove-uscite-di-agosto-2026/',
    'https://www.houseofgames.it/giochi-da-tavolo-le-nuove-uscite-di-novembre-2025/', // lastmod assente: tenuto (safe)
  ])
  expect(ROUNDUP_FLOOR_DATE).toBe('2026-08-01')
})

test('extractDatePublished legge il json-ld aioseo della pagina reale', () => {
  expect(extractDatePublished(articleHtml)).toBe('2026-10-02')
  expect(extractDatePublished('<html><body>senza schema</body></html>')).toBeNull()
})
```

Test di parsing sulla fixture reale (il cuore):

```ts
test('parseRoundupArticle estrae i 87 giochi della pagina reale con publisher, data e riassunto', () => {
  const games = parseRoundupArticle(articleHtml)
  expect(games).toHaveLength(87)
  // I li di menu/sidebar (attributi o <a>) non sono giochi.
  expect(games.every((g) => g.title !== 'LIBROGAME')).toBe(true)
  expect(games.every((g) => g.publisher !== undefined)).toBe(true)
  expect(new Set(games.map((g) => g.publisher)).size).toBe(16)

  const toy = games[0]
  expect(toy).toMatchObject({ title: 'TOY BATTLE – LA CROISETTE', dateRaw: '2 Ottobre', publisher: 'ASMODEE' })
  expect(toy.summary).toContain('Porta le tue battaglie di giocattoli')
  // Il riassunto esce prima del link promo dungeondice.
  expect(toy.summary).not.toContain('dungeondice')
  expect(toy.summary).not.toContain('Cliccando')

  // Due giochi senza data fra parentesi restano senza dateRaw.
  const noDate = games.find((g) => g.title === 'FAME & FABLE')
  expect(noDate?.dateRaw).toBeUndefined()
  expect(noDate?.publisher).toBeDefined()

  // Entità decodificate e varianti di parentesi/spazi.
  expect(games.some((g) => g.title === 'LET’S CHAT' && g.dateRaw === 'Fine Ottobre')).toBe(true)
  expect(games.some((g) => g.title.includes('MOZA') && g.dateRaw === '2 Ottobre')).toBe(true)
  expect(games.some((g) => g.title.endsWith('DEL MONDO') && g.dateRaw === '23 Ottobre')).toBe(true)
  expect(games.some((g) => g.title.includes('LORCANA') && g.dateRaw === '1 OTTOBRE')).toBe(true)
  expect(games.some((g) => g.title === 'FOURTH WING' && g.dateRaw === 'Anteprima Lucca')).toBe(true)
})
```

Test sintetici sugli stipi (senza fixture):

```ts
test('lo summary si ferma ai link, alle immagini e al publisher successivo', () => {
  const html = `
    <p><strong>CASA EDITRICE</strong></p>
    <ul><li><strong>Gioco Uno (3 Gennaio)</strong></li></ul>
    <p><img src="x.webp"/></p>
    <p>Prima frase del riassunto.</p>
    <p>Seconda frase.</p>
    <p><a href="https://www.dungeondice.it/p/1">Cliccando qui</a></p>
    <p><strong>ALTRA CASA</strong></p>
    <ul><li><strong>Gioco Due</strong></li></ul>
    <li class="menu"><a href="/x">MENU</a></li>`
  const games = parseRoundupArticle(html)
  expect(games).toHaveLength(2)
  expect(games[0].summary).toBe('Prima frase del riassunto. Seconda frase.')
  expect(games[1]).toMatchObject({ title: 'Gioco Due', publisher: 'ALTRA CASA', dateRaw: undefined, summary: undefined })
})

test('un li con attributi o un p con testo forte fuori dal contesto non creano publisher', () => {
  const html = `
    <ul><li class="menu-item"><strong>NON UN GIOCO (1 Gennaio)</strong></li></ul>
    <ul><li><strong>Gioco Prima Di Ogni Publisher</strong></li></ul>`
  const games = parseRoundupArticle(html)
  expect(games).toHaveLength(1)
  expect(games[0].title).toBe('Gioco Prima Di Ogni Publisher')
  expect(games[0].publisher).toBeUndefined()
})
```

- [ ] **Step 2: Verifica RED**

Run: `node node_modules\vitest\vitest.mjs run test\adapters\roundups.test.ts`
Atteso: FAIL (modulo `roundups.js` inesistente).

- [ ] **Step 3: Implementa `collector/src/adapters/roundups.ts`**

Testa in testa un blocco commento come gli altri adapter (in italiano: fonte, cosa fa, perché senza LLM). Implementa solo le funzioni pure dell'Interfaces: per `parseRoundupArticle` la state machine descritta sopra (`$('p, li')` in ordine di documento; publisher = p con unico figlio strong; gioco = li senza attributi con unico figlio strong; split titolo/data con la regex radicata; summary accumulato fra virgolette di stato, chiuso da `a`/`img`/nuovo gioco/publisher). Nessuna dipendenza di rete in questo file (le parti di fetch arrivano nel task 4).

- [ ] **Step 4: Verifica GREEN**

Run: `node node_modules\vitest\vitest.mjs run test\adapters\roundups.test.ts`
Atteso: PASS. Se un'asserzione della fixture reale fallisce (es. conteggio o testo atteso), PRIMA di toccare il codice rileggi la zona della fixture coinvolta: la struttura reale detta il comportamento corretto, non viceversa.

- [ ] **Step 5: Commit**

```powershell
git add collector/src/adapters/roundups.ts collector/test/adapters/roundups.test.ts
git commit -m "feat: parser roundups houseofgames (funzioni pure)"
```

---

### Task 4: Adapter `fetchRoundupProducts` (fetch sitemap + articoli + righe)

**Files:**
- Modify: `collector/src/adapters/roundups.ts` (aggiunge orchestrazione e mapping)
- Test: `collector/test/adapters/roundups.test.ts` (stesso file, nuovi test)

**Interfaces:**
- Consuma: funzioni pure del task 3; `fetchSitemapEntries`/`newestFirst` da `adapters/sitemap.ts` (task 2); `textFetcher`/`TextFetcher` da `adapters/http.ts`; `extractReleaseDate`, `normalizeTitle` da `normalize.ts`; `GameFinder`, `RawProduct`, `Source` da `types.ts`.
- Produce (usato dal task 5):

```ts
export type RoundupOptions = {
  fetchText?: TextFetcher   // default textFetcher()
  sleep?: (ms: number) => Promise<void>   // default setTimeout, solo test lo inietta
}
export async function fetchRoundupProducts(
  source: Source,
  gameFinder?: GameFinder,
  options?: RoundupOptions,
): Promise<RawProduct[]>
```

**Comportamenti esatti decisi dalla spec:**
1. `entries = await fetchSitemapEntries(`${source.baseUrl}/post-sitemap.xml`, fetchText)`; `entries.length === 0` → `throw new Error('sitemap roundups non raggiungibile')` (la fonte finisce in `failures`, come i feed news).
2. `candidates = newestFirst(filterRoundupEntries(entries))`; pacing: `sleep(1000)` prima di ogni fetch articolo tranne il primo.
3. Per candidato: `fetchText(loc)` → null → log + continue. `extractDatePublished(html)` → null o `< ROUNDUP_FLOOR_DATE` → log + continue (floor autorevole). `parseRoundupArticle` → 0 giochi → log + continue.
4. Mapping per gioco (specchio news `google-news.ts:437-467`): `externalId = normalizeTitle(g.title).replace(/\s+/g,'-')`, `url = loc`; se `g.dateRaw` → `iso = extractReleaseDate(`${g.dateRaw} ${datePublished.slice(0, 4)}`)` e se non null → `releaseDateRaw = iso`; se `g.summary` → `articleDescription = g.summary`; se `g.publisher` → `publisher = g.publisher`; poi `gameFinder(normalizeTitle(g.title), g.publisher)` dentro try/catch (log `[roundups] gameFinder: ${msg}`): da `match` entrano `priceRaw = String(match.price)`, `currencyRaw`, `releaseDateRaw` (sovrascrive il dato dell'articolo: il catalogo vince), `imageUrl`, `gameId` — solo i campi non null, come news.
5. Dedup finale per `normalizeTitle(title)` sui prodotti di TUTTI gli articoli, tenendo il primo (i candidati sono dal più recente: vince l'articolo più nuovo, come news riga 490-491).
6. Log `[roundups]`: `N voci sitemap, M articoli candidati`, per articolo `K giochi dall'articolo`, totale `T prodotti`. Ritorna anche `[]` valido (zeroed) quando tutti gli articoli sono scartati.

- [ ] **Step 1: Test RED**

Nuovi test in `test/adapters/roundups.test.ts` (fake `fetchText` come `{[url]: html}` più `sleep: async () => {}` in ogni test per non aspettare i 1000ms reali):

```ts
test('fetchRoundupProducts mappa la pagina reale in righe RawProduct con gameFinder', async () => {
  const sitemapUrl = 'https://www.houseofgames.it/post-sitemap.xml'
  const articoloUrl = 'https://www.houseofgames.it/giochi-da-tavolo-tutte-le-nuove-uscite-di-ottobre-lucca-comics-e-combo-festival-2026/'
  const pages: Record<string, string> = { [sitemapUrl]: sitemapXml, [articoloUrl]: articleHtml }
  const gameFinder = vi.fn().mockResolvedValue({ gameId: 5, price: 29.99, currency: 'EUR', releaseDate: null, imageUrl: 'https://e.com/i.webp' })
  const out = await fetchRoundupProducts(
    { id: 'houseofgames', name: 'House of Games', kind: 'roundups' as const, baseUrl: 'https://www.houseofgames.it', enabled: true },
    gameFinder,
    { fetchText: async (u) => pages[u] ?? null, sleep: async () => {} },
  )
  expect(out).toHaveLength(87)
  const toy = out.find((p) => p.externalId === 'toy-battle-la-croisette')
  expect(toy).toMatchObject({
    url: articoloUrl,
    title: 'TOY BATTLE – LA CROISETTE',
    publisher: 'ASMODEE',
    releaseDateRaw: '2026-10-02',
    priceRaw: '29.99',
    currencyRaw: 'EUR',
    gameId: 5,
  })
  expect(toy?.articleDescription).toContain('Porta le tue battaglie')
  expect(gameFinder).toHaveBeenCalledWith('toy battle la croisette', 'ASMODEE')
})
```

```ts
test('floor pre-fetch: gli articoli con lastmod prima del floor non vengono scaricati', async () => {
  const sitemapUrl = 'https://www.houseofgames.it/post-sitemap.xml'
  const vecchio = 'https://www.houseofgames.it/giochi-da-tavolo-le-nuove-uscite-di-giugno-2026-speciale-play-bologna/'
  const pages: Record<string, string> = { [sitemapUrl]: sitemapXml, [vecchio]: '<p>non deve servire</p>' }
  const fetched: string[] = []
  const out = await fetchRoundupProducts(SOURCE, undefined, {
    fetchText: async (u) => { fetched.push(u); return pages[u] ?? null },
    sleep: async () => {},
  })
  // La sitemap contiene solo un roundups pre-floor scaricabile: nessun fetch di quel URL.
  expect(fetched.filter((u) => u === vecchio)).toHaveLength(0)
  // Tutti gli altri candidati (ago/set/ott 2026) non sono in pages → fetch null → skip silenzioso.
  expect(out).toEqual([])
})
```

```ts
test('floor autorevole: una pagina con datePublished pre-floor viene scartata', async () => {
  const sitemapUrl = 'https://www.houseofgames.it/post-sitemap.xml'
  const url = 'https://www.houseofgames.it/giochi-da-tavolo-le-nuove-uscite-di-settembre-2026/'
  const vecchiaPagina = articleHtml.replace(
    /"datePublished"\s*:\s*"[^"]+"/,
    '"datePublished": "2026-07-01T09:00:00+02:00"',
  )
  const out = await fetchRoundupProducts(SOURCE, undefined, {
    fetchText: async (u) => (u === sitemapUrl ? sitemapXml : u === url ? vecchiaPagina : null),
    sleep: async () => {},
  })
  expect(out).toEqual([])
})
```

```ts
test('sitemap non raggiungibile lancia; un articolo non fetchabile salta senza far cadere la fonte', async () => {
  await expect(fetchRoundupProducts(SOURCE, undefined, { fetchText: async () => null, sleep: async () => {} }))
    .rejects.toThrow(/non raggiungibile/)
  const sitemapUrl = 'https://www.houseofgames.it/post-sitemap.xml'
  const out = await fetchRoundupProducts(SOURCE, undefined, {
    fetchText: async (u) => (u === sitemapUrl ? sitemapXml : null),
    sleep: async () => {},
  })
  expect(out).toEqual([])
})
```

```ts
test('due roundups con lo stesso gioco → una riga sola, vince l\'articolo più recente', async () => {
  const sitemapUrl = 'https://www.houseofgames.it/post-sitemap.xml'
  const settembre = 'https://www.houseofgames.it/giochi-da-tavolo-le-nuove-uscite-di-settembre-2026/'
  const agosto = 'https://www.houseofgames.it/giochi-da-tavolo-le-nuove-uscite-di-agosto-2026/'
  const mini = (data: string) => `<script type="application/ld+json">{"datePublished":"${data}"}</script>
    <p><strong>CASA</strong></p><ul><li><strong>Gioco Uguale (5 Gennaio)</strong></li></ul><p>Riassunto.</p>`
  const out = await fetchRoundupProducts(SOURCE, undefined, {
    fetchText: async (u) =>
      u === sitemapUrl
        ? `<urlset><url><loc>${settembre}</loc><lastmod>2026-09-03</lastmod></url><url><loc>${agosto}</loc><lastmod>2026-08-04</lastmod></url></urlset>`
        : u === settembre ? mini('2026-09-03') : mini('2026-08-04'),
    sleep: async () => {},
  })
  expect(out).toHaveLength(1)
  expect(out[0].url).toBe(settembre)
})
```

Dove `SOURCE` è un piccolo helper locale del file di test (`kind: 'roundups' as const`, `baseUrl: 'https://www.houseofgames.it'`). `vi` va aggiunto agli import di vitest nel file.

- [ ] **Step 2: Verifica RED**

Run: `node node_modules\vitest\vitest.mjs run test\adapters\roundups.test.ts`
Atteso: FAIL (`fetchRoundupProducts` non esportato).

- [ ] **Step 3: Implementa l'orchestrazione in `roundups.ts`**

Aggiungi import (`fetchSitemapEntries`, `newestFirst`, `textFetcher`, `extractReleaseDate`, `normalizeTitle`, tipi) e implementa `fetchRoundupProducts` seguendo punti 1-6 dell'Interfaces: la parte di rete è un for su `candidates` con `fetchText` iniettabile; tutta la logica di parsing resta nelle funzioni pure del task 3. Seleziona `vi`/`expect` già presenti nel file di test.

- [ ] **Step 4: Verifica GREEN**

Run: `node node_modules\vitest\vitest.mjs run test\adapters\roundups.test.ts`
Atteso: PASS (tutti i test del task 3 inclusi).

- [ ] **Step 5: Commit**

```powershell
git add collector/src/adapters/roundups.ts collector/test/adapters/roundups.test.ts
git commit -m "feat: adapter roundups con scoperta da sitemap"
```

---

### Task 5: Wiring — `SourceKind 'roundups'`, registry, seed, `sources.test`

**Files:**
- Modify: `collector/src/types.ts:3` (`SOURCE_KINDS`)
- Modify: `collector/src/adapters/registry.ts:14-32`
- Modify: `collector/src/sources.seed.ts:20-22`
- Test: `collector/test/sources.test.ts` (tutte e tre i test)

**Interfaces:**
- Consuma: `fetchRoundupProducts` (task 4), `GameFinder` già passato a `buildRegistry`.
- Produce: sorgente `houseofgames` abilitata e `google-news` disabilitata; `r.roundups` funzionante per `runCollector` (task 6).

- [ ] **Step 1: Test RED — il contratto nuovo della seed**

Aggiorna `test/sources.test.ts` PRIMA del codice (il test diventa la specifica):

- riga 5: `const KINDS = ['shopify', 'woocommerce', 'wordpress', 'html', 'headless', 'gemini', 'news', 'roundups'] as const`
- test 1: `expect(SOURCES).toHaveLength(19)` (titolo: `'seeds all 19 sources with a known kind'`)
- test 3 (lista enabled): atteso

```ts
expect(enabled).toEqual(['asmodee', 'boardhouse', 'cranio', 'dungeondice', 'dvgiochi', 'gateongames', 'ghenos', 'giochiuniti', 'horribleguild', 'houseofgames', 'littlerocket', 'msedizioni', 'pendragon', 'supernova'])
```

- [ ] **Step 2: Verifica RED**

Run: `node node_modules\vitest\vitest.mjs run test\sources.test.ts`
Atteso: FAIL (18≠19, lista enabled con `google-news` e senza `houseofgames`; dopo l'aggiornamento di KINDS anche `r.roundups` non-funzione).

- [ ] **Step 3: Implementa wiring**

`types.ts` riga 3: aggiungi `'roundups'` alla fine di `SOURCE_KINDS`.

`registry.ts`: importa `fetchRoundupProducts` e aggiungi voce

```ts
// Roundup mensili "nuove uscite" di houseofgames.it: sitemap + parse HTML deterministico, zero LLM.
roundups: (source) => fetchRoundupProducts(source, gameFinder),
```

`sources.seed.ts`: dopo la riga di `gemini`, aggiungi

```ts
{ id: 'houseofgames',   name: 'House of Games',          kind: 'roundups',    baseUrl: 'https://www.houseofgames.it', enabled: true }, // Roundup mensili "nuove uscite": scoperta da post-sitemap.xml, parse HTML deterministico (spec docs/superpowers/specs/2026-10-22-hog-roundups-design.md)
```

e sulla riga `google-news` cambia `enabled: true` → `enabled: false` con commento aggiornato (sostituita da houseofgames/roundups: titoli Google News inutili; riabilitare solo se servisse di nuovo).

- [ ] **Step 4: Verifica GREEN**

Run: `node node_modules\vitest\vitest.mjs run test\sources.test.ts test\adapters\registry.test.ts`
Atteso: PASS.

- [ ] **Step 5: Commit**

```powershell
git add collector/src/types.ts collector/src/adapters/registry.ts collector/src/sources.seed.ts collector/test/sources.test.ts
git commit -m "feat: kind roundups e source houseofgames, google-news off"
```

---

### Task 6: `run.ts` — riga editoriale, fill publisher, skip di re-run

**Files:**
- Modify: `collector/src/run.ts:47-113`
- Test: `collector/test/run.roundups.test.ts` (nuovo; `run.test.ts` esistente resta intatto)

**Interfaces:**
- Consuma: `fillProductPublisher` (task 1), kind `roundups` (task 5), righe `RawProduct` del task 4 (`publisher`, `articleDescription`, `releaseDateRaw`).
- Produce: comportamento di `runCollector` per la fonte roundups (verificato da test indipendenti).

**Modifiche esatte a `run.ts`:**

1. Riga 61: `const isNews = source.kind === 'news'` → `const isEditorial = source.kind === 'news' || source.kind === 'roundups'`; riusa il nome nelle righe 81, 91 e 104 (commenti riga 64-66 e 87-90 da aggiornare: "run editoriale (news/roundups)"). NIENTE tocco a `GENERATED_TEXT_KINDS` (i roundups non ci entrano: il riassunto è verbatim e viaggia in `articleDescription`).
2. Dopo la riga 104, fill del publisher (kind-indipendente: solo i roundups popolano il campo):

```ts
if (p.raw.publisher) await store.fillProductPublisher(productId, p.raw.publisher)
```

3. Skip di re-run, DOPO il filtro di cutoff (riga 57) e PRIMA di `upsertListings` (riga 58): `paired` diventa `let` e per `source.kind === 'roundups'` ogni riga la cui `row.title` ha già un listing della STESSA fonte viene scartata:

```ts
if (source.kind === 'roundups') {
  const fresh: typeof paired = []
  for (const p of paired) {
    const matches = await store.findListingsByTitle(normalizeTitle(p.row.title))
    if (!matches.some((m) => m.sourceId === source.id)) fresh.push(p)
  }
  paired = fresh
}
```

(`normalizeTitle` è già importato. `findListingsByTitle` è già nel contratto `ListingStore`. La skip NON tocca `raw.length`/`zeroed`/`perSource`.)

- [ ] **Step 1: Test RED**

Nuovo `test/run.roundups.test.ts` con helper locale stile `storeBase` di `run.test.ts:126-136` **a cui si aggiunge** `fillProductPublisher: vi.fn().mockResolvedValue(undefined)` e `src(id, kind)` locale. Test:

```ts
test('re-run roundups: un gioco già raccolto dalla stessa fonte non viene più riscritto', async () => {
  const store = storeBase()
  store.findListingsByTitle.mockResolvedValue([
    { id: 9, title: 'G', url: 'https://www.houseofgames.it/a', price: null, currency: null, releaseDate: null, imageUrl: null, gameId: null, sourceId: 'houseofgames' },
  ])
  const adapters = { roundups: async () => [{ externalId: 'g', title: 'G', url: 'https://www.houseofgames.it/a', publisher: 'PUB' }] } as any
  const report = await runCollector({ sources: [src('houseofgames', 'roundups')], adapters, store })
  expect(store.upsertListings).toHaveBeenCalledWith([])
  expect(store.upsertProducts).not.toHaveBeenCalled()
  expect(store.fillProductPublisher).not.toHaveBeenCalled()
  expect(store.markSourceScanned).toHaveBeenCalledWith('houseofgames')
  expect(report.perSource['houseofgames']).toBe(1)
})

test('roundups: payload editoriale senza prezzo/data, fill publisher e release_date write-once', async () => {
  const store = storeBase()
  const adapters = {
    roundups: async () => [{
      externalId: 'g', title: 'G', url: 'https://www.houseofgames.it/a',
      publisher: 'PUB', releaseDateRaw: '2026-10-02', priceRaw: '29.99',
      articleDescription: 'Riassunto verbatim.',
    }],
  } as any
  await runCollector({
    sources: [src('houseofgames', 'roundups')], adapters, store,
    now: () => new Date('2026-10-20T00:00:00Z'),
  })
  const payload = store.upsertProducts.mock.calls[0][0][0]
  expect(payload).not.toHaveProperty('releaseDate')
  expect(payload).not.toHaveProperty('price')
  expect(store.fillProductReleaseDate).toHaveBeenCalledWith(7, '2026-10-02')
  expect(store.fillProductPublisher).toHaveBeenCalledWith(7, 'PUB')
  expect(store.fillProductDescription).toHaveBeenCalledWith(7, 'Riassunto verbatim.')
})

test('la skip vale solo per roundups: una fonte shopify con lo stesso matching non viene bloccata', async () => {
  const store = storeBase()
  store.findListingsByTitle.mockResolvedValue([
    { id: 3, title: 'A', url: 'https://e.com/a', price: null, currency: null, releaseDate: null, imageUrl: null, gameId: null, sourceId: 's1' },
  ])
  const adapters = { shopify: async () => [{ externalId: '1', title: 'A', url: 'https://e.com/a' }] } as any
  await runCollector({ sources: [src('s1', 'shopify')], adapters, store })
  expect(store.upsertListings).toHaveBeenCalledTimes(1)
  expect(store.upsertProducts).toHaveBeenCalledTimes(1)
})
```

- [ ] **Step 2: Verifica RED**

Run: `node node_modules\vitest\vitest.mjs run test\run.roundups.test.ts`
Atteso: FAIL (test 1: la riga roundups viene scritta igualmente; test 2: `fillProductPublisher` assente sullo store finto o payload con `releaseDate`; test 3: può essere già verde — serve come pin contro regressioni future, deve restare verde).

- [ ] **Step 3: Implementa le tre modifiche a `run.ts`**

Come da "Modifiche esatte" sopra: rinomina `isNews` → `isEditorial` nei 4 punti (61, 81, 91, 104) con commenti aggiornati, aggiunge il fill publisher, inserisce il blocco skip tra filtro cutoff e `upsertListings`. Non ristrutturare altro.

- [ ] **Step 4: Verifica GREEN**

Run: `node node_modules\vitest\vitest.mjs run test\run.roundups.test.ts test\run.test.ts test\run.monitor.test.ts`
Atteso: PASS tutti (i test news preesistenti DEVONO restare verdi: se falliscono, hai toccato il comportamento news — sbagliato, correggi `run.ts`, non i test news).

- [ ] **Step 5: Commit**

```powershell
git add collector/src/run.ts collector/test/run.roundups.test.ts
git commit -m "feat: run gestisce roundups (editorial, publisher, skip re-run)"
```

---

### Task 7: Verifica completa e commit dei documenti

**Files:**
- Test: nessun file nuovo; verifica di tutti
- Commit: `docs/superpowers/specs/2026-10-22-hog-roundups-design.md`, `docs/superpowers/plans/2026-10-22-hog-roundups.md`

**Interfaces:** nessuna (task di verifica e documenti).

- [ ] **Step 1: Typecheck completo**

Run (da `collector/`): `node node_modules\typescript\bin\tsc --noEmit`
Atteso: zero errori.

- [ ] **Step 2: Suite completa**

Run: `node node_modules\vitest\vitest.mjs run`
Atteso: tutti verdi (circa 255+ test dopo i nuovi; il conteggio esatto lo si legge dall'output, non si inventa). Se falliscono test della sessione parallela (`shopify`, `normalize.date`), non sono nostri: non toccare, segnalarli all'utente e verificare che i test nostri siano verdi singolarmente.

- [ ] **Step 3: Commit dei documenti**

```powershell
git add docs/superpowers/specs/2026-10-22-hog-roundups-design.md docs/superpowers/plans/2026-10-22-hog-roundups.md
git commit -m "docs: design e piano roundups houseofgames"
```

- [ ] **Step 4: Messaggio finale all'utente**

Riporta: suite e typecheck (con numeri reali), i 7 commit, il promemoria DDL (`alter table product add column publisher text;`) da eseguire PRIMA della prima run reale, e che push/dispatch della run restano da scegliere all'utente.
