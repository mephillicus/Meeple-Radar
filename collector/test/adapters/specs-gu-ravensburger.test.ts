import { expect, test } from 'vitest'
import { HTML_SPECS } from '../../src/adapters/html/specs.js'

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
  expect(urls).toContain('https://www.igiochideigrandi.it/h6612.html')
  expect(urls).toContain('https://www.igiochideigrandi.it/everdell-bellfaire.html')
  expect(urls).toContain('https://www.igiochideigrandi.it/k7208.html')
  expect(urls).not.toContain('https://www.igiochideigrandi.it/puzzle/1000-pz.html')
})

test('ravensburger filters product pages from its italian sitemap', async () => {
  const pages: Record<string, string> = {
    'https://www.ravensburger.it/sitemap.xml': `<urlset>
      <url><loc>https://www.ravensburger.it/it-IT/ravensburger/start/category-links</loc></url>
      <url><loc>https://www.ravensburger.it/it-IT/ravensburger/products/games/board-games</loc></url>
      <url><loc>https://www.ravensburger.it/it-IT/ravensburger/products/jigsaw-puzzles/adult-puzzles/afterglow-12001471</loc></url>
      <url><loc>https://www.ravensburger.it/it-IT/brio/products/railway-toys/trains-wagons-vehicles/tow-truck-63352800</loc></url>
    </urlset>`,
  }
  const urls = await HTML_SPECS.ravensburger.discover(async (u) => pages[u] ?? null)
  expect(urls).toEqual([
    'https://www.ravensburger.it/it-IT/ravensburger/products/jigsaw-puzzles/adult-puzzles/afterglow-12001471',
    'https://www.ravensburger.it/it-IT/brio/products/railway-toys/trains-wagons-vehicles/tow-truck-63352800',
  ])
})
