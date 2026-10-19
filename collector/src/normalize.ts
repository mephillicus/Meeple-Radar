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
  const nowYear = new Date().getUTCFullYear()

  // giorno/mese/anno o giorno-mese-anno o giorno.mese.anno (D M Y)
  const dmy = text.match(/(\d{1,2})[\/\-.](\d{1,2})[\/\-.](\d{4})/)
  if (dmy) {
    const iso = `${dmy[3]}-${dmy[2].padStart(2, '0')}-${dmy[1].padStart(2, '0')}`
    if (isValidIso(iso)) return iso
  }

  // anno-mese (YYYY-MM) -> primo giorno del mese
  const ym = text.match(/(\d{4})-(\d{1,2})(?!\d)/)
  if (ym) {
    const iso = `${ym[1]}-${ym[2].padStart(2, '0')}-01`
    if (isValidIso(iso)) return iso
  }

  // giorno/mese senza anno -> assumiamo anno corrente
  const dm = text.match(/(\d{1,2})[\/\-.](\d{1,2})(?![\/\-.]\d)/)
  if (dm) {
    const iso = `${nowYear}-${dm[2].padStart(2, '0')}-${dm[1].padStart(2, '0')}`
    if (isValidIso(iso)) return iso
  }

  // mese (nome) con o senza giorno, in italiano o inglese
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
    const dayPart = mn[1] ? mn[1].padStart(2, '0') : '01'
    const monthPart = String(months[mn[2].toLowerCase()]).padStart(2, '0')
    const iso = `${nowYear}-${monthPart}-${dayPart}`
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
