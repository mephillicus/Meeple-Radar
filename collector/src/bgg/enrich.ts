import type { ListingStore } from '../db.js'
import type { RanksIndex } from './csv-ranks.js'
import { matchCsv } from './csv-ranks.js'
import type { GeekItem } from './online.js'
import { buildSearchQuery, parseGeekItem, parseSearchIds, resolveGate } from './online.js'
import type { SkipStore } from './skipped.js'
import { normalizeTitle } from '../normalize.js'

export type BggReport = {
  candidates: number
  linked: number
  viaCsv: number
  viaSearch: number
  listingsLinked: number
  unresolved: number
  onlineRequests: number
  csvMissing: boolean
  error?: string
}

export const ONLINE_BUDGET = 10
export const CANDIDATE_CAP = 3
export const ONLINE_SPACING_MS = 2000
export const RETRY_DELAY_MS = 30000

export async function runBggEnrichment(deps: {
  store: ListingStore
  ranks: RanksIndex | null
  searchIds: (query: string) => Promise<number[] | null>
  fetchGeekItem: (id: number) => Promise<GeekItem | null>
  skipped: SkipStore
  sleep?: (ms: number) => Promise<void>
}): Promise<BggReport> {
  const sleep = deps.sleep ?? ((ms: number) => new Promise((r) => setTimeout(r, ms)))

  if (deps.ranks === null) {
    return {
      candidates: 0,
      linked: 0,
      viaCsv: 0,
      viaSearch: 0,
      listingsLinked: 0,
      unresolved: 0,
      onlineRequests: 0,
      csvMissing: true,
    }
  }

  const products = await deps.store.findUnlinkedProducts()
  let candidates = products.length
  let linked = 0
  let viaCsv = 0
  let viaSearch = 0
  let listingsLinked = 0
  let onlineRequests = 0
  let error: string | undefined

  const onlineCandidates: {
    id: number
    title: string
    normalizedTitle: string
    releaseDate: string | null
  }[] = []

  // Lato CSV (bulk, zero rete)
  for (const product of products) {
    try {
      const row = matchCsv(deps.ranks, product.normalizedTitle)
      if (row === null) {
        onlineCandidates.push(product)
        continue
      }
      let gameId: number | null = null
      const found = await deps.store.findGameByBggId(row.bggId)
      if (found === null) {
        gameId = await deps.store.createGame({
          name: product.title,
          kind: row.isExpansion ? 'expansion' : 'base',
          bggId: row.bggId,
          bggRating: row.average,
          bggRank: row.rank,
        })
      } else {
        gameId = found.id
      }
      const written = await deps.store.setProductGame(product.id, gameId)
      if (written) {
        linked++
        viaCsv++
      }
      listingsLinked += await deps.store.fillListingGame(product.id, gameId)
    } catch (err) {
      error = err instanceof Error ? err.message : String(err)
    }
  }

  // Lato online (budget)
  const toProcess = onlineCandidates.filter(
    (p) => !deps.skipped.shouldSkip(p.normalizedTitle),
  )
  let onlineStopped = false
  for (const product of toProcess) {
    if (onlineStopped || onlineRequests >= ONLINE_BUDGET) break

    await sleep(ONLINE_SPACING_MS)
    onlineRequests++
    let ids = await deps.searchIds(buildSearchQuery(product.title))

    if (ids === null) {
      await sleep(RETRY_DELAY_MS)
      onlineRequests++
      ids = await deps.searchIds(buildSearchQuery(product.title))
      if (ids === null) {
        onlineStopped = true
        break
      }
    }

    const items: GeekItem[] = []
    let itemStopped = false
    for (const id of ids.slice(0, CANDIDATE_CAP)) {
      if (itemStopped) break
      await sleep(ONLINE_SPACING_MS)
      onlineRequests++
      const item = await deps.fetchGeekItem(id)
      if (item === null) {
        itemStopped = true
        break
      }
      items.push(item)
    }
    if (itemStopped) {
      onlineStopped = true
      break
    }

    const chosen = resolveGate(product.title, product.releaseDate, items)
    if (chosen === null) {
      deps.skipped.record(product.normalizedTitle)
      continue
    }

    const bggId = chosen.bggId
    const ranked = deps.ranks.byId.get(bggId)
    let gameId: number | null = null
    const found = await deps.store.findGameByBggId(bggId)
    if (found === null) {
      const kind = ranked
        ? ranked.isExpansion ? 'expansion' : 'base'
        : chosen.subtype === 'boardgameexpansion' ? 'expansion' : 'base'
      gameId = await deps.store.createGame({
        name: product.title,
        kind,
        bggId,
        ...(ranked ? { bggRating: ranked.average, bggRank: ranked.rank } : {}),
      })
    } else {
      gameId = found.id
    }
    const written = await deps.store.setProductGame(product.id, gameId)
    if (written) {
      linked++
      viaSearch++
    }
    listingsLinked += await deps.store.fillListingGame(product.id, gameId)
  }

  await deps.skipped.save()

  return {
    candidates,
    linked,
    viaCsv,
    viaSearch,
    listingsLinked,
    unresolved: candidates - linked,
    onlineRequests,
    csvMissing: false,
    ...(error ? { error } : {}),
  }
}
