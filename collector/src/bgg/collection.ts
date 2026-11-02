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
