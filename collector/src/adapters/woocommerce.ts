import { RawProductSchema, type RawProduct } from '../types.js'
import { extractReleaseDate } from '../normalize.js'
import { htmlToText } from '../text.js'
import { defaultFetch, textFetcher, type HttpClient } from './http.js'

type StoreTerm = {
  name?: string
  slug?: string
}

type StoreProduct = {
  id: number | string
  name?: string
  permalink?: string
  description?: string
  short_description?: string
  is_in_stock?: boolean
  is_on_backorder?: boolean
  add_to_cart?: { text?: string }
  categories?: StoreTerm[]
  tags?: StoreTerm[]
  images?: { src?: string }[]
  prices?: { price?: string; currency_code?: string; currency_minor_unit?: number }
}

export type WooCommerceFetchOptions = {
  sleep?: (ms: number) => Promise<void>
  maxRetries?: number
  baseDelayMs?: number
}

const PAGE_SIZE = 100
const PREORDER_RE = /pre-?order|preordin/i

function isPreorderProduct(p: StoreProduct): boolean {
  if (p.is_on_backorder === true) return true
  if (PREORDER_RE.test(p.add_to_cart?.text ?? '')) return true
  const terms = [...(p.categories ?? []), ...(p.tags ?? [])]
  return terms.some((t) => PREORDER_RE.test(t.name ?? '') || PREORDER_RE.test(t.slug ?? ''))
}

export async function fetchWooCommerceProducts(
  baseUrl: string,
  currency: string,
  http: HttpClient = defaultFetch,
  opts?: WooCommerceFetchOptions,
): Promise<RawProduct[]> {
  const base = baseUrl.replace(/\/+$/, '')
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
    {
      sleep: opts?.sleep,
      maxRetries: opts?.maxRetries,
      baseDelayMs: opts?.baseDelayMs,
    },
  )

  const out: RawProduct[] = []
  for (let page = 1; ; page++) {
    const rawJson = await fetchJsonText(
      `${base}/wp-json/wc/store/v1/products?per_page=${PAGE_SIZE}&page=${page}`,
    )
    if (!rawJson) break
    let body: StoreProduct[] | null = null
    try {
      body = JSON.parse(rawJson) as StoreProduct[] | null
    } catch {
      break
    }
    const products = Array.isArray(body) ? body : []
    if (products.length === 0) break
    for (const p of products) {
      const rawPrice = p.prices?.price?.trim()
      const priceRaw = rawPrice && Number(rawPrice) !== 0 ? rawPrice : undefined
      const availabilityRaw = isPreorderProduct(p)
        ? 'preordine'
        : p.is_in_stock === undefined
          ? undefined
          : String(p.is_in_stock)
      const releaseDateRaw =
        extractReleaseDate(htmlToText(p.short_description) ?? undefined) ??
        extractReleaseDate(htmlToText(p.description) ?? undefined) ??
        undefined

      const parsed = RawProductSchema.safeParse({
        externalId: String(p.id),
        title: p.name ?? '',
        url: p.permalink ?? '',
        priceRaw,
        minorUnitRaw: p.prices?.currency_minor_unit,
        currencyRaw: p.prices?.currency_code ?? currency,
        availabilityRaw,
        descriptionRaw: p.description || p.short_description,
        releaseDateRaw,
        imageUrl: p.images?.[0]?.src,
      })
      if (parsed.success) out.push(parsed.data)
    }
    if (products.length < PAGE_SIZE) break
  }
  return out
}
