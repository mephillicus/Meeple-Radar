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

test('matchCsv: fallback normalizeForBggMatch gestisce suffissi italiani, accenti e trattini', () => {
  const index = buildRanksIndex(parseRanksCsv(
    HEADER + '\n13,"Catan",1995,500,7,7.1,100000,0,,\n14,"Cafe International",1989,2000,6,6.5,5000,0,,\n15,"Dune: Imperium",2020,6,8.2,8.4,50000,0,,\n'))
  expect(matchCsv(index, 'catan edizione italiana')?.bggId).toBe(13)
  expect(matchCsv(index, 'catan 2ª edizione')?.bggId).toBe(13)
  expect(matchCsv(index, 'café international')?.bggId).toBe(14)
  expect(matchCsv(index, 'duneimperium', 'Dune–Imperium')?.bggId).toBe(15)
})

test('loadRanksIndex: primo path esistente vince, nessun path -> null', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ranks-'))
  fs.writeFileSync(path.join(dir, 'boardgames_ranks.csv'), HEADER + '\n1,"X",2000,1,7,7.5,10,0,,\n')
  const index = loadRanksIndex([path.join(dir, 'missing.csv'), path.join(dir, 'boardgames_ranks.csv')])
  expect(index?.byId.size).toBe(1)
  expect(loadRanksIndex([path.join(dir, 'nope.csv')])).toBeNull()
  fs.rmSync(dir, { recursive: true, force: true })
})

test('loadRanksIndex: ignora CSV vuoto o pagina HTML corrotta (0 righe) e cerca nei fallback o ritorna null', () => {
  expect(parseRanksCsv('')).toEqual([])
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ranks-empty-'))
  const emptyCsv = path.join(dir, 'empty.csv')
  const htmlErrorCsv = path.join(dir, 'html.csv')
  const validCsv = path.join(dir, 'valid.csv')
  fs.writeFileSync(emptyCsv, '')
  fs.writeFileSync(htmlErrorCsv, '<html><body>503 Service Unavailable</body></html>')
  fs.writeFileSync(validCsv, HEADER + '\n42,"Catan",1995,100,7,7.2,1000,0,,\n')

  expect(loadRanksIndex([emptyCsv, htmlErrorCsv])).toBeNull()
  const fallbackIndex = loadRanksIndex([emptyCsv, htmlErrorCsv, validCsv])
  expect(fallbackIndex?.byId.get(42)?.name).toBe('Catan')
  fs.rmSync(dir, { recursive: true, force: true })
})
