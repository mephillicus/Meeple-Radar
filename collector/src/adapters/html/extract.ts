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
  for (const value of Object.values(obj)) collectProductNodes(value, out)
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

const SITE_SUFFIX_RE =
  /\s+[-|–]\s+(?:Asmodee(?:\s+Italia)?|GateOnGames|Uplay\.it|DungeonDice\.it|Ravensburger|Giochi\s+Uniti)\s*$/i

function validHttpUrl(value: string | undefined): string | undefined {
  if (!value) return undefined
  try {
    const parsed = new URL(value)
    return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? value : undefined
  } catch {
    return undefined
  }
}

export function buildProduct(fields: {
  title?: string
  url: string
  externalId?: string
  price?: string
  currency?: string
  availability?: string
  image?: string
  description?: string
  releaseDate?: string
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
    releaseDateRaw: fields.releaseDate,
  })
  return parsed.success ? parsed.data : null
}

function flattenOffers(offers: unknown): JsonObject[] {
  const out: JsonObject[] = []
  for (const item of asArray(offers)) {
    if (!item || typeof item !== 'object') continue
    const obj = item as JsonObject
    const nested = obj.offers !== undefined ? flattenOffers(obj.offers) : []
    if (nested.length > 0) {
      for (const child of nested) {
        out.push({
          ...child,
          priceCurrency: child.priceCurrency ?? obj.priceCurrency,
        })
      }
    } else {
      out.push(obj)
    }
  }
  return out
}

function offerFields(offers: unknown): {
  price?: string
  currency?: string
  availability?: string
  url?: string
  availabilityStarts?: string
} {
  const list = flattenOffers(offers)
  if (list.length === 0) return {}
  const chosen =
    list.find((o) => /InStock/i.test(asString(o.availability) ?? '')) ??
    list.find((o) => /PreOrder/i.test(asString(o.availability) ?? '')) ??
    list[0]
  return {
    price: asString(chosen.price) ?? asString(chosen.lowPrice) ?? asString(chosen.highPrice),
    currency: asString(chosen.priceCurrency),
    availability: asString(chosen.availability),
    url: asString(chosen.url),
    availabilityStarts: asString(chosen.availabilityStarts),
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
      const url = validHttpUrl(canonical) ?? validHttpUrl(pageUrl) ?? validHttpUrl(offer.url) ?? pageUrl
      const product = buildProduct({
        title: asString(node.name),
        url,
        externalId: asString(node.sku) ?? asString(node.productID),
        price: offer.price,
        currency: offer.currency,
        availability: offer.availability,
        image: pickImage(node.image),
        description: asString(node.description),
        releaseDate: asString(node.releaseDate) ?? offer.availabilityStarts,
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
  const url = validHttpUrl(canonical) ?? validHttpUrl(pageUrl) ?? pageUrl
  const rawTitle = meta('og:title')
  const title = rawTitle?.replace(SITE_SUFFIX_RE, '').trim()
  return buildProduct({
    title,
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
  const product = fromJsonLd($, pageUrl, canonical) ?? fromOpenGraph($, pageUrl, canonical)
  if (!product) return null
  const descEl = $('div[itemprop="description"]').first().clone()
  descEl.find('script, style, noscript').remove()
  const visible = descEl.text().trim()
  if (visible && (!product.descriptionRaw || product.descriptionRaw.length < visible.length)) {
    return { ...product, descriptionRaw: visible }
  }
  return product
}
