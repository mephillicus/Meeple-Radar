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
  upsertProducts(products: Listing[]): Promise<number[]>
  linkListingToProduct(listingId: number, productId: number): Promise<void>
  purgeStale(before: string): Promise<{ listings: number; products: number }>
}

type SupabaseLike = { from(table: string): any }

const PURGE_PAGE_SIZE = 1000
const PURGE_DELETE_BATCH_SIZE = 500

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
        ...(r.price != null && { price: r.price }),
        ...(r.currency != null && { currency: r.currency }),
        ...(r.availability != null && { availability: r.availability }),
        ...(r.releaseDate != null && { release_date: r.releaseDate }),
        ...(r.imageUrl != null && { image_url: r.imageUrl }),
        ...(r.gameId != null && { game_id: r.gameId }),
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

    async upsertProducts(products: Listing[]): Promise<number[]> {
      if (products.length === 0) return []
      const payload = products.map((p) => ({
        normalized_title: normalizeTitle(p.title),
        ...(p.title != null && { title: p.title }),
        url: p.url,
        ...(p.price != null && { price: p.price }),
        ...(p.currency != null && { currency: p.currency }),
        ...(p.releaseDate != null && { release_date: p.releaseDate }),
        ...(p.imageUrl != null && { image_url: p.imageUrl }),
        ...(p.gameId != null && { game_id: p.gameId }),
        sources: JSON.stringify([]),
        updated_at: now(),
      }))
      const { data, error } = await client
        .from('product')
        .upsert(payload, { onConflict: 'normalized_title' })
        .select('id')
      if (error) throw new Error(`upsert product: ${error.message}`)
      return (data ?? []).map((row: Record<string, unknown>) => Number(row.id))
    },

    async linkListingToProduct(listingId: number, productId: number): Promise<void> {
      const { error } = await client
        .from('listing')
        .update({ product_id: productId })
        .eq('id', listingId)
      if (error) throw new Error(`linkListingToProduct(${listingId}, ${productId}): ${error.message}`)
    },

    async purgeStale(before: string): Promise<{ listings: number; products: number }> {
      const fetchAll = async (
        run: (from: number, to: number) => PromiseLike<{ data: unknown; error: { message: string } | null }>,
      ): Promise<Record<string, unknown>[]> => {
        const rows: Record<string, unknown>[] = []
        let from = 0
        for (;;) {
          const { data, error } = await run(from, from + PURGE_PAGE_SIZE - 1)
          if (error) throw new Error(`purgeStale: ${error.message}`)
          const page = (data ?? []) as Record<string, unknown>[]
          if (page.length === 0) break
          rows.push(...page)
          from += page.length
        }
        return rows
      }

      // PostgREST mette gli id nella query string: troppi id in una chiamata
      // sola superano il limite di Kong e la richiesta fallisce con 414.
      const deleteInBatches = async (table: string, ids: number[]): Promise<void> => {
        for (let i = 0; i < ids.length; i += PURGE_DELETE_BATCH_SIZE) {
          const batch = ids.slice(i, i + PURGE_DELETE_BATCH_SIZE)
          const { error } = await client.from(table).delete().in('id', batch)
          if (error) throw new Error(`purgeStale: ${error.message}`)
        }
      }

      const dated = await fetchAll((from, to) =>
        client.from('listing').select('id')
          .not('release_date', 'is', null).lt('release_date', before).range(from, to))
      const undated = await fetchAll((from, to) =>
        client.from('listing').select('id')
          .is('release_date', null).lt('last_seen_at', before).range(from, to))
      const stale = [...dated, ...undated].map((row) => Number(row.id))

      const favoriteListings = await fetchAll((from, to) =>
        client.from('user_action').select('listing_id')
          .eq('action', 'favorite').not('listing_id', 'is', null).range(from, to))
      const favoriteIds = new Set(favoriteListings.map((row) => Number(row.listing_id)))
      const toDelete = stale.filter((id) => !favoriteIds.has(id))
      await deleteInBatches('listing', toDelete)

      const staleProducts = await fetchAll((from, to) =>
        client.from('product').select('id').lt('release_date', before).range(from, to))
      const staleProductIds = staleProducts.map((row) => Number(row.id))

      const favoriteProducts = await fetchAll((from, to) =>
        client.from('user_action').select('product_id')
          .eq('action', 'favorite').not('product_id', 'is', null).range(from, to))
      const favoriteProductIds = new Set(favoriteProducts.map((row) => Number(row.product_id)))

      const referenced = await fetchAll((from, to) =>
        client.from('listing').select('product_id')
          .not('product_id', 'is', null).range(from, to))
      const referencedIds = new Set(referenced.map((row) => Number(row.product_id)))

      const orphan = staleProductIds.filter((id) => !referencedIds.has(id) && !favoriteProductIds.has(id))
      await deleteInBatches('product', orphan)

      return { listings: toDelete.length, products: orphan.length }
    },
  }
}

export function createSupabaseClient(url: string, serviceKey: string): ListingStore {
  return createSupabaseStore(createClient(url, serviceKey))
}