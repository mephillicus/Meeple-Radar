import { RawProductSchema, type RawProduct } from '../types.js'
import { defaultFetch, USER_AGENT, type HttpClient } from './http.js'

type WpPost = {
  id: number | string
  link?: string
  title?: { rendered?: string }
  content?: { rendered?: string }
  date?: string
}

const PAGE_SIZE = 100

export function decodeHtml(s: string): string {
  return s
    .replace(/&#x([0-9a-fA-F]+);/g, (_, h) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(Number(n)))
    .replace(/&nbsp;/g, ' ')
    .replace(/&quot;/g, '"')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
}

export async function fetchWordPressProducts(
  baseUrl: string,
  http: HttpClient = defaultFetch,
): Promise<RawProduct[]> {
  const base = baseUrl.replace(/\/+$/, '')
  const out: RawProduct[] = []
  for (let page = 1; ; page++) {
    const res = await http(`${base}/wp-json/wp/v2/product?per_page=${PAGE_SIZE}&page=${page}`, {
      'User-Agent': USER_AGENT,
    })
    if (res.status !== 200) break
    const body = (await res.json()) as WpPost[] | null
    const posts = Array.isArray(body) ? body : []
    if (posts.length === 0) break
    for (const p of posts) {
      const parsed = RawProductSchema.safeParse({
        externalId: String(p.id),
        title: decodeHtml(p.title?.rendered ?? ''),
        url: p.link ?? '',
        descriptionRaw: p.content?.rendered,
        publishedAtRaw: p.date,
      })
      if (parsed.success) out.push(parsed.data)
    }
    if (posts.length < PAGE_SIZE) break
  }
  return out
}
