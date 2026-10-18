import { readFile } from 'node:fs/promises'
import type { GameFinder, GameMatch, RawProduct, Source } from '../types.js'
import type { ListingRow, ListingStore } from '../db.js'
import { normalizeTitle } from '../normalize.js'

// `gemini-2.0-flash` è stato dismesso il 2026-06-01 (404). Default a un modello GA
// corrente, sovrascrivibile con la env `GEMINI_MODEL` senza toccare il codice.
const GEMINI_MODEL = process.env.GEMINI_MODEL ?? 'gemini-3.6-flash'
const API_BASE = 'https://generativelanguage.googleapis.com/v1beta/models'

// Il modello può rispondere 429/503 (limite o "high demand" temporanea): ritenta con backoff.
const RETRYABLE_STATUSES = new Set([429, 503])
const MAX_ATTEMPTS = 3

async function postWithRetry(url: string, init: RequestInit): Promise<Response> {
  for (let attempt = 0; ; attempt++) {
    const res = await fetch(url, init)
    if (res.ok || !RETRYABLE_STATUSES.has(res.status) || attempt >= MAX_ATTEMPTS - 1) return res
    await new Promise<void>((resolve) => setTimeout(resolve, 1000 * 2 ** attempt))
  }
}

export async function fetchGeminiProducts(
  source: Source,
  gameFinder?: GameFinder,
): Promise<RawProduct[]> {
  const apiKey = process.env.GEMINI_API_KEY
  if (!apiKey) throw new Error('GEMINI_API_KEY non impostata')

  const now = new Date()
  const mesi = [
    'gennaio', 'febbraio', 'marzo', 'aprile', 'maggio', 'giugno',
    'luglio', 'agosto', 'settembre', 'ottobre', 'novembre', 'dicembre',
  ]
  const meseCorrente = mesi[now.getMonth()]
  const prossimo = new Date(now)
  prossimo.setMonth(now.getMonth() + 1)
  const meseSuccessivo = mesi[prossimo.getMonth()]
  const anno = prossimo.getFullYear()

  const promptPath = new URL('../../prompts/upcoming-releases.txt', import.meta.url)
  let prompt = await readFile(promptPath, 'utf-8')
  prompt = prompt
    .replace(/\{MESE_CORRENTE\}/g, meseCorrente)
    .replace(/\{MESE_SUCCESSIVO\}/g, meseSuccessivo)
    .replace(/\{ANNO\}/g, String(anno))

  const res = await postWithRetry(`${API_BASE}/${GEMINI_MODEL}:generateContent?key=${apiKey}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      contents: [{ parts: [{ text: prompt }] }],
      generationConfig: { responseMimeType: 'application/json' },
    }),
  })
  if (!res.ok) {
    let detail = ''
    try {
      detail = (await res.text()).slice(0, 300)
    } catch {
      // corpo non leggibile: ci accontentiamo dello status
    }
    throw new Error(`Gemini API: ${res.status}${detail ? ` ${detail}` : ''}`)
  }

  let data: {
    candidates?: { content?: { parts?: { text?: unknown; thought?: unknown }[] } }[] | null
  } | null = null
  try {
    data = await res.json()
  } catch {
    return []
  }
  // I modelli 3.x "pensano" per default: scarta le parti di ragionamento e tieni il testo.
  const parts = data?.candidates?.[0]?.content?.parts ?? []
  const text = parts
    .filter((p) => p && p.thought !== true && typeof p.text === 'string')
    .map((p) => p.text as string)
    .join('')
  if (text === '') return []

  let parsed: unknown
  try {
    parsed = JSON.parse(text.replace(/^```json\s*/, '').replace(/\s*```$/, ''))
  } catch {
    return []
  }
  if (!Array.isArray(parsed)) return []

  const out: RawProduct[] = []
  for (const entry of parsed as unknown[]) {
    if (!entry || typeof entry !== 'object') continue
    const e = entry as {
      title?: unknown; publisher?: unknown; articleCode?: unknown
      price?: unknown; releaseDate?: unknown; description?: unknown
    }
    if (typeof e.title !== 'string' || e.title.trim() === '') continue
    const title = e.title.trim()

    const raw: RawProduct = {
      externalId: typeof e.articleCode === 'string' && e.articleCode.trim() !== ''
        ? e.articleCode.trim()
        : normalizeTitle(title).replace(/\s+/g, '-'),
      title,
      url: `https://duckduckgo.com/?q=${encodeURIComponent(
        `${title} ${typeof e.publisher === 'string' ? e.publisher : ''}`.trim(),
      )}`,
    }

    if (typeof e.price === 'string' && e.price.trim() !== '') {
      const { raw: priceRaw, currency } = parsePrice(e.price)
      raw.priceRaw = priceRaw
      if (currency) raw.currencyRaw = currency
    }
    if (typeof e.releaseDate === 'string' && e.releaseDate.trim() !== '') {
      raw.releaseDateRaw = e.releaseDate.trim()
    }
    if (typeof e.description === 'string' && e.description.trim() !== '') {
      raw.descriptionRaw = e.description.trim()
    }

    if (gameFinder) {
      const match = await gameFinder(
        normalizeTitle(title),
        typeof e.publisher === 'string' ? e.publisher : undefined,
      )
      if (match) {
        if (match.price !== null) raw.priceRaw = String(match.price)
        if (match.currency !== null) raw.currencyRaw = match.currency
        if (match.releaseDate !== null) raw.releaseDateRaw = match.releaseDate
        if (match.imageUrl !== null) raw.imageUrl = match.imageUrl
        if (match.gameId !== null) raw.gameId = match.gameId
      }
    }

    out.push(raw)
  }
  return out
}

export function createGameFinder(store: ListingStore): GameFinder {
  let sourceIdByNameCache: Record<string, string> | null = null

  async function loadSourceIdByName(): Promise<Record<string, string>> {
    if (sourceIdByNameCache !== null) return sourceIdByNameCache
    const sources = await store.findSources()
    const map: Record<string, string> = {}
    for (const s of sources) map[normalizeTitle(s.name)] = s.id
    sourceIdByNameCache = map
    return map
  }

  return async (normalizedTitle, publisherHint) => {
    const rows = await store.findListingsByTitle(normalizedTitle)
    if (rows.length === 0) return null
    if (rows.length === 1) return listingToMatch(rows[0])
    if (publisherHint !== undefined && publisherHint !== '') {
      const byName = await loadSourceIdByName()
      const targetSourceId = byName[normalizeTitle(publisherHint)]
      if (targetSourceId !== undefined && targetSourceId !== '') {
        const hits = rows.filter((row) => row.sourceId === targetSourceId)
        if (hits.length === 1) return listingToMatch(hits[0])
      }
    }
    return null
  }
}

function listingToMatch(row: ListingRow): GameMatch {
  return {
    gameId: row.gameId,
    price: row.price,
    currency: row.currency,
    releaseDate: row.releaseDate,
    imageUrl: row.imageUrl,
  }
}

function parsePrice(value: string): { raw: string; currency?: string } {
  let currency: string | undefined
  if (/€/.test(value) || /\bEUR\b/i.test(value)) currency = 'EUR'
  const raw = value
    .replace(/[€$£¥]/g, '')
    .replace(/\b(EUR|USD|GBP|CHF)\b/ig, '')
    .replace(/\s+/g, ' ')
    .trim()
  return { raw, currency }
}
