import { normalizeTitle } from '../normalize.js'

export type GeekItem = {
  bggId: number
  name: string
  alternateNames: string[]
  subtype: string
  yearpublished: number | null
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

export function parseGeekItem(json: string, bggId: number): GeekItem | null {
  try {
    const parsed = JSON.parse(json)
    if (!parsed || typeof parsed !== 'object' || !parsed.item) return null
    const item = parsed.item
    const alternateNames: string[] = []
    if (Array.isArray(item.alternatenames)) {
      for (const alt of item.alternatenames) {
        if (alt && typeof alt.name === 'string' && alt.name.length > 0) {
          alternateNames.push(alt.name)
        }
      }
    }
    return {
      bggId,
      name: item.name,
      alternateNames,
      subtype: item.subtype,
      yearpublished: item.yearpublished ?? null,
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
  const candidates = items.filter(
    (item) =>
      normalizeTitle(item.name) === normalized ||
      item.alternateNames.some((alt) => normalizeTitle(alt) === normalized),
  )

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
