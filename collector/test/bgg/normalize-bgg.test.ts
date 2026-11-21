import { expect, test } from 'vitest'
import { normalizeForBggMatch } from '../../src/bgg/normalize-bgg.js'

test('rimuove suffissi italiani di negozio ed edizione', () => {
  expect(normalizeForBggMatch('Catan (Edizione Italiana)')).toBe('catan')
  expect(normalizeForBggMatch('Catan - Ed. Italiana')).toBe('catan')
  expect(normalizeForBggMatch('Catan Ed Italiana')).toBe('catan')
  expect(normalizeForBggMatch('Catan Italiano')).toBe('catan')
  expect(normalizeForBggMatch('Catan (ITA)')).toBe('catan')
  expect(normalizeForBggMatch('Catan 2a Edizione')).toBe('catan')
  expect(normalizeForBggMatch('Catan 2ª Edizione')).toBe('catan')
  expect(normalizeForBggMatch('Carcassonne [Promo]')).toBe('carcassonne')
  expect(normalizeForBggMatch('Wingspan - Esclusiva')).toBe('wingspan')
})

test('rimuove suffissi multipli in cascata senza svuotare titoli corti', () => {
  expect(normalizeForBggMatch('Catan - 2ª Edizione (Edizione Italiana) Promo')).toBe('catan')
  expect(normalizeForBggMatch('Promo')).toBe('promo')
  expect(normalizeForBggMatch('Italiano')).toBe('italiano')
})

test('normalizza accenti, diacritici, ligature, apostrofi e trattini tipografici', () => {
  expect(normalizeForBggMatch('Café International')).toBe('cafe international')
  expect(normalizeForBggMatch('Pokémon: Splendor')).toBe('pokemon splendor')
  expect(normalizeForBggMatch('The Werewolves of Miller’s Hollow')).toBe('the werewolves of millers hollow')
  expect(normalizeForBggMatch("The Werewolves of Miller's Hollow")).toBe('the werewolves of millers hollow')
  expect(normalizeForBggMatch('Dune–Imperium')).toBe('dune imperium')
  expect(normalizeForBggMatch('Dune — Imperium')).toBe('dune imperium')
  expect(normalizeForBggMatch('Æon’s End')).toBe('aeons end')
})

test('rimuove il prefisso brand ridondante Ravensburger senza svuotare il titolo', () => {
  expect(normalizeForBggMatch('Ravensburger Le peripezie di Pinocchio')).toBe('le peripezie di pinocchio')
  expect(normalizeForBggMatch('Ravensburger - Catan (Edizione Italiana)')).toBe('catan')
  expect(normalizeForBggMatch('Ravensburger')).toBe('ravensburger')
  expect(normalizeForBggMatch('Ravensburger (Edizione Italiana)')).toBe('ravensburger')
})
