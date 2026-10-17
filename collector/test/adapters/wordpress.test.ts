import { expect, test } from 'vitest'
import { fetchWordPressProducts } from '../../src/adapters/wordpress.js'
import fixture from '../fixtures/wordpress-products.json' with { type: 'json' }
import smgFixture from '../fixtures/wordpress-smg_game.json' with { type: 'json' }

const http = async () => ({ status: 200, json: async () => fixture, text: async () => '' })

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

test('uses a custom post type when provided (stonemaier)', async () => {
  let calledUrl = ''
  const http = async (u: string) => {
    calledUrl = u
    return { status: 200, json: async () => smgFixture, text: async () => '' }
  }
  const out = await fetchWordPressProducts('https://stonemaiergames.com', http, 'smg_game')
  expect(calledUrl).toContain('/wp-json/wp/v2/smg_game')
  expect(out[0].externalId).toBe('35684')
})

test('normalizes a querystring permalink when a permalink base is given', async () => {
  const http = async () => ({
    status: 200,
    json: async () => [{ id: 7, link: 'https://s.com/?smg_game=x', title: { rendered: 'X' } }],
    text: async () => '',
  })
  const out = await fetchWordPressProducts('https://s.com', http, 'smg_game', '/games')
  expect(out[0].url).toBe('https://s.com/games/x/')
})
