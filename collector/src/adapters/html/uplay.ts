import * as cheerio from 'cheerio'
import { fetchSitemapEntries, newestFirst } from '../sitemap.js'
import type { HtmlSourceSpec } from './crawl.js'
import { extractProduct } from './extract.js'
import { toItems } from './links.js'

const PRODUCT_RE = /\/gioco-da-tavolo-[\w-]+\.html$/

export const uplaySpec: HtmlSourceSpec = {
  async discover(fetchText) {
    const entries = await fetchSitemapEntries('https://www.uplay.it/sitemap.xml', fetchText)
    return toItems(newestFirst(entries)
      .map((e) => e.loc)
      .filter((url) => PRODUCT_RE.test(url)))
  },
  extract(html, url) {
    const $ = cheerio.load(html)
    const isBoardgame =
      $('.game-languages-label').length > 0 ||
      $('a[href*="boardgamegeek.com/boardgame"]').length > 0
    if (!isBoardgame) return null
    return extractProduct(html, url)
  },
}
