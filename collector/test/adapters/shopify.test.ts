import { expect, test } from 'vitest'
import { fetchShopifyProducts } from '../../src/adapters/shopify.js'
import fixture from '../fixtures/shopify-products.json' with { type: 'json' }

const now = new Date('2026-10-22T00:00:00Z')

const productPageHtml = (releaseDate: string) =>
  `<html><body><p>In uscita il ${releaseDate}</p></body></html>`

const http = async (url: string) => {
  if (url.includes('/products/') && !url.includes('/products.json')) {
    const handle = url.split('/products/')[1]
    if (handle === 'gioco-senza-data') {
      return {
        status: 200,
        json: async () => ({}),
        text: async () => productPageHtml('20/11/2026'),
      }
    }
    if (handle === 'gioco-preordine-senza-data') {
      return {
        status: 200,
        json: async () => ({}),
        text: async () => productPageHtml('12 Novembre 2026'),
      }
    }
    return { status: 404, json: async () => ({}), text: async () => '' }
  }
  return { status: 200, json: async () => fixture, text: async () => '' }
}

test('maps shopify products to RawProduct', async () => {
  const out = await fetchShopifyProducts('https://shop.example', http, undefined, now)
  expect(out).toHaveLength(6)
  const ancient = out.find((p) => p.externalId === '16087837376843')
  expect(ancient).toMatchObject({
    externalId: '16087837376843',
    title: 'Ancient Empires - Deluxe Pack',
    url: 'https://shop.example/products/ancient-empires-deluxe-pack',
    priceRaw: '89.90',
    currencyRaw: 'EUR',
    imageUrl: 'https://cdn.shopify.com/x.jpg',
  })
  // 15/03/2026 è nel passato rispetto a now (22/10/2026): resta "true"
  expect(ancient?.availabilityRaw).toBe('true')
  expect(ancient?.releaseDateRaw).toBe('2026-03-15')
})

test('data futura nel body_html segna il prodotto come preordine', async () => {
  const out = await fetchShopifyProducts('https://shop.example', http, undefined, now)
  const pre = out.find((p) => p.externalId === '16087837376844')
  expect(pre?.availabilityRaw).toBe('preordine')
  expect(pre?.releaseDateRaw).toBe('2026-12-15')
})

test('esaurito resta "false" anche senza data', async () => {
  const out = await fetchShopifyProducts('https://shop.example', http, undefined, now)
  const esaurito = out.find((p) => p.externalId === '16087837376845')
  expect(esaurito?.availabilityRaw).toBe('false')
})

test('disponibile senza data con fetch HTML e data futura diventa preordine', async () => {
  const out = await fetchShopifyProducts('https://shop.example', http, undefined, now)
  const senzaData = out.find((p) => p.externalId === '16087837376846')
  expect(senzaData?.availabilityRaw).toBe('preordine')
  expect(senzaData?.releaseDateRaw).toBe('2026-11-20')
})

test('preordini collection senza data: fetch HTML prende la data', async () => {
  const out = await fetchShopifyProducts(
    'https://shop.example',
    http,
    'preordini',
    now,
  )
  const senzaData = out.find((p) => p.externalId === '16087837376847')
  expect(senzaData?.availabilityRaw).toBe('preordine')
  expect(senzaData?.releaseDateRaw).toBe('2026-11-12')
})

test('stagione nel body_html diventa preordine con giorno intermedio (Cranio)', async () => {
  const out = await fetchShopifyProducts('https://shop.example', http, undefined, now)
  const cranio = out.find((p) => p.externalId === '16087837376848')
  expect(cranio?.availabilityRaw).toBe('preordine')
  expect(cranio?.releaseDateRaw).toBe('2027-10-15')
})

test('returns [] on an unexpected payload', async () => {
  const bad = async () => ({ status: 200, json: async () => ({ nope: true }), text: async () => '' })
  expect(await fetchShopifyProducts('https://shop.example', bad)).toEqual([])
})

test('legge tutti i prodotti quando non è indicata una collection', async () => {
  const urls: string[] = []
  const http = async (url: string) => {
    urls.push(url)
    return { status: 200, json: async () => ({ products: [] }), text: async () => '' }
  }
  await fetchShopifyProducts('https://shop.example', http)
  expect(urls[0]).toBe('https://shop.example/products.json?limit=250&page=1')
})

test('legge solo la collection quando indicata', async () => {
  const urls: string[] = []
  const http = async (url: string) => {
    urls.push(url)
    return { status: 200, json: async () => ({ products: [] }), text: async () => '' }
  }
  await fetchShopifyProducts('https://shop.example', http, 'preordini')
  expect(urls[0]).toBe('https://shop.example/collections/preordini/products.json?limit=250&page=1')
})
