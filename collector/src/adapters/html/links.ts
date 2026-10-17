import * as cheerio from 'cheerio'
import type { TextFetcher } from '../http.js'

export async function collectLinks(
  fetchText: TextFetcher,
  seedUrls: string[],
  matches: (url: string) => boolean,
): Promise<string[]> {
  const out: string[] = []
  const seen = new Set<string>()
  for (const seed of seedUrls) {
    const html = await fetchText(seed)
    if (!html) continue
    const $ = cheerio.load(html)
    for (const el of $('a[href]').toArray()) {
      const href = $(el).attr('href')
      if (!href) continue
      let url: string
      try {
        url = new URL(href, seed).toString().split('#')[0]
      } catch {
        continue
      }
      if (!matches(url)) continue
      if (seen.has(url)) continue
      seen.add(url)
      out.push(url)
    }
  }
  return out
}
