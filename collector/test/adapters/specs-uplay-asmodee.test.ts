import { expect, test } from 'vitest'
import { HTML_SPECS } from '../../src/adapters/html/specs.js'
import { HEADLESS_SPECS } from '../../src/adapters/headless/specs.js'
import { uplaySpec } from '../../src/adapters/html/uplay.js'

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

test('uplay extract accepts board games with .game-languages-label or BGG link and rejects non-boardgames', () => {
  const productLd = (name: string) => `<script type="application/ld+json">${JSON.stringify({
    '@context': 'https://schema.org',
    '@type': 'Product',
    name,
    sku: 'SKU-1',
    offers: { price: '39.90', priceCurrency: 'EUR', availability: 'https://schema.org/InStock' },
  })}</script>`

  const withLangLabel = `<html><head>${productLd('Azul')}</head><body>
    <span class="game-languages-label">Italiano</span>
  </body></html>`
  const resLang = uplaySpec.extract?.(withLangLabel, 'https://www.uplay.it/it/gioco-da-tavolo-azul.html')
  expect(resLang).not.toBeNull()
  expect(resLang?.title).toBe('Azul')

  const withBggLink = `<html><head>${productLd('Wingspan')}</head><body>
    <a href="https://boardgamegeek.com/boardgame/266192/wingspan">BGG</a>
  </body></html>`
  const resBgg = uplaySpec.extract?.(withBggLink, 'https://www.uplay.it/it/gioco-da-tavolo-wingspan.html')
  expect(resBgg).not.toBeNull()
  expect(resBgg?.title).toBe('Wingspan')

  const droneHtml = `<html><head>${productLd('Mini Drone Pieghevole')}</head><body><p>Drone giocattolo</p></body></html>`
  expect(uplaySpec.extract?.(droneHtml, 'https://www.uplay.it/it/gioco-da-tavolo-mini-drone.html')).toBeNull()

  const playmobilHtml = `<html><head>${productLd('Playmobil Caserma Pompieri')}</head><body><p>Giocattolo</p></body></html>`
  expect(uplaySpec.extract?.(playmobilHtml, 'https://www.uplay.it/it/gioco-da-tavolo-playmobil.html')).toBeNull()

  const puzzleHtml = `<html><head>${productLd('Puzzle 1000pz Venezia')}</head><body><p>Puzzle</p></body></html>`
  expect(uplaySpec.extract?.(puzzleHtml, 'https://www.uplay.it/it/gioco-da-tavolo-puzzle-venezia.html')).toBeNull()
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

test('asmodee prefers sitemap_index.xml so product-sitemap2.xml+ are included', async () => {
  const pages: Record<string, string> = {
    'https://www.asmodee.it/sitemap_index.xml': `<sitemapindex>
      <sitemap><loc>https://www.asmodee.it/product-sitemap.xml</loc><lastmod>2026-10-01</lastmod></sitemap>
      <sitemap><loc>https://www.asmodee.it/product-sitemap2.xml</loc><lastmod>2026-11-01</lastmod></sitemap>
    </sitemapindex>`,
    'https://www.asmodee.it/product-sitemap.xml': `<urlset>
      <url><loc>https://www.asmodee.it/product/gioco-1/</loc><lastmod>2026-10-01</lastmod></url>
    </urlset>`,
    'https://www.asmodee.it/product-sitemap2.xml': `<urlset>
      <url><loc>https://www.asmodee.it/product/gioco-2/</loc><lastmod>2026-11-01</lastmod></url>
    </urlset>`,
  }
  const urls = await HTML_SPECS.asmodee.discover(async (u) => pages[u] ?? null)
  expect(urls).toEqual([
    { url: 'https://www.asmodee.it/product/gioco-2/' },
    { url: 'https://www.asmodee.it/product/gioco-1/' },
  ])
})
