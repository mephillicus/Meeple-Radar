import { expect, test } from 'vitest'
import { fetchWordPressProducts, parseGhenosProductPage } from '../../src/adapters/wordpress.js'
import fixture from '../fixtures/wordpress-products.json' with { type: 'json' }
import customPostTypeFixture from '../fixtures/wordpress-custom-post-type.json' with { type: 'json' }

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

test('uses a custom post type when provided', async () => {
  let calledUrl = ''
  const http = async (u: string) => {
    // La pagina prodotto viene scaricata dopo la chiamata API: senza questo
    // filtro l'ultimo URL visto sarebbe quello della pagina, non dell'endpoint.
    if (u.includes('/wp-json/')) calledUrl = u
    return { status: 200, json: async () => customPostTypeFixture, text: async () => '' }
  }
  const out = await fetchWordPressProducts('https://example.com', http, 'game')
  expect(calledUrl).toContain('/wp-json/wp/v2/game')
  expect(out[0].externalId).toBe('35684')
})

test('normalizes a querystring permalink when a permalink base is given', async () => {
  const http = async () => ({
    status: 200,
    json: async () => [{ id: 7, link: 'https://s.com/?game=x', title: { rendered: 'X' } }],
    text: async () => '',
  })
  const out = await fetchWordPressProducts('https://s.com', http, 'game', '/games')
  expect(out[0].url).toBe('https://s.com/games/x/')
})

const GHENOS_PAGE = '<div class="ae-element-custom-field" download=""><p>Day one 27 novembre</p><p>Descrizione del gioco.</p></div>'

test('prende la data dal Day one e non dal campo date del WP', () => {
  const parsed = parseGhenosProductPage(GHENOS_PAGE)
  // La riga Day one non porta l'anno, che quindi cade su quello corrente:
  // e la stessa regola di extractReleaseDate, il cui output deve essere ISO
  // per sopravvivere a isoOrNull.
  const year = new Date().getUTCFullYear()
  expect(parsed.releaseDateRaw).toBe(`${year}-11-27`)
})

test('descrizione dai paragrafi successivi alla data', () => {
  const parsed = parseGhenosProductPage(GHENOS_PAGE)
  expect(parsed.descriptionRaw).toBe('Descrizione del gioco.')
})

test('senza il blocco restituisce campi vuoti e si cade sul contenuto dell API', () => {
  expect(parseGhenosProductPage('<div class="entry-content"><p>Altro testo</p></div>')).toEqual({})
  expect(parseGhenosProductPage('')).toEqual({})
})

test('porta in uscita la data della pagina e non quella di WordPress', async () => {
  const api = [
    {
      id: 9,
      link: 'https://www.ghenosgames.com/prodotto/y/',
      title: { rendered: 'Y' },
      content: { rendered: '<p>Contenuto dell API</p>' },
      date: '2020-05-05T12:00:00',
    },
  ]
  const page = '<div class="ae-element-custom-field" download=""><p>Day one 27 novembre</p><p>Descrizione dal paragrafo.</p></div>'
  const http = async (u: string) =>
    u.includes('/wp-json/')
      ? { status: 200, json: async () => api, text: async () => '' }
      : { status: 200, json: async () => ({}), text: async () => page }
  const out = await fetchWordPressProducts('https://www.ghenosgames.com', http)
  const year = new Date().getUTCFullYear()
  expect(out[0].releaseDateRaw).toBe(`${year}-11-27`)
  expect(out[0].publishedAtRaw).toBe('2020-05-05T12:00:00')
  expect(out[0].descriptionRaw).toBe('Descrizione dal paragrafo.')
})
