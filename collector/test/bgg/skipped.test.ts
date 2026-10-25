import { expect, test } from 'vitest'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createSkipStore, MAX_ONLINE_ATTEMPTS } from '../../src/bgg/skipped.js'

test('record conta, a MAX_ONLINE_ATTEMPTS shouldSkip diventa vero, save persiste', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'skip-'))
  const file = path.join(dir, 'skipped.json')
  const store = createSkipStore(file)
  expect(store.shouldSkip('dune')).toBe(false)
  // MAX_ONLINE_ATTEMPTS = 3 tentativi: registro fino a 3 per arrivare alla soglia
  for (let i = 1; i <= MAX_ONLINE_ATTEMPTS; i++) expect(store.record('dune')).toBe(i)
  expect(store.shouldSkip('dune')).toBe(true)
  expect(store.shouldSkip('altri')).toBe(false)
  store.save()
  expect(createSkipStore(file).shouldSkip('dune')).toBe(true)
  fs.rmSync(dir, { recursive: true, force: true })
})

test('file corrotto -> mappa vuota, nessun throw', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'skip-'))
  const file = path.join(dir, 'skipped.json')
  fs.writeFileSync(file, '{corrotto')
  expect(createSkipStore(file).shouldSkip('x')).toBe(false)
  fs.rmSync(dir, { recursive: true, force: true })
})
