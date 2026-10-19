import { pathToFileURL } from 'node:url'
import type { RawProduct, Source, SourceKind } from './types.js'
import { normalizeListing, normalizeTitle } from './normalize.js'
import { createSupabaseClient, type ListingStore } from './db.js'
import { loadConfig } from './config.js'
import { buildRegistry } from './adapters/registry.js'
import { createGameFinder } from './adapters/gemini.js'
import { SOURCES } from './sources.seed.js'
import type { Listing } from './types.js'

export type Adapter = (source: Source) => Promise<RawProduct[]>

export type RunReport = {
  ok: boolean
  perSource: Record<string, number>
  failures: Record<string, string>
  zeroed: string[]
}

function releaseDatePrecision(iso: string | null): number {
  if (!iso) return 0
  const parts = iso.split('-')
  if (parts.length === 3 && parts[2] && parts[2] !== '01') return 3
  if (parts.length >= 2 && parts[1] && parts[1] !== '01') return 2
  if (parts.length >= 1 && parts[0]) return 1
  return 0
}

function chooseCanonicalField<T>(values: Array<T | null | undefined>, prefer: (v: T) => boolean = () => true): T | null {
  const real = values.filter((v): v is T => v !== null && v !== undefined)
  if (real.length === 0) return null
  return real.find(prefer) ?? real[0]
}

function canonicalizeProduct(listings: Listing[]): Listing | null {
  if (listings.length === 0) return null

  const title = chooseCanonicalField(listings.map((l) => l.title)) ?? listings[0].title
  const url = chooseCanonicalField(listings.map((l) => l.url), (v) => !!v) ?? listings[0].url
  const price = chooseCanonicalField(listings.map((l) => l.price), (v) => v !== null && v !== undefined) ?? null
  const currency = chooseCanonicalField(listings.map((l) => l.currency), (v) => !!v) ?? null
  const releaseDate = listings
    .map((l) => l.releaseDate)
    .filter((v): v is string => !!v)
    .sort((a, b) => releaseDatePrecision(b) - releaseDatePrecision(a))[0] ?? null
  const imageUrl = chooseCanonicalField(listings.map((l) => l.imageUrl), (v) => !!v) ?? null
  const gameId = chooseCanonicalField(listings.map((l) => l.gameId), (v) => v !== null && v !== undefined) ?? null

  return {
    sourceId: listings[0].sourceId,
    externalId: listings[0].externalId,
    title,
    url,
    price,
    currency,
    availability: chooseCanonicalField(listings.map((l) => l.availability), (v) => !!v) ?? null,
    releaseDate,
    imageUrl,
    gameId,
  }
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
        const related = matches
          .map((m) => ({
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
          }))
          .concat(row)

        const canonical = canonicalizeProduct(related)
        if (canonical) {
          await store.upsertProducts([canonical])
          const productMatches = await store.findListingsByTitle(normalizeTitle(canonical.title))
          for (const listingRow of productMatches) {
            if (listingRow.id) {
              await store.linkListingToProduct(listingRow.id, Number(listingRow.id))
            }
          }
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
