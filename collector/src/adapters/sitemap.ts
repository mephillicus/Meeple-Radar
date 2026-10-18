import type { TextFetcher } from './http.js'

export type SitemapEntry = { loc: string; lastmod?: string }

const ENTRY_RE = /<(url|sitemap)\b[^>]*>([\s\S]*?)<\/\1>/gi
const LOC_RE = /<loc>\s*([^<]*?)\s*<\/loc>/i
const LASTMOD_RE = /<lastmod>\s*([^<]*?)\s*<\/lastmod>/i

// Il reader Jina AI rende una sitemap come HTML: `<div><a href="URL">URL</a><br><time>LASTMOD</time></div>`.
const JINA_ENTRY_RE = /<a\s+[^>]*href="([^"]+)"[^>]*>[^<]*<\/a>(?:\s*<br\s*\/?>\s*<time>\s*([^<]*?)\s*<\/time>)?/gi
const JINA_INDEX_RE = /<title>\s*Sitemap Index\s*<\/title>/i

function isJinaHtml(text: string): boolean {
  return !/<loc>/.test(text) && /<a\s+[^>]*href="/.test(text)
}

function parseJinaEntries(html: string): SitemapEntry[] {
  const out: SitemapEntry[] = []
  for (const m of html.matchAll(JINA_ENTRY_RE)) {
    const loc = m[1].trim()
    if (!loc) continue
    const lastmod = m[2]?.trim()
    out.push(lastmod ? { loc, lastmod } : { loc })
  }
  return out
}

export function parseSitemapEntries(xml: string): SitemapEntry[] {
  if (isJinaHtml(xml)) return parseJinaEntries(xml)
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
  if (/<sitemapindex[\s>]/.test(xml)) return true
  return JINA_INDEX_RE.test(xml)
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
