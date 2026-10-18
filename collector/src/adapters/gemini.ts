import { readFile } from 'node:fs/promises'
import type { GameFinder, RawProduct, Source } from '../types.js'
import { normalizeTitle } from '../normalize.js'

const GEMINI_MODEL = 'gemini-2.0-flash'
const API_BASE = 'https://generativelanguage.googleapis.com/v1beta/models'

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
  const meseSuccessivo = mesi[(now.getMonth() + 1) % 12]
  const anno = now.getFullYear()

  const promptPath = new URL('../../prompts/upcoming-releases.txt', import.meta.url)
  let prompt = await readFile(promptPath, 'utf-8')
  prompt = prompt
    .replace(/\{MESE_CORRENTE\}/g, meseCorrente)
    .replace(/\{MESE_SUCCESSIVO\}/g, meseSuccessivo)
    .replace(/\{ANNO\}/g, String(anno))

  const res = await fetch(`${API_BASE}/${GEMINI_MODEL}:generateContent?key=${apiKey}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ contents: [{ parts: [{ text: prompt }] }] }),
  })
  if (!res.ok) throw new Error(`Gemini API: ${res.status}`)

  const data = (await res.json()) as {
    candidates?: { content?: { parts?: { text?: unknown }[] } }[] | null
  } | null
  const text = data?.candidates?.[0]?.content?.parts?.[0]?.text
  if (typeof text !== 'string') return []

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
