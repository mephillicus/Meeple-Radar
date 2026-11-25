import { expect, test } from 'vitest'
import { HTML_SPECS } from '../../src/adapters/html/specs.js'
import { ravensburgerSpec } from '../../src/adapters/html/ravensburger.js'

test('giochiuniti follows its sitemap into the igiochideigrandi catalog', async () => {
  const pages: Record<string, string> = {
    'https://www.giochiuniti.it/sitemap.xml': `<sitemapindex><sitemap><loc>https://www.igiochideigrandi.it/pub/sitemap-2-1.xml</loc></sitemap><sitemap><loc>https://www.igiochideigrandi.it/pub/sitemap-2-2.xml</loc></sitemap></sitemapindex>`,
    'https://www.igiochideigrandi.it/pub/sitemap-2-1.xml': `<urlset>
      <url><loc>https://www.igiochideigrandi.it/wargame.html</loc></url>
      <url><loc>https://www.igiochideigrandi.it/h6612.html</loc></url>
      <url><loc>https://www.igiochideigrandi.it/puzzle/1000-pz.html</loc></url>
      <url><loc>https://www.igiochideigrandi.it/everdell-bellfaire.html</loc></url>
    </urlset>`,
    'https://www.igiochideigrandi.it/pub/sitemap-2-2.xml': `<urlset>
      <url><loc>https://www.igiochideigrandi.it/k7208.html</loc><lastmod>2026-10-17T00:00:00+00:00</lastmod></url>
    </urlset>`,
  }
  const urls = await HTML_SPECS.giochiuniti.discover(async (u) => pages[u] ?? null)
  expect(urls).toContainEqual({ url: 'https://www.igiochideigrandi.it/h6612.html' })
  expect(urls).toContainEqual({ url: 'https://www.igiochideigrandi.it/everdell-bellfaire.html' })
  expect(urls).toContainEqual({ url: 'https://www.igiochideigrandi.it/k7208.html' })
  expect(urls).not.toContainEqual({ url: 'https://www.igiochideigrandi.it/puzzle/1000-pz.html' })
})

test('ravensburger whitelists /products/games/ and excludes 3D/jigsaw puzzles, BRIO, GraviTrax, kids/travel games, etc.', async () => {
  const pages: Record<string, string> = {
    'https://www.ravensburger.it/sitemap.xml': `<urlset>
      <url><loc>https://www.ravensburger.it/it-IT/ravensburger/start/category-links</loc></url>
      <url><loc>https://www.ravensburger.it/it-IT/ravensburger/products/games/board-games</loc></url>
      <url><loc>https://www.ravensburger.it/it-IT/ravensburger/products/games/board-games/disney-villainous-26061</loc></url>
      <url><loc>https://www.ravensburger.it/it-IT/ravensburger/products/games/family-games/labirinto-magico-26448</loc></url>
      <url><loc>https://www.ravensburger.it/it-IT/ravensburger/products/games/card-games/level-8-26912</loc></url>
      <url><loc>https://www.ravensburger.it/it-IT/ravensburger/products/games/games-for-adults/el-dorado-26711</loc></url>
      <url><loc>https://www.ravensburger.it/it-IT/ravensburger/products/games/logic-games/rush-hour-27651</loc></url>
      <url><loc>https://www.ravensburger.it/it-IT/ravensburger/products/games/games-for-kids/lotti-karotti-21566</loc></url>
      <url><loc>https://www.ravensburger.it/it-IT/ravensburger/products/games/travel-games/labirinto-mini-23112</loc></url>
      <url><loc>https://www.ravensburger.it/it-IT/ravensburger/products/3d-puzzle/buildings/torre-eiffel-12556</loc></url>
      <url><loc>https://www.ravensburger.it/it-IT/ravensburger/products/jigsaw-puzzles/adult-puzzles/afterglow-12001471</loc></url>
      <url><loc>https://www.ravensburger.it/it-IT/brio/products/railway-toys/trains-wagons-vehicles/tow-truck-63352800</loc></url>
      <url><loc>https://www.ravensburger.it/it-IT/ravensburger/products/gravitrax/starter-set-26832</loc></url>
      <url><loc>https://www.ravensburger.it/it-IT/ravensburger/products/tiptoi/penna-00112</loc></url>
      <url><loc>https://www.ravensburger.it/it-IT/ravensburger/products/creart/gatto-20105</loc></url>
      <url><loc>https://www.ravensburger.it/it-IT/ravensburger/products/other-toys/gioco-generico-99999</loc></url>
    </urlset>`,
  }
  const urls = await ravensburgerSpec.discover(async (u) => pages[u] ?? null)
  expect(urls).toEqual([
    { url: 'https://www.ravensburger.it/it-IT/ravensburger/products/games/board-games/disney-villainous-26061' },
    { url: 'https://www.ravensburger.it/it-IT/ravensburger/products/games/family-games/labirinto-magico-26448' },
    { url: 'https://www.ravensburger.it/it-IT/ravensburger/products/games/card-games/level-8-26912' },
    { url: 'https://www.ravensburger.it/it-IT/ravensburger/products/games/games-for-adults/el-dorado-26711' },
    { url: 'https://www.ravensburger.it/it-IT/ravensburger/products/games/logic-games/rush-hour-27651' },
  ])
})

test('ravensburger extract strips leading "Ravensburger " prefix from title', () => {
  const html = `<html><head><script type="application/ld+json">${JSON.stringify({
    '@context': 'https://schema.org',
    '@type': 'Product',
    name: 'Ravensburger El Dorado',
    sku: '26711',
    offers: { price: '34.99', priceCurrency: 'EUR', availability: 'https://schema.org/InStock' },
  })}</script></head><body></body></html>`

  const product = ravensburgerSpec.extract?.(
    html,
    'https://www.ravensburger.it/it-IT/ravensburger/products/games/games-for-adults/el-dorado-26711',
  )
  expect(product).not.toBeNull()
  expect(product?.title).toBe('El Dorado')

  const plainHtml = `<html><head><script type="application/ld+json">${JSON.stringify({
    '@context': 'https://schema.org',
    '@type': 'Product',
    name: 'Villainous',
    sku: '26061',
  })}</script></head><body></body></html>`
  const plain = ravensburgerSpec.extract?.(
    plainHtml,
    'https://www.ravensburger.it/it-IT/ravensburger/products/games/board-games/disney-villainous-26061',
  )
  expect(plain?.title).toBe('Villainous')
})
