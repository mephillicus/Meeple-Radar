import { afterEach, expect, test, vi } from 'vitest'
import {
  createTrackedTextFetcher,
  defaultFetch,
  FETCH_TIMEOUT_MS,
  parseRetryAfterMs,
  textFetcher,
  type HttpClient,
} from '../../src/adapters/http.js'

const ok = { status: 200, json: async () => ({}), text: async () => '' }

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

test('passa un AbortSignal a ogni richiesta', async () => {
  const segnali: (AbortSignal | null | undefined)[] = []
  vi.stubGlobal('fetch', async (_url: string, init: RequestInit) => {
    segnali.push(init.signal)
    return ok
  })
  await defaultFetch('https://example.com')
  expect(segnali).toHaveLength(1)
  expect(segnali[0]).toBeInstanceOf(AbortSignal)
})

test('crea il segnale con AbortSignal.timeout usando FETCH_TIMEOUT_MS', async () => {
  const spy = vi.spyOn(AbortSignal, 'timeout')
  vi.stubGlobal('fetch', async () => ok)
  await defaultFetch('https://example.com')
  expect(spy).toHaveBeenCalledTimes(1)
  expect(spy).toHaveBeenCalledWith(FETCH_TIMEOUT_MS)
})

test('il segnale viene annullato quando la richiesta resta appesa', async () => {
  vi.spyOn(AbortSignal, 'timeout').mockReturnValue(AbortSignal.abort(new Error('scadenza')))
  vi.stubGlobal(
    'fetch',
    async (_url: string, init: RequestInit) =>
      new Promise((_resolve, reject) => {
        init.signal?.throwIfAborted?.()
        init.signal?.addEventListener('abort', () => reject(init.signal?.reason))
      }),
  )
  await expect(defaultFetch('https://example.com')).rejects.toThrow('scadenza')
})

test('il timeout è di 20 secondi', () => {
  expect(FETCH_TIMEOUT_MS).toBe(20_000)
})

test('parseRetryAfterMs gestisce secondi, date HTTP, valori passati e invalidi', () => {
  const now = Date.parse('2026-10-20T10:00:00Z')
  expect(parseRetryAfterMs('5', now)).toBe(5_000)
  expect(parseRetryAfterMs('0', now)).toBe(0)
  expect(parseRetryAfterMs('120', now)).toBe(60_000)
  expect(parseRetryAfterMs('Tue, 20 Oct 2026 10:00:12 GMT', now)).toBe(12_000)
  expect(parseRetryAfterMs('Tue, 20 Oct 2026 09:59:50 GMT', now)).toBe(0)
  expect(parseRetryAfterMs('not-a-date', now)).toBeNull()
  expect(parseRetryAfterMs('', now)).toBeNull()
  expect(parseRetryAfterMs(null, now)).toBeNull()
})

test('textFetcher ritenta con backoff esponenziale su 429, 502, 503, 504', async () => {
  const statuses = [429, 502, 503, 200]
  let idx = 0
  const delays: number[] = []
  const http: HttpClient = async () => ({
    status: statuses[idx++],
    json: async () => ({}),
    text: async () => '<html>ok</html>',
  })

  const fetchText = textFetcher(http, {
    sleep: async (ms) => {
      delays.push(ms)
    },
    baseDelayMs: 100,
    maxRetries: 3,
  })

  expect(await fetchText('https://example.com/a')).toBe('<html>ok</html>')
  expect(delays).toEqual([100, 200, 400])
})

test('textFetcher rispetta Retry-After in secondi e in formato HTTP-date', async () => {
  const fixedNow = Date.parse('2026-10-20T10:00:00Z')
  const delays: number[] = []
  let calls = 0
  const http: HttpClient = async () => {
    calls++
    if (calls === 1) {
      return {
        status: 429,
        headers: new Headers({ 'Retry-After': '3' }),
        json: async () => ({}),
        text: async () => '',
      }
    }
    if (calls === 2) {
      return {
        status: 503,
        headers: new Headers({ 'Retry-After': 'Tue, 20 Oct 2026 10:00:07 GMT' }),
        json: async () => ({}),
        text: async () => '',
      }
    }
    return { status: 200, json: async () => ({}), text: async () => 'ok' }
  }

  const fetchText = textFetcher(http, {
    sleep: async (ms) => {
      delays.push(ms)
    },
    now: () => fixedNow,
    baseDelayMs: 100,
  })

  expect(await fetchText('https://example.com/r')).toBe('ok')
  expect(delays).toEqual([3_000, 7_000])
})

test('textFetcher non ritenta su 404 o 403', async () => {
  const delays: number[] = []
  const http = vi.fn<HttpClient>(async () => ({
    status: 404,
    json: async () => ({}),
    text: async () => '',
  }))
  const fetchText = textFetcher(http, {
    sleep: async (ms) => {
      delays.push(ms)
    },
  })
  expect(await fetchText('https://example.com/missing')).toBeNull()
  expect(http).toHaveBeenCalledTimes(1)
  expect(delays).toEqual([])
})

test('textFetcher ritenta su errori di rete e restituisce null a budget esaurito', async () => {
  const delays: number[] = []
  const http = vi.fn<HttpClient>(async () => {
    throw new Error('ECONNRESET')
  })
  const fetchText = textFetcher(http, {
    sleep: async (ms) => {
      delays.push(ms)
    },
    maxRetries: 2,
    baseDelayMs: 50,
  })
  expect(await fetchText('https://example.com/down')).toBeNull()
  expect(http).toHaveBeenCalledTimes(3)
  expect(delays).toEqual([50, 100])
})

test('createTrackedTextFetcher registra errori HTTP, timeout e rete per stato', async () => {
  const delays: number[] = []
  let call = 0
  const mockFetch = vi.fn(async () => {
    call++
    if (call === 1) return new Response('', { status: 429, headers: { 'Retry-After': '2' } })
    if (call === 2) return new Response('', { status: 504 })
    if (call === 3) return new Response('ok-body', { status: 200 })
    if (call === 4) return new Response('', { status: 404 })
    if (call === 5) {
      const err = new Error('timed out')
      err.name = 'TimeoutError'
      throw err
    }
    throw new Error('socket hang up')
  }) as unknown as typeof fetch

  const tracked = createTrackedTextFetcher({
    fetch: mockFetch,
    sleep: async (ms) => {
      delays.push(ms)
    },
    maxRetries: 2,
    baseDelayMs: 100,
    timeoutMs: 5_000,
  })

  expect(await tracked.fetcher('https://example.com/1')).toBe('ok-body')
  expect(await tracked.fetcher('https://example.com/2')).toBeNull()
  expect(await tracked.fetcher('https://example.com/3')).toBeNull()

  const counts = tracked.getErrorsByStatus()
  expect(counts).toEqual({
    '429': 1,
    '504': 1,
    '404': 1,
    timeout: 1,
    network: 2,
  })
  expect(delays).toEqual([2_000, 200, 100, 200])

  // getErrorsByStatus restituisce una copia difensiva
  counts['404'] = 999
  expect(tracked.getErrorsByStatus()['404']).toBe(1)
})
