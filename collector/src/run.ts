import type { RawProduct, Source, SourceKind } from './types.js'
import { normalizeListing } from './normalize.js'
import type { ListingStore } from './db.js'

export type Adapter = (source: Source) => Promise<RawProduct[]>

export type RunReport = {
  ok: boolean
  perSource: Record<string, number>
  failures: Record<string, string>
}

export async function runCollector(args: {
  sources: Source[]
  adapters: Record<SourceKind, Adapter>
  store: ListingStore
}): Promise<RunReport> {
  const { sources, adapters, store } = args
  const perSource: Record<string, number> = {}
  const failures: Record<string, string> = {}

  for (const source of sources) {
    if (!source.enabled) continue
    try {
      const raw = await adapters[source.kind](source)
      const rows = raw.map((r) => normalizeListing(source.id, r))
      await store.upsertListings(rows)
      await store.markSourceScanned(source.id)
      perSource[source.id] = raw.length
    } catch (err) {
      failures[source.id] = err instanceof Error ? err.message : String(err)
    }
  }

  const ok = Object.keys(failures).length === 0
  const report: RunReport = { ok, perSource, failures }
  await store.logRun(ok, report)
  return report
}
