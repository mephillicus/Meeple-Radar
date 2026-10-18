import { expect, test, vi } from 'vitest'
import { createRelayFetcher, RELAY_ENDPOINT, type FetchLike } from '../../src/adapters/relay.js'

function okFetch(body: string) {
  return vi.fn(async (_url: string, _init?: { headers?: Record<string, string> }) => ({
    ok: true,
    status: 200,
    text: async () => body,
  })) as unknown as FetchLike & { mock: { calls: [string, { headers?: Record<string, string> }?][] } }
}

test('relay fetcher calls the reader endpoint with html format and returns the body', async () => {
  const fetchImpl = okFetch('<html>ok</html>')
  const out = await createRelayFetcher(fetchImpl, '')('https://www.uplay.it/x')
  expect(out).toBe('<html>ok</html>')
  expect(fetchImpl.mock.calls[0][0]).toBe(`${RELAY_ENDPOINT}https://www.uplay.it/x`)
  expect(fetchImpl.mock.calls[0][1]?.headers?.['X-Return-Format']).toBe('html')
  expect(fetchImpl.mock.calls[0][1]?.headers?.['Authorization']).toBeUndefined()
})

test('relay fetcher sends the api key when provided', async () => {
  const fetchImpl = okFetch('x')
  await createRelayFetcher(fetchImpl, 'secret-key')('https://e.com')
  expect(fetchImpl.mock.calls[0][1]?.headers?.['Authorization']).toBe('Bearer secret-key')
})

test('relay fetcher returns null on non-ok status or throw', async () => {
  const bad = (async () => ({ ok: false, status: 403, text: async () => '' })) as unknown as FetchLike
  expect(await createRelayFetcher(bad, '')('https://e.com')).toBeNull()
  const boom = (async () => { throw new Error('boom') }) as unknown as FetchLike
  expect(await createRelayFetcher(boom, '')('https://e.com')).toBeNull()
})
