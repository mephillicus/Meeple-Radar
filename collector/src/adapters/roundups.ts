import * as cheerio from 'cheerio'
import { htmlToText } from '../text.js'
import { extractReleaseDate, normalizeTitle } from '../normalize.js'
import type { GameFinder, RawProduct, Source } from '../types.js'
import { textFetcher, type TextFetcher } from './http.js'
import { fetchSitemapEntries, newestFirst, type SitemapEntry } from './sitemap.js'

// Fonte "roundups" di houseofgames.it: ogni mese la redazione pubblica un articolo-elenco
// "giochi da tavolo: tutte le nuove uscite di {mese}" a struttura regolare (publisher =
// p con solo strong, gioco = li senza attributi con strong "Titolo (data)", riassunto =
// paragrafi di solo testo fermati da link o immagini). Discovery dalla sitemap, fetch delle
// pagine articolo con pacing e parsing deterministico con cheerio, senza chiamate LLM: la
// struttura è fissa e verificabile, e un modello rischierebbe di inventare titoli, date o
// editori invece di estrarli.

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

  const entryContent = $('.entry-content').first()
  const scope = entryContent.length > 0 ? entryContent : $('article, main').first()
  const elements = (scope.length > 0 ? scope.find('p, li') : $('p, li')).toArray()

  for (const el of elements) {
    const node = $(el)
    if (el.name === 'p') {
      const kids = node.children()
      if (kids.length === 1 && kids.is('strong') && node.text().trim() === kids.text().trim()) {
        if (Object.keys(el.attribs).length > 0) continue
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

export type RoundupOptions = {
  fetchText?: TextFetcher // default textFetcher()
  sleep?: (ms: number) => Promise<void> // default setTimeout, solo test lo inietta
}

const ARTICLE_DELAY_MS = 1000

const EXPLICIT_YEAR_RE = /\b20\d{2}\b/

function resolveRoundupReleaseDate(dateRaw: string, datePublished: string): string | null {
  if (EXPLICIT_YEAR_RE.test(dateRaw)) return extractReleaseDate(dateRaw)
  const pubYear = Number(datePublished.slice(0, 4))
  const pubMonth = Number(datePublished.slice(5, 7))
  if (!Number.isFinite(pubYear)) return null
  const iso = extractReleaseDate(`${dateRaw} ${pubYear}`)
  if (iso === null) return null
  const relMonth = Number(iso.slice(5, 7))
  if (pubMonth >= 10 && relMonth <= 3) {
    return extractReleaseDate(`${dateRaw} ${pubYear + 1}`)
  }
  return iso
}

function extractArticleSlug(loc: string): string {
  try {
    const segments = new URL(loc).pathname.split('/').filter(Boolean)
    return segments[segments.length - 1] ?? loc
  } catch {
    return loc
  }
}

export async function fetchRoundupProducts(
  source: Source,
  gameFinder?: GameFinder,
  options?: RoundupOptions,
): Promise<RawProduct[]> {
  const fetchText = options?.fetchText ?? textFetcher()
  const sleep = options?.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)))

  const entries = await fetchSitemapEntries(`${source.baseUrl}/post-sitemap.xml`, fetchText)
  if (entries.length === 0) throw new Error('sitemap roundups non raggiungibile')
  const candidates = newestFirst(filterRoundupEntries(entries))
  console.error(
    `[House of Games - articoli mensili] ${entries.length} voci sitemap, ${candidates.length} articoli mensili da leggere`,
  )

  const products: RawProduct[] = []
  for (const [i, entry] of candidates.entries()) {
    // Pacing tra i fetch articolo: mai prima del primo candidato.
    if (i > 0) await sleep(ARTICLE_DELAY_MS)
    const slug = extractArticleSlug(entry.loc)
    try {
      const html = await fetchText(entry.loc)
      if (html === null) {
        console.error(`[House of Games - articoli mensili] "${slug}": pagina non scaricata, articolo saltato`)
        continue
      }
      // Floor autorevole: anche un lastmod recente non salva un articolo vecchio.
      const datePublished = extractDatePublished(html)
      if (datePublished === null || datePublished < ROUNDUP_FLOOR_DATE) {
        console.error(`[House of Games - articoli mensili] "${slug}": data pubblicazione assente o pre-floor, articolo saltato`)
        continue
      }
      const games = parseRoundupArticle(html)
      if (games.length === 0) {
        console.error(`[House of Games - articoli mensili] "${slug}": nessun gioco trovato, articolo saltato`)
        continue
      }
      const mapped: RawProduct[] = []
      for (const g of games) {
        const product: RawProduct = {
          externalId: normalizeTitle(g.title).replace(/\s+/g, '-'),
          title: g.title,
          url: entry.loc,
        }
        if (g.dateRaw) {
          const iso = resolveRoundupReleaseDate(g.dateRaw, datePublished)
          if (iso !== null) product.releaseDateRaw = iso
        }
        if (g.summary) product.articleDescription = g.summary
        if (g.publisher) product.publisher = g.publisher
        // Come news: i dati di catalogo (il negozio) vincono sull'articolo. Un errore del
        // catalogo su un gioco non butta via gli altri prodotti già mappati.
        if (gameFinder) {
          try {
            const match = await gameFinder(normalizeTitle(g.title), g.publisher)
            if (match) {
              if (match.price !== null) product.priceRaw = String(match.price)
              if (match.currency !== null) product.currencyRaw = match.currency
              if (match.releaseDate !== null) product.releaseDateRaw = match.releaseDate
              if (match.imageUrl !== null) product.imageUrl = match.imageUrl
              if (match.gameId !== null) product.gameId = match.gameId
            }
          } catch (err) {
            const msg = err instanceof Error ? err.message : String(err)
            console.error(`[House of Games - articoli mensili] gameFinder: ${msg}`)
          }
        }
        mapped.push(product)
      }
      products.push(...mapped)
      console.error(`[House of Games - articoli mensili] "${slug}": ${games.length} uscite estratte`)
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err)
      console.error(`[House of Games - articoli mensili] "${slug}": ${msg}, articolo saltato`)
    }
  }

  // Dedup finale per titolo normalizzato: i candidati sono dal più recente, quindi
  // tenendo il primo vince l'articolo più nuovo (come news riga 490-491).
  const seen = new Set<string>()
  const deduped = products.filter((p) => {
    const key = normalizeTitle(p.title)
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
  console.error(`[House of Games - articoli mensili] totale ${deduped.length} uscite estratte`)
  return deduped
}
