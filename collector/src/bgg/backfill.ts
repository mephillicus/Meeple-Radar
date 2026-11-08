import type { ListingStore } from '../db.js'
import type { RanksIndex } from './csv-ranks.js'
import { matchCsv } from './csv-ranks.js'
import type { GeekItem } from './online.js'
import { buildSearchQuery, resolveGate } from './online.js'
import type { SkipStore } from './skipped.js'
import { CANDIDATE_CAP, RETRY_DELAY_MS, ONLINE_SPACING_MS } from './enrich.js'
import { buildCsvTokenIndex, itemScore, pickCertainCsv, pickCertainItems } from './match.js'

export type BackfillReport = {
  scanned: number
  linkedCsv: number
  linkedFuzzyCsv: number
  linkedOnline: number
  candidatesCaptured: number
  onlineRequests: number
  remaining: number
  stopped: boolean
  csvMissing?: boolean
}

export type CapturedCandidate = {
  bggId: number
  name: string
  yearpublished: number | null
  subtype: string
}

export type CapturedCandidates = Record<string, CapturedCandidate[]>

export async function runBggBackfill(deps: {
  store: ListingStore
  ranks: RanksIndex | null
  searchIds: (query: string) => Promise<number[] | null>
  fetchGeekItem: (id: number) => Promise<GeekItem | null>
  skipped: SkipStore
  maxOnlineRequests: number
  sleep?: (ms: number) => Promise<void>
  loadCaptured: () => CapturedCandidates
  saveCaptured: (data: CapturedCandidates) => void
}): Promise<BackfillReport> {
  const sleep = deps.sleep ?? ((ms: number) => new Promise((r) => setTimeout(r, ms)))

  if (deps.ranks === null) {
    return {
      scanned: 0, linkedCsv: 0, linkedFuzzyCsv: 0, linkedOnline: 0,
      candidatesCaptured: 0, onlineRequests: 0, remaining: 0, stopped: false, csvMissing: true,
    }
  }

  const products = await deps.store.findUnlinkedProducts()
  const tokenIndex = buildCsvTokenIndex(deps.ranks)
  let linkedCsv = 0
  let linkedFuzzyCsv = 0
  let linkedOnline = 0
  let onlineRequests = 0
  let stopped = false

  const linkGame = async (
    productId: number,
    title: string,
    bggId: number,
    kind: 'base' | 'expansion',
    rating: number | null,
    rank: number | null,
  ): Promise<boolean> => {
    const found = await deps.store.findGameByBggId(bggId)
    const gameId = found?.id ?? (await deps.store.createGame({
      name: title,
      kind,
      bggId,
      ...(rating !== null && { bggRating: rating }),
      ...(rank !== null && { bggRank: rank }),
    }))
    const written = await deps.store.setProductGame(productId, gameId)
    await deps.store.fillListingGame(productId, gameId)
    return written
  }

  // Passata CSV (bulk, zero rete): esatto-unico e refusi oltre soglia.
  const onlineQueue: typeof products = []
  for (const product of products) {
    const exact = matchCsv(deps.ranks, product.normalizedTitle)
    const row = exact ?? pickCertainCsv(deps.ranks, tokenIndex, product.normalizedTitle)
    if (row === null) {
      onlineQueue.push(product)
      continue
    }
    const written = await linkGame(
      product.id,
      product.title,
      row.bggId,
      row.isExpansion ? 'expansion' : 'base',
      row.average,
      row.rank,
    )
    if (written) {
      if (exact !== null) linkedCsv++
      else linkedFuzzyCsv++
    }
  }

  // Passata online con budget: il gate esatto di enrich, poi il certo per similarita.
  // I titoli gia falliti tre volte restano fuori: non devono intasare il
  // budget dei run successivi (il file skipped qui e separato da quello del daily).
  const captured = deps.loadCaptured()
  let candidatesCaptured = 0
  let onlineStopped = false
  for (const product of onlineQueue) {
    if (onlineRequests >= deps.maxOnlineRequests) {
      stopped = true
      break
    }
    if (deps.skipped.shouldSkip(product.normalizedTitle)) continue

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

    const chosen = resolveGate(product.title, product.releaseDate, items) ?? pickCertainItems(items, product.normalizedTitle)
    if (chosen !== null) {
      const ranked = deps.ranks.byId.get(chosen.bggId)
      const kind: 'base' | 'expansion' = ranked
        ? ranked.isExpansion ? 'expansion' : 'base'
        : chosen.subtype === 'boardgameexpansion' ? 'expansion' : 'base'
      const written = await linkGame(product.id, product.title, chosen.bggId, kind, ranked?.average ?? null, ranked?.rank ?? null)
      if (written) linkedOnline++
      continue
    }

    const snapshot = items
      .map((item) => ({ item, score: itemScore(item, product.normalizedTitle) }))
      .sort((a, b) => b.score - a.score)
      .slice(0, 3)
      .map(({ item }) => ({ bggId: item.bggId, name: item.name, yearpublished: item.yearpublished, subtype: item.subtype }))
    captured[product.normalizedTitle] = snapshot
    candidatesCaptured++
    deps.skipped.record(product.normalizedTitle)
    // Un pass lungo puo essere interrotto: senza questo flush a ogni cattura,
    // timeout o interruzioni perderebbero skip e candidati dell'intero giro.
    deps.skipped.save()
    deps.saveCaptured(captured)
  }
  deps.skipped.save()
  deps.saveCaptured(captured)

  return {
    scanned: products.length,
    linkedCsv,
    linkedFuzzyCsv,
    linkedOnline,
    candidatesCaptured,
    onlineRequests,
    remaining: products.length - linkedCsv - linkedFuzzyCsv - linkedOnline,
    stopped: stopped || onlineStopped,
  }
}
