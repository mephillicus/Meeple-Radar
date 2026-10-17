import * as cheerio from 'cheerio'
import type { HtmlSourceSpec } from './crawl.js'
import { collectLinks, toItems } from './links.js'
import { buildProduct } from './extract.js'

const SHOP_URL = 'https://horribleguild.com/eu/shop/'

// Solo la regione /eu/ (prezzi in EUR): la stessa scheda esiste anche sotto /us/.
const PRODUCT_URL = /^https:\/\/horribleguild\.com\/eu\/product\/[a-z0-9-]+\/$/

export const horribleguildSpec: HtmlSourceSpec = {
  discover: async (fetchText) => toItems(await collectLinks(fetchText, [SHOP_URL], (url) => PRODUCT_URL.test(url))),
  extract(html, url) {
    const $ = cheerio.load(html)
    const title = $('.product_title').first().text().trim()
    const priceText = $('.summary .price .woocommerce-Price-amount bdi').first().text()
    const price = priceText.match(/[\d.,]+/)?.[0]
    return buildProduct({ title, url, price, currency: price === undefined ? undefined : 'EUR' })
  },
}
