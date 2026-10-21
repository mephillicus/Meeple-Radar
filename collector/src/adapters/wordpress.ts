import * as cheerio from 'cheerio'
import { RawProductSchema, type RawProduct } from '../types.js'
import { decodeHtml, htmlToText } from '../text.js'
import { extractReleaseDate } from '../normalize.js'
import { defaultFetch, USER_AGENT, type HttpClient } from './http.js'

type WpPost = {
  id: number | string
  link?: string
  title?: { rendered?: string }
  content?: { rendered?: string }
  date?: string
}

const PAGE_SIZE = 100

function normalizePermalink(link: string, base: string, postType: string, permalinkBase?: string): string {
  if (!permalinkBase) return link
  const match = link.match(new RegExp(`[?&]${postType}=([^&]+)`))
  return match ? `${base}${permalinkBase}/${match[1]}/` : link
}

type PageFields = { descriptionRaw?: string; releaseDateRaw?: string }

export function parseGhenosProductPage(html: string): PageFields {
  const $ = cheerio.load(html)
  const block = $('.ae-element-custom-field').first()
  if (block.length === 0) return {}
  const paragraphs = block.find('p').get()
  if (paragraphs.length === 0) return {}
  const first = $(paragraphs[0]).text().trim()
  const out: PageFields = {}
  if (first.startsWith('Day one')) {
    const iso = extractReleaseDate(first)
    if (iso) out.releaseDateRaw = iso
  }
  const body = (first.startsWith('Day one') ? paragraphs.slice(1) : paragraphs)
    .map((el) => $(el).html() ?? '')
    .filter((s) => s !== '')
    .join(' ')
  const description = htmlToText(body) ?? undefined
  if (description) out.descriptionRaw = description
  return out
}

async function fetchPageFields(link: string | undefined, http: HttpClient): Promise<PageFields> {
  if (!link) return {}
  try {
    const res = await http(link, { 'User-Agent': USER_AGENT })
    if (res.status !== 200) return {}
    return parseGhenosProductPage(await res.text())
  } catch {
    return {}
  }
}

export async function fetchWordPressProducts(
  baseUrl: string,
  http: HttpClient = defaultFetch,
  postType = 'product',
  permalinkBase?: string,
): Promise<RawProduct[]> {
  const base = baseUrl.replace(/\/+$/, '')
  const out: RawProduct[] = []
  for (let page = 1; ; page++) {
    const res = await http(`${base}/wp-json/wp/v2/${postType}?per_page=${PAGE_SIZE}&page=${page}`, {
      'User-Agent': USER_AGENT,
    })
    if (res.status !== 200) break
    const body = (await res.json()) as WpPost[] | null
    const posts = Array.isArray(body) ? body : []
    if (posts.length === 0) break
    for (const p of posts) {
      const page = await fetchPageFields(p.link, http)
      const parsed = RawProductSchema.safeParse({
        externalId: String(p.id),
        title: decodeHtml(p.title?.rendered ?? ''),
        url: normalizePermalink(p.link ?? '', base, postType, permalinkBase),
        descriptionRaw: page.descriptionRaw || p.content?.rendered,
        releaseDateRaw: page.releaseDateRaw,
        publishedAtRaw: p.date,
      })
      if (parsed.success) out.push(parsed.data)
    }
    if (posts.length < PAGE_SIZE) break
  }
  return out
}
