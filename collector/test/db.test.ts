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

test('upserts sources on the id conflict target', async () => {
  const upsert = vi.fn().mockResolvedValue({ error: null })
  const from = vi.fn(() => ({ upsert }))
  const store = createSupabaseStore({ from } as any, () => '2026-10-17T00:00:00Z')
  await store.upsertSources([
    { id: 'cranio', name: 'Cranio Creations', kind: 'shopify', baseUrl: 'https://craniocreations.it', enabled: true },
  ])
  expect(from).toHaveBeenCalledWith('source')
  expect(upsert.mock.calls[0][1]).toMatchObject({ onConflict: 'id' })
  expect(upsert.mock.calls[0][0][0]).toMatchObject({
    id: 'cranio', base_url: 'https://craniocreations.it', adapter: 'shopify', enabled: true,
  })
})
