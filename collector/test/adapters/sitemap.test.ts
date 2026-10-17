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
