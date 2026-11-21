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
  hydratedGames?: number
  csvMissing: boolean
  error?: string
  collection?: { synced: number; owned: number; wishlist: number; error?: string }
  autofavorite?: { inserted: number; skipped: number; error?: string }
}

export const ONLINE_BUDGET = 25
export const CANDIDATE_CAP = 3
export const ONLINE_SPACING_MS = 2000
export const RETRY_DELAY_MS = 30000
export const THING_BATCH_SIZE = 20
export const HYDRATE_GAME_LIMIT = 200

const NON_BOARDGAME_PRODUCT_RE =
  /\b(?:puzzle\s+\d+\s*pezzi|puzzle\s+2d|puzzle\s+3d|booster\s+pack|prerelease\s+box|spotlight\s+deck|starter\s+deck|extra\s+booster|display\s+\d+\s*buste|dice\s+set|dice\s+block|set\s+cancelleria|action\s+figure|pista\s+di\s+biglie|bustine\s+protettive|card\s+sleeves|playmat)\b/i

export function isNonBoardgameProduct(title: string): boolean {
  return NON_BOARDGAME_PRODUCT_RE.test(title)
}

type HydratableStore = ListingStore & {
  findGamesMissingMetadata?: (
    limit?: number,
  ) => Promise<{ id: number; bggId: number; kind: 'base' | 'expansion' }[]>
  updateGameMetadata?: (
    gameId: number,
    patch: {
      imageUrl?: string | null
      description?: string | null
      expandsBggId?: number | null
      bggRating?: number | null
      bggRank?: number | null
    },
  ) => Promise<void>
}

export async function runBggEnrichment(deps: {
  store: HydratableStore
  ranks: RanksIndex | null
  searchIds: (query: string) => Promise<number[] | null>
  fetchGeekItem?: (id: number) => Promise<GeekItem | null>
  fetchGeekItems?: (ids: number[]) => Promise<GeekItem[] | null>
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
  let hydratedGames = 0
  let error: string | undefined

  const onlineCandidates: {
    id: number
    title: string
    normalizedTitle: string
    releaseDate: string | null
  }[] = []

  const newlyCreatedCsvGames = new Map<number, number>()
  const existingCsvMatches = new Map<
    number,
    { bggId: number; average: number | null; rank: number | null }
  >()

  // Lato CSV (bulk, zero rete)
  for (const product of products) {
    try {
      const row = matchCsv(deps.ranks, product.normalizedTitle, product.title)
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
        if (!newlyCreatedCsvGames.has(row.bggId)) {
          newlyCreatedCsvGames.set(row.bggId, gameId)
        }
      } else {
        gameId = found.id
        if (!existingCsvMatches.has(gameId)) {
          existingCsvMatches.set(gameId, {
            bggId: row.bggId,
            average: row.average,
            rank: row.rank,
          })
        }
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

  // Batch metadata hydration via /xmlapi2/thing per game creati da CSV o già esistenti con metadati mancanti.
  // Non consuma ONLINE_BUDGET (riservato alle ricerche searchIds/fetchGeekItem dei prodotti senza match CSV).
  const hydratedGameIds = new Set<number>()
  if (deps.fetchGeekItems && deps.store.updateGameMetadata) {
    try {
      const gameIdByBggId = new Map<number, number>()
      for (const [bggId, gameId] of newlyCreatedCsvGames) {
        if (gameIdByBggId.size >= HYDRATE_GAME_LIMIT) break
        gameIdByBggId.set(bggId, gameId)
      }
      if (gameIdByBggId.size < HYDRATE_GAME_LIMIT && deps.store.findGamesMissingMetadata) {
        const missing = await deps.store.findGamesMissingMetadata(HYDRATE_GAME_LIMIT)
        for (const g of missing) {
          if (gameIdByBggId.size >= HYDRATE_GAME_LIMIT) break
          if (Number.isInteger(g.bggId) && g.bggId > 0 && !gameIdByBggId.has(g.bggId)) {
            gameIdByBggId.set(g.bggId, g.id)
          }
        }
      }

      const bggIdsToHydrate = [...gameIdByBggId.keys()]
      for (let i = 0; i < bggIdsToHydrate.length; i += THING_BATCH_SIZE) {
        const chunk = bggIdsToHydrate.slice(i, i + THING_BATCH_SIZE)
        await sleep(ONLINE_SPACING_MS)
        const items = await deps.fetchGeekItems(chunk)
        if (items === null) break
        for (const item of items) {
          const targetGameId = gameIdByBggId.get(item.bggId)
          if (targetGameId === undefined) continue
          const ranked = deps.ranks.byId.get(item.bggId)
          await deps.store.updateGameMetadata(targetGameId, {
            imageUrl: item.imageUrl ?? null,
            description: item.description ?? null,
            expandsBggId: item.expandsBggId ?? null,
            bggRating: ranked?.average ?? item.bggRating ?? null,
            bggRank: ranked?.rank ?? item.bggRank ?? null,
          })
          hydratedGameIds.add(targetGameId)
          hydratedGames++
        }
      }
    } catch (err) {
      error = err instanceof Error ? err.message : String(err)
    }
  }

  // Aggiorna rating/rank settimanali dai CSV per i game esistenti matchati che non sono già stati idratati sopra.
  if (deps.store.updateGameMetadata) {
    for (const [gameId, info] of existingCsvMatches) {
      if (hydratedGameIds.has(gameId)) continue
      if (info.average === null && info.rank === null) continue
      try {
        await deps.store.updateGameMetadata(gameId, {
          bggRating: info.average,
          bggRank: info.rank,
        })
      } catch (err) {
        error = err instanceof Error ? err.message : String(err)
      }
    }
  }

  // Lato online (budget)
  const toProcess = onlineCandidates
    .filter(
      (p) => !deps.skipped.shouldSkip(p.normalizedTitle) && !isNonBoardgameProduct(p.title),
    )
    .sort((a, b) => {
      const aDate = a.releaseDate ?? null
      const bDate = b.releaseDate ?? null
      if (aDate !== null && bDate === null) return -1
      if (aDate === null && bDate !== null) return 1
      if (aDate !== null && bDate !== null && aDate !== bDate) {
        return bDate.localeCompare(aDate)
      }
      return b.id - a.id
    })
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
    const candidateIds = ids.slice(0, CANDIDATE_CAP)
    if (deps.fetchGeekItems) {
      if (candidateIds.length > 0) {
        await sleep(ONLINE_SPACING_MS)
        onlineRequests++
        const fetched = await deps.fetchGeekItems(candidateIds)
        if (fetched === null) {
          itemStopped = true
        } else {
          items.push(...fetched)
        }
      }
    } else if (deps.fetchGeekItem) {
      for (const id of candidateIds) {
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
      const bggRating = ranked?.average ?? chosen.bggRating ?? null
      const bggRank = ranked?.rank ?? chosen.bggRank ?? null
      gameId = await deps.store.createGame({
        name: product.title,
        kind,
        bggId,
        ...(bggRating != null ? { bggRating } : {}),
        ...(bggRank != null ? { bggRank } : {}),
        ...(chosen.imageUrl != null ? { imageUrl: chosen.imageUrl } : {}),
        ...(chosen.description != null ? { description: chosen.description } : {}),
        ...(chosen.expandsBggId != null ? { expandsBggId: chosen.expandsBggId } : {}),
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
    hydratedGames,
    csvMissing: false,
    ...(error ? { error } : {}),
  }
}
