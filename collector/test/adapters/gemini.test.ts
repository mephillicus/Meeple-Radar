import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { fetchGeminiProducts } from '../../src/adapters/gemini.js'
import type { GameFinder, Source } from '../../src/types.js'

const source: Source = { id: 'gemini', name: 'Gemini', kind: 'gemini', baseUrl: 'https://e.com', enabled: true }

const fetchMock = vi.fn()

const geminiOk = (text: string) =>
  fetchMock.mockResolvedValue({
    status: 200,
    ok: true,
    json: async () => ({ candidates: [{ content: { parts: [{ text }] } }] }),
    text: async () => text,
  } as any)

beforeEach(() => {
  vi.setSystemTime(new Date('2026-10-15T12:00:00Z'))
  fetchMock.mockClear()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
  delete process.env.GEMINI_API_KEY
})

test('throws if GEMINI_API_KEY is missing, without calling the API', async () => {
  delete process.env.GEMINI_API_KEY
  await expect(fetchGeminiProducts(source)).rejects.toThrow(/GEMINI_API_KEY/i)
  expect(fetchMock).not.toHaveBeenCalled()
})

test('POSTs the prompt to the Gemini API', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  geminiOk('```json\n[{"title": "Carcassonne"}]```')
  await fetchGeminiProducts(source)
  expect(fetchMock).toHaveBeenCalledTimes(1)
  const [url, init] = fetchMock.mock.calls[0]
  expect(url).toBe('https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=test-key')
  expect(init.method).toBe('POST')
  expect(init.headers).toMatchObject({ 'Content-Type': 'application/json' })
  const body = JSON.parse(init.body as string)
  expect(body.contents[0].parts[0].text).toContain('ottobre')
  expect(body.contents[0].parts[0].text).toContain('novembre')
  expect(body.contents[0].parts[0].text).toContain('2026')
})

test('parses a fenced JSON array and skips entries without a title', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  geminiOk('```json\n[\n  {"title": "Carcassonne", "publisher": "Ghenos Games"},\n  {"title": "Cascadia", "publisher": "Giochi Uniti"},\n  {"publisher": "Senza titolo"}\n]```')
  const products = await fetchGeminiProducts(source)
  expect(products).toHaveLength(2)
  expect(products[0].title).toBe('Carcassonne')
  expect(products[1].title).toBe('Cascadia')
})

test('keeps the raw price and detects the currency', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  geminiOk('```json\n[{"title": "X", "price": "19,99 €"}]```')
  const [p] = await fetchGeminiProducts(source)
  expect(p.priceRaw).toBe('19,99')
  expect(p.currencyRaw).toBe('EUR')
})

test('derives externalId from articleCode or from the normalized title', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  geminiOk('```json\n[\n  {"title": "Terra Mystica", "articleCode": "TM-01"},\n  {"title": "Carcassonne: Nuova Edizione"}\n]```')
  const products = await fetchGeminiProducts(source)
  expect(products[0].externalId).toBe('TM-01')
  expect(products[1].externalId).toBe('carcassonne-nuova-edizione')
})

test('builds a DuckDuckGo search URL from title and publisher', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  geminiOk('```json\n[{"title": "Carcassonne", "publisher": "Ghenos Games"}]```')
  const [p] = await fetchGeminiProducts(source)
  expect(p.url).toBe(`https://duckduckgo.com/?q=${encodeURIComponent('Carcassonne Ghenos Games')}`)
})

test('enriches with the gameFinder match; model values fill the gaps', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  const gameFinder: GameFinder = vi.fn().mockResolvedValue({
    gameId: 7, price: 19.99, currency: 'EUR', releaseDate: '2026-11-10', imageUrl: 'https://e.com/match.jpg',
  })
  geminiOk('```json\n[{"title": "Carcassonne", "publisher": "Ghenos Games", "price": "29,90 €", "releaseDate": "2026-11-05", "description": "Un titolo"}]```')
  const [p] = await fetchGeminiProducts(source, gameFinder)
  expect(gameFinder).toHaveBeenCalledWith('carcassonne', 'Ghenos Games')
  expect(p.priceRaw).toBe('19.99')
  expect(p.currencyRaw).toBe('EUR')
  expect(p.releaseDateRaw).toBe('2026-11-10')
  expect(p.imageUrl).toBe('https://e.com/match.jpg')
  expect(p.gameId).toBe(7)
})

test('lets model values fill the gaps the match lacks', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  const gameFinder: GameFinder = vi.fn().mockResolvedValue({
    gameId: 3, price: null, currency: null, releaseDate: '2026-12-01', imageUrl: null,
  })
  geminiOk('```json\n[{"title": "X", "price": "10,00 €"}]```')
  const [p] = await fetchGeminiProducts(source, gameFinder)
  expect(p.priceRaw).toBe('10,00')
  expect(p.releaseDateRaw).toBe('2026-12-01')
  expect(p.imageUrl).toBeUndefined()
  expect(p.gameId).toBe(3)
})

test('keeps model data and leaves gameId undefined when the finder returns null', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  const gameFinder: GameFinder = vi.fn().mockResolvedValue(null)
  geminiOk('```json\n[{"title": "Cascadia", "price": "39,50 €"}]```')
  const [p] = await fetchGeminiProducts(source, gameFinder)
  expect(p.priceRaw).toBe('39,50')
  expect(p.imageUrl).toBeUndefined()
  expect(p.gameId).toBeUndefined()
})

test('returns [] without throwing when nothing can be parsed as an array', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  geminiOk('non è JSON')
  expect(await fetchGeminiProducts(source)).toEqual([])
  geminiOk(JSON.stringify({ foo: 1 }))
  expect(await fetchGeminiProducts(source)).toEqual([])
  geminiOk('[]')
  expect(await fetchGeminiProducts(source)).toEqual([])
})

test('a dicembre chiede gennaio dell’anno successivo', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  vi.setSystemTime(new Date('2026-12-15T12:00:00Z'))
  let corpo: string | undefined
  fetchMock.mockImplementation(async (_url: string, init: any) => {
    corpo = init.body as string
    return { status: 200, ok: true, json: async () => ({ candidates: [{ content: { parts: [{ text: '[]' }] } }] }), text: async () => '' } as any
  })
  await fetchGeminiProducts(source)
  expect(corpo).toContain('gennaio 2027')
})

test('con HTTP 200 e corpo non JSON restituisce [] senza lanciare', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  fetchMock.mockResolvedValue({
    status: 200, ok: true,
    json: async () => { throw new Error('non è JSON') },
    text: async () => 'ciao',
  } as any)
  expect(await fetchGeminiProducts(source)).toEqual([])
})
