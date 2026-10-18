import { expect, test, vi } from 'vitest'
import { createRelayFetcher, RELAY_ENDPOINT, type FetchLike } from '../../src/adapters/relay.js'

const noSleep = async () => {}

function okFetch(body: string) {
  return vi.fn(async (_url: string, _init?: { headers?: Record<string, string> }) => ({
    ok: true,
    status: 200,
    text: async () => body,
  })) as unknown as FetchLike & { mock: { calls: [string, { headers?: Record<string, string> }?][] } }
}

test('relay fetcher calls the reader endpoint with html format and returns the body', async () => {
  const fetchImpl = okFetch('<html>ok</html>')
  const out = await createRelayFetcher(fetchImpl, '', noSleep)('https://www.uplay.it/x')
  expect(out).toBe('<html>ok</html>')
  expect(fetchImpl.mock.calls[0][0]).toBe(`${RELAY_ENDPOINT}https://www.uplay.it/x`)
  expect(fetchImpl.mock.calls[0][1]?.headers?.['X-Return-Format']).toBe('html')
  expect(fetchImpl.mock.calls[0][1]?.headers?.['Authorization']).toBeUndefined()
})

test('relay fetcher sends the api key when provided', async () => {
  const fetchImpl = okFetch('x')
  await createRelayFetcher(fetchImpl, 'secret-key', noSleep)('https://e.com')
  expect(fetchImpl.mock.calls[0][1]?.headers?.['Authorization']).toBe('Bearer secret-key')
})

test('relay fetcher returns null immediately on a non-retryable status', async () => {
  const bad = vi.fn(async () => ({ ok: false, status: 403, text: async () => '' })) as unknown as FetchLike & { mock: { calls: unknown[] } }
  expect(await createRelayFetcher(bad, '', noSleep)('https://e.com')).toBeNull()
  expect(bad.mock.calls).toHaveLength(1)
})

test('relay fetcher retries a rate-limited response and then succeeds', async () => {
  let calls = 0
  const fetchImpl = (async () => {
    calls++
    if (calls < 3) return { ok: false, status: 429, text: async () => '' }
    return { ok: true, status: 200, text: async () => '<html>ok</html>' }
  }) as unknown as FetchLike
  expect(await createRelayFetcher(fetchImpl, '', noSleep)('https://e.com')).toBe('<html>ok</html>')
  expect(calls).toBe(3)
})

test('relay fetcher gives up after the retry budget and returns null', async () => {
  const fetchImpl = vi.fn(async () => ({ ok: false, status: 503, text: async () => '' })) as unknown as FetchLike & { mock: { calls: unknown[] } }
  expect(await createRelayFetcher(fetchImpl, '', noSleep)('https://e.com')).toBeNull()
  expect(fetchImpl.mock.calls).toHaveLength(3)
})
