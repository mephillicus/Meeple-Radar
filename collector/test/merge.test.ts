import { expect, test } from 'vitest'
import { canonicalizeProduct } from '../src/merge.js'
import type { Listing } from '../src/types.js'

const base: Listing = {
  sourceId: 's1', externalId: '1', title: 'Azul', url: 'https://e.com/a',
  price: null, currency: null, availability: null, releaseDate: null,
  imageUrl: null, gameId: null, description: null,
}

test('canonicalizeProduct prende la descrizione dalla prima listing che ne ha una', () => {
  const canonical = canonicalizeProduct([
    { ...base, description: null },
    { ...base, sourceId: 's2', externalId: '2', description: 'Una descrizione' },
    { ...base, sourceId: 's3', externalId: '3', description: 'Un altra' },
  ])
  expect(canonical?.description).toBe('Una descrizione')
})

test('canonicalizeProduct ha description a null quando nessuna listing la ha', () => {
  expect(canonicalizeProduct([{ ...base }])?.description).toBeNull()
})
