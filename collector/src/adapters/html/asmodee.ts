import { fetchSitemapEntries, newestFirst } from '../sitemap.js'
import type { HtmlSourceSpec } from './crawl.js'

const PRODUCT_RE = /\/product\/[^/]+\/$/

export const asmodeeSpec: HtmlSourceSpec = {
  async discover(fetchText) {
    const entries = await fetchSitemapEntries('https://www.asmodee.it/product-sitemap.xml', fetchText)
    return newestFirst(entries)
      .map((e) => e.loc)
      .filter((url) => PRODUCT_RE.test(url))
  },
}
