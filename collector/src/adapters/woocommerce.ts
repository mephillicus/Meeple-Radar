import { RawProductSchema, type RawProduct } from '../types.js'
import { defaultFetch, USER_AGENT, type HttpClient } from './http.js'

type StoreProduct = {
  id: number | string
  name?: string
  permalink?: string
  description?: string
  is_in_stock?: boolean
  images?: { src?: string }[]
  prices?: { price?: string; currency_code?: string; currency_minor_unit?: number }
}

const PAGE_SIZE = 100

export async function fetchWooCommerceProducts(
  baseUrl: string,
  currency: string,
  http: HttpClient = defaultFetch,
): Promise<RawProduct[]> {
  const base = baseUrl.replace(/\/+$/, '')
  const out: RawProduct[] = []
  for (let page = 1; ; page++) {
    const res = await http(
      `${base}/wp-json/wc/store/v1/products?per_page=${PAGE_SIZE}&page=${page}`,
      { 'User-Agent': USER_AGENT },
    )
    if (res.status !== 200) break
    const body = (await res.json()) as StoreProduct[] | null
    const products = Array.isArray(body) ? body : []
    if (products.length === 0) break
    for (const p of products) {
      const parsed = RawProductSchema.safeParse({
        externalId: String(p.id),
        title: p.name ?? '',
        url: p.permalink ?? '',
        priceRaw: p.prices?.price,
        minorUnitRaw: p.prices?.currency_minor_unit,
        currencyRaw: p.prices?.currency_code ?? currency,
        availabilityRaw: p.is_in_stock === undefined ? undefined : String(p.is_in_stock),
        descriptionRaw: p.description,
        imageUrl: p.images?.[0]?.src,
      })
      if (parsed.success) out.push(parsed.data)
    }
    if (products.length < PAGE_SIZE) break
  }
  return out
}
