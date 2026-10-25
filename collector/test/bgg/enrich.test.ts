import { expect, test, vi } from 'vitest'
import { runBggEnrichment, ONLINE_BUDGET } from '../../src/bgg/enrich.js'
import { parseRanksCsv, buildRanksIndex } from '../../src/bgg/csv-ranks.js'
import type { GeekItem } from '../../src/bgg/online.js'

const HEADER = 'id,name,yearpublished,rank,bayesaverage,average,usersrated,is_expansion,abstracts_rank'
const ranks = () => buildRanksIndex(parseRanksCsv(
  HEADER + '\n1,"Carcassonne",2000,10,7,7.4,100,0,,\n2,"Dune",2019,,8,8.1,200,0,,\n'))

const storeBase = (products: { id: number; title: string; normalizedTitle: string }[]) => ({
  findUnlinkedProducts: vi.fn().mockResolvedValue(products),
  findGameByBggId: vi.fn().mockResolvedValue(null),
  createGame: vi.fn().mockResolvedValue(50),
  setProductGame: vi.fn().mockResolvedValue(true),
  fillListingGame: vi.fn().mockResolvedValue(2),
} as any)

const skipBase = () => ({ shouldSkip: vi.fn().mockReturnValue(false), record: vi.fn().mockReturnValue(1), save: vi.fn() })
const geek = (id: number, name: string, opts: { year?: number; alt?: string[]; subtype?: string } = {}): GeekItem =>
  ({ bggId: id, name, alternateNames: opts.alt ?? [], subtype: opts.subtype ?? 'boardgame', yearpublished: opts.year ?? 2019 })

test('lato CSV bulk: linka, crea game col titolo italiano e riempie i listings', async () => {
  const store = storeBase([
    { id: 1, title: 'Carcassonne', normalizedTitle: 'carcassonne' },
    { id: 2, title: 'TitoloSconosciuto', normalizedTitle: 'titolosconosciuto' },
  ])
  const report = await runBggEnrichment({
    store, ranks: ranks(), searchIds: vi.fn().mockResolvedValue([]), fetchGeekItem: vi.fn(), skipped: skipBase(),
  })
  expect(store.createGame).toHaveBeenCalledWith(
    expect.objectContaining({ name: 'Carcassonne', kind: 'base', bggId: 1, bggRank: 10 }))
  expect(store.setProductGame).toHaveBeenCalledWith(1, 50)
  expect(store.fillListingGame).toHaveBeenCalledTimes(1)
  expect(report).toMatchObject({ candidates: 2, linked: 1, viaCsv: 1, listingsLinked: 2, csvMissing: false })
  expect(report.unresolved).toBe(1)
})

test('lato online: titolo italiano non nel CSV, gate via alternatenames, rating dal CSV by-id, spacing rispettato', async () => {
  const idx = buildRanksIndex(parseRanksCsv(
    HEADER + '\n25821,"The Werewolves of Miller\'s Hollow",2001,,7,7.2,100,0,,\n'))
  const store = storeBase([{ id: 7, title: 'Lupi Mannari di Roccascura', normalizedTitle: 'lupi mannari di roccascura' }])
  const sleep = vi.fn().mockResolvedValue(undefined)
  const searchIds = vi.fn().mockResolvedValue([25821])
  const fetchGeekItem = vi.fn().mockResolvedValue(
    geek(25821, "The Werewolves of Miller's Hollow", { year: 2001, alt: ['Lupi Mannari di Roccascura'] }))
  const skipped = skipBase()
  const report = await runBggEnrichment({ store, ranks: idx, searchIds, fetchGeekItem, skipped, sleep })
  expect(searchIds).toHaveBeenCalledTimes(1)
  expect(sleep).toHaveBeenCalledWith(2000)
  expect(store.createGame).toHaveBeenCalledWith(expect.objectContaining(
    { name: 'Lupi Mannari di Roccascura', bggId: 25821, bggRating: 7.2 }))
  expect(report).toMatchObject({ linked: 1, viaSearch: 1, onlineRequests: 2 })
  expect(skipped.record).not.toHaveBeenCalled()
})

test('budget online: 12 candidati -> al massimo ONLINE_BUDGET ricerche', async () => {
  const products = Array.from({ length: 12 }, (_, i) => ({ id: i + 1, title: `Gioco ${i}`, normalizedTitle: `gioco ${i}` }))
  const store = storeBase(products)
  const searchIds = vi.fn().mockResolvedValue([])
  const sleep = vi.fn().mockResolvedValue(undefined)
  const report = await runBggEnrichment({ store, ranks: buildRanksIndex([]), searchIds, fetchGeekItem: vi.fn(), skipped: skipBase(), sleep })
  expect(searchIds).toHaveBeenCalledTimes(ONLINE_BUDGET)
  expect(report.viaCsv).toBe(0)
  expect(report.unresolved).toBe(12)
})

test('skip-file: prodotto a 3 tentativi non consuma slot online', async () => {
  const store = storeBase([{ id: 1, title: 'Morto', normalizedTitle: 'morto' }, { id: 2, title: 'Vivo', normalizedTitle: 'vivo' }])
  const skipped = skipBase()
  skipped.shouldSkip.mockImplementation((t: string) => t === 'morto')
  const searchIds = vi.fn().mockResolvedValue([])
  await runBggEnrichment({ store, ranks: buildRanksIndex([]), searchIds, fetchGeekItem: vi.fn(), skipped })
  expect(searchIds).toHaveBeenCalledTimes(1)
  expect(skipped.record).toHaveBeenCalledWith('vivo')
})

test('trasporto null: retry una volta, poi stop silenzioso senza errori', async () => {
  const store = storeBase([{ id: 1, title: 'X', normalizedTitle: 'x' }])
  const searchIds = vi.fn().mockResolvedValue(null)
  const sleep = vi.fn().mockResolvedValue(undefined)
  const report = await runBggEnrichment({ store, ranks: buildRanksIndex([]), searchIds, fetchGeekItem: vi.fn(), skipped: skipBase(), sleep })
  expect(searchIds).toHaveBeenCalledTimes(2)
  expect(sleep).toHaveBeenCalledWith(30000)
  expect(report.error).toBeUndefined()
  expect(report.csvMissing).toBe(false)
})

test('CSV assente: passata intera saltata, nessuna chiamata online', async () => {
  const store = storeBase([])
  const searchIds = vi.fn()
  const report = await runBggEnrichment({ store, ranks: null, searchIds, fetchGeekItem: vi.fn(), skipped: skipBase() })
  expect(searchIds).not.toHaveBeenCalled()
  expect(store.findUnlinkedProducts).not.toHaveBeenCalled()
  expect(report.csvMissing).toBe(true)
})

test('errore su un prodotto non ferma la passata, finisce in error', async () => {
  const idx = buildRanksIndex(parseRanksCsv(
    HEADER + '\n1,"Carcassonne",2000,10,7,7.4,100,0,,\n5,"Cascadia",2021,20,7,7.9,50,0,,\n'))
  const store = storeBase([
    { id: 1, title: 'Carcassonne', normalizedTitle: 'carcassonne' },
    { id: 2, title: 'Cascadia', normalizedTitle: 'cascadia' },
  ])
  store.createGame.mockRejectedValueOnce(new Error('db down'))
  const report = await runBggEnrichment({
    store, ranks: idx, searchIds: vi.fn().mockResolvedValue([]), fetchGeekItem: vi.fn(), skipped: skipBase(),
  })
  expect(report.error).toBe('db down')
  expect(report.linked).toBe(1)
  expect(store.setProductGame).toHaveBeenCalledTimes(1)
})
