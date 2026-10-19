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
    linkListingToProduct: vi.fn().mockResolvedValue(undefined),
    markSourceScanned: vi.fn(),
    logRun: vi.fn(),
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
