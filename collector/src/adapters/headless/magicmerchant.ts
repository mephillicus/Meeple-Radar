import type { HtmlSourceSpec } from '../html/crawl.js'
import { collectLinks } from '../html/links.js'

// Magic Merchant è dietro una Cloudflare "managed challenge": anche un browser reale
// (patchright + Chrome) resta sull'interstitziale, quindi la fonte resta `enabled: false`.
// Questo spec è pronto per quando/ se l'IP non verrà più sfidato.
const SEEDS = [
  'https://magicmerchant.it/catalogue/category/giochi-da-tavolo/prossime-uscite-113/',
  'https://magicmerchant.it/catalogue/category/giochi-da-tavolo/novita-88/',
]

const PRODUCT_URL = /^https:\/\/magicmerchant\.it\/catalogue\/(?!category\/)[a-z0-9][a-z0-9-]*-\d+\/$/

export const magicmerchantSpec: HtmlSourceSpec = {
  discover: (fetchText) => collectLinks(fetchText, SEEDS, (url) => PRODUCT_URL.test(url)),
}
