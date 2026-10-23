import { expect, test } from 'vitest'
import { parseSitemapEntries, isSitemapIndex, newestFirst, fetchSitemapEntries } from '../../src/adapters/sitemap.js'

const URLSET = `<?xml version="1.0"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <url><loc>https://e.com/it/gioco-da-tavolo-catan.html</loc><lastmod>2026-10-17T05:05:24+00:00</lastmod></url>
  <url><loc>https://e.com/it</loc></url>
</urlset>`
const INDEX = `<?xml version="1.0"?><sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <sitemap><loc>https://e.com/sitemap-0.xml</loc></sitemap>
  <sitemap><loc>https://e.com/sitemap-1.xml</loc></sitemap>
</sitemapindex>`

test('parses entries with loc and lastmod wrapped in CDATA (AIOSEO)', () => {
  const xml = `<?xml version="1.0"?><urlset>
    <url>
      <loc><![CDATA[https://www.houseofgames.it/giochi-da-tavolo-le-nuove-uscite-di-settembre-2026/]]></loc>
      <lastmod><![CDATA[2026-09-03T06:11:02+00:00]]></lastmod>
    </url>
  </urlset>`
  expect(parseSitemapEntries(xml)).toEqual([
    { loc: 'https://www.houseofgames.it/giochi-da-tavolo-le-nuove-uscite-di-settembre-2026/', lastmod: '2026-09-03T06:11:02+00:00' },
  ])
})

test('parses urls and optional lastmod', () => {
  const e = parseSitemapEntries(URLSET)
  expect(e).toHaveLength(2)
  expect(e[0]).toMatchObject({ loc: 'https://e.com/it/gioco-da-tavolo-catan.html', lastmod: '2026-10-17T05:05:24+00:00' })
  expect(e[1].lastmod).toBeUndefined()
})

test('detects a sitemap index', () => {
  expect(isSitemapIndex(INDEX)).toBe(true)
  expect(isSitemapIndex(URLSET)).toBe(false)
})

test('sorts newest first and keeps entries without lastmod (Review Focus 4)', () => {
  const sorted = newestFirst([
    { loc: 'a', lastmod: '2026-01-01T00:00:00Z' },
    { loc: 'b' },
    { loc: 'c', lastmod: '2026-10-17T00:00:00Z' },
  ])
  expect(sorted.map((e) => e.loc)).toEqual(['c', 'a', 'b'])
})

test('follows a sitemap index into child sitemaps', async () => {
  const pages: Record<string, string> = { 'https://e.com/sitemap.xml': INDEX, 'https://e.com/sitemap-0.xml': URLSET, 'https://e.com/sitemap-1.xml': URLSET }
  const fetchText = async (u: string) => pages[u] ?? null
  const entries = await fetchSitemapEntries('https://e.com/sitemap.xml', fetchText)
  expect(entries.length).toBe(4)
})

test('returns [] when a sitemap is unreachable', async () => {
  expect(await fetchSitemapEntries('https://e.com/sitemap.xml', async () => null)).toEqual([])
})

// Il reader Jina AI rende le sitemap come HTML: stessi dati, markup diverso.
const JINA_URLSET = `<html><head><title>Sitemap</title></head><body><div><a href="https://e.com/it/gioco-da-tavolo-catan.html">https://e.com/it/gioco-da-tavolo-catan.html</a><br><time>2026-10-17T05:05:24+00:00</time></div><div><a href="https://e.com/it">https://e.com/it</a></div></body></html>`
const JINA_INDEX = `<html><head><title>Sitemap Index</title></head><body><div><a href="https://e.com/sitemap-0.xml">https://e.com/sitemap-0.xml</a><br><time>2026-10-17T05:05:24+00:00</time></div></body></html>`

test('parses a sitemap rendered as HTML by the relay', () => {
  const e = parseSitemapEntries(JINA_URLSET)
  expect(e).toHaveLength(2)
  expect(e[0]).toMatchObject({ loc: 'https://e.com/it/gioco-da-tavolo-catan.html', lastmod: '2026-10-17T05:05:24+00:00' })
  expect(e[1].lastmod).toBeUndefined()
})

test('detects a sitemap index rendered as HTML by the relay', () => {
  expect(isSitemapIndex(JINA_INDEX)).toBe(true)
  expect(isSitemapIndex(JINA_URLSET)).toBe(false)
})

test('follows an index rendered as HTML into child sitemaps', async () => {
  const pages: Record<string, string> = { 'https://e.com/sitemap.xml': JINA_INDEX, 'https://e.com/sitemap-0.xml': JINA_URLSET }
  const entries = await fetchSitemapEntries('https://e.com/sitemap.xml', async (u) => pages[u] ?? null)
  expect(entries).toHaveLength(2)
})
