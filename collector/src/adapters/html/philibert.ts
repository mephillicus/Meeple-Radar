import type { HtmlSourceSpec } from './crawl.js'
import { collectLinks, toItems } from './links.js'

// Il negozio IT (philibertnet.com/it/…) risponde 500; la versione EN è raggiungibile
// e in EUR, quindi si usa quella.
const SEEDS = [
  'https://www.philibertnet.com/en/new-products',
  'https://www.philibertnet.com/en/18622-coming-soon',
  'https://www.philibertnet.com/en/14959-pre-orders-kickstarter',
]

const PRODUCT_URL = /^https:\/\/www\.philibertnet\.com\/en\/[a-z0-9-]+\/\d+-[^/]+\.html$/

export const philibertSpec: HtmlSourceSpec = {
  discover: async (fetchText) => toItems(await collectLinks(fetchText, SEEDS, (url) => PRODUCT_URL.test(url))),
}
