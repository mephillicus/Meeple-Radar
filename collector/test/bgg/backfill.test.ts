import { expect, test, vi } from 'vitest'
import { runBggBackfill } from '../../src/bgg/backfill.js'
import { buildRanksIndex, type RankRow } from '../../src/bgg/csv-ranks.js'
import type { GeekItem } from '../../src/bgg/online.js'
import type { SkipStore } from '../../src/bgg/skipped.js'

const row = (bggId: number, name: string, isExpansion = false): RankRow => ({
  bggId, name, year: 2020, rank: 100, average: 7.5, usersrated: 1000, isExpansion,
})

const product = (id: number, title: string, releaseDate: string | null = null) => ({
  id, title, normalizedTitle: title.toLowerCase(), releaseDate,
})

const fakeStore = (products: ReturnType<typeof product>[]) => ({
  findUnlinkedProducts: vi.fn().mockResolvedValue(products),
  findGameByBggId: vi.fn().mockResolvedValue(null),
  createGame: vi.fn().mockResolvedValue(100),
  setProductGame: vi.fn().mockResolvedValue(true),
  fillListingGame: vi.fn().mockResolvedValue(2),
})

const fakeSkipped = (): SkipStore & { record: ReturnType<typeof vi.fn> } => ({
  shouldSkip: vi.fn().mockReturnValue(false),
  record: vi.fn().mockReturnValue(1),
  save: vi.fn(),
})

const noopSleep = () => Promise.resolve()

test('assegna via CSV esatto-unico e collega listing', async () => {
  const store = fakeStore([product(1, 'Ark Nova')])
  const ranks = buildRanksIndex([row(13, 'Ark Nova')])
  const report = await runBggBackfill({
    store: store as never,
    ranks,
    searchIds: vi.fn(),
    fetchGeekItem: vi.fn(),
    skipped: fakeSkipped(),
    maxOnlineRequests: 0,
    sleep: noopSleep,
    loadCaptured: () => ({}),
    saveCaptured: vi.fn(),
  })
  expect(report.linkedCsv).toBe(1)
  expect(store.setProductGame).toHaveBeenCalledWith(1, 100)
  expect(store.createGame).toHaveBeenCalledWith(
    expect.objectContaining({ bggId: 13, kind: 'base', name: 'Ark Nova' }),
  )
  expect(store.fillListingGame).toHaveBeenCalledWith(1, 100)
})

test('assegna via similarita dal CSV (refuso) senza toccare la rete', async () => {
  const store = fakeStore([product(1, 'Carcasonne')])
  const ranks = buildRanksIndex([row(10, 'Carcassonne')])
  const report = await runBggBackfill({
    store: store as never,
    ranks,
    searchIds: vi.fn(),
    fetchGeekItem: vi.fn(),
    skipped: fakeSkipped(),
    maxOnlineRequests: 0,
    sleep: noopSleep,
    loadCaptured: () => ({}),
    saveCaptured: vi.fn(),
  })
  expect(report.linkedFuzzyCsv).toBe(1)
  expect(report.onlineRequests).toBe(0)
})

test('online: il nome alternativo BGG assegna il gioco (caso Dobble/Spot it!)', async () => {
  const store = fakeStore([product(1, 'Dobble', '2026-12-01')])
  const ranks = buildRanksIndex([row(63268, 'Spot it!')])
  const items: GeekItem[] = [
    { bggId: 63268, name: 'Spot it!', subtype: 'boardgame', alternateNames: ['Dobble'], yearpublished: 2009 },
  ]
  const report = await runBggBackfill({
    store: store as never,
    ranks,
    searchIds: vi.fn().mockResolvedValue([63268]),
    fetchGeekItem: vi.fn().mockResolvedValue(items[0]),
    skipped: fakeSkipped(),
    maxOnlineRequests: 10,
    sleep: noopSleep,
    loadCaptured: () => ({}),
    saveCaptured: vi.fn(),
  })
  expect(report.linkedOnline).toBe(1)
  expect(store.setProductGame).toHaveBeenCalledWith(1, 100)
  // Spot it! è base nel CSV: kind base, non espansione.
  expect(store.createGame).toHaveBeenCalledWith(expect.objectContaining({ kind: 'base' }))
})

test('online incerto: niente assegnazione, candidati catturati e skip registrato', async () => {
  const store = fakeStore([product(1, 'Il Gioco Che Non Esiste')])
  const ranks = buildRanksIndex([])
  const skipped = fakeSkipped()
  const saveCaptured = vi.fn()
  const report = await runBggBackfill({
    store: store as never,
    ranks,
    searchIds: vi.fn().mockResolvedValue([99]),
    fetchGeekItem: vi.fn().mockResolvedValue({
      bggId: 99, name: 'Un Altro Gioco', subtype: 'boardgame', alternateNames: [], yearpublished: 2001,
    }),
    skipped,
    maxOnlineRequests: 10,
    sleep: noopSleep,
    loadCaptured: () => ({}),
    saveCaptured,
  })
  expect(report.linkedOnline).toBe(0)
  expect(skipped.record).toHaveBeenCalledWith('il gioco che non esiste')
  // Il flush avviene a ogni cattura, non solo a fine giro: un'interruzione
  // non deve perdere il lavoro fatto.
  expect(skipped.save).toHaveBeenCalledTimes(2)
  expect(saveCaptured).toHaveBeenCalledTimes(2)
  const captured = saveCaptured.mock.calls[0][0] as Record<string, unknown[]>
  expect(Object.keys(captured)).toContain('il gioco che non esiste')
})

test('il budget online ferma il pass prima di esaurire i prodotti', async () => {
  const store = fakeStore([product(1, 'Gioco Uno'), product(2, 'Gioco Due')])
  const ranks = buildRanksIndex([])
  const searchIds = vi.fn().mockResolvedValue([99])
  const report = await runBggBackfill({
    store: store as never,
    ranks,
    searchIds,
    fetchGeekItem: vi.fn().mockResolvedValue({
      bggId: 99, name: 'Niente A Che Vedere', subtype: 'boardgame', alternateNames: [], yearpublished: 2001,
    }),
    skipped: fakeSkipped(),
    maxOnlineRequests: 1,
    sleep: noopSleep,
    loadCaptured: () => ({}),
    saveCaptured: vi.fn(),
  })
  expect(report.stopped).toBe(true)
  expect(searchIds).toHaveBeenCalledTimes(1)
  expect(report.remaining).toBe(2)
})

test('i titoli gia falliti tre volte non consumano il budget online', async () => {
  const store = fakeStore([product(1, 'Gia Tentato'), product(2, 'Gioco Nuovo')])
  const ranks = buildRanksIndex([])
  const searchIds = vi.fn().mockResolvedValue([])
  const skipped = fakeSkipped()
  skipped.shouldSkip = vi.fn((nt: string) => nt === 'gia tentato')
  await runBggBackfill({
    store: store as never,
    ranks,
    searchIds,
    fetchGeekItem: vi.fn().mockResolvedValue(null),
    skipped,
    maxOnlineRequests: 1,
    sleep: noopSleep,
    loadCaptured: () => ({}),
    saveCaptured: vi.fn(),
  })
  // Solo il titolo non-skippato arriva alla ricerca.
  expect(searchIds).toHaveBeenCalledTimes(1)
})

test('rete giu due volte di fila: il pass online si ferma senza assegnare', async () => {
  const store = fakeStore([product(1, 'Gioco Uno')])
  const ranks = buildRanksIndex([])
  const searchIds = vi.fn().mockResolvedValue(null)
  const report = await runBggBackfill({
    store: store as never,
    ranks,
    searchIds,
    fetchGeekItem: vi.fn(),
    skipped: fakeSkipped(),
    maxOnlineRequests: 10,
    sleep: noopSleep,
    loadCaptured: () => ({}),
    saveCaptured: vi.fn(),
  })
  expect(report.stopped).toBe(true)
  expect(report.linkedOnline).toBe(0)
  expect(searchIds).toHaveBeenCalledTimes(2)
})

test('CSV mancante: report csvMissing e nessuna assegnazione', async () => {
  const store = fakeStore([product(1, 'Qualsiasi')])
  const report = await runBggBackfill({
    store: store as never,
    ranks: null,
    searchIds: vi.fn(),
    fetchGeekItem: vi.fn(),
    skipped: fakeSkipped(),
    maxOnlineRequests: 0,
    sleep: noopSleep,
    loadCaptured: () => ({}),
    saveCaptured: vi.fn(),
  })
  expect(report.csvMissing).toBe(true)
  expect(store.setProductGame).not.toHaveBeenCalled()
})
