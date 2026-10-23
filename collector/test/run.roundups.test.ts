import { expect, test, vi } from 'vitest'
import { runCollector } from '../src/run.js'

const src = (id: string, kind: any) => ({ id, name: id, kind, baseUrl: 'https://e.com', enabled: true })

const storeBase = () => ({
  upsertListings: vi.fn().mockResolvedValue(undefined),
  findListingsByTitle: vi.fn().mockResolvedValue([]),
  upsertProducts: vi.fn().mockResolvedValue([7]),
  fillProductDescription: vi.fn().mockResolvedValue(undefined),
  fillProductReleaseDate: vi.fn().mockResolvedValue(undefined),
  fillProductPublisher: vi.fn().mockResolvedValue(undefined),
  linkListingToProduct: vi.fn().mockResolvedValue(undefined),
  markSourceScanned: vi.fn(),
  logRun: vi.fn(),
  purgeStale: vi.fn().mockResolvedValue({ listings: 0, products: 0 }),
} as any)

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
