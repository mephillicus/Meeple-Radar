import { expect, test } from 'vitest'
import { HTML_SPECS } from '../../src/adapters/html/specs.js'

const LISTING = 'https://www.dvgiochi.com/prossime-uscite/'

test('dvgiochi discovers catalog links, reading the release date from the start of the anchor text', async () => {
  const pages: Record<string, string> = {
    [LISTING]: `<html><body>
      <a href="/catalogo/">Catalogo</a>
      <a href="/catalogo/black-flag-ita/">27 OTTOBRE 2026 – in arrivo BANG! Il gioco di carte definitivo!</a>
      <a href="/catalogo/nuovo-gioco/">DA DEFINIRE – in arrivo Nuovo gioco: scoprilo presto!</a>
    </body></html>`,
  }
  const items = await HTML_SPECS.dvgiochi.discover(async (u) => pages[u] ?? null)
  expect(items).toEqual([
    { url: 'https://www.dvgiochi.com/catalogo/black-flag-ita/', releaseDate: '2026-10-27' },
    { url: 'https://www.dvgiochi.com/catalogo/nuovo-gioco/' },
  ])
})

test('dvgiochi extract takes the clean product name from the h2 image title', () => {
  const html = `<html><body>
    <h2>BANG! Game System | Black Flag! (ITA) | DV Games<br /> <img src="/images/infobox.gif" alt="Black Flag! (ITA)" title="Black Flag! (ITA)"></h2>
  </body></html>`
  const product = HTML_SPECS.dvgiochi.extract?.(html, 'https://www.dvgiochi.com/catalogo/black-flag-ita/')
  expect(product?.title).toBe('Black Flag! (ITA)')
  expect(product?.externalId).toBe('black-flag-ita')
  expect(product?.priceRaw).toBeUndefined()
})

test('dvgiochi extract falls back to the h2 text node when there is no image', () => {
  const html = `<html><body><h2>Combo Creatures: Piccoli e scattanti</h2></body></html>`
  const product = HTML_SPECS.dvgiochi.extract?.(html, 'https://www.dvgiochi.com/catalogo/combo-creatures/')
  expect(product?.title).toBe('Combo Creatures: Piccoli e scattanti')
})

test('dvgiochi extract returns null when the page has no h2', () => {
  const html = `<html><body><div>nessun titolo</div></body></html>`
  expect(HTML_SPECS.dvgiochi.extract?.(html, 'https://www.dvgiochi.com/catalogo/senza-titolo/')).toBeNull()
})

const HG_LISTING = 'https://horribleguild.com/eu/shop/'

test('horribleguild discovers only the /eu/product/ links from the shop listing', async () => {
  const pages: Record<string, string> = {
    [HG_LISTING]: `<html><body>
      <a href="https://horribleguild.com/eu/product/railroad-ink-challenge/">Railroad Ink Challenge</a>
      <a href="https://horribleguild.com/eu/product/kingdomino/">Kingdomino</a>
      <a href="https://horribleguild.com/us/product/railroad-ink-challenge/">Railroad Ink Challenge (US)</a>
      <a href="https://horribleguild.com/eu/cart/">Carrello</a>
    </body></html>`,
  }
  const items = await HTML_SPECS.horribleguild.discover(async (u) => pages[u] ?? null)
  expect(items).toEqual([
    { url: 'https://horribleguild.com/eu/product/railroad-ink-challenge/' },
    { url: 'https://horribleguild.com/eu/product/kingdomino/' },
  ])
})

test('horribleguild extract reads the WooCommerce title and the EUR price', () => {
  const html = `<html><body>
    <h2 class="single-post-title product_title entry-title" itemprop="name">Railroad Ink Challenge</h2>
    <div class="summary entry-summary">
      <p class="price"><span class="woocommerce-Price-amount amount"><bdi>24.90<span>€</span></bdi></span></p>
    </div>
  </body></html>`
  const product = HTML_SPECS.horribleguild.extract?.(html, 'https://horribleguild.com/eu/product/railroad-ink-challenge/')
  expect(product?.title).toBe('Railroad Ink Challenge')
  expect(product?.priceRaw).toBe('24.90')
  expect(product?.currencyRaw).toBe('EUR')
})

test('horribleguild extract keeps priceRaw undefined when the page has no price', () => {
  const html = `<html><body>
    <h2 class="single-post-title product_title entry-title" itemprop="name">Railroad Ink Challenge</h2>
    <div class="summary entry-summary"></div>
  </body></html>`
  const product = HTML_SPECS.horribleguild.extract?.(html, 'https://horribleguild.com/eu/product/railroad-ink-challenge/')
  expect(product?.title).toBe('Railroad Ink Challenge')
  expect(product?.priceRaw).toBeUndefined()
})

const GOG_SEEDS = [
  'https://www.gateongames.com/prodotti/localizzazioni/',
  'https://www.gateongames.com/prodotti/originali-gog/',
]

test('gateongames discovers only product links, skipping menu pages and the German subtree', async () => {
  const pages: Record<string, string> = {
    [GOG_SEEDS[0]]: `<html><body>
      <a href="/alpaca/">Alpaca</a>
      <a href="/mini-crimes/">Mini Crimes</a>
      <a href="/chi-siamo/">Chi Siamo</a>
      <a href="/privacy-policy/">Privacy Policy</a>
      <a href="https://www.gateongames.com/de/prodotti/gog-originalen/">GOG Originalen</a>
      <a href="/prodotti/localizzazioni/">Torna alle localizzazioni</a>
    </body></html>`,
  }
  const items = await HTML_SPECS.gateongames.discover(async (u) => pages[u] ?? null)
  expect(items).toEqual([
    { url: 'https://www.gateongames.com/alpaca/' },
    { url: 'https://www.gateongames.com/mini-crimes/' },
  ])
})

test('gateongames deduplicates a link found on both source pages', async () => {
  const pages: Record<string, string> = {
    [GOG_SEEDS[0]]: `<html><body>
      <a href="/alpaca/">Alpaca</a>
      <a href="/contattaci/">Contattaci</a>
    </body></html>`,
    [GOG_SEEDS[1]]: `<html><body>
      <a href="/alpaca/">Alpaca (originali GoG)</a>
      <a href="/corti-in-gioco/">Corti in Gioco</a>
    </body></html>`,
  }
  const items = await HTML_SPECS.gateongames.discover(async (u) => pages[u] ?? null)
  expect(items).toEqual([
    { url: 'https://www.gateongames.com/alpaca/' },
  ])
})

test('horribleguild extract prefers the sale price over the struck-through original', () => {
  const html = `<html><body>
    <h2 class="single-post-title product_title entry-title" itemprop="name">Railroad Ink Challenge</h2>
    <div class="summary entry-summary">
      <p class="price"><del><span class="woocommerce-Price-amount amount"><bdi>29,90<span>€</span></bdi></span></del> <ins><span class="woocommerce-Price-amount amount"><bdi>24,90<span>€</span></bdi></span></ins></p>
    </div>
  </body></html>`
  const product = HTML_SPECS.horribleguild.extract?.(html, 'https://horribleguild.com/eu/product/railroad-ink-challenge/')
  expect(product?.priceRaw).toBe('24.90')
  expect(product?.currencyRaw).toBe('EUR')
})

test('horribleguild extract normalizes comma decimals to a dot', () => {
  const html = `<html><body>
    <h2 class="single-post-title product_title entry-title" itemprop="name">Kingdomino</h2>
    <div class="summary entry-summary">
      <p class="price"><span class="woocommerce-Price-amount amount"><bdi>24,90<span>€</span></bdi></span></p>
    </div>
  </body></html>`
  const product = HTML_SPECS.horribleguild.extract?.(html, 'https://horribleguild.com/eu/product/kingdomino/')
  expect(product?.priceRaw).toBe('24.90')
})

test('horribleguild extract normalizes Italian thousands separators', () => {
  const html = `<html><body>
    <h2 class="single-post-title product_title entry-title" itemprop="name">Expensive Deluxe</h2>
    <div class="summary entry-summary">
      <p class="price"><span class="woocommerce-Price-amount amount"><bdi>1.234,56<span>€</span></bdi></span></p>
    </div>
  </body></html>`
  const product = HTML_SPECS.horribleguild.extract?.(html, 'https://horribleguild.com/eu/product/expensive-deluxe/')
  expect(product?.priceRaw).toBe('1234.56')
})

test('horribleguild extract leaves priceRaw undefined when the price text has no number', () => {
  const html = `<html><body>
    <h2 class="single-post-title product_title entry-title" itemprop="name">Mystery Box</h2>
    <div class="summary entry-summary">
      <p class="price"><span class="woocommerce-Price-amount amount"><bdi>N/A<span>€</span></bdi></span></p>
    </div>
  </body></html>`
  const product = HTML_SPECS.horribleguild.extract?.(html, 'https://horribleguild.com/eu/product/mystery-box/')
  expect(product?.title).toBe('Mystery Box')
  expect(product?.priceRaw).toBeUndefined()
})
