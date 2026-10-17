import { expect, test, vi } from 'vitest'
import { runCollector } from '../src/run.js'

const s = (id: string) => ({ id, name: id, kind: 'shopify' as const, baseUrl: 'https://e.com', enabled: true })

test('flags a source that returns zero items', async () => {
  const store = { upsertListings: vi.fn(), markSourceScanned: vi.fn(), logRun: vi.fn() } as any
  const adapters = { shopify: async () => [], woocommerce: async()=>[], wordpress: async()=>[], html: async()=>[], headless: async()=>[] } as any
  const report = await runCollector({ sources: [s('empty')], adapters, store })
  expect(report.zeroed).toContain('empty')
})
