#!/usr/bin/env ts-node

/**
 * Import BGG ranking CSV into Supabase `game` table.
 *
 * The script reads the CSV file `boardgames_ranks.csv` in the repository root,
 * parses each row (bgg id, name, year, rank, average rating, ...),
 * and upserts the data into the `game` table.
 *
 * Expected environment variables:
 *   SUPABASE_URL         – Supabase project URL
 *   SUPABASE_SERVICE_KEY – Service role key (admin access, bypasses RLS)
 */

import { createClient } from '@supabase/supabase-js';
import { readFileSync, existsSync } from 'node:fs';
import path from 'node:path';

// Re‑use the CSV parser already present in the codebase.
import { parseRanksCsv } from '../src/bgg/csv-ranks.js';

async function main() {
  const supabaseUrl = process.env.SUPABASE_URL;
  const serviceKey = process.env.SUPABASE_SERVICE_KEY;

  if (!supabaseUrl || !serviceKey) {
    console.error('Missing SUPABASE_URL or SUPABASE_SERVICE_KEY environment variables.');
    process.exit(1);
  }

  const supabase = createClient(supabaseUrl, serviceKey);

  // Locate the CSV – it lives at the repo root.
  const csvPath = path.resolve(__dirname, '../../boardgames_ranks.csv');
  if (!existsSync(csvPath)) {
    console.error(`CSV file not found at ${csvPath}`);
    process.exit(1);
  }

  const csvText = readFileSync(csvPath, { encoding: 'utf8' });
  const rows = parseRanksCsv(csvText);
  console.log(`Parsed ${rows.length} rows from CSV`);

  let success = 0;
  for (const row of rows) {
    const payload = {
      name: row.name,
      kind: row.isExpansion ? 'expansion' : 'base',
      bgg_id: row.bggId,
      bgg_rating: row.average,
      bgg_rank: row.rank,
    } as Record<string, unknown>;

    const { error } = await supabase
      .from('game')
      .upsert(payload, { onConflict: 'bgg_id' });
    if (error) {
      console.error(`Failed to upsert BGG id ${row.bggId}: ${error.message}`);
    } else {
      success++;
    }
  }

  console.log(`Successfully upserted ${success} records out of ${rows.length}`);
  process.exit(0);
}

main().catch((e) => {
  console.error('Unexpected error:', e);
  process.exit(1);
});
