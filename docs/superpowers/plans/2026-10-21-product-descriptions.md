# Descrizione in italiano sui giochi — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Portare la descrizione in italiano dal testo delle fonti fino a `product.description`, riempiendola solo quando manca, e togliere Philibert.

**Architecture:** `descriptionRaw` esiste già negli adattatori e viene scartato in `normalize.ts`. Lo si porta su `Listing.description` (in memoria), si converte da HTML a testo, si fa confluire nel prodotto canonico e lo si scrive con un update condizionato a "colonna ancora vuota" — l'upsert non tocca mai la colonna. L'unica fonte che deve scaricare pagine è ghenos.

**Tech Stack:** Node 22, TypeScript ESM, zod, cheerio, vitest, Supabase (PostgREST), GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-21-product-descriptions-design.md`

## Global Constraints

- Comandi da eseguire con working directory `collector/`: `node node_modules\typescript\bin\tsc --noEmit` e `node node_modules\vitest\vitest.mjs run`. `npx` è bloccato dall'ExecutionPolicy.
- PowerShell 5.1: niente `&&`, usare `;` o `if ($?)`. I path con `@` o `+` vengono mangiati da git-bash.
- TypeScript ESM: import con estensione `.js`. `strict: true`, `include: ["src","test"]` → `tsc --noEmit` controlla **anche** i file di test.
- Commenti in italiano solo dove i vicini ne hanno già.
- Commit in italiano, convenzionali, senza accenti.
- Nessun push senza richiesta esplicita. Nessuna operazione sul database Supabase senza conferma dell'utente.
- Non committare il lavoro parallelo su Android / `.vite` / `collection.csv` / `.cache`.
- `SUPABASE_SERVICE_ROLE_KEY` non va mai stampata né committata.

## Review Focus

1. **Descrizione che arriva nel database ancora come markup HTML** (shopify `body_html`, wordpress `content.rendered` sono HTML) → Task 1, test `htmlToText rimuove i tag e decodifica le entità`.
2. **Una descrizione vuota o di soli spazi che si appicca alla colonna e blocca per sempre il riempimento** → Task 2, test `non scrive la descrizione quando il prodotto canonico non ce l'ha`.
3. **Una descrizione già salvata che ne viene sovrascritta da un'altra fonte** (è l'invariance centrale e PostgREST non la garantisce da solo) → Task 2, test `fillProductDescription aggiorna solo la colonna ancora vuota`.
4. **Il testo generato da Gemini che entra in `product.description`** (google-news è abilitata e gira per ultima nel seed) → Task 2, test `non scrive la descrizione di una fonte news`.
5. **Su ghenos la data di uscita presa dalla data di pubblicazione del WP** (è la data in cui il negozio ha pubblicato l'articolo, non l'uscita del gioco) → Task 6, test `prende la data dal Day one e non dal campo date del WP`.

---

### Task 1: Dal testo sorgente alla descrizione del prodotto canonico

**Files:**
- Create: `collector/src/text.ts`
- Modify: `collector/src/types.ts:33-44` (`Listing`)
- Modify: `collector/src/normalize.ts:103-116` (`normalizeListing`)
- Modify: `collector/src/merge.ts:21-47` (`canonicalizeProduct`)
- Modify: `collector/src/run.ts:55-67` (mapping delle listing dal DB)
- Modify: `collector/src/adapters/wordpress.ts:20-29` (`decodeHtml` si sposta in `text.ts`)
- Test: `collector/test/normalize.test.ts`, create `collector/test/merge.test.ts`

**Interfaces:**
- Consuma: `RawProduct.descriptionRaw: string | undefined` (`types.ts:14`).
- Produce: `htmlToText(html?: string): string | null`; `Listing.description: string | null`; `canonicalizeProduct` restituisce `Listing` con `description` valorizzata dalla prima listing che ne ha una.

- [ ] **Step 1: Scrivere i test che falliscono**

In `test/normalize.test.ts`:

```ts
test('htmlToText rimuove i tag e decodifica le entità', () => {
  expect(htmlToText('<p>Salpa per i sette mari!</p><p>Un gioco di <strong>esplorazione</strong>.</p>'))
    .toBe('Salpa per i sette mari! Un gioco di esplorazione.')
  expect(htmlToText('Ghenos&#039; gioco &amp; carta')).toBe("Ghenos' gioco & carta")
})

test('htmlToText restituisce null per testo vuoto o solo spazi', () => {
  expect(htmlToText(undefined)).toBeNull()
  expect(htmlToText('')).toBeNull()
  expect(htmlToText('<p></p>   \n')).toBeNull()
})

test('normalizeListing porta descriptionRaw nella descrizione come testo pulito', () => {
  const row = normalizeListing('cranio', {
    externalId: '1', title: 'Azul', url: 'https://e.com/a',
    descriptionRaw: '<div>Un gioco di <em>astratto</em></div>',
  })
  expect(row.description).toBe('Un gioco di astratto')
})

test('normalizeListing lascia la descrizione a null quando il sorgente non la ha', () => {
  const row = normalizeListing('cranio', { externalId: '1', title: 'Azul', url: 'https://e.com/a' })
  expect(row.description).toBeNull()
})
```

In `test/merge.test.ts`:

```ts
test('canonicalizeProduct prende la descrizione dalla prima listing che ne ha una', () => {
  const base = { sourceId: 's1', externalId: '1', title: 'Azul', url: 'https://e.com/a',
    price: null, currency: null, availability: null, releaseDate: null, imageUrl: null, gameId: null }
  const canonical = canonicalizeProduct([
    { ...base, description: null },
    { ...base, sourceId: 's2', externalId: '2', description: 'Una descrizione' },
    { ...base, sourceId: 's3', externalId: '3', description: 'Un altra' },
  ])
  expect(canonical?.description).toBe('Una descrizione')
})

test('canonicalizeProduct ha description a null quando nessuna listing la ha', () => {
  const base = { sourceId: 's1', externalId: '1', title: 'Azul', url: 'https://e.com/a',
    price: null, currency: null, availability: null, releaseDate: null, imageUrl: null, gameId: null,
    description: null }
  expect(canonicalizeProduct([base])?.description).toBeNull()
})
```

- [ ] **Step 2: Verificare che falliscano**

Run: `node node_modules\vitest\vitest.mjs run test/normalize.test.ts test/merge.test.ts`
Expected: FAIL — `htmlToText` non esiste / `merge.test.ts` non importa il modulo / `description` non esiste su `Listing`.

- [ ] **Step 3: Creare `src/text.ts` con `decodeHtml` e `htmlToText`**

Spostare il corpo di `decodeHtml` da `adapters/wordpress.ts:20-29` in `src/text.ts` (verbatim, invariato), esportarlo da lì, e aggiungere:

```ts
export function htmlToText(html?: string): string | null
```

Corpo: rimuove `<script>`/`<style>` con il loro interno, poi i restanti tag, poi `decodeHtml`, poi collassa gli spazi multipli e fa `trim`; restituisce `null` se il risultato è vuoto. Il `null` sul vuoto è il contratto che il Task 2 usa per non scrivere mai stringhe vuote.

- [ ] **Step 4: Correggere i riferimenti a `decodeHtml`**

In `adapters/wordpress.ts` sostituire la definizione con `import { decodeHtml } from '../text.js'`. Verificare con `grep -r "decodeHtml" collector/src` che non restino duplicati.

- [ ] **Step 5: Estendere `Listing` e collegare `normalize` e `merge`**

- `types.ts`: a `Listing` aggiungere `description: string | null` come **campo obbligatorio**, dopo `releaseDate`.
- `normalize.ts`: importare `htmlToText` da `./text.js` e impostare `description: htmlToText(raw.descriptionRaw)` in `normalizeListing`.
- `merge.ts`: calcolare `const description = chooseCanonicalField(listings.map((l) => l.description), (v) => !!v) ?? null` e aggiungerlo all'oggetto restituito, con lo stesso stile delle righe 26-33.
- `run.ts:56-67`: nel literal costruito dalle righe del database aggiungere `description: null`.

- [ ] **Step 6: Correggere con il compilatore tutti i literal di `Listing`**

Run: `node node_modules\typescript\bin\tsc --noEmit`
Expected: errore su ogni literal `Listing` che manca di `description` (nei test e nelle fixture). Aggiungere `description: null` a ciascuno. Ricontrollare fino a `exit 0`.

- [ ] **Step 7: Verificare che i test passino**

Run: `node node_modules\vitest\vitest.mjs run`
Expected: tutti PASS. Poi `node node_modules\typescript\bin\tsc --noEmit` → exit 0.

- [ ] **Step 8: Commit**

```
git add collector/src/text.ts collector/src/types.ts collector/src/normalize.ts collector/src/merge.ts collector/src/run.ts collector/src/adapters/wordpress.ts collector/test/normalize.test.ts collector/test/merge.test.ts
git commit -m "feat(collector): porta la descrizione dalla sorgente al prodotto canonico"
```

---

### Task 2: Colonna, write-once ed esclusione delle notizie

**Files:**
- Create: `collector/supabase/migrations/0007_add_product_description.sql`
- Modify: `collector/src/db.ts:18-28` (`ListingStore`) e implementazione
- Modify: `collector/src/run.ts:43-89`
- Test: `collector/test/db.test.ts`, `collector/test/run.test.ts`

**Interfaces:**
- Consuma: `Listing.description: string | null` (Task 1); `canonical.description`.
- Produce: `ListingStore.fillProductDescription(productId: number, description: string): Promise<void>`.

- [ ] **Step 1: Scrivere i test che falliscono**

In `test/db.test.ts`:

```ts
test('fillProductDescription aggiorna solo la colonna ancora vuota', async () => {
  const is = vi.fn().mockReturnValue({ error: null })
  const eq = vi.fn(() => ({ is }))
  const update = vi.fn(() => ({ eq }))
  const from = vi.fn(() => ({ update }))
  const store = createSupabaseStore({ from } as any, () => '2026-10-21T00:00:00Z')
  await store.fillProductDescription(7, 'Una descrizione')
  expect(from).toHaveBeenCalledWith('product')
  expect(update.mock.calls[0][0]).toEqual({ description: 'Una descrizione' })
  expect(eq).toHaveBeenCalledWith('id', 7)
  expect(is).toHaveBeenCalledWith('description', null)
})
```

In `test/run.test.ts` aggiungere `fillProductDescription: vi.fn()` ai cinque mock `store` del file (righe 13, 38, 67, 89, 122 — sono gli unici della suite; `run.monitor.test.ts` non ne costruisce), poi:

```ts
test('chiama fillProductDescription con la descrizione del prodotto canonico', async () => {
  const store = {
    upsertListings: vi.fn().mockResolvedValue(undefined),
    findListingsByTitle: vi.fn().mockResolvedValue([]),
    upsertProducts: vi.fn().mockResolvedValue([7]),
    fillProductDescription: vi.fn().mockResolvedValue(undefined),
    linkListingToProduct: vi.fn().mockResolvedValue(undefined),
    markSourceScanned: vi.fn(), logRun: vi.fn(),
    purgeStale: vi.fn().mockResolvedValue({ listings: 0, products: 0 }),
  } as any
  const adapters = { shopify: async () => [{ externalId: '1', title: 'A', url: 'https://e.com/a',
    descriptionRaw: '<p>Una descrizione</p>' }], woocommerce: async () => [], wordpress: async () => [],
    html: async () => [], headless: async () => [] } as any
  await runCollector({ sources: [src('s1', 'shopify')], adapters, store })
  expect(store.fillProductDescription).toHaveBeenCalledWith(7, 'Una descrizione')
})

test('non scrive la descrizione quando il prodotto canonico non ce l\'ha', async () => {
  // stesso store: descriptionRaw assente su tutti i campi in ingresso
  // assert: expect(store.fillProductDescription).not.toHaveBeenCalled()
})

test('non scrive la descrizione di una fonte news', async () => {
  // adapters.news? no: la fonte ha kind 'news', quindi serve un adapter registrato per quel kind
  // sources: [src('sN', 'news')]; adapters.news restituisce { descriptionRaw: 'Una frase dal titolo' }
  // assert: expect(store.fillProductDescription).not.toHaveBeenCalled()
})
```

Gli ultimi due casi vanno scritti completi seguendo lo stampo del primo: stesso store mock, stessa struttura di `adapters`, una sola differenza nei dati in ingresso.

- [ ] **Step 2: Verificare che falliscano**

Run: `node node_modules\vitest\vitest.mjs run test/db.test.ts test/run.test.ts`
Expected: FAIL — `fillProductDescription` non esiste sullo store.

- [ ] **Step 3: Creare la migrazione 0007**

`collector/supabase/migrations/0007_add_product_description.sql`:

```sql
-- 0007_add_product_description.sql

alter table product
  add column if not exists description text;
```

- [ ] **Step 4: Implementare `fillProductDescription` su `ListingStore`**

In `db.ts`, nell'interfaccia e nell'implementazione:

```ts
fillProductDescription(productId: number, description: string): Promise<void>
```

Corpo: `client.from('product').update({ description }).eq('id', productId).is('description', null)`, con il solito `throw new Error(\`fillProductDescription: ${error.message}\`)`.

**L'upsert dei prodotti non va toccato**: `upsertProducts` non mette `description` nel payload, quindi sul conflitto la colonna resta intatta. Chiave assente = colonne non toccate da `ON CONFLICT DO UPDATE`. L'unico scrittore è questo update condizionato.

Poiché `htmlToText` restituisce `null` sui vuoti, `null` è l'unico stato "mancante" e `.is('description', null)` basta.

- [ ] **Step 5: Collegare in `run.ts` ed escludere news e gemini**

In `run.ts`, a livello di modulo:

```ts
const GENERATED_TEXT_KINDS: ReadonlySet<SourceKind> = new Set(['news', 'gemini'])
```

Nella catena che costruisce `rows`, subito dopo `normalizeListing`, inserire un passaggio che azzera `description` quando `source.kind` è in `GENERATED_TEXT_KINDS`. Va fatto **dopo** `normalizeListing`, non sul campo `descriptionRaw`: `normalize.ts:112` continua a leggere `descriptionRaw` per estrarre la data e deve continuare a funzionare.

Poi, dopo `const productIds = await store.upsertProducts([canonical])`:

```ts
if (canonical.description) await store.fillProductDescription(productId, canonical.description)
```

- [ ] **Step 6: Verificare che i test passino**

Run: `node node_modules\vitest\vitest.mjs run`
Expected: tutti PASS. Poi `node node_modules\typescript\bin\tsc --noEmit` → exit 0.

Se il secondo caso (`non scrive la descrizione quando il prodotto canonico non ce l'ha`) fallisce, il guard di `run.ts` è sbagliato o `htmlToText` sta lasciando stringhe vuote.

- [ ] **Step 7: Commit**

```
git add collector/supabase/migrations/0007_add_product_description.sql collector/src/db.ts collector/src/run.ts collector/test/db.test.ts collector/test/run.test.ts
git commit -m "feat(collector): salva la descrizione sul prodotto solo quando manca"
```

- [ ] **Step 8: Blocco dipendente dall'utente**

La migrazione 0007 va applicata su Supabase **prima** che il collector con il nuovo codice giri in produzione: senza la colonna, `fillProductDescription` va in errore e fa fallire il giro. Chiedere all'utente di applicarla. Non committare né eseguire nulla finché non lo conferma.

---

### Task 3: Fallback su `short_description` per WooCommerce

**Files:**
- Modify: `collector/src/adapters/woocommerce.ts:8` (tipo) e `:41` (`descriptionRaw`)
- Test: `collector/test/adapters/woocommerce.test.ts`

**Interfaces:**
- Consuma: `WooProduct.description?: string`.
- Produce: nessun cambio di interfaccia: `descriptionRaw` resta `string | undefined`.

- [ ] **Step 1: Scrivere il test che fallisce**

In `test/adapters/woocommerce.test.ts`, con il pattern di fixture già usato nel file (`woocommerce-products.json` o un payload inline):

```ts
test('description vuota cade sul short_description', async () => {
  // un prodotto con description: '' e short_description: '<p>Una frase breve</p>'
  // assert: (await adapter(...))[0].descriptionRaw).toBe('<p>Una frase breve</p>')
})
```

- [ ] **Step 2: Verificare che fallisca**

Run: `node node_modules\vitest\vitest.mjs run test/adapters/woocommerce.test.ts`
Expected: FAIL — arriva `descriptionRaw: ''`.

- [ ] **Step 3: Implementare il fallback**

Aggiungere `short_description?: string` al tipo del prodotto e impostare `descriptionRaw: p.description || p.short_description`. L'`||` basta: una stringa vuota o di soli spazi cade sul fallback, e se non c'è nemmeno quello `htmlToText` restituisce `null`.

- [ ] **Step 4: Verificare che passi**

Run: `node node_modules\vitest\vitest.mjs run test/adapters/woocommerce.test.ts`
Expected: PASS. Poi la suite intera.

- [ ] **Step 5: Commit**

```
git add collector/src/adapters/woocommerce.ts collector/test/adapters/woocommerce.test.ts
git commit -m "fix(collector): msedizioni usa short_description quando description e vuota"
```

---

### Task 4: Descrizione nelle tre fonti html con extract custom

**Files:**
- Modify: `collector/src/adapters/html/dvgiochi.ts:68`
- Modify: `collector/src/adapters/html/horribleguild.ts:44`
- Modify: `collector/src/adapters/html/gateongames.ts`
- Test: `collector/test/adapters/specs-publishers.test.ts`

**Interfaces:**
- Consuma: `buildProduct({ title, url, ..., description })` (`html/extract.ts:55-62`), che alimenta `descriptionRaw`.
- Produce: nessun cambio di interfaccia.

Selettori verificati sulle pagine reali il 2026-10-21 — vanno replicati nella fixture, non reinventati:

- **dvgiochi** — `div.col-sm-30.col-md-30.lp40.rp40.tm20[style*="font-size:1.3em"]`: contiene prima la tagline (`Salpa per i sette mari!`), poi il paragrafo vero (~290 car.), poi dentro `.orange` l'elenco dei componenti. La fixture deve contenere quelle tre parti e il test deve **sia** che il testo del paragrafo ci sia **sia** che l'elenco dei componenti non ci sia. Attenzione: il markup è malformato (`<i><p>` dentro `<p>`), cheerio lo riannida.
- **horribleguild** — `#tab-description` (l'intero tab WooCommerce, ~1284 car. Elementor). Contiene un `<h2>Description</h2>` in testa: va tolto prima di leggere il testo. Fallback su `.woocommerce-product-details__short-description`, che però contiene le righe Designer/Artist con immagini: usarlo solo se il tab manca.
- **gateongames** — `div.e-col.ms-12.tr-7` contiene `<h2>Scopri X</h2>` seguito dai `<p>` di prosa (~508 car. nel primo). Il testo è span soup da export Word: `$(el).text()` lo risolve da solo. Regola: prendere i `p` **fino al prossimo `h2`**, non tutti quelli del div — oltre il secondo heading ci stanno espansioni e componenti.

- [ ] **Step 1: Scrivere i tre test che falliscono**

In `test/adapters/specs-publishers.test.ts`, uno per fonte, tutti nello stile di quelli già presenti (`HTML_SPECS.x.extract?.(html, url)`):

```ts
test('dvgiochi extract legge la descrizione dal blocco di testo, senza l\'elenco dei componenti', () => {})
test('horribleguild extract legge la descrizione dal tab Description', () => {})
test('gateongames extract legge la prosa che segue il titolo Scopri', () => {})
```

Ogni fixture è uno scarto della pagina reale con le tre parti descritte sopra. Le assert dicono cosa deve esserci e cosa no.

- [ ] **Step 2: Verificare che falliscano**

Run: `node node_modules\vitest\vitest.mjs run test/adapters/specs-publishers.test.ts`
Expected: FAIL — `descriptionRaw` è `undefined` sui tre.

- [ ] **Step 3: Implementare i tre selettori**

Passare `description` a `buildProduct` in ciascuno dei tre `extract`. La regola per gateongames (p fino al prossimo h2) è l'unico pezzo che richiede un algoritmo: iterare i figli del div, tenere in un array i `p`, fermarsi al secondo `h2`.

- [ ] **Step 4: Verificare che passino**

Run: `node node_modules\vitest\vitest.mjs run test/adapters/specs-publishers.test.ts`
Expected: PASS — i test precedenti del file (discover, prezzi) restano verdi. Poi la suite intera + `tsc`.

- [ ] **Step 5: Commit**

```
git add collector/src/adapters/html/dvgiochi.ts collector/src/adapters/html/horribleguild.ts collector/src/adapters/html/gateongames.ts collector/test/adapters/specs-publishers.test.ts
git commit -m "feat(collector): estrae la descrizione da dv games, horrible guild e gateongames"
```

---

### Task 5: Fallback `itemprop` nell'estrattore condiviso

**Files:**
- Modify: `collector/src/adapters/html/extract.ts:135-139` (`extractProduct`)
- Test: `collector/test/adapters/extract.test.ts`

**Interfaces:**
- Consuma: `extractProduct(html: string, pageUrl: string): RawProduct | null`.
- Produce: nessun cambio di firma.

Motivo: dungeondice espone la descrizione buona (1512 car.) in `<div itemprop="description">` e un `og:description` di 174 car. che è testo copiato identico su ogni prodotto. Oggi vince l'OpenGraph.

- [ ] **Step 1: Scrivere il test che fallisce**

In `test/adapters/extract.test.ts`, con HTML che ha `og:title` **e** `og:description` breve **e** un `div.itemprop="description"` lungo:

```ts
test('itemprop description vince sull og:description quando e piu lungo', () => {})
```

E il caso limite che tiene in piedi l'anti-obiettivo "testo copiato":

```ts
test('senza itemprop resta l og:description', () => {})
```

E l'anti-obiettivo del design §7 sulle stringhe da scheda categoria: `<meta name="description">` **non va letto**. La riga `extract.ts:120` cerca `meta[name="${property}"]` con `property = 'og:description'`, quindi il tagline in `<meta name="description">` oggi non entra — il test lo blocca perché un'aggiunta ingenua di un fallback lo farebbe entrare al posto del testo vero:

```ts
test('non usa il meta name description da tre righe', () => {})
// fixture: og:title presente, nessun og:description, nessun itemprop,
//          <meta name="description" content="Acquista X. Scopri tutte le offerte">
// assert: descriptionRaw resta undefined
```

- [ ] **Step 2: Verificare che falliscano**

Run: `node node_modules\vitest\vitest.mjs run test/adapters/extract.test.ts`
Expected: FAIL — vince l'`og:description`.

- [ ] **Step 3: Implementare il fallback**

In `extractProduct`, dopo aver ottenuto il prodotto da JSON-LD oppure OpenGraph: se manca `descriptionRaw` oppure è più corto del testo di `$('div[itemprop="description"]')` (e quel testo non è vuoto), rimpiazzarlo. Fare l'override **solo** in `extractProduct`, non in `fromJsonLd`: un JSON-LD dichiarato va rispettato.

- [ ] **Step 4: Verificare che passino**

Run: `node node_modules\vitest\vitest.mjs run test/adapters/extract.test.ts`
Expected: PASS. Poi la suite intera.

- [ ] **Step 5: Commit**

```
git add collector/src/adapters/html/extract.ts collector/test/adapters/extract.test.ts
git commit -m "feat(collector): preferisce la descrizione itemprop a quella copiata in og"
```

---

### Task 6: Ghenos — data e descrizione dalla pagina prodotto

**Files:**
- Modify: `collector/src/adapters/wordpress.ts`
- Test: `collector/test/adapters/wordpress.test.ts`

**Interfaces:**
- Consuma: `fetchWordPressProducts(baseUrl, http, postType, permalinkBase)`, `RawProduct.releaseDateRaw`, `htmlToText`.
- Produce: `parseGhenosProductPage(html: string): { descriptionRaw?: string; releaseDateRaw?: string }` esportata per il test.

Struttura reale, verificata su 25 pagine su 25 (2026-10-21). **Un solo blocco** contiene data e descrizione:

```html
<div class="ae-element-custom-field" download="">
  <p>Day one 27 novembre</p>
  <p>Seleziona i vini migliori e affinali...</p>
  <p>Il gioco si svolge nel corso di otto aste...</p>
  ...
</div>
```

Primo `<p>` = data, i successivi = descrizione. `og:description` e `meta description` non ci sono, il JSON-LD Product non c'è.

- [ ] **Step 1: Scrivere i test che falliscono**

In `test/adapters/wordpress.test.ts`, con due fixture inline (una con il blocco, una senza):

```ts
test('prende la data dal Day one e non dal campo date del WP', () => {
  const page = '<div class="ae-element-custom-field" download=""><p>Day one 27 novembre</p><p>Descrizione del gioco.</p></div>'
  const parsed = parseGhenosProductPage(page)
  expect(parsed.releaseDateRaw).toBe('Day one 27 novembre')
})

test('descrizione dai paragrafi successivi alla data', () => {
  // stessa fixture: expect(parsed.descriptionRaw).toBe('Descrizione del gioco.')
})

test('senza il blocco restituisce campi vuoti e si cade sul contenuto dell API', () => {
  expect(parseGhenosProductPage('<div class="entry-content"><p>Altro testo</p></div>'))
    .toEqual({})
})
```

Un secondo test che fissa il vincolo della Review Focus 5, esercitato a monte su `fetchWordPressProducts` con un `http` finto (stesso pattern dei test esistenti del file): la `RawProduct` in uscita ha `releaseDateRaw` dal testo e **non** reca la data WP in `releaseDateRaw`. Poiché `normalize.ts` non legge mai `publishedAtRaw`, il test vero è che `releaseDateRaw` sia valorizzato dalla pagina.

- [ ] **Step 2: Verificare che falliscano**

Run: `node node_modules\vitest\vitest.mjs run test/adapters/wordpress.test.ts`
Expected: FAIL — `parseGhenosProductPage` non esiste.

- [ ] **Step 3: Implementare `parseGhenosProductPage`**

Con cheerio: se `.ae-element-custom-field` non c'è, restituire `{}`. Altrimenti leggere i `<p>` del blocco in ordine: il primo diventa `releaseDateRaw` **solo se inizia con `Day one`**, gli altri vengono uniti con uno spazio e passati a `htmlToText`.

Attenzione al tipo: `htmlToText` restituisce `string | null` mentre `descriptionRaw` è `string | undefined` — serve `?? undefined`, altrimenti `tsc` si ferma qui. Stessa cosa per `releaseDateRaw`, che va lasciato fuori dall'oggetto quando il primo paragrafo non è una riga `Day one`.

Il filtro `Day one` serve perché su quei pochi prodotti dove il WP ha già il contenuto il primo paragrafo potrebbe non essere la data.

- [ ] **Step 4: Collegare il fetch nel ciclo di api**

In `fetchWordPressProducts`, per ogni post: fare `await http(p.link, { 'User-Agent': USER_AGENT })`, e in un `try/catch` che **non** fa fallire l'intero post in caso di errore di rete, passare l'HTML a `parseGhenosProductPage`. Poi nel `RawProductSchema.safeParse`:

- `descriptionRaw: pagina.descriptionRaw || p.content?.rendered` — la pagina vince, il contenuto dell'API è il ripiego (sui 412 prodotti: 196 hanno l'API vuota, su 216 la pagina dà comunque il `Day one` che l'API non ha).
- `releaseDateRaw: pagina.releaseDateRaw`

Se il fetch della pagina fallisce, usare solo i campi dell'API come oggi.

`ghenos` è l'unica fonte con `kind: 'wordpress'`, quindi il selettori specifico di Elementor/AE può stare lì dentro senza bisogno di un flag: la presenza del blocco fa da guarda.

- [ ] **Step 5: Verificare che passino**

Run: `node node_modules\vitest\vitest.mjs run test/adapters/wordpress.test.ts`
Expected: PASS. Poi la suite intera + `tsc`.

Nessun test deve toccare la rete.

- [ ] **Step 6: Commit**

```
git add collector/src/adapters/wordpress.ts collector/test/adapters/wordpress.test.ts
git commit -m "feat(collector): legge data e descrizione di ghenos dalla pagina prodotto"
```

---

### Task 7: Togliere Philibert dal codice

**Files:**
- Modify: `collector/src/sources.seed.ts:14`
- Modify: `collector/src/adapters/html/specs.ts` (registrazione)
- Delete: `collector/src/adapters/html/philibert.ts`
- Modify: `collector/test/sources.test.ts:22`
- Modify: `collector/test/adapters/specs-crawl.test.ts` (il caso philibert, primo del file)
- Test: nessun test nuovo

**Interfaces:**
- Produce: nessun cambio di interfaccia.

- [ ] **Step 1: Trovare tutti i riferimenti**

Run: `Select-String -Path "collector\src\*.ts","collector\src\adapters\*.ts","collector\src\adapters\html\*.ts","collector\test\*.ts","collector\test\adapters\*.ts" -Pattern "philibert"`
Expected: elenco completo. Ogni occorrenza va rimossa o aggiornata in questi step.

- [ ] **Step 2: Rimuovere il codice**

Cancellare la riga del seed, la registrazione in `specs.ts` e il file `philibert.ts`.

- [ ] **Step 3: Aggiornare i test che lo richiamano**

`sources.test.ts:22` confronta l'elenco completo degli id abilitati: `'philibert'` va tolto dalla riga 22. In `specs-crawl.test.ts` va rimosso il caso `philibert collects product links from its new-products listing` e il relativo import.

- [ ] **Step 4: Verificare**

Run: `node node_modules\typescript\bin\tsc --noEmit` → exit 0.
Run: `node node_modules\vitest\vitest.mjs run` → tutti PASS.

- [ ] **Step 5: Commit**

```
git add -A collector/src collector/test
git commit -m "feat(collector): rimuove philibert dalle fonti"
```

---

### Task 8: Cancellare i dati di Philibert (SQL, eseguito dall'utente)

**Files:**
- Nessun file. Operazione sul database Supabase, fatta a mano come la pulizia del ciclo di vita.

**Interfaces:**
- Consuma: i conteggi verificati il 2026-10-21 con `service_role`.

- [ ] **Step 1: Rileggere i conteggi subito prima**

Con `service_role`, mai con l'anon key (la RLS su `listing` e `user_action` restituisce righe vuote **senza errore**):

- `listing` con `source_id = 'philibert'` → atteso **277**
- `user_action` `action = 'favorite'` su quelle listing → atteso **0**

Se i numeri non sono più quelli, fermarsi e riportarli all'utente prima di procedere.

- [ ] **Step 2: Mostrare i numeri e chiedere conferma**

Nessuna cancellazione senza conferma esplicita. Il SQL va mostrato prima di eseguirlo.

- [ ] **Step 3: Eseguire nell'ordine**

```sql
-- 1. le listing
delete from listing where source_id = 'philibert';

-- 2. i prodotti che a questo punto non hanno piu nessuna listing che li punti,
--    e verso cui non punta nessuna azione dell'utente
delete from product p
where not exists (select 1 from listing l where l.product_id = p.id)
  and not exists (select 1 from user_action ua where ua.product_id = p.id);

-- 3. la riga della fonte, cosi l'app non la mostra piu come abilitata
delete from source where id = 'philibert';
```

L'ordine conta: le listing prima, perché è quello che rende orfani i prodotti. Il secondo `not exists` protegge un prodotto preferito; il primo non cancella mai un prodotto che un'altra fonte sta ancora referenziando.

- [ ] **Step 4: Verificare il risultato**

Conteggi attesi dopo: `source` senza `philibert`; `listing` con `source_id = 'philibert'` = 0; nessun `product` senza `listing` che non fosse già in quello stato prima (sono le 2341 listing senza prodotto, non i prodotti orfani).

- [ ] **Step 5: Commit del solo eventuale allineamento documentale**

Se dalla rimozione emergono allineamenti da fare nei documenti, committarli con:

```
git commit -m "docs: allinea il design alla rimozione di philibert"
```
