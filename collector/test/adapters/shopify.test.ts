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

test('prodotto ordinario disponibile senza segnali di preordine NON scarica la pagina HTML', async () => {
  const htmlUrls: string[] = []
  const trackedHttp = async (url: string) => {
    if (url.includes('/products/') && !url.includes('/products.json')) {
      htmlUrls.push(url)
    }
    return http(url)
  }
  const out = await fetchShopifyProducts('https://shop.example', trackedHttp, undefined, now, {
    htmlDelayMs: 0,
  })
  const senzaData = out.find((p) => p.externalId === '16087837376846')
  expect(senzaData?.availabilityRaw).toBe('true')
  expect(senzaData?.releaseDateRaw).toBeUndefined()
  expect(htmlUrls).not.toContain('https://shop.example/products/gioco-senza-data')
})

test('prodotto con segnali di preordine (title/body_html/tags) senza data nel body scarica HTML e diventa preordine', async () => {
  const out = await fetchShopifyProducts('https://shop.example', http, undefined, now, {
    htmlDelayMs: 0,
  })
  const preSenzaData = out.find((p) => p.externalId === '16087837376847')
  expect(preSenzaData?.availabilityRaw).toBe('preordine')
  expect(preSenzaData?.releaseDateRaw).toBe('2026-11-12')
})

test('preordini collection senza data: fetch HTML prende la data anche senza segnali nel titolo/body', async () => {
  const out = await fetchShopifyProducts('https://shop.example', http, 'preordini', now, {
    htmlDelayMs: 0,
  })
  const senzaData = out.find((p) => p.externalId === '16087837376846')
  expect(senzaData?.availabilityRaw).toBe('preordine')
  expect(senzaData?.releaseDateRaw).toBe('2026-11-20')
  const preSenzaData = out.find((p) => p.externalId === '16087837376847')
  expect(preSenzaData?.availabilityRaw).toBe('preordine')
  expect(preSenzaData?.releaseDateRaw).toBe('2026-11-12')
})

test('segnale di preordine nei tags attiva il fallback HTML e rispetta Retry-After su 429', async () => {
  let htmlAttempts = 0
  const sleeps: number[] = []
  const customHttp = async (url: string) => {
    if (url.includes('/products.json')) {
      return {
        status: 200,
        json: async () => ({
          products: [
            {
              id: 999,
              title: 'Nuovo Gioco',
              handle: 'nuovo-gioco',
              body_html: '<p>Gioco da tavolo</p>',
              tags: ['Boardgame', 'Pre-Order'],
              variants: [{ price: '39.90', available: true }],
            },
          ],
        }),
        text: async () => '',
      }
    }
    htmlAttempts++
    if (htmlAttempts === 1) {
      return {
        status: 429,
        headers: { get: (h: string) => (h.toLowerCase() === 'retry-after' ? '3' : null) },
        json: async () => ({}),
        text: async () => '',
      }
    }
    return {
      status: 200,
      json: async () => ({}),
      text: async () => productPageHtml('25/12/2026'),
    }
  }
  const out = await fetchShopifyProducts('https://shop.example', customHttp, undefined, now, {
    htmlDelayMs: 250,
    sleep: async (ms) => {
      sleeps.push(ms)
    },
  })
  expect(htmlAttempts).toBe(2)
  expect(sleeps).toContain(3000)
  expect(out[0]?.releaseDateRaw).toBe('2026-12-25')
  expect(out[0]?.availabilityRaw).toBe('preordine')
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

test('funziona con un oggetto Response nativo di fetch (status/headers su prototype)', async () => {
  const nativeHttp = async (url: string) => {
    if (url.includes('page=1')) {
      return new Response(
        JSON.stringify({
          products: [
            {
              id: 777,
              title: 'Native Response Game',
              handle: 'native-response-game',
              body_html: '<p>Gioco</p>',
              variants: [{ price: '29.90', available: true }],
            },
          ],
        }),
        { status: 200, headers: { 'content-type': 'application/json' } },
      ) as any
    }
    return new Response(JSON.stringify({ products: [] }), { status: 200 }) as any
  }
  const out = await fetchShopifyProducts('https://shop.example', nativeHttp, undefined, now)
  expect(out).toHaveLength(1)
  expect(out[0]).toMatchObject({
    externalId: '777',
    title: 'Native Response Game',
    url: 'https://shop.example/products/native-response-game',
    priceRaw: '29.90',
    availabilityRaw: 'true',
  })
})
