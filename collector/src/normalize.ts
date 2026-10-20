import type { Listing, RawProduct } from './types.js'

export function normalizeTitle(title: string): string {
  return title.toLowerCase().replace(/[^\w\s]/gu, '').replace(/\s+/g, ' ').trim()
}

export function toPrice(priceRaw?: string, minorUnit?: number): number | null {
  if (priceRaw === undefined || priceRaw.trim() === '') return null
  const n = Number(priceRaw)
  if (!Number.isFinite(n)) return null
  if (minorUnit === undefined) return n
  const value = n / 10 ** minorUnit
  return Number.isFinite(value) ? value : null
}

function isValidIso(iso: string): boolean {
  const [y, m, d] = iso.split('-').map(Number)
  if (!y || !m || !d || m < 1 || m > 12 || d < 1 || d > 31) return false
  const dt = new Date(Date.UTC(y, m - 1, d))
  return dt.getUTCFullYear() === y && dt.getUTCMonth() === m - 1 && dt.getUTCDate() === d
}

export const RELEASE_YEAR_MIN_OFFSET = -1
export const RELEASE_YEAR_MAX_OFFSET = 3

function isPlausibleYear(year: number, nowYear: number): boolean {
  return year >= nowYear + RELEASE_YEAR_MIN_OFFSET && year <= nowYear + RELEASE_YEAR_MAX_OFFSET
}

function isPlausibleIso(iso: string, nowYear: number): boolean {
  return isValidIso(iso) && isPlausibleYear(Number(iso.slice(0, 4)), nowYear)
}

export function extractReleaseDate(text?: string): string | null {
  if (!text) return null
  const nowYear = new Date().getUTCFullYear()
  const accept = (iso: string): boolean => isPlausibleIso(iso, nowYear)

  const dmy = text.match(/(\d{1,2})[\/\-.](\d{1,2})[\/\-.](\d{4})/)
  if (dmy) {
    const iso = `${dmy[3]}-${dmy[2].padStart(2, '0')}-${dmy[1].padStart(2, '0')}`
    if (accept(iso)) return iso
  }

  // anno-mese-giorno: la data intera, non va degradata al primo del mese
  const ymd = text.match(/(\d{4})-(\d{1,2})-(\d{1,2})(?!\d)/)
  if (ymd) {
    const iso = `${ymd[1]}-${ymd[2].padStart(2, '0')}-${ymd[3].padStart(2, '0')}`
    if (accept(iso)) return iso
  }

  const ym = text.match(/(\d{4})-(\d{1,2})(?!\d)/)
  if (ym) {
    const iso = `${ym[1]}-${ym[2].padStart(2, '0')}-01`
    if (accept(iso)) return iso
  }

  const dm = text.match(/(\d{1,2})[\/\-.](\d{1,2})(?![\/\-.]\d)/)
  if (dm) {
    const iso = `${nowYear}-${dm[2].padStart(2, '0')}-${dm[1].padStart(2, '0')}`
    if (accept(iso)) return iso
  }

  const months: Record<string, number> = {
    gennaio: 1, gen: 1, january: 1, jan: 1,
    febbraio: 2, february: 2, febr: 2, feb: 2,
    marzo: 3, march: 3, mar: 3,
    aprile: 4, april: 4, apr: 4,
    maggio: 5, may: 5, mag: 5,
    giugno: 6, june: 6, giu: 6, jun: 6,
    luglio: 7, july: 7, lug: 7, jul: 7,
    agosto: 8, august: 8, ago: 8, aug: 8,
    settembre: 9, sept: 9, sep: 9, set: 9,
    ottobre: 10, oct: 10, ott: 10,
    novembre: 11, nov: 11,
    dicembre: 12, dec: 12, dic: 12
  }

  const monthNameRegex = new RegExp(
    '\\b(\\d{1,2})?\\s*(' + Object.keys(months).join('|') + ')\\b',
    'i',
  )
  const mn = text.match(monthNameRegex)
  if (mn) {
    const monthPart = String(months[mn[2].toLowerCase()]).padStart(2, '0')
    const after = text.slice((mn.index ?? 0) + mn[0].length)
    // Il giorno può stare prima del nome del mese ("10 marzo") o dopo ("March 10").
    const dayPart = (mn[1] ?? after.match(/^\s*(\d{1,2})\b/)?.[1] ?? '1').padStart(2, '0')
    // L'anno può seguire il mese ("marzo 2027"): senza di quello corrente si perderebbe.
    const year = Number(after.match(/\b(\d{4})\b/)?.[1] ?? nowYear)
    const iso = `${year}-${monthPart}-${dayPart}`
    if (accept(iso)) return iso
  }

  return null
}

function isoOrNull(preferred?: string, text?: string): string | null {
  if (preferred !== undefined && isPlausibleIso(preferred, new Date().getUTCFullYear())) return preferred
  return extractReleaseDate(text)
}

export function normalizeListing(sourceId: string, raw: RawProduct): Listing {
  return {
    sourceId,
    externalId: raw.externalId,
    title: raw.title,
    url: raw.url,
    price: toPrice(raw.priceRaw, raw.minorUnitRaw),
    currency: raw.currencyRaw ? raw.currencyRaw.toUpperCase() : null,
    availability: raw.availabilityRaw ?? null,
    releaseDate: isoOrNull(raw.releaseDateRaw, raw.descriptionRaw),
    imageUrl: raw.imageUrl ?? null,
    gameId: raw.gameId ?? null,
  }
}