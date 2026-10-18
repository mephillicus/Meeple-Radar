import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { createGameFinder, fetchGeminiProducts } from '../../src/adapters/gemini.js'
import { buildRegistry } from '../../src/adapters/registry.js'
import type { GameFinder, Source } from '../../src/types.js'

const source: Source = { id: 'gemini', name: 'Gemini', kind: 'gemini', baseUrl: 'https://e.com', enabled: true }

const row = (overrides: Record<string, unknown> = {}) => ({
  id: 1,
  title: 'Carcassonne',
  url: 'https://e.com/carcassonne',
  price: 19.99,
  currency: 'EUR',
  releaseDate: '2026-11-10',
  imageUrl: 'https://e.com/img.jpg',
  gameId: 7,
  ...overrides,
})

const storeWith = (rows: unknown[]) => ({ findListingsByTitle: vi.fn().mockResolvedValue(rows) } as any)

afterEach(() => {
  vi.unstubAllGlobals()
  delete process.env.GEMINI_API_KEY
})

test('createGameFinder: one row resolves to a GameMatch built from that row', async () => {
  const finder = createGameFinder(storeWith([row()]))
  const match = await finder('carcassonne', 'Ghenos Games')
  expect(match).toEqual({
    gameId: 7,
    price: 19.99,
    currency: 'EUR',
    releaseDate: '2026-11-10',
    imageUrl: 'https://e.com/img.jpg',
  })
})

test('createGameFinder: no rows resolves to null', async () => {
  const finder = createGameFinder(storeWith([]))
  const match = await finder('carcassonne', 'Ghenos Games')
  expect(match).toBeNull()
})

test('createGameFinder: several rows, publisher hint picks the matching sourceId', async () => {
  const rows = [
    row({ id: 1, sourceId: 'cranio', title: 'Carcassonne', gameId: 7, price: 19.99 }),
    row({ id: 2, sourceId: 'ghenos', title: 'Carcassonne: Nuova Edizione', gameId: 9, price: 29.99 }),
  ]
  const finder = createGameFinder(storeWith(rows))
  const match = await finder('carcassonne', 'cranio')
  expect(match).toEqual({ gameId: 7, price: 19.99, currency: 'EUR', releaseDate: '2026-11-10', imageUrl: 'https://e.com/img.jpg' })
})

test('createGameFinder: several rows without a matching hint is ambiguous and returns null', async () => {
  const rows = [
    row({ id: 1, sourceId: 'cranio', title: 'Carcassonne', gameId: 7 }),
    row({ id: 2, sourceId: 'ghenos', title: 'Carcassonne: Nuova Edizione', gameId: 9 }),
  ]
  const finder = createGameFinder(storeWith(rows))
  expect(await finder('carcassonne', 'giochiuniti')).toBeNull()
  expect(await finder('carcassonne')).toBeNull()
})

test('buildRegistry.gemini passes gameFinder to fetchGeminiProducts', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  const gameFinder: GameFinder = vi.fn().mockResolvedValue(null)
  const body = '```json\n[{"title": "Carcassonne", "publisher": "Ghenos Games"}]```'
  const fetchMock = vi.fn().mockResolvedValue({
    status: 200,
    ok: true,
    json: async () => ({ candidates: [{ content: { parts: [{ text: body }] } }] }),
  } as any)
  vi.stubGlobal('fetch', fetchMock)
  const registry = buildRegistry(fetchMock as any, gameFinder)
  const products = await registry.gemini(source)
  expect(products).toHaveLength(1)
  expect(gameFinder).toHaveBeenCalledWith('carcassonne', 'Ghenos Games')
})
