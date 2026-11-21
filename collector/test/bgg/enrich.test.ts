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
const geek = (
  id: number,
  name: string,
  opts: {
    year?: number
    alt?: string[]
    subtype?: string
    imageUrl?: string | null
    description?: string | null
    expandsBggId?: number | null
    bggRating?: number | null
    bggRank?: number | null
  } = {},
): GeekItem => ({
  bggId: id,
  name,
  alternateNames: opts.alt ?? [],
  subtype: opts.subtype ?? 'boardgame',
  yearpublished: opts.year ?? 2019,
  ...(opts.imageUrl !== undefined ? { imageUrl: opts.imageUrl } : {}),
  ...(opts.description !== undefined ? { description: opts.description } : {}),
  ...(opts.expandsBggId !== undefined ? { expandsBggId: opts.expandsBggId } : {}),
  ...(opts.bggRating !== undefined ? { bggRating: opts.bggRating } : {}),
  ...(opts.bggRank !== undefined ? { bggRank: opts.bggRank } : {}),
})

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

test('budget online: oltre ONLINE_BUDGET candidati -> al massimo ONLINE_BUDGET ricerche', async () => {
  expect(ONLINE_BUDGET).toBe(25)
  const total = ONLINE_BUDGET + 2
  const products = Array.from({ length: total }, (_, i) => ({ id: i + 1, title: `Gioco ${i}`, normalizedTitle: `gioco ${i}` }))
  const store = storeBase(products)
  const searchIds = vi.fn().mockResolvedValue([])
  const sleep = vi.fn().mockResolvedValue(undefined)
  const report = await runBggEnrichment({ store, ranks: buildRanksIndex([]), searchIds, fetchGeekItem: vi.fn(), skipped: skipBase(), sleep })
  expect(searchIds).toHaveBeenCalledTimes(ONLINE_BUDGET)
  expect(report.viaCsv).toBe(0)
  expect(report.unresolved).toBe(total)
})

test('lato online: passa imageUrl, description ed expandsBggId a createGame senza toccare product.description', async () => {
  const store = {
    ...storeBase([{ id: 9, title: 'Agora (Edizione Italiana)', normalizedTitle: 'agora edizione italiana' }]),
    fillProductDescription: vi.fn(),
  } as any
  const sleep = vi.fn().mockResolvedValue(undefined)
  const searchIds = vi.fn().mockResolvedValue([309116])
  const fetchGeekItem = vi.fn().mockResolvedValue(
    geek(309116, 'Agora', {
      subtype: 'boardgameexpansion',
      year: 2020,
      imageUrl: 'https://cf.geekdo-images.com/agora.jpg',
      description: 'BGG expansion description',
      expandsBggId: 173346,
    }),
  )
  const report = await runBggEnrichment({
    store,
    ranks: buildRanksIndex([]),
    searchIds,
    fetchGeekItem,
    skipped: skipBase(),
    sleep,
  })
  expect(report.viaSearch).toBe(1)
  expect(store.createGame).toHaveBeenCalledWith(expect.objectContaining({
    name: 'Agora (Edizione Italiana)',
    kind: 'expansion',
    bggId: 309116,
    imageUrl: 'https://cf.geekdo-images.com/agora.jpg',
    description: 'BGG expansion description',
    expandsBggId: 173346,
  }))
  expect(store.fillProductDescription).not.toHaveBeenCalled()
})

test('lato CSV bulk: matcha anche titoli con suffisso edizione italiana via normalizeForBggMatch', async () => {
  const store = storeBase([
    { id: 1, title: 'Carcassonne (Edizione Italiana)', normalizedTitle: 'carcassonne edizione italiana' },
  ])
  const searchIds = vi.fn()
  const report = await runBggEnrichment({
    store,
    ranks: ranks(),
    searchIds,
    fetchGeekItem: vi.fn(),
    skipped: skipBase(),
  })
  expect(report.viaCsv).toBe(1)
  expect(searchIds).not.toHaveBeenCalled()
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

test('lato online con fetchGeekItems batch: 1 sola chiamata per fino a 3 candidati e fallback bggRating/bggRank', async () => {
  const store = {
    ...storeBase([{ id: 42, title: 'Gioco Nuovissimo', normalizedTitle: 'gioco nuovissimo' }]),
    fillProductDescription: vi.fn(),
  } as any
  const sleep = vi.fn().mockResolvedValue(undefined)
  const searchIds = vi.fn().mockResolvedValue([901, 902, 903, 904, 905])
  const fetchGeekItems = vi.fn().mockResolvedValue([
    geek(901, 'Altro Gioco', { year: 2024 }),
    geek(902, 'Brand New Game', {
      year: 2026,
      alt: ['Gioco Nuovissimo'],
      imageUrl: 'https://cf.geekdo-images.com/new.jpg',
      description: 'Fresh BGG description',
      bggRating: 7.85,
      bggRank: 312,
    }),
    geek(903, 'Terzo Gioco', { year: 2025 }),
  ])

  const report = await runBggEnrichment({
    store,
    ranks: buildRanksIndex([]),
    searchIds,
    fetchGeekItems,
    skipped: skipBase(),
    sleep,
  })

  expect(fetchGeekItems).toHaveBeenCalledTimes(1)
  expect(fetchGeekItems).toHaveBeenCalledWith([901, 902, 903])
  expect(report).toMatchObject({ linked: 1, viaSearch: 1, onlineRequests: 2 })
  expect(store.createGame).toHaveBeenCalledWith(expect.objectContaining({
    name: 'Gioco Nuovissimo',
    kind: 'base',
    bggId: 902,
    bggRating: 7.85,
    bggRank: 312,
    imageUrl: 'https://cf.geekdo-images.com/new.jpg',
    description: 'Fresh BGG description',
  }))
  expect(store.fillProductDescription).not.toHaveBeenCalled()
})

test('lato online con fetchGeekItems: CSV ha precedenza su bggRating/bggRank se presente', async () => {
  const idx = buildRanksIndex(parseRanksCsv(
    HEADER + '\n902,"Brand New Game",2026,15,7.5,8.1,500,0,,\n',
  ))
  const store = storeBase([{ id: 43, title: 'Gioco Nuovissimo', normalizedTitle: 'gioco nuovissimo' }])
  const sleep = vi.fn().mockResolvedValue(undefined)
  const searchIds = vi.fn().mockResolvedValue([902])
  const fetchGeekItems = vi.fn().mockResolvedValue([
    geek(902, 'Brand New Game', {
      year: 2026,
      alt: ['Gioco Nuovissimo'],
      bggRating: 7.0,
      bggRank: 999,
    }),
  ])

  const report = await runBggEnrichment({
    store,
    ranks: idx,
    searchIds,
    fetchGeekItems,
    skipped: skipBase(),
    sleep,
  })

  expect(report.viaSearch).toBe(1)
  expect(store.createGame).toHaveBeenCalledWith(expect.objectContaining({
    bggId: 902,
    bggRating: 8.1,
    bggRank: 15,
  }))
})

test('lato online con fetchGeekItems: ritorno null interrompe silenziosamente il loop online', async () => {
  const store = storeBase([
    { id: 1, title: 'Primo', normalizedTitle: 'primo' },
    { id: 2, title: 'Secondo', normalizedTitle: 'secondo' },
  ])
  const sleep = vi.fn().mockResolvedValue(undefined)
  const searchIds = vi.fn().mockResolvedValue([101, 102])
  const fetchGeekItems = vi.fn().mockResolvedValue(null)

  const report = await runBggEnrichment({
    store,
    ranks: buildRanksIndex([]),
    searchIds,
    fetchGeekItems,
    skipped: skipBase(),
    sleep,
  })

  expect(searchIds).toHaveBeenCalledTimes(1)
  expect(fetchGeekItems).toHaveBeenCalledTimes(1)
  expect(report.onlineRequests).toBe(2)
  expect(report.error).toBeUndefined()
})
