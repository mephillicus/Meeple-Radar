import { buildRegistry } from '../src/adapters/registry.js'
import { SOURCES } from '../src/sources.seed.js'

async function main(): Promise<void> {
  const id = process.argv[2]
  if (!id) {
    console.error('usage: tsx scripts/probe-source.ts <sourceId>')
    process.exitCode = 2
    return
  }
  const source = SOURCES.find((s) => s.id === id)
  if (!source) {
    console.error(`fonte sconosciuta: ${id}`)
    process.exitCode = 2
    return
  }
  const registry = buildRegistry()
  process.env.MEEPLE_MAX_PRODUCTS ??= '40'
  const items = await registry[source.kind](source)
  console.log(
    JSON.stringify({ id, kind: source.kind, count: items.length, sample: items.slice(0, 3) }, null, 2),
  )
}

main().catch((err: unknown) => {
  console.error(err instanceof Error ? err.message : String(err))
  process.exitCode = 1
})
