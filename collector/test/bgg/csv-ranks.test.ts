import { expect, test } from 'vitest'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { parseRanksCsv, buildRanksIndex, matchCsv, loadRanksIndex } from '../../src/bgg/csv-ranks.js'

const HEADER = 'id,name,yearpublished,rank,bayesaverage,average,usersrated,is_expansion,abstracts_rank'

test('parseRanksCsv: campo nome con virgolette e virgola interna', () => {
  const rows = parseRanksCsv(HEADER + '\n224517,"Brass: Birmingham",2018,1,8.39,8.55,60635,0,,\n')
  expect(rows).toHaveLength(1)
  expect(rows[0]).toMatchObject({ bggId: 224517, name: 'Brass: Birmingham', year: 2018, rank: 1, average: 8.55, isExpansion: false })
})

test('parseRanksCsv: campi numerici vuoti -> null, is_expansion 1 -> true', () => {
  const rows = parseRanksCsv(HEADER + '\n999,"Gioco Senza Rango",2020,,,,,1,,\n')
  expect(rows[0]).toMatchObject({ rank: null, average: null, usersrated: null, isExpansion: true })
})

test('buildRanksIndex: bucket per nome (anche multipli) e indice per id', () => {
  const index = buildRanksIndex(parseRanksCsv(
    HEADER + '\n1,"Dune",1979,5,7,7.1,100,0,,\n2,"Dune",2019,10,8,8.1,200,0,,\n'))
  expect(index.byName.get('dune')).toHaveLength(2)
  expect(index.byId.get(1)?.year).toBe(1979)
})

test('matchCsv: hit esatto singolo, bucket ambiguo -> null, mancante -> null', () => {
  const index = buildRanksIndex(parseRanksCsv(
    HEADER + '\n1,"Carcassonne",2000,10,7,7.4,100,0,,\n2,"Dune",1979,5,7,7.1,100,0,,\n3,"Dune",2019,10,8,8.1,200,0,,\n'))
  expect(matchCsv(index, 'carcassonne')?.bggId).toBe(1)
  expect(matchCsv(index, 'dune')).toBeNull()
  expect(matchCsv(index, 'assente')).toBeNull()
})

test('loadRanksIndex: primo path esistente vince, nessun path -> null', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ranks-'))
  fs.writeFileSync(path.join(dir, 'boardgames_ranks.csv'), HEADER + '\n1,"X",2000,1,7,7.5,10,0,,\n')
  const index = loadRanksIndex([path.join(dir, 'missing.csv'), path.join(dir, 'boardgames_ranks.csv')])
  expect(index?.byId.size).toBe(1)
  expect(loadRanksIndex([path.join(dir, 'nope.csv')])).toBeNull()
  fs.rmSync(dir, { recursive: true, force: true })
})
