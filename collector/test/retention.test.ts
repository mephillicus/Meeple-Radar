import { expect, test } from 'vitest'
import { RETENTION_DAYS, retentionCutoff } from '../src/retention.js'

test('la finestra è di 365 giorni', () => {
  expect(RETENTION_DAYS).toBe(365)
})

test('taglia 365 giorni indietro rispetto a now', () => {
  expect(retentionCutoff(new Date('2026-10-20T12:00:00Z'))).toBe('2025-10-20')
})

test('attraversa anno bisestile e cambio di mese', () => {
  // 2024 e' bisestile: il giorno extra cade dentro la finestra,
  // quindi 365 indietro da 2024-03-01 finisce sul 2 marzo, non sul 1.
  expect(retentionCutoff(new Date('2024-03-01T00:00:00Z'))).toBe('2023-03-02')
  expect(retentionCutoff(new Date('2025-01-01T00:00:00Z'))).toBe('2024-01-02')
})
