import type { HtmlSourceSpec } from './crawl.js'
import { fetchSitemapEntries, newestFirst } from '../sitemap.js'
import { toItems } from './links.js'

const SITEMAP = 'https://www.ravensburger.it/sitemap.xml'

// ravensburger.it è dietro Cloudflare: il fetch di Node riceve 403 ("Just a moment…"),
// quindi la fonte è `headless` (Task 9 riusa questo spec con il renderer browser).
function isProductUrl(url: string): boolean {
  if (!url.includes('/products/')) return false
  const last = url.split('/').pop() ?? ''
  return /-\d+$/.test(last)
}

export const ravensburgerSpec: HtmlSourceSpec = {
  discover: async (fetchText) => {
    const entries = await fetchSitemapEntries(SITEMAP, fetchText)
    return toItems(newestFirst(entries)
      .map((entry) => entry.loc)
      .filter(isProductUrl))
  },
}
