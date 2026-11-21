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

test('rileva preordini da is_on_backorder, add_to_cart.text, categories e tags', async () => {
  const payload = [
    {
      id: 1,
      name: 'Backorder Game',
      permalink: 'https://e.com/1',
      is_in_stock: true,
      is_on_backorder: true,
    },
    {
      id: 2,
      name: 'AddToCart Preorder',
      permalink: 'https://e.com/2',
      is_in_stock: true,
      add_to_cart: { text: 'Preordina ora' },
    },
    {
      id: 3,
      name: 'Category Preorder',
      permalink: 'https://e.com/3',
      is_in_stock: true,
      categories: [{ name: 'Preordini', slug: 'preordini' }],
    },
    {
      id: 4,
      name: 'Tag Preorder',
      permalink: 'https://e.com/4',
      is_in_stock: true,
      tags: [{ name: 'Pre-Order', slug: 'pre-order' }],
    },
    {
      id: 5,
      name: 'Normal In Stock',
      permalink: 'https://e.com/5',
      is_in_stock: true,
      add_to_cart: { text: 'Aggiungi al carrello' },
    },
  ]
  const http = async () => ({ status: 200, json: async () => payload, text: async () => '' })
  const out = await fetchWooCommerceProducts('https://e.com', 'EUR', http)
  expect(out.map((p) => [p.externalId, p.availabilityRaw])).toEqual([
    ['1', 'preordine'],
    ['2', 'preordine'],
    ['3', 'preordine'],
    ['4', 'preordine'],
    ['5', 'true'],
  ])
})

test('estrae releaseDateRaw da short_description prima di description e tratta price="0" come undefined', async () => {
  const payload = [
    {
      id: 10,
      name: 'Gioco Con Short Date',
      permalink: 'https://e.com/10',
      short_description: '<p>In uscita il 15/12/2026</p>',
      description: '<p>Descrizione completa del 10/01/2026</p>',
      prices: { price: '0', currency_code: 'EUR', currency_minor_unit: 2 },
    },
    {
      id: 11,
      name: 'Gioco Con Desc Date',
      permalink: 'https://e.com/11',
      short_description: '<p><img src="https://e.com/2026-01-01/img.jpg" />Breve intro</p>',
      description: '<p>Disponibile dal 20/11/2026</p>',
      prices: { price: '2990', currency_code: 'EUR', currency_minor_unit: 2 },
    },
  ]
  const http = async () => ({ status: 200, json: async () => payload, text: async () => '' })
  const out = await fetchWooCommerceProducts('https://e.com', 'EUR', http)
  expect(out[0]).toMatchObject({
    externalId: '10',
    releaseDateRaw: '2026-12-15',
    descriptionRaw: '<p>Descrizione completa del 10/01/2026</p>',
  })
  expect(out[0].priceRaw).toBeUndefined()
  expect(out[1]).toMatchObject({
    externalId: '11',
    releaseDateRaw: '2026-11-20',
    priceRaw: '2990',
  })
})

test('ritenta la paginazione su HTTP 429 con Retry-After e funziona con Response nativo', async () => {
  let attempts = 0
  const sleeps: number[] = []
  const nativeHttp = async () => {
    attempts++
    if (attempts === 1) {
      return new Response('', { status: 429, headers: { 'retry-after': '2' } }) as any
    }
    return new Response(
      JSON.stringify([
        { id: 99, name: 'Native Woo Game', permalink: 'https://e.com/99', is_in_stock: true },
      ]),
      { status: 200, headers: { 'content-type': 'application/json' } },
    ) as any
  }
  const out = await fetchWooCommerceProducts('https://e.com', 'EUR', nativeHttp, {
    sleep: async (ms) => {
      sleeps.push(ms)
    },
  })
  expect(attempts).toBe(2)
  expect(sleeps).toContain(2000)
  expect(out).toHaveLength(1)
  expect(out[0].title).toBe('Native Woo Game')
})
