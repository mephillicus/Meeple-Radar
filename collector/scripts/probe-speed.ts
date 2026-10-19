import { loadConfig } from '../src/config.js'
import { createSupabaseClient } from '../src/db.js'
import { buildRegistry } from '../src/adapters/registry.js'
import { SOURCES } from '../src/sources.seed.js'
import { normalizeTitle } from '../src/normalize.js'
import { canonicalizeProduct } from '../src/merge.js'
import type { Listing } from '../src/types.js'

const ms = (n: number) => `${(n / 1000).toFixed(1)}s`
const clock = (): number => performance.now()

const PER_SOURCE_LIMIT_MS = 30_000

async function main(): Promise<void> {
  process.env.MEEPLE_MAX_PRODUCTS ??= '40'

  console.log('=== A) tempo di scraping per fonte (nessun DB) ===')
  const registry = buildRegistry()
  const enabled = SOURCES.filter((s) => s.enabled && s.kind !== 'news' && s.kind !== 'gemini')
  let slowest = { id: '-', ms: 0 }
  let totalA = 0
  for (const source of enabled) {
    const t0 = clock()
    let result: string
    try {
      const raw = await Promise.race([
        registry[source.kind](source),
        new Promise<never>((_, rej) =>
          setTimeout(() => rej(new Error(`timeout ${PER_SOURCE_LIMIT_MS}ms`)), PER_SOURCE_LIMIT_MS),
        ),
      ])
      result = `${raw.length} prodotti`
    } catch (err) {
      result = `ERRORE: ${(err instanceof Error ? err.message : String(err)).slice(0, 80)}`
    }
    const took = clock() - t0
    totalA += took
    if (took > slowest.ms) slowest = { id: source.id, ms: took }
    console.log(`  ${source.id.padEnd(16)} ${ms(took).padStart(7)}  ${result}`)
  }
  console.log(`  ${'TOTALE'.padEnd(16)} ${ms(totalA).padStart(7)}   lento: ${slakestLabel()}`)
  function slakestLabel(): string {
    return `${slowest.id} (${ms(slowest.ms)})`
  }

  console.log('\n=== B) costo del loop DB per singola listing ===')
  const cfg = loadConfig(process.env)
  const store = createSupabaseClient(cfg.supabaseUrl, cfg.supabaseServiceKey)

  const titles = ['Catan', 'Ticket to Ride', 'Carcassonne', '7 Wonders', 'Wingspan', 'Azul', 'Pandemic']
  let measured = 0
  let sumMs = 0
  for (const title of titles) {
    const t0 = clock()
    const matches = await store.findListingsByTitle(normalizeTitle(title))
    const tFind = clock() - t0
    if (matches.length === 0) {
      console.log(`  "${title}" nessun match`)
      continue
    }
    const related: Listing[] = matches.map((m) => ({
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
    const canonical = canonicalizeProduct(related)
    const t1 = clock()
    const ids = canonical ? await store.upsertProducts([canonical]) : []
    const tUpsert = clock() - t1
    const t2 = clock()
    const all = canonical ? await store.findListingsByTitle(normalizeTitle(canonical.title)) : []
    const tFind2 = clock() - t2
    let tLink = 0
    const productId = ids[0]
    if (productId !== undefined) {
      for (const l of all) {
        const t3 = clock()
        await store.linkListingToProduct(l.id, productId)
        tLink += clock() - t3
      }
    }
    const tot = tFind + tUpsert + tFind2 + tLink
    measured += 1
    sumMs += tot
    console.log(
      `  "${title.padEnd(16)}" match=${String(matches.length).padStart(3)} find=${ms(tFind).padStart(6)} upsert=${ms(tUpsert).padStart(6)} find2=${ms(tFind2).padStart(6)} link=${ms(tLink).padStart(6)} x${all.length}  TOT=${ms(tot).padStart(7)}`,
    )
  }
  const avg = measured > 0 ? sumMs / measured : 0
  console.log(`\n  medio per listing: ${ms(avg)}`)
  console.log(`  560 listing (14 fonti x 40) -> ${ms(avg * 560)}`)
}

main().catch((err: unknown) => {
  console.error(err instanceof Error ? err.message : String(err))
  process.exitCode = 1
})
