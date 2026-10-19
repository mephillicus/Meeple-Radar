import { createClient } from '@supabase/supabase-js'
import type { Listing, Source } from './types.js'
import { normalizeTitle } from './normalize.js'

export interface ListingRow {
  id: number
  title: string
  url: string
  price: number | null
  currency: string | null
  releaseDate: string | null
  imageUrl: string | null
  gameId: number | null
  sourceId: string
  productId?: number | null
}

export interface ListingStore {
  upsertSources(sources: Source[]): Promise<void>
  upsertListings(rows: Listing[]): Promise<void>
  markSourceScanned(sourceId: string): Promise<void>
  logRun(ok: boolean, details: unknown): Promise<void>
  findListingsByTitle(normalizedTitle: string): Promise<ListingRow[]>
  findSources(): Promise<{ id: string; name: string }[]>
  upsertProducts(products: Listing[]): Promise<void>
  linkListingToProduct(listingId: number, productId: number): Promise<void>
}

type SupabaseLike = { from(table: string): any }

export function createSupabaseStore(
  client: SupabaseLike,
  now: () => string = () => new Date().toISOString(),
): ListingStore {
  return {
    async upsertSources(sources: Source[]): Promise<void> {
      if (sources.length === 0) return
      const payload = sources.map((s) => ({
        id: s.id,
        name: s.name,
        kind: s.kind,
        base_url: s.baseUrl,
        adapter: s.kind,
        enabled: s.enabled,
      }))
      const { error } = await client.from('source').upsert(payload, { onConflict: 'id' })
      if (error) throw new Error(`upsert source: ${error.message}`)
    },

    async upsertListings(rows: Listing[]): Promise<void> {
      if (rows.length === 0) return
      const seen = now()
      const payload = rows.map((r) => ({
        source_id: r.sourceId,
        external_id: r.externalId,
        title: r.title,
        normalized_title: normalizeTitle(r.title),
        url: r.url,
        price: r.price,
        currency: r.currency,
        availability: r.availability,
        release_date: r.releaseDate,
        image_url: r.imageUrl,
        game_id: r.gameId ?? null,
        last_seen_at: seen,
      }))
      const { error } = await client
        .from('listing')
        .upsert(payload, { onConflict: 'source_id,external_id' })
      if (error) throw new Error(`upsert listing: ${error.message}`)
    },

    async markSourceScanned(sourceId: string): Promise<void> {
      const { error } = await client
        .from('source')
        .update({ last_scan_at: now() })
        .eq('id', sourceId)
      if (error) throw new Error(`markSourceScanned(${sourceId}): ${error.message}`)
    },

    async logRun(ok: boolean, details: unknown): Promise<void> {
      const { error } = await client
        .from('run_log')
        .insert({ ok, details, finished_at: now() })
      if (error) throw new Error(`logRun: ${error.message}`)
    },

    async findListingsByTitle(normalizedTitle: string): Promise<ListingRow[]> {
      const { data, error } = await client
        .from('listing')
        .select('id, title, url, price, currency, release_date, image_url, game_id, source_id, product_id')
        .eq('normalized_title', normalizedTitle)
      if (error) throw new Error(`findListingsByTitle: ${error.message}`)
      return (data ?? [])
        .map((row: Record<string, unknown>) => ({
          id: Number(row.id),
          title: String(row.title ?? ''),
          url: String(row.url ?? ''),
          price: row.price === null || row.price === undefined ? null : Number(row.price),
          currency: row.currency === null || row.currency === undefined ? null : String(row.currency),
          releaseDate: row.release_date === null || row.release_date === undefined ? null : String(row.release_date),
          imageUrl: row.image_url === null || row.image_url === undefined ? null : String(row.image_url),
          gameId: row.game_id === null || row.game_id === undefined ? null : Number(row.game_id),
          sourceId: row.source_id === null || row.source_id === undefined ? '' : String(row.source_id),
          productId: row.product_id === null || row.product_id === undefined ? null : Number(row.product_id),
        }))
    },

    async findSources(): Promise<{ id: string; name: string }[]> {
      const { data, error } = await client
        .from('source')
        .select('id, name')
      if (error) throw new Error(`findSources: ${error.message}`)
      return (data ?? []).map((row: Record<string, unknown>) => ({
        id: String(row.id ?? ''),
        name: String(row.name ?? ''),
      }))
    },

    async upsertProducts(products: Listing[]): Promise<void> {
      if (products.length === 0) return
      // products are canonical representations built from listings; normalized_title must be present
      const payload = products.map((p) => ({
        normalized_title: normalizeTitle(p.title),
        title: p.title,
        url: p.url,
        price: p.price,
        currency: p.currency,
        release_date: p.releaseDate,
        image_url: p.imageUrl,
        game_id: p.gameId ?? null,
        sources: JSON.stringify([]),
        updated_at: now(),
      }))
      const { error } = await client.from('product').upsert(payload, { onConflict: 'normalized_title' })
      if (error) throw new Error(`upsert product: ${error.message}`)
    },

    async linkListingToProduct(listingId: number, productId: number): Promise<void> {
      const { error } = await client
        .from('listing')
        .update({ product_id: productId })
        .eq('id', listingId)
      if (error) throw new Error(`linkListingToProduct(${listingId}, ${productId}): ${error.message}`)
    },
  }
}

export function createSupabaseClient(url: string, serviceKey: string): ListingStore {
  return createSupabaseStore(createClient(url, serviceKey))
}
