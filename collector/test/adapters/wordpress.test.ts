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

// Prezzo e disponibilità vivono nei meta OG di WooCommerce/Yoast, fuori dal
// blocco descrizione: senza quella lettura il collector non porta mai prezzo.
const GHENOS_PAGE_INSTOCK = `
  <meta property="product:price:amount" content="19.90"/>
  <meta property="product:price:currency" content="EUR"/>
  <meta property="product:availability" content="instock"/>
  <div class="ae-element-custom-field" download=""><p>Descrizione del gioco.</p></div>`

const GHENOS_PAGE_PREORDER = `
  <meta property="product:price:amount" content="22.90"/>
  <meta property="product:price:currency" content="EUR"/>
  <div class="ae-element-custom-field" download=""><p>Day one 11 dicembre</p><p>Descrizione del gioco.</p></div>`

test('prezzo e disponibilità dai meta OG della pagina (prodotto disponibile)', () => {
  const parsed = parseGhenosProductPage(GHENOS_PAGE_INSTOCK)
  expect(parsed).toMatchObject({ priceRaw: '19.90', currencyRaw: 'EUR', availabilityRaw: 'instock' })
  expect(parsed.descriptionRaw).toBe('Descrizione del gioco.')
})

test('prezzo presente anche sul preordine, senza meta di disponibilità', () => {
  const parsed = parseGhenosProductPage(GHENOS_PAGE_PREORDER)
  expect(parsed).toMatchObject({ priceRaw: '22.90', currencyRaw: 'EUR' })
  expect(parsed.availabilityRaw).toBeUndefined()
  const year = new Date().getUTCFullYear()
  expect(parsed.releaseDateRaw).toBe(`${year}-12-11`)
})

test('i meta OG valgono anche senza il blocco descrizione', () => {
  const parsed = parseGhenosProductPage('<meta property="product:price:amount" content="29"/>')
  expect(parsed).toMatchObject({ priceRaw: '29' })
})

test('il prezzo arriva in uscita dall adapter', async () => {
  const api = [{ id: 9, link: 'https://www.ghenosgames.com/prodotto/y/', title: { rendered: 'Y' } }]
  const http = async (u: string) =>
    u.includes('/wp-json/')
      ? { status: 200, json: async () => api, text: async () => '' }
      : { status: 200, json: async () => ({}), text: async () => GHENOS_PAGE_INSTOCK }
  const out = await fetchWordPressProducts('https://www.ghenosgames.com', http)
  expect(out[0]).toMatchObject({ priceRaw: '19.90', currencyRaw: 'EUR', availabilityRaw: 'instock' })
})

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

const postApi = (n: number) =>
  Array.from({ length: n }, (_, i) => ({
    id: i + 1,
    link: `https://s.com/prodotto/${i + 1}/`,
    title: { rendered: `G${i + 1}` },
  }))

test('smette di scaricare le pagine dopo molti fallimenti di fila', async () => {
  const api = postApi(10)
  let pageCalls = 0
  const http = async (u: string) => {
    if (u.includes('/wp-json/')) return { status: 200, json: async () => api, text: async () => '' }
    pageCalls++
    return { status: 500, json: async () => ({}), text: async () => '' }
  }
  const out = await fetchWordPressProducts('https://s.com', http)
  // Le pagine cadute non fermano il post: restano tutti con i campi dell API.
  expect(out).toHaveLength(10)
  expect(pageCalls).toBe(5)
})

test('un fallimento isolato non ferma le pagine successive', async () => {
  const api = postApi(3)
  let pageCalls = 0
  const http = async (u: string) => {
    if (u.includes('/wp-json/')) return { status: 200, json: async () => api, text: async () => '' }
    pageCalls++
    return { status: pageCalls === 1 ? 500 : 200, json: async () => ({}), text: async () => '' }
  }
  await fetchWordPressProducts('https://s.com', http)
  expect(pageCalls).toBe(3)
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

test('scarica le pagine di dettaglio con concorrenza limitata (default 4) e pacing condiviso', async () => {
  const api = postApi(8)
  let inFlight = 0
  let maxInFlight = 0
  const sleeps: number[] = []
  const http = async (u: string) => {
    if (u.includes('/wp-json/')) {
      return { status: 200, json: async () => api, text: async () => '' }
    }
    inFlight++
    maxInFlight = Math.max(maxInFlight, inFlight)
    await new Promise((resolve) => setTimeout(resolve, 25))
    inFlight--
    return { status: 200, json: async () => ({}), text: async () => GHENOS_PAGE_INSTOCK }
  }
  const out = await fetchWordPressProducts('https://s.com', http, 'product', undefined, {
    concurrency: 4,
    delayMs: 200,
    sleep: async (ms) => {
      sleeps.push(ms)
    },
  })
  expect(out).toHaveLength(8)
  expect(maxInFlight).toBeGreaterThan(1)
  expect(maxInFlight).toBeLessThanOrEqual(4)
  expect(sleeps.length).toBeGreaterThanOrEqual(7)
})

test('ritenta automaticamente le pagine di dettaglio in caso di HTTP 429/503 tramite textFetcher', async () => {
  const api = [{ id: 42, link: 'https://www.ghenosgames.com/prodotto/retry/', title: { rendered: 'Retry Game' } }]
  let detailAttempts = 0
  const sleeps: number[] = []
  const http = async (u: string) => {
    if (u.includes('/wp-json/')) {
      return { status: 200, json: async () => api, text: async () => '' }
    }
    detailAttempts++
    if (detailAttempts === 1) {
      return {
        status: 429,
        headers: { get: (h: string) => (h.toLowerCase() === 'retry-after' ? '2' : null) },
        json: async () => ({}),
        text: async () => '',
      }
    }
    return { status: 200, json: async () => ({}), text: async () => GHENOS_PAGE_INSTOCK }
  }
  const out = await fetchWordPressProducts('https://www.ghenosgames.com', http, 'product', undefined, {
    delayMs: 0,
    sleep: async (ms) => {
      sleeps.push(ms)
    },
  })
  expect(detailAttempts).toBe(2)
  expect(sleeps).toContain(2000)
  expect(out[0]).toMatchObject({ priceRaw: '19.90', currencyRaw: 'EUR', availabilityRaw: 'instock' })
})

test('estrae imageUrl da og:image e cade su twitter:image', () => {
  const ogHtml = `<meta property="og:image" content="https://www.ghenosgames.com/img/cover.jpg"/>`
  expect(parseGhenosProductPage(ogHtml).imageUrl).toBe('https://www.ghenosgames.com/img/cover.jpg')

  const twitterHtml = `<meta name="twitter:image" content="https://www.ghenosgames.com/img/tw.jpg"/>`
  expect(parseGhenosProductPage(twitterHtml).imageUrl).toBe('https://www.ghenosgames.com/img/tw.jpg')
})

test('parsing data case-insensitive su tutti i paragrafi di .ae-element-custom-field', () => {
  const html1 = `<div class="ae-element-custom-field"><p>&nbsp;</p><p>Day One 15/12/2026</p><p>Testo descrittivo.</p></div>`
  expect(parseGhenosProductPage(html1)).toMatchObject({
    releaseDateRaw: '2026-12-15',
    descriptionRaw: 'Testo descrittivo.',
  })

  const html2 = `<div class="ae-element-custom-field"><p>Introduzione al gioco.</p><p>Disponibile dal 20 gennaio 2027</p></div>`
  expect(parseGhenosProductPage(html2)).toMatchObject({
    releaseDateRaw: '2027-01-20',
    descriptionRaw: 'Introduzione al gioco.',
  })

  const html3 = `<div class="ae-element-custom-field"><p>USCITA prevista: 05/03/2027</p><p>Altra descrizione.</p></div>`
  expect(parseGhenosProductPage(html3)).toMatchObject({
    releaseDateRaw: '2027-03-05',
    descriptionRaw: 'Altra descrizione.',
  })
})

test('data di uscita futura sovrascrive instock con preordine e propaga imageUrl', async () => {
  const api = [
    { id: 101, link: 'https://www.ghenosgames.com/prodotto/futuro/', title: { rendered: 'Gioco Futuro' } },
    { id: 102, link: 'https://www.ghenosgames.com/prodotto/passato/', title: { rendered: 'Gioco Passato' } },
  ]
  const pageFuture = `
    <meta property="og:image" content="https://www.ghenosgames.com/img/futuro.jpg"/>
    <meta property="product:price:amount" content="39.90"/>
    <meta property="product:price:currency" content="EUR"/>
    <meta property="product:availability" content="instock"/>
    <div class="ae-element-custom-field"><p>Day One 15/12/2026</p><p>Descrizione.</p></div>`
  const pagePast = `
    <meta property="og:image" content="https://www.ghenosgames.com/img/passato.jpg"/>
    <meta property="product:availability" content="instock"/>
    <div class="ae-element-custom-field"><p>Day one 10/01/2026</p><p>Descrizione.</p></div>`
  const http = async (u: string) => {
    if (u.includes('/wp-json/')) return { status: 200, json: async () => api, text: async () => '' }
    if (u.includes('/futuro/')) return { status: 200, json: async () => ({}), text: async () => pageFuture }
    return { status: 200, json: async () => ({}), text: async () => pagePast }
  }
  const out = await fetchWordPressProducts('https://www.ghenosgames.com', http, 'product', undefined, {
    delayMs: 0,
    now: new Date('2026-10-22T00:00:00Z'),
  })
  expect(out[0]).toMatchObject({
    externalId: '101',
    availabilityRaw: 'preordine',
    releaseDateRaw: '2026-12-15',
    imageUrl: 'https://www.ghenosgames.com/img/futuro.jpg',
  })
  expect(out[1]).toMatchObject({
    externalId: '102',
    availabilityRaw: 'instock',
    releaseDateRaw: '2026-01-10',
    imageUrl: 'https://www.ghenosgames.com/img/passato.jpg',
  })
})

test('ritenta la paginazione WP REST su HTTP 429 con Response nativo', async () => {
  let apiAttempts = 0
  const sleeps: number[] = []
  const nativeHttp = async (u: string) => {
    if (u.includes('/wp-json/')) {
      apiAttempts++
      if (apiAttempts === 1) {
        return new Response('', { status: 429, headers: { 'retry-after': '3' } }) as any
      }
      return new Response(
        JSON.stringify([
          { id: 55, link: 'https://www.ghenosgames.com/prodotto/native/', title: { rendered: 'Native WP' } },
        ]),
        { status: 200, headers: { 'content-type': 'application/json' } },
      ) as any
    }
    return new Response(GHENOS_PAGE_INSTOCK, { status: 200 }) as any
  }
  const out = await fetchWordPressProducts('https://www.ghenosgames.com', nativeHttp, 'product', undefined, {
    delayMs: 0,
    sleep: async (ms) => {
      sleeps.push(ms)
    },
  })
  expect(apiAttempts).toBe(2)
  expect(sleeps).toContain(3000)
  expect(out).toHaveLength(1)
  expect(out[0].title).toBe('Native WP')
})
