import { expect, test } from 'vitest'
import { RawProductSchema } from '../src/types.js'

test('accepts a minimal raw product', () => {
  const parsed = RawProductSchema.parse({ externalId: '1', title: 'X', url: 'https://e.com/x' })
  expect(parsed.externalId).toBe('1')
})

test('rejects a raw product without a url', () => {
  expect(() => RawProductSchema.parse({ externalId: '1', title: 'X' })).toThrow()
})
