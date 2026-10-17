import { expect, test } from 'vitest'
import { SOURCES } from '../src/sources.seed.js'
import { buildRegistry } from '../src/adapters/registry.js'

test('seeds all 14 sources with a known kind', () => {
  expect(SOURCES).toHaveLength(14)
  const kinds = new Set(SOURCES.map(s => s.kind))
  for (const k of kinds) expect(['shopify','woocommerce','wordpress','html','headless']).toContain(k)
})

test('registry provides an adapter for every kind', () => {
  const r = buildRegistry()
  for (const k of ['shopify','woocommerce','wordpress','html','headless'] as const) {
    expect(typeof r[k]).toBe('function')
  }
})

test('only sources with a verified working adapter are enabled', () => {
  const enabled = SOURCES.filter(s => s.enabled).map(s => s.id).sort()
  expect(enabled).toEqual(['boardhouse', 'cranio', 'ghenos', 'kosmos', 'pendragon'])
})
