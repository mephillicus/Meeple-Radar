import { SOURCES } from '../src/sources.seed.js'
import { buildRegistry } from '../src/adapters/registry.js'
import { runCollector } from '../src/run.js'

const store = {
  async upsertSources() {},
  async upsertListings() {},
  async markSourceScanned() {},
  async logRun() {},
}

const report = await runCollector({ sources: SOURCES, adapters: buildRegistry(), store })
console.log(JSON.stringify(report, null, 2))
