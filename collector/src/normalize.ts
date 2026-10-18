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

export function extractReleaseDate(text?: string): string | null {
  if (!text) return null
  const dmy = text.match(/(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{4})/)
  if (dmy) {
    const iso = `${dmy[3]}-${dmy[2].padStart(2, '0')}-${dmy[1].padStart(2, '0')}`
    if (isValidIso(iso)) return iso
  }
  const ym = text.match(/(\d{4})-(\d{1,2})(?!\d)/)
  if (ym) {
    const iso = `${ym[1]}-${ym[2].padStart(2, '0')}-01`
    if (isValidIso(iso)) return iso
  }
  return null
}

function isoOrNull(preferred?: string, text?: string): string | null {
  if (preferred !== undefined && isValidIso(preferred)) return preferred
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
