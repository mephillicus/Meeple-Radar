import { pathToFileURL } from 'node:url'
import type { Listing, RawProduct, Source, SourceKind } from './types.js'
import { normalizeListing, normalizeTitle } from './normalize.js'
import { createSupabaseClient, type ListingStore } from './db.js'
import { loadConfig } from './config.js'
import { buildRegistry } from './adapters/registry.js'
import { createGameFinder } from './adapters/gemini.js'
import { SOURCES } from './sources.seed.js'
import { canonicalizeProduct } from './merge.js'

export type Adapter = (source: Source) => Promise<RawProduct[]>

export type RunReport = {
  ok: boolean
  perSource: Record<string, number>
  failures: Record<string, string>
  zeroed: string[]
}

export async function runCollector(args: {
  sources: Source[]
  adapters: Record<SourceKind, Adapter>
  store: ListingStore
}): Promise<RunReport> {
  const { sources, adapters, store } = args
  const perSource: Record<string, number> = {}
  const failures: Record<string, string> = {}
  const zeroed: string[] = []

  for (const source of sources) {
    if (!source.enabled) continue
    try {
      const raw = await adapters[source.kind](source)
      if (raw.length === 0) zeroed.push(source.id)
      const rows = raw.map((r) => normalizeListing(source.id, r))
      await store.upsertListings(rows)

      for (const row of rows) {
        const matches = await store.findListingsByTitle(normalizeTitle(row.title))
        const related: Listing[] = [
          ...matches.map((m) => ({
            sourceId: m.sourceId,
            externalId: m.id.toString(),
            title: m.title,
            url: m.url,
            price: m.price,
            currency: m.currency,
            availability: null,
            releaseDate: m.releaseDate,
            imageUrl: m.imageUrl,
            gameId: m.gameId,
          })),
          row,
        ]

        const canonical = canonicalizeProduct(related)
        if (!canonical) continue

        const productIds = await store.upsertProducts([canonical])
        const productId = productIds[0]
        if (productId === undefined) continue

        const allMatches = await store.findListingsByTitle(normalizeTitle(canonical.title))
        for (const listingRow of allMatches) {
          await store.linkListingToProduct(listingRow.id, productId)
        }
      }

      await store.markSourceScanned(source.id)
      perSource[source.id] = raw.length
    } catch (err) {
      failures[source.id] = err instanceof Error ? err.message : String(err)
    }
  }

  const ok = Object.keys(failures).length === 0
  const report: RunReport = { ok, perSource, failures, zeroed }
  await store.logRun(ok, report)
  return report
}

export async function main(): Promise<number> {
  const { supabaseUrl, supabaseServiceKey } = loadConfig(process.env)
  const store = createSupabaseClient(supabaseUrl, supabaseServiceKey)
  await store.upsertSources(SOURCES)
  const report = await runCollector({
    sources: SOURCES,
    adapters: buildRegistry(undefined, createGameFinder(store)),
    store,
  })
  console.log(JSON.stringify(report, null, 2))
  return report.ok && report.zeroed.length === 0 ? 0 : 1
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main()
    .then((code) => {
      process.exitCode = code
    })
    .catch((err: unknown) => {
      console.error(err instanceof Error ? err.message : String(err))
      process.exitCode = 1
    })
}