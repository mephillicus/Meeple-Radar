import { expect, test, vi } from 'vitest'
import { createSupabaseStore } from '../src/db.js'

test('upserts on the (source_id, external_id) conflict target', async () => {
  const upsert = vi.fn().mockResolvedValue({ error: null })
  const from = vi.fn(() => ({ upsert }))
  const store = createSupabaseStore({ from } as any, () => '2026-10-17T00:00:00Z')
  await store.upsertListings([
    { sourceId: 'cranio', externalId: '1', title: 'X', url: 'https://e.com/x',
      price: 1, currency: 'EUR', availability: null, releaseDate: null, imageUrl: null, gameId: null },
  ])
  expect(from).toHaveBeenCalledWith('listing')
  expect(upsert.mock.calls[0][1]).toMatchObject({ onConflict: 'source_id,external_id' })
})

test('includes game_id in the upsert payload', async () => {
  const upsert = vi.fn().mockResolvedValue({ error: null })
  const from = vi.fn(() => ({ upsert }))
  const store = createSupabaseStore({ from } as any, () => '2026-10-17T00:00:00Z')
  await store.upsertListings([
    { sourceId: 'cranio', externalId: '1', title: 'X', url: 'https://e.com/x',
      price: 1, currency: 'EUR', availability: null, releaseDate: null, imageUrl: null, gameId: 42 },
  ])
  expect(upsert.mock.calls[0][0][0]).toMatchObject({ game_id: 42 })
})

test('findListingsByTitle fetches the listing columns including source_id and filters by normalizeTitle', async () => {
  const select = vi.fn().mockResolvedValue({
    data: [
      { id: 1, title: 'Carcassonne', url: 'https://e.com/c', price: 19.99, currency: 'EUR', release_date: '2026-11-10', image_url: 'https://e.com/i.jpg', game_id: 7, source_id: 'ghenos' },
      { id: 2, title: 'Carcassonne: Nuova Edizione', url: 'https://e.com/c2', price: 29.99, currency: 'EUR', release_date: null, image_url: null, game_id: null, source_id: 'cranio' },
      { id: 3, title: 'Cascadia', url: 'https://e.com/ca', price: null, currency: null, release_date: null, image_url: null, game_id: null, source_id: 'asmodee' },
    ],
    error: null,
  })
  const from = vi.fn(() => ({ select }))
  const store = createSupabaseStore({ from } as any)
  const rows = await store.findListingsByTitle('carcassonne')
  expect(from).toHaveBeenCalledWith('listing')
  expect(select).toHaveBeenCalledWith('id, title, url, price, currency, release_date, image_url, game_id, source_id')
  expect(rows).toHaveLength(1)
  expect(rows[0]).toMatchObject({ id: 1, title: 'Carcassonne', gameId: 7, sourceId: 'ghenos', price: 19.99, currency: 'EUR', releaseDate: '2026-11-10', imageUrl: 'https://e.com/i.jpg' })
})

test('findSources returns id/name rows from the source table', async () => {
  const select = vi.fn().mockResolvedValue({
    data: [
      { id: 'cranio', name: 'Cranio Creations' },
      { id: 'ghenos', name: 'Ghenos Games' },
    ],
    error: null,
  })
  const from = vi.fn(() => ({ select }))
  const store = createSupabaseStore({ from } as any)
  const found = await store.findSources()
  expect(from).toHaveBeenCalledWith('source')
  expect(select).toHaveBeenCalledWith('id, name')
  expect(found).toEqual([
    { id: 'cranio', name: 'Cranio Creations' },
    { id: 'ghenos', name: 'Ghenos Games' },
  ])
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
