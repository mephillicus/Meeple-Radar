import { expect, test } from 'vitest'
import { bggSearchUrl, buildSearchQuery, parseSearchIds, parseGeekItem, resolveGate } from '../../src/bgg/online.js'

test('bggSearchUrl: endpoint con query encodificata', () => {
  expect(bggSearchUrl('Catan Nuova')).toBe('https://boardgamegeek.com/search/boardgame?q=Catan%20Nuova')
})

test('buildSearchQuery: parentesi rimosse, vuoto -> titolo originale', () => {
  expect(buildSearchQuery('Catan (Nuova Edizione)')).toBe('Catan')
  expect(buildSearchQuery('(solo parentesi) ')).toBe('(solo parentesi) ')
})

test('parseSearchIds: base + expansion, distinti e in ordine', () => {
  const html = '<a href="/boardgame/25821/x">a</a><a href="/boardgameexpansion/999/y">b</a><a href="/boardgame/25821/z">c</a><a href="/boardgame/123/w">d</a>'
  expect(parseSearchIds(html)).toEqual([25821, 999, 123])
})

test('parseGeekItem: forma reale con nomi alternativi italiani', () => {
  const json = JSON.stringify({
    item: {
      name: "The Werewolves of Miller's Hollow", subtype: 'boardgame', yearpublished: 2001,
    },
    alternatenames: [{ nameid: '1190942', name: 'Lupi Mannari di Roccascura' }],
  })
  const item = parseGeekItem(json, 25821)
  expect(item).toMatchObject({ bggId: 25821, subtype: 'boardgame', yearpublished: 2001 })
  expect(item?.alternateNames).toEqual(['Lupi Mannari di Roccascura'])
  expect(parseGeekItem('non-json', 1)).toBeNull()
  expect(parseGeekItem('{}', 1)).toBeNull()
})

test('parseGeekItem: legge il titolo italiano dalla posizione restituita da Geekdo', () => {
  const json = JSON.stringify({
    item: {
      name: 'Dune: War for Arrakis – The Spacing Guild',
      subtype: 'boardgameexpansion',
      yearpublished: 2024,
    },
    alternatenames: [
      { nameid: '2562395', name: 'Dune: La Guerra per Arrakis – La Gilda Spaziale' },
    ],
  })

  const item = parseGeekItem(json, 370796)!

  expect(resolveGate('Dune: La Guerra per Arrakis – La Gilda Spaziale', null, [item])?.bggId).toBe(370796)
})

test('resolveGate: singolo match esatto vince', () => {
  const items = [parseGeekItem(JSON.stringify({ item: { name: 'Catan', subtype: 'boardgame', yearpublished: 1995, alternatenames: [] } }), 1)!]
  expect(resolveGate('Catan', null, items)?.bggId).toBe(1)
})

test('resolveGate: omonimi -> solo anno +-1 decide, mai un link a caso', () => {
  const mk = (id: number, year: number) => parseGeekItem(JSON.stringify({ item: { name: 'Dune', subtype: 'boardgame', yearpublished: year, alternatenames: [] } }), id)!
  expect(resolveGate('Dune', '2019-05-01', [mk(1, 1979), mk(2, 2019)])?.bggId).toBe(2)
  expect(resolveGate('Dune', null, [mk(1, 1979), mk(2, 2019)])).toBeNull()
  expect(resolveGate('Dune', '2021-01-01', [mk(1, 1979), mk(2, 2019)])).toBeNull()
})

test('resolveGate: match solo approssimativo (contenuto) non basta', () => {
  const items = [parseGeekItem(JSON.stringify({ item: { name: 'Catan: Il Viaggio', subtype: 'boardgame', yearpublished: 2019, alternatenames: [] } }), 1)!]
  expect(resolveGate('Catan', null, items)).toBeNull()
})
