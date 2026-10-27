import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createClient } from '@supabase/supabase-js'
import { normalizeTitle } from '../src/normalize.js'
import { decodeHtml } from '../src/text.js'
import { applyEnvFile, parseEnvFile } from './export-news.js'

// Riparazione una tantum dei titoli con entità HTML (&#8211; &#8217; &#039; &) entrati
// nel DB prima del decode in normalizeListing. Non e' una regola che riparte: dopo la
// corsa del collector col fix a bordo, le upsert riallineano tutto da sole.
// Uso: tsx scripts/fix-html-entities.ts          (dry-run, mostra cosa farebbe)
//      tsx scripts/fix-html-entities.ts --apply  (scrive davvero)

const ENTITY_RE = /&#?[0-9a-zA-Z]+;/
const PAGE = 1000

type Row = Record<string, unknown>

async function fetchAll(client: ReturnType<typeof createClient>, table: string, select: string): Promise<Row[]> {
  const rows: Row[] = []
  for (let from = 0; ; from += PAGE) {
    const { data, error } = await client
      .from(table)
      .select(select)
      .order('id')
      .range(from, from + PAGE - 1)
    if (error) throw new Error(`fetch ${table}: ${error.message}`)
    const batch = (data ?? []) as Row[]
    rows.push(...batch)
    if (batch.length < PAGE) break
  }
  return rows
}

const str = (row: Row, key: string): string => (row[key] === null || row[key] === undefined ? '' : String(row[key]))

async function main(): Promise<void> {
  const apply = process.argv.includes('--apply')
  const qui = dirname(fileURLToPath(import.meta.url))
  const envText = readFileSync(join(qui, '..', 'supabase.env'), 'utf8')
  applyEnvFile(parseEnvFile(envText), process.env)
  const url = process.env.SUPABASE_URL
  const key = process.env.SUPABASE_SERVICE_ROLE_KEY
  if (!url || !key) throw new Error('SUPABASE_URL o SUPABASE_SERVICE_ROLE_KEY mancanti in supabase.env')
  const client = createClient(url, key)
  console.log(apply ? '=== APPLY ===' : '=== DRY-RUN (usa --apply per scrivere) ===')

  // ---- product: title e normalized_title (chiave unica di upsert) ----
  const products = await fetchAll(client, 'product', 'id, title, normalized_title, game_id')
  const poisoned = products.filter((p) => ENTITY_RE.test(str(p, 'title')) && decodeHtml(str(p, 'title')) !== str(p, 'title'))
  console.log(`\n[product] ${poisoned.length} righe con entita su ${products.length}`)
  const byNorm = new Map<string, Row>()
  for (const p of products) {
    if (!poisoned.includes(p)) byNorm.set(str(p, 'normalized_title'), p)
  }

  let fixed = 0
  let merged = 0
  for (const p of poisoned) {
    const decoded = decodeHtml(str(p, 'title'))
    const newNorm = normalizeTitle(decoded)
    const twin = byNorm.get(newNorm)
    if (twin !== undefined && Number(twin.id) !== Number(p.id)) {
      console.log(`MERGE ${p.id} "${str(p, 'title')}" -> ${twin.id} "${str(twin, 'title')}"`)
      if (apply) {
        const { error: reErr } = await client.from('listing').update({ product_id: twin.id }).eq('product_id', p.id)
        if (reErr) throw new Error(`re-point listing del prodotto ${p.id}: ${reErr.message}`)
        if (p.game_id != null && twin.game_id == null) {
          const { error: gErr } = await client.from('product').update({ game_id: p.game_id }).eq('id', twin.id)
          if (gErr) throw new Error(`copia game_id su ${twin.id}: ${gErr.message}`)
        }
        const { error: delErr } = await client.from('product').delete().eq('id', p.id)
        if (delErr) throw new Error(`delete prodotto ${p.id}: ${delErr.message}`)
      }
      merged++
      continue
    }
    console.log(`FIX ${p.id}: "${str(p, 'title')}" -> "${decoded}" (key "${str(p, 'normalized_title')}" -> "${newNorm}")`)
    if (apply) {
      const { error } = await client
        .from('product')
        .update({ title: decoded, normalized_title: newNorm })
        .eq('id', p.id)
      if (error) throw new Error(`update prodotto ${p.id}: ${error.message}`)
    }
    byNorm.set(newNorm, p)
    fixed++
  }

  // ---- listing: title e normalized_title (match delle fonti editoriali) ----
  const listings = await fetchAll(client, 'listing', 'id, title, normalized_title')
  const badListings = listings.filter((l) => ENTITY_RE.test(str(l, 'title')) && decodeHtml(str(l, 'title')) !== str(l, 'title'))
  console.log(`\n[listing] ${badListings.length} righe con entita su ${listings.length}`)
  let listingsFixed = 0
  for (const l of badListings) {
    const decoded = decodeHtml(str(l, 'title'))
    console.log(`FIX ${l.id}: "${str(l, 'title')}" -> "${decoded}"`)
    if (apply) {
      const { error } = await client
        .from('listing')
        .update({ title: decoded, normalized_title: normalizeTitle(decoded) })
        .eq('id', l.id)
      if (error) throw new Error(`update listing ${l.id}: ${error.message}`)
    }
    listingsFixed++
  }

  // ---- game: name (copiato dal titolo prodotto al momento del link) ----
  const games = await fetchAll(client, 'game', 'id, name')
  const badGames = games.filter((g) => ENTITY_RE.test(str(g, 'name')) && decodeHtml(str(g, 'name')) !== str(g, 'name'))
  console.log(`\n[game] ${badGames.length} righe con entita su ${games.length}`)
  let gamesFixed = 0
  for (const g of badGames) {
    const decoded = decodeHtml(str(g, 'name'))
    console.log(`FIX ${g.id}: "${str(g, 'name')}" -> "${decoded}"`)
    if (apply) {
      const { error } = await client.from('game').update({ name: decoded }).eq('id', g.id)
      if (error) throw new Error(`update game ${g.id}: ${error.message}`)
    }
    gamesFixed++
  }

  console.log(`\n=== ${apply ? 'APPLICATO' : 'PREVIEW'}: ${fixed} prodotti fixati, ${merged} mergiati, ${listingsFixed} listing, ${gamesFixed} game ===`)
}

main().catch((err: unknown) => {
  console.error(err instanceof Error ? err.message : String(err))
  process.exitCode = 1
})
