import { pathToFileURL } from 'node:url'
import type { Listing, RawProduct, Source, SourceKind, SourceMetrics } from './types.js'
import { normalizeListing, normalizeTitle } from './normalize.js'
import { createSupabaseClient, type ListingRow, type ListingStore } from './db.js'
import { loadConfig } from './config.js'
import { buildRegistry } from './adapters/registry.js'
import { createGameFinder } from './adapters/gemini.js'
import { SOURCES } from './sources.seed.js'
import { canonicalizeProduct } from './merge.js'
import { retentionCutoff } from './retention.js'
import { loadRanksIndex } from './bgg/csv-ranks.js'
import { bggSearchUrl, parseGeekItem, parseSearchIds } from './bgg/online.js'
import { runBggEnrichment, type BggReport } from './bgg/enrich.js'
import { createSkipStore } from './bgg/skipped.js'
import { createCurlFetcher } from './adapters/headless/fetch.js'
import { defaultFetch, USER_AGENT, type HttpClient } from './adapters/http.js'
import { runHealthCheck, type HealthReport } from './health.js'
import { runAlerts, type AlertsReport } from './alerts.js'

export type Adapter = (source: Source) => Promise<RawProduct[]>

// Testi generati dal titolo e non scritti da un editore: non finiscono mai su
// product.description. Restano su descriptionRaw per estrarre la data.
const GENERATED_TEXT_KINDS: ReadonlySet<SourceKind> = new Set(['news', 'gemini'])

const DEFAULT_CONCURRENCY = 3

export type RunReport = {
  ok: boolean
  perSource: Record<string, number>
  failures: Record<string, string>
  zeroed: string[]
  purged?: { listings: number; products: number }
  bgg?: BggReport
  metrics?: Record<string, SourceMetrics>
  health?: HealthReport
  alerts?: AlertsReport
}

function createTrackedHttpClient(
  base: HttpClient,
  bucket: Record<string, number>,
): HttpClient {
  return async (url, headers) => {
    try {
      const res = await base(url, headers)
      if (res.status !== 200) {
        const key = String(res.status)
        bucket[key] = (bucket[key] ?? 0) + 1
      }
      return res
    } catch (err) {
      const name = err && typeof err === 'object' ? (err as { name?: unknown }).name : undefined
      const key = name === 'TimeoutError' || name === 'AbortError' ? 'timeout' : 'network'
      bucket[key] = (bucket[key] ?? 0) + 1
      throw err
    }
  }
}

async function fetchListingsMap(
  store: ListingStore,
  titles: string[],
): Promise<Map<string, ListingRow[]>> {
  const unique = Array.from(new Set(titles.filter((t) => t.length > 0)))
  if (unique.length === 0) return new Map()
  if (store.findListingsByTitles) {
    return store.findListingsByTitles(unique)
  }
  const map = new Map<string, ListingRow[]>()
  for (const t of unique) {
    map.set(t, await store.findListingsByTitle(t))
  }
  return map
}

export async function runCollector(args: {
  sources: Source[]
  adapters: Record<SourceKind, Adapter>
  store: ListingStore
  now?: () => Date
  concurrency?: number
  getSourceHttpErrors?: (sourceId: string) => Record<string, number>
  enrich?: () => Promise<BggReport>
  collection?: () => Promise<{
    synced: number
    owned: number
    wishlist: number
    autofavorite: { inserted: number; skipped: number }
    error?: string
  }>
  healthCheck?: (report: RunReport) => Promise<HealthReport>
  alerts?: () => Promise<AlertsReport>
}): Promise<RunReport> {
  const {
    sources,
    adapters,
    store,
    now: nowFn = () => new Date(),
    concurrency = DEFAULT_CONCURRENCY,
    getSourceHttpErrors,
    enrich,
    collection,
    healthCheck,
    alerts,
  } = args
  const perSource: Record<string, number> = {}
  const failures: Record<string, string> = {}
  const zeroed: string[] = []
  const metrics: Record<string, SourceMetrics> = {}
  const startedAt = nowFn()
  const cutoff = retentionCutoff(startedAt)
  let purged: RunReport['purged']
  try {
    purged = await store.purgeStale(cutoff)
  } catch (err) {
    // Una purge fallita non deve fermare la raccolta, ma deve fallire il giro
    // e finire nel report come un fallimento di fonte.
    failures['purgeStale'] = err instanceof Error ? err.message : String(err)
  }

  const processSource = async (source: Source): Promise<void> => {
    const sourceStartMs = Date.now()
    let discoveredCount = 0
    let extractedCount = 0
    try {
      const raw = await adapters[source.kind](source)
      discoveredCount = raw.length
      if (raw.length === 0) zeroed.push(source.id)
      // Pairing raw/riga PRIMA del filtro cutoff: un filtro per indice su rows
      // separate sfalserebbe gli indici e farebbe perdere articleDescription.
      let paired = raw
        .map((r) => ({ raw: r, row: normalizeListing(source.id, r) }))
        .map((p) => (GENERATED_TEXT_KINDS.has(source.kind) ? { ...p, row: { ...p.row, description: null } } : p))
        .filter((p) => p.row.releaseDate === null || p.row.releaseDate >= cutoff)

      if (source.kind === 'roundups' && paired.length > 0) {
        const existingByTitle = await fetchListingsMap(
          store,
          paired.map((p) => normalizeTitle(p.row.title)),
        )
        paired = paired.filter((p) => {
          const matches = existingByTitle.get(normalizeTitle(p.row.title)) ?? []
          return !matches.some((m) => m.sourceId === source.id)
        })
      }

      extractedCount = paired.length
      await store.upsertListings(paired.map((p) => p.row))

      if (paired.length > 0) {
        const isEditorial = source.kind === 'news' || source.kind === 'roundups'
        const matchesByTitle = await fetchListingsMap(
          store,
          paired.map((p) => normalizeTitle(p.row.title)),
        )

        const prepared: {
          p: (typeof paired)[number]
          canonical: Listing
          canonicalNormTitle: string
          payload: Listing
        }[] = []

        for (const p of paired) {
          const { row } = p
          const matches = matchesByTitle.get(normalizeTitle(row.title)) ?? []
          // In una run editoriale (news/roundups) la riga corrente non porta
          // prezzo né data nel prodotto: quei campi arrivano a product solo dai
          // fill write-once, così il dato del negozio vince sempre.
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
              description: null,
              imageUrl: m.imageUrl,
              gameId: m.gameId,
            })),
            isEditorial ? { ...row, releaseDate: null, price: null, currency: null } : row,
          ]

          const canonical = canonicalizeProduct(related)
          if (!canonical) continue

          // In una run editoriale (news/roundups) l'upsert del prodotto non
          // porta mai prezzo né data: quei campi arrivano su product solo dai
          // fill write-once, quindi le chiavi vanno tolte del tutto, l'upsert
          // sostituisce le colonne presenti nel payload. Anche la richiesta di
          // azzeramento resta fuori: una data scritta da un fill non si tocca.
          // Le righe listing continuano a portarli.
          const payload = (isEditorial
            ? (({ price, currency, releaseDate, clearReleaseDate, ...rest }) => rest)(canonical)
            : canonical) as Listing

          prepared.push({
            p,
            canonical,
            canonicalNormTitle: normalizeTitle(canonical.title),
            payload,
          })
        }

        const productIdByTitle = new Map<string, number>()
        if (store.upsertProductsBatch) {
          const batchIds = await store.upsertProductsBatch(prepared.map((item) => item.payload))
          for (const [k, v] of batchIds) productIdByTitle.set(k, v)
        } else {
          for (const item of prepared) {
            const ids = await store.upsertProducts([item.payload])
            if (ids[0] !== undefined) productIdByTitle.set(item.canonicalNormTitle, ids[0])
          }
        }

        // Write-once fills: lo store aggiorna solo dove la colonna e ancora null.
        for (const { p, canonical, canonicalNormTitle } of prepared) {
          const productId = productIdByTitle.get(canonicalNormTitle)
          if (productId === undefined) continue
          if (canonical.description) await store.fillProductDescription(productId, canonical.description)
          // La citazione verbatim dell'articolo viaggia fuori da row.description
          // (azzerato per GENERATED_TEXT_KINDS) ed entra solo se ancora assente.
          if (p.raw.articleDescription) await store.fillProductDescription(productId, p.raw.articleDescription)
          if (isEditorial && p.row.releaseDate) await store.fillProductReleaseDate(productId, p.row.releaseDate)
          if (p.raw.publisher) await store.fillProductPublisher(productId, p.raw.publisher)
        }

        // Link listings to products in batch (o fallback singolo per mock parziali).
        const allMatchesByTitle = await fetchListingsMap(
          store,
          prepared.map((item) => item.canonicalNormTitle),
        )
        const linkedTitles = new Set<string>()
        for (const { canonicalNormTitle } of prepared) {
          if (linkedTitles.has(canonicalNormTitle)) continue
          linkedTitles.add(canonicalNormTitle)
          const productId = productIdByTitle.get(canonicalNormTitle)
          if (productId === undefined) continue
          const allMatches = allMatchesByTitle.get(canonicalNormTitle) ?? []
          if (allMatches.length === 0) continue
          if (store.linkListingsToProduct) {
            await store.linkListingsToProduct(
              allMatches.map((m) => m.id),
              productId,
            )
          } else {
            for (const listingRow of allMatches) {
              await store.linkListingToProduct(listingRow.id, productId)
            }
          }
        }
      }

      await store.markSourceScanned(source.id)
      perSource[source.id] = raw.length
    } catch (err) {
      failures[source.id] = err instanceof Error ? err.message : String(err)
    } finally {
      metrics[source.id] = {
        discoveredCount,
        extractedCount,
        durationMs: Math.max(0, Date.now() - sourceStartMs),
        httpErrorsByStatus: getSourceHttpErrors ? { ...getSourceHttpErrors(source.id) } : {},
      }
    }
  }

  const enabledSources = sources.filter((s) => s.enabled)
  let nextIndex = 0
  const workerCount = Math.min(Math.max(1, concurrency), enabledSources.length)
  await Promise.all(
    Array.from({ length: workerCount }, async () => {
      while (nextIndex < enabledSources.length) {
        const source = enabledSources[nextIndex++]
        await processSource(source)
      }
    }),
  )

  const report: RunReport = { ok: false, perSource, failures, zeroed, purged, metrics }

  if (enrich) {
    try {
      report.bgg = await enrich()
    } catch (err) {
      // L'arricchimento e' best-effort: l'errore finisce in report.bgg ma non
      // entra in failures e non tocca ok della run.
      report.bgg = {
        candidates: 0,
        linked: 0,
        viaCsv: 0,
        viaSearch: 0,
        listingsLinked: 0,
        unresolved: 0,
        onlineRequests: 0,
        csvMissing: false,
        error: err instanceof Error ? err.message : String(err),
      }
    }
  }

  if (collection) {
    try {
      const coll = await collection()
      const prev = (report.bgg ?? {}) as BggReport
      report.bgg = {
        candidates: prev.candidates,
        linked: prev.linked,
        viaCsv: prev.viaCsv,
        viaSearch: prev.viaSearch,
        listingsLinked: prev.listingsLinked,
        unresolved: prev.unresolved,
        onlineRequests: prev.onlineRequests,
        csvMissing: prev.csvMissing,
        error: prev.error,
        collection: { synced: coll.synced, owned: coll.owned, wishlist: coll.wishlist, error: coll.error },
        autofavorite: coll.autofavorite
          ? { inserted: coll.autofavorite.inserted, skipped: coll.autofavorite.skipped }
          : undefined,
      }
    } catch (err) {
      const prev = (report.bgg ?? {}) as BggReport
      report.bgg = {
        candidates: prev.candidates,
        linked: prev.linked,
        viaCsv: prev.viaCsv,
        viaSearch: prev.viaSearch,
        listingsLinked: prev.listingsLinked,
        unresolved: prev.unresolved,
        onlineRequests: prev.onlineRequests,
        csvMissing: prev.csvMissing,
        error: prev.error,
        collection: {
          synced: 0,
          owned: 0,
          wishlist: 0,
          error: err instanceof Error ? err.message : String(err),
        },
      }
    }
  }

  // Motore alert post-raccolta (P3.2): best-effort, non fallisce mai la run.
  if (alerts) {
    try {
      report.alerts = await alerts()
    } catch (err) {
      report.alerts = {
        detected: 0,
        inserted: 0,
        byType: { back_in_stock: 0, price_drop: 0, new_expansion: 0 },
        error: err instanceof Error ? err.message : String(err),
      }
    }
  } else if (store.insertUserAlerts) {
    report.alerts = await runAlerts({ store })
  }

  // Controllo salute post-raccolta (P2.1): best-effort, non fallisce mai la run.
  if (healthCheck) {
    try {
      report.health = await healthCheck(report)
    } catch (err) {
      report.health = {
        checkedRuns: 0,
        warnings: [],
        error: err instanceof Error ? err.message : String(err),
      }
    }
  } else if (store.getRecentRunLogs) {
    report.health = await runHealthCheck({
      store,
      sources,
      currentReport: report,
    })
  }

  report.ok = Object.keys(failures).length === 0
  await store.logRun(report.ok, report, startedAt)
  return report
}

export async function main(): Promise<number> {
  const { supabaseUrl, supabaseServiceKey } = loadConfig(process.env)
  const store = createSupabaseClient(supabaseUrl, supabaseServiceKey)
  await store.upsertSources(SOURCES)
  const gameFinder = createGameFinder(store)
  const httpErrorsBySource = new Map<string, Record<string, number>>()

  const adapters = Object.fromEntries(
    (['shopify', 'woocommerce', 'wordpress', 'html', 'headless', 'gemini', 'news', 'roundups'] as SourceKind[]).map(
      (kind) => [
        kind,
        async (source: Source) => {
          const bucket: Record<string, number> = {}
          httpErrorsBySource.set(source.id, bucket)
          const trackedHttp = createTrackedHttpClient(defaultFetch, bucket)
          return buildRegistry(trackedHttp, gameFinder)[kind](source)
        },
      ],
    ),
  ) as Record<SourceKind, Adapter>

  const enrich = () =>
    runBggEnrichment({
      store,
      ranks: loadRanksIndex(['data/boardgames_ranks.csv', 'boardgames_ranks.csv', '../boardgames_ranks.csv']),
      searchIds: async (query: string) => {
        const html = await createCurlFetcher()(bggSearchUrl(query))
        return html === null ? null : parseSearchIds(html)
      },
      fetchGeekItem: async (id: number) => {
        const res = await defaultFetch(
          `https://api.geekdo.com/api/geekitems?objectid=${id}&objecttype=thing`,
          { 'User-Agent': USER_AGENT, Accept: 'application/json' },
        )
        return res.status === 200 ? parseGeekItem(await res.text(), id) : null
      },
      skipped: createSkipStore('data/bgg-online-skipped.json'),
    })

  const report = await runCollector({
    sources: SOURCES,
    adapters,
    store,
    getSourceHttpErrors: (sourceId) => httpErrorsBySource.get(sourceId) ?? {},
    enrich,
  })
  console.log(JSON.stringify(report, null, 2))
  return report.ok ? 0 : 1
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
