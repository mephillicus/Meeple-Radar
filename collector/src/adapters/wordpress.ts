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
const PAGE_DELAY_MS = 250
const PAGE_FAILURE_LIMIT = 5

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

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

async function fetchPageFields(link: string, http: HttpClient): Promise<PageFields | null> {
  // null = la pagina non e arrivata: e l'unico segnale che conta i fallimenti,
  // mentre {} e una pagina letta che non ha il blocco.
  try {
    const res = await http(link, { 'User-Agent': USER_AGENT })
    if (res.status !== 200) return null
    return parseGhenosProductPage(await res.text())
  } catch {
    return null
  }
}

function makePageReader(baseUrl: string, http: HttpClient): (link: string | undefined) => Promise<PageFields> {
  let failures = 0
  let enabled = true
  let requested = false
  return async (link) => {
    if (!link || !enabled) return {}
    if (requested) await sleep(PAGE_DELAY_MS)
    requested = true
    const fields = await fetchPageFields(link, http)
    if (fields === null) {
      failures += 1
      if (failures >= PAGE_FAILURE_LIMIT) {
        enabled = false
        console.error(`[wp] ${baseUrl}: ${PAGE_FAILURE_LIMIT} pagine prodotto fallite di fila, salto le altre`)
      }
      return {}
    }
    failures = 0
    return fields
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
  const readPage = makePageReader(base, http)
  for (let page = 1; ; page++) {
    const res = await http(`${base}/wp-json/wp/v2/${postType}?per_page=${PAGE_SIZE}&page=${page}`, {
      'User-Agent': USER_AGENT,
    })
    if (res.status !== 200) break
    const body = (await res.json()) as WpPost[] | null
    const posts = Array.isArray(body) ? body : []
    if (posts.length === 0) break
    for (const p of posts) {
      const fields = await readPage(p.link)
      const parsed = RawProductSchema.safeParse({
        externalId: String(p.id),
        title: decodeHtml(p.title?.rendered ?? ''),
        url: normalizePermalink(p.link ?? '', base, postType, permalinkBase),
        descriptionRaw: fields.descriptionRaw || p.content?.rendered,
        releaseDateRaw: fields.releaseDateRaw,
        publishedAtRaw: p.date,
      })
      if (parsed.success) out.push(parsed.data)
    }
    if (posts.length < PAGE_SIZE) break
  }
  return out
}
