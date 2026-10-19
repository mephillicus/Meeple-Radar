import { readFile } from 'node:fs/promises'
import type { GameFinder, GameMatch, RawProduct, Source } from '../types.js'
import type { ListingRow, ListingStore } from '../db.js'
import { normalizeTitle } from '../normalize.js'
import { buildProviders, type AiOptions, type AiResult } from './ai-providers.js'

// Stesso prompt per tutti i provider: la cascata cambia solo chi lo esegue.
export async function buildUpcomingPrompt(): Promise<string> {
  const now = new Date()
  const mesi = [
    'gennaio', 'febbraio', 'marzo', 'aprile', 'maggio', 'giugno',
    'luglio', 'agosto', 'settembre', 'ottobre', 'novembre', 'dicembre',
  ]
  const meseCorrente = mesi[now.getMonth()]
  // Si parte dal giorno 1 per evitare overflow (es. 31 gennaio + 1 mese = marzo).
  const prossimo = new Date(now.getFullYear(), now.getMonth() + 1, 1)
  const meseSuccessivo = mesi[prossimo.getMonth()]
  const anno = prossimo.getFullYear()
  const oggi = now.toLocaleDateString('it-IT', {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
    timeZone: 'Europe/Rome',
  })

  const promptPath = new URL('../../prompts/upcoming-releases.txt', import.meta.url)
  const prompt = await readFile(promptPath, 'utf-8')
  return prompt
    .replace(/\{OGGI\}/g, oggi)
    .replace(/\{MESE_CORRENTE\}/g, meseCorrente)
    .replace(/\{MESE_SUCCESSIVO\}/g, meseSuccessivo)
    .replace(/\{ANNO\}/g, String(anno))
}

export type ParsedUpcoming = {
  ok: boolean
  products: RawProduct[]
}

// Con la ricerca web Gemini non può forzare il JSON: il testo può contenere frasi attorno o
// blocchi ```json. Prima si prova il parse diretto, poi si ritaglia dal primo `{`/`[` all'ultimo
// `}`/`]` corrispondente.
function extractJson(text: string): unknown {
  const cleaned = text
    .trim()
    .replace(/^```(?:json)?\s*/i, '')
    .replace(/\s*```$/, '')
    .trim()
  try {
    return JSON.parse(cleaned)
  } catch {
    // continua con l'estrazione
  }
  const start = cleaned.search(/[{[]/)
  if (start === -1) throw new Error('nessun JSON nel testo')
  const close = cleaned[start] === '{' ? '}' : ']'
  const end = cleaned.lastIndexOf(close)
  if (end <= start) throw new Error('JSON incompleto')
  return JSON.parse(cleaned.slice(start, end + 1))
}

// `ok` distingue "risposta non interpretabile" da "array valido (anche vuoto)": solo la prima
// è un errore che merita di passare al provider successivo come fallimento tecnico.
export async function parseUpcomingJson(text: string, gameFinder?: GameFinder): Promise<ParsedUpcoming> {
  let parsed: unknown
  try {
    parsed = extractJson(text)
  } catch {
    return { ok: false, products: [] }
  }

  let entries: unknown[]
  if (Array.isArray(parsed)) {
    entries = parsed
  } else if (parsed !== null && typeof parsed === 'object') {
    // Alcuni modelli avvolgono l'array: {"releases": [...]} → lo scartolo.
    const arrays = Object.values(parsed as Record<string, unknown>).filter(Array.isArray)
    if (arrays.length !== 1) return { ok: false, products: [] }
    entries = arrays[0] as unknown[]
  } else {
    return { ok: false, products: [] }
  }

  const out: RawProduct[] = []
  for (const entry of entries) {
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
  return { ok: true, products: out }
}

// Cascata di provider LLM: Gemini (con ricerca web) → Gemini di ripiego. Si passa al
// successivo quando la chiamata fallisce (429/503/404), la risposta non è interpretabile o
// l'array è vuoto.
export async function fetchGeminiProducts(
  _source: Source,
  gameFinder?: GameFinder,
  options?: AiOptions,
): Promise<RawProduct[]> {
  const providers = buildProviders(process.env, options)
  if (providers.length === 0) {
    throw new Error('nessuna chiave LLM: imposta GEMINI_API_KEY')
  }

  const prompt = await buildUpcomingPrompt()
  const errors: string[] = []
  let anyResponded = false

  for (const provider of providers) {
    let result: AiResult
    try {
      result = await provider.call(prompt)
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err)
      errors.push(`${provider.id}: ${msg}`)
      console.error(`[llm] ${provider.id}: ${msg}`)
      continue
    }

    anyResponded = true
    if (!result.ok) {
      errors.push(`${provider.id}: ${result.reason}`)
      console.error(`[llm] ${provider.id}: ${result.reason}`)
      continue
    }

    const { ok, products } = await parseUpcomingJson(result.text, gameFinder)
    if (!ok) {
      errors.push(`${provider.id}: risposta non interpretabile`)
      // Un'anteprima della risposta rende evidente se il modello ha scritto prosa invece di JSON.
      console.error(`[llm] ${provider.id}: risposta non interpretabile: ${result.text.slice(0, 300)}`)
      continue
    }
    if (products.length === 0) {
      console.error(`[llm] ${provider.id}: nessuna uscita trovata, provo il provider successivo`)
      continue
    }
    console.error(`[llm] ${products.length} prodotti da ${provider.id}`)
    return products
  }

  if (!anyResponded) {
    throw new Error(`nessun provider LLM ha funzionato: ${errors.join(' | ')}`)
  }
  return []
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
