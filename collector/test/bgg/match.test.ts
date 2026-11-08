import { expect, test } from 'vitest'
import {
  buildCsvTokenIndex,
  findCsvCandidates,
  pickCertainCsv,
  pickCertainItems,
  similarity,
} from '../../src/bgg/match.js'
import { buildRanksIndex, type RankRow } from '../../src/bgg/csv-ranks.js'
import type { GeekItem } from '../../src/bgg/online.js'

const row = (bggId: number, name: string, isExpansion = false): RankRow => ({
  bggId, name, year: 2020, rank: 100, average: 7.5, usersrated: 1000, isExpansion,
})

test('similarity: 1 su identiche, 0 su completamente diverse', () => {
  expect(similarity('black flag', 'black flag')).toBe(1)
  expect(similarity('abc', 'xyz')).toBe(0)
})

test('pickCertainCsv: match esatto unico viene assegnato', () => {
  const index = buildRanksIndex([row(1, 'Ark Nova'), row(2, 'Black Flag')])
  const tokens = buildCsvTokenIndex(index)
  expect(pickCertainCsv(index, tokens, 'black flag')?.bggId).toBe(2)
})

test('pickCertainCsv: nome ambiguo nel CSV non viene assegnato', () => {
  // Il caso reale: due righe BGG si chiamano "Black Flag" / "Black Flag!".
  const index = buildRanksIndex([row(335370, 'Black Flag'), row(472803, 'Black Flag!')])
  const tokens = buildCsvTokenIndex(index)
  expect(pickCertainCsv(index, tokens, 'black flag')).toBeNull()
})

test('pickCertainCsv: refusi vicinissimi e distaccati sono certi', () => {
  const index = buildRanksIndex([row(10, 'Carcassonne'), row(11, 'Ark Nova')])
  const tokens = buildCsvTokenIndex(index)
  expect(pickCertainCsv(index, tokens, 'carcasonne')?.bggId).toBe(10)
})

test('pickCertainCsv: titoli italiani localizzati non sono certi, finiscono in review', () => {
  const index = buildRanksIndex([row(110327, 'Lords of Waterdeep')])
  const tokens = buildCsvTokenIndex(index)
  expect(pickCertainCsv(index, tokens, 'i lord di waterdeep')).toBeNull()
})

test('pickCertainCsv: le espansioni esatte-uniche passano, come nel daily enrich', () => {
  const index = buildRanksIndex([row(12, 'Carcassonne Expansion', true)])
  const tokens = buildCsvTokenIndex(index)
  expect(pickCertainCsv(index, tokens, 'carcassonne expansion')?.bggId).toBe(12)
})

test('pickCertainCsv: le espansioni non passano per similarità, solo per match esatto', () => {
  const index = buildRanksIndex([row(12, 'Carcassonne Safari Expansion', true)])
  const tokens = buildCsvTokenIndex(index)
  expect(pickCertainCsv(index, tokens, 'carcassonne safari expnsion')).toBeNull()
})

test('pickCertainCsv: nomi senza token in comune (Dobble vs Spot it!) restano dubbi', () => {
  const index = buildRanksIndex([row(63268, 'Spot it!')])
  const tokens = buildCsvTokenIndex(index)
  expect(pickCertainCsv(index, tokens, 'dobble')).toBeNull()
})

test('findCsvCandidates: candidati ordinati per similarita con dati per la review', () => {
  const index = buildRanksIndex([
    row(21, 'Dixit'), row(22, 'Dixit: Odyssey'), row(23, 'Ark Nova'),
  ])
  const tokens = buildCsvTokenIndex(index)
  const candidates = findCsvCandidates(index, tokens, 'dixit signature', 3)
  // Ark Nova non condivide nessun token: resta fuori.
  expect(candidates).toHaveLength(2)
  expect(candidates.map((c) => c.row.bggId)).not.toContain(23)
  expect(candidates[0]?.score).toBeGreaterThanOrEqual(candidates[1]?.score ?? 0)
})

const item = (bggId: number, name: string, subtype = 'boardgame', alternateNames: string[] = []): GeekItem => ({
  bggId, name, subtype, alternateNames, yearpublished: 2020,
})

test('pickCertainItems: refuso su un solo candidato boardgame è certo', () => {
  const items = [item(10, 'Carcassonne'), item(11, 'Carcassonne: Safari')]
  expect(pickCertainItems(items, 'carcasonne')?.bggId).toBe(10)
})

test('pickCertainItems: le espansioni BGG restano fuori dal certo', () => {
  const items = [item(10, 'Carcassonne Expansion', 'boardgameexpansion')]
  expect(pickCertainItems(items, 'carcassonne expansion')).toBeNull()
})

test('pickCertainItems: due candidati troppo simili tra loro non sono certi', () => {
  // Il caso reale "Black Flag": due righe BGG con lo stesso nome normalizzato.
  const items = [item(10, 'Carcassonne'), item(11, 'Carcassonne!')]
  expect(pickCertainItems(items, 'carcassonne')).toBeNull()
})

test('pickCertainItems: i nomi alternativi valgono per il punteggio', () => {
  const items = [item(63268, 'Spot it!', 'boardgame', ['Dobble Kids Edition'])]
  expect(pickCertainItems(items, 'dobble kids edtion')?.bggId).toBe(63268)
})
