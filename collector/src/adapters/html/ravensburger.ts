import type { HtmlSourceSpec } from './crawl.js'
import { extractProduct } from './extract.js'
import { fetchSitemapEntries, newestFirst } from '../sitemap.js'
import { toItems } from './links.js'

const SITEMAP = 'https://www.ravensburger.it/sitemap.xml'

const NON_BOARDGAME_RE = /(?:puzzle|gravitrax|brio|creart|tiptoi|treno|binari|ferrovia|memory|colorino|libro|libri|stencils)/i

// ravensburger.it è dietro Cloudflare: il fetch di Node riceve 403 ("Just a moment…"),
// quindi la fonte è `headless` (fetchText fornito dal fetcher dedicato: curl → reader Jina AI).
function isProductUrl(url: string): boolean {
  if (!url.includes('/products/games/')) return false
  if (url.includes('/games-for-kids/') || url.includes('/travel-games/')) return false
  if (NON_BOARDGAME_RE.test(url)) return false
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
  extract(html, url) {
    const product = extractProduct(html, url)
    if (!product) return null
    const stripped = product.title.replace(/^ravensburger\s+/i, '').trim()
    if (stripped) {
      product.title = stripped
    }
    return product
  },
}
