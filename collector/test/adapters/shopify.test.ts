import { expect, test } from 'vitest'
import {
  extractDateFromHtml,
  extractReleaseDateFromTags,
  fetchShopifyProducts,
} from '../../src/adapters/shopify.js'
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

test('extractReleaseDateFromTags parses Boardhouse MM-DD-YYYY, ISO YYYY-MM-DD, and DD-MM-YYYY tags', () => {
  expect(extractReleaseDateFromTags(['57x89', 'filler', 'uscita:11-27-2026'])).toBe('2026-11-27')
  expect(extractReleaseDateFromTags('filler, uscita:11-27-2026')).toBe('2026-11-27')
  expect(extractReleaseDateFromTags(['uscita:2026-12-18'])).toBe('2026-12-18')
  expect(extractReleaseDateFromTags(['uscita:27-11-2026'])).toBe('2026-11-27')
  expect(extractReleaseDateFromTags(['uscita:2026-02-30'])).toBeUndefined()
  expect(extractReleaseDateFromTags(['57x89', 'filler'])).toBeUndefined()
  expect(extractReleaseDateFromTags(undefined)).toBeUndefined()
})

test('fetchShopifyProducts usa tag uscita:MM-DD-YYYY come preordine senza scaricare HTML', async () => {
  const htmlUrls: string[] = []
  const bhHttp = async (url: string) => {
    if (url.includes('/products/') && !url.includes('/products.json')) {
      htmlUrls.push(url)
      return { status: 500, json: async () => ({}), text: async () => '' }
    }
    return {
      status: 200,
      json: async () => ({
        products: [
          {
            id: 888,
            title: 'Boardhouse Preorder Game',
            handle: 'boardhouse-preorder-game',
            body_html: '<p>Descrizione senza data</p>',
            tags: ['57x89', 'filler', 'uscita:11-27-2026'],
            variants: [{ price: '44.90', available: true }],
          },
        ],
      }),
      text: async () => '',
    }
  }
  const bhNow = new Date('2026-11-21T00:00:00Z')
  const out = await fetchShopifyProducts('https://boardhouse.example', bhHttp, undefined, bhNow, {
    htmlDelayMs: 0,
  })
  expect(out).toHaveLength(1)
  expect(out[0]?.releaseDateRaw).toBe('2026-11-27')
  expect(out[0]?.availabilityRaw).toBe('preordine')
  expect(htmlUrls).toHaveLength(0)
})

test('extractDateFromHtml ignora script/style/noscript e legge la data visibile', async () => {
  const html = `<!doctype html>
<html>
  <head>
    <script type="application/json">{"published_at":"2026-08-07T17:59:49+02:00"}</script>
    <style>.foo { content: "2026-01-01"; }</style>
  </head>
  <body>
    <noscript>2026-02-02</noscript>
    <div class="bh-preordine-avviso">Le spedizioni partiranno dal <strong>27 Novembre 2026</strong></div>
  </body>
</html>`
  const extracted = await extractDateFromHtml(
    'https://boardhouse.example/products/test',
    async () => html,
    async () => {},
  )
  expect(extracted).toBe('2026-11-27')
})

test('extractReleaseDateFromTags treats slash-separated dates as DD/MM/YYYY and hyphenated as MM-DD-YYYY', () => {
  expect(extractReleaseDateFromTags(['uscita:05/11/2026'])).toBe('2026-11-05')
  expect(extractReleaseDateFromTags(['uscita:27/11/2026'])).toBe('2026-11-27')
  expect(extractReleaseDateFromTags(['uscita:05-11-2026'])).toBe('2026-05-11')
  expect(extractReleaseDateFromTags(['uscita:11-27-2026'])).toBe('2026-11-27')
})

test('body_html viene convertito in testo prima di extractReleaseDate (ignora date negli attributi HTML)', async () => {
  const imgDateHttp = async (url: string) => {
    if (url.includes('/products.json')) {
      return {
        status: 200,
        json: async () => ({
          products: [
            {
              id: 1001,
              title: 'Gioco Con Immagine',
              handle: 'gioco-con-immagine',
              body_html:
                '<p><img src="https://cdn.example/uploads/2027-12-25/box.jpg" alt="Box" />Un classico gioco da tavolo.</p>',
              variants: [{ price: '35.00', available: true }],
            },
          ],
        }),
        text: async () => '',
      }
    }
    return { status: 404, json: async () => ({}), text: async () => '' }
  }
  const out = await fetchShopifyProducts('https://shop.example', imgDateHttp, undefined, now, {
    htmlDelayMs: 0,
  })
  expect(out).toHaveLength(1)
  expect(out[0]?.releaseDateRaw).toBeUndefined()
  expect(out[0]?.availabilityRaw).toBe('true')
})

test('extractDateFromHtml rimuove header/nav/footer e preferisce i container prodotto', async () => {
  const html = `<!doctype html>
<html>
  <body>
    <header><p>Promo valida fino al 01/11/2026</p></header>
    <nav><p>Evento 02/11/2026</p></nav>
    <div class="sidebar"><p>Altro gioco in uscita il 03/11/2026</p></div>
    <main>
      <div class="product-info"><p>Uscita prevista: 18/12/2026</p></div>
    </main>
    <footer><p>Copyright 2026-01-01</p></footer>
  </body>
</html>`
  const extracted = await extractDateFromHtml(
    'https://shop.example/products/test',
    async () => html,
    async () => {},
  )
  expect(extracted).toBe('2026-12-18')
})

test('preordini collection emette availabilityRaw="preordine" anche senza data futura se disponibile', async () => {
  const preHttp = async (url: string) => {
    if (url.includes('/products.json')) {
      return {
        status: 200,
        json: async () => ({
          products: [
            {
              id: 2001,
              title: 'Preordine Senza Data',
              handle: 'preordine-senza-data',
              body_html: '<p>Nessuna data indicata</p>',
              variants: [{ price: '49.90', available: true }],
            },
            {
              id: 2002,
              title: 'Preordine Esaurito',
              handle: 'preordine-esaurito',
              body_html: '<p>Nessuna data indicata</p>',
              variants: [{ price: '49.90', available: false }],
            },
          ],
        }),
        text: async () => '',
      }
    }
    return { status: 200, json: async () => ({}), text: async () => '<html><body><p>TBA</p></body></html>' }
  }
  const out = await fetchShopifyProducts('https://shop.example', preHttp, 'preordini', now, {
    htmlDelayMs: 0,
  })
  expect(out.find((p) => p.externalId === '2001')?.availabilityRaw).toBe('preordine')
  expect(out.find((p) => p.externalId === '2001')?.releaseDateRaw).toBeUndefined()
  expect(out.find((p) => p.externalId === '2002')?.availabilityRaw).toBe('false')
})

test('seleziona la prima variante disponibile invece di fermarsi a variants[0] esaurita', async () => {
  const variantHttp = async () => ({
    status: 200,
    json: async () => ({
      products: [
        {
          id: 3001,
          title: 'Multi Variant Game',
          handle: 'multi-variant-game',
          body_html: '<p>Gioco base</p>',
          variants: [
            { price: '10.00', available: false },
            { price: '45.00', available: true },
          ],
        },
      ],
    }),
    text: async () => '',
  })
  const out = await fetchShopifyProducts('https://shop.example', variantHttp, undefined, now, {
    htmlDelayMs: 0,
  })
  expect(out).toHaveLength(1)
  expect(out[0]?.availabilityRaw).toBe('true')
  expect(out[0]?.priceRaw).toBe('45.00')
})

test('salta product_type palesemente non giochi da tavolo e mantiene i boardgame', async () => {
  const mixedHttp = async () => ({
    status: 200,
    json: async () => ({
      products: [
        { id: 1, title: 'Bustine 63x88', handle: 'bustine', product_type: 'Bustine', variants: [{ price: '3.00', available: true }] },
        { id: 2, title: 'Sleeves', handle: 'sleeves', product_type: 'Card Sleeves', variants: [{ price: '4.00', available: true }] },
        { id: 3, title: 'Playmat', handle: 'playmat', product_type: 'Playmat', variants: [{ price: '20.00', available: true }] },
        { id: 4, title: 'Gift Card', handle: 'gift-card', product_type: 'Gift Card', variants: [{ price: '50.00', available: true }] },
        { id: 5, title: 'Buono Regalo', handle: 'buono', product_type: 'Buono Regalo', variants: [{ price: '25.00', available: true }] },
        { id: 6, title: 'Accessori', handle: 'accessori', product_type: 'Accessori', variants: [{ price: '9.00', available: true }] },
        { id: 7, title: 'Manga Vol 1', handle: 'manga', product_type: 'Manga', variants: [{ price: '7.00', available: true }] },
        { id: 8, title: 'Fumetto', handle: 'fumetti', product_type: 'Fumetti', variants: [{ price: '12.00', available: true }] },
        { id: 9, title: 'Set Dadi', handle: 'dadi', product_type: 'Dadi', variants: [{ price: '8.00', available: true }] },
        { id: 10, title: 'Quaderno', handle: 'cancelleria', product_type: 'Cancelleria', variants: [{ price: '6.00', available: true }] },
        { id: 11, title: 'Brass Birmingham', handle: 'brass', product_type: 'Giochi da tavolo', variants: [{ price: '79.90', available: true }] },
        { id: 12, title: 'Cascadia', handle: 'cascadia', variants: [{ price: '39.90', available: true }] },
      ],
    }),
    text: async () => '',
  })
  const out = await fetchShopifyProducts('https://shop.example', mixedHttp, undefined, now, {
    htmlDelayMs: 0,
  })
  expect(out.map((p) => p.externalId)).toEqual(['11', '12'])
})
