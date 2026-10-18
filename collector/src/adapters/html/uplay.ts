import { fetchSitemapEntries, newestFirst } from '../sitemap.js'
import type { HtmlSourceSpec } from './crawl.js'
import { toItems } from './links.js'

const PRODUCT_RE = /\/gioco-da-tavolo-[\w-]+\.html$/

export const uplaySpec: HtmlSourceSpec = {
  async discover(fetchText) {
    const entries = await fetchSitemapEntries('https://www.uplay.it/sitemap.xml', fetchText)
    return toItems(newestFirst(entries)
      .map((e) => e.loc)
      .filter((url) => PRODUCT_RE.test(url)))
  },
}
