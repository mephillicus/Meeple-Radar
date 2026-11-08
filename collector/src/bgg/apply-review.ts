import type { ListingStore } from '../db.js'
import type { RanksIndex } from './csv-ranks.js'
import type { GeekItem } from './online.js'
import { ONLINE_SPACING_MS } from './enrich.js'

export type ApplyReport = {
  applied: number
  alreadyLinked: number
  emptyRows: number
  missingProducts: number
  missingItems: number
}

// Applica le assegnazioni manuali dalla review compilata: ogni bgg_id viene
// verificato via geekitem (nome, subtype, anno) prima di creare il game e
// collegare product e listing. Le righe non applicabili restano numerate nel
// report, non vengono mai assegnate alla cieca. Più prodotti possono puntare
// allo stesso bgg_id: il game esistente viene riusato, non duplicato.
export async function applyReview(deps: {
  store: ListingStore
  ranks: RanksIndex | null
  fetchGeekItem: (id: number) => Promise<GeekItem | null>
  sleep?: (ms: number) => Promise<void>
}, rows: Array<{ productId: number; bggId: number | null }>): Promise<ApplyReport> {
  const sleep = deps.sleep ?? ((ms: number) => new Promise((r) => setTimeout(r, ms)))
  let applied = 0
  let alreadyLinked = 0
  let emptyRows = 0
  let missingProducts = 0
  let missingItems = 0

  for (const row of rows) {
    if (row.bggId === null) {
      emptyRows++
      continue
    }
    const product = await deps.store.findProductById(row.productId)
    if (product === null) {
      missingProducts++
      continue
    }
    if (product.gameId !== null) {
      alreadyLinked++
      continue
    }
    await sleep(ONLINE_SPACING_MS)
    const item = await deps.fetchGeekItem(row.bggId)
    if (item === null) {
      missingItems++
      continue
    }
    const ranked = deps.ranks?.byId.get(row.bggId) ?? null
    const kind: 'base' | 'expansion' = ranked
      ? ranked.isExpansion ? 'expansion' : 'base'
      : item.subtype === 'boardgameexpansion' ? 'expansion' : 'base'
    const found = await deps.store.findGameByBggId(row.bggId)
    const gameId = found?.id ?? (await deps.store.createGame({
      name: product.title,
      kind,
      bggId: row.bggId,
      ...(ranked ? { bggRating: ranked.average, bggRank: ranked.rank } : {}),
    }))
    if (await deps.store.setProductGame(product.id, gameId)) applied++
    await deps.store.fillListingGame(product.id, gameId)
  }

  return { applied, alreadyLinked, emptyRows, missingProducts, missingItems }
}
