import { expect, test, vi } from 'vitest'
import { applyReview } from '../../src/bgg/apply-review.js'
import { buildRanksIndex, type RankRow } from '../../src/bgg/csv-ranks.js'

const row = (bggId: number, name: string): RankRow => ({
  bggId, name, year: 2009, rank: 1723, average: 6.5, usersrated: 22804, isExpansion: false,
})

const noopSleep = () => Promise.resolve()

const fakeStore = () => ({
  findProductById: vi.fn().mockImplementation(async (id: number) =>
    id === 1 ? { id: 1, title: 'Dobble', normalizedTitle: 'dobble', releaseDate: null, gameId: null } : null),
  findGameByBggId: vi.fn().mockResolvedValue(null),
  createGame: vi.fn().mockResolvedValue(100),
  setProductGame: vi.fn().mockResolvedValue(true),
  fillListingGame: vi.fn().mockResolvedValue(2),
})

test('applica la riga compilata: gioco dal geekitem, kind dal subtype, link product e listing', async () => {
  const store = fakeStore()
  const report = await applyReview({
    store: store as never,
    ranks: buildRanksIndex([row(63268, 'Spot it!')]),
    fetchGeekItem: vi.fn().mockResolvedValue({
      bggId: 63268, name: 'Spot it!', subtype: 'boardgame', alternateNames: ['Dobble'], yearpublished: 2009,
    }),
    sleep: noopSleep,
  }, [{ productId: 1, bggId: 63268 }])
  expect(report.applied).toBe(1)
  expect(store.createGame).toHaveBeenCalledWith(expect.objectContaining({ bggId: 63268, kind: 'base', name: 'Dobble' }))
  expect(store.setProductGame).toHaveBeenCalledWith(1, 100)
  expect(store.fillListingGame).toHaveBeenCalledWith(1, 100)
})

test('le espansioni BGG arrivano come kind expansion', async () => {
  const store = fakeStore()
  await applyReview({
    store: store as never,
    ranks: buildRanksIndex([]),
    fetchGeekItem: vi.fn().mockResolvedValue({
      bggId: 555, name: 'Espansione X', subtype: 'boardgameexpansion', alternateNames: [], yearpublished: 2015,
    }),
    sleep: noopSleep,
  }, [{ productId: 1, bggId: 555 }])
  expect(store.createGame).toHaveBeenCalledWith(expect.objectContaining({ kind: 'expansion', bggId: 555 }))
})

test('righe senza bgg_id o prodotto mancante non contano come applicate', async () => {
  const store = fakeStore()
  const report = await applyReview({
    store: store as never,
    ranks: buildRanksIndex([]),
    fetchGeekItem: vi.fn().mockResolvedValue({
      bggId: 63268, name: 'Spot it!', subtype: 'boardgame', alternateNames: ['Dobble'], yearpublished: 2009,
    }),
    sleep: noopSleep,
  }, [
    { productId: 1, bggId: null },
    { productId: 404, bggId: 63268 },
    { productId: 1, bggId: 63268 },
  ])
  expect(report.applied).toBe(1)
  expect(report.missingProducts).toBe(1)
  expect(report.emptyRows).toBe(1)
  // Il geekitem viene scaricato solo dove serve.
  expect(store.createGame).toHaveBeenCalledTimes(1)
})

test('geekitem irraggiungibile: la riga resta da rifare, non si assegna alla cieca', async () => {
  const store = fakeStore()
  const report = await applyReview({
    store: store as never,
    ranks: buildRanksIndex([row(63268, 'Spot it!')]),
    fetchGeekItem: vi.fn().mockResolvedValue(null),
    sleep: noopSleep,
  }, [{ productId: 1, bggId: 63268 }])
  expect(report.applied).toBe(0)
  expect(report.missingItems).toBe(1)
  expect(store.setProductGame).not.toHaveBeenCalled()
})

test('i prodotti gia collegati vengono saltati senza richieste di rete', async () => {
  const store = fakeStore()
  store.findProductById.mockImplementation(async () => ({
    id: 1, title: 'Dobble', normalizedTitle: 'dobble', releaseDate: null, gameId: 798,
  }))
  const fetchGeekItem = vi.fn()
  const report = await applyReview({
    store: store as never,
    ranks: buildRanksIndex([]),
    fetchGeekItem,
    sleep: noopSleep,
  }, [{ productId: 1, bggId: 63268 }])
  expect(report.alreadyLinked).toBe(1)
  expect(report.applied).toBe(0)
  expect(fetchGeekItem).not.toHaveBeenCalled()
})
