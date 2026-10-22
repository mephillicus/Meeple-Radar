import { expect, test, vi } from 'vitest'
import { afterEach, beforeEach } from 'vitest'
import { isCandidateHeadline, verifyQuote, buildArticlePrompt, parseArticleGames } from '../../src/adapters/articles.js'

// isCandidateHeadline: le negative vincono sempre; senza corpo serve una parola positiva;
// con corpo (feed dei siti) basta l'assenza di negative.
test.each([
  ['Recensione di Ark Nova', false, false],
  ['Provato: Gloomhaven, il verdetto', true, false],
  ['Arriva a novembre Wingspan', false, true],
  ['Nuova uscita: il seguito di Cascadia', true, true],
  ['Titolo qualsiasi', false, false],
  ['Titolo qualsiasi', true, true],
])('isCandidateHeadline(%o, hasBody=%o) => %o', (title, hasBody, expected) => {
  expect(isCandidateHeadline(title, hasBody)).toBe(expected)
})

test('verifyQuote accetta solo una substring con spazi normalizzati', () => {
  const corpo = 'Il gioco arriva\n  il 3 novembre. È un gioco di carte.'
  expect(verifyQuote('Il gioco arriva il 3 novembre.', corpo)).toBe('Il gioco arriva il 3 novembre.')
  expect(verifyQuote('arriva il 3 di novembre', corpo)).toBeNull()
  expect(verifyQuote('', corpo)).toBeNull()
})

test('buildArticlePrompt chiede il JSON senza chiedere il prezzo', () => {
  vi.setSystemTime(new Date('2026-10-15T12:00:00Z'))
  const prompt = buildArticlePrompt('Testo dell’articolo.', new Date())
  expect(prompt).toContain('15 ottobre 2026')
  expect(prompt).toContain('Testo dell’articolo.')
  expect(prompt).toContain('descriptionQuote')
  expect(prompt).toContain('VERBATIM')
  expect(prompt.toLowerCase()).not.toContain('prezzo')
  vi.useRealTimers()
})

test('parseArticleGames gestisce fence e JSON invalido', () => {
  const ok = parseArticleGames('```json\n{"games":[{"title":"Cascadia","releaseDate":"novembre 2026"}]}\n```')
  expect(ok).toEqual({ ok: true, games: [{ title: 'Cascadia', releaseDate: 'novembre 2026' }] })
  expect(parseArticleGames('frasi di testo')).toEqual({ ok: false, games: [] })
  expect(parseArticleGames('{"games":[]}')).toEqual({ ok: true, games: [] })
})
