import { pathToFileURL } from 'node:url'
import { loadConfig } from './config.js'
import { createSupabaseClient, type ListingStore } from './db.js'
import {
  fetchBggCollection,
  runAutoFavorites,
  type CollectionItem,
} from './bgg/collection.js'
import { renderCollectionSyncSummaryMarkdown, writeGithubStepSummary } from './report-ui.js'

export type CollectionSyncReport = {
  ok: boolean
  synced: number
  owned: number
  wishlist: number
  autofavorite: { inserted: number; skipped: number }
  error?: string
}

export async function runCollectionSync(deps: {
  username: string
  store: ListingStore
  token?: string
  fetchCollection?: (username: string) => Promise<CollectionItem[]>
}): Promise<CollectionSyncReport> {
  const { username, store, token, fetchCollection } = deps
  try {
    const items = fetchCollection
      ? await fetchCollection(username)
      : await fetchBggCollection(username, { token })

    await store.upsertCollectionItem(items)

    const ownedBggIds = await store.collectionOwnedBggIds()
    const autofavorite = await runAutoFavorites(store, ownedBggIds)

    const owned = items.filter((i) => i.owned).length
    const wishlist = items.filter((i) => i.wishlist).length

    return {
      ok: true,
      synced: items.length,
      owned,
      wishlist,
      autofavorite,
    }
  } catch (err) {
    return {
      ok: false,
      synced: 0,
      owned: 0,
      wishlist: 0,
      autofavorite: { inserted: 0, skipped: 0 },
      error: err instanceof Error ? err.message : String(err),
    }
  }
}

export async function main(env: NodeJS.ProcessEnv = process.env): Promise<number> {
  const { supabaseUrl, supabaseServiceKey } = loadConfig(env)
  const username = env.BGG_USERNAME?.trim()
  if (!username) {
    throw new Error('BGG_USERNAME mancante')
  }
  const store = createSupabaseClient(supabaseUrl, supabaseServiceKey)
  const report = await runCollectionSync({
    username,
    token: env.BGG_API_TOKEN?.trim() || undefined,
    store,
  })
  if (report.ok) {
    console.error(
      `🎲 Collection sync completata: ${report.synced} giochi sincronizzati (${report.owned} posseduti, ${report.wishlist} wishlist, ${report.autofavorite.inserted} preferiti aggiunti)`,
    )
  } else {
    console.error(`❌ Collection sync fallita: ${report.error ?? 'errore sconosciuto'}`)
  }
  writeGithubStepSummary(renderCollectionSyncSummaryMarkdown(report))
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
