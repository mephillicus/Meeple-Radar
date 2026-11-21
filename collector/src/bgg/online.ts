import { normalizeTitle } from '../normalize.js'
import { htmlToText } from '../text.js'
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
}

export function bggSearchUrl(query: string): string {
  return `https://boardgamegeek.com/search/boardgame?q=${encodeURIComponent(query)}`
}

export function buildSearchQuery(title: string): string {
  const withoutParens = title.replace(/\([^)]*\)/g, '').trim()
  return withoutParens || title
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
