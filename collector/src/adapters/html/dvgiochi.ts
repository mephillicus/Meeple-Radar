import * as cheerio from 'cheerio'
import type { DiscoveredProduct, HtmlSourceSpec } from './crawl.js'
import { buildProduct } from './extract.js'

const LISTING_URL = 'https://www.dvgiochi.com/prossime-uscite/'

const PRODUCT_URL = /^https:\/\/www\.dvgiochi\.com\/catalogo\/[a-z0-9-]+\/$/

const DESCRIPTION_BLOCK = 'div.col-sm-30.col-md-30.lp40.rp40.tm20[style*="font-size:1.3em"]'

// I mesi in italiano compaiono in maiuscolo all'inizio del testo dell'ancora ("27 OTTOBRE 2026").
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

// La data sta all'inizio del testo, seguita dalla descrizione: il pattern è ancorato.
const DATE_TEXT = new RegExp(`^(\\d{1,2})\\s+(${Object.keys(MONTHS).join('|')})\\s+(\\d{4})`)

function releaseDateFromText(text: string): string | undefined {
  const match = text.trim().match(DATE_TEXT)
  if (!match) return undefined
  return `${match[3]}-${MONTHS[match[2]]}-${match[1].padStart(2, '0')}`
}

function titleFrom($: cheerio.CheerioAPI): string {
  const h2 = $('h2').first()
  // L'<img> interno porta sempre il nome pulito; il primo nodo di testo conserva invece la categoria.
  const img = h2.find('img').first()
  const fromImage = img.attr('title')?.trim() || img.attr('alt')?.trim()
  if (fromImage) return fromImage
  return h2.contents().first().text().replace(/\s*\|\s*DV Games\s*$/i, '').trim()
}

// Il blocco contiene anche l'elenco dei componenti dentro .orange: resta parte
// della scheda ma non della descrizione, quindi va tolto prima di leggere il testo.
function descriptionFrom($: cheerio.CheerioAPI): string | undefined {
  const block = $(DESCRIPTION_BLOCK).first()
  if (block.length === 0) return undefined
  const copy = block.clone()
  copy.find('.orange').remove()
  return copy.html()?.trim() || undefined
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
    const $ = cheerio.load(html)
    return buildProduct({ title: titleFrom($), url, description: descriptionFrom($) })
  },
}
