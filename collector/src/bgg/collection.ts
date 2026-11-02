import { USER_AGENT, FETCH_TIMEOUT_MS } from '../adapters/http.js'

export type CollectionItem = {
  bggId: number
  name: string
  kind: 'base' | 'expansion'
  owned: boolean
  wishlist: boolean
}

// Legge la collezione pubblica BGG da XML API 2 (collection?stats=1).
// Nessun DOMParser (non disponibile in tutti gli ambienti test): si usano
// espressioni regolari mirate, come fa parseSitemapEntries in sitemap.ts.
const ITEM_RE = /<item\b[^>]*>([\s\S]*?)<\/item>/gi
const OBJECTID_RE = /objectid="(\d+)"/i
const SUBTYPE_RE = /subtype="([^"]*)"/i
const OWN_RE = /own="1"/i
const WISHLIST_RE = /wishlist="1"/i
const NAME_RE = /<name>(?:<!\[CDATA\[)?\s*([^<]*?)\s*(?:\]\]>)?\s*<\/name>/i

export function parseCollectionXml(xml: string): CollectionItem[] {
  const out: CollectionItem[] = []
  for (const m of xml.matchAll(ITEM_RE)) {
    const full = m[0]
    const block = m[1]
    const bggId = Number(full.match(OBJECTID_RE)?.[1])
    const subtype = full.match(SUBTYPE_RE)?.[1] ?? ''
    const name = block.match(NAME_RE)?.[1]?.trim() ?? ''
    const owned = OWN_RE.test(full)
    const wishlist = WISHLIST_RE.test(full)
    out.push({
      bggId,
      name,
      kind: subtype === 'boardgameexpansion' ? 'expansion' : 'base',
      owned,
      wishlist,
    })
  }
  return out
}

// Legge il gioco base di un'espansione da xmlapi2/thing:
// <link type="boardgameexpansion" id="<base>" value="<base>" inbound="true"/>
// Budget: max 10 chiamate per run, distanziate ~1 secondo. Best-effort:
// errore di rete/429/401 -> null, mai lancio.
const THING_BUDGET = 10
let thingCalls = 0
let lastThingCall = 0
const THING_CACHE = new Map<number, number | null>()

async function thingThrottle(): Promise<void> {
  const now = Date.now()
  const diff = now - lastThingCall
  if (diff > 0 && diff < 1000) {
    await new Promise((r) => setTimeout(r, 1000 - diff))
  }
  lastThingCall = Date.now()
}

function parseExpandsBggId(xml: string): number | null {
  const LINK_RE = /<link type="boardgameexpansion"\s+[^>]*>/gi
  const ID_RE = /objectid="(\d+)"/i
  const INBOUND_RE = /inbound="true"/i
  for (const m of xml.matchAll(LINK_RE)) {
    if (!INBOUND_RE.test(m[0])) continue
    const idM = m[0].match(ID_RE)
    if (idM) return Number(idM[1])
  }
  return null
}

export async function fetchExpandsBggId(bggId: number): Promise<number | null> {
  if (THING_CACHE.has(bggId)) return THING_CACHE.get(bggId)!
  const token = process.env.BGG_API_TOKEN
  if (!token) return null
  if (thingCalls >= THING_BUDGET) return null
  await thingThrottle()
  thingCalls++
  try {
    const res = await fetch(
      `https://boardgamegeek.com/xmlapi2/thing?id=${bggId}&type=boardgame`,
      {
        headers: { 'User-Agent': USER_AGENT, Authorization: `Bearer ${token}` },
        signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
      },
    )
    if (!res.ok) return null
    const xml = await res.text()
    const baseId = parseExpandsBggId(xml)
    THING_CACHE.set(bggId, baseId)
    return baseId
  } catch {
    return null
  }
}
