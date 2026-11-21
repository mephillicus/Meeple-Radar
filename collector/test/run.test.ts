import { expect, test, vi } from 'vitest'
import { runCollector } from '../src/run.js'

const src = (id: string, kind: any) => ({ id, name: id, kind, baseUrl: 'https://e.com', enabled: true })

test('collects enabled sources and isolates a failing one', async () => {
  const store = {
    upsertListings: vi.fn().mockResolvedValue(undefined),
    // Il blocco di canonicalizzazione gira per ogni riga: serve tutto il necessario.
    findListingsByTitle: vi.fn().mockResolvedValue([
      { id: 12, title: 'A', url: 'https://e.com/a', price: null, currency: null, releaseDate: null, imageUrl: null, gameId: null, sourceId: 's1' },
    ]),
    upsertProducts: vi.fn().mockResolvedValue([7]),
    fillProductDescription: vi.fn().mockResolvedValue(undefined),
    fillProductReleaseDate: vi.fn().mockResolvedValue(undefined),
    linkListingToProduct: vi.fn().mockResolvedValue(undefined),
    markSourceScanned: vi.fn(),
    logRun: vi.fn(),
    purgeStale: vi.fn().mockResolvedValue({ listings: 0, products: 0 }),
  } as any
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
  // Il prodotto canonico va collegato alle listing, con l'id che ha restituito l'upsert.
  expect(store.upsertProducts).toHaveBeenCalledTimes(1)
  expect(store.linkListingToProduct).toHaveBeenCalledWith(12, 7)
})

test('scarta in ingresso i prodotti datati oltre la finestra', async () => {
  const store = {
    upsertListings: vi.fn().mockResolvedValue(undefined),
    findListingsByTitle: vi.fn().mockResolvedValue([]),
    upsertProducts: vi.fn().mockResolvedValue([1]),
    fillProductDescription: vi.fn().mockResolvedValue(undefined),
    fillProductReleaseDate: vi.fn().mockResolvedValue(undefined),
    linkListingToProduct: vi.fn().mockResolvedValue(undefined),
    markSourceScanned: vi.fn(),
    logRun: vi.fn(),
    purgeStale: vi.fn().mockResolvedValue({ listings: 0, products: 0 }),
  } as any
  const adapters = {
    shopify: async () => [
      { externalId: '1', title: 'Vecchio', url: 'https://e.com/1', releaseDateRaw: '2025-01-15' },
      { externalId: '2', title: 'Fresco', url: 'https://e.com/2', releaseDateRaw: '2027-03-01' },
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

test('una fonte a zero non rende il report non ok', async () => {
  const store = {
    upsertListings: vi.fn().mockResolvedValue(undefined),
    findListingsByTitle: vi.fn().mockResolvedValue([]),
    upsertProducts: vi.fn().mockResolvedValue([]),
    fillProductDescription: vi.fn().mockResolvedValue(undefined),
    fillProductReleaseDate: vi.fn().mockResolvedValue(undefined),
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

test('un fallimento di purgeStale non ferma la raccolta ma rende il report non ok', async () => {
  const store = {
    upsertListings: vi.fn().mockResolvedValue(undefined),
    findListingsByTitle: vi.fn().mockResolvedValue([]),
    upsertProducts: vi.fn().mockResolvedValue([]),
    fillProductDescription: vi.fn().mockResolvedValue(undefined),
    fillProductReleaseDate: vi.fn().mockResolvedValue(undefined),
    linkListingToProduct: vi.fn().mockResolvedValue(undefined),
    markSourceScanned: vi.fn(),
    logRun: vi.fn(),
    purgeStale: vi.fn().mockRejectedValue(new Error('purge boom')),
  } as any
  const adapters = {
    shopify: async () => [{ externalId: '1', title: 'A', url: 'https://e.com/a' }],
    woocommerce: async () => [], wordpress: async () => [], html: async () => [],
    headless: async () => [],
  } as any
  const report = await runCollector({
    sources: [src('s1', 'shopify')], adapters, store,
    now: () => new Date('2026-10-20T00:00:00Z'),
  })
  expect(report.failures['purgeStale']).toBe('purge boom')
  expect(report.ok).toBe(false)
  // La raccolta prosegue nonostante la purge fallita.
  expect(report.perSource['s1']).toBe(1)
  expect(store.upsertListings).toHaveBeenCalledTimes(1)
  expect(store.markSourceScanned).toHaveBeenCalledWith('s1')
  // Il report finisce in run_log anche quando la purge fallisce.
  expect(store.logRun).toHaveBeenCalledWith(
    false,
    report,
    new Date('2026-10-20T00:00:00Z'),
  )
})

const storeBase = () => ({
  upsertListings: vi.fn().mockResolvedValue(undefined),
  findListingsByTitle: vi.fn().mockResolvedValue([]),
  upsertProducts: vi.fn().mockResolvedValue([7]),
  fillProductDescription: vi.fn().mockResolvedValue(undefined),
  fillProductReleaseDate: vi.fn().mockResolvedValue(undefined),
  linkListingToProduct: vi.fn().mockResolvedValue(undefined),
  markSourceScanned: vi.fn(),
  logRun: vi.fn(),
  purgeStale: vi.fn().mockResolvedValue({ listings: 0, products: 0 }),
  findExpansionGamesWithBase: vi.fn().mockResolvedValue([]),
  hasUserActionOnGame: vi.fn().mockResolvedValue(false),
  insertUserAction: vi.fn().mockResolvedValue(undefined),
} as any)

test('chiama fillProductDescription con la descrizione del prodotto canonico', async () => {
  const store = storeBase()
  const adapters = {
    shopify: async () => [{ externalId: '1', title: 'A', url: 'https://e.com/a', descriptionRaw: '<p>Una descrizione</p>' }],
    woocommerce: async () => [], wordpress: async () => [], html: async () => [], headless: async () => [],
  } as any
  await runCollector({ sources: [src('s1', 'shopify')], adapters, store })
  expect(store.fillProductDescription).toHaveBeenCalledWith(7, 'Una descrizione')
})

test("non scrive la descrizione quando il prodotto canonico non ce l'ha", async () => {
  const store = storeBase()
  const adapters = {
    shopify: async () => [{ externalId: '1', title: 'A', url: 'https://e.com/a' }],
    woocommerce: async () => [], wordpress: async () => [], html: async () => [], headless: async () => [],
  } as any
  await runCollector({ sources: [src('s1', 'shopify')], adapters, store })
  expect(store.fillProductDescription).not.toHaveBeenCalled()
})

test('non scrive la descrizione di una fonte news', async () => {
  const store = storeBase()
  const adapters = {
    news: async () => [{ externalId: '9', title: 'N', url: 'https://e.com/n', descriptionRaw: 'Una frase dal titolo' }],
    shopify: async () => [], woocommerce: async () => [], wordpress: async () => [],
    html: async () => [], headless: async () => [],
  } as any
  await runCollector({ sources: [src('sN', 'news')], adapters, store })
  expect(store.fillProductDescription).not.toHaveBeenCalled()
})

test('passa a logRun l istante in cui la run e iniziata', async () => {
  const store = {
    upsertListings: vi.fn().mockResolvedValue(undefined),
    findListingsByTitle: vi.fn().mockResolvedValue([]),
    upsertProducts: vi.fn().mockResolvedValue([]),
    fillProductDescription: vi.fn().mockResolvedValue(undefined),
    fillProductReleaseDate: vi.fn().mockResolvedValue(undefined),
    linkListingToProduct: vi.fn().mockResolvedValue(undefined),
    markSourceScanned: vi.fn(),
    logRun: vi.fn(),
    purgeStale: vi.fn().mockResolvedValue({ listings: 0, products: 0 }),
  } as any
  const adapters = {
    shopify: async () => [{ externalId: '1', title: 'A', url: 'https://e.com/a' }],
    woocommerce: async () => [], wordpress: async () => [], html: async () => [],
    headless: async () => [],
  } as any
  const avvio = new Date('2026-10-20T00:00:00Z')
  const report = await runCollector({
    sources: [src('s1', 'shopify')], adapters, store,
    now: () => avvio,
  })
  // Il database usa default now() su started_at, che e il timestamp della transazione,
  // quindi senza un istante esplicito la colonna registra la FINE della run.
  expect(store.logRun).toHaveBeenCalledWith(true, report, avvio)
})

test('una fonte news non porta prezzo e data nell upsert del prodotto', async () => {
  const store = storeBase()
  const adapters = {
    news: async () => [{ externalId: '9', title: 'N', url: 'https://e.com/n', releaseDateRaw: '2026-11-01', priceRaw: '29.99' }],
    shopify: async () => [], woocommerce: async () => [], wordpress: async () => [], html: async () => [], headless: async () => [],
  } as any
  await runCollector({ sources: [src('sN', 'news')], adapters, store })
  const payload = store.upsertProducts.mock.calls[0][0][0]
  expect(payload).not.toHaveProperty('releaseDate')
  expect(payload).not.toHaveProperty('price')
  // Né la richiesta di azzeramento: la write-once delle news non va toccata.
  expect(payload).not.toHaveProperty('clearReleaseDate')
  expect(store.fillProductReleaseDate).toHaveBeenCalledWith(7, '2026-11-01')
})

test('dvgiochi senza data azzera quella vecchia su listing e prodotto', async () => {
  const store = storeBase()
  const adapters = {
    html: async () => [{ externalId: 'villa', title: 'Villa', url: 'https://www.dvgiochi.com/catalogo/villa/', descriptionRaw: '<p>per 2-4 giocatori</p>' }],
    shopify: async () => [], woocommerce: async () => [], wordpress: async () => [], headless: async () => [],
  } as any
  await runCollector({ sources: [src('dvgiochi', 'html')], adapters, store })
  const listing = store.upsertListings.mock.calls[0][0][0]
  expect(listing).toMatchObject({ releaseDate: null, clearReleaseDate: true })
  const product = store.upsertProducts.mock.calls[0][0][0]
  expect(product.clearReleaseDate).toBe(true)
  // Il fill write-once non deve riscrivere una data appena azzerata.
  expect(store.fillProductReleaseDate).not.toHaveBeenCalled()
})

test('chiama fillProductDescription con la citazione dell’articolo', async () => {
  const store = storeBase()
  const adapters = {
    news: async () => [{ externalId: '9', title: 'N', url: 'https://e.com/n', articleDescription: 'Frase verbatim dell’articolo' }],
    shopify: async () => [], woocommerce: async () => [], wordpress: async () => [], html: async () => [], headless: async () => [],
  } as any
  await runCollector({ sources: [src('sN', 'news')], adapters, store })
  expect(store.fillProductDescription).toHaveBeenCalledWith(7, 'Frase verbatim dell’articolo')
})

test('la citazione sopravvive al filtro di cutoff', async () => {
  const store = storeBase()
  const adapters = {
    news: async () => [
      { externalId: '1', title: 'Vecchio', url: 'https://e.com/1', releaseDateRaw: '2025-01-15' },
      { externalId: '2', title: 'Fresco', url: 'https://e.com/2', articleDescription: 'Citazione viva' },
    ],
    shopify: async () => [], woocommerce: async () => [], wordpress: async () => [], html: async () => [], headless: async () => [],
  } as any
  await runCollector({
    sources: [src('sN', 'news')], adapters, store,
    now: () => new Date('2026-10-20T00:00:00Z'),
  })
  expect(store.fillProductDescription).toHaveBeenCalledTimes(1)
  expect(store.fillProductDescription).toHaveBeenCalledWith(7, 'Citazione viva')
  expect(store.fillProductReleaseDate).not.toHaveBeenCalled()
})

test('report.bgg.collection e autofavorite vengono scritti dal callback collection', async () => {
  const store = storeBase()
  const adapters = {
    shopify: async () => [], woocommerce: async () => [], wordpress: async () => [],
    html: async () => [], headless: async () => [],
  } as any
  const collection = vi.fn().mockResolvedValue({
    synced: 70, owned: 30, wishlist: 5,
    autofavorite: { inserted: 2, skipped: 1 },
  })
  const report = await runCollector({
    sources: [src('s1','shopify')], adapters, store,
    collection,
  })
  expect(collection).toHaveBeenCalledTimes(1)
  expect(report.bgg?.collection).toEqual({ synced: 70, owned: 30, wishlist: 5 })
  expect(report.bgg?.autofavorite).toEqual({ inserted: 2, skipped: 1 })
})

test('usa i metodi batch quando presenti sullo store eliminando il loop N+1', async () => {
  const store = {
    ...storeBase(),
    findListingsByTitles: vi.fn().mockResolvedValue(new Map([
      ['a', [{ id: 101, title: 'A', url: 'https://e.com/a', price: 20, currency: 'EUR', releaseDate: null, imageUrl: null, gameId: null, sourceId: 's1' }]],
      ['b', [{ id: 102, title: 'B', url: 'https://e.com/b', price: 30, currency: 'EUR', releaseDate: null, imageUrl: null, gameId: null, sourceId: 's1' }]],
    ])),
    upsertProductsBatch: vi.fn().mockResolvedValue(new Map([['a', 11], ['b', 12]])),
    linkListingsToProduct: vi.fn().mockResolvedValue(undefined),
  } as any
  const adapters = {
    shopify: async () => [
      { externalId: '1', title: 'A', url: 'https://e.com/a', priceRaw: '20' },
      { externalId: '2', title: 'B', url: 'https://e.com/b', priceRaw: '30' },
    ],
  } as any

  const report = await runCollector({
    sources: [src('s1', 'shopify')],
    adapters,
    store,
    getSourceHttpErrors: () => ({ '429': 1 }),
  })

  expect(store.findListingsByTitles).toHaveBeenCalledTimes(2)
  expect(store.findListingsByTitle).not.toHaveBeenCalled()
  expect(store.upsertProductsBatch).toHaveBeenCalledTimes(1)
  expect(store.upsertProducts).not.toHaveBeenCalled()
  expect(store.linkListingsToProduct).toHaveBeenCalledWith([101], 11)
  expect(store.linkListingsToProduct).toHaveBeenCalledWith([102], 12)
  expect(store.linkListingToProduct).not.toHaveBeenCalled()
  expect(report.metrics?.['s1']).toMatchObject({
    discoveredCount: 2,
    extractedCount: 2,
    httpErrorsByStatus: { '429': 1 },
  })
  expect(report.metrics?.['s1'].durationMs).toBeGreaterThanOrEqual(0)
})

test('esegue le fonti con pool a concorrenza limitata', async () => {
  const store = storeBase()
  let active = 0
  let maxActive = 0
  const adapters = {
    shopify: async () => {
      active++
      maxActive = Math.max(maxActive, active)
      await new Promise((r) => setTimeout(r, 20))
      active--
      return []
    },
  } as any

  const report = await runCollector({
    sources: [src('s1', 'shopify'), src('s2', 'shopify'), src('s3', 'shopify'), src('s4', 'shopify')],
    adapters,
    store,
    concurrency: 2,
  })

  expect(report.ok).toBe(true)
  expect(maxActive).toBe(2)
  expect(Object.keys(report.perSource)).toHaveLength(4)
})

test('errori in alerts o healthCheck sono best-effort e non falliscono la run', async () => {
  const store = storeBase()
  const adapters = { shopify: async () => [] } as any
  const report = await runCollector({
    sources: [src('s1', 'shopify')],
    adapters,
    store,
    alerts: vi.fn().mockRejectedValue(new Error('alerts failed')),
    healthCheck: vi.fn().mockRejectedValue(new Error('health failed')),
  })
  expect(report.ok).toBe(true)
  expect(report.alerts?.error).toBe('alerts failed')
  expect(report.health?.error).toBe('health failed')
  expect(report.failures).toEqual({})
})

test('salta fillProduct* ridondanti quando getProductFillState indica colonne gia popolate e usa source.defaultPublisher', async () => {
  const store = {
    ...storeBase(),
    findListingsByTitles: vi.fn().mockResolvedValue(new Map()),
    upsertProductsBatch: vi.fn().mockResolvedValue(new Map([['a', 11], ['b', 12]])),
    getProductFillState: vi.fn((id: number) =>
      id === 11
        ? { hasDescription: true, hasReleaseDate: true, hasPublisher: true }
        : { hasDescription: false, hasReleaseDate: false, hasPublisher: false },
    ),
    fillProductPublisher: vi.fn().mockResolvedValue(undefined),
    linkListingsToProduct: vi.fn().mockResolvedValue(undefined),
  } as any

  const adapters = {
    shopify: async () => [
      { externalId: '1', title: 'A', url: 'https://e.com/a', descriptionRaw: '<p>Desc A</p>' },
      { externalId: '2', title: 'B', url: 'https://e.com/b', descriptionRaw: '<p>Desc B</p>' },
      { externalId: '3', title: 'B', url: 'https://e.com/b2', descriptionRaw: '<p>Desc B2</p>' },
    ],
  } as any

  await runCollector({
    sources: [{ ...src('cranio', 'shopify'), defaultPublisher: 'Cranio Creations' }],
    adapters,
    store,
  })

  // Product 11 (A) has all fill flags true -> skipped completely
  // Product 12 (B) has fill flags false -> filled once, second item with title 'B' skipped via local state
  expect(store.fillProductDescription).toHaveBeenCalledTimes(1)
  expect(store.fillProductDescription).toHaveBeenCalledWith(12, 'Desc B')
  expect(store.fillProductPublisher).toHaveBeenCalledTimes(1)
  expect(store.fillProductPublisher).toHaveBeenCalledWith(12, 'Cranio Creations')
})

test('esclude i titoli non-boardgame (puzzle, bustine, playmat, BRIO, TCG) preservando i veri giochi', async () => {
  const store = storeBase()
  const adapters = {
    html: async () => [
      { externalId: '1', title: 'Ravensburger Puzzle 1000 Pezzi', url: 'https://e.com/1' },
      { externalId: '2', title: 'Dragon Shield Bustine Protettive', url: 'https://e.com/2' },
      { externalId: '3', title: 'Star Wars Unlimited Booster Pack', url: 'https://e.com/3' },
      { externalId: '4', title: 'Ra: The Dice Game', url: 'https://e.com/4' },
      { externalId: '5', title: '7 Wonders Dice', url: 'https://e.com/5' },
    ],
    shopify: async () => [], woocommerce: async () => [], wordpress: async () => [], headless: async () => [],
  } as any

  await runCollector({
    sources: [src('dungeondice', 'html')],
    adapters,
    store,
  })

  const written = store.upsertListings.mock.calls[0][0]
  expect(written.map((r: any) => r.title)).toEqual(['Ra: The Dice Game', '7 Wonders Dice'])
})
