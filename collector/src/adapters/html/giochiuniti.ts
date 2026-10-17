import type { HtmlSourceSpec } from './crawl.js'
import { fetchSitemapEntries, newestFirst } from '../sitemap.js'

const SITEMAP_INDEX = 'https://www.giochiuniti.it/sitemap.xml'

const PRODUCT_URL = /^https:\/\/www\.igiochideigrandi\.it\/[^/]+\.html$/

export const giochiunitiSpec: HtmlSourceSpec = {
  discover: async (fetchText) => {
    const entries = await fetchSitemapEntries(SITEMAP_INDEX, fetchText)
    return newestFirst(entries)
      .map((entry) => entry.loc)
      .filter((loc) => PRODUCT_URL.test(loc))
  },
}
