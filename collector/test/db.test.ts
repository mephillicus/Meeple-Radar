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
  const all = [
    { id: 1, title: 'Carcassonne', normalized_title: 'carcassonne', url: 'https://e.com/c', price: 19.99, currency: 'EUR', release_date: '2026-11-10', image_url: 'https://e.com/i.jpg', game_id: 7, source_id: 'ghenos', product_id: null },
    { id: 2, title: 'Carcassonne: Nuova Edizione', normalized_title: 'carcassonne nuova edizione', url: 'https://e.com/c2', price: 29.99, currency: 'EUR', release_date: null, image_url: null, game_id: null, source_id: 'cranio', product_id: 4 },
    { id: 3, title: 'Cascadia', normalized_title: 'cascadia', url: 'https://e.com/ca', price: null, currency: null, release_date: null, image_url: null, game_id: null, source_id: 'asmodee', product_id: null },
  ]
  // Il filtraggio lo fa il database: il fake deve farlo anch'esso, come fa PostgREST.
  const eq = vi.fn((column: string, value: string) =>
    Promise.resolve({ data: all.filter((r) => (r as Record<string, unknown>)[column] === value), error: null }))
  const select = vi.fn(() => ({ eq }))
  const from = vi.fn(() => ({ select }))
  const store = createSupabaseStore({ from } as any)
  const rows = await store.findListingsByTitle('carcassonne')
  expect(from).toHaveBeenCalledWith('listing')
  expect(select).toHaveBeenCalledWith('id, title, url, price, currency, release_date, image_url, game_id, source_id, product_id')
  expect(eq).toHaveBeenCalledWith('normalized_title', 'carcassonne')
  expect(rows).toHaveLength(1)
  expect(rows[0]).toMatchObject({ id: 1, title: 'Carcassonne', gameId: 7, sourceId: 'ghenos', price: 19.99, currency: 'EUR', releaseDate: '2026-11-10', imageUrl: 'https://e.com/i.jpg', productId: null })
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

const listing = (title: string) => ({
  sourceId: 'cranio', externalId: '1', title, url: 'https://e.com/x',
  price: 1, currency: 'EUR', availability: null, releaseDate: null, imageUrl: null, gameId: null,
})

// supabase-js v2 ignora { returning: 'representation' } e restituisce data: null.
// Gli id si ottengono solo con .select('id') concatenato: senza quello il collector
// scrive i prodotti ma non sa mai quale id assegnare alle listing.
test('upsertProducts restituisce gli id restituendo .select("id")', async () => {
  const select = vi.fn().mockResolvedValue({ data: [{ id: 11 }, { id: 12 }], error: null })
  const upsert = vi.fn().mockReturnValue({ select })
  const from = vi.fn(() => ({ upsert }))
  const store = createSupabaseStore({ from } as any, () => '2026-10-17T00:00:00Z')
  const ids = await store.upsertProducts([listing('A'), listing('B')])
  expect(select).toHaveBeenCalledWith('id')
  expect(ids).toEqual([11, 12])
})

test('upsertProducts non passa il returning v1', async () => {
  const select = vi.fn().mockResolvedValue({ data: [{ id: 1 }], error: null })
  const upsert = vi.fn().mockReturnValue({ select })
  const from = vi.fn(() => ({ upsert }))
  const store = createSupabaseStore({ from } as any, () => '2026-10-17T00:00:00Z')
  await store.upsertProducts([listing('A')])
  expect(upsert.mock.calls[0][1]).not.toHaveProperty('returning')
})

test('purgeStale scarta solo le righe datate oltre before, o senza data e non viste da before', async () => {
  const rows = [{ id: 1 }, { id: 3 }]
  const notFn = vi.fn().mockReturnValue({ lt: vi.fn().mockResolvedValue({ data: [{ id: 1 }], error: null }) })
  const isFn = vi.fn().mockReturnValue({ lt: vi.fn().mockResolvedValue({ data: [{ id: 3 }], error: null }) })
  const select = vi.fn(() => ({ not: notFn, is: isFn }))
  const deleteIn = vi.fn().mockResolvedValue({ error: null })
  const productSelect = vi.fn().mockReturnValue({ lt: vi.fn().mockResolvedValue({ data: [], error: null }) })
  const productFrom = vi.fn(() => ({ select: productSelect, delete: () => ({ in: deleteIn }) }))
  const listingFrom = vi.fn(() => ({ select, delete: () => ({ in: deleteIn }) }))
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
  expect(deleteIn).toHaveBeenCalledWith('id', rows.map((r) => r.id))
})

test('purgeStale non cancella le preferite e non cancella un prodotto referenziato', async () => {
  const favorite = [{ listing_id: 9 }]
  const staleListings = [{ id: 1 }, { id: 9 }]
  const notRelease = vi.fn().mockReturnValue({ lt: vi.fn().mockResolvedValue({ data: staleListings, error: null }) })
  const notProduct = vi.fn().mockResolvedValue({ data: [{ product_id: 50 }], error: null })
  const isFn = vi.fn().mockReturnValue({ lt: vi.fn().mockResolvedValue({ data: [], error: null }) })
  const selectListing = vi.fn(() => ({
    not: (column: string) => (column === 'release_date' ? notRelease() : notProduct()),
    is: isFn,
  }))
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
  const from = vi.fn((table: string) =>
    table === 'product' ? productFrom() : table === 'user_action' ? userFrom() : listingFrom())
  const store = createSupabaseStore({ from } as any)
  const purged = await store.purgeStale('2025-10-20')
  expect(purged.listings).toBe(1)
  expect(deleteIn).toHaveBeenCalledWith('id', [1])
  expect(purged.products).toBe(0)
})

test('purgeStale restituisce zeri senza cancellare nulla quando non c\'e niente di vecchio', async () => {
  const empty = vi.fn().mockResolvedValue({ data: [], error: null })
  const notFn = vi.fn().mockReturnValue({ lt: vi.fn().mockResolvedValue({ data: [], error: null }) })
  const isFn = vi.fn().mockReturnValue({ lt: vi.fn().mockResolvedValue({ data: [], error: null }) })
  const eqFn = vi.fn().mockReturnValue({ not: empty })
  const from = vi.fn(() => ({ select: vi.fn(() => ({ not: notFn, is: isFn, lt: empty, eq: eqFn })), delete: () => ({ in: empty }) }))
  const store = createSupabaseStore({ from } as any)
  const purged = await store.purgeStale('2025-10-20')
  expect(purged).toEqual({ listings: 0, products: 0 })
})
