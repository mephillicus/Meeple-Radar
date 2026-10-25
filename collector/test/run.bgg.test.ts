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

test('la passata bgg gira dopo le fonti e i suoi numeri finiscono nel logRun', async () => {
  const store = storeBase()
  const order: string[] = []
  store.markSourceScanned.mockImplementation(async () => { order.push('scanned') })
  store.logRun.mockImplementation(async (ok: boolean, details: any) => { order.push('logRun:' + JSON.stringify(details.bgg)) })
  const adapters = { shopify: async () => [] } as any
  const enrich = vi.fn(async () => {
    order.push('enrich')
    return { candidates: 3, linked: 3, viaCsv: 3, viaSearch: 0, listingsLinked: 4, unresolved: 0, onlineRequests: 0, csvMissing: false }
  })
  const report = await runCollector({ sources: [src('s1', 'shopify')], adapters, store, enrich })
  expect(order).toEqual(['scanned', 'enrich', 'logRun:' + JSON.stringify(report.bgg)])
  expect(report.bgg?.linked).toBe(3)
  expect(report.ok).toBe(true)
})

test('un fallimento dell arricchimento non fallisce la run', async () => {
  const store = storeBase()
  const adapters = { shopify: async () => [] } as any
  const enrich = vi.fn().mockRejectedValue(new Error('boom'))
  const report = await runCollector({ sources: [src('s1', 'shopify')], adapters, store, enrich })
  expect(report.ok).toBe(true)
  expect(report.bgg?.error).toBe('boom')
  expect(report.failures).toEqual({})
})

test('senza arricchimento il report non ha il campo bgg', async () => {
  const store = storeBase()
  const report = await runCollector({ sources: [src('s1', 'shopify')], adapters: { shopify: async () => [] } as any, store })
  expect(report.bgg).toBeUndefined()
})
