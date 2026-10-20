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

test('upsertProducts omette i campi nulli allo stesso modo', async () => {
  const select = vi.fn().mockResolvedValue({ data: [], error: null })
  const upsert = vi.fn().mockReturnValue({ select })
  const from = vi.fn(() => ({ upsert }))
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

// PostgREST serve al massimo `max-rows` righe per richiesta: il fake deve
// paginare con .range come fa il database, non restituire tutto in un colpo.
function paged(rows: Record<string, unknown>[], cap = rows.length) {
  let start = 0
  let end = rows.length
  const lt = vi.fn(() => builder)
  const range = vi.fn((from: number, to: number) => {
    start = from
    end = to + 1
    return builder
  })
  const builder: any = {
    lt,
    range,
    then: (resolve: (value: unknown) => unknown, reject: (reason: unknown) => unknown) =>
      Promise.resolve({ data: rows.slice(start, Math.min(end, start + cap)), error: null })
        .then(resolve, reject),
  }
  return builder
}

test('purgeStale scarta solo le righe datate oltre before, o senza data e non viste da before', async () => {
  const rows = [{ id: 1 }, { id: 3 }]
  const datedBody = paged([{ id: 1 }])
  const undatedBody = paged([{ id: 3 }])
  const notFn = vi.fn((column: string) => (column === 'release_date' ? datedBody : paged([])))
  const isFn = vi.fn(() => undatedBody)
  const select = vi.fn(() => ({ not: notFn, is: isFn }))
  const deleteIn = vi.fn().mockResolvedValue({ error: null })
  const productLt = vi.fn(() => paged([]))
  const productSelect = vi.fn(() => ({ lt: productLt }))
  const productFrom = vi.fn(() => ({ select: productSelect, delete: () => ({ in: deleteIn }) }))
  const listingFrom = vi.fn(() => ({ select, delete: () => ({ in: deleteIn }) }))
  const userFrom = vi.fn(() => ({
    select: vi.fn(() => ({ eq: vi.fn(() => ({ not: vi.fn(() => paged([])) })) })),
  }))
  const from = vi.fn((table: string) =>
    table === 'product' ? productFrom() : table === 'user_action' ? userFrom() : listingFrom())
  const store = createSupabaseStore({ from } as any)
  const purged = await store.purgeStale('2025-10-20')
  expect(notFn).toHaveBeenCalledWith('release_date', 'is', null)
  expect(isFn).toHaveBeenCalledWith('release_date', null)
  expect(datedBody.lt).toHaveBeenCalledWith('release_date', '2025-10-20')
  expect(undatedBody.lt).toHaveBeenCalledWith('last_seen_at', '2025-10-20')
  expect(productLt).toHaveBeenCalledWith('release_date', '2025-10-20')
  expect(purged.listings).toBe(2)
  expect(deleteIn).toHaveBeenCalledWith('id', rows.map((r) => r.id))
})

test('purgeStale non cancella le preferite e non cancella un prodotto referenziato', async () => {
  const staleBody = paged([{ id: 1 }, { id: 9 }])
  const referencedBody = paged([{ product_id: 50 }])
  const notFn = vi.fn((column: string) => (column === 'release_date' ? staleBody : referencedBody))
  const isFn = vi.fn(() => paged([]))
  const selectListing = vi.fn(() => ({ not: notFn, is: isFn }))
  const deleteIn = vi.fn().mockResolvedValue({ error: null })
  const listingFrom = vi.fn(() => ({ select: selectListing, delete: () => ({ in: deleteIn }) }))
  const favoriteListings = paged([{ listing_id: 9 }])
  const favNot = vi.fn((column: string) => (column === 'listing_id' ? favoriteListings : paged([])))
  const userActionSelect = vi.fn(() => ({ eq: vi.fn(() => ({ not: favNot })) }))
  const userFrom = vi.fn(() => ({ select: userActionSelect }))
  const productBody = paged([{ id: 50 }])
  const productSelect = vi.fn(() => ({ lt: vi.fn(() => productBody) }))
  const productDeleteIn = vi.fn().mockResolvedValue({ error: null })
  const productFrom = vi.fn(() => ({
    select: productSelect,
    delete: () => ({ in: productDeleteIn }),
  }))
  const from = vi.fn((table: string) =>
    table === 'product' ? productFrom() : table === 'user_action' ? userFrom() : listingFrom())
  const store = createSupabaseStore({ from } as any)
  const purged = await store.purgeStale('2025-10-20')
  expect(purged.listings).toBe(1)
  expect(deleteIn).toHaveBeenCalledWith('id', [1])
  expect(purged.products).toBe(0)
  expect(productDeleteIn).not.toHaveBeenCalled()
})

test('purgeStale restituisce zeri senza cancellare nulla quando non c\'e niente di vecchio', async () => {
  const body = paged([])
  const notFn = vi.fn(() => body)
  const isFn = vi.fn(() => body)
  const eqFn = vi.fn(() => ({ not: vi.fn(() => body) }))
  const ltFn = vi.fn(() => body)
  const from = vi.fn(() => ({
    select: vi.fn(() => ({ not: notFn, is: isFn, lt: ltFn, eq: eqFn })),
    delete: () => ({ in: vi.fn().mockResolvedValue({ error: null }) }),
  }))
  const store = createSupabaseStore({ from } as any)
  const purged = await store.purgeStale('2025-10-20')
  expect(purged).toEqual({ listings: 0, products: 0 })
})

test('purgeStale non cancella un prodotto segnato come preferito', async () => {
  const notFn = vi.fn(() => paged([]))
  const isFn = vi.fn(() => paged([]))
  const listingFrom = vi.fn(() => ({
    select: vi.fn(() => ({ not: notFn, is: isFn })),
    delete: () => ({ in: vi.fn().mockResolvedValue({ error: null }) }),
  }))
  const favNot = vi.fn((column: string) =>
    column === 'product_id' ? paged([{ product_id: 50 }]) : paged([]))
  const userFrom = vi.fn(() => ({
    select: vi.fn(() => ({ eq: vi.fn(() => ({ not: favNot })) })),
  }))
  const productDeleteIn = vi.fn().mockResolvedValue({ error: null })
  const productFrom = vi.fn(() => ({
    select: vi.fn(() => ({ lt: vi.fn(() => paged([{ id: 50 }])) })),
    delete: () => ({ in: productDeleteIn }),
  }))
  const from = vi.fn((table: string) =>
    table === 'product' ? productFrom() : table === 'user_action' ? userFrom() : listingFrom())
  const store = createSupabaseStore({ from } as any)
  const purged = await store.purgeStale('2025-10-20')
  expect(purged).toEqual({ listings: 0, products: 0 })
  expect(productDeleteIn).not.toHaveBeenCalled()
})

test('purgeStale pagine le select e raccoglie tutte le righe oltre il cap del server', async () => {
  const many = Array.from({ length: 5 }, (_, i) => ({ id: i + 1 }))
  const datedBody = paged(many, 2)
  const notFn = vi.fn((column: string) => (column === 'release_date' ? datedBody : paged([])))
  const isFn = vi.fn(() => paged([]))
  const deleteIn = vi.fn().mockResolvedValue({ error: null })
  const listingFrom = vi.fn(() => ({
    select: vi.fn(() => ({ not: notFn, is: isFn })),
    delete: () => ({ in: deleteIn }),
  }))
  const userFrom = vi.fn(() => ({
    select: vi.fn(() => ({ eq: vi.fn(() => ({ not: vi.fn(() => paged([])) })) })),
  }))
  const productFrom = vi.fn(() => ({
    select: vi.fn(() => ({ lt: vi.fn(() => paged([])) })),
    delete: () => ({ in: vi.fn().mockResolvedValue({ error: null }) }),
  }))
  const from = vi.fn((table: string) =>
    table === 'product' ? productFrom() : table === 'user_action' ? userFrom() : listingFrom())
  const store = createSupabaseStore({ from } as any)
  const purged = await store.purgeStale('2025-10-20')
  expect(purged.listings).toBe(5)
  expect(datedBody.range).toHaveBeenCalledTimes(4)
  expect(deleteIn).toHaveBeenCalledWith('id', many.map((r) => r.id))
})

test('purgeStale spezza i delete in batch da PURGE_DELETE_BATCH_SIZE id', async () => {
  const many = Array.from({ length: 1201 }, (_, i) => ({ id: i + 1 }))
  const datedBody = paged(many)
  const notFn = vi.fn((column: string) => (column === 'release_date' ? datedBody : paged([])))
  const isFn = vi.fn(() => paged([]))
  const deleteIn = vi.fn().mockResolvedValue({ error: null })
  const listingFrom = vi.fn(() => ({
    select: vi.fn(() => ({ not: notFn, is: isFn })),
    delete: () => ({ in: deleteIn }),
  }))
  const userFrom = vi.fn(() => ({
    select: vi.fn(() => ({ eq: vi.fn(() => ({ not: vi.fn(() => paged([])) })) })),
  }))
  const productFrom = vi.fn(() => ({
    select: vi.fn(() => ({ lt: vi.fn(() => paged([])) })),
    delete: () => ({ in: vi.fn().mockResolvedValue({ error: null }) }),
  }))
  const from = vi.fn((table: string) =>
    table === 'product' ? productFrom() : table === 'user_action' ? userFrom() : listingFrom())
  const store = createSupabaseStore({ from } as any)
  const purged = await store.purgeStale('2025-10-20')
  expect(purged.listings).toBe(1201)
  expect(deleteIn).toHaveBeenCalledTimes(3)
  for (const [, ids] of deleteIn.mock.calls) {
    expect(ids.length).toBeLessThanOrEqual(500)
  }
  const deleted = deleteIn.mock.calls.flatMap(([, ids]) => ids)
  expect(deleted).toEqual(many.map((r) => r.id))
})

test('purgeStale non emette nessun delete quando la lista e vuota', async () => {
  const body = paged([])
  const notFn = vi.fn(() => body)
  const isFn = vi.fn(() => body)
  const eqFn = vi.fn(() => ({ not: vi.fn(() => body) }))
  const ltFn = vi.fn(() => body)
  const deleteIn = vi.fn().mockResolvedValue({ error: null })
  const from = vi.fn(() => ({
    select: vi.fn(() => ({ not: notFn, is: isFn, lt: ltFn, eq: eqFn })),
    delete: () => ({ in: deleteIn }),
  }))
  const store = createSupabaseStore({ from } as any)
  const purged = await store.purgeStale('2025-10-20')
  expect(purged).toEqual({ listings: 0, products: 0 })
  expect(deleteIn).not.toHaveBeenCalled()
})

test('logRun scrive started_at con l istante di avvio della run', async () => {
  const insert = vi.fn().mockResolvedValue({ error: null })
  const from = vi.fn(() => ({ insert }))
  const store = createSupabaseStore({ from } as any, () => '2026-10-21T00:00:00Z')
  const avvio = new Date('2026-10-21T09:30:00Z')
  await store.logRun(true, { ok: true }, avvio)
  expect(from).toHaveBeenCalledWith('run_log')
  // Senza un valore esplicito la colonna cade su default now(), che e il timestamp
  // della transazione di INSERT: quindi started_at finirebbe per indicare la FINE run.
  expect(insert.mock.calls[0][0].started_at).toBe('2026-10-21T09:30:00.000Z')
})
