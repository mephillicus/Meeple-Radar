import { expect, test } from 'vitest'
import { buildReviewRows, parseReviewCsv, reviewToCsv } from '../../src/bgg/review.js'
import { buildRanksIndex, type RankRow } from '../../src/bgg/csv-ranks.js'
import type { CapturedCandidates } from '../../src/bgg/backfill.js'

const row = (bggId: number, name: string): RankRow => ({
  bggId, name, year: 2020, rank: 100, average: 7.5, usersrated: 5000, isExpansion: false,
})

const product = (id: number, title: string, releaseDate: string | null = null) => ({
  id, title, normalizedTitle: title.toLowerCase(), releaseDate,
})

test('buildReviewRows: i candidati catturati online hanno la precedenza e dedup per bgg_id', () => {
  const ranks = buildRanksIndex([row(63268, 'Spot it!')])
  const captured: CapturedCandidates = {
    dobble: [{ bggId: 63268, name: 'Spot it!', yearpublished: 2009, subtype: 'boardgame' }],
  }
  const rows = buildReviewRows([product(1, 'Dobble')], ranks, captured)
  expect(rows).toHaveLength(1)
  expect(rows[0]?.candidates).toHaveLength(1)
  expect(rows[0]?.candidates[0]).toMatchObject({ bggId: 63268, name: 'Spot it!', year: 2009 })
})

test('buildReviewRows: senza candidati la riga resta con lista vuota', () => {
  const ranks = buildRanksIndex([])
  const rows = buildReviewRows([product(7, 'Gioco Misterioso')], ranks, {})
  expect(rows[0]?.candidates).toEqual([])
})

test('reviewToCsv: BOM, punto e virgola, header con bgg_id e quoting', () => {
  const ranks = buildRanksIndex([row(63268, 'Spot it!')])
  const rows = buildReviewRows([product(1, 'Dobble; Edizione Speciale', '2026-12-01')], ranks, {})
  const csv = reviewToCsv(rows)
  expect(csv.charCodeAt(0)).toBe(0xfeff)
  expect(csv).toContain('product_id;titolo;nome_normalizzato;data_uscita;')
  expect(csv).toContain(';bgg_id;note')
  expect(csv).toContain('"Dobble; Edizione Speciale"')
  const dataLine = csv.split('\n')[1]
  expect(dataLine?.startsWith('1;')).toBe(true)
})

test('reviewToCsv e parseReviewCsv fanno un roundtrip del bgg_id inserito', () => {
  const ranks = buildRanksIndex([])
  const rows = buildReviewRows([product(1, 'Dobble'), product(2, 'Altro')], ranks, {})
  const csv = reviewToCsv(rows)
  // L'utente compila la colonna bgg_id: senza candidati il campo è l'unico
  // valorizzato oltre a product_id/titolo, lo split semplice è affidabile.
  const lines = csv.replace(/^\ufeff/, '').split(/\r?\n/)
  const compiled = lines
    .map((line) => (line.startsWith('1;') ? line.split(';').map((f, i) => (i === 13 ? '642688' : f)).join(';') : line))
    .join('\n')
  const parsed = parseReviewCsv(compiled)
  expect(parsed).toContainEqual({ productId: 1, bggId: 642688 })
  expect(parsed).toContainEqual({ productId: 2, bggId: null })
})

test('parseReviewCsv legge i campi quotate con punto e virgola dentro', () => {
  const header = 'product_id;titolo;nome_normalizzato;data_uscita;candidato_1;candidato_1_id;candidato_1_anno;candidato_2;candidato_2_id;candidato_2_anno;candidato_3;candidato_3_id;candidato_3_anno;bgg_id;note'
  const csv = `${header}\n1;"Un titolo; con punto e virgola";un titolo con punto e virgola;;;;;;;;;;;12345;\n`
  const parsed = parseReviewCsv(csv)
  expect(parsed).toEqual([{ productId: 1, bggId: 12345 }])
})
