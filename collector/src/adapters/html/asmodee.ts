import { fetchSitemapEntries, newestFirst } from '../sitemap.js'
import type { HtmlSourceSpec } from './crawl.js'
import { toItems } from './links.js'

const PRODUCT_RE = /\/product\/[^/]+\/$/

export const asmodeeSpec: HtmlSourceSpec = {
  async discover(fetchText) {
    const entries = await fetchSitemapEntries('https://www.asmodee.it/product-sitemap.xml', fetchText)
    return toItems(newestFirst(entries)
      .map((e) => e.loc)
      .filter((url) => PRODUCT_RE.test(url)))
  },
}
