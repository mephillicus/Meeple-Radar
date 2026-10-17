import { expect, test } from 'vitest'
import { readFileSync } from 'node:fs'
import { extractProduct } from '../../src/adapters/html/extract.js'

const read = (f: string) => readFileSync(new URL(`../fixtures/${f}`, import.meta.url), 'utf8')

test('extracts a product from JSON-LD (Review Focus 2)', () => {
  const p = extractProduct(read('product-ldjson.html'), 'https://www.uplay.it/it/gioco-da-tavolo-mystery-express.html')
  expect(p).toMatchObject({
    externalId: 'UPL-123',
    title: 'Mystery Express',
    url: 'https://www.uplay.it/it/gioco-da-tavolo-mystery-express.html',
    priceRaw: '34.90',
    currencyRaw: 'EUR',
    imageUrl: 'https://x/m.jpg',
  })
  expect(p?.availabilityRaw).toContain('InStock')
})

test('falls back to Open Graph when there is no JSON-LD', () => {
  const p = extractProduct(read('product-og.html'), 'https://e.com/p')
  expect(p).toMatchObject({ title: 'Catan', priceRaw: '39.99', currencyRaw: 'EUR', url: 'https://e.com/p' })
})

test('returns null when nothing usable is present (Review Focus 1)', () => {
  expect(extractProduct(read('product-empty.html'), 'https://e.com/p')).toBeNull()
})

test('uses the page URL when there is no canonical (Review Focus 3)', () => {
  const p = extractProduct(read('product-og.html'), 'https://e.com/prodotto/catan')
  expect(p?.url).toBe('https://e.com/prodotto/catan')
})

test('finds the product nested under ItemPage.mainEntity (giochiuniti/Magento)', () => {
  const html = `<script type="application/ld+json">{"@context":"https://schema.org","@type":"ItemPage","name":"I Giochi dei Grandi","mainEntity":{"@type":"Product","name":"TOMBOLA 48 LEGNO","sku":"H6612","image":"https://x/h6612_1.jpg","offers":{"@type":"Offer","price":"17.5","priceCurrency":"EUR","availability":"http://schema.org/OutOfStock"}}}</script>`
  const p = extractProduct(html, 'https://www.igiochideigrandi.it/h6612.html')
  expect(p).toMatchObject({
    externalId: 'H6612',
    title: 'TOMBOLA 48 LEGNO',
    url: 'https://www.igiochideigrandi.it/h6612.html',
    priceRaw: '17.5',
    currencyRaw: 'EUR',
    imageUrl: 'https://x/h6612_1.jpg',
  })
  expect(p?.availabilityRaw).toContain('OutOfStock')
})
