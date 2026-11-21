import { expect, test } from 'vitest'
import { extractReleaseDate } from '../src/normalize.js'

test('parses full ISO-like month (YYYY-MM) to first day', () => {
  expect(extractReleaseDate('2026-03')).toBe('2026-03-01')
})

test('parses day/month/year formats', () => {
  expect(extractReleaseDate('05/03/2026')).toBe('2026-03-05')
  expect(extractReleaseDate('5-3-2026')).toBe('2026-03-05')
})

test('fills current year when only month name is provided', () => {
  const year = new Date().getUTCFullYear()
  expect(extractReleaseDate('luglio')).toBe(`${year}-07-01`)
  expect(extractReleaseDate('July')).toBe(`${year}-07-01`)
})

test('parses month name with day', () => {
  const year = new Date().getUTCFullYear()
  expect(extractReleaseDate('5 marzo')).toBe(`${year}-03-05`)
  expect(extractReleaseDate('March 10')).toBe(`${year}-03-10`)
})

test('keeps the day in a full ISO date instead of degrading it to the first', () => {
  expect(extractReleaseDate('Data di uscita: 2026-11-15')).toBe('2026-11-15')
})

test('takes the year that follows the month name', () => {
  expect(extractReleaseDate('uscita prevista per marzo 2027')).toBe('2027-03-01')
  expect(extractReleaseDate('dal 15 novembre 2026')).toBe('2026-11-15')
})

test('reads the phrasings Italian headlines actually use', () => {
  const year = new Date().getUTCFullYear()
  expect(extractReleaseDate('esce a novembre')).toBe(`${year}-11-01`)
  expect(extractReleaseDate('arriva a dicembre')).toBe(`${year}-12-01`)
  expect(extractReleaseDate('in libreria il 3 marzo')).toBe(`${year}-03-03`)
  expect(extractReleaseDate('uscita il 15 novembre')).toBe(`${year}-11-15`)
})

test('scarta gli anni che arrivano dal testo descrittivo', () => {
  expect(extractReleaseDate('05/03/1943')).toBeNull()
  expect(extractReleaseDate('1943-09-01')).toBeNull()
  expect(extractReleaseDate('uscita il 5 marzo 1943')).toBeNull()
  expect(extractReleaseDate('riedito per la collezione, 1979-09')).toBeNull()
})

test('accetta la finestra plausibile: un anno indietro e tre avanti', () => {
  const year = new Date().getUTCFullYear()
  expect(extractReleaseDate(`${year - 1}-06-30`)).toBe(`${year - 1}-06-30`)
  expect(extractReleaseDate(`${year}-06-30`)).toBe(`${year}-06-30`)
  expect(extractReleaseDate(`${year + 3}-06-30`)).toBe(`${year + 3}-06-30`)
})

test('scarta le date fuori dalla finestra plausibile', () => {
  const year = new Date().getUTCFullYear()
  expect(extractReleaseDate(`${year - 2}-06-30`)).toBeNull()
  expect(extractReleaseDate(`${year + 4}-06-30`)).toBeNull()
  expect(extractReleaseDate('uscita prevista per marzo 8230')).toBeNull()
})

test('converte una stagione nel 15 del mese intermedio', () => {
  const year = new Date().getUTCFullYear()
  expect(extractReleaseDate('Autunno 2026')).toBe('2026-10-15')
  expect(extractReleaseDate('primavera')).toBe(`${year}-04-15`)
  expect(extractReleaseDate('estate 2027')).toBe('2027-07-15')
  expect(extractReleaseDate('inverno')).toBe(`${year}-01-15`)
})

test('ignora date dentro URL/attributi HTML grezzi', () => {
  expect(
    extractReleaseDate('<p><img src="https://cdn.shop.it/uploads/2026-03/box.jpg" alt="Gioco">Nessuna data qui</p>'),
  ).toBeNull()
  expect(
    extractReleaseDate('<div data-created="2026-05-12"><p>Descrizione del gioco da tavolo</p></div>'),
  ).toBeNull()
})

test('abbreviazioni di 3 lettere non producono falsi positivi da testo libero ma matchano con giorno o anno', () => {
  const year = new Date().getUTCFullYear()
  expect(extractReleaseDate('Include un set di carte e un set di dadi')).toBeNull()
  expect(extractReleaseDate('Set base completo per 4 giocatori')).toBeNull()
  expect(extractReleaseDate('This may be the best game')).toBeNull()
  expect(extractReleaseDate('15 set 2026')).toBe('2026-09-15')
  expect(extractReleaseDate('15 set')).toBe(`${year}-09-15`)
  expect(extractReleaseDate('uscita prevista set 2026')).toBe('2026-09-01')
  expect(extractReleaseDate('Set base in uscita a ottobre 2026')).toBe('2026-10-01')
})

test('limita il lookahead dell anno a 20 caratteri per ignorare copyright lontani', () => {
  const year = new Date().getUTCFullYear()
  const padding = 'a '.repeat(60)
  expect(extractReleaseDate(`in uscita a marzo. ${padding} © ${year + 2} Editore`)).toBe(`${year}-03-01`)
  expect(extractReleaseDate(`March 10, ${year + 1}`)).toBe(`${year + 1}-03-10`)
})

test('stagioni: ignora lore/flavor text ma accetta anno esplicito o indizio di uscita', () => {
  const year = new Date().getUTCFullYear()
  expect(extractReleaseDate("Sopravvivi all'inverno artico in questo cooperativo")).toBeNull()
  expect(extractReleaseDate('Durante la primavera i mercanti percorrono la valle')).toBeNull()
  expect(extractReleaseDate('In arrivo in primavera')).toBe(`${year}-04-15`)
  expect(extractReleaseDate('Preordine aperto, spedizione autunno')).toBe(`${year}-10-15`)
})

test('giorno/mese con slash richiede indizio di uscita entro 30 caratteri', () => {
  const year = new Date().getUTCFullYear()
  expect(extractReleaseDate('Gioco per 2/4 giocatori')).toBeNull()
  expect(extractReleaseDate('Voto 9/10 e durata 1/2 ora')).toBeNull()
  expect(extractReleaseDate('Stile 1/2 cooperativo')).toBeNull()
  expect(extractReleaseDate('Disponibile dal 15/10')).toBe(`${year}-10-15`)
  expect(extractReleaseDate('Per 2/4 giocatori, day one 20/11')).toBe(`${year}-11-20`)
})