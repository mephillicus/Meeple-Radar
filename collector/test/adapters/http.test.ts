import { afterEach, expect, test, vi } from 'vitest'
import { defaultFetch, FETCH_TIMEOUT_MS } from '../../src/adapters/http.js'

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
