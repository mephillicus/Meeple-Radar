#!/usr/bin/env tsx

/**
 * Import BGG ranking CSV into Supabase `game` table in batches.
 *
 * The script reads the CSV file `boardgames_ranks.csv` (`collector/data/` or repo root),
 * parses each row (bgg id, name, year, rank, average rating, ...),
 * and upserts the data into the `game` table in chunks of 500 rows.
 *
 * Expected environment variables:
 *   SUPABASE_URL              – Supabase project URL
 *   SUPABASE_SERVICE_ROLE_KEY – Service role key (admin access, bypasses RLS)
 */

import { createClient } from '@supabase/supabase-js'
import { readFileSync, existsSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseRanksCsv } from '../src/bgg/csv-ranks.js'

const BATCH_SIZE = 500
const __dirname = path.dirname(fileURLToPath(import.meta.url))

async function main() {
  const supabaseUrl = process.env.SUPABASE_URL
  const serviceKey = process.env.SUPABASE_SERVICE_ROLE_KEY ?? process.env.SUPABASE_SERVICE_KEY

  if (!supabaseUrl || !serviceKey) {
    console.error('Missing SUPABASE_URL or SUPABASE_SERVICE_ROLE_KEY environment variables.')
    process.exit(1)
  }

  const supabase = createClient(supabaseUrl, serviceKey)

  const candidates = [
    path.resolve(__dirname, '../data/boardgames_ranks.csv'),
    path.resolve(__dirname, '../../boardgames_ranks.csv'),
  ]
  const csvPath = candidates.find((p) => existsSync(p)) ?? candidates[1]
  if (!existsSync(csvPath)) {
    console.error(`CSV file not found at ${csvPath}`)
    process.exit(1)
  }

  const csvText = readFileSync(csvPath, { encoding: 'utf8' })
  const rows = parseRanksCsv(csvText)
  console.log(`Parsed ${rows.length} rows from CSV`)

  let success = 0
  for (let i = 0; i < rows.length; i += BATCH_SIZE) {
    const chunk = rows.slice(i, i + BATCH_SIZE).map((row) => ({
      name: row.name,
      kind: row.isExpansion ? 'expansion' : 'base',
      bgg_id: row.bggId,
      bgg_rating: row.average,
      bgg_rank: row.rank,
    }))

    const { error } = await supabase
      .from('game')
      .upsert(chunk, { onConflict: 'bgg_id' })
    if (error) {
      console.error(`Failed to upsert batch at offset ${i}: ${error.message}`)
    } else {
      success += chunk.length
    }
  }

  console.log(`Successfully upserted ${success} records out of ${rows.length}`)
  process.exit(0)
}

main().catch((e) => {
  console.error('Unexpected error:', e)
  process.exit(1)
})
