import { expect, test } from 'vitest'
import { readFileSync } from 'node:fs'
import { extractProduct } from '../../src/adapters/html/extract.js'

const read = (f: string) => readFileSync(new URL(`../fixtures/${f}`, import.meta.url), 'utf8')

test('extracts a product from JSON-LD (Review Focus 2)', () => {
  const p = extractProduct(read('product-ldjson.html'), 'https://www.uplay.it/it/gioco-da-tavolo-mystery-express.html')
  expect(p).toMatchObject({
    externalId: 'UPL-123',
    title: 'Mystery Express',
    url: 'https://www.uplay.it/it/gioco-da-tavolo-mystery-express.html',
    priceRaw: '34.90',
    currencyRaw: 'EUR',
    imageUrl: 'https://x/m.jpg',
  })
  expect(p?.availabilityRaw).toContain('InStock')
})

test('falls back to Open Graph when there is no JSON-LD', () => {
  const p = extractProduct(read('product-og.html'), 'https://e.com/p')
  expect(p).toMatchObject({ title: 'Catan', priceRaw: '39.99', currencyRaw: 'EUR', url: 'https://e.com/p' })
})

test('returns null when nothing usable is present (Review Focus 1)', () => {
  expect(extractProduct(read('product-empty.html'), 'https://e.com/p')).toBeNull()
})

test('uses the page URL when there is no canonical (Review Focus 3)', () => {
  const p = extractProduct(read('product-og.html'), 'https://e.com/prodotto/catan')
  expect(p?.url).toBe('https://e.com/prodotto/catan')
})

test('finds the product nested under ItemPage.mainEntity (giochiuniti/Magento)', () => {
  const html = `<script type="application/ld+json">{"@context":"https://schema.org","@type":"ItemPage","name":"I Giochi dei Grandi","mainEntity":{"@type":"Product","name":"TOMBOLA 48 LEGNO","sku":"H6612","image":"https://x/h6612_1.jpg","offers":{"@type":"Offer","price":"17.5","priceCurrency":"EUR","availability":"http://schema.org/OutOfStock"}}}</script>`
  const p = extractProduct(html, 'https://www.igiochideigrandi.it/h6612.html')
  expect(p).toMatchObject({
    externalId: 'H6612',
    title: 'TOMBOLA 48 LEGNO',
    url: 'https://www.igiochideigrandi.it/h6612.html',
    priceRaw: '17.5',
    currencyRaw: 'EUR',
    imageUrl: 'https://x/h6612_1.jpg',
  })
  expect(p?.availabilityRaw).toContain('OutOfStock')
})

test('itemprop description vince sull og:description quando e piu lungo', () => {
  const html = `<html><head>
    <meta property="og:title" content="Dungeon Dice" />
    <meta property="og:description" content="Gioca con i dadi." />
  </head><body>
    <div itemprop="description">Una descrizione lunga e dettagliata scritta dal produttore, che racconta meccanica, componenti e durata della partita.</div>
  </body></html>`
  const p = extractProduct(html, 'https://e.com/p')
  expect(p?.descriptionRaw).toContain('scritta dal produttore')
})

test('senza itemprop resta l og:description', () => {
  const html = `<html><head>
    <meta property="og:title" content="Catan" />
    <meta property="og:description" content="Il classico gioco di insediamento." />
  </head><body></body></html>`
  const p = extractProduct(html, 'https://e.com/p')
  expect(p?.descriptionRaw).toBe('Il classico gioco di insediamento.')
})

test('non usa il meta name description da tre righe', () => {
  const html = `<html><head>
    <meta property="og:title" content="Catan" />
    <meta name="description" content="Acquista Catan. Scopri tutte le offerte" />
  </head><body></body></html>`
  const p = extractProduct(html, 'https://e.com/p')
  expect(p?.descriptionRaw).toBeUndefined()
})

test('strips script, style, and noscript inside itemprop description', () => {
  const html = `<html><head>
    <meta property="og:title" content="Heat" />
  </head><body>
    <div itemprop="description">
      <style>.x { color: red; }</style>
      <script>window.alert("xss")</script>
      <noscript>Abilita JS</noscript>
      Una corsa mozzafiato negli anni Sessanta con gestione della mano.
    </div>
  </body></html>`
  const p = extractProduct(html, 'https://e.com/heat')
  expect(p?.descriptionRaw).toBe('Una corsa mozzafiato negli anni Sessanta con gestione della mano.')
})

test('extracts releaseDateRaw from JSON-LD releaseDate or offer.availabilityStarts', () => {
  const withReleaseDate = `<script type="application/ld+json">{"@type":"Product","name":"Dune","releaseDate":"2026-11-15","offers":{"@type":"Offer","price":"59.90","priceCurrency":"EUR"}}</script>`
  expect(extractProduct(withReleaseDate, 'https://e.com/dune')?.releaseDateRaw).toBe('2026-11-15')

  const withAvailStarts = `<script type="application/ld+json">{"@type":"Product","name":"Dune 2","offers":{"@type":"Offer","price":"49.90","priceCurrency":"EUR","availability":"https://schema.org/PreOrder","availabilityStarts":"2026-12-01T00:00:00Z"}}</script>`
  expect(extractProduct(withAvailStarts, 'https://e.com/dune-2')?.releaseDateRaw).toBe('2026-12-01T00:00:00Z')
})

test('supports AggregateOffer nested offers and prefers InStock or PreOrder offer', () => {
  const html = `<script type="application/ld+json">{
    "@type":"Product",
    "name":"Ark Nova",
    "offers":{
      "@type":"AggregateOffer",
      "priceCurrency":"EUR",
      "lowPrice":"54.90",
      "offers":[
        {"@type":"Offer","price":"64.90","availability":"https://schema.org/OutOfStock"},
        {"@type":"Offer","price":"59.90","availability":"https://schema.org/PreOrder"},
        {"@type":"Offer","price":"54.90","availability":"https://schema.org/InStock"}
      ]
    }
  }</script>`
  const p = extractProduct(html, 'https://e.com/ark-nova')
  expect(p).toMatchObject({
    title: 'Ark Nova',
    priceRaw: '54.90',
    currencyRaw: 'EUR',
    availabilityRaw: 'https://schema.org/InStock',
  })

  const preorderOnly = `<script type="application/ld+json">{
    "@type":"Product",
    "name":"Ark Nova Exp",
    "offers":[
      {"@type":"Offer","price":"35.00","priceCurrency":"EUR","availability":"https://schema.org/OutOfStock"},
      {"@type":"Offer","price":"29.90","priceCurrency":"EUR","availability":"https://schema.org/PreOrder"}
    ]
  }</script>`
  expect(extractProduct(preorderOnly, 'https://e.com/ark-nova-exp')).toMatchObject({
    priceRaw: '29.90',
    availabilityRaw: 'https://schema.org/PreOrder',
  })
})

test('prefers canonical or pageUrl over relative/invalid offer.url', () => {
  const html = `<html><head><link rel="canonical" href="https://e.com/canonical-catan" /></head><body>
    <script type="application/ld+json">{"@type":"Product","name":"Catan","offers":{"@type":"Offer","url":"/relative/path","price":"39.90","priceCurrency":"EUR"}}</script>
  </body></html>`
  expect(extractProduct(html, 'https://e.com/catan')?.url).toBe('https://e.com/canonical-catan')

  const noCanonical = `<script type="application/ld+json">{"@type":"Product","name":"Catan","offers":{"@type":"Offer","url":"not-a-url","price":"39.90","priceCurrency":"EUR"}}</script>`
  expect(extractProduct(noCanonical, 'https://e.com/catan')?.url).toBe('https://e.com/catan')
})

test('strips common trailing site-name suffixes from og:title fallback', () => {
  const cases: Array<[string, string]> = [
    ['Heat - Asmodee Italia', 'Heat'],
    ['Alpaca | GateOnGames', 'Alpaca'],
    ['Harmonies – DungeonDice.it', 'Harmonies'],
    ['Catan - Giochi Uniti', 'Catan'],
    ['Villainous | Ravensburger', 'Villainous'],
    ['Azul - Uplay.it', 'Azul'],
  ]
  for (const [ogTitle, expected] of cases) {
    const html = `<html><head><meta property="og:title" content="${ogTitle}" /></head></html>`
    expect(extractProduct(html, 'https://e.com/p')?.title).toBe(expected)
  }
})

test('filters non-boardgame JSON-LD brands without falling back to Open Graph', () => {
  const pantasy = `<html><head><meta property="og:title" content="Retro Bakery" /></head><body>
    <script type="application/ld+json">{"@type":"Product","name":"Retro Bakery PAN85001","brand":"PANTASY","offers":{"@type":"Offer","price":"59.90","priceCurrency":"EUR"}}</script>
  </body></html>`
  expect(extractProduct(pantasy, 'https://www.uplay.it/it/retro-bakery.html')).toBeNull()

  const lemax = `<html><head><meta property="og:title" content="Christmas Village" /></head><body>
    <script type="application/ld+json">{"@type":"Product","name":"Christmas Village","brand":{"@type":"Brand","name":"Lemax"},"offers":{"@type":"Offer","price":"39.90","priceCurrency":"EUR"}}</script>
  </body></html>`
  expect(extractProduct(lemax, 'https://www.uplay.it/it/christmas-village.html')).toBeNull()

  const legoSet = `<html><head><meta property="og:title" content="LEGO Millennium Falcon" /></head><body>
    <script type="application/ld+json">{"@type":"Product","name":"LEGO Star Wars Millennium Falcon 75375","brand":{"@type":"Brand","name":"LEGO"},"offers":{"@type":"Offer","price":"84.99","priceCurrency":"EUR"}}</script>
  </body></html>`
  expect(extractProduct(legoSet, 'https://www.uplay.it/it/lego-falcon.html')).toBeNull()
})

test('populates publisher from JSON-LD brand for real board games and allowed LEGO board games', () => {
  const saltPepper = `<script type="application/ld+json">{"@type":"Product","name":"Conservas","brand":{"@type":"Brand","name":"  Salt & Pepper Games  "},"offers":{"@type":"Offer","price":"24.90","priceCurrency":"EUR"}}</script>`
  expect(extractProduct(saltPepper, 'https://www.uplay.it/it/conservas.html')).toMatchObject({
    title: 'Conservas',
    publisher: 'Salt & Pepper Games',
  })

  const stringBrand = `<script type="application/ld+json">{"@type":"Product","name":"Barrage","brand":"Cranio Creations","offers":{"@type":"Offer","price":"74.90","priceCurrency":"EUR"}}</script>`
  expect(extractProduct(stringBrand, 'https://www.uplay.it/it/barrage.html')).toMatchObject({
    title: 'Barrage',
    publisher: 'Cranio Creations',
  })

  const monkeyPalace = `<script type="application/ld+json">{"@type":"Product","name":"Monkey Palace - Un Gioco da Tavolo LEGO®","brand":{"@type":"Brand","name":"LEGO"},"offers":{"@type":"Offer","price":"39.90","priceCurrency":"EUR"}}</script>`
  expect(extractProduct(monkeyPalace, 'https://www.uplay.it/it/monkey-palace.html')).toMatchObject({
    title: 'Monkey Palace - Un Gioco da Tavolo LEGO®',
    publisher: 'LEGO',
  })

  const destinysBounty = `<script type="application/ld+json">{"@type":"Product","name":"LEGO NINJAGO: Destiny's Bounty Adventures","brand":"LEGO","offers":{"@type":"Offer","price":"34.90","priceCurrency":"EUR"}}</script>`
  expect(extractProduct(destinysBounty, 'https://www.uplay.it/it/destinys-bounty.html')).toMatchObject({
    title: "LEGO NINJAGO: Destiny's Bounty Adventures",
    publisher: 'LEGO',
  })
})
