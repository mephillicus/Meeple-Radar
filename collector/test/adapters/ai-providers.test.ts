import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { buildProviders } from '../../src/adapters/ai-providers.js'

const KEYS = ['GEMINI_API_KEY', 'GEMINI_MODEL', 'GEMINI_FALLBACK_MODEL']

beforeEach(() => {
  for (const k of KEYS) delete process.env[k]
})

afterEach(() => {
  vi.restoreAllMocks()
  for (const k of KEYS) delete process.env[k]
})

test('senza chiavi non c’è alcun provider', () => {
  expect(buildProviders({} as NodeJS.ProcessEnv)).toEqual([])
})

test('una sola chiave Gemini dà due provider: primario e ripiego', () => {
  const env = { GEMINI_API_KEY: 'g' } as NodeJS.ProcessEnv
  expect(buildProviders(env).map((p) => p.id)).toEqual(['gemini', 'gemini-fallback'])
})

test('usa i modelli di default', () => {
  const env = { GEMINI_API_KEY: 'g' } as NodeJS.ProcessEnv
  expect(buildProviders(env).map((p) => [p.id, p.model])).toEqual([
    ['gemini', 'gemini-3.5-flash-lite'],
    ['gemini-fallback', 'gemini-3.6-flash'],
  ])
})

test('rispetta i modelli sovrascritti da env', () => {
  const env = {
    GEMINI_API_KEY: 'g',
    GEMINI_MODEL: 'gemini-3.6-flash',
    GEMINI_FALLBACK_MODEL: 'gemini-3.5-flash-lite',
  } as NodeJS.ProcessEnv
  expect(buildProviders(env).map((p) => [p.id, p.model])).toEqual([
    ['gemini', 'gemini-3.6-flash'],
    ['gemini-fallback', 'gemini-3.5-flash-lite'],
  ])
})

test('se il ripiego coincide col primario non si aggiunge due volte', () => {
  const env = {
    GEMINI_API_KEY: 'g',
    GEMINI_MODEL: 'gemini-3.6-flash',
    GEMINI_FALLBACK_MODEL: 'gemini-3.6-flash',
  } as NodeJS.ProcessEnv
  expect(buildProviders(env).map((p) => p.id)).toEqual(['gemini'])
})

test('il primario insiste di più del ripiego', () => {
  const providers = buildProviders({ GEMINI_API_KEY: 'g' } as NodeJS.ProcessEnv)
  expect(providers[0].attempts).toBeGreaterThan(providers[1].attempts)
})
