import { expect, test } from 'vitest'
import { collectHtmlProducts } from '../../src/adapters/html/crawl.js'

const P1 = `<script type="application/ld+json">{"@type":"Product","name":"A","sku":"1","url":"https://s/p1",
  "offers":{"price":"10.00","priceCurrency":"EUR"}}</script>`

test('discovers product URLs then extracts them', async () => {
  const pages: Record<string, string> = { 'https://s/p1': P1, 'https://s/p2': P1.replace('"1"', '"2"').replace('p1', 'p2') }
  const fetchText = async (u: string) => pages[u] ?? null
  const spec = { discover: async () => [{ url: 'https://s/p1' }, { url: 'https://s/p2' }] }
  const out = await collectHtmlProducts({ spec, fetchText, delayMs: 0 })
  expect(out.map((p) => p.externalId).sort()).toEqual(['1', '2'])
})

test('skips URLs that fail to load and does not throw', async () => {
  const out = await collectHtmlProducts({ spec: { discover: async () => [{ url: 'https://s/missing' }] }, fetchText: async () => null, delayMs: 0 })
  expect(out).toEqual([])
})

test('caps the number of product pages fetched (Review Focus 4)', async () => {
  let calls = 0
  const fetchText = async () => { calls++; return P1 }
  await collectHtmlProducts({ spec: { discover: async () => ['a', 'b', 'c', 'd'].map((x) => ({ url: `https://s/${x}` })) }, fetchText, maxProducts: 2, delayMs: 0 })
  expect(calls).toBe(2)
})

test('returns [] when every page yields nothing usable (Review Focus 1)', async () => {
  const fetchText = async () => '<html><head><title>404</title></head></html>'
  const out = await collectHtmlProducts({ spec: { discover: async () => [{ url: 'https://s/x' }] }, fetchText, delayMs: 0 })
  expect(out).toEqual([])
})

test('deduplicates product URLs by external id', async () => {
  const out = await collectHtmlProducts({ spec: { discover: async () => [{ url: 'https://s/p1' }, { url: 'https://s/p1?x=1' }] }, fetchText: async () => P1, delayMs: 0 })
  expect(out).toHaveLength(1)
})

test('uses a per-source extract override when present', async () => {
  const spec = { discover: async () => [{ url: 'https://s/p1' }], extract: (_html: string, url: string) => ({ externalId: 'custom', title: 'Custom', url }) }
  const out = await collectHtmlProducts({ spec, fetchText: async () => P1, delayMs: 0 })
  expect(out).toHaveLength(1)
  expect(out[0].title).toBe('Custom')
  expect(out[0].externalId).toBe('custom')
})

test('falls back to generic extraction when no extract override is given', async () => {
  const spec = { discover: async () => [{ url: 'https://s/p1' }] }
  const out = await collectHtmlProducts({ spec, fetchText: async () => P1, delayMs: 0 })
  expect(out).toHaveLength(1)
  expect(out[0].title).toBe('A')
  expect(out[0].externalId).toBe('1')
})

test('carries a listing release date onto the discovered product', async () => {
  const spec = { discover: async () => [{ url: 'https://s/p1', releaseDate: '2026-11-13' }] }
  const out = await collectHtmlProducts({ spec, fetchText: async () => P1, delayMs: 0 })
  expect(out).toHaveLength(1)
  expect(out[0].releaseDateRaw).toBe('2026-11-13')
})
