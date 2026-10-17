import { fetchSitemapEntries, newestFirst } from '../sitemap.js'
import type { HtmlSourceSpec } from './crawl.js'

const PRODUCT_RE = /\/gioco-da-tavolo-[\w-]+\.html$/

export const uplaySpec: HtmlSourceSpec = {
  async discover(fetchText) {
    const entries = await fetchSitemapEntries('https://www.uplay.it/sitemap.xml', fetchText)
    return newestFirst(entries)
      .map((e) => e.loc)
      .filter((url) => PRODUCT_RE.test(url))
  },
}
