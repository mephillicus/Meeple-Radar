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
  expect(store.logRun).toHaveBeenCalledWith(false, report)
})
