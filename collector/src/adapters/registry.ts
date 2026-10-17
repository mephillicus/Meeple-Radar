import type { SourceKind } from '../types.js'
import type { Adapter } from '../run.js'
import { textFetcher, type HttpClient } from './http.js'
import { fetchShopifyProducts } from './shopify.js'
import { fetchWooCommerceProducts } from './woocommerce.js'
import { fetchWordPressProducts } from './wordpress.js'
import { collectHtmlProducts } from './html/crawl.js'
import { HTML_SPECS } from './html/specs.js'
import { HEADLESS_SPECS } from './headless/specs.js'
import { getSharedFetcher } from './headless/fetch.js'

const WP_SOURCES: Record<string, { postType: string; permalinkBase?: string }> = {
  stonemaier: { postType: 'smg_game', permalinkBase: '/games' },
}

export function buildRegistry(http?: HttpClient): Record<SourceKind, Adapter> {
  return {
    shopify: (source) => fetchShopifyProducts(source.baseUrl, http),
    woocommerce: (source) => fetchWooCommerceProducts(source.baseUrl, 'EUR', http),
    wordpress: (source) => {
      const wp = WP_SOURCES[source.id]
      return fetchWordPressProducts(source.baseUrl, http, wp?.postType ?? 'product', wp?.permalinkBase)
    },
    html: async (source) => {
      const spec = HTML_SPECS[source.id]
      if (!spec) throw new Error(`nessuno spec HTML per la fonte "${source.id}"`)
      return collectHtmlProducts({ spec, fetchText: textFetcher(http) })
    },
    headless: async (source) => {
      const spec = HEADLESS_SPECS[source.id]
      if (!spec) throw new Error(`nessuno spec headless per la fonte "${source.id}"`)
      return collectHtmlProducts({ spec, fetchText: getSharedFetcher() })
    },
  }
}
