import * as cheerio from 'cheerio'
import type { TextFetcher } from '../http.js'
import type { HtmlSourceSpec } from './crawl.js'
import { toItems } from './links.js'
import { buildProduct, extractProduct } from './extract.js'

const SHOP_URL = 'https://horribleguild.com/eu/shop/'
const MAX_PAGES = 10

// Solo la regione /eu/ (prezzi in EUR): la stessa scheda esiste anche sotto /us/.
const PRODUCT_URL = /^https:\/\/horribleguild\.com\/eu\/product\/[a-z0-9-]+\/$/

// In sconto WooCommerce mostra sia il prezzo originale (<del>) sia quello
// corrente (<ins>): serve il secondo, il primo è il prezzo sbarrato.
function priceTextFrom($: cheerio.CheerioAPI): string {
  const sale = $('.summary .price ins .woocommerce-Price-amount bdi').first().text()
  if (sale.trim()) return sale
  return $('.summary .price .woocommerce-Price-amount bdi').first().text()
}

// Accetta sia "24,90" (virgola) sia "24.90" (punto), con o senza migliaia.
function normalizePrice(text: string): string | undefined {
  const t = text.trim().replace(/\s+/g, '')
  if (!t) return undefined
  const commas = (t.match(/,/g) ?? []).length
  const dots = (t.match(/\./g) ?? []).length
  let s = t
  if (commas > 0 && dots > 0) {
    s = t.lastIndexOf(',') > t.lastIndexOf('.')
      ? t.replace(/\./g, '').replace(',', '.')
      : t.replace(/,/g, '')
  } else if (commas === 1) {
    s = t.replace(',', '.')
  }
  if (!Number.isFinite(Number(s))) return undefined
  return s
}

// Il tab Description e il testo lungo della pagina; lo short-description ha
// dentro le righe Designer/Artist con le immagini, quindi si usa solo se manca
// il tab. L'<h2> di testa e l'etichetta della sezione, non parte del testo.
function descriptionFrom($: cheerio.CheerioAPI): string | undefined {
  const tab = $('#tab-description').first()
  const source = tab.length > 0 ? tab : $('.woocommerce-product-details__short-description').first()
  if (source.length === 0) return undefined
  const copy = source.clone()
  copy.find('h2').first().remove()
  return copy.html()?.trim() || undefined
}

function resolveUrl(href: string | undefined, base: string): string | undefined {
  const trimmed = href?.trim()
  if (!trimmed) return undefined
  try {
    return new URL(trimmed, base).toString()
  } catch {
    return undefined
  }
}

function imageFrom($: cheerio.CheerioAPI, baseUrl: string, fallback?: string): string | undefined {
  if (fallback) return fallback
  const og = $('meta[property="og:image"]').first().attr('content')
  if (og?.trim()) return resolveUrl(og, baseUrl)
  const galleryImg = $('.woocommerce-product-gallery__image img').first()
  const src = galleryImg.attr('data-large_image') || galleryImg.attr('data-src') || galleryImg.attr('src')
  return resolveUrl(src, baseUrl)
}

function availabilityFrom($: cheerio.CheerioAPI, fallback?: string): string | undefined {
  const hasPreorder =
    $('.preorder, .pre-order, .wc-pre-orders-availability-date, [class*="preorder"], [class*="pre-order"]').length > 0 ||
    /pre-?order/i.test($('.single_add_to_cart_button').first().text())
  if (hasPreorder) return 'preorder'
  if (fallback) return fallback
  if ($('.summary .out-of-stock, .stock.out-of-stock, .product.outofstock').length > 0) return 'out_of_stock'
  if ($('.summary .in-stock, .stock.in-stock, .product.instock').length > 0) return 'available'
  return undefined
}

function releaseDateFrom($: cheerio.CheerioAPI, fallback?: string): string | undefined {
  if (fallback) return fallback
  const attrDate = $('[data-release-date]').first().attr('data-release-date')?.trim()
  if (attrDate) return attrDate
  const preOrderDate = $('.wc-pre-orders-availability-date').first().text().trim()
  return preOrderDate || undefined
}

async function discoverPaginated(fetchText: TextFetcher): Promise<string[]> {
  const out: string[] = []
  const seenProducts = new Set<string>()
  const seenPages = new Set<string>()
  let pageUrl: string | undefined = SHOP_URL

  while (pageUrl && seenPages.size < MAX_PAGES) {
    if (seenPages.has(pageUrl)) break
    seenPages.add(pageUrl)

    let html = await fetchText(pageUrl)
    if (!html) html = await fetchText(pageUrl)
    if (!html) break

    const $ = cheerio.load(html)
    for (const el of $('a[href]').toArray()) {
      const href = $(el).attr('href')
      if (!href) continue
      let url: string
      try {
        url = new URL(href, pageUrl).toString().split('#')[0]
      } catch {
        continue
      }
      if (!PRODUCT_URL.test(url) || seenProducts.has(url)) continue
      seenProducts.add(url)
      out.push(url)
    }

    const nextHref = $('a.next.page-numbers').first().attr('href')
    if (!nextHref) break
    try {
      pageUrl = new URL(nextHref, pageUrl).toString().split('#')[0]
    } catch {
      break
    }
  }
  return out
}

export const horribleguildSpec: HtmlSourceSpec = {
  discover: async (fetchText) => toItems(await discoverPaginated(fetchText)),
  extract(html, url) {
    const $ = cheerio.load(html)
    const base = extractProduct(html, url)
    const title = $('.product_title').first().text().trim()
    const rawPrice = priceTextFrom($).match(/[\d.,]+/)?.[0]
    const price = rawPrice === undefined ? undefined : normalizePrice(rawPrice)
    return buildProduct({
      title,
      url,
      price,
      currency: price === undefined ? undefined : 'EUR',
      image: imageFrom($, url, base?.imageUrl),
      availability: availabilityFrom($, base?.availabilityRaw),
      releaseDate: releaseDateFrom($, base?.releaseDateRaw),
      description: descriptionFrom($),
    })
  },
}
