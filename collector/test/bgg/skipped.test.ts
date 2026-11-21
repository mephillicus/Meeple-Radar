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
  for (let i = 1; i <= MAX_ONLINE_ATTEMPTS; i++) expect(store.record('dune')).toBe(i)
  expect(store.shouldSkip('dune')).toBe(true)
  expect(store.shouldSkip('altri')).toBe(false)
  store.save()
  expect(createSkipStore(file).shouldSkip('dune')).toBe(true)
  fs.rmSync(dir, { recursive: true, force: true })
})

test('retrocompatibile con formato legacy { [title]: number }', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'skip-legacy-'))
  const file = path.join(dir, 'skipped.json')
  fs.writeFileSync(file, JSON.stringify({ vecchio: 3, parziale: 2 }), 'utf8')
  const store = createSkipStore(file)
  expect(store.shouldSkip('vecchio')).toBe(true)
  expect(store.shouldSkip('parziale')).toBe(false)
  expect(store.record('parziale')).toBe(3)
  expect(store.shouldSkip('parziale')).toBe(true)
  store.save()
  const saved = JSON.parse(fs.readFileSync(file, 'utf8'))
  expect(saved.vecchio).toMatchObject({ attempts: 3, skippedAt: expect.any(String) })
  fs.rmSync(dir, { recursive: true, force: true })
})

test('TTL 30 giorni: voci scadute vengono riprovate', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'skip-ttl-'))
  const file = path.join(dir, 'skipped.json')
  const day0 = new Date('2026-01-01T00:00:00.000Z')
  const day29 = new Date('2026-01-30T00:00:00.000Z')
  const day31 = new Date('2026-02-01T00:00:00.000Z')

  const store0 = createSkipStore(file, { now: () => day0 })
  for (let i = 0; i < MAX_ONLINE_ATTEMPTS; i++) store0.record('nuovo gioco')
  store0.save()

  expect(createSkipStore(file, { now: () => day29 }).shouldSkip('nuovo gioco')).toBe(true)
  expect(createSkipStore(file, { now: () => day31 }).shouldSkip('nuovo gioco')).toBe(false)

  const store31 = createSkipStore(file, { now: () => day31 })
  expect(store31.record('nuovo gioco')).toBe(1)
  fs.rmSync(dir, { recursive: true, force: true })
})

test('save crea automaticamente le directory padre mancanti', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'skip-mkdir-'))
  const nestedFile = path.join(dir, 'nested', 'data', 'bgg-online-skipped.json')
  const store = createSkipStore(nestedFile)
  store.record('catan')
  store.save()
  expect(fs.existsSync(nestedFile)).toBe(true)
  fs.rmSync(dir, { recursive: true, force: true })
})

test('file corrotto -> mappa vuota, nessun throw', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'skip-'))
  const file = path.join(dir, 'skipped.json')
  fs.writeFileSync(file, '{corrotto')
  expect(createSkipStore(file).shouldSkip('x')).toBe(false)
  fs.rmSync(dir, { recursive: true, force: true })
})
