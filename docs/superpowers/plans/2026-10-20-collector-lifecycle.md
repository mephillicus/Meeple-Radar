# Collector Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Il collector gira una volta a settimana e tiene il database entro una finestra scorrevole di 365 giorni, senza cancellare mai i preferiti.

**Architecture:** Un helper puro calcola la data di taglio. `run.ts` scarta in ingresso i prodotti datati oltre la finestra e, all'apertura, chiama un nuovo metodo `purgeStale` su `db.ts` che cancella le listing vecchie (datate o non viste da un anno) tranne le preferite, e poi i prodotti datati che nessuna listing sopravvissuta referenzia. Una migrazione rende le foreign key di `user_action` a `ON DELETE CASCADE`, altrimenti i DELETE vengono rifiutati e il giro abortisce.

**Tech Stack:** Node 22, TypeScript ESM, vitest, `@supabase/supabase-js` 2.x (PostgREST), GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-20-collector-lifecycle-design.md`

## Global Constraints

- Lingua italiana: commenti e messaggi commit in italiano, senza accenti.
- Node 22 / TypeScript ESM: gli import finiscono con `.js`.
- Comandi di verifica, da `collector/`:
  - `node node_modules\typescript\bin\tsc --noEmit`
  - `node node_modules\vitest\vitest.mjs run`
- Commenti solo dove i vicini ne hanno già.
- Nessun push senza richiesta esplicita.
- Costante di retention: **365 giorni**. Soglia = `oggi - 365 giorni`, finestra scorrevole, non legata all'anno solare.
- Le righe con `user_action.action = 'favorite'` **non** vengono cancellate mai.
- `user_action` con `action = 'ignored'` viene cancellato insieme alla sua riga (via `ON DELETE CASCADE`).
- Un prodotto si cancella solo se **nessuna listing sopravvissuta** punta al suo `listing.product_id`.
- Un prodotto senza `release_date` **non** viene cancellato: la regola di prodotto usa solo la data del prodotto.
- `listing.release_date` è `date`, `listing.last_seen_at` è `timestamptz`. Un confronto `.lt('last_seen_at', 'YYYY-MM-DD')` in PostgREST interpreta la stringa come mezzanotte UTC: va bene, è la stessa convenzione usata per `release_date`.
- Nessuna modifica all'app Android.

## Review Focus

Cinque modi in cui una persona ragionevole si aspetterebbe un comportamento diverso da quello che il codice produrrà:

1. **Il DELETE viene rifiutato dalla foreign key e il sabato intero sparisce silenziosamente.** Se la migrazione 0006 non è stata applicata, `purgeStale` lancia e `runCollector` registra il fallimento senza aver raccolto niente. Il test che lo pinned è in Task 3: il metodo deve escludere le preferite **prima** di chiamare `delete`, così non esiste un caso in cui si cancelli una riga con un `favorite` appiccicato.
2. **Un prodotto ancora referenziato da una listing viva fa abortire il DELETE del prodotto.** Il test in Task 3 che conta: `purgeStale` calcola i prodotti da cancellare **dopo** aver cancellato le listing, e sottrae quelli che compaiono ancora in `listing.product_id`.
3. **Una listing senza data ma ancora fresca viene cancellata.** La regola è `release_date IS NULL AND last_seen_at < before`: il test in Task 3 deve coprire esplicitamente il caso `release_date: null, last_seen_at` recente, che deve sopravvivere.
4. **Il taglio si sposta per fuso orario e cancella una riga in più o in meno.** `retentionCutoff` riceve `now` come parametro esplicito (Task 1); il test passa una data fissa e confronta con una stringa attesa, non con `new Date()`.
5. **Una fonte che restituisce zero fa fallire il job.** Con un giro a settimana, philibert che un sabato non risponde costerebbe il giro intero. Il test in Task 2 deve coprire `main`'s exit path: `report.ok` resta vero quando `zeroed` non è vuoto. (Il resto del report resta invariato.)

---

### Task 1: Soglia di retention

**Files:**
- Create: `collector/src/retention.ts`
- Test: `collector/test/retention.test.ts`

**Interfaces:**
- Consumes: nulla.
- Produces: `RETENTION_DAYS: number` (valore `365`) e `retentionCutoff(now?: Date): string` che restituisce una data ISO `YYYY-MM-DD`. Task 2 e Task 3 la importano da `./retention.js`.

- [ ] **Step 1: Scrivi il test che fallisce**

```ts
import { expect, test } from 'vitest'
import { RETENTION_DAYS, retentionCutoff } from '../src/retention.js'

test('la finestra è di 365 giorni', () => {
  expect(RETENTION_DAYS).toBe(365)
})

test('taglia 365 giorni indietro rispetto a now', () => {
  expect(retentionCutoff(new Date('2026-10-20T12:00:00Z'))).toBe('2025-10-20')
})

test('attraversa anno bisestile e cambio di mese', () => {
  // 2024 e 2025 sono bisestili: il giorno extra cade dentro la finestra,
  // quindi 365 indietro da 2024-03-01 finisce sul 2 marzo, non sul 1.
  expect(retentionCutoff(new Date('2024-03-01T00:00:00Z'))).toBe('2023-03-02')
  expect(retentionCutoff(new Date('2025-01-01T00:00:00Z'))).toBe('2024-01-02')
})
```

- [ ] **Step 2: Esegui il test e verifica che fallisca**

Run: `node node_modules\vitest\vitest.mjs run test/retention.test.ts`
Expected: FAIL, il modulo `../src/retention.js` non esiste.

- [ ] **Step 3: Implementa `retentionCutoff(now: Date = new Date()): string` in `collector/src/retention.ts`**

Il corpo è la sottrazione di giorni sul campo UTC e il taglio ai primi 10 caratteri dell'ISO. Niente `toLocaleDateString`, niente fuso locale.

```ts
export const RETENTION_DAYS = 365

export function retentionCutoff(now: Date = new Date()): string {
  const cutoff = new Date(now.getTime())
  cutoff.setUTCDate(cutoff.getUTCDate() - RETENTION_DAYS)
  return cutoff.toISOString().slice(0, 10)
}
```

- [ ] **Step 4: Esegui il test e verifica che passi**

Run: `node node_modules\vitest\vitest.mjs run test/retention.test.ts`
Expected: PASS, 3 test.

- [ ] **Step 5: Esegui `tsc` e i test completi**

Run: `node node_modules\typescript\bin\tsc --noEmit` poi `node node_modules\vitest\vitest.mjs run`
Expected: `tsc` exit 0; tutti i test passano (erano 152, diventano 155).

- [ ] **Step 6: Commit**

```bash
git add collector/src/retention.ts collector/test/retention.test.ts
git commit -m "feat(collector): aggiungi la soglia di retention a 365 giorni"
```

---

### Task 2: Scarto in ingresso e codice di uscita

**Files:**
- Modify: `collector/src/run.ts:20-28` (firma), `:35-36` (smistamento), `:92` (exit code)
- Modify: `collector/src/run.ts:13-18` (`RunReport`)
- Test: `collector/test/run.test.ts`

**Interfaces:**
- Consumes: `retentionCutoff` da `./retention.js` (Task 1).
- Produces: `runCollector` accetta un campo opzionale `now?: () => Date`. `RunReport` ha un campo `purged?: { listings: number; products: number }`. Task 3 implementa `store.purgeStale`.

- [ ] **Step 1: Estendi il fake dello store e scrivi i test che falliscono**

Nel file `collector/test/run.test.ts`, aggiungi `purgeStale: vi.fn().mockResolvedValue({ listings: 0, products: 0 })` all'oggetto `store` esistente (Task 2 lo chiama all'apertura, altrimenti il test esistente va in errore).

Aggiungi poi due test:

```ts
test('scarta in ingresso i prodotti datati oltre la finestra', async () => {
  const store = {
    upsertListings: vi.fn().mockResolvedValue(undefined),
    findListingsByTitle: vi.fn().mockResolvedValue([]),
    upsertProducts: vi.fn().mockResolvedValue([1]),
    linkListingToProduct: vi.fn().mockResolvedValue(undefined),
    markSourceScanned: vi.fn(),
    logRun: vi.fn(),
    purgeStale: vi.fn().mockResolvedValue({ listings: 0, products: 0 }),
  } as any
  const adapters = {
    shopify: async () => [
      { externalId: '1', title: 'Vecchio', url: 'https://e.com/1' },
      { externalId: '2', title: 'Fresco', url: 'https://e.com/2' },
    ],
    woocommerce: async () => [], wordpress: async () => [], html: async () => [],
    headless: async () => [],
  } as any
  await runCollector({
    sources: [src('s1', 'shopify')],
    adapters,
    store,
    now: () => new Date('2026-10-20T00:00:00Z'),
  })
  const written = store.upsertListings.mock.calls[0][0]
  expect(written).toHaveLength(1)
  expect(written[0].title).toBe('Fresco')
})
```

Serve un adattatore di appoggio perché `normalizeListing` legge la data da `releaseDateRaw`, non dal titolo. Aggiungi a `adapters`:

```ts
shopify: async () => [
  { externalId: '1', title: 'Vecchio', url: 'https://e.com/1', releaseDateRaw: '2025-01-15' },
  { externalId: '2', title: 'Fresco', url: 'https://e.com/2', releaseDateRaw: '2027-03-01' },
],
```

Il secondo test copre il Review Focus 5:

```ts
test('una fonte a zero non rende il report non ok', async () => {
  const store = {
    upsertListings: vi.fn().mockResolvedValue(undefined),
    findListingsByTitle: vi.fn().mockResolvedValue([]),
    upsertProducts: vi.fn().mockResolvedValue([]),
    linkListingToProduct: vi.fn().mockResolvedValue(undefined),
    markSourceScanned: vi.fn(),
    logRun: vi.fn(),
    purgeStale: vi.fn().mockResolvedValue({ listings: 0, products: 0 }),
  } as any
  const adapters = {
    shopify: async () => [], woocommerce: async () => [], wordpress: async () => [],
    html: async () => [], headless: async () => [],
  } as any
  const report = await runCollector({
    sources: [src('s1', 'shopify')], adapters, store,
    now: () => new Date('2026-10-20T00:00:00Z'),
  })
  expect(report.zeroed).toEqual(['s1'])
  expect(report.ok).toBe(true)
})
```

- [ ] **Step 2: Esegui i test e verifica che falliscano**

Run: `node node_modules\vitest\vitest.mjs run test/run.test.ts`
Expected: FAIL — `now` non è un parametro accettato, e il primo test trova 2 righe scritte invece di 1.

- [ ] **Step 3: Modifica `runCollector` in `collector/src/run.ts`**

Aggiungi `now?: () => Date` agli argomenti, con default `() => new Date()`. Importa `retentionCutoff`. Sostituisci lo smistamento:

```ts
const cutoff = retentionCutoff(nowFn())
const rows = raw
  .map((r) => normalizeListing(source.id, r))
  .filter((row) => row.releaseDate === null || row.releaseDate >= cutoff)
```

`raw.length` resta il valore di `perSource[source.id]`: conta ciò che la fonte ha restituito, non ciò che è stato scritto. La differenza fra i due numeri è l'informazione che serve per capire se lo scarto sta funzionando.

- [ ] **Step 4: Esegui i test e verifica che passino**

Run: `node node_modules\vitest\vitest.mjs run test/run.test.ts`
Expected: PASS, 3 test.

- [ ] **Step 5: Cambia il codice di uscita in `collector/src/run.ts:92`**

```ts
return report.ok ? 0 : 1
```

Aggiungi `purged?: { listings: number; products: number }` al tipo `RunReport` e copialo dentro l'oggetto `report` costruito in `runCollector`, leggendolo da `store.purgeStale` quando Task 3 lo implementerà. Per ora: `const purged = await store.purgeStale(retentionCutoff(nowFn()))` appena prima del loop sui source, e includi `purged` nel report.

- [ ] **Step 6: Esegui `tsc` e i test completi**

Run: `node node_modules\typescript\bin\tsc --noEmit` poi `node node_modules\vitest\vitest.mjs run`
Expected: `tsc` exit 0; tutti i test passano.

- [ ] **Step 7: Commit**

```bash
git add collector/src/run.ts collector/test/run.test.ts
git commit -m "feat(collector): scarta in ingresso i prodotti oltre la finestra di retention"
```

---

### Task 3: `purgeStale` e migrazione del CASCADE

**Files:**
- Create: `collector/supabase/migrations/0006_delete_cascade.sql`
- Modify: `collector/src/db.ts:18-27` (`ListingStore`), `:142` (dopo `linkListingToProduct`)
- Test: `collector/test/db.test.ts`

**Interfaces:**
- Consumes: `retentionCutoff` già usata da `run.ts` (Task 2 la chiama con `retentionCutoff(nowFn())`).
- Produces: `purgeStale(before: string): Promise<{ listings: number; products: number }>`. La migrazione 0006 rende `user_action.listing_id` e `user_action.product_id` a `ON DELETE CASCADE`.

- [ ] **Step 1: Scrivi la migrazione in `collector/supabase/migrations/0006_delete_cascade.sql`**

```sql
-- 0006_delete_cascade.sql
-- Senza CASCADE i DELETE di listing/product vengono rifiutati da user_action
-- e l'intero giro del collector abortisce prima di raccogliere.

alter table user_action
  drop constraint if exists user_action_listing_id_fkey;
alter table user_action
  add constraint user_action_listing_id_fkey
  foreign key (listing_id) references listing(id) on delete cascade;

alter table user_action
  drop constraint if exists user_action_product_id_fkey;
alter table user_action
  add constraint user_action_product_id_fkey
  foreign key (product_id) references product(id) on delete cascade;
```

Non serve alcuna migrazione su `listing.product_id`: la regola cancella i prodotti solo quando nessuna listing li referenzia più, quindi quella foreign key non viene mai violate.

- [ ] **Step 2: Scrivi i test che falliscono in `collector/test/db.test.ts`**

Tre test. Ognuno usa lo stesso schema di fake già presente nel file: `createSupabaseStore({ from } as any)`.

Il primo copre il Review Focus 3 — la riga senza data ma fresca deve sopravvivere:

```ts
test('purgeStale scarta solo le righe datate oltre before, o senza data e non viste da before', async () => {
  const rows = [{ id: 1 }, { id: 3 }]
  const notFn = vi.fn().mockReturnValue({ lt: vi.fn().mockResolvedValue({ data: [{ id: 1 }], error: null }) })
  const isFn = vi.fn().mockReturnValue({ lt: vi.fn().mockResolvedValue({ data: [{ id: 3 }], error: null }) })
  const select = vi.fn(() => ({ not: notFn, is: isFn }))
  const deleteFn = vi.fn().mockResolvedValue({ error: null })
  const inFn = vi.fn().mockResolvedValue({ error: null })
  const productSelect = vi.fn().mockResolvedValue({ data: [], error: null })
  const productFrom = vi.fn(() => ({ select: productSelect, delete: () => ({ in: inFn }) }))
  const listingFrom = vi.fn(() => ({ select, delete: () => ({ in: inFn }) }))
  const userFrom = vi.fn(() => ({
    select: vi.fn().mockReturnValue({ eq: vi.fn().mockReturnValue({ not: vi.fn().mockResolvedValue({ data: [], error: null }) }) }),
  }))
  const from = vi.fn((table: string) =>
    table === 'product' ? productFrom() : table === 'user_action' ? userFrom() : listingFrom())
  const store = createSupabaseStore({ from } as any)
  const purged = await store.purgeStale('2025-10-20')
  expect(notFn).toHaveBeenCalledWith('release_date', 'is', null)
  expect(isFn).toHaveBeenCalledWith('release_date', null)
  expect(purged.listings).toBe(2)
  expect(inFn).toHaveBeenCalledWith('id', rows.map((r) => r.id))
})
```

Il secondo copre il Review Focus 1 e 2 — preferite escluse, prodotti referenziati non toccati:

```ts
test('purgeStale non cancella le preferite e non cancella un prodotto referenziato', async () => {
  const favorite = [{ id: 9 }]
  const staleListings = [{ id: 1 }, { id: 9 }]
  const notFn = vi.fn().mockReturnValue({ lt: vi.fn().mockResolvedValue({ data: staleListings, error: null }) })
  const isFn = vi.fn().mockReturnValue({ lt: vi.fn().mockResolvedValue({ data: [], error: null }) })
  const selectListing = vi.fn(() => ({ not: notFn, is: isFn }))
  const deleteIn = vi.fn().mockResolvedValue({ error: null })
  const listingFrom = vi.fn(() => ({ select: selectListing, delete: () => ({ in: deleteIn }) }))
  const userActionSelect = vi.fn().mockReturnValue({
    eq: vi.fn().mockReturnValue({ not: vi.fn().mockResolvedValue({ data: favorite, error: null }) }),
  })
  const userFrom = vi.fn(() => ({ select: userActionSelect }))
  const productSelect = vi.fn().mockReturnValue({
    lt: vi.fn().mockResolvedValue({ data: [{ id: 50 }], error: null }),
  })
  const productFrom = vi.fn(() => ({
    select: productSelect,
    delete: () => ({ in: vi.fn().mockResolvedValue({ error: null }) }),
  }))
  const referencedSelect = vi.fn().mockResolvedValue({ data: [{ product_id: 50 }], error: null })
  const from = vi.fn((table: string) =>
    table === 'product' ? productFrom() : table === 'user_action' ? userFrom() : listingFrom())
  // la seconda select su listing serve per referencedProductIds
  from.mockImplementation((table: string) => {
    if (table === 'product') return productFrom()
    if (table === 'user_action') return userFrom()
    return listingFrom()
  })
  const store = createSupabaseStore({ from } as any)
  const purged = await store.purgeStale('2025-10-20')
  expect(purged.listings).toBe(1)
  expect(deleteIn).toHaveBeenCalledWith('id', [1])
  expect(purged.products).toBe(0)
})
```

Il terzo copre il Review Focus 3, il caso limite delle preferite non datate:

```ts
test('purgeStale restituisce zeri senza cancellare nulla quando non c’è niente di vecchio', async () => {
  const empty = vi.fn().mockResolvedValue({ data: [], error: null })
  const notFn = vi.fn().mockReturnValue({ lt: vi.fn().mockResolvedValue({ data: [], error: null }) })
  const isFn = vi.fn().mockReturnValue({ lt: vi.fn().mockResolvedValue({ data: [], error: null }) })
  const from = vi.fn(() => ({ select: vi.fn(() => ({ not: notFn, is: isFn, lt: empty })), delete: () => ({ in: empty }) }))
  const store = createSupabaseStore({ from } as any)
  const purged = await store.purgeStale('2025-10-20')
  expect(purged).toEqual({ listings: 0, products: 0 })
})
```

- [ ] **Step 3: Esegui i test e verifica che falliscano**

Run: `node node_modules\vitest\vitest.mjs run test/db.test.ts`
Expected: FAIL — `purgeStale` non esiste su `ListingStore`.

- [ ] **Step 4: Implementa `purgeStale` in `collector/src/db.ts`**

Aggiungi `purgeStale(before: string): Promise<{ listings: number; products: number }>` all'interfaccia `ListingStore`. Implementala dentro `createSupabaseStore` come sequenza di quattro query private, nell'ordine:

1. `staleListingIds(before)` — due select su `listing`: `.select('id').not('release_date', 'is', null).lt('release_date', before)` e `.select('id').is('release_date', null).lt('last_seen_at', before)`. Concatena i due risultati.
2. `favoriteListingIds()` — `.select('listing_id').eq('action', 'favorite').not('listing_id', 'is', null)` su `user_action`.
3. `listing.delete().in('id', stale.filter((id) => !favorites.includes(id)))`. **Questo prima di tutto il resto**: i prodotti si valutano dopo che le listing sono andate, così un prodotto appena rimasto scoperto entra nel conteggio.
4. Prodotti: `product.select('id').lt('release_date', before)` sottratto a `listing.select('product_id').not('product_id', 'is', null)`, poi `product.delete().in('id', orphan)`.

Ogni `error` non nullo diventa un `throw` con il prefisso del metodo, come fanno gli altri metodi del file. Un prodotto senza `release_date` non compare nel passo 4: `.lt` esclude i null, ed è la regola scelta.

- [ ] **Step 5: Esegui i test e verifica che passino**

Run: `node node_modules\vitest\vitest.mjs run test/db.test.ts`
Expected: PASS.

- [ ] **Step 6: Esegui `tsc` e i test completi**

Run: `node node_modules\typescript\bin\tsc --noEmit` poi `node node_modules\vitest\vitest.mjs run`
Expected: `tsc` exit 0; tutti i test passano. I test di Task 2 già chiamavano `purgeStale` sul fake, quindi non cambiano.

- [ ] **Step 7: Commit**

```bash
git add collector/supabase/migrations/0006_delete_cascade.sql collector/src/db.ts collector/test/db.test.ts
git commit -m "feat(collector): cancella le righe oltre la finestra senza toccare i preferiti"
```

---

### Task 4: Cron settimanale e allineamento delle spec

**Files:**
- Modify: `.github/workflows/collect.yml:5`
- Modify: `docs/superpowers/specs/2026-10-18-gemini-collector-design.md:32` e `:36`
- Modify: `docs/superpowers/specs/2026-10-20-collector-lifecycle-design.md` (§7, le "due migrazioni" sono una)

**Interfaces:**
- Consumes: il comportamento già stabilito dai Task 1–3.
- Produces: un solo cron, sabato mattina. Niente codice.

- [ ] **Step 1: Cambia il cron in `.github/workflows/collect.yml:5`**

```yaml
    - cron: "0 6 * * 6"
```

Sabato 06:00 UTC, che alle ora italiana sono le 08:00 in suolo con ora legale. Via `0 5,17 * * *`.

- [ ] **Step 2: Aggiorna la spec del 18 ottobre**

`docs/superpowers/specs/2026-10-18-gemini-collector-design.md:32` cita ancora il cron giornaliero; sostituiscilo con il settimanale. Alla riga 36, dove dice "**Mai cancellare**: il job Gemini fa solo upsert (update in place), mai delete", riscrivilo per dire che il job Gemini continua a fare solo upsert, e che le cancellazioni sono una regola del collector presa in apertura, descritta in `2026-10-20-collector-lifecycle-design.md`.

- [ ] **Step 3: Correggi la §7 del design del 20 ottobre**

`docs/superpowers/specs/2026-10-20-collector-lifecycle-design.md:62` dice "servono due migrazioni ... e la stessa cosa su `listing.product_id` dove serve". La seconda non serve: i prodotti si cancellano solo quando nessuna listing li referenzia più. Togli quel pezzo.

- [ ] **Step 4: Verifica che non resti nessun riferimento al cron giornaliero**

Run: `rg "5,17|cron 5:00|giornaliero" docs collector .github`
Expected: nessun residuo che descriva la vecchia cadenza come attuale.

- [ ] **Step 5: Esegui `tsc` e i test completi**

Run: `node node_modules\typescript\bin\tsc --noEmit` poi `node node_modules\vitest\vitest.mjs run`
Expected: `tsc` exit 0; tutti i test passano.

- [ ] **Step 6: Commit**

```bash
git add .github/workflows/collect.yml docs/superpowers/specs/2026-10-18-gemini-collector-design.md docs/superpowers/specs/2026-10-20-collector-lifecycle-design.md
git commit -m "chore: passa il collector a un giro a settimana e allinea le spec"
```

---

### Task 5: "Riempi se manca" negli upsert

**Files:**
- Modify: `collector/src/db.ts:50-71` (`upsertListings`), `:120-140` (`upsertProducts`)
- Test: `collector/test/db.test.ts`

**Interfaces:**
- Consumes: nulla.
- Produces: gli upsert di listing e product omettono dal payload le colonne il cui valore è `null`. PostgREST genera `set col = excluded.col` solo per le colonne presenti nel payload, quindi una colonna assente preserva il valore già in database. Questo è il modo in cui "un dato mancante nel testo non azzera il campo corrispondente già salvato" diventa codice.

- [ ] **Step 1: Scrivi i test che falliscono in `collector/test/db.test.ts`**

```ts
test('upsertListings omette i campi nulli per non azzerare i valori esistenti', async () => {
  const upsert = vi.fn().mockResolvedValue({ error: null })
  const from = vi.fn(() => ({ upsert }))
  const store = createSupabaseStore({ from } as any, () => '2026-10-20T00:00:00Z')
  await store.upsertListings([
    { sourceId: 'cranio', externalId: '1', title: 'X', url: 'https://e.com/x',
      price: null, currency: null, availability: null, releaseDate: null, imageUrl: null, gameId: null },
  ])
  const payload = upsert.mock.calls[0][0][0]
  expect(payload).not.toHaveProperty('price')
  expect(payload).not.toHaveProperty('currency')
  expect(payload).not.toHaveProperty('availability')
  expect(payload).not.toHaveProperty('release_date')
  expect(payload).not.toHaveProperty('image_url')
  expect(payload).not.toHaveProperty('game_id')
  expect(payload).toMatchObject({ title: 'X', source_id: 'cranio', external_id: '1', url: 'https://e.com/x' })
})

test('upsertListings scrive i campi valorizzati, così un prezzo aggiornato prevale', async () => {
  const upsert = vi.fn().mockResolvedValue({ error: null })
  const from = vi.fn(() => ({ upsert }))
  const store = createSupabaseStore({ from } as any, () => '2026-10-20T00:00:00Z')
  await store.upsertListings([
    { sourceId: 'cranio', externalId: '1', title: 'X', url: 'https://e.com/x',
      price: 25, currency: 'EUR', availability: 'Disponibile', releaseDate: '2027-01-01', imageUrl: null, gameId: 42 },
  ])
  const payload = upsert.mock.calls[0][0][0]
  expect(payload).toMatchObject({ price: 25, currency: 'EUR', availability: 'Disponibile', release_date: '2027-01-01', game_id: 42 })
  expect(payload).not.toHaveProperty('image_url')
})

test('upsertProducts omette i campi nulli allo stesso modo', async () => {
  const upsert = vi.fn().mockResolvedValue({ error: null })
  const select = vi.fn().mockResolvedValue({ data: [], error: null })
  const from = vi.fn(() => ({ upsert: Object.assign(upsert, { select }) }))
  const store = createSupabaseStore({ from } as any, () => '2026-10-20T00:00:00Z')
  await store.upsertProducts([
    { sourceId: 's', externalId: '1', title: 'X', url: 'https://e.com/x',
      price: null, currency: null, availability: null, releaseDate: null, imageUrl: null, gameId: null },
  ])
  const payload = upsert.mock.calls[0][0][0]
  expect(payload).not.toHaveProperty('price')
  expect(payload).not.toHaveProperty('release_date')
  expect(payload).toMatchObject({ title: 'X', normalized_title: 'x' })
})
```

- [ ] **Step 2: Esegui i test e verifica che falliscano**

Run: `node node_modules\vitest\vitest.mjs run test/db.test.ts`
Expected: FAIL — i payload contengono ancora tutte le colonne, con `null` dove manca il dato.

- [ ] **Step 3: Modifica `upsertListings` e `upsertProducts` in `collector/src/db.ts`**

I campi che restano sempre presenti sono quelli `NOT NULL` dello schema più `last_seen_at`/`updated_at`. Quelli condizionali in `upsertListings` sono `price`, `currency`, `availability`, `release_date`, `image_url`, `game_id`; in `upsertProducts` sono `title`, `price`, `currency`, `release_date`, `image_url`, `game_id`. Costruisci l'oggetto spargendo la parte invariante e aggiungendo ogni campo condizionale solo se non è `null`.

Una nota di forma: in `upsertProducts` il `select('id')` va concatenato solo quando il payload non è vuoto. Un upsert con payload vuoto non ha senso e va aggirato con lo stesso `if (products.length === 0) return []` che esiste già.

- [ ] **Step 4: Esegui i test e verifica che passino**

Run: `node node_modules\vitest\vitest.mjs run test/db.test.ts`
Expected: PASS.

- [ ] **Step 5: Esegui `tsc` e i test completi**

Run: `node node_modules\typescript\bin\tsc --noEmit` poi `node node_modules\vitest\vitest.mjs run`
Expected: `tsc` exit 0; tutti i test passano.

- [ ] **Step 6: Commit**

```bash
git add collector/src/db.ts collector/test/db.test.ts
git commit -m "feat(collector): un dato mancante non azzera piu quello gia salvato"
```

---

## Fine del piano

Dopo i quattro task, la migrazione 0006 va **applicata a mano** nel SQL Editor di Supabase, come le precedenti. E la pulizia one-shot delle 2237 listing orfane è SQL a mano, non codice:

```sql
delete from listing
where product_id is null
  and id not in (select listing_id from user_action where action = 'favorite' and listing_id is not null);
```

Quella va eseguita **dopo** il primo giro settimanale andato a buon fine, così il backfill ha avuto modo di collegare le righe nuove prima che la pulizia le consideri orfane.
