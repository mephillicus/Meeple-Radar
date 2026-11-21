import { USER_AGENT, FETCH_TIMEOUT_MS } from '../adapters/http.js'
import type { ListingStore } from '../db.js'
import { decodeHtml } from '../text.js'

export type CollectionItem = {
  bggId: number
  name: string
  kind: 'base' | 'expansion'
  owned: boolean
  wishlist: boolean
  expandsBggId?: number | null
}

export type BggFetchOptions = {
  token?: string
  fetch?: typeof globalThis.fetch
  sleep?: (ms: number) => Promise<void>
  maxRetries?: number
  baseDelayMs?: number
  batchSize?: number
}

// Legge la collezione pubblica BGG da XML API 2 (collection?stats=1).
// Nessun DOMParser (non disponibile in tutti gli ambienti test): si usano
// espressioni regolari mirate, come fa parseSitemapEntries in sitemap.ts.
const ITEM_RE = /<item\b[^>]*>([\s\S]*?)<\/item>/gi
const OBJECTID_RE = /\bobjectid="(\d+)"/i
const ITEM_ID_RE = /<item\b[^>]*\b(?:id|objectid)="(\d+)"/i
const SUBTYPE_RE = /\bsubtype="([^"]*)"/i
const OWN_RE = /\bown="1"/i
const WISHLIST_RE = /\bwishlist="1"/i
const NAME_RE = /<name\b[^>]*>(?:<!\[CDATA\[)?\s*([^<]*?)\s*(?:\]\]>)?\s*<\/name>/i
const QUEUED_MESSAGE_RE = /<message\b[^>]*>[\s\S]*?<\/message>/i

export const THING_BATCH_SIZE = 20
const DEFAULT_MAX_RETRIES = 4
const DEFAULT_BASE_DELAY_MS = 1500

const defaultSleep = (ms: number): Promise<void> =>
  new Promise((resolve) => setTimeout(resolve, ms))

export function parseCollectionXml(xml: string): CollectionItem[] {
  const out: CollectionItem[] = []
  for (const m of xml.matchAll(ITEM_RE)) {
    const full = m[0]
    const block = m[1]
    const bggId = Number(full.match(OBJECTID_RE)?.[1])
    if (!Number.isFinite(bggId) || bggId <= 0) continue
    const subtype = full.match(SUBTYPE_RE)?.[1] ?? ''
    const rawName = block.match(NAME_RE)?.[1]?.trim() ?? ''
    const name = decodeHtml(rawName).trim()
    const owned = OWN_RE.test(full)
    const wishlist = WISHLIST_RE.test(full)
    out.push({
      bggId,
      name,
      kind: subtype === 'boardgameexpansion' ? 'expansion' : 'base',
      owned,
      wishlist,
    })
  }
  return out
}

// Estrae il gioco base di un'espansione da xmlapi2/thing:
// <link type="boardgameexpansion" id="<base>" value="<base>" inbound="true"/>
// Supporta id="..." (formato XML API2 standard) con fallback a objectid="...".
export function parseExpandsBggId(xml: string): number | null {
  const LINK_RE = /<link\b[^>]*type="boardgameexpansion"[^>]*>/gi
  const ID_RE = /\bid="(\d+)"/i
  const FALLBACK_OBJECTID_RE = /\bobjectid="(\d+)"/i
  const INBOUND_RE = /\binbound="true"/i
  for (const m of xml.matchAll(LINK_RE)) {
    if (!INBOUND_RE.test(m[0])) continue
    const idM = m[0].match(ID_RE) ?? m[0].match(FALLBACK_OBJECTID_RE)
    if (idM) return Number(idM[1])
  }
  return null
}

// Estrae la mappa expansionBggId -> baseBggId | null da una risposta XML API2
// /xmlapi2/thing contenente uno o più <item id="...">...</item>.
export function parseExpandsBggIds(xml: string): Map<number, number | null> {
  const result = new Map<number, number | null>()
  for (const m of xml.matchAll(ITEM_RE)) {
    const full = m[0]
    const block = m[1]
    const idMatch = full.match(ITEM_ID_RE)
    if (!idMatch) continue
    const itemId = Number(idMatch[1])
    if (!Number.isFinite(itemId) || itemId <= 0) continue
    result.set(itemId, parseExpandsBggId(block))
  }
  return result
}

function parseRetryAfterMs(header: string | null, fallbackMs: number): number {
  if (!header) return fallbackMs
  const trimmed = header.trim()
  const seconds = Number(trimmed)
  if (Number.isFinite(seconds) && seconds >= 0) {
    return Math.round(seconds * 1000)
  }
  const dateMs = Date.parse(trimmed)
  if (!Number.isNaN(dateMs)) {
    return Math.max(0, dateMs - Date.now())
  }
  return fallbackMs
}

function isQueuedXml(xml: string): boolean {
  return QUEUED_MESSAGE_RE.test(xml) && !/<item\b/i.test(xml)
}

export async function fetchBggXml(
  url: string,
  opts: BggFetchOptions = {},
): Promise<{ ok: true; xml: string } | { ok: false; status: number | null; error: string }> {
  const fetchFn = opts.fetch ?? globalThis.fetch
  const sleep = opts.sleep ?? defaultSleep
  const maxRetries = opts.maxRetries ?? DEFAULT_MAX_RETRIES
  const baseDelayMs = opts.baseDelayMs ?? DEFAULT_BASE_DELAY_MS
  const token = opts.token !== undefined ? opts.token : process.env.BGG_API_TOKEN

  const headers: Record<string, string> = { 'User-Agent': USER_AGENT }
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  let lastStatus: number | null = null
  let lastError = 'unknown error'

  for (let attempt = 0; attempt <= maxRetries; attempt++) {
    try {
      const res = await fetchFn(url, {
        headers,
        signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
      })
      lastStatus = res.status

      if (res.status === 202) {
        lastError = 'HTTP 202 queued'
        if (attempt < maxRetries) {
          await sleep(baseDelayMs * 2 ** attempt)
          continue
        }
        return { ok: false, status: 202, error: lastError }
      }

      if (res.status === 429) {
        lastError = 'HTTP 429 rate limited'
        if (attempt < maxRetries) {
          const retryAfter = res.headers?.get?.('retry-after') ?? null
          const delayMs = parseRetryAfterMs(retryAfter, baseDelayMs * 2 ** attempt)
          await sleep(delayMs)
          continue
        }
        return { ok: false, status: 429, error: lastError }
      }

      if (!res.ok) {
        return { ok: false, status: res.status, error: `HTTP ${res.status}` }
      }

      const xml = await res.text()
      if (isQueuedXml(xml)) {
        lastError = 'BGG XML queued <message>'
        if (attempt < maxRetries) {
          await sleep(baseDelayMs * 2 ** attempt)
          continue
        }
        return { ok: false, status: res.status, error: lastError }
      }

      return { ok: true, xml }
    } catch (err) {
      lastError = err instanceof Error ? err.message : String(err)
      if (attempt < maxRetries) {
        await sleep(baseDelayMs * 2 ** attempt)
        continue
      }
      return { ok: false, status: lastStatus, error: lastError }
    }
  }

  return { ok: false, status: lastStatus, error: lastError }
}

/**
 * Legge il gioco base per un insieme di espansioni da xmlapi2/thing in batch
 * (fino a 20 ID per richiesta), con retry e backoff su 202 / <message> / 429.
 * Best-effort: errori di rete o 401/404 non lanciano mai.
 */
export async function fetchExpandsBggIds(
  bggIds: number[],
  opts: BggFetchOptions = {},
): Promise<Map<number, number | null>> {
  const result = new Map<number, number | null>()
  const uniqueIds = Array.from(
    new Set(bggIds.filter((id) => Number.isInteger(id) && id > 0)),
  )
  if (uniqueIds.length === 0) return result

  const token = opts.token !== undefined ? opts.token : process.env.BGG_API_TOKEN
  if (!token) return result

  const sleep = opts.sleep ?? defaultSleep
  const batchSize = Math.min(THING_BATCH_SIZE, Math.max(1, opts.batchSize ?? THING_BATCH_SIZE))
  const interBatchDelayMs = opts.baseDelayMs ?? 1000

  for (let i = 0; i < uniqueIds.length; i += batchSize) {
    if (i > 0) {
      await sleep(interBatchDelayMs)
    }
    const batch = uniqueIds.slice(i, i + batchSize)
    const url = `https://boardgamegeek.com/xmlapi2/thing?id=${batch.join(',')}&type=boardgame,boardgameexpansion`
    const res = await fetchBggXml(url, { ...opts, token })
    if (!res.ok) continue
    const parsed = parseExpandsBggIds(res.xml)
    for (const id of batch) {
      result.set(id, parsed.get(id) ?? null)
    }
  }

  return result
}

export async function fetchExpandsBggId(
  bggId: number,
  opts: BggFetchOptions = {},
): Promise<number | null> {
  const map = await fetchExpandsBggIds([bggId], opts)
  return map.get(bggId) ?? null
}

/**
 * Scarica la collezione BGG di un utente (owned + wishlist) da XML API 2
 * con retry/backoff su 202 / <message> / 429 e arricchisce opzionalmente le
 * espansioni con il rispettivo gioco base via fetchExpandsBggIds in batch.
 */
export async function fetchBggCollection(
  username: string,
  opts: BggFetchOptions & { enrichExpansions?: boolean } = {},
): Promise<CollectionItem[]> {
  const url = `https://boardgamegeek.com/xmlapi2/collection?username=${encodeURIComponent(username)}&stats=1`
  const res = await fetchBggXml(url, opts)
  if (!res.ok) {
    throw new Error(`fetchBggCollection(${username}): ${res.error}`)
  }

  const items = parseCollectionXml(res.xml)
  if (opts.enrichExpansions !== false) {
    const expansionIds = items
      .filter((item) => item.kind === 'expansion')
      .map((item) => item.bggId)
    if (expansionIds.length > 0) {
      const expandsMap = await fetchExpandsBggIds(expansionIds, opts)
      for (const item of items) {
        if (item.kind === 'expansion' && expandsMap.has(item.bggId)) {
          item.expandsBggId = expandsMap.get(item.bggId) ?? null
        }
      }
    }
  }

  return items
}

/**
 * Passata auto-preferiti: per ogni espansione il cui gioco base e posseduto
 * dall'utente, inserisce user_action favorite se l'utente non ha gia una
 * azione su quel gioco (scrittura write-una-volta, mai sovrascrive).
 * Best-effort: errori non bloccano il flusso (gestiti dal chiamante).
 */
export async function runAutoFavorites(
  store: ListingStore,
  ownedBggIds: Set<number>,
): Promise<{ inserted: number; skipped: number }> {
  let inserted = 0
  let skipped = 0
  const expansions = await store.findExpansionGamesWithBase()
  for (const g of expansions) {
    if (!ownedBggIds.has(g.expands_bgg_id)) {
      skipped++
      continue
    }
    const hasAction = await store.hasUserActionOnGame(g.id)
    if (hasAction) {
      skipped++
      continue
    }
    await store.insertUserAction(g.id, 'favorite')
    inserted++
  }
  return { inserted, skipped }
}
