import { readFileSync } from 'node:fs'
import { expect, test } from 'vitest'
import { collectHtmlProducts } from '../../src/adapters/html/crawl.js'
import { HTML_SPECS } from '../../src/adapters/html/specs.js'
import { HEADLESS_SPECS } from '../../src/adapters/headless/specs.js'
import { fetchRoundupProducts } from '../../src/adapters/roundups.js'

const fixture = (name: string) =>
  readFileSync(new URL(`../fixtures/${name}`, import.meta.url), 'utf8')

test('contract: asmodee (sitemap discovery + JSON-LD/visible description extraction)', async () => {
  const productUrl = 'https://www.asmodee.it/product/heat-pedale-a-tavoletta/'
  const pages: Record<string, string> = {
    'https://www.asmodee.it/product-sitemap.xml': `<urlset>
      <url><loc>https://www.asmodee.it/chi-siamo/</loc></url>
      <url><loc>${productUrl}</loc><lastmod>2026-10-15</lastmod></url>
    </urlset>`,
    [productUrl]: fixture('asmodee-product.html'),
  }

  const products = await collectHtmlProducts({
    spec: HTML_SPECS.asmodee,
    fetchText: async (u) => pages[u] ?? null,
  })

  expect(products).toHaveLength(1)
  expect(products[0]).toMatchObject({
    externalId: 'ASM-HEAT-01',
    title: 'Heat: Pedale a Tavoletta',
    url: productUrl,
    priceRaw: '59.90',
    currencyRaw: 'EUR',
    availabilityRaw: 'https://schema.org/InStock',
    imageUrl: 'https://www.asmodee.it/wp-content/uploads/heat-cover.jpg',
  })
  expect(products[0].descriptionRaw).toContain('monoposto degli anni Sessanta')
})

test('contract: dvgiochi (prossime-uscite listing + catalog page extraction)', async () => {
  const blackFlagUrl = 'https://www.dvgiochi.com/catalogo/black-flag-ita/'
  const villaUrl = 'https://www.dvgiochi.com/catalogo/villa/'
  const pages: Record<string, string> = {
    'https://www.dvgiochi.com/prossime-uscite/': fixture('dvgiochi-listing.html'),
    [blackFlagUrl]: fixture('dvgiochi-product.html'),
    [villaUrl]: '<html><body><h2>Villa | DV Games</h2></body></html>',
  }

  const products = await collectHtmlProducts({
    spec: HTML_SPECS.dvgiochi,
    fetchText: async (u) => pages[u] ?? null,
  })

  expect(products).toHaveLength(2)
  expect(products[0]).toMatchObject({
    externalId: 'black-flag-ita',
    title: 'Black Flag! (ITA)',
    url: blackFlagUrl,
    releaseDateRaw: '2026-10-27',
  })
  expect(products[0].descriptionRaw).toContain('isole dei Caraibi')
  expect(products[0].descriptionRaw).not.toContain('60 carte')
  expect(products[1]).toMatchObject({
    externalId: 'villa',
    title: 'Villa',
    url: villaUrl,
  })
  expect(products[1].releaseDateRaw).toBeUndefined()
})

test('contract: horribleguild (/eu/shop discovery + WooCommerce sale price & description)', async () => {
  const productUrl = 'https://horribleguild.com/eu/product/railroad-ink-challenge/'
  const pages: Record<string, string> = {
    'https://horribleguild.com/eu/shop/': `<html><body>
      <a href="${productUrl}">Railroad Ink Challenge</a>
      <a href="https://horribleguild.com/us/product/railroad-ink-challenge/">US</a>
    </body></html>`,
    [productUrl]: fixture('horribleguild-product.html'),
  }

  const products = await collectHtmlProducts({
    spec: HTML_SPECS.horribleguild,
    fetchText: async (u) => pages[u] ?? null,
  })

  expect(products).toHaveLength(1)
  expect(products[0]).toMatchObject({
    externalId: 'railroad-ink-challenge',
    title: 'Railroad Ink Challenge',
    url: productUrl,
    priceRaw: '24.90',
    currencyRaw: 'EUR',
  })
  expect(products[0].descriptionRaw).toContain('rete ferroviaria')
  expect(products[0].descriptionRaw).not.toContain('Description')
})

test('contract: gateongames (localizzazioni discovery + JSON-LD + Scopri prose)', async () => {
  const productUrl = 'https://www.gateongames.com/alpaca/'
  const pages: Record<string, string> = {
    'https://www.gateongames.com/prodotti/localizzazioni/': `<html><body>
      <a href="/alpaca/">Alpaca</a>
      <a href="/chi-siamo/">Chi Siamo</a>
    </body></html>`,
    'https://www.gateongames.com/prodotti/originali-gog/': '<html><body></body></html>',
    [productUrl]: fixture('gateongames-product.html'),
  }

  const products = await collectHtmlProducts({
    spec: HTML_SPECS.gateongames,
    fetchText: async (u) => pages[u] ?? null,
  })

  expect(products).toHaveLength(1)
  expect(products[0]).toMatchObject({
    externalId: 'GOG-ALPACA',
    title: 'Alpaca',
    url: productUrl,
    priceRaw: '19.90',
    currencyRaw: 'EUR',
    availabilityRaw: 'https://schema.org/PreOrder',
    imageUrl: 'https://www.gateongames.com/wp-content/uploads/alpaca.webp',
  })
  expect(products[0].descriptionRaw).toContain('deckbuilding sulle Ande')
  expect(products[0].descriptionRaw).not.toContain('120 carte')
})

test('contract: dungeondice (ultime-novita listing + PrestaShop JSON-LD extraction)', async () => {
  const productUrl = 'https://www.dungeondice.it/44937-harmonies.html'
  const pages: Record<string, string> = {
    'https://www.dungeondice.it/445-ultime-novita': fixture('dungeondice-listing.html'),
    'https://www.dungeondice.it/445-ultime-novita?page=2': '<html><body></body></html>',
    'https://www.dungeondice.it/445-ultime-novita?page=3': '<html><body></body></html>',
    'https://www.dungeondice.it/445-ultime-novita?page=4': '<html><body></body></html>',
    [productUrl]: fixture('dungeondice-product.html'),
  }

  const products = await collectHtmlProducts({
    spec: HEADLESS_SPECS.dungeondice,
    fetchText: async (u) => pages[u] ?? null,
  })

  expect(products).toHaveLength(1)
  expect(products[0]).toMatchObject({
    externalId: 'DD-44937',
    title: 'Harmonies',
    url: productUrl,
    priceRaw: '34.90',
    currencyRaw: 'EUR',
    availabilityRaw: 'https://schema.org/InStock',
    imageUrl: 'https://www.dungeondice.it/img/p/harmonies.jpg',
  })
  expect(products[0].descriptionRaw).toContain('paesaggi tridimensionali')
})

test('contract: houseofgames (post-sitemap.xml + roundup article HTML fixture)', async () => {
  const sitemapUrl = 'https://www.houseofgames.it/post-sitemap.xml'
  const articleUrl =
    'https://www.houseofgames.it/giochi-da-tavolo-tutte-le-nuove-uscite-di-ottobre-lucca-comics-e-combo-festival-2026/'
  const pages: Record<string, string> = {
    [sitemapUrl]: fixture('hog-post-sitemap.xml'),
    [articleUrl]: fixture('hog-roundup-article.html'),
  }

  const products = await fetchRoundupProducts(
    {
      id: 'houseofgames',
      name: 'House of Games',
      kind: 'roundups',
      baseUrl: 'https://www.houseofgames.it',
      enabled: true,
    },
    undefined,
    { fetchText: async (u) => pages[u] ?? null, sleep: async () => {} },
  )

  expect(products).toHaveLength(87)
  expect(products[0]).toMatchObject({
    externalId: 'toy-battle-la-croisette',
    title: 'TOY BATTLE – LA CROISETTE',
    publisher: 'ASMODEE',
    releaseDateRaw: '2026-10-02',
    url: articleUrl,
  })
})
