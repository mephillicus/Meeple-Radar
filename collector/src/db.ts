import { createClient } from '@supabase/supabase-js'
import type { Listing, Source } from './types.js'

export interface ListingStore {
  upsertSources(sources: Source[]): Promise<void>
  upsertListings(rows: Listing[]): Promise<void>
  markSourceScanned(sourceId: string): Promise<void>
  logRun(ok: boolean, details: unknown): Promise<void>
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
        url: r.url,
        price: r.price,
        currency: r.currency,
        availability: r.availability,
        release_date: r.releaseDate,
        image_url: r.imageUrl,
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
  }
}

export function createSupabaseClient(url: string, serviceKey: string): ListingStore {
  return createSupabaseStore(createClient(url, serviceKey))
}
