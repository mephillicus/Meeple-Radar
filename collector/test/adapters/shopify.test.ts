import { expect, test } from 'vitest'
import { fetchShopifyProducts } from '../../src/adapters/shopify.js'
import fixture from '../fixtures/shopify-products.json' with { type: 'json' }

const http = async () => ({ status: 200, json: async () => fixture, text: async () => '' })

test('maps shopify products to RawProduct', async () => {
  const out = await fetchShopifyProducts('https://shop.example', http)
  expect(out).toHaveLength(1)
  expect(out[0]).toMatchObject({
    externalId: '16087837376843',
    title: 'Ancient Empires - Deluxe Pack',
    url: 'https://shop.example/products/ancient-empires-deluxe-pack',
    priceRaw: '89.90',
    currencyRaw: 'EUR',
    imageUrl: 'https://cdn.shopify.com/x.jpg',
  })
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
