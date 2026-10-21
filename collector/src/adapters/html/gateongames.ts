import * as cheerio from 'cheerio'
import type { HtmlSourceSpec } from './crawl.js'
import { collectLinks, toItems } from './links.js'
import { extractProduct } from './extract.js'

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
  extract(html, url) {
    const base = extractProduct(html, url)
    if (!base) return base
    const $ = cheerio.load(html)
    const block = $('div.e-col.ms-12.tr-7').first()
    if (block.length === 0) return base
    // Il testo e span soup da export Word, ma i paragrafi vanno presi fino al
    // secondo h2: oltre quello iniziano espansioni e componenti.
    const parts: string[] = []
    let headings = 0
    for (const el of block.find('h2, p').toArray()) {
      const node = $(el)
      if (node.is('h2')) {
        headings += 1
        if (headings > 1) break
        continue
      }
      const inner = node.html()
      if (inner) parts.push(`<p>${inner}</p>`)
    }
    const description = parts.join('')
    return description ? { ...base, descriptionRaw: description } : base
  },
}
