import { pathToFileURL } from 'node:url'
import type { Listing, RawProduct, Source, SourceKind } from './types.js'
import { normalizeListing, normalizeTitle } from './normalize.js'
import { createSupabaseClient, type ListingStore } from './db.js'
import { loadConfig } from './config.js'
import { buildRegistry } from './adapters/registry.js'
import { createGameFinder } from './adapters/gemini.js'
import { SOURCES } from './sources.seed.js'
import { canonicalizeProduct } from './merge.js'
import { retentionCutoff } from './retention.js'

export type Adapter = (source: Source) => Promise<RawProduct[]>

// Testi generati dal titolo e non scritti da un editore: non finiscono mai su
// product.description. Restano su descriptionRaw per estrarre la data.
const GENERATED_TEXT_KINDS: ReadonlySet<SourceKind> = new Set(['news', 'gemini'])

export type RunReport = {
  ok: boolean
  perSource: Record<string, number>
  failures: Record<string, string>
  zeroed: string[]
  purged?: { listings: number; products: number }
}

export async function runCollector(args: {
  sources: Source[]
  adapters: Record<SourceKind, Adapter>
  store: ListingStore
  now?: () => Date
}): Promise<RunReport> {
  const { sources, adapters, store, now: nowFn = () => new Date() } = args
  const perSource: Record<string, number> = {}
  const failures: Record<string, string> = {}
  const zeroed: string[] = []
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

  for (const source of sources) {
    if (!source.enabled) continue
    try {
      const raw = await adapters[source.kind](source)
      if (raw.length === 0) zeroed.push(source.id)
      // Pairing raw/riga PRIMA del filtro cutoff: un filtro per indice su rows
      // separate sfalserebbe gli indici e farebbe perdere articleDescription.
      let paired = raw
        .map((r) => ({ raw: r, row: normalizeListing(source.id, r) }))
        .map((p) => (GENERATED_TEXT_KINDS.has(source.kind) ? { ...p, row: { ...p.row, description: null } } : p))
        .filter((p) => p.row.releaseDate === null || p.row.releaseDate >= cutoff)
      if (source.kind === 'roundups') {
        const fresh: typeof paired = []
        for (const p of paired) {
          const matches = await store.findListingsByTitle(normalizeTitle(p.row.title))
          if (!matches.some((m) => m.sourceId === source.id)) fresh.push(p)
        }
        paired = fresh
      }
      await store.upsertListings(paired.map((p) => p.row))

      for (const p of paired) {
        const isEditorial = source.kind === 'news' || source.kind === 'roundups'
        const { row } = p
        const matches = await store.findListingsByTitle(normalizeTitle(row.title))
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
        // sostituisce le colonne presenti nel payload. Le righe listing
        // continuano a portarli.
        const payload = (isEditorial
          ? (({ price, currency, releaseDate, ...rest }) => rest)(canonical)
          : canonical) as Listing
        const productIds = await store.upsertProducts([payload])
        const productId = productIds[0]
        if (productId === undefined) continue

        // Write-once: lo store aggiorna solo dove la colonna e ancora null,
        // quindi il guard qui sopra serve solo a non sprecare una chiamata.
        if (canonical.description) await store.fillProductDescription(productId, canonical.description)
        // La citazione verbatim dell'articolo viaggia fuori da row.description
        // (azzerato per GENERATED_TEXT_KINDS) ed entra solo se ancora assente.
        if (p.raw.articleDescription) await store.fillProductDescription(productId, p.raw.articleDescription)
        if (isEditorial && row.releaseDate) await store.fillProductReleaseDate(productId, row.releaseDate)
        if (p.raw.publisher) await store.fillProductPublisher(productId, p.raw.publisher)

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
  const report: RunReport = { ok, perSource, failures, zeroed, purged }
  await store.logRun(ok, report, startedAt)
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