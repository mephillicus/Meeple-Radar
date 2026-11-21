import { expect, test, vi } from 'vitest'
import { runCollectionSync } from '../src/run-collection.js'
import type { CollectionItem } from '../src/bgg/collection.js'

test('runCollectionSync upserts collection items and runs auto-favorites without touching shops', async () => {
  const items: CollectionItem[] = [
    { bggId: 173346, name: '7 Wonders Duel', kind: 'base', owned: true, wishlist: false },
    { bggId: 202976, name: '7 Wonders Duel: Pantheon', kind: 'expansion', owned: false, wishlist: true },
  ]
  const store = {
    upsertCollectionItem: vi.fn().mockResolvedValue(undefined),
    collectionOwnedBggIds: vi.fn().mockResolvedValue(new Set([173346])),
    findExpansionGamesWithBase: vi.fn().mockResolvedValue([
      { id: 42, expands_bgg_id: 173346 },
      { id: 43, expands_bgg_id: 999999 },
    ]),
    hasUserActionOnGame: vi.fn().mockResolvedValue(false),
    insertUserAction: vi.fn().mockResolvedValue(undefined),
    upsertListings: vi.fn(),
  } as any

  const fetchCollection = vi.fn().mockResolvedValue(items)

  const report = await runCollectionSync({
    username: 'testuser',
    store,
    fetchCollection,
  })

  expect(fetchCollection).toHaveBeenCalledWith('testuser')
  expect(store.upsertCollectionItem).toHaveBeenCalledWith(items)
  expect(store.insertUserAction).toHaveBeenCalledWith(42, 'favorite')
  expect(store.upsertListings).not.toHaveBeenCalled()
  expect(report).toEqual({
    ok: true,
    synced: 2,
    owned: 1,
    wishlist: 1,
    autofavorite: { inserted: 1, skipped: 1 },
  })
})

test('runCollectionSync returns ok: false and error message when fetch fails', async () => {
  const store = {
    upsertCollectionItem: vi.fn(),
    collectionOwnedBggIds: vi.fn(),
    findExpansionGamesWithBase: vi.fn(),
    hasUserActionOnGame: vi.fn(),
    insertUserAction: vi.fn(),
  } as any

  const report = await runCollectionSync({
    username: 'testuser',
    store,
    fetchCollection: vi.fn().mockRejectedValue(new Error('BGG 503')),
  })

  expect(report.ok).toBe(false)
  expect(report.error).toBe('BGG 503')
  expect(store.upsertCollectionItem).not.toHaveBeenCalled()
})
