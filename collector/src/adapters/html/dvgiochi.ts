import * as cheerio from 'cheerio'
import type { DiscoveredProduct, HtmlSourceSpec } from './crawl.js'
import { buildProduct } from './extract.js'

const LISTING_URL = 'https://www.dvgiochi.com/prossime-uscite/'

const PRODUCT_URL = /^https:\/\/www\.dvgiochi\.com\/catalogo\/[a-z0-9-]+\/$/

const DATE_TEXT = /(\d{1,2})\s+([A-Za-z]+)\s+(\d{4})/

// I mesi in italiano compaiono in maiuscolo nel testo delle ancore ("27 OTTOBRE 2026").
const MONTHS: Record<string, string> = {
  GENNAIO: '01',
  FEBBRAIO: '02',
  MARZO: '03',
  APRILE: '04',
  MAGGIO: '05',
  GIUGNO: '06',
  LUGLIO: '07',
  AGOSTO: '08',
  SETTEMBRE: '09',
  OTTOBRE: '10',
  NOVEMBRE: '11',
  DICEMBRE: '12',
}

function releaseDateFromText(text: string): string | undefined {
  const match = text.match(DATE_TEXT)
  if (!match) return undefined
  const month = MONTHS[match[2].toUpperCase()]
  if (!month) return undefined
  return `${match[3]}-${month}-${match[1].padStart(2, '0')}`
}

function titleFromHtml(html: string): string {
  const $ = cheerio.load(html)
  // Il primo nodo di testo dell'<h2> porta il titolo: il suffisso e l'<img> figlio vanno esclusi.
  return $('h2').first().contents().first().text().replace(/\s*\|\s*DV Games\s*$/i, '').trim()
}

export const dvgiochiSpec: HtmlSourceSpec = {
  async discover(fetchText) {
    const html = await fetchText(LISTING_URL)
    if (!html) return []
    const $ = cheerio.load(html)
    const out: DiscoveredProduct[] = []
    const seen = new Set<string>()
    for (const el of $('a[href]').toArray()) {
      const href = $(el).attr('href')
      if (!href) continue
      let url: string
      try {
        url = new URL(href, LISTING_URL).toString()
      } catch {
        continue
      }
      if (!PRODUCT_URL.test(url) || seen.has(url)) continue
      seen.add(url)
      const releaseDate = releaseDateFromText($(el).text())
      out.push(releaseDate === undefined ? { url } : { url, releaseDate })
    }
    return out
  },
  extract(html, url) {
    return buildProduct({ title: titleFromHtml(html), url })
  },
}
