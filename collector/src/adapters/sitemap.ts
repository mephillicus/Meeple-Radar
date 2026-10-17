import type { TextFetcher } from './http.js'

export type SitemapEntry = { loc: string; lastmod?: string }

const ENTRY_RE = /<(url|sitemap)\b[^>]*>([\s\S]*?)<\/\1>/gi
const LOC_RE = /<loc>\s*([^<]*?)\s*<\/loc>/i
const LASTMOD_RE = /<lastmod>\s*([^<]*?)\s*<\/lastmod>/i

export function parseSitemapEntries(xml: string): SitemapEntry[] {
  const out: SitemapEntry[] = []
  for (const m of xml.matchAll(ENTRY_RE)) {
    const block = m[2]
    const loc = block.match(LOC_RE)?.[1]
    if (!loc) continue
    const lastmod = block.match(LASTMOD_RE)?.[1]
    out.push(lastmod ? { loc, lastmod } : { loc })
  }
  return out
}

export function isSitemapIndex(xml: string): boolean {
  return /<sitemapindex[\s>]/.test(xml)
}

export function newestFirst(entries: SitemapEntry[]): SitemapEntry[] {
  const dated = entries.filter((e) => e.lastmod !== undefined)
  const undated = entries.filter((e) => e.lastmod === undefined)
  dated.sort((a, b) => {
    if (a.lastmod! < b.lastmod!) return 1
    if (a.lastmod! > b.lastmod!) return -1
    return 0
  })
  return [...dated, ...undated]
}

export async function fetchSitemapEntries(
  url: string,
  fetchText: TextFetcher,
  opts: { maxSitemaps?: number } = {},
): Promise<SitemapEntry[]> {
  const maxSitemaps = opts.maxSitemaps ?? 20
  const xml = await fetchText(url)
  if (!xml) return []
  if (!isSitemapIndex(xml)) return parseSitemapEntries(xml)

  const children = parseSitemapEntries(xml)
    .map((e) => e.loc)
    .slice(0, maxSitemaps)
  const out: SitemapEntry[] = []
  for (const child of children) {
    const childXml = await fetchText(child)
    if (!childXml) continue
    out.push(...parseSitemapEntries(childXml))
  }
  return out
}
