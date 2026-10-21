import { expect, test } from 'vitest'
import { SOURCES } from '../src/sources.seed.js'
import { buildRegistry } from '../src/adapters/registry.js'

const KINDS = ['shopify', 'woocommerce', 'wordpress', 'html', 'headless', 'gemini', 'news'] as const

test('seeds all 18 sources with a known kind', () => {
  expect(SOURCES).toHaveLength(18)
  const kinds = new Set(SOURCES.map(s => s.kind))
  for (const k of kinds) expect(KINDS).toContain(k)
})

test('registry provides an adapter for every kind', () => {
  const r = buildRegistry()
  for (const k of KINDS) {
    expect(typeof r[k]).toBe('function')
  }
})

test('only sources with a verified working adapter are enabled', () => {
  const enabled = SOURCES.filter(s => s.enabled).map(s => s.id).sort()
  expect(enabled).toEqual(['asmodee', 'boardhouse', 'cranio', 'dungeondice', 'dvgiochi', 'gateongames', 'ghenos', 'giochiuniti', 'google-news', 'horribleguild', 'littlerocket', 'msedizioni', 'pendragon', 'supernova'])
})
