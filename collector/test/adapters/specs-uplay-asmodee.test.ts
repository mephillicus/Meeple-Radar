import { expect, test } from 'vitest'
import { HTML_SPECS } from '../../src/adapters/html/specs.js'
import { HEADLESS_SPECS } from '../../src/adapters/headless/specs.js'

test('uplay discovers product pages from its sitemap index', async () => {
  const pages: Record<string, string> = {
    'https://www.uplay.it/sitemap.xml': `<sitemapindex><sitemap><loc>https://www.uplay.it/sitemap-0.xml</loc></sitemap></sitemapindex>`,
    'https://www.uplay.it/sitemap-0.xml': `<urlset>
      <url><loc>https://www.uplay.it/it</loc></url>
      <url><loc>https://www.uplay.it/it/gioco-da-tavolo-mystery-express.html</loc></url>
    </urlset>`,
  }
  const urls = await HEADLESS_SPECS.uplay.discover(async (u) => pages[u] ?? null)
  expect(urls).toEqual([{ url: 'https://www.uplay.it/it/gioco-da-tavolo-mystery-express.html' }])
})

test('asmodee discovers product pages from its product sitemap', async () => {
  const pages: Record<string, string> = {
    'https://www.asmodee.it/product-sitemap.xml': `<urlset>
      <url><loc>https://www.asmodee.it/chi-siamo/</loc></url>
      <url><loc>https://www.asmodee.it/product/catchables/</loc></url>
    </urlset>`,
  }
  const urls = await HTML_SPECS.asmodee.discover(async (u) => pages[u] ?? null)
  expect(urls).toEqual([{ url: 'https://www.asmodee.it/product/catchables/' }])
})
