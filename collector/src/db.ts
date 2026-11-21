import { createClient } from '@supabase/supabase-js'
import type { Listing, Source } from './types.js'
import type { CollectionItem } from './bgg/collection.js'
import { normalizeTitle } from './normalize.js'

export interface ListingRow {
  id: number
  title: string
  url: string
  price: number | null
  currency: string | null
  releaseDate: string | null
  imageUrl: string | null
  gameId: number | null
  sourceId: string
  productId?: number | null
}

export interface RunLogEntry {
  id: number
  ok: boolean
  details: Record<string, unknown> | null
  startedAt: string
  finishedAt: string | null
}

export interface ListingPriceHistoryRow {
  listingId: number
  price: number | null
  currency: string | null
  availability: string | null
  recordedAt: string
}

export interface FavoriteListingSnapshot {
  listingId: number
  gameId: number | null
  productId: number | null
  title: string
  url: string
  sourceId: string
  price: number | null
  currency: string | null
  availability: string | null
  history: ListingPriceHistoryRow[]
}

export interface ExpansionGameDetail {
  id: number
  name: string
  bggId: number | null
  expandsBggId: number
}

export interface UserAlertInput {
  alertType: 'back_in_stock' | 'price_drop' | 'new_expansion'
  gameId?: number | null
  productId?: number | null
  listingId?: number | null
  title: string
  payload?: Record<string, unknown>
  dedupeKey: string
}

export interface ListingStore {
  upsertSources(sources: Source[]): Promise<void>
  upsertListings(rows: Listing[]): Promise<void>
  markSourceScanned(sourceId: string): Promise<void>
  logRun(ok: boolean, details: unknown, startedAt: Date): Promise<void>
  findListingsByTitle(normalizedTitle: string): Promise<ListingRow[]>
  findListingsByTitles?(normalizedTitles: string[]): Promise<Map<string, ListingRow[]>>
  findSources(): Promise<{ id: string; name: string }[]>
  upsertProducts(products: Listing[]): Promise<number[]>
  upsertProductsBatch?(products: Listing[]): Promise<Map<string, number>>
  getProductFillState?(productId: number): { hasDescription: boolean; hasReleaseDate: boolean; hasPublisher: boolean } | undefined
  linkListingToProduct(listingId: number, productId: number): Promise<void>
  linkListingsToProduct?(listingIds: number[], productId: number): Promise<void>
  fillProductDescription(productId: number, description: string): Promise<void>
  fillProductReleaseDate(productId: number, iso: string): Promise<void>
  fillProductPublisher(productId: number, publisher: string): Promise<void>
  findUnlinkedProducts(): Promise<{ id: number; title: string; normalizedTitle: string; releaseDate: string | null }[]>
  findProductById(productId: number): Promise<{ id: number; title: string; normalizedTitle: string; releaseDate: string | null; gameId: number | null } | null>
  findGameByBggId(bggId: number): Promise<{ id: number } | null>
  createGame(game: {
    name: string
    kind: 'base' | 'expansion'
    bggId: number
    bggRating?: number | null
    bggRank?: number | null
    imageUrl?: string | null
    description?: string | null
    expandsBggId?: number | null
  }): Promise<number>
  findGamesMissingMetadata?(limit?: number): Promise<{ id: number; bggId: number; kind: 'base' | 'expansion' }[]>
  updateGameMetadata?(
    gameId: number,
    patch: {
      imageUrl?: string | null
      description?: string | null
      expandsBggId?: number | null
      bggRating?: number | null
      bggRank?: number | null
    },
  ): Promise<void>
  setProductGame(productId: number, gameId: number): Promise<boolean>
  fillListingGame(productId: number, gameId: number): Promise<number>
  upsertCollectionItem(rows: CollectionItem[]): Promise<void>
  collectionOwnedBggIds(): Promise<Set<number>>
  findExpansionGamesWithBase(): Promise<{ id: number; expands_bgg_id: number }[]>
  findExpansionGamesWithBaseDetails?(): Promise<ExpansionGameDetail[]>
  hasUserActionOnGame(gameId: number): Promise<boolean>
  insertUserAction(gameId: number, action: 'favorite' | 'ignored' | 'unfavorite'): Promise<void>
  purgeStale(before: string): Promise<{ listings: number; products: number }>
  compactUserActions?(): Promise<number>
  getRecentRunLogs?(limit: number): Promise<RunLogEntry[]>
  findFavoriteListings?(): Promise<FavoriteListingSnapshot[]>
  insertUserAlerts?(alerts: UserAlertInput[]): Promise<number>
}

type SupabaseLike = {
  from(table: string): any
  rpc?(fn: string, params?: Record<string, unknown>): any
}

const PURGE_PAGE_SIZE = 1000
const PURGE_DELETE_BATCH_SIZE = 500
const UNLINKED_PAGE_SIZE = 1000
const TITLE_QUERY_BATCH_SIZE = 150
const PRODUCT_UPSERT_BATCH_SIZE = 150

function isTransientMessage(msg: string): boolean {
  return (
    msg.includes('502') ||
    msg.includes('503') ||
    msg.includes('504') ||
    msg.includes('Bad Gateway') ||
    msg.includes('HTML') ||
    msg.includes('timeout') ||
    msg.includes('fetch failed')
  )
}

async function retryOperation<T>(fn: () => Promise<T> | T, retries = 3, delay = 1000): Promise<T> {
  let lastError: unknown
  for (let attempt = 1; attempt <= retries; attempt++) {
    try {
      const res = await fn()
      if (
        attempt < retries &&
        res !== null &&
        typeof res === 'object' &&
        'error' in res &&
        (res as { error: { message?: string } | null }).error?.message &&
        isTransientMessage(String((res as { error: { message?: string } }).error.message))
      ) {
        await new Promise((r) => setTimeout(r, delay * attempt))
        continue
      }
      return res
    } catch (err) {
      lastError = err
      const msg = err instanceof Error ? err.message : String(err)
      if (!isTransientMessage(msg) || attempt === retries) {
        throw err
      }
      await new Promise((r) => setTimeout(r, delay * attempt))
    }
  }
  throw lastError
}

function mapListingRow(row: Record<string, unknown>): ListingRow {
  return {
    id: Number(row.id),
    title: String(row.title ?? ''),
    url: String(row.url ?? ''),
    price: row.price === null || row.price === undefined ? null : Number(row.price),
    currency: row.currency === null || row.currency === undefined ? null : String(row.currency),
    releaseDate: row.release_date === null || row.release_date === undefined ? null : String(row.release_date),
    imageUrl: row.image_url === null || row.image_url === undefined ? null : String(row.image_url),
    gameId: row.game_id === null || row.game_id === undefined ? null : Number(row.game_id),
    sourceId: row.source_id === null || row.source_id === undefined ? '' : String(row.source_id),
    productId: row.product_id === null || row.product_id === undefined ? null : Number(row.product_id),
  }
}

function buildProductPayload(p: Listing, updatedAt: string): Record<string, unknown> {
  return {
    normalized_title: normalizeTitle(p.title),
    ...(p.title != null && { title: p.title }),
    url: p.url,
    ...(p.price != null && { price: p.price }),
    ...(p.currency != null && { currency: p.currency }),
    ...(p.releaseDate != null && { release_date: p.releaseDate }),
    ...(p.releaseDate == null && p.clearReleaseDate === true && { release_date: null }),
    ...(p.imageUrl != null && { image_url: p.imageUrl }),
    ...(p.gameId != null && { game_id: p.gameId }),
    sources: JSON.stringify([]),
    updated_at: updatedAt,
  }
}

export function createSupabaseStore(
  client: SupabaseLike,
  now: () => string = () => new Date().toISOString(),
): ListingStore {
  const productFillState = new Map<
    number,
    { hasDescription: boolean; hasReleaseDate: boolean; hasPublisher: boolean }
  >()

  // Lettore condiviso: createGame ci si riaggancia quando l'insert viene
  // respinto dal vincolo unique(bgg_id), per leggere la riga che ha vinto.
  const findGameByBggId = async (bggId: number): Promise<{ id: number } | null> => {
    const { data, error } = await client
      .from('game')
      .select('id')
      .eq('bgg_id', bggId)
      .maybeSingle()
    if (error) throw new Error(`findGameByBggId(${bggId}): ${error.message}`)
    if (data === null || data === undefined) return null
    return { id: Number((data as Record<string, unknown>).id) }
  }

  return {
    async upsertSources(sources: Source[]): Promise<void> {
      if (sources.length === 0) return
      const payload = sources.map((s) => ({
        id: s.id,
        name: s.name,
        kind: s.kind,
        base_url: s.baseUrl,
        adapter: s.kind,
        enabled: s.enabled,
      }))
      const { error } = await client.from('source').upsert(payload, { onConflict: 'id' })
      if (error) throw new Error(`upsert source: ${error.message}`)
    },

    async upsertListings(rows: Listing[]): Promise<void> {
      if (rows.length === 0) return
      const seen = now()
      const map = new Map<string, Listing>()
      for (const r of rows) {
        map.set(`${r.sourceId}::${r.externalId}`, r)
      }
      const uniqueRows = Array.from(map.values())
      const payload = uniqueRows.map((r) => ({
        source_id: r.sourceId,
        external_id: r.externalId,
        title: r.title,
        normalized_title: normalizeTitle(r.title),
        url: r.url,
        ...(r.price != null && { price: r.price }),
        ...(r.currency != null && { currency: r.currency }),
        ...(r.availability != null && { availability: r.availability }),
        ...(r.releaseDate != null && { release_date: r.releaseDate }),
        // Fonte autorevole senza data: azzera esplicitamente il vecchio valore.
        ...(r.releaseDate == null && r.clearReleaseDate === true && { release_date: null }),
        ...(r.imageUrl != null && { image_url: r.imageUrl }),
        ...(r.gameId != null && { game_id: r.gameId }),
        last_seen_at: seen,
      }))
      const { error } = (await retryOperation(() =>
        client.from('listing').upsert(payload, { onConflict: 'source_id,external_id' })
      )) as { error: { message: string } | null }
      if (error) throw new Error(`upsert listing: ${error.message}`)
    },

    async markSourceScanned(sourceId: string): Promise<void> {
      const { error } = await client
        .from('source')
        .update({ last_scan_at: now() })
        .eq('id', sourceId)
      if (error) throw new Error(`markSourceScanned(${sourceId}): ${error.message}`)
    },

    async logRun(ok: boolean, details: unknown, startedAt: Date): Promise<void> {
      const { error } = await client
        .from('run_log')
        .insert({
          ok,
          details,
          started_at: startedAt.toISOString(),
          finished_at: now(),
        })
      if (error) throw new Error(`logRun: ${error.message}`)
    },

    async findListingsByTitle(normalizedTitle: string): Promise<ListingRow[]> {
      const { data, error } = (await retryOperation(() =>
        client
          .from('listing')
          .select('id, title, url, price, currency, release_date, image_url, game_id, source_id, product_id')
          .eq('normalized_title', normalizedTitle)
      )) as { data: Record<string, unknown>[] | null; error: { message: string } | null }
      if (error) throw new Error(`findListingsByTitle: ${error.message}`)
      return (data ?? []).map(mapListingRow)
    },

    async findListingsByTitles(normalizedTitles: string[]): Promise<Map<string, ListingRow[]>> {
      const result = new Map<string, ListingRow[]>()
      const unique = Array.from(new Set(normalizedTitles.filter((t) => t.length > 0)))
      if (unique.length === 0) return result

      for (let i = 0; i < unique.length; i += TITLE_QUERY_BATCH_SIZE) {
        const chunk = unique.slice(i, i + TITLE_QUERY_BATCH_SIZE)
        const { data, error } = (await retryOperation(() =>
          client
            .from('listing')
            .select('id, title, normalized_title, url, price, currency, release_date, image_url, game_id, source_id, product_id')
            .in('normalized_title', chunk)
        )) as { data: Record<string, unknown>[] | null; error: { message: string } | null }
        if (error) throw new Error(`findListingsByTitles: ${error.message}`)
        for (const row of data ?? []) {
          const key =
            row.normalized_title !== null && row.normalized_title !== undefined
              ? String(row.normalized_title)
              : normalizeTitle(String(row.title ?? ''))
          const bucket = result.get(key)
          const mapped = mapListingRow(row)
          if (bucket) bucket.push(mapped)
          else result.set(key, [mapped])
        }
      }
      return result
    },

    async findSources(): Promise<{ id: string; name: string }[]> {
      const { data, error } = await client
        .from('source')
        .select('id, name')
      if (error) throw new Error(`findSources: ${error.message}`)
      return (data ?? []).map((row: Record<string, unknown>) => ({
        id: String(row.id ?? ''),
        name: String(row.name ?? ''),
      }))
    },

    async upsertProducts(products: Listing[]): Promise<number[]> {
      if (products.length === 0) return []
      const map = new Map<string, Listing>()
      for (const p of products) {
        map.set(normalizeTitle(p.title), p)
      }
      const uniqueProducts = Array.from(map.values())
      const updatedAt = now()
      const payload = uniqueProducts.map((p) => buildProductPayload(p, updatedAt))
      const { data, error } = (await retryOperation(() =>
        client
          .from('product')
          .upsert(payload, { onConflict: 'normalized_title' })
          .select('id')
      )) as { data: Record<string, unknown>[] | null; error: { message: string } | null }
      if (error) throw new Error(`upsert product: ${error.message}`)
      return (data ?? []).map((row: Record<string, unknown>) => Number(row.id))
    },

    async upsertProductsBatch(products: Listing[]): Promise<Map<string, number>> {
      const result = new Map<string, number>()
      if (products.length === 0) return result

      const dedup = new Map<string, Listing>()
      for (const p of products) {
        dedup.set(normalizeTitle(p.title), p)
      }
      const updatedAt = now()
      // PostgREST richiede che tutti gli oggetti in un singolo bulk upsert
      // abbiano lo stesso insieme di chiavi, altrimenti riempie le chiavi
      // mancanti con NULL e sovrascrive i valori esistenti in DB.
      const groups = new Map<string, Record<string, unknown>[]>()
      for (const p of dedup.values()) {
        const row = buildProductPayload(p, updatedAt)
        const shape = Object.keys(row).sort().join(',')
        const bucket = groups.get(shape)
        if (bucket) bucket.push(row)
        else groups.set(shape, [row])
      }

      for (const groupRows of groups.values()) {
        for (let i = 0; i < groupRows.length; i += PRODUCT_UPSERT_BATCH_SIZE) {
          const chunk = groupRows.slice(i, i + PRODUCT_UPSERT_BATCH_SIZE)
          const { data, error } = (await retryOperation(() =>
            client
              .from('product')
              .upsert(chunk, { onConflict: 'normalized_title' })
              .select('id, normalized_title, description, release_date, publisher')
          )) as { data: Record<string, unknown>[] | null; error: { message: string } | null }
          if (error) throw new Error(`upsertProductsBatch: ${error.message}`)
          const returned = data ?? []
          for (let j = 0; j < returned.length; j++) {
            const row = returned[j]
            const key =
              row.normalized_title !== null && row.normalized_title !== undefined
                ? String(row.normalized_title)
                : String(chunk[j]?.normalized_title ?? '')
            if (key && row.id !== null && row.id !== undefined) {
              const id = Number(row.id)
              result.set(key, id)
              productFillState.set(id, {
                hasDescription: Boolean(
                  row.description !== undefined &&
                    row.description !== null &&
                    String(row.description).trim().length > 0,
                ),
                hasReleaseDate: Boolean(
                  row.release_date !== undefined &&
                    row.release_date !== null &&
                    String(row.release_date).trim().length > 0,
                ),
                hasPublisher: Boolean(
                  row.publisher !== undefined &&
                    row.publisher !== null &&
                    String(row.publisher).trim().length > 0,
                ),
              })
            }
          }
        }
      }
      return result
    },

    getProductFillState(productId: number) {
      return productFillState.get(productId)
    },

    async linkListingToProduct(listingId: number, productId: number): Promise<void> {
      const { error } = (await retryOperation(() =>
        client
          .from('listing')
          .update({ product_id: productId })
          .eq('id', listingId)
      )) as { error: { message: string } | null }
      if (error) throw new Error(`linkListingToProduct(${listingId}, ${productId}): ${error.message}`)
    },

    async linkListingsToProduct(listingIds: number[], productId: number): Promise<void> {
      const unique = Array.from(new Set(listingIds))
      if (unique.length === 0) return
      for (let i = 0; i < unique.length; i += PURGE_DELETE_BATCH_SIZE) {
        const batch = unique.slice(i, i + PURGE_DELETE_BATCH_SIZE)
        const { error } = (await retryOperation(() =>
          client
            .from('listing')
            .update({ product_id: productId })
            .in('id', batch)
        )) as { error: { message: string } | null }
        if (error) throw new Error(`linkListingsToProduct(${productId}): ${error.message}`)
      }
    },

    async fillProductDescription(productId: number, description: string): Promise<void> {
      // L'unico scrittore della colonna. Il filtro .is("description", null)
      // e cio che rende l'operazione write-once: una descrizione presente
      // resta quella della prima fonte che l'ha portata, a qualunque costo.
      const { error } = (await retryOperation(() =>
        client
          .from('product')
          .update({ description })
          .eq('id', productId)
          .is('description', null)
      )) as { error: { message: string } | null }
      if (error) throw new Error(`fillProductDescription(${productId}): ${error.message}`)
    },

    async fillProductReleaseDate(productId: number, iso: string): Promise<void> {
      // L'unico scrittore della colonna dai fill. Il filtro .is("release_date", null)
      // e cio che rende l'operazione write-once: la data di uscita presente
      // resta quella della prima fonte che l'ha portata, a qualunque costo.
      const { error } = (await retryOperation(() =>
        client
          .from('product')
          .update({ release_date: iso })
          .eq('id', productId)
          .is('release_date', null)
      )) as { error: { message: string } | null }
      if (error) throw new Error(`fillProductReleaseDate(${productId}): ${error.message}`)
    },

    async fillProductPublisher(productId: number, publisher: string): Promise<void> {
      // L'unico scrittore della colonna dai fill. Il filtro .is("publisher", null)
      // e cio che rende l'operazione write-once: l'editore presente
      // resta quello della prima fonte che l'ha portato, a qualunque costo.
      const { error } = (await retryOperation(() =>
        client
          .from('product')
          .update({ publisher })
          .eq('id', productId)
          .is('publisher', null)
      )) as { error: { message: string } | null }
      if (error) throw new Error(`fillProductPublisher(${productId}): ${error.message}`)
    },

    async findUnlinkedProducts(): Promise<{ id: number; title: string; normalizedTitle: string; releaseDate: string | null }[]> {
      // Paginazione interna come fetchAll di purgeStale: PostgREST serve al
      // massimo max-rows righe per richiesta, quindi si avanza range per range.
      // Una pagina piu corta di UNLINKED_PAGE_SIZE e l'ultima.
      const rows: { id: number; title: string; normalizedTitle: string; releaseDate: string | null }[] = []
      let from = 0
      for (;;) {
        const { data, error } = await client
          .from('product')
          .select('id, title, normalized_title, release_date')
          .is('game_id', null)
          .order('id')
          .range(from, from + UNLINKED_PAGE_SIZE - 1)
        if (error) throw new Error(`findUnlinkedProducts: ${error.message}`)
        const page = (data ?? []) as Record<string, unknown>[]
        rows.push(...page.map((row) => ({
          id: Number(row.id),
          title: String(row.title ?? ''),
          normalizedTitle: String(row.normalized_title ?? ''),
          releaseDate: row.release_date === null || row.release_date === undefined ? null : String(row.release_date),
        })))
        if (page.length < UNLINKED_PAGE_SIZE) break
        from += page.length
      }
      return rows
    },

    findProductById: async (productId: number) => {
      const { data, error } = await client
        .from('product')
        .select('id, title, normalized_title, release_date, game_id')
        .eq('id', productId)
        .maybeSingle()
      if (error) throw new Error(`findProductById(${productId}): ${error.message}`)
      if (data === null || data === undefined) return null
      return {
        id: Number(data.id),
        title: String(data.title ?? ''),
        normalizedTitle: String(data.normalized_title ?? ''),
        releaseDate: data.release_date === null || data.release_date === undefined ? null : String(data.release_date),
        gameId: data.game_id === null || data.game_id === undefined ? null : Number(data.game_id),
      }
    },

    findGameByBggId,

    // Crea il gioco una sola volta per bgg_id: su conflitto del vincolo
    // unique(bgg_id) non si rilancia subito l'errore, ma si riaggancia alla
    // riga esistente, cosi il link punta sempre allo stesso gioco.
    async createGame(game: {
      name: string
      kind: 'base' | 'expansion'
      bggId: number
      bggRating?: number | null
      bggRank?: number | null
      imageUrl?: string | null
      description?: string | null
      expandsBggId?: number | null
    }): Promise<number> {
      const payload = {
        name: game.name,
        kind: game.kind,
        bgg_id: game.bggId,
        ...(game.bggRating != null && { bgg_rating: game.bggRating }),
        ...(game.bggRank != null && { bgg_rank: game.bggRank }),
        ...(game.imageUrl != null && { image_url: game.imageUrl }),
        ...(game.description != null && { description: game.description }),
        ...(game.expandsBggId != null && { expands_bgg_id: game.expandsBggId }),
      }
      const { data, error } = (await retryOperation(() =>
        client.from('game').insert(payload)
      )) as { data: Record<string, unknown> | Record<string, unknown>[] | null; error: { message: string } | null }
      if (!error) {
        const row = Array.isArray(data) ? data[0] : data
        if (row !== null && row !== undefined && row.id !== null && row.id !== undefined) {
          return Number(row.id)
        }
      }
      // O l'insert e fallito sul vincolo, o supabase-js v2 ha restituito
      // data: null come sugli altri insert senza .select("id"): in entrambi
      // i casi l'id si recupera rileggendo la riga per bgg_id.
      const existing = await findGameByBggId(game.bggId).catch(() => null)
      if (existing) return existing.id
      throw new Error(`createGame: ${error ? error.message : 'id non recuperabile dopo insert'}`)
    },

    async findGamesMissingMetadata(limit = 200): Promise<{ id: number; bggId: number; kind: 'base' | 'expansion' }[]> {
      if (limit <= 0) return []
      const { data, error } = (await retryOperation(() =>
        client
          .from('game')
          .select('id, bgg_id, kind')
          .not('bgg_id', 'is', null)
          .or('and(kind.eq.expansion,expands_bgg_id.is.null),image_url.is.null,description.is.null')
          .order('kind', { ascending: false })
          .order('id', { ascending: true })
          .limit(limit)
      )) as { data: Record<string, unknown>[] | null; error: { message: string } | null }
      if (error) throw new Error(`findGamesMissingMetadata: ${error.message}`)
      return (data ?? []).map((row) => ({
        id: Number(row.id),
        bggId: Number(row.bgg_id),
        kind: row.kind === 'expansion' ? 'expansion' : 'base',
      }))
    },

    async updateGameMetadata(
      gameId: number,
      patch: {
        imageUrl?: string | null
        description?: string | null
        expandsBggId?: number | null
        bggRating?: number | null
        bggRank?: number | null
      },
    ): Promise<void> {
      const payload: Record<string, unknown> = {
        ...(patch.imageUrl != null && { image_url: patch.imageUrl }),
        ...(patch.description != null && { description: patch.description }),
        ...(patch.expandsBggId != null && { expands_bgg_id: patch.expandsBggId }),
        ...(patch.bggRating != null && { bgg_rating: patch.bggRating }),
        ...(patch.bggRank != null && { bgg_rank: patch.bggRank }),
      }
      if (Object.keys(payload).length === 0) return
      const { error } = (await retryOperation(() =>
        client
          .from('game')
          .update(payload)
          .eq('id', gameId)
      )) as { error: { message: string } | null }
      if (error) throw new Error(`updateGameMetadata(${gameId}): ${error.message}`)
    },

    // Write-once sul product: il filtro .is("game_id", null) e cio che
    // rende l'operazione write-once, il primo collegamento prevale.
    async setProductGame(productId: number, gameId: number): Promise<boolean> {
      const { data, error } = (await retryOperation(() =>
        client
          .from('product')
          .update({ game_id: gameId })
          .eq('id', productId)
          .is('game_id', null)
          .select('id')
      )) as { data: Record<string, unknown>[] | null; error: { message: string } | null }
      if (error) throw new Error(`setProductGame(${productId}, ${gameId}): ${error.message}`)
      return (data ?? []).length > 0
    },

    // Write-once sulle listing del prodotto: solo le listing ancora senza
    // game_id vengono agganciate, il primo gioco assegnato prevale.
    async fillListingGame(productId: number, gameId: number): Promise<number> {
      const { data, error } = (await retryOperation(() =>
        client
          .from('listing')
          .update({ game_id: gameId })
          .eq('product_id', productId)
          .is('game_id', null)
          .select('id')
      )) as { data: Record<string, unknown>[] | null; error: { message: string } | null }
      if (error) throw new Error(`fillListingGame(${productId}, ${gameId}): ${error.message}`)
      return (data ?? []).length
    },

    async upsertCollectionItem(rows: CollectionItem[]): Promise<void> {
      if (rows.length === 0) return
      const map = new Map<number, CollectionItem>()
      for (const r of rows) {
        map.set(r.bggId, r)
      }
      const uniqueRows = Array.from(map.values())
      const payload = uniqueRows.map((r) => ({
        bgg_id: r.bggId,
        name: r.name,
        kind: r.kind,
        owned: r.owned,
        wishlist: r.wishlist,
        synced_at: now(),
      }))
      const { error } = (await retryOperation(() =>
        client
          .from('collection_item')
          .upsert(payload, { onConflict: 'bgg_id' })
      )) as { error: { message: string } | null }
      if (error) throw new Error(`upsert collection_item: ${error.message}`)
    },

    async collectionOwnedBggIds(): Promise<Set<number>> {
      const { data, error } = await client
        .from('collection_item')
        .select('bgg_id')
        .eq('owned', true)
      if (error) throw new Error(`collectionOwnedBggIds: ${error.message}`)
      return new Set(((data ?? []) as Record<string, unknown>[]).map((row) => Number(row.bgg_id)))
    },

    async findExpansionGamesWithBase(): Promise<{ id: number; expands_bgg_id: number }[]> {
      const { data, error } = await client
        .from('game')
        .select('id, expands_bgg_id')
        .eq('kind', 'expansion')
        .not('expands_bgg_id', 'is', null)
      if (error) throw new Error(`findExpansionGamesWithBase: ${error.message}`)
      return ((data ?? []) as Record<string, unknown>[]).map((row) => ({
        id: Number(row.id),
        expands_bgg_id: Number(row.expands_bgg_id),
      }))
    },

    async findExpansionGamesWithBaseDetails(): Promise<ExpansionGameDetail[]> {
      const { data, error } = await client
        .from('game')
        .select('id, name, bgg_id, expands_bgg_id')
        .eq('kind', 'expansion')
        .not('expands_bgg_id', 'is', null)
      if (error) throw new Error(`findExpansionGamesWithBaseDetails: ${error.message}`)
      return ((data ?? []) as Record<string, unknown>[]).map((row) => ({
        id: Number(row.id),
        name: String(row.name ?? ''),
        bggId: row.bgg_id === null || row.bgg_id === undefined ? null : Number(row.bgg_id),
        expandsBggId: Number(row.expands_bgg_id),
      }))
    },

    async hasUserActionOnGame(gameId: number): Promise<boolean> {
      const { data, error } = await client
        .from('user_action')
        .select('id')
        .eq('game_id', gameId)
        .limit(1)
      if (error) throw new Error(`hasUserActionOnGame(${gameId}): ${error.message}`)
      return (data ?? []).length > 0
    },

    async insertUserAction(gameId: number, action: 'favorite' | 'ignored' | 'unfavorite'): Promise<void> {
      const { error } = await client
        .from('user_action')
        .insert({ game_id: gameId, listing_id: null, action })
      if (error) throw new Error(`insertUserAction(${gameId}, ${action}): ${error.message}`)
    },

    async compactUserActions(): Promise<number> {
      if (typeof client.rpc !== 'function') return 0
      const { data, error } = await client.rpc('compact_user_action_seen')
      if (error) throw new Error(`compactUserActions: ${error.message}`)
      return typeof data === 'number' ? data : Number(data ?? 0)
    },

    async purgeStale(before: string): Promise<{ listings: number; products: number }> {
      const fetchAll = async (
        run: (from: number, to: number) => PromiseLike<{ data: unknown; error: { message: string } | null }>,
      ): Promise<Record<string, unknown>[]> => {
        const rows: Record<string, unknown>[] = []
        let from = 0
        for (;;) {
          const { data, error } = await run(from, from + PURGE_PAGE_SIZE - 1)
          if (error) throw new Error(`purgeStale: ${error.message}`)
          const page = (data ?? []) as Record<string, unknown>[]
          if (page.length === 0) break
          rows.push(...page)
          from += page.length
        }
        return rows
      }

      // PostgREST mette gli id nella query string: troppi id in una chiamata
      // sola superano il limite di Kong e la richiesta fallisce con 414.
      const deleteInBatches = async (table: string, ids: number[]): Promise<void> => {
        for (let i = 0; i < ids.length; i += PURGE_DELETE_BATCH_SIZE) {
          const batch = ids.slice(i, i + PURGE_DELETE_BATCH_SIZE)
          const { error } = await client.from(table).delete().in('id', batch)
          if (error) throw new Error(`purgeStale: ${error.message}`)
        }
      }

      const dated = await fetchAll((from, to) =>
        client.from('listing').select('id')
          .not('release_date', 'is', null).lt('release_date', before).range(from, to))
      const undated = await fetchAll((from, to) =>
        client.from('listing').select('id')
          .is('release_date', null).lt('last_seen_at', before).range(from, to))
      const stale = [...dated, ...undated].map((row) => Number(row.id))

      const favoriteListings = await fetchAll((from, to) =>
        client.from('user_action').select('listing_id')
          .eq('action', 'favorite').not('listing_id', 'is', null).range(from, to))
      const favoriteIds = new Set(favoriteListings.map((row) => Number(row.listing_id)))
      const toDelete = stale.filter((id) => !favoriteIds.has(id))
      await deleteInBatches('listing', toDelete)

      const staleProducts = await fetchAll((from, to) =>
        client.from('product').select('id').lt('release_date', before).range(from, to))
      const staleProductIds = staleProducts.map((row) => Number(row.id))

      const favoriteProducts = await fetchAll((from, to) =>
        client.from('user_action').select('product_id')
          .eq('action', 'favorite').not('product_id', 'is', null).range(from, to))
      const favoriteProductIds = new Set(favoriteProducts.map((row) => Number(row.product_id)))

      const referenced = await fetchAll((from, to) =>
        client.from('listing').select('product_id')
          .not('product_id', 'is', null).range(from, to))
      const referencedIds = new Set(referenced.map((row) => Number(row.product_id)))

      const orphan = staleProductIds.filter((id) => !referencedIds.has(id) && !favoriteProductIds.has(id))
      await deleteInBatches('product', orphan)

      // Compattazione best-effort delle azioni 'seen' duplicate (P1.2).
      if (typeof client.rpc === 'function') {
        try {
          await client.rpc('compact_user_action_seen')
        } catch {
          // Best-effort: la migrazione potrebbe non essere ancora applicata.
        }
      }

      return { listings: toDelete.length, products: orphan.length }
    },

    async getRecentRunLogs(limit: number): Promise<RunLogEntry[]> {
      if (limit <= 0) return []
      const { data, error } = await client
        .from('run_log')
        .select('id, ok, details, started_at, finished_at')
        .order('started_at', { ascending: false })
        .limit(limit)
      if (error) throw new Error(`getRecentRunLogs: ${error.message}`)
      return ((data ?? []) as Record<string, unknown>[]).map((row) => ({
        id: Number(row.id),
        ok: Boolean(row.ok),
        details: (row.details && typeof row.details === 'object' ? row.details : null) as Record<string, unknown> | null,
        startedAt: String(row.started_at ?? ''),
        finishedAt: row.finished_at === null || row.finished_at === undefined ? null : String(row.finished_at),
      }))
    },

    async findFavoriteListings(): Promise<FavoriteListingSnapshot[]> {
      const { data: actionRows, error: actionErr } = await client
        .from('user_action')
        .select('id, game_id, product_id, listing_id, action, created_at')
        .in('action', ['favorite', 'unfavorite'])
        .order('created_at', { ascending: false })
        .order('id', { ascending: false })
      if (actionErr) throw new Error(`findFavoriteListings(user_action): ${actionErr.message}`)

      const seenTarget = new Set<string>()
      const favGameIds = new Set<number>()
      const favProductIds = new Set<number>()
      const favListingIds = new Set<number>()
      for (const row of (actionRows ?? []) as Record<string, unknown>[]) {
        const g = row.game_id != null ? Number(row.game_id) : null
        const p = row.product_id != null ? Number(row.product_id) : null
        const l = row.listing_id != null ? Number(row.listing_id) : null
        const key = `${g ?? ''}:${p ?? ''}:${l ?? ''}`
        if (seenTarget.has(key)) continue
        seenTarget.add(key)
        if (row.action === 'favorite') {
          if (g != null) favGameIds.add(g)
          if (p != null) favProductIds.add(p)
          if (l != null) favListingIds.add(l)
        }
      }

      const listingsById = new Map<number, Record<string, unknown>>()
      const collectByCol = async (col: 'game_id' | 'product_id' | 'id', ids: number[]): Promise<void> => {
        for (let i = 0; i < ids.length; i += TITLE_QUERY_BATCH_SIZE) {
          const chunk = ids.slice(i, i + TITLE_QUERY_BATCH_SIZE)
          const { data, error } = await client
            .from('listing')
            .select('id, game_id, product_id, title, url, source_id, price, currency, availability')
            .in(col, chunk)
          if (error) throw new Error(`findFavoriteListings(listing.${col}): ${error.message}`)
          for (const r of (data ?? []) as Record<string, unknown>[]) {
            listingsById.set(Number(r.id), r)
          }
        }
      }
      await collectByCol('game_id', Array.from(favGameIds))
      await collectByCol('product_id', Array.from(favProductIds))
      await collectByCol('id', Array.from(favListingIds))

      const listingIds = Array.from(listingsById.keys())
      if (listingIds.length === 0) return []

      const historyByListing = new Map<number, ListingPriceHistoryRow[]>()
      for (let i = 0; i < listingIds.length; i += TITLE_QUERY_BATCH_SIZE) {
        const chunk = listingIds.slice(i, i + TITLE_QUERY_BATCH_SIZE)
        const { data, error } = await client
          .from('listing_best_price')
          .select('listing_id, current_price, previous_price, current_availability, previous_availability, recorded_at')
          .in('listing_id', chunk)
        if (error) throw new Error(`findFavoriteListings(history): ${error.message}`)
        for (const h of (data ?? []) as Record<string, unknown>[]) {
          const lid = Number(h.listing_id)
          const listingRow = listingsById.get(lid)
          const currency =
            h.currency !== null && h.currency !== undefined
              ? String(h.currency)
              : listingRow && listingRow.currency !== null && listingRow.currency !== undefined
                ? String(listingRow.currency)
                : null
          const currentPrice =
            h.current_price === null || h.current_price === undefined ? null : Number(h.current_price)
          const previousPrice =
            h.previous_price === null || h.previous_price === undefined ? null : Number(h.previous_price)
          const currentAvailability =
            h.current_availability === null || h.current_availability === undefined
              ? null
              : String(h.current_availability)
          const previousAvailability =
            h.previous_availability === null || h.previous_availability === undefined
              ? null
              : String(h.previous_availability)
          const recordedAt = String(h.recorded_at ?? '')

          const bucket: ListingPriceHistoryRow[] = [
            {
              listingId: lid,
              price: currentPrice,
              currency,
              availability: currentAvailability,
              recordedAt,
            },
          ]
          if (previousPrice !== null || previousAvailability !== null) {
            bucket.push({
              listingId: lid,
              price: previousPrice,
              currency,
              availability: previousAvailability,
              recordedAt,
            })
          }
          historyByListing.set(lid, bucket)
        }
      }

      return Array.from(listingsById.values()).map((r) => {
        const id = Number(r.id)
        return {
          listingId: id,
          gameId: r.game_id === null || r.game_id === undefined ? null : Number(r.game_id),
          productId: r.product_id === null || r.product_id === undefined ? null : Number(r.product_id),
          title: String(r.title ?? ''),
          url: String(r.url ?? ''),
          sourceId: String(r.source_id ?? ''),
          price: r.price === null || r.price === undefined ? null : Number(r.price),
          currency: r.currency === null || r.currency === undefined ? null : String(r.currency),
          availability: r.availability === null || r.availability === undefined ? null : String(r.availability),
          history: historyByListing.get(id) ?? [],
        }
      })
    },

    async insertUserAlerts(alerts: UserAlertInput[]): Promise<number> {
      if (alerts.length === 0) return 0
      const dedup = new Map<string, UserAlertInput>()
      for (const a of alerts) {
        dedup.set(a.dedupeKey, a)
      }
      const unique = Array.from(dedup.values())
      let inserted = 0
      for (let i = 0; i < unique.length; i += PRODUCT_UPSERT_BATCH_SIZE) {
        const chunk = unique.slice(i, i + PRODUCT_UPSERT_BATCH_SIZE).map((a) => ({
          alert_type: a.alertType,
          game_id: a.gameId ?? null,
          product_id: a.productId ?? null,
          listing_id: a.listingId ?? null,
          title: a.title,
          payload: a.payload ?? {},
          dedupe_key: a.dedupeKey,
        }))
        const { data, error } = (await retryOperation(() =>
          client
            .from('user_alert')
            .upsert(chunk, { onConflict: 'dedupe_key', ignoreDuplicates: true })
            .select('id')
        )) as { data: Record<string, unknown>[] | null; error: { message: string } | null }
        if (error) throw new Error(`insertUserAlerts: ${error.message}`)
        inserted += (data ?? []).length
      }
      return inserted
    },
  }
}

export function createSupabaseClient(url: string, serviceKey: string): ListingStore {
  return createSupabaseStore(createClient(url, serviceKey))
}