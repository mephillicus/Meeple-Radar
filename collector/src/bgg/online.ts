import { normalizeTitle } from '../normalize.js'
import { decodeHtml, htmlToText } from '../text.js'
import { fetchBggXml, parseExpandsBggId, type BggFetchOptions } from './collection.js'
import { normalizeForBggMatch } from './normalize-bgg.js'

export type GeekItem = {
  bggId: number
  name: string
  alternateNames: string[]
  subtype: string
  yearpublished: number | null
  imageUrl?: string | null
  description?: string | null
  expandsBggId?: number | null
  bggRating?: number | null
  bggRank?: number | null
}

const TRAILING_IT_EDITION_RE =
  /(?:\s*[-–—/:,]\s*|\s+)(?:edizione italiana|ed\.?\s*italiana|versione italiana|lingua italiana|ed\.?\s*it\.?|italiano|italiana|ita)$/i

export function bggSearchUrl(query: string): string {
  return `https://boardgamegeek.com/search/boardgame?q=${encodeURIComponent(query)}`
}

export function buildSearchQuery(title: string): string {
  let cleaned = title.replace(/\([^)]*\)/g, ' ').replace(/\s+/g, ' ').trim()
  while (cleaned.length > 0) {
    const next = cleaned.replace(TRAILING_IT_EDITION_RE, '').trim()
    if (next === cleaned || next.length === 0) break
    cleaned = next
  }
  const withoutBrand = cleaned.replace(/^ravensburger(?:\s*[-–—/:,]\s*|\s+)/i, '').trim()
  if (withoutBrand.length > 0) {
    cleaned = withoutBrand
  }
  return cleaned || title
}

export function parseSearchIds(html: string): number[] {
  const regex = /\/boardgame(?:expansion)?\/(\d+)\//g
  const ids: number[] = []
  let match: RegExpExecArray | null
  while ((match = regex.exec(html)) !== null) {
    const id = Number(match[1])
    if (!ids.includes(id)) ids.push(id)
  }
  return ids
}

function extractImageUrl(item: Record<string, unknown>): string | null {
  const images =
    item.images !== null && typeof item.images === 'object'
      ? (item.images as Record<string, unknown>)
      : undefined
  const candidates = [
    images?.original,
    images?.mediacard,
    item.imageurl_lg,
    item.imageurl,
    images?.previewthumb,
    images?.square200,
  ]
  for (const c of candidates) {
    if (typeof c === 'string' && c.trim().length > 0) return c.trim()
  }
  return null
}

function cleanDescription(raw: unknown): string | null {
  if (typeof raw !== 'string' || raw.trim() === '') return null
  const normalizedEntities = raw
    .replace(/&apos;/gi, "'")
    .replace(/&rsquo;/gi, "'")
    .replace(/&lsquo;/gi, "'")
    .replace(/&rdquo;/gi, '"')
    .replace(/&ldquo;/gi, '"')
    .replace(/&ndash;/gi, '–')
    .replace(/&mdash;/gi, '—')
    .replace(/&hellip;/gi, '...')
  return htmlToText(normalizedEntities)
}

function extractExpandsBggId(
  item: Record<string, unknown>,
  parsed: Record<string, unknown>,
): number | null {
  for (const container of [item.links, parsed.links]) {
    if (!container || typeof container !== 'object') continue
    const expands = (container as Record<string, unknown>).expandsboardgame
    if (Array.isArray(expands)) {
      for (const entry of expands) {
        if (!entry || typeof entry !== 'object') continue
        const rawId =
          (entry as Record<string, unknown>).objectid ??
          (entry as Record<string, unknown>).id
        const id = Number(rawId)
        if (Number.isInteger(id) && id > 0) return id
      }
    }
  }
  return null
}

export function parseGeekItem(json: string, bggId: number): GeekItem | null {
  try {
    const parsed = JSON.parse(json)
    if (!parsed || typeof parsed !== 'object' || !parsed.item) return null
    const item = parsed.item as Record<string, unknown>
    const alternateNames = new Set<string>()
    for (const names of [item.alternatenames, parsed.alternatenames]) {
      if (Array.isArray(names)) {
        for (const alt of names) {
          if (alt && typeof alt.name === 'string' && alt.name.length > 0) {
            alternateNames.add(alt.name)
          }
        }
      }
    }
    const imageUrl = extractImageUrl(item)
    const description =
      cleanDescription(item.description) ?? cleanDescription(item.short_description)
    const expandsBggId = extractExpandsBggId(item, parsed as Record<string, unknown>)

    return {
      bggId,
      name: item.name as string,
      alternateNames: [...alternateNames],
      subtype: item.subtype as string,
      yearpublished: (item.yearpublished as number | null | undefined) ?? null,
      imageUrl,
      description,
      expandsBggId,
    }
  } catch {
    return null
  }
}

export function resolveGate(
  title: string,
  releaseDate: string | null,
  items: GeekItem[],
): GeekItem | null {
  const normalized = normalizeTitle(title)
  let candidates = items.filter(
    (item) =>
      normalizeTitle(item.name) === normalized ||
      item.alternateNames.some((alt) => normalizeTitle(alt) === normalized),
  )

  if (candidates.length === 0) {
    const bggNormalized = normalizeForBggMatch(title)
    candidates = items.filter(
      (item) =>
        normalizeForBggMatch(item.name) === bggNormalized ||
        item.alternateNames.some((alt) => normalizeForBggMatch(alt) === bggNormalized),
    )
  }

  if (candidates.length === 0) return null
  if (candidates.length === 1) return candidates[0]

  if (releaseDate === null) return null
  const productYear = Number(releaseDate.slice(0, 4))
  if (!Number.isFinite(productYear)) return null

  const winners = candidates.filter(
    (item) => item.yearpublished !== null && Math.abs(item.yearpublished - productYear) <= 1,
  )

  if (winners.length === 1) return winners[0]
  return null
}

function decodeXmlText(raw: string): string {
  return decodeHtml(raw.replace(/&apos;/gi, "'"))
}

export function bggXmlSearchUrl(query: string): string {
  return `https://boardgamegeek.com/xmlapi2/search?query=${encodeURIComponent(query)}&type=boardgame,boardgameexpansion`
}

export function parseXmlSearchIds(xml: string): number[] {
  const seen = new Set<number>()
  const ids: number[] = []
  const ITEM_OPEN_RE = /<item\b([^>]*)>/gi
  const TYPE_RE = /\btype="(boardgame|boardgameexpansion)"/i
  const ID_RE = /\bid="(\d+)"/i
  for (const m of xml.matchAll(ITEM_OPEN_RE)) {
    const attrs = m[1]
    if (!TYPE_RE.test(attrs)) continue
    const idMatch = attrs.match(ID_RE)
    if (!idMatch) continue
    const id = Number(idMatch[1])
    if (!Number.isInteger(id) || id <= 0 || seen.has(id)) continue
    seen.add(id)
    ids.push(id)
  }
  return ids
}

export function bggXmlThingUrl(ids: number[]): string {
  return `https://boardgamegeek.com/xmlapi2/thing?id=${ids.join(',')}&type=boardgame,boardgameexpansion&stats=1`
}

export function parseXmlThingItems(xml: string): GeekItem[] {
  const items: GeekItem[] = []
  const ITEM_BLOCK_RE = /<item\b([^>]*)>([\s\S]*?)<\/item>/gi
  const NAME_TAG_RE = /<name\b([^>]*)>/gi
  const RANK_TAG_RE = /<rank\b([^>]*)>/gi

  for (const m of xml.matchAll(ITEM_BLOCK_RE)) {
    const attrs = m[1]
    const block = m[2]

    const idMatch = attrs.match(/\bid="(\d+)"/i)
    if (!idMatch) continue
    const bggId = Number(idMatch[1])
    if (!Number.isInteger(bggId) || bggId <= 0) continue

    const subtype = attrs.match(/\btype="([^"]*)"/i)?.[1] ?? ''

    let primaryName = ''
    const alternateNames: string[] = []
    const seenAlt = new Set<string>()
    for (const nm of block.matchAll(NAME_TAG_RE)) {
      const nameAttrs = nm[1]
      const typeMatch = nameAttrs.match(/\btype="(primary|alternate)"/i)
      const valMatch = nameAttrs.match(/\bvalue="([^"]*)"/i)
      if (!typeMatch || !valMatch) continue
      const decoded = decodeXmlText(valMatch[1]).trim()
      if (!decoded) continue
      if (typeMatch[1].toLowerCase() === 'primary') {
        if (!primaryName) primaryName = decoded
      } else if (!seenAlt.has(decoded)) {
        seenAlt.add(decoded)
        alternateNames.push(decoded)
      }
    }

    const yearMatch = block.match(/<yearpublished\b[^>]*\bvalue="(-?\d+)"/i)?.[1]
    const yearNum = yearMatch !== undefined ? Number(yearMatch) : null
    const yearpublished =
      yearNum !== null && Number.isFinite(yearNum) && yearNum !== 0 ? yearNum : null

    const rawImage =
      block.match(/<image>([\s\S]*?)<\/image>/i)?.[1] ??
      block.match(/<thumbnail>([\s\S]*?)<\/thumbnail>/i)?.[1]
    const imageUrl = rawImage ? decodeXmlText(rawImage).trim() || null : null

    const rawDesc = block.match(/<description>([\s\S]*?)<\/description>/i)?.[1]
    const description = rawDesc ? cleanDescription(decodeXmlText(rawDesc)) : null

    const expandsBggId = parseExpandsBggId(block)

    const avgMatch = block.match(/<average\b[^>]*\bvalue="([^"]*)"/i)?.[1]
    const avgNum = avgMatch !== undefined ? Number(avgMatch) : NaN
    const bggRating = Number.isFinite(avgNum) && avgNum > 0 ? avgNum : null

    let bggRank: number | null = null
    for (const rm of block.matchAll(RANK_TAG_RE)) {
      const rankAttrs = rm[1]
      if (!/\btype="subtype"/i.test(rankAttrs) || !/\bname="boardgame"/i.test(rankAttrs)) continue
      const valMatch = rankAttrs.match(/\bvalue="([^"]*)"/i)
      if (!valMatch) continue
      const rankNum = Number(valMatch[1])
      if (Number.isInteger(rankNum) && rankNum > 0) {
        bggRank = rankNum
        break
      }
    }

    items.push({
      bggId,
      name: primaryName,
      alternateNames,
      subtype,
      yearpublished,
      imageUrl,
      description,
      expandsBggId,
      bggRating,
      bggRank,
    })
  }

  return items
}

export function createBggXmlOnlineClient(opts: BggFetchOptions = {}): {
  searchIds: (query: string) => Promise<number[] | null>
  fetchGeekItems: (ids: number[]) => Promise<GeekItem[] | null>
} {
  return {
    async searchIds(query: string): Promise<number[] | null> {
      const res = await fetchBggXml(bggXmlSearchUrl(query), opts)
      if (!res.ok) return null
      return parseXmlSearchIds(res.xml)
    },
    async fetchGeekItems(ids: number[]): Promise<GeekItem[] | null> {
      const validIds = ids.filter((id) => Number.isInteger(id) && id > 0)
      if (validIds.length === 0) return []
      const res = await fetchBggXml(bggXmlThingUrl(validIds), opts)
      if (!res.ok) return null
      return parseXmlThingItems(res.xml)
    },
  }
}
