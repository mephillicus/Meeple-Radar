import { RawProductSchema, type RawProduct } from '../types.js'
import { defaultFetch, USER_AGENT, type HttpClient } from './http.js'

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

export async function fetchShopifyProducts(
  baseUrl: string,
  http: HttpClient = defaultFetch,
): Promise<RawProduct[]> {
  const base = baseUrl.replace(/\/+$/, '')
  const out: RawProduct[] = []
  for (let page = 1; ; page++) {
    const res = await http(`${base}/products.json?limit=${PAGE_SIZE}&page=${page}`, {
      'User-Agent': USER_AGENT,
    })
    if (res.status !== 200) break
    const body = (await res.json()) as { products?: ShopifyProduct[] } | null
    const products = Array.isArray(body?.products) ? body!.products! : []
    if (products.length === 0) break
    for (const p of products) {
      const variant = p.variants?.[0]
      const parsed = RawProductSchema.safeParse({
        externalId: String(p.id),
        title: p.title ?? '',
        url: `${base}/products/${p.handle ?? ''}`,
        priceRaw: variant?.price,
        currencyRaw: 'EUR',
        availabilityRaw: variant?.available === undefined ? undefined : String(variant.available),
        descriptionRaw: p.body_html,
        imageUrl: p.images?.[0]?.src,
        publishedAtRaw: p.published_at,
      })
      if (parsed.success) out.push(parsed.data)
    }
    if (products.length < PAGE_SIZE) break
  }
  return out
}
