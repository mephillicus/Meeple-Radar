import * as cheerio from 'cheerio'
import { RawProductSchema, type RawProduct } from '../../types.js'

type JsonObject = Record<string, unknown>

function asArray(value: unknown): unknown[] {
  if (value === undefined || value === null) return []
  return Array.isArray(value) ? value : [value]
}

function asString(value: unknown): string | undefined {
  if (typeof value === 'string') return value.trim() || undefined
  if (typeof value === 'number') return String(value)
  return undefined
}

function pickImage(value: unknown): string | undefined {
  if (typeof value === 'string') return value.trim() || undefined
  if (Array.isArray(value)) {
    for (const item of value) {
      const url = pickImage(item)
      if (url) return url
    }
    return undefined
  }
  if (value && typeof value === 'object') return asString((value as JsonObject).url)
  return undefined
}

function collectProductNodes(node: unknown, out: JsonObject[]): void {
  if (!node) return
  if (Array.isArray(node)) {
    for (const item of node) collectProductNodes(item, out)
    return
  }
  if (typeof node !== 'object') return
  const obj = node as JsonObject
  const types = asArray(obj['@type'])
  if (types.some((t) => t === 'Product' || t === 'ProductGroup')) out.push(obj)
  if (obj['@graph']) collectProductNodes(obj['@graph'], out)
}

function deriveId(url: string): string {
  try {
    const parsed = new URL(url)
    const last = parsed.pathname.split('/').filter(Boolean).pop()
    return last ? last.replace(/\.html?$/i, '') : parsed.hostname
  } catch {
    return url
  }
}

function buildProduct(fields: {
  title?: string
  url: string
  externalId?: string
  price?: string
  currency?: string
  availability?: string
  image?: string
  description?: string
}): RawProduct | null {
  const title = fields.title?.trim()
  if (!title) return null
  const parsed = RawProductSchema.safeParse({
    externalId: fields.externalId ?? deriveId(fields.url),
    title,
    url: fields.url,
    priceRaw: fields.price,
    currencyRaw: fields.currency,
    availabilityRaw: fields.availability,
    imageUrl: fields.image,
    descriptionRaw: fields.description,
  })
  return parsed.success ? parsed.data : null
}

function offerFields(offers: unknown): { price?: string; currency?: string; availability?: string; url?: string } {
  const first = asArray(offers)[0]
  if (!first || typeof first !== 'object') return {}
  const o = first as JsonObject
  return {
    price: asString(o.price) ?? asString(o.lowPrice) ?? asString(o.highPrice),
    currency: asString(o.priceCurrency),
    availability: asString(o.availability),
    url: asString(o.url),
  }
}

function fromJsonLd($: cheerio.CheerioAPI, pageUrl: string, canonical?: string): RawProduct | null {
  for (const el of $('script[type="application/ld+json"]').toArray()) {
    let data: unknown
    try {
      data = JSON.parse($(el).text())
    } catch {
      continue
    }
    const nodes: JsonObject[] = []
    collectProductNodes(data, nodes)
    for (const node of nodes) {
      const offer = offerFields(node.offers)
      const product = buildProduct({
        title: asString(node.name),
        url: offer.url ?? canonical ?? pageUrl,
        externalId: asString(node.sku) ?? asString(node.productID),
        price: offer.price,
        currency: offer.currency,
        availability: offer.availability,
        image: pickImage(node.image),
        description: asString(node.description),
      })
      if (product) return product
    }
  }
  return null
}

function fromOpenGraph($: cheerio.CheerioAPI, pageUrl: string, canonical?: string): RawProduct | null {
  const meta = (property: string): string | undefined => {
    const el = $(`meta[property="${property}"], meta[name="${property}"]`).first()
    return asString(el.attr('content'))
  }
  const url = canonical ?? pageUrl
  return buildProduct({
    title: meta('og:title'),
    url,
    price: meta('product:price:amount'),
    currency: meta('product:price:currency'),
    availability: meta('product:availability'),
    image: meta('og:image'),
    description: meta('og:description'),
  })
}

export function extractProduct(html: string, pageUrl: string): RawProduct | null {
  const $ = cheerio.load(html)
  const canonical = asString($('link[rel="canonical"]').attr('href'))
  return fromJsonLd($, pageUrl, canonical) ?? fromOpenGraph($, pageUrl, canonical)
}
