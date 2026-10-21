import * as cheerio from 'cheerio'
import type { HtmlSourceSpec } from './crawl.js'
import { collectLinks, toItems } from './links.js'
import { buildProduct } from './extract.js'

const SHOP_URL = 'https://horribleguild.com/eu/shop/'

// Solo la regione /eu/ (prezzi in EUR): la stessa scheda esiste anche sotto /us/.
const PRODUCT_URL = /^https:\/\/horribleguild\.com\/eu\/product\/[a-z0-9-]+\/$/

// In sconto WooCommerce mostra sia il prezzo originale (<del>) sia quello
// corrente (<ins>): serve il secondo, il primo è il prezzo sbarrato.
function priceTextFrom($: cheerio.CheerioAPI): string {
  const sale = $('.summary .price ins .woocommerce-Price-amount bdi').first().text()
  if (sale.trim()) return sale
  return $('.summary .price .woocommerce-Price-amount bdi').first().text()
}

// Accetta sia "24,90" (virgola) sia "24.90" (punto), con o senza migliaia.
function normalizePrice(text: string): string | undefined {
  const t = text.trim().replace(/\s+/g, '')
  if (!t) return undefined
  const commas = (t.match(/,/g) ?? []).length
  const dots = (t.match(/\./g) ?? []).length
  let s = t
  if (commas > 0 && dots > 0) {
    s = t.lastIndexOf(',') > t.lastIndexOf('.')
      ? t.replace(/\./g, '').replace(',', '.')
      : t.replace(/,/g, '')
  } else if (commas === 1) {
    s = t.replace(',', '.')
  }
  if (!Number.isFinite(Number(s))) return undefined
  return s
}

// Il tab Description e il testo lungo della pagina; lo short-description ha
// dentro le righe Designer/Artist con le immagini, quindi si usa solo se manca
// il tab. L'<h2> di testa e l'etichetta della sezione, non parte del testo.
function descriptionFrom($: cheerio.CheerioAPI): string | undefined {
  const tab = $('#tab-description').first()
  const source = tab.length > 0 ? tab : $('.woocommerce-product-details__short-description').first()
  if (source.length === 0) return undefined
  const copy = source.clone()
  copy.find('h2').first().remove()
  return copy.html()?.trim() || undefined
}

export const horribleguildSpec: HtmlSourceSpec = {
  discover: async (fetchText) => toItems(await collectLinks(fetchText, [SHOP_URL], (url) => PRODUCT_URL.test(url))),
  extract(html, url) {
    const $ = cheerio.load(html)
    const title = $('.product_title').first().text().trim()
    const rawPrice = priceTextFrom($).match(/[\d.,]+/)?.[0]
    const price = rawPrice === undefined ? undefined : normalizePrice(rawPrice)
    return buildProduct({
      title,
      url,
      price,
      currency: price === undefined ? undefined : 'EUR',
      description: descriptionFrom($),
    })
  },
}
