import type { ListingStore } from '../src/db.js'
import { normalizeTitle } from '../src/normalize.js'
import { SOURCES } from '../src/sources.seed.js'
import { buildRegistry } from '../src/adapters/registry.js'
import { runCollector } from '../src/run.js'

let nextId = 1

const store: ListingStore = {
  async upsertSources() {},
  async upsertListings() {},
  async markSourceScanned() {},
  async logRun() {},
  async findListingsByTitle() {
    return []
  },
  async findListingsByTitles() {
    return new Map()
  },
  async findSources() {
    return []
  },
  async upsertProducts(products) {
    return products.map(() => nextId++)
  },
  async upsertProductsBatch(products) {
    const map = new Map<string, number>()
    for (const p of products) {
      map.set(normalizeTitle(p.title), nextId++)
    }
    return map
  },
  async linkListingToProduct() {},
  async linkListingsToProduct() {},
  async fillProductDescription() {},
  async fillProductReleaseDate() {},
  async fillProductPublisher() {},
  async findUnlinkedProducts() {
    return []
  },
  async findProductById() {
    return null
  },
  async findGameByBggId() {
    return null
  },
  async createGame() {
    return nextId++
  },
  async setProductGame() {
    return true
  },
  async fillListingGame() {
    return 0
  },
  async upsertCollectionItem() {},
  async collectionOwnedBggIds() {
    return new Set()
  },
  async findExpansionGamesWithBase() {
    return []
  },
  async hasUserActionOnGame() {
    return false
  },
  async insertUserAction() {},
  async purgeStale() {
    return { listings: 0, products: 0 }
  },
}

const report = await runCollector({ sources: SOURCES, adapters: buildRegistry(), store })
console.log(JSON.stringify(report, null, 2))
