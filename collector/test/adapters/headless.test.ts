import { expect, test } from 'vitest'
import { collectHtmlProducts } from '../../src/adapters/html/crawl.js'
import { HEADLESS_SPECS } from '../../src/adapters/headless/specs.js'

const SEED_PROSSIME = 'https://magicmerchant.it/catalogue/category/giochi-da-tavolo/prossime-uscite-113/'
const SEED_NOVITA = 'https://magicmerchant.it/catalogue/category/giochi-da-tavolo/novita-88/'
const PRODUCT = 'https://magicmerchant.it/catalogue/catan-123/'

test('magic merchant extracts products from a rendered listing (Review Focus 5)', async () => {
  const pages: Record<string, string> = {
    [SEED_PROSSIME]: `<html><body>
      <a href="/catalogue/catan-123/">Catan</a>
      <a href="/catalogue/category/giochi-da-tavolo/novita-88/">Novita</a></body></html>`,
    [SEED_NOVITA]: `<html><body></body></html>`,
    [PRODUCT]: `<script type="application/ld+json">{"@type":"Product","name":"Catan","sku":"9","offers":{"price":"29.90","priceCurrency":"EUR"}}</script>`,
  }
  const fetchText = async (u: string) => pages[u] ?? null
  const out = await collectHtmlProducts({ spec: HEADLESS_SPECS.magicmerchant, fetchText, delayMs: 0 })
  expect(out).toHaveLength(1)
  expect(out[0]).toMatchObject({ title: 'Catan', priceRaw: '29.90', currencyRaw: 'EUR' })
})

test('headless specs cover the cloudflare-protected sources', () => {
  expect(Object.keys(HEADLESS_SPECS).sort()).toEqual(['dungeondice', 'magicmerchant', 'ravensburger', 'uplay'])
})
