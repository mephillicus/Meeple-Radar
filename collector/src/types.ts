import { z } from 'zod'

export const SOURCE_KINDS = ['shopify', 'woocommerce', 'wordpress', 'html', 'headless', 'gemini', 'news'] as const
export type SourceKind = (typeof SOURCE_KINDS)[number]

export const RawProductSchema = z.object({
  externalId: z.string().min(1),
  title: z.string().min(1),
  url: z.string().url(),
  priceRaw: z.string().optional(),
  currencyRaw: z.string().optional(),
  minorUnitRaw: z.number().optional(),
  availabilityRaw: z.string().optional(),
  descriptionRaw: z.string().optional(),
  imageUrl: z.string().optional(),
  publishedAtRaw: z.string().optional(),
  releaseDateRaw: z.string().optional(),
  gameId: z.number().optional(),
})

export type RawProduct = z.infer<typeof RawProductSchema>

export type GameMatch = {
  gameId: number | null
  price: number | null
  currency: string | null
  releaseDate: string | null
  imageUrl: string | null
}

export type GameFinder = (normalizedTitle: string, publisherHint?: string) => Promise<GameMatch | null>

export type Listing = {
  sourceId: string
  externalId: string
  title: string
  url: string
  price: number | null
  currency: string | null
  availability: string | null
  releaseDate: string | null
  description: string | null
  imageUrl: string | null
  gameId: number | null
}

export type Source = {
  id: string
  name: string
  kind: SourceKind
  baseUrl: string
  enabled: boolean
  collection?: string
}
