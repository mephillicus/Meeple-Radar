import { expect, test } from 'vitest'
import { VERSION } from '../src/index.js'

test('exports a version string', () => {
  expect(VERSION).toBe('0.1.0')
})
