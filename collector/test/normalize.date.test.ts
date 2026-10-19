import { expect, test } from 'vitest'
import { extractReleaseDate } from '../src/normalize.js'

test('parses full ISO-like month (YYYY-MM) to first day', () => {
  expect(extractReleaseDate('2026-03')).toBe('2026-03-01')
})

test('parses day/month/year formats', () => {
  expect(extractReleaseDate('05/03/2026')).toBe('2026-03-05')
  expect(extractReleaseDate('5-3-2026')).toBe('2026-03-05')
})

test('fills current year when only month number is provided', () => {
  const year = new Date().getUTCFullYear()
  const expected = `${year}-07-01`
  expect(extractReleaseDate('07')).toBeNull()
  // month with name
  expect(extractReleaseDate('luglio')).toBe(`${year}-07-01`)
  expect(extractReleaseDate('July')).toBe(`${year}-07-01`)
})

test('parses month name with day', () => {
  const year = new Date().getUTCFullYear()
  expect(extractReleaseDate('5 marzo')).toBe(`${year}-03-05`)
  expect(extractReleaseDate('March 10')).toBe(`${year}-03-10`)
})
