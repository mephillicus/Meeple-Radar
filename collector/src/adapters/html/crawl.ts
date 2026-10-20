import type { RawProduct } from '../../types.js'
import type { TextFetcher } from '../http.js'
import { extractProduct } from './extract.js'

export type DiscoveredProduct = { url: string; releaseDate?: string }

export type HtmlSourceSpec = {
  discover: (fetchText: TextFetcher) => Promise<DiscoveredProduct[]>
  extract?: (html: string, url: string) => RawProduct | null
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

export const DEFAULT_CONCURRENCY = 6

export async function collectHtmlProducts(args: {
  spec: HtmlSourceSpec
  fetchText: TextFetcher
  maxProducts?: number
  delayMs?: number
  concurrency?: number
}): Promise<RawProduct[]> {
  const { spec, fetchText, delayMs = 250 } = args
  const concurrency = Math.max(1, args.concurrency ?? DEFAULT_CONCURRENCY)
  const envMax = Number(process.env.MEEPLE_MAX_PRODUCTS)
  const maxProducts = args.maxProducts ?? (Number.isFinite(envMax) && envMax > 0 ? envMax : 400)

  const discovered = await spec.discover(fetchText)
  const items: DiscoveredProduct[] = []
  const seenUrls = new Set<string>()
  for (const item of discovered) {
    if (seenUrls.has(item.url)) continue
    seenUrls.add(item.url)
    items.push(item)
    if (items.length >= maxProducts) break
  }

  const results: (RawProduct | null)[] = new Array(items.length).fill(null)
  let next = 0
  const workers = Array.from({ length: Math.min(concurrency, items.length) }, async () => {
    for (;;) {
      const i = next++
      if (i >= items.length) return
      const item = items[i]
      if (i > 0 && delayMs > 0) await sleep(delayMs)
      let html: string | null = null
      try {
        html = await fetchText(item.url)
      } catch {
        continue
      }
      if (!html) continue
      const product = spec.extract ? spec.extract(html, item.url) : extractProduct(html, item.url)
      if (!product) continue
      if (product.releaseDateRaw === undefined && item.releaseDate !== undefined) {
        product.releaseDateRaw = item.releaseDate
      }
      results[i] = product
    }
  })
  await Promise.all(workers)

  const out: RawProduct[] = []
  const seenIds = new Set<string>()
  for (const product of results) {
    if (!product) continue
    if (seenIds.has(product.externalId)) continue
    seenIds.add(product.externalId)
    out.push(product)
  }
  return out
}
