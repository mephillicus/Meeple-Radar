import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { buildProviders } from '../../src/adapters/ai-providers.js'

const KEYS = ['GEMINI_API_KEY', 'GROQ_API_KEY', 'OPENROUTER_API_KEY', 'GEMINI_MODEL', 'GROQ_MODEL', 'OPENROUTER_MODEL']

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

test('ordina i provider Gemini → Groq → OpenRouter', () => {
  const env = {
    GEMINI_API_KEY: 'g', GROQ_API_KEY: 'k', OPENROUTER_API_KEY: 'o',
  } as NodeJS.ProcessEnv
  expect(buildProviders(env).map((p) => p.id)).toEqual(['gemini', 'groq', 'openrouter'])
})

test('salta i provider senza chiave', () => {
  const env = { GROQ_API_KEY: 'k' } as NodeJS.ProcessEnv
  expect(buildProviders(env).map((p) => p.id)).toEqual(['groq'])
})

test('usa i modelli di default', () => {
  const env = {
    GEMINI_API_KEY: 'g', GROQ_API_KEY: 'k', OPENROUTER_API_KEY: 'o',
  } as NodeJS.ProcessEnv
  const models = Object.fromEntries(buildProviders(env).map((p) => [p.id, p.model]))
  expect(models).toEqual({
    gemini: 'gemini-3.6-flash',
    groq: 'openai/gpt-oss-120b',
    openrouter: 'openrouter/free',
  })
})

test('rispetta i modelli sovrascritti da env', () => {
  const env = {
    GEMINI_API_KEY: 'g', GEMINI_MODEL: 'gemini-3.5-flash-lite',
    GROQ_API_KEY: 'k', GROQ_MODEL: 'qwen/qwen3.8-27b',
    OPENROUTER_API_KEY: 'o', OPENROUTER_MODEL: 'nvidia/nemotron-3-super-120b-a12b:free',
  } as NodeJS.ProcessEnv
  const models = Object.fromEntries(buildProviders(env).map((p) => [p.id, p.model]))
  expect(models.gemini).toBe('gemini-3.5-flash-lite')
  expect(models.groq).toBe('qwen/qwen3.8-27b')
  expect(models.openrouter).toBe('nvidia/nemotron-3-super-120b-a12b:free')
})

test('rifiuta un modello OpenRouter a pagamento', () => {
  vi.spyOn(console, 'error').mockImplementation(() => {})
  const env = { OPENROUTER_API_KEY: 'o', OPENROUTER_MODEL: 'anthropic/claude-sonnet-4' } as NodeJS.ProcessEnv
  expect(buildProviders(env)).toEqual([])
  expect(console.error).toHaveBeenCalledWith(expect.stringContaining('non free'))
})

test('accetta il router openrouter/free e un modello :free', () => {
  const router = buildProviders({ OPENROUTER_API_KEY: 'o' } as NodeJS.ProcessEnv)
  expect(router.map((p) => p.model)).toEqual(['openrouter/free'])

  const specifico = buildProviders({
    OPENROUTER_API_KEY: 'o',
    OPENROUTER_MODEL: 'google/gemma-4-31b-it:free',
  } as NodeJS.ProcessEnv)
  expect(specifico.map((p) => p.model)).toEqual(['google/gemma-4-31b-it:free'])
})
