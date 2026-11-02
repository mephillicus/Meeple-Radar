import { expect, test } from 'vitest'
import { parseCollectionXml, fetchExpandsBggId } from '../../src/bgg/collection.js'

test('estrae giochi posseduti e wishlist dalla collection XML', () => {
  const xml = `<?xml version="1.0"?>
<boardgames username="prova" version="1">
  <item objectid="173346" subtype="boardgame" numplays="0" own="1" prevowned="0" want="0" wishlist="0" wishlistpriority="0" rating="0" numwant="0">
    <name>7 Wonders Duel</name>
    <yearpublished>2015</yearpublished>
    <stats>
      <value name="usersrated" value="..."/>
    </stats>
  </item>
  <item objectid="309116" subtype="boardgameexpansion" own="0" prevowned="0" want="0" wishlist="1" wishlistpriority="0" rating="0" numwant="0">
    <name>7 Wonders Duel: Agora</name>
    <yearpublished>2020</yearpublished>
  </item>
</boardgames>`
  const items = parseCollectionXml(xml)
  expect(items).toHaveLength(2)
  expect(items[0]).toMatchObject({ bggId: 173346, kind: 'base', owned: true, wishlist: false })
  expect(items[1]).toMatchObject({ bggId: 309116, kind: 'expansion', owned: false, wishlist: true })
})

test('collection vuota restituisce array vuoto', () => {
  expect(parseCollectionXml('<?xml version="1.0"?><boardgames username="prova" version="1"><totalitems>0</totalitems></boardgames>')).toEqual([])
})

const hasToken = typeof process.env.BGG_API_TOKEN === 'string' && process.env.BGG_API_TOKEN.length > 0
const testBgg = hasToken ? test : test.skip

testBgg('estrae il gioco base da un espansione tramite thing', async () => {
  const baseId = await fetchExpandsBggId(309116) // 7 Wonders Duel: Agora -> 7 Wonders Duel
  expect(baseId).toBeGreaterThan(0)
})

test('senza token restituisce null senza chiamare la rete', async () => {
  const orig = process.env.BGG_API_TOKEN
  process.env.BGG_API_TOKEN = ''
  try {
    const res = await fetchExpandsBggId(309116)
    expect(res).toBeNull()
  } finally {
    process.env.BGG_API_TOKEN = orig
  }
})
