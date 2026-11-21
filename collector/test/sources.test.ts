import { expect, test } from 'vitest'
import { SOURCES } from '../src/sources.seed.js'
import { buildRegistry } from '../src/adapters/registry.js'

const KINDS = ['shopify', 'woocommerce', 'wordpress', 'html', 'headless', 'gemini', 'news', 'roundups'] as const

test('seeds all 19 sources with a known kind', () => {
  expect(SOURCES).toHaveLength(19)
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
  expect(enabled).toEqual(['asmodee', 'boardhouse', 'cranio', 'dungeondice', 'dvgiochi', 'gateongames', 'ghenos', 'giochiuniti', 'horribleguild', 'houseofgames', 'littlerocket', 'msedizioni', 'pendragon', 'ravensburger', 'supernova', 'uplay'])
})

test('assigns defaultPublisher only to mono-brand publisher sources', () => {
  const withPublisher = Object.fromEntries(
    SOURCES.filter((s) => s.defaultPublisher !== undefined).map((s) => [s.id, s.defaultPublisher]),
  )
  expect(withPublisher).toEqual({
    asmodee: 'Asmodee Italia',
    cranio: 'Cranio Creations',
    ghenos: 'Ghenos Games',
    dvgiochi: 'dV Giochi',
    horribleguild: 'Horrible Guild',
    pendragon: 'Pendragon Game Studio',
    gateongames: 'GateOnGames',
    msedizioni: 'MS Edizioni',
    ravensburger: 'Ravensburger',
    littlerocket: 'Little Rocket Games',
    supernova: 'Supernova',
    giochiuniti: 'Giochi Uniti',
  })
})
