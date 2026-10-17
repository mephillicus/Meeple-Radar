import type { RawProduct } from '../../types.js'
import type { TextFetcher } from '../http.js'
import { extractProduct } from './extract.js'

export type HtmlSourceSpec = { discover: (fetchText: TextFetcher) => Promise<string[]> }

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

export async function collectHtmlProducts(args: {
  spec: HtmlSourceSpec
  fetchText: TextFetcher
  maxProducts?: number
  delayMs?: number
}): Promise<RawProduct[]> {
  const { spec, fetchText, maxProducts = 400, delayMs = 250 } = args

  const discovered = await spec.discover(fetchText)
  const urls: string[] = []
  const seenUrls = new Set<string>()
  for (const url of discovered) {
    if (seenUrls.has(url)) continue
    seenUrls.add(url)
    urls.push(url)
    if (urls.length >= maxProducts) break
  }

  const out: RawProduct[] = []
  const seenIds = new Set<string>()
  for (let i = 0; i < urls.length; i++) {
    if (i > 0 && delayMs > 0) await sleep(delayMs)
    let html: string | null = null
    try {
      html = await fetchText(urls[i])
    } catch {
      continue
    }
    if (!html) continue
    const product = extractProduct(html, urls[i])
    if (!product) continue
    if (seenIds.has(product.externalId)) continue
    seenIds.add(product.externalId)
    out.push(product)
  }
  return out
}
