import type { Listing } from './types.js'

export function releaseDatePrecision(iso: string | null): number {
  if (!iso) return 0
  const parts = iso.split('-')
  if (parts.length === 3 && parts[2] && parts[2] !== '01') return 3
  if (parts.length >= 2 && parts[1] && parts[1] !== '01') return 2
  if (parts.length >= 1 && parts[0]) return 1
  return 0
}

export function chooseCanonicalField<T>(
  values: Array<T | null | undefined>,
  prefer: (v: T) => boolean = () => true,
): T | null {
  const real = values.filter((v): v is T => v !== null && v !== undefined)
  if (real.length === 0) return null
  return real.find(prefer) ?? real[0]
}

export function canonicalizeProduct(listings: Listing[]): Listing | null {
  if (listings.length === 0) return null

  const title = chooseCanonicalField(listings.map((l) => l.title)) ?? listings[0].title
  const url = chooseCanonicalField(listings.map((l) => l.url), (v) => !!v) ?? listings[0].url
  const price = chooseCanonicalField(listings.map((l) => l.price), (v) => v !== null && v !== undefined) ?? null
  const currency = chooseCanonicalField(listings.map((l) => l.currency), (v) => !!v) ?? null
  const releaseDate = listings
    .map((l) => l.releaseDate)
    .filter((v): v is string => !!v)
    .sort((a, b) => releaseDatePrecision(b) - releaseDatePrecision(a))[0] ?? null
  const imageUrl = chooseCanonicalField(listings.map((l) => l.imageUrl), (v) => !!v) ?? null
  const gameId = chooseCanonicalField(listings.map((l) => l.gameId), (v) => v !== null && v !== undefined) ?? null

  return {
    sourceId: listings[0].sourceId,
    externalId: listings[0].externalId,
    title,
    url,
    price,
    currency,
    availability: chooseCanonicalField(listings.map((l) => l.availability), (v) => !!v) ?? null,
    releaseDate,
    imageUrl,
    gameId,
  }
}