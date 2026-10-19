import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { fetchGeminiProducts } from '../../src/adapters/gemini.js'
import { RESPONSE_TIMEOUT_MS } from '../../src/adapters/ai-providers.js'
import type { GameFinder, Source } from '../../src/types.js'

const source: Source = { id: 'gemini', name: 'Gemini', kind: 'gemini', baseUrl: 'https://e.com', enabled: true }

// Il backoff tra le retry è reale: in test lo disattiviamo per non rallentare la suite.
const noSleep = async () => {}

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
  // Le chiavi devono essere esplicite: in CI GROQ_API_KEY/OPENROUTER_API_KEY possono essere presenti.
  delete process.env.GEMINI_API_KEY
  delete process.env.GROQ_API_KEY
  delete process.env.OPENROUTER_API_KEY
  delete process.env.GEMINI_MODEL
  delete process.env.GROQ_MODEL
  delete process.env.OPENROUTER_MODEL
})

afterEach(() => {
  vi.unstubAllGlobals()
  delete process.env.GEMINI_API_KEY
  delete process.env.GROQ_API_KEY
  delete process.env.OPENROUTER_API_KEY
  delete process.env.GEMINI_MODEL
  delete process.env.GROQ_MODEL
  delete process.env.OPENROUTER_MODEL
})

test('throws if GEMINI_API_KEY is missing, without calling the API', async () => {
  delete process.env.GEMINI_API_KEY
  await expect(fetchGeminiProducts(source, undefined, { sleep: noSleep })).rejects.toThrow(/GEMINI_API_KEY/i)
  expect(fetchMock).not.toHaveBeenCalled()
})

test('POSTs the prompt to the Gemini API', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  geminiOk('```json\n[{"title": "Carcassonne"}]```')
  await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(fetchMock).toHaveBeenCalledTimes(1)
  const [url, init] = fetchMock.mock.calls[0]
  expect(url).toBe('https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent?key=test-key')
  expect(init.method).toBe('POST')
  expect(init.headers).toMatchObject({ 'Content-Type': 'application/json' })
  const body = JSON.parse(init.body as string)
  // Il grounding richiede di non imporre il formato JSON: lo estrae invece il parser.
  expect(body.tools).toEqual([{ google_search: {} }])
  expect(body.generationConfig?.responseMimeType).toBeUndefined()
  expect(body.contents[0].parts[0].text).toContain('ottobre')
  expect(body.contents[0].parts[0].text).toContain('novembre')
  expect(body.contents[0].parts[0].text).toContain('2026')
})

test('parses a fenced JSON array and skips entries without a title', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  geminiOk('```json\n[\n  {"title": "Carcassonne", "publisher": "Ghenos Games"},\n  {"title": "Cascadia", "publisher": "Giochi Uniti"},\n  {"publisher": "Senza titolo"}\n]```')
  const products = await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(products).toHaveLength(2)
  expect(products[0].title).toBe('Carcassonne')
  expect(products[1].title).toBe('Cascadia')
})

test('keeps the raw price and detects the currency', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  geminiOk('```json\n[{"title": "X", "price": "19,99 €"}]```')
  const [p] = await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(p.priceRaw).toBe('19,99')
  expect(p.currencyRaw).toBe('EUR')
})

test('derives externalId from articleCode or from the normalized title', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  geminiOk('```json\n[\n  {"title": "Terra Mystica", "articleCode": "TM-01"},\n  {"title": "Carcassonne: Nuova Edizione"}\n]```')
  const products = await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(products[0].externalId).toBe('TM-01')
  expect(products[1].externalId).toBe('carcassonne-nuova-edizione')
})

test('builds a DuckDuckGo search URL from title and publisher', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  geminiOk('```json\n[{"title": "Carcassonne", "publisher": "Ghenos Games"}]```')
  const [p] = await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(p.url).toBe(`https://duckduckgo.com/?q=${encodeURIComponent('Carcassonne Ghenos Games')}`)
})

test('enriches with the gameFinder match; model values fill the gaps', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  const gameFinder: GameFinder = vi.fn().mockResolvedValue({
    gameId: 7, price: 19.99, currency: 'EUR', releaseDate: '2026-11-10', imageUrl: 'https://e.com/match.jpg',
  })
  geminiOk('```json\n[{"title": "Carcassonne", "publisher": "Ghenos Games", "price": "29,90 €", "releaseDate": "2026-11-05", "description": "Un titolo"}]```')
  const [p] = await fetchGeminiProducts(source, gameFinder, { sleep: noSleep })
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
  const [p] = await fetchGeminiProducts(source, gameFinder, { sleep: noSleep })
  expect(p.priceRaw).toBe('10,00')
  expect(p.releaseDateRaw).toBe('2026-12-01')
  expect(p.imageUrl).toBeUndefined()
  expect(p.gameId).toBe(3)
})

test('keeps model data and leaves gameId undefined when the finder returns null', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  const gameFinder: GameFinder = vi.fn().mockResolvedValue(null)
  geminiOk('```json\n[{"title": "Cascadia", "price": "39,50 €"}]```')
  const [p] = await fetchGeminiProducts(source, gameFinder, { sleep: noSleep })
  expect(p.priceRaw).toBe('39,50')
  expect(p.imageUrl).toBeUndefined()
  expect(p.gameId).toBeUndefined()
})

test('returns [] without throwing when nothing can be parsed as an array', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  geminiOk('non è JSON')
  expect(await fetchGeminiProducts(source, undefined, { sleep: noSleep })).toEqual([])
  geminiOk(JSON.stringify({ foo: 1 }))
  expect(await fetchGeminiProducts(source, undefined, { sleep: noSleep })).toEqual([])
  geminiOk('[]')
  expect(await fetchGeminiProducts(source, undefined, { sleep: noSleep })).toEqual([])
})

test('a dicembre chiede gennaio dell’anno successivo', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  vi.setSystemTime(new Date('2026-12-15T12:00:00Z'))
  let corpo: string | undefined
  fetchMock.mockImplementation(async (_url: string, init: any) => {
    corpo = init.body as string
    return { status: 200, ok: true, json: async () => ({ candidates: [{ content: { parts: [{ text: '[]' }] } }] }), text: async () => '' } as any
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

test('include il corpo della risposta nell’errore quando lo status non è ok', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  fetchMock.mockResolvedValue({
    status: 404, ok: false,
    json: async () => ({}),
    text: async () => '{"error":{"code":404,"message":"model not found"}}',
  } as any)
  await expect(fetchGeminiProducts(source, undefined, { sleep: noSleep })).rejects.toThrow(/404.*model not found/s)
})

test('ignora le parti di ragionamento (thought) dei modelli 3.x', async () => {
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

test('ritenta su 503 transitorio e poi riesce', async () => {
  process.env.GEMINI_API_KEY = 'test-key'
  let calls = 0
  fetchMock.mockImplementation(async () => {
    calls++
    if (calls === 1) return { status: 503, ok: false, json: async () => ({}), text: async () => 'high demand' } as any
    return { status: 200, ok: true, json: async () => ({ candidates: [{ content: { parts: [{ text: '[{"title": "X"}]' }] } }] }), text: async () => '' } as any
  })
  const products = await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(calls).toBe(2)
  expect(products).toHaveLength(1)
  expect(products[0].title).toBe('X')
})

const geminiResp = (text: string) => ({
  status: 200,
  ok: true,
  json: async () => ({ candidates: [{ content: { parts: [{ text }] } }] }),
  text: async () => text,
})

const chatOk = (content: string) => ({
  status: 200, ok: true,
  json: async () => ({ choices: [{ message: { content } }] }),
  text: async () => content,
})
const fail = (status: number, body = '') => ({
  status, ok: false, json: async () => ({}), text: async () => body,
})
const GEMINI_URL = 'generativelanguage.googleapis.com'
const GROQ_URL = 'api.groq.com'
const OPENROUTER_URL = 'openrouter.ai'

test('cascata: quando Gemini fallisce usa Groq', async () => {
  process.env.GEMINI_API_KEY = 'g'
  process.env.GROQ_API_KEY = 'k'
  fetchMock.mockImplementation(async (url: string) =>
    String(url).includes(GEMINI_URL)
      ? fail(503, 'high demand')
      : chatOk('[{"title": "Carcassonne", "publisher": "Ghenos Games"}]'),
  )
  const products = await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(products).toHaveLength(1)
  expect(products[0].title).toBe('Carcassonne')
  // Gemini ha la priorità: 3 tentativi prima di cedere il turno a Groq.
  expect(fetchMock).toHaveBeenCalledTimes(4)
  expect(String(fetchMock.mock.calls[3][0])).toContain(GROQ_URL)
})

test('dà priorità a Gemini: insiste di più prima di passare ai fallback', async () => {
  process.env.GEMINI_API_KEY = 'g'
  process.env.GROQ_API_KEY = 'k'
  const perHost = new Map<string, number>()
  fetchMock.mockImplementation(async (url: string) => {
    const u = String(url)
    const host = u.includes(GEMINI_URL) ? 'gemini' : u.includes(GROQ_URL) ? 'groq' : 'openrouter'
    perHost.set(host, (perHost.get(host) ?? 0) + 1)
    return fail(503, 'high demand')
  })
  await expect(fetchGeminiProducts(source, undefined, { sleep: noSleep })).rejects.toThrow()
  expect(perHost.get('gemini')).toBe(3)
  expect(perHost.get('groq')).toBe(2)
})

test('ritenta sullo stesso provider quando la risposta è vuota', async () => {
  process.env.GROQ_API_KEY = 'k'
  let calls = 0
  fetchMock.mockImplementation(async () => {
    calls++
    // Un contenuto vuoto è non deterministico (il modello "pensa" e a volte non risponde):
    // conviene riprovare lo stesso provider prima di cambiare.
    if (calls === 1) return chatOk('')
    return chatOk('[{"title": "Carcassonne"}]')
  })
  const products = await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(calls).toBe(2)
  expect(products).toHaveLength(1)
  expect(products[0].title).toBe('Carcassonne')
})

test('non ritenta un errore HTTP non ripristinabile', async () => {
  process.env.GEMINI_API_KEY = 'g'
  let calls = 0
  fetchMock.mockImplementation(async () => {
    calls++
    return fail(404, 'model not found')
  })
  await expect(fetchGeminiProducts(source, undefined, { sleep: noSleep })).rejects.toThrow()
  expect(calls).toBe(1)
})

test('annulla la richiesta dopo 5 minuti passando al provider successivo', async () => {
  expect(RESPONSE_TIMEOUT_MS).toBe(5 * 60 * 1000)
  process.env.GEMINI_API_KEY = 'g'
  process.env.GROQ_API_KEY = 'k'
  const segnali: (AbortSignal | null | undefined)[] = []
  fetchMock.mockImplementation(async (url: string, init: any) => {
    segnali.push(init.signal)
    return String(url).includes(GEMINI_URL) ? fail(503) : chatOk('[{"title": "X"}]')
  })
  const products = await fetchGeminiProducts(source, undefined, { sleep: noSleep, timeoutMs: 1234 })
  expect(products).toHaveLength(1)
  for (const s of segnali) expect(s).toBeInstanceOf(AbortSignal)
})

test('cascata completa fino a OpenRouter', async () => {
  process.env.GEMINI_API_KEY = 'g'
  process.env.GROQ_API_KEY = 'k'
  process.env.OPENROUTER_API_KEY = 'o'
  fetchMock.mockImplementation(async (url: string) => {
    const u = String(url)
    if (u.includes(GEMINI_URL)) return fail(503, 'high demand')
    if (u.includes(GROQ_URL)) return fail(429, 'rate limit exceeded')
    return chatOk('[{"title": "Cascadia"}]')
  })
  const products = await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(products).toHaveLength(1)
  expect(products[0].title).toBe('Cascadia')
  const hosts = fetchMock.mock.calls.map(([u]) => String(u))
  expect(hosts.some((u) => u.includes(GEMINI_URL))).toBe(true)
  expect(hosts.some((u) => u.includes(GROQ_URL))).toBe(true)
  expect(hosts[hosts.length - 1]).toContain(OPENROUTER_URL)
})

test('passa al provider successivo quando l’array è vuoto', async () => {
  process.env.GEMINI_API_KEY = 'g'
  process.env.GROQ_API_KEY = 'k'
  fetchMock.mockImplementation(async (url: string) =>
    String(url).includes(GEMINI_URL) ? geminiResp('[]') : chatOk('[{"title": "Cascadia"}]'),
  )
  const products = await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(products).toHaveLength(1)
  expect(products[0].title).toBe('Cascadia')
})

test('passa al provider successivo quando la risposta non è interpretabile', async () => {
  process.env.GEMINI_API_KEY = 'g'
  process.env.GROQ_API_KEY = 'k'
  fetchMock.mockImplementation(async (url: string) =>
    String(url).includes(GEMINI_URL) ? geminiResp('non è JSON') : chatOk('[{"title": "Cascadia"}]'),
  )
  const products = await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(products).toHaveLength(1)
  expect(products[0].title).toBe('Cascadia')
})

test('invia lo stesso prompt a tutti i provider', async () => {
  process.env.GEMINI_API_KEY = 'g'
  process.env.GROQ_API_KEY = 'k'
  const prompts = new Map<string, string>()
  fetchMock.mockImplementation(async (url: string, init: any) => {
    const body = JSON.parse(init.body as string)
    const u = String(url)
    if (u.includes(GEMINI_URL)) {
      prompts.set('gemini', body.contents[0].parts[0].text)
      return fail(503)
    }
    prompts.set('groq', body.messages[0].content)
    return chatOk('[{"title": "X"}]')
  })
  await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(prompts.get('gemini')).toBe(prompts.get('groq'))
  expect(prompts.get('gemini')).toContain('ottobre')
})

test('apre Groq in chat completions con JSON mode', async () => {
  process.env.GEMINI_API_KEY = 'g'
  process.env.GROQ_API_KEY = 'sk-groq'
  fetchMock.mockImplementation(async (url: string, init: any) => {
    const u = String(url)
    if (u.includes(GEMINI_URL)) return fail(503)
    expect(u).toBe('https://api.groq.com/openai/v1/chat/completions')
    expect(init.headers.Authorization).toBe('Bearer sk-groq')
    const body = JSON.parse(init.body as string)
    expect(body.model).toBe('openai/gpt-oss-120b')
    expect(body.response_format).toEqual({ type: 'json_object' })
    return chatOk('[{"title": "X"}]')
  })
  await fetchGeminiProducts(source, undefined, { sleep: noSleep })
})

test('rispetta i modelli sovrascritti da env', async () => {
  process.env.GEMINI_API_KEY = 'g'
  process.env.GROQ_API_KEY = 'k'
  const modelli = new Map<string, string>()
  fetchMock.mockImplementation(async (url: string, init: any) => {
    const body = JSON.parse(init.body as string)
    const u = String(url)
    if (u.includes(GEMINI_URL)) {
      modelli.set('gemini', String(url).match(/models\/([^:]+):/)?.[1] ?? '')
      return fail(503)
    }
    modelli.set('groq', body.model)
    return chatOk('[{"title": "X"}]')
  })
  process.env.GEMINI_MODEL = 'gemini-3.5-flash-lite'
  process.env.GROQ_MODEL = 'qwen/qwen3.8-27b'
  await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(modelli.get('gemini')).toBe('gemini-3.5-flash-lite')
  expect(modelli.get('groq')).toBe('qwen/qwen3.8-27b')
})

test('se tutti i provider rispondono [] restituisce [] senza lanciare', async () => {
  process.env.GEMINI_API_KEY = 'g'
  process.env.GROQ_API_KEY = 'k'
  process.env.OPENROUTER_API_KEY = 'o'
  fetchMock.mockImplementation(async (url: string) => {
    const u = String(url)
    if (u.includes(OPENROUTER_URL)) return chatOk('[]')
    if (u.includes(GROQ_URL)) return chatOk('[]')
    return geminiResp('[]')
  })
  expect(await fetchGeminiProducts(source, undefined, { sleep: noSleep })).toEqual([])
  expect(fetchMock).toHaveBeenCalledTimes(3)
})

test('se nessun provider risponde lancia con tutti gli errori', async () => {
  process.env.GEMINI_API_KEY = 'g'
  process.env.GROQ_API_KEY = 'k'
  fetchMock.mockImplementation(async (url: string) => {
    const u = String(url)
    if (u.includes(GROQ_URL)) return fail(401, 'invalid api key')
    return fail(503, 'high demand')
  })
  await expect(fetchGeminiProducts(source, undefined, { sleep: noSleep })).rejects.toThrow(/gemini.*groq/s)
  await expect(fetchGeminiProducts(source, undefined, { sleep: noSleep })).rejects.toThrow(/503/)
  await expect(fetchGeminiProducts(source, undefined, { sleep: noSleep })).rejects.toThrow(/401/)
})

test('manda a OpenRouter gli header di identificazione', async () => {
  process.env.GEMINI_API_KEY = 'g'
  process.env.GROQ_API_KEY = 'k'
  process.env.OPENROUTER_API_KEY = 'sk-or'
  fetchMock.mockImplementation(async (url: string, init: any) => {
    const u = String(url)
    if (u.includes(GEMINI_URL)) return fail(503)
    if (u.includes(GROQ_URL)) return fail(503)
    expect(u).toBe('https://openrouter.ai/api/v1/chat/completions')
    expect(init.headers['HTTP-Referer']).toContain('github.com')
    expect(init.headers['X-Title']).toBe('Meeple Radar')
    return chatOk('[{"title": "X"}]')
  })
  const products = await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(products).toHaveLength(1)
})

test('scarta un array avvolto in un oggetto', async () => {
  process.env.GEMINI_API_KEY = 'g'
  geminiOk('{"releases": [{"title": "Carcassonne", "publisher": "Ghenos Games"}]}')
  const products = await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(products).toHaveLength(1)
  expect(products[0].title).toBe('Carcassonne')
})

test('estrae il JSON anche quando il modello aggiunge prosa attorno', async () => {
  process.env.GEMINI_API_KEY = 'g'
  geminiOk('Ho cercato sul web e questi sono i risultati.\n\n```json\n{"releases": [{"title": "Carcassonne"}]}\n```\n\nFonti: esempio.com')
  const products = await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(products).toHaveLength(1)
  expect(products[0].title).toBe('Carcassonne')
})

test('parentesi quadre dentro una stringa non sbilanciano l’estrazione', async () => {
  process.env.GEMINI_API_KEY = 'g'
  geminiOk('{"releases": [{"title": "Carcassonne [edizione 2026]", "description": "con {tessere} extra"}]}')
  const products = await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(products).toHaveLength(1)
  expect(products[0].title).toBe('Carcassonne [edizione 2026]')
})

test('segna se il grounding è partito, per accorgersi se viene disattivato', async () => {
  process.env.GEMINI_API_KEY = 'g'
  const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
  fetchMock.mockResolvedValue({
    status: 200, ok: true,
    json: async () => ({
      candidates: [{
        content: { parts: [{ text: '{"releases": []}' }] },
        groundingMetadata: { webSearchQueries: ['giochi in uscita ottobre 2026'] },
      }],
    }),
    text: async () => '',
  } as any)
  await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(spy).toHaveBeenCalledWith(expect.stringContaining('grounding attivo (1 query)'))
})

test('con nessuna chiave lancia un errore chiaro', async () => {
  await expect(fetchGeminiProducts(source, undefined, { sleep: noSleep })).rejects.toThrow(/GEMINI_API_KEY/)
  expect(fetchMock).not.toHaveBeenCalled()
})

test('un 429 senza retry-after (limite giornaliero) non viene ritentato', async () => {
  process.env.GEMINI_API_KEY = 'g'
  let calls = 0
  fetchMock.mockImplementation(async () => {
    calls++
    return { status: 429, ok: false, headers: { get: () => null }, json: async () => ({}), text: async () => 'daily limit' } as any
  })
  await expect(fetchGeminiProducts(source, undefined, { sleep: noSleep })).rejects.toThrow()
  expect(calls).toBe(1)
})

test('un 429 con retry-after (limite al minuto) viene ritentato', async () => {
  process.env.GEMINI_API_KEY = 'g'
  let calls = 0
  fetchMock.mockImplementation(async () => {
    calls++
    if (calls === 1) {
      return {
        status: 429, ok: false,
        headers: { get: (h: string) => (h === 'retry-after' ? '2' : null) },
        json: async () => ({}), text: async () => 'rate limit',
      } as any
    }
    return geminiResp('[{"title": "X"}]')
  })
  const products = await fetchGeminiProducts(source, undefined, { sleep: noSleep })
  expect(calls).toBe(2)
  expect(products).toHaveLength(1)
})
