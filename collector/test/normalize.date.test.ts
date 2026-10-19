import { expect, test } from 'vitest'
import { extractReleaseDate } from '../src/normalize.js'

test('parses full ISO-like month (YYYY-MM) to first day', () => {
  expect(extractReleaseDate('2026-03')).toBe('2026-03-01')
})

test('parses day/month/year formats', () => {
  expect(extractReleaseDate('05/03/2026')).toBe('2026-03-05')
  expect(extractReleaseDate('5-3-2026')).toBe('2026-03-05')
})

test('fills current year when only month name is provided', () => {
  const year = new Date().getUTCFullYear()
  expect(extractReleaseDate('luglio')).toBe(`${year}-07-01`)
  expect(extractReleaseDate('July')).toBe(`${year}-07-01`)
})

test('parses month name with day', () => {
  const year = new Date().getUTCFullYear()
  expect(extractReleaseDate('5 marzo')).toBe(`${year}-03-05`)
  expect(extractReleaseDate('March 10')).toBe(`${year}-03-10`)
})

test('keeps the day in a full ISO date instead of degrading it to the first', () => {
  expect(extractReleaseDate('Data di uscita: 2026-11-15')).toBe('2026-11-15')
})

test('takes the year that follows the month name', () => {
  expect(extractReleaseDate('uscita prevista per marzo 2027')).toBe('2027-03-01')
  expect(extractReleaseDate('dal 15 novembre 2026')).toBe('2026-11-15')
})

test('reads the phrasings Italian headlines actually use', () => {
  const year = new Date().getUTCFullYear()
  expect(extractReleaseDate('esce a novembre')).toBe(`${year}-11-01`)
  expect(extractReleaseDate('arriva a dicembre')).toBe(`${year}-12-01`)
  expect(extractReleaseDate('in libreria il 3 marzo')).toBe(`${year}-03-03`)
  expect(extractReleaseDate('uscita il 15 novembre')).toBe(`${year}-11-15`)
})