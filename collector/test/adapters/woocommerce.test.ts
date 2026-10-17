import { expect, test } from 'vitest'
import { fetchWooCommerceProducts } from '../../src/adapters/woocommerce.js'
import fixture from '../fixtures/woocommerce-products.json' with { type: 'json' }

const http = async () => ({ status: 200, json: async () => fixture, text: async () => '' })

test('maps woocommerce store products to RawProduct', async () => {
  const out = await fetchWooCommerceProducts('https://pendragongamestudio.com', 'EUR', http)
  expect(out).toHaveLength(1)
  expect(out[0]).toMatchObject({
    externalId: '5854',
    title: 'Shijin',
    url: 'https://pendragongamestudio.com/product/shijin/',
    priceRaw: '1299',
    minorUnitRaw: 2,
    currencyRaw: 'EUR',
  })
})
