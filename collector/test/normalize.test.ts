import { expect, test } from 'vitest'
import { toPrice, extractReleaseDate, normalizeListing } from '../src/normalize.js'

test('converts minor units to a decimal price', () => {
  expect(toPrice('1299', 2)).toBe(12.99)
  expect(toPrice('89.90')).toBe(89.9)
  expect(toPrice(undefined)).toBeNull()
})

test('extracts an Italian release date', () => {
  expect(extractReleaseDate('<p>In uscita il 15/03/2026</p>')).toBe('2026-03-15')
  expect(extractReleaseDate('<p>In uscita: 2026-04</p>')).toBe('2026-04-01')
  expect(extractReleaseDate('<p>Nessuna data</p>')).toBeNull()
})

test('normalizes a raw product, never inventing a date', () => {
  const l = normalizeListing('cranio', {
    externalId: '1', title: 'X', url: 'https://e.com/x', descriptionRaw: '<p>Nessuna data</p>',
  })
  expect(l).toMatchObject({ sourceId: 'cranio', price: null, currency: null, releaseDate: null })
})

test('prefers a structured release date over the parsed description', () => {
  const structured = normalizeListing('x', {
    externalId: '1', title: 'X', url: 'https://e.com/x', releaseDateRaw: '2026-10-27',
  })
  expect(structured.releaseDate).toBe('2026-10-27')
  const fallback = normalizeListing('x', {
    externalId: '1', title: 'X', url: 'https://e.com/x', releaseDateRaw: 'non-una-data',
    descriptionRaw: '<p>In uscita: 2026-04</p>',
  })
  expect(fallback.releaseDate).toBe('2026-04-01')
})
