import { expect, test } from 'vitest'
import { fetchWordPressProducts } from '../../src/adapters/wordpress.js'
import fixture from '../fixtures/wordpress-products.json' with { type: 'json' }

const http = async () => ({ status: 200, json: async () => fixture })

test('maps wp product post type to RawProduct without price', async () => {
  const out = await fetchWordPressProducts('https://www.ghenosgames.com', http)
  expect(out).toHaveLength(1)
  expect(out[0]).toMatchObject({
    externalId: '435844',
    title: 'Nuovo Gioco',
    url: 'https://www.ghenosgames.com/prodotto/x/',
    descriptionRaw: '<p>In uscita: 2026-04</p>',
  })
  expect(out[0].priceRaw).toBeUndefined()
})
