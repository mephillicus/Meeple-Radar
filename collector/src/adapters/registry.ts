import type { SourceKind } from '../types.js'
import type { Adapter } from '../run.js'
import type { HttpClient } from './http.js'
import { fetchShopifyProducts } from './shopify.js'
import { fetchWooCommerceProducts } from './woocommerce.js'
import { fetchWordPressProducts } from './wordpress.js'

export function buildRegistry(http?: HttpClient): Record<SourceKind, Adapter> {
  return {
    shopify: (source) => fetchShopifyProducts(source.baseUrl, http),
    woocommerce: (source) => fetchWooCommerceProducts(source.baseUrl, 'EUR', http),
    wordpress: (source) => fetchWordPressProducts(source.baseUrl, http),
    html: async () => [],
    headless: async () => [],
  }
}
