import { RawProductSchema, type RawProduct } from '../types.js'
import { defaultFetch, USER_AGENT, type HttpClient } from './http.js'
import { extractReleaseDate } from '../normalize.js'
import * as cheerio from 'cheerio'

const PREORDER_AVAILABILITY = 'preordine'

type ShopifyProduct = {
  id: number | string
  title?: string
  handle?: string
  body_html?: string
  published_at?: string
  images?: { src?: string }[]
  variants?: { price?: string; available?: boolean }[]
}

const PAGE_SIZE = 250

function isFutureIso(iso: string, now: Date): boolean {
  const d = new Date(`${iso}T00:00:00Z`)
  return d > now
}

async function extractDateFromHtml(url: string, http: HttpClient): Promise<string | undefined> {
  try {
    const res = await http(url, { 'User-Agent': USER_AGENT })
    if (res.status !== 200) return undefined
    const html = await res.text()
    const $ = cheerio.load(html)
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
): Promise<RawProduct[]> {
  const base = baseUrl.replace(/\/+$/, '')
  const isPreordini = collection === 'preordini'
  const path = collection
    ? `/collections/${encodeURIComponent(collection)}/products.json`
    : '/products.json'
  const out: RawProduct[] = []
  for (let page = 1; ; page++) {
    const res = await http(`${base}${path}?limit=${PAGE_SIZE}&page=${page}`, {
      'User-Agent': USER_AGENT,
    })
    if (res.status !== 200) break
    const body = (await res.json()) as { products?: ShopifyProduct[] } | null
    const products = Array.isArray(body?.products) ? body!.products! : []
    if (products.length === 0) break
    for (const p of products) {
      const variant = p.variants?.[0]
      const available = variant?.available

      let releaseDateRaw = extractReleaseDate(p.body_html ?? undefined) ?? undefined

      if (!releaseDateRaw && (isPreordini || available === true)) {
        const handle = p.handle ?? ''
        if (handle) {
          const htmlDate = await extractDateFromHtml(`${base}/products/${handle}`, http)
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
