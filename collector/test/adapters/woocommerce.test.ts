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

test('description vuota cade sul short_description', async () => {
  const payload = [
    { id: 1, name: 'Gioco', permalink: 'https://e.com/g', description: '', short_description: '<p>Una frase breve</p>' },
  ]
  const http = async () => ({ status: 200, json: async () => payload, text: async () => '' })
  const out = await fetchWooCommerceProducts('https://e.com', 'EUR', http)
  expect(out).toHaveLength(1)
  expect(out[0].descriptionRaw).toBe('<p>Una frase breve</p>')
})
