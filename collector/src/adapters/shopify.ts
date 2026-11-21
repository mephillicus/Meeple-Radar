import { RawProductSchema, type RawProduct } from '../types.js'
import { defaultFetch, textFetcher, type HttpClient, type TextFetcher } from './http.js'
import { createMinIntervalLimiter } from './relay.js'
import { extractReleaseDate } from '../normalize.js'
import * as cheerio from 'cheerio'

const PREORDER_AVAILABILITY = 'preordine'
export const DEFAULT_HTML_DELAY_MS = 250
export const PREORDER_SIGNAL_RE = /pre-?order|preordin|uscita|day[\s-]?one|arrivo/i

type ShopifyProduct = {
  id: number | string
  title?: string
  handle?: string
  body_html?: string
  tags?: string[] | string
  published_at?: string
  images?: { src?: string }[]
  variants?: { price?: string; available?: boolean }[]
}

export type ShopifyFetchOptions = {
  htmlDelayMs?: number
  sleep?: (ms: number) => Promise<void>
  maxRetries?: number
  baseDelayMs?: number
}

const PAGE_SIZE = 250
const USCITA_TAG_RE = /^uscita\s*:\s*(\d{1,4})[-\/](\d{1,2})[-\/](\d{1,4})$/i

function toValidIsoDate(year: number, month: number, day: number): string | undefined {
  if (month < 1 || month > 12 || day < 1 || day > 31) return undefined
  const d = new Date(Date.UTC(year, month - 1, day))
  if (
    d.getUTCFullYear() !== year ||
    d.getUTCMonth() + 1 !== month ||
    d.getUTCDate() !== day
  ) {
    return undefined
  }
  return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`
}

export function extractReleaseDateFromTags(tags?: string[] | string): string | undefined {
  if (!tags) return undefined
  const list = (Array.isArray(tags) ? tags : tags.split(','))
    .map((t) => t.trim())
    .filter(Boolean)

  for (const tag of list) {
    const m = USCITA_TAG_RE.exec(tag)
    if (!m) continue
    const [, p1, p2, p3] = m
    if (p1.length === 4) {
      const iso = toValidIsoDate(Number(p1), Number(p2), Number(p3))
      if (iso) return iso
    } else if (p3.length === 4) {
      const a = Number(p1)
      const b = Number(p2)
      const year = Number(p3)
      const [month, day] = a > 12 && b <= 12 ? [b, a] : [a, b]
      const iso = toValidIsoDate(year, month, day)
      if (iso) return iso
    }
  }
  return undefined
}

function isFutureIso(iso: string, now: Date): boolean {
  const d = new Date(`${iso}T00:00:00Z`)
  return d > now
}

export function hasPreorderSignal(product: Pick<ShopifyProduct, 'title' | 'body_html' | 'tags'>): boolean {
  const tagsText = Array.isArray(product.tags) ? product.tags.join(' ') : (product.tags ?? '')
  return (
    PREORDER_SIGNAL_RE.test(product.title ?? '') ||
    PREORDER_SIGNAL_RE.test(product.body_html ?? '') ||
    PREORDER_SIGNAL_RE.test(tagsText)
  )
}

export async function extractDateFromHtml(
  url: string,
  fetchText: TextFetcher,
  acquireHtmlSlot: () => Promise<void>,
): Promise<string | undefined> {
  try {
    await acquireHtmlSlot()
    const html = await fetchText(url)
    if (!html) return undefined
    const $ = cheerio.load(html)
    $('script, style, noscript').remove()
    return extractReleaseDate($.text()) ?? undefined
  } catch {
    return undefined
  }
}

export async function fetchShopifyProducts(
  baseUrl: string,
  http: HttpClient = defaultFetch,
  collection?: string,
  now: Date = new Date(),
  opts?: ShopifyFetchOptions,
): Promise<RawProduct[]> {
  const base = baseUrl.replace(/\/+$/, '')
  const isPreordini = collection === 'preordini'
  const path = collection
    ? `/collections/${encodeURIComponent(collection)}/products.json`
    : '/products.json'

  const retryOpts = {
    sleep: opts?.sleep,
    maxRetries: opts?.maxRetries,
    baseDelayMs: opts?.baseDelayMs,
  }
  const fetchHtmlText = textFetcher(http, retryOpts)
  const fetchJsonText = textFetcher(
    async (url, headers) => {
      const res = await http(url, headers)
      return {
        status: res.status,
        headers: res.headers,
        json: () => res.json(),
        text: async () => {
          if (typeof res.text === 'function') {
            const text = await res.text()
            if (text) return text
          }
          if (typeof res.json === 'function') {
            const json = await res.json()
            return json === undefined ? '' : JSON.stringify(json)
          }
          return ''
        },
      }
    },
    retryOpts,
  )
  const htmlDelayMs = opts?.htmlDelayMs ?? DEFAULT_HTML_DELAY_MS
  const acquireHtmlSlot = createMinIntervalLimiter({
    minIntervalMs: htmlDelayMs,
    sleep: opts?.sleep,
  })

  const out: RawProduct[] = []
  for (let page = 1; ; page++) {
    const rawJson = await fetchJsonText(`${base}${path}?limit=${PAGE_SIZE}&page=${page}`)
    if (!rawJson) break
    let body: { products?: ShopifyProduct[] } | null = null
    try {
      body = JSON.parse(rawJson) as { products?: ShopifyProduct[] } | null
    } catch {
      break
    }
    const products = Array.isArray(body?.products) ? body!.products! : []
    if (products.length === 0) break
    for (const p of products) {
      const variant = p.variants?.[0]
      const available = variant?.available

      let releaseDateRaw =
        extractReleaseDateFromTags(p.tags) ?? extractReleaseDate(p.body_html ?? undefined) ?? undefined

      if (!releaseDateRaw && (isPreordini || (available !== false && hasPreorderSignal(p)))) {
        const handle = p.handle ?? ''
        if (handle) {
          const htmlDate = await extractDateFromHtml(
            `${base}/products/${handle}`,
            fetchHtmlText,
            acquireHtmlSlot,
          )
          if (htmlDate) releaseDateRaw = htmlDate
        }
      }

      let availabilityRaw: string | undefined
      if (available === false) {
        availabilityRaw = 'false'
      } else if (releaseDateRaw && isFutureIso(releaseDateRaw, now)) {
        availabilityRaw = PREORDER_AVAILABILITY
      } else {
        availabilityRaw = available === undefined ? undefined : String(available)
      }

      const parsed = RawProductSchema.safeParse({
        externalId: String(p.id),
        title: p.title ?? '',
        url: `${base}/products/${p.handle ?? ''}`,
        priceRaw: variant?.price,
        currencyRaw: 'EUR',
        availabilityRaw,
        descriptionRaw: p.body_html,
        imageUrl: p.images?.[0]?.src,
        publishedAtRaw: p.published_at,
        releaseDateRaw,
      })
      if (parsed.success) out.push(parsed.data)
    }
    if (products.length < PAGE_SIZE) break
  }
  return out
}
