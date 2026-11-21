import { expect, test, vi } from 'vitest'
import {
  bggSearchUrl,
  bggXmlSearchUrl,
  bggXmlThingUrl,
  buildSearchQuery,
  createBggXmlOnlineClient,
  parseGeekItem,
  parseSearchIds,
  parseXmlSearchIds,
  parseXmlThingItems,
  resolveGate,
} from '../../src/bgg/online.js'

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

test('parseGeekItem: estrae imageUrl, description pulita ed expandsBggId', () => {
  const json = JSON.stringify({
    item: {
      name: '7 Wonders Duel: Agora',
      subtype: 'boardgameexpansion',
      yearpublished: 2020,
      images: { original: 'https://cf.geekdo-images.com/agora.jpg' },
      description: '<p>Enter the <strong>Senate</strong> &amp; win&rsquo;s glory.</p><p>Second paragraph.</p>',
      links: {
        expandsboardgame: [{ objectid: '173346', name: '7 Wonders Duel' }],
      },
    },
  })
  const item = parseGeekItem(json, 309116)
  expect(item).toMatchObject({
    bggId: 309116,
    imageUrl: 'https://cf.geekdo-images.com/agora.jpg',
    description: "Enter the Senate & win's glory. Second paragraph.",
    expandsBggId: 173346,
  })
})

test('resolveGate: fallback normalizeForBggMatch per suffissi italiani e accenti', () => {
  const items = [parseGeekItem(JSON.stringify({ item: { name: 'Catan', subtype: 'boardgame', yearpublished: 1995 } }), 13)!]
  expect(resolveGate('Catan (Edizione Italiana)', null, items)?.bggId).toBe(13)
})

test('buildSearchQuery: rimuove suffissi italiani in coda e parentesi preservando titoli normali', () => {
  expect(buildSearchQuery('Catan Edizione Italiana')).toBe('Catan')
  expect(buildSearchQuery('Catan - Ed. Italiana')).toBe('Catan')
  expect(buildSearchQuery('Dune: Imperium - Italiano')).toBe('Dune: Imperium')
  expect(buildSearchQuery('Azul ITA')).toBe('Azul')
  expect(buildSearchQuery('Gloomhaven (Edizione Italiana) - ITA')).toBe('Gloomhaven')
  expect(buildSearchQuery('7 Wonders Duel')).toBe('7 Wonders Duel')
  expect(buildSearchQuery('Vita')).toBe('Vita')
})

test('bggXmlSearchUrl e parseXmlSearchIds: estrae boardgame e expansion deduplicati in ordine', () => {
  expect(bggXmlSearchUrl('7 Wonders Duel')).toBe(
    'https://boardgamegeek.com/xmlapi2/search?query=7%20Wonders%20Duel&type=boardgame,boardgameexpansion',
  )
  const xml = `<?xml version="1.0" encoding="utf-8"?>
<items total="4">
  <item type="boardgame" id="173346"><name type="primary" value="7 Wonders Duel"/></item>
  <item id="309116" type="boardgameexpansion"><name type="primary" value="7 Wonders Duel: Agora"/></item>
  <item type="rpgitem" id="999999"><name type="primary" value="Ignored"/></item>
  <item type="boardgame" id="173346"><name type="primary" value="7 Wonders Duel"/></item>
  <item type="boardgame" id="68448"><name type="primary" value="7 Wonders"/></item>
</items>`
  expect(parseXmlSearchIds(xml)).toEqual([173346, 309116, 68448])
})

test('bggXmlThingUrl e parseXmlThingItems: parsa tutti i campi inclusi stats, alternatenames e expandsBggId', () => {
  expect(bggXmlThingUrl([173346, 309116])).toBe(
    'https://boardgamegeek.com/xmlapi2/thing?id=173346,309116&type=boardgame,boardgameexpansion&stats=1',
  )
  const xml = `<?xml version="1.0" encoding="utf-8"?>
<items>
  <item type="boardgame" id="173346">
    <thumbnail>https://cf.geekdo-images.com/thumb.jpg</thumbnail>
    <image>https://cf.geekdo-images.com/full.jpg</image>
    <name type="primary" sortindex="1" value="7 Wonders Duel"/>
    <name type="alternate" sortindex="1" value="7 Wonders: Duello &amp; Gloria"/>
    <name type="alternate" sortindex="1" value="7 Wonders: Duello &amp; Gloria"/>
    <description>Build &amp;amp; lead&rsquo;s civilizations.&#10;Second line.</description>
    <yearpublished value="2015"/>
    <statistics page="1">
      <ratings>
        <average value="8.0954"/>
        <ranks>
          <rank type="family" id="5497" name="strategygames" value="15"/>
          <rank type="subtype" id="1" name="boardgame" value="18"/>
        </ranks>
      </ratings>
    </statistics>
  </item>
  <item type="boardgameexpansion" id="309116">
    <thumbnail>https://cf.geekdo-images.com/agora-thumb.jpg</thumbnail>
    <name type="primary" sortindex="1" value="7 Wonders Duel: Agora"/>
    <description>&lt;p&gt;Enter the Senate.&lt;/p&gt;</description>
    <yearpublished value="2020"/>
    <link type="boardgameexpansion" id="173346" value="7 Wonders Duel" inbound="true"/>
    <statistics page="1">
      <ratings>
        <average value="0"/>
        <ranks>
          <rank type="subtype" id="1" name="boardgame" value="Not Ranked"/>
        </ranks>
      </ratings>
    </statistics>
  </item>
</items>`

  const items = parseXmlThingItems(xml)
  expect(items).toHaveLength(2)
  expect(items[0]).toEqual({
    bggId: 173346,
    name: '7 Wonders Duel',
    alternateNames: ['7 Wonders: Duello & Gloria'],
    subtype: 'boardgame',
    yearpublished: 2015,
    imageUrl: 'https://cf.geekdo-images.com/full.jpg',
    description: "Build & lead's civilizations. Second line.",
    expandsBggId: null,
    bggRating: 8.0954,
    bggRank: 18,
  })
  expect(items[1]).toEqual({
    bggId: 309116,
    name: '7 Wonders Duel: Agora',
    alternateNames: [],
    subtype: 'boardgameexpansion',
    yearpublished: 2020,
    imageUrl: 'https://cf.geekdo-images.com/agora-thumb.jpg',
    description: 'Enter the Senate.',
    expandsBggId: 173346,
    bggRating: null,
    bggRank: null,
  })
})

test('createBggXmlOnlineClient: invia Bearer token, gestisce 202 retry e ritorna null su errore', async () => {
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(
      new Response('<items><item type="boardgame" id="173346"/></items>', { status: 200 }),
    )
    .mockResolvedValueOnce(new Response('<message>Queued</message>', { status: 202 }))
    .mockResolvedValueOnce(
      new Response(
        '<items><item type="boardgame" id="173346"><name type="primary" value="7 Wonders Duel"/><yearpublished value="2015"/></item></items>',
        { status: 200 },
      ),
    )
    .mockResolvedValueOnce(new Response('Unauthorized', { status: 401 }))

  const sleep = vi.fn().mockResolvedValue(undefined)
  const client = createBggXmlOnlineClient({
    token: 'secret-token',
    fetch: fetchMock as unknown as typeof globalThis.fetch,
    sleep,
    baseDelayMs: 10,
  })

  const ids = await client.searchIds('7 Wonders Duel')
  expect(ids).toEqual([173346])
  expect(fetchMock).toHaveBeenNthCalledWith(
    1,
    'https://boardgamegeek.com/xmlapi2/search?query=7%20Wonders%20Duel&type=boardgame,boardgameexpansion',
    expect.objectContaining({
      headers: expect.objectContaining({ Authorization: 'Bearer secret-token' }),
    }),
  )

  const items = await client.fetchGeekItems([173346])
  expect(items).toHaveLength(1)
  expect(items?.[0].name).toBe('7 Wonders Duel')
  expect(sleep).toHaveBeenCalledTimes(1)

  expect(await client.fetchGeekItems([])).toEqual([])
  expect(await client.searchIds('fail')).toBeNull()
})

const hasToken = Boolean(process.env.BGG_API_TOKEN)

test.skipIf(!hasToken)('live BGG XML API2: searchIds + fetchGeekItems per 7 Wonders Duel', async () => {
  const client = createBggXmlOnlineClient()
  const ids = await client.searchIds('7 Wonders Duel')
  expect(ids).not.toBeNull()
  expect(ids).toContain(173346)
  const items = await client.fetchGeekItems([173346])
  expect(items).not.toBeNull()
  expect(items?.[0]).toMatchObject({
    bggId: 173346,
    name: '7 Wonders Duel',
    subtype: 'boardgame',
    yearpublished: 2015,
  })
})
