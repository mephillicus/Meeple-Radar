import { expect, test } from 'vitest'
import { HTML_SPECS } from '../../src/adapters/html/specs.js'
import { dungeondiceSpec } from '../../src/adapters/html/dungeondice.js'

test('philibert collects product links from its new-products listing', async () => {
  const pages: Record<string, string> = {
    'https://www.philibertnet.com/en/new-products': `<html><body>
      <a href="/en/super-meeple/181793-wondrous-creatures-3760426550387.html">Wondrous</a>
      <a href="/en/18622-coming-soon">Coming soon</a>
      <a href="/content/9-chi-siamo">Chi siamo</a></body></html>`,
    'https://www.philibertnet.com/en/18622-coming-soon': `<html><body></body></html>`,
    'https://www.philibertnet.com/en/14959-pre-orders-kickstarter': `<html><body></body></html>`,
  }
  const urls = await HTML_SPECS.philibert.discover(async (u) => pages[u] ?? null)
  expect(urls).toEqual([
    { url: 'https://www.philibertnet.com/en/super-meeple/181793-wondrous-creatures-3760426550387.html' },
  ])
})

test('dungeondice collects product links from its new-arrivals pages', async () => {
  const pages: Record<string, string> = {
    'https://www.dungeondice.it/445-ultime-novita': `<html><body>
      <a href="https://www.dungeondice.it/44937-barchette-papere-e-saponette.html">X</a>
      <a href="https://www.dungeondice.it/445-ultime-novita?page=2">2</a>
      <a href="https://www.dungeondice.it/253-giochi-da-tavolo">Cat</a></body></html>`,
    'https://www.dungeondice.it/445-ultime-novita?page=2': `<html><body></body></html>`,
    'https://www.dungeondice.it/445-ultime-novita?page=3': `<html><body></body></html>`,
    'https://www.dungeondice.it/445-ultime-novita?page=4': `<html><body></body></html>`,
  }
  const urls = await dungeondiceSpec.discover(async (u) => pages[u] ?? null)
  expect(urls).toEqual([{ url: 'https://www.dungeondice.it/44937-barchette-papere-e-saponette.html' }])
})
