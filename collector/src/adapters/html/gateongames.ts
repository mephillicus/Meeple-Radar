import type { HtmlSourceSpec } from './crawl.js'
import { collectLinks, toItems } from './links.js'

const SEEDS = [
  'https://www.gateongames.com/prodotti/localizzazioni/',
  'https://www.gateongames.com/prodotti/originali-gog/',
]

const PRODUCT_URL = /^https:\/\/www\.gateongames\.com\/([a-z0-9][a-z0-9-]*)\/$/

// Il filtro a slug unico corrisponde anche a pagine normali del sito: si escludono quelle note.
const NON_PRODOTTI = new Set(['chi-siamo','contattaci','privacy-policy','corti-in-gioco','english-catalogue','rulebook','speciali-mini-crimes','homepage'])

export const gateongamesSpec: HtmlSourceSpec = {
  discover: async (fetchText) =>
    toItems(await collectLinks(fetchText, SEEDS, (url) => {
      const m = PRODUCT_URL.exec(url)
      return m !== null && !NON_PRODOTTI.has(m[1])
    })),
}
