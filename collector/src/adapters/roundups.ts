import * as cheerio from 'cheerio'
import { htmlToText } from '../text.js'
import type { SitemapEntry } from './sitemap.js'

// Fonte "roundups" di houseofgames.it: ogni mese la redazione pubblica un articolo-elenco
// "giochi da tavolo: tutte le nuove uscite di {mese}" a struttura regolare (publisher =
// p con solo strong, gioco = li senza attributi con strong "Titolo (data)", riassunto =
// paragrafi di solo testo fermati da link o immagini). Discovery dalla sitemap e parsing
// deterministico con cheerio, senza chiamate LLM: la struttura è fissa e verificabile, e un
// modello rischierebbe di inventare titoli, date o editori invece di estrarli. Qui entrano
// solo HTML già scaricato: il fetch delle pagine vive altrove.

export const ROUNDUP_FLOOR_DATE = '2026-08-01'

export type RoundupGame = { title: string; dateRaw?: string; publisher?: string; summary?: string }

const TITLE_DATE_RE = /^(.*?)\s*\(([^()]*)\)\s*$/u
const DATE_PUBLISHED_RE = /"datePublished"\s*:\s*"([^"]+)"/

export function isRoundupUrl(loc: string): boolean {
  let url: URL
  try {
    url = new URL(loc)
  } catch {
    return false
  }
  if (!url.hostname.endsWith('houseofgames.it')) return false
  return url.pathname.includes('giochi-da-tavolo-') && url.pathname.includes('nuove-uscite-di-')
}

export function filterRoundupEntries(entries: SitemapEntry[], floor: string = ROUNDUP_FLOOR_DATE): SitemapEntry[] {
  return entries.filter((entry) => {
    if (!isRoundupUrl(entry.loc)) return false
    if (entry.lastmod === undefined) return true
    return entry.lastmod.slice(0, 10) >= floor
  })
}

export function extractDatePublished(html: string): string | null {
  const match = html.match(DATE_PUBLISHED_RE)
  return match === null ? null : match[1].slice(0, 10)
}

export function parseRoundupArticle(html: string): RoundupGame[] {
  const $ = cheerio.load(html)
  const games: RoundupGame[] = []
  let publisher: string | undefined
  let current: RoundupGame | undefined
  let summaryParts: string[] = []

  const closeSummary = (): void => {
    if (current !== undefined && summaryParts.length > 0) {
      const piece = summaryParts.join(' ')
      current.summary = current.summary === undefined ? piece : `${current.summary} ${piece}`
    }
    summaryParts = []
  }

  for (const el of $('p, li').toArray()) {
    const node = $(el)
    if (el.name === 'p') {
      const kids = node.children()
      if (kids.length === 1 && kids.is('strong') && node.text().trim() === kids.text().trim()) {
        closeSummary()
        const text = kids.text().trim()
        if (text !== '') publisher = text
        current = undefined
        continue
      }
      if (node.find('a, img').length > 0) {
        closeSummary()
        continue
      }
      const piece = htmlToText(node.html() ?? undefined)
      if (piece !== null && current !== undefined) summaryParts.push(piece)
      continue
    }
    if (Object.keys(el.attribs).length > 0) continue
    const kids = node.children()
    if (kids.length !== 1 || !kids.is('strong')) continue
    closeSummary()
    const raw = kids.text()
    const match = raw.match(TITLE_DATE_RE)
    const game: RoundupGame = {
      title: match === null ? raw.trim() : match[1].trim(),
      dateRaw: match === null ? undefined : match[2].trim(),
      summary: undefined,
    }
    if (publisher !== undefined) game.publisher = publisher
    games.push(game)
    current = game
  }
  closeSummary()
  return games
}
