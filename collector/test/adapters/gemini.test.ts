import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { fetchGeminiProducts } from '../../src/adapters/gemini.js'
import { RESPONSE_TIMEOUT_MS } from '../../src/adapters/ai-providers.js'
import type { GameFinder, Source } from '../../src/types.js'

const source: Source = { id: 'gemini', name: 'Gemini', kind: 'gemini', baseUrl: 'https://e.com', enabled: true }

// Il backoff tra le retry è reale: in test lo disattiviamo per non rallentare la suite.
const noSleep = async () => {}

const fetchMock = vi.fn()

const ENV_KEYS = ['GEMINI_API_KEY', 'GEMINI_MODEL', 'GEMINI_FALLBACK_MODEL']

const geminiOk = (text: string) => ({
  status: 200,
  ok: true,
  json: async () => ({ candidates: [{ content: { parts: [{ text }] } }] }),
  text: async () => text,
})

const fail = (status: number, body = '') => ({
  status,
  ok: false,
  json: async () => ({}),
  text: async () => body,
})

const MODELLO = (url: string) => String(url).match(/models\/([^:]+):/)?.[1] ?? ''

beforeEach(() => {
  vi.setSystemTime(new Date('2026-10-15T12:00:00Z'))
  fetchMock.mockClear()
  vi.stubGlobal('fetch', fetchMock)
  for (const k of ENV_KEYS) delete process.env[k]
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  for (const k of ENV_KEYS) delete process.env[k]
})

test('lancia se GEMINI_API_KEY manca, senza chiamare l’API', async () => {
  await expect(fetchGeminiProducts(source, undefined, { sleep: noSleep })).rejects.toThrow(/GEMINI_API_KEY/i)
  expect(fetchMock).not.toHaveBeenCalled()
})

test('POST con la chiave nell’header e non nell’URL', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  fetchMock.mockResolvedValue(geminiOk('[{"title": "Carcassonne"}]'))
  await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(fetchMock).toHaveBeenCalledTimes(1)
  const [url, init] = fetchMock.mock.calls[0]
  // La chiave nell'URL finirebbe nei log in caso di errore.
  expect(String(url)).not.toContain('test-key')
  expect(String(url)).toContain('models/gemini-3.5-flash-lite:generateContent')
  expect(init.headers['x-goog-api-key']).toBe('test-key')
  expect(init.method).toBe('POST')
  const body = JSON.parse(init.body as string)
  expect(body.contents[0].parts[0].text).toContain('ottobre')
  expect(body.contents[0].parts[0].text).toContain('novembre')
  expect(body.contents[0].parts[0].text).toContain('2026')
})

test('obbliga il JSON mode e non attacha alcun tool', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  fetchMock.mockResolvedValue(geminiOk('[{"title": "X"}]'))
  await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  const body = JSON.parse(fetchMock.mock.calls[0][1].body as string)
  // Nessun grounding: il tier gratuito non ha quota per la ricerca web, e i dati arrivano
  // già dentro il prompt.
  expect(body.tools).toBeUndefined()
  expect(body.generationConfig.responseMimeType).toBe('application/json')
  expect(body.generationConfig.maxOutputTokens).toBe(8192)
})

test('legge un array tra blocchi di codice e scarta le voci senza titolo', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  fetchMock.mockResolvedValue(geminiOk('```json\n[\n  {"title": "Carcassonne", "publisher": "Ghenos Games"},\n  {"title": "Cascadia", "publisher": "Giochi Uniti"},\n  {"publisher": "Senza titolo"}\n]```'))
  const products = await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(products).toHaveLength(2)
  expect(products[0].title).toBe('Carcassonne')
  expect(products[1].title).toBe('Cascadia')
})

test('tiene il prezzo grezzo e riconosce la valuta', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  fetchMock.mockResolvedValue(geminiOk('[{"title": "X", "price": "19,99 €"}]'))
  const [p] = await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(p.priceRaw).toBe('19,99')
  expect(p.currencyRaw).toBe('EUR')
})

test('deriva externalId dal codice articolo o dal titolo normalizzato', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  fetchMock.mockResolvedValue(geminiOk('[\n  {"title": "Terra Mystica", "articleCode": "TM-01"},\n  {"title": "Carcassonne: Nuova Edizione"}\n]'))
  const products = await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(products[0].externalId).toBe('TM-01')
  expect(products[1].externalId).toBe('carcassonne-nuova-edizione')
})

test('costruisce l’URL di ricerca DuckDuckGo da titolo ed editore', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  fetchMock.mockResolvedValue(geminiOk('[{"title": "Carcassonne", "publisher": "Ghenos Games"}]'))
  const [p] = await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(p.url).toBe(`https://duckduckgo.com/?q=${encodeURIComponent('Carcassonne Ghenos Games')}`)
})

test('arricchisce con il match del gameFinder; i valori del modello colmano i buchi', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  const gameFinder: GameFinder = vi.fn().mockResolvedValue({
    gameId: 7, price: 19.99, currency: 'EUR', releaseDate: '2026-11-10', imageUrl: 'https://e.com/match.jpg',
  })
  fetchMock.mockResolvedValue(geminiOk('[{"title": "Carcassonne", "publisher": "Ghenos Games", "price": "29,90 €", "releaseDate": "2026-11-05", "description": "Un titolo"}]'))
  const [p] = await fetchGeminiProducts(source, gameFinder, { sleep: noSleep })
  expect(gameFinder).toHaveBeenCalledWith('carcassonne', 'Ghenos Games')
  expect(p.priceRaw).toBe('19.99')
  expect(p.currencyRaw).toBe('EUR')
  expect(p.releaseDateRaw).toBe('2026-11-10')
  expect(p.imageUrl).toBe('https://e.com/match.jpg')
  expect(p.gameId).toBe(7)
})

test('i valori del modello colmano i buchi lasciati dal match', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  const gameFinder: GameFinder = vi.fn().mockResolvedValue({
    gameId: 3, price: null, currency: null, releaseDate: '2026-12-01', imageUrl: null,
  })
  fetchMock.mockResolvedValue(geminiOk('[{"title": "X", "price": "10,00 €"}]'))
  const [p] = await fetchGeminiProducts(source, gameFinder, { sleep: noSleep })
  expect(p.priceRaw).toBe('10,00')
  expect(p.releaseDateRaw).toBe('2026-12-01')
  expect(p.imageUrl).toBeUndefined()
  expect(p.gameId).toBe(3)
})

test('tieni i dati del modello e lascia gameId indefinito se il finder torna null', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  const gameFinder: GameFinder = vi.fn().mockResolvedValue(null)
  fetchMock.mockResolvedValue(geminiOk('[{"title": "Cascadia", "price": "39,50 €"}]'))
  const [p] = await fetchGeminiProducts(source, gameFinder, { sleep: noSleep })
  expect(p.priceRaw).toBe('39,50')
  expect(p.imageUrl).toBeUndefined()
  expect(p.gameId).toBeUndefined()
})

test('a dicembre chiede gennaio dell’anno successivo', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  vi.setSystemTime(new Date('2026-12-15T12:00:00Z'))
  let corpo: string | undefined
  fetchMock.mockImplementation(async (_url: string, init: any) => {
    corpo = init.body as string
    return geminiOk('[]')
  })
  await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(corpo).toContain('gennaio 2027')
})

test('con HTTP 200 e corpo non JSON restituisce [] senza lanciare', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  fetchMock.mockResolvedValue({
    status: 200, ok: true,
    json: async () => { throw new Error('non è JSON') },
    text: async () => 'ciao',
  } as any)
  expect(await fetchGeminiProducts(source, undefined, { sleep: noSleep })).toEqual([])
})

test('scarta le parti di ragionamento (thought) dei modelli 3.x', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  fetchMock.mockResolvedValue({
    status: 200, ok: true,
    json: async () => ({
      candidates: [{ content: { parts: [
        { text: 'sto ragionando...', thought: true },
        { text: '[{"title": "Carcassonne"}]' },
      ] } }],
    }),
    text: async () => '',
  } as any)
  const products = await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(products).toHaveLength(1)
  expect(products[0].title).toBe('Carcassonne')
})

test('ritenta su 503 transitorio e poi riesce sullo stesso modello', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  let calls = 0
  fetchMock.mockImplementation(async () => {
    calls++
    if (calls === 1) return fail(503, 'high demand')
    return geminiOk('[{"title": "X"}]')
  })
  const products = await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(calls).toBe(2)
  expect(MODELLO(fetchMock.mock.calls[1][0])).toBe('gemini-3.5-flash-lite')
  expect(products).toHaveLength(1)
})

test('un 429 da limite giornaliero non viene ritentato', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  let calls = 0
  fetchMock.mockImplementation(async () => {
    calls++
    return fail(429, 'Quota exceeded for quota metric free-model-requests-per-day')
  })
  await expect(fetchGeminiProducts(source, undefined, { sleep: noSleep })).rejects.toThrow()
  // Uno per il primario e uno per il ripiego: nessuno dei due insiste.
  expect(calls).toBe(2)
})

test('un 429 da limite al minuto viene ritentato', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  let calls = 0
  fetchMock.mockImplementation(async () => {
    calls++
    if (calls === 1) return fail(429, 'Resource has been exhausted (e.g. check quota).')
    return geminiOk('[{"title": "X"}]')
  })
  const products = await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(calls).toBe(2)
  expect(products).toHaveLength(1)
})

test('non ritenta un errore HTTP non ripristinabile', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  let calls = 0
  fetchMock.mockImplementation(async () => {
    calls++
    return fail(404, 'model not found')
  })
  await expect(fetchGeminiProducts(source, undefined, { sleep: noSleep })).rejects.toThrow(/404.*model not found/s)
  // 404 non è transitorio: né il primario né il ripiego ci riprovano.
  expect(calls).toBe(2)
})

test('cascata: se il primario fallisce passa al secondo modello Gemini', async () => {
  process.env.GEMINI_API_KEY = 'g'
  fetchMock.mockImplementation(async (url: string) =>
    MODELLO(url) === 'gemini-3.5-flash-lite' ? fail(503, 'high demand') : geminiOk('[{"title": "Carcassonne", "publisher": "Ghenos Games"}]'),
  )
  const products = await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(products).toHaveLength(1)
  expect(products[0].title).toBe('Carcassonne')
  // 5 tentativi sul primario, poi il ripiego.
  expect(fetchMock).toHaveBeenCalledTimes(6)
  expect(MODELLO(fetchMock.mock.calls[5][0])).toBe('gemini-3.6-flash')
})

test('dà priorità al primario: insiste di più prima di cedere il turno', async () => {
  process.env.GEMINI_API_KEY = 'g'
  const perModello = new Map<string, number>()
  fetchMock.mockImplementation(async (url: string) => {
    const m = MODELLO(url)
    perModello.set(m, (perModello.get(m) ?? 0) + 1)
    return fail(503, 'high demand')
  })
  await expect(fetchGeminiProducts(source, undefined, { sleep: noSleep })).rejects.toThrow()
  expect(perModello.get('gemini-3.5-flash-lite')).toBe(5)
  expect(perModello.get('gemini-3.6-flash')).toBe(4)
})

test('passa al modello di ripiego quando l’array è vuoto', async () => {
  process.env.GEMINI_API_KEY = 'g'
  fetchMock.mockImplementation(async (url: string) =>
    MODELLO(url) === 'gemini-3.5-flash-lite' ? geminiOk('{"releases": []}') : geminiOk('[{"title": "Cascadia"}]'),
  )
  const products = await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(products).toHaveLength(1)
  expect(products[0].title).toBe('Cascadia')
  expect(MODELLO(fetchMock.mock.calls[fetchMock.mock.calls.length - 1][0])).toBe('gemini-3.6-flash')
})

test('passa al modello di ripiego quando la risposta non è interpretabile', async () => {
  process.env.GEMINI_API_KEY = 'g'
  fetchMock.mockImplementation(async (url: string) =>
    MODELLO(url) === 'gemini-3.5-flash-lite' ? geminiOk('non è JSON') : geminiOk('[{"title": "Cascadia"}]'),
  )
  const products = await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(products).toHaveLength(1)
  expect(products[0].title).toBe('Cascadia')
})

test('invia lo stesso prompt a entrambi i modelli', async () => {
  process.env.GEMINI_API_KEY = 'g'
  const prompts = new Map<string, string>()
  fetchMock.mockImplementation(async (url: string, init: any) => {
    const m = MODELLO(url)
    prompts.set(m, JSON.parse(init.body as string).contents[0].parts[0].text)
    return m === 'gemini-3.5-flash-lite' ? fail(503) : geminiOk('[{"title": "X"}]')
  })
  await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(prompts.get('gemini-3.5-flash-lite')).toBe(prompts.get('gemini-3.6-flash'))
  expect(prompts.get('gemini-3.5-flash-lite')).toContain('ottobre')
})

test('rispetta GEMINI_MODEL e GEMINI_FALLBACK_MODEL', async () => {
  process.env.GEMINI_API_KEY = 'g'
  process.env.GEMINI_MODEL = 'alpha'
  process.env.GEMINI_FALLBACK_MODEL = 'beta'
  const modelli: string[] = []
  fetchMock.mockImplementation(async (url: string) => {
    const m = MODELLO(url)
    modelli.push(m)
    return m === 'alpha' ? fail(503) : geminiOk('[{"title": "X"}]')
  })
  await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(modelli[0]).toBe('alpha')
  expect(modelli.at(-1)).toBe('beta')
  expect(modelli).not.toContain('gemini-3.5-flash-lite')
})

test('se entrambi i modelli rispondono [] restituisce [] senza lanciare', async () => {
  process.env.GEMINI_API_KEY = 'g'
  fetchMock.mockResolvedValue(geminiOk('{"releases": []}'))
  expect(await fetchGeminiProducts(source, undefined, { sleep: noSleep })).toEqual([])
  // Un array vuoto è una risposta valida, non un errore: una chiamata per modello e basta.
  expect(fetchMock).toHaveBeenCalledTimes(2)
})

test('se nessun provider risponde lancia con tutti gli errori', async () => {
  process.env.GEMINI_API_KEY = 'g'
  fetchMock.mockResolvedValue(fail(401, 'invalid api key'))
  await expect(fetchGeminiProducts(source, undefined, { sleep: noSleep })).rejects.toThrow(/gemini.*gemini-fallback/s)
  await expect(fetchGeminiProducts(source, undefined, { sleep: noSleep })).rejects.toThrow(/401/)
})

test('riporta il motivo di una risposta bloccata', async () => {
  process.env.GEMINI_API_KEY = 'g'
  const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
  fetchMock.mockResolvedValue({
    status: 200, ok: true,
    json: async () => ({ candidates: [{ content: { parts: [] } }], promptFeedback: { blockReason: 'SAFETY' } }),
    text: async () => '',
  } as any)
  await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(spy).toHaveBeenCalledWith(expect.stringContaining('bloccato (SAFETY)'))
})

test('annulla la richiesta dopo 3 minuti', () => {
  expect(RESPONSE_TIMEOUT_MS).toBe(3 * 60 * 1000)
})

test('passa un AbortSignal a ogni chiamata', async () => {
  process.env.GEMINI_API_KEY = 'g'
  const segnali: (AbortSignal | null | undefined)[] = []
  fetchMock.mockImplementation(async (_url: string, init: any) => {
    segnali.push(init.signal)
    return geminiOk('[{"title": "X"}]')
  })
  await fetchGeminiProducts(source, undefined, { sleep: noSleep, timeoutMs: 1234 })
  expect(segnali).toHaveLength(1)
  expect(segnali[0]).toBeInstanceOf(AbortSignal)
})

test('scarta un array avvolto in un oggetto', async () => {
  process.env.GEMINI_API_KEY = 'g'
  fetchMock.mockResolvedValue(geminiOk('{"releases": [{"title": "Carcassonne", "publisher": "Ghenos Games"}]}'))
  const products = await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(products).toHaveLength(1)
  expect(products[0].title).toBe('Carcassonne')
})

test('estrae il JSON anche quando il modello aggiunge prosa attorno', async () => {
  process.env.GEMINI_API_KEY = 'g'
  fetchMock.mockResolvedValue(geminiOk('Ho cercato sul web e questi sono i risultati.\n\n```json\n{"releases": [{"title": "Carcassonne"}]}\n```\n\nFonti: esempio.com'))
  const products = await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(products).toHaveLength(1)
  expect(products[0].title).toBe('Carcassonne')
})

test('parentesi dentro una stringa non sbilanciano l’estrazione', async () => {
  process.env.GEMINI_API_KEY = 'g'
  fetchMock.mockResolvedValue(geminiOk('{"releases": [{"title": "Carcassonne [edizione 2026]", "description": "con {tessere} extra"}]}'))
  const products = await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(products).toHaveLength(1)
  expect(products[0].title).toBe('Carcassonne [edizione 2026]')
})

test('senza chiave lancia un errore chiaro', async () => {
  await expect(fetchGeminiProducts(source, undefined, { sleep: noSleep })).rejects.toThrow(/GEMINI_API_KEY/)
  expect(fetchMock).not.toHaveBeenCalled()
})
