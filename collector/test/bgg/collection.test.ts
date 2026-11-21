import { expect, test, vi } from 'vitest'
import {
  parseCollectionXml,
  parseExpandsBggId,
  parseExpandsBggIds,
  fetchExpandsBggId,
  fetchExpandsBggIds,
  fetchBggCollection,
  runAutoFavorites,
} from '../../src/bgg/collection.js'

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
  expect(items[0]).toMatchObject({ bggId: 173346, name: '7 Wonders Duel', kind: 'base', owned: true, wishlist: false })
  expect(items[1]).toMatchObject({ bggId: 309116, name: '7 Wonders Duel: Agora', kind: 'expansion', owned: false, wishlist: true })
})

test('parseCollectionXml: supporta attributi su <name sortindex="1">, CDATA ed entità HTML', () => {
  const xml = `<?xml version="1.0"?>
<items totalitems="2">
  <item objectid="173346" subtype="boardgame">
    <name sortindex="1">7 Wonders &amp; Duel</name>
    <status own="1" wishlist="0"/>
  </item>
  <item objectid="309116" subtype="boardgameexpansion">
    <name sortindex="3"><![CDATA[  The Agora Expansion  ]]></name>
    <status own="0" wishlist="1"/>
  </item>
</items>`
  const items = parseCollectionXml(xml)
  expect(items).toHaveLength(2)
  expect(items[0]).toMatchObject({ bggId: 173346, name: '7 Wonders & Duel', owned: true, wishlist: false })
  expect(items[1]).toMatchObject({ bggId: 309116, name: 'The Agora Expansion', kind: 'expansion', owned: false, wishlist: true })
})

test('collection vuota restituisce array vuoto', () => {
  expect(parseCollectionXml('<?xml version="1.0"?><boardgames username="prova" version="1"><totalitems>0</totalitems></boardgames>')).toEqual([])
})

test('parseExpandsBggId: legge id="..." con fallback objectid="..." e ignora link non inbound', () => {
  const xmlId = `<item type="boardgameexpansion" id="309116">
    <link type="boardgameexpansion" id="999999" value="Sub-expansion"/>
    <link type="boardgameexpansion" id="173346" value="7 Wonders Duel" inbound="true"/>
  </item>`
  expect(parseExpandsBggId(xmlId)).toBe(173346)

  const xmlFallback = `<item type="boardgameexpansion" id="309116">
    <link type="boardgameexpansion" objectid="173346" value="7 Wonders Duel" inbound="true"/>
  </item>`
  expect(parseExpandsBggId(xmlFallback)).toBe(173346)

  const xmlNoInbound = `<item type="boardgame" id="173346">
    <link type="boardgameexpansion" id="309116" value="7 Wonders Duel: Agora"/>
  </item>`
  expect(parseExpandsBggId(xmlNoInbound)).toBeNull()
})

test('parseExpandsBggIds: estrae più item in batch', () => {
  const xml = `<items>
    <item type="boardgameexpansion" id="309116">
      <link type="boardgameexpansion" id="173346" value="7 Wonders Duel" inbound="true"/>
    </item>
    <item type="boardgame" id="173346">
      <link type="boardgameexpansion" id="309116" value="7 Wonders Duel: Agora"/>
    </item>
  </items>`
  const map = parseExpandsBggIds(xml)
  expect(map.get(309116)).toBe(173346)
  expect(map.get(173346)).toBeNull()
})

test('fetchExpandsBggIds: batching da max 20 ID, type=boardgame,boardgameexpansion, retry su 202/<message>/429 con Retry-After', async () => {
  const ids = Array.from({ length: 22 }, (_, i) => i + 1)
  const sleep = vi.fn().mockResolvedValue(undefined)
  const calls: string[] = []
  let attempt = 0

  const mockFetch = vi.fn(async (input: string | URL | Request) => {
    const url = String(input)
    calls.push(url)
    attempt++
    if (attempt === 1) {
      return new Response('', { status: 202 })
    }
    if (attempt === 2) {
      return new Response('<?xml version="1.0"?><message>Queued</message>', { status: 200 })
    }
    if (attempt === 3) {
      return new Response('', { status: 429, headers: { 'Retry-After': '3' } })
    }
    // Batch 1 (20 IDs) succeeds on attempt 4; Batch 2 (2 IDs) succeeds on attempt 5
    if (url.includes('id=1,2,')) {
      return new Response(
        '<items><item type="boardgameexpansion" id="1"><link type="boardgameexpansion" id="100" inbound="true"/></item></items>',
        { status: 200 },
      )
    }
    return new Response(
      '<items><item type="boardgameexpansion" id="21"><link type="boardgameexpansion" id="200" inbound="true"/></item></items>',
      { status: 200 },
    )
  }) as unknown as typeof globalThis.fetch

  const map = await fetchExpandsBggIds(ids, {
    token: 'test-token',
    fetch: mockFetch,
    sleep,
    baseDelayMs: 100,
  })

  expect(map.get(1)).toBe(100)
  expect(map.get(2)).toBeNull()
  expect(map.get(21)).toBe(200)
  expect(map.get(22)).toBeNull()
  expect(calls.every((u) => u.includes('type=boardgame,boardgameexpansion'))).toBe(true)
  // Retry-After: 3 -> 3000ms
  expect(sleep).toHaveBeenCalledWith(3000)
  // 2 batch distinti (20 + 2)
  expect(calls[calls.length - 1]).toContain('id=21,22&type=boardgame,boardgameexpansion')
})

test('fetchBggCollection: retry su 202 e arricchimento espansioni in batch', async () => {
  const sleep = vi.fn().mockResolvedValue(undefined)
  let collCalls = 0
  const mockFetch = vi.fn(async (input: string | URL | Request) => {
    const url = String(input)
    if (url.includes('/xmlapi2/collection')) {
      collCalls++
      if (collCalls === 1) return new Response('', { status: 202 })
      return new Response(
        `<items totalitems="2">
          <item objectid="173346" subtype="boardgame"><name sortindex="1">7 Wonders Duel</name><status own="1" wishlist="0"/></item>
          <item objectid="309116" subtype="boardgameexpansion"><name sortindex="1">Agora</name><status own="0" wishlist="1"/></item>
        </items>`,
        { status: 200 },
      )
    }
    if (url.includes('/xmlapi2/thing')) {
      return new Response(
        `<items><item type="boardgameexpansion" id="309116"><link type="boardgameexpansion" id="173346" inbound="true"/></item></items>`,
        { status: 200 },
      )
    }
    return new Response('', { status: 404 })
  }) as unknown as typeof globalThis.fetch

  const items = await fetchBggCollection('prova', {
    token: 'test-token',
    fetch: mockFetch,
    sleep,
    baseDelayMs: 50,
  })

  expect(items).toHaveLength(2)
  expect(items[0]).toMatchObject({ bggId: 173346, name: '7 Wonders Duel', kind: 'base', owned: true })
  expect(items[1]).toMatchObject({ bggId: 309116, name: 'Agora', kind: 'expansion', wishlist: true, expandsBggId: 173346 })
  expect(collCalls).toBe(2)
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

test('runAutoFavorites inserisce favorite solo per espansioni di giochi posseduti senza azione utente', async () => {
  const findExpansions = vi.fn().mockResolvedValue([
    { id: 10, expands_bgg_id: 173346 }, // posseduto, nessuna azione -> favorite
    { id: 11, expands_bgg_id: 999999 }, // non posseduto -> skipped
    { id: 12, expands_bgg_id: 173346 }, // posseduto ma ha azione utente -> skipped
  ])
  const hasAction = vi.fn((gameId: number) => Promise.resolve(gameId === 12))
  const insertAction = vi.fn().mockResolvedValue(undefined)
  const store = {
    findExpansionGamesWithBase: findExpansions,
    hasUserActionOnGame: hasAction,
    insertUserAction: insertAction,
  } as any
  const result = await runAutoFavorites(store, new Set([173346]))
  expect(result).toEqual({ inserted: 1, skipped: 2 })
  expect(insertAction).toHaveBeenCalledWith(10, 'favorite')
  expect(insertAction).not.toHaveBeenCalledWith(11, 'favorite')
  expect(insertAction).not.toHaveBeenCalledWith(12, 'favorite')
})
