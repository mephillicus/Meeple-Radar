import * as cheerio from 'cheerio'
import { RawProductSchema, type RawProduct } from '../types.js'
import { decodeHtml, htmlToText } from '../text.js'
import { extractReleaseDate } from '../normalize.js'
import { defaultFetch, textFetcher, USER_AGENT, type HttpClient, type TextFetcher } from './http.js'
import { createMinIntervalLimiter } from './relay.js'

type WpPost = {
  id: number | string
  link?: string
  title?: { rendered?: string }
  content?: { rendered?: string }
  date?: string
}

export type WordPressFetchOptions = {
  concurrency?: number
  delayMs?: number
  sleep?: (ms: number) => Promise<void>
  maxRetries?: number
  baseDelayMs?: number
}

const PAGE_SIZE = 100
export const PAGE_DELAY_MS = 200
export const DEFAULT_CONCURRENCY = 4
const PAGE_FAILURE_LIMIT = 5

function normalizePermalink(link: string, base: string, postType: string, permalinkBase?: string): string {
  if (!permalinkBase) return link
  const match = link.match(new RegExp(`[?&]${postType}=([^&]+)`))
  return match ? `${base}${permalinkBase}/${match[1]}/` : link
}

type PageFields = {
  descriptionRaw?: string
  releaseDateRaw?: string
  priceRaw?: string
  currencyRaw?: string
  availabilityRaw?: string
}

export function parseGhenosProductPage(html: string): PageFields {
  const $ = cheerio.load(html)
  const out: PageFields = {}
  // Prezzo e disponibilità stanno nei meta OG che WooCommerce/Yoast scrive in
  // <head>, fuori dal blocco descrizione: vanno letti prima dell'early return.
  // Il preordine non porta il meta product:availability: senza di esso l'app
  // marca comunque "In preordine" grazie alla data futura.
  const meta = (property: string): string | undefined =>
    $(`meta[property="${property}"]`).first().attr('content')?.trim() || undefined
  const priceRaw = meta('product:price:amount')
  if (priceRaw) out.priceRaw = priceRaw
  const currencyRaw = meta('product:price:currency')
  if (currencyRaw) out.currencyRaw = currencyRaw
  const availabilityRaw = meta('product:availability')
  if (availabilityRaw) out.availabilityRaw = availabilityRaw

  const block = $('.ae-element-custom-field').first()
  if (block.length === 0) return out
  const paragraphs = block.find('p').get()
  if (paragraphs.length === 0) return out
  const first = $(paragraphs[0]).text().trim()
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

async function fetchPageFields(link: string, fetchText: TextFetcher): Promise<PageFields | null> {
  // null = la pagina non e arrivata: e l'unico segnale che conta i fallimenti,
  // mentre {} e una pagina letta che non ha il blocco.
  try {
    const html = await fetchText(link)
    if (html === null) return null
    return parseGhenosProductPage(html)
  } catch {
    return null
  }
}

function makePageReader(
  baseUrl: string,
  fetchText: TextFetcher,
  acquireSlot: () => Promise<void>,
): (link: string | undefined) => Promise<PageFields> {
  let failures = 0
  let enabled = true
  let inFlight = 0
  const waiters = new Set<() => void>()

  const notifyWaiters = () => {
    for (const notify of waiters) notify()
    waiters.clear()
  }

  return async (link) => {
    if (!link || !enabled) return {}
    while (enabled && failures > 0 && failures + inFlight >= PAGE_FAILURE_LIMIT) {
      await new Promise<void>((resolve) => {
        waiters.add(resolve)
      })
    }
    if (!enabled) return {}

    inFlight += 1
    try {
      await acquireSlot()
      if (!enabled) return {}
      const fields = await fetchPageFields(link, fetchText)
      if (fields === null) {
        failures += 1
        if (failures >= PAGE_FAILURE_LIMIT && enabled) {
          enabled = false
          console.error(`[wp] ${baseUrl}: ${PAGE_FAILURE_LIMIT} pagine prodotto fallite di fila, salto le altre`)
        }
        return {}
      }
      failures = 0
      return fields
    } finally {
      inFlight -= 1
      notifyWaiters()
    }
  }
}

export async function fetchWordPressProducts(
  baseUrl: string,
  http: HttpClient = defaultFetch,
  postType = 'product',
  permalinkBase?: string,
  opts?: WordPressFetchOptions,
): Promise<RawProduct[]> {
  const base = baseUrl.replace(/\/+$/, '')
  const out: RawProduct[] = []
  const concurrency = Math.max(1, opts?.concurrency ?? DEFAULT_CONCURRENCY)
  const delayMs = opts?.delayMs ?? PAGE_DELAY_MS
  const fetchText = textFetcher(http, {
    sleep: opts?.sleep,
    maxRetries: opts?.maxRetries,
    baseDelayMs: opts?.baseDelayMs,
  })
  const acquireSlot = createMinIntervalLimiter({
    minIntervalMs: delayMs,
    sleep: opts?.sleep,
  })
  const readPage = makePageReader(base, fetchText, acquireSlot)

  for (let page = 1; ; page++) {
    const res = await http(`${base}/wp-json/wp/v2/${postType}?per_page=${PAGE_SIZE}&page=${page}`, {
      'User-Agent': USER_AGENT,
    })
    if (res.status !== 200) break
    const body = (await res.json()) as WpPost[] | null
    const posts = Array.isArray(body) ? body : []
    if (posts.length === 0) break

    const fieldsByIndex: PageFields[] = new Array(posts.length).fill({})
    let next = 0
    const workers = Array.from({ length: Math.min(concurrency, posts.length) }, async () => {
      for (;;) {
        const i = next++
        if (i >= posts.length) return
        fieldsByIndex[i] = await readPage(posts[i].link)
      }
    })
    await Promise.all(workers)

    for (let i = 0; i < posts.length; i++) {
      const p = posts[i]
      const fields = fieldsByIndex[i]
      const parsed = RawProductSchema.safeParse({
        externalId: String(p.id),
        title: decodeHtml(p.title?.rendered ?? ''),
        url: normalizePermalink(p.link ?? '', base, postType, permalinkBase),
        descriptionRaw: fields.descriptionRaw || p.content?.rendered,
        releaseDateRaw: fields.releaseDateRaw,
        publishedAtRaw: p.date,
        priceRaw: fields.priceRaw,
        currencyRaw: fields.currencyRaw,
        availabilityRaw: fields.availabilityRaw,
      })
      if (parsed.success) out.push(parsed.data)
    }
    if (posts.length < PAGE_SIZE) break
  }
  return out
}
