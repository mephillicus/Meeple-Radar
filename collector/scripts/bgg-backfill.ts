import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { loadConfig } from '../src/config.js'
import { createSupabaseClient } from '../src/db.js'
import { loadRanksIndex } from '../src/bgg/csv-ranks.js'
import { runBggBackfill, type CapturedCandidates } from '../src/bgg/backfill.js'
import { createSkipStore } from '../src/bgg/skipped.js'
import { buildReviewRows, parseReviewCsv, reviewToCsv } from '../src/bgg/review.js'
import { applyReview } from '../src/bgg/apply-review.js'
import { createCurlFetcher } from '../src/adapters/headless/fetch.js'
import { bggSearchUrl, parseGeekItem, parseSearchIds } from '../src/bgg/online.js'
import { defaultFetch, USER_AGENT } from '../src/adapters/http.js'

const RANKS_CANDIDATES = ['data/boardgames_ranks.csv', 'boardgames_ranks.csv', '../boardgames_ranks.csv']
const CAPTURED_FILE = 'data/bgg-backfill-candidates.json'
const SKIPPED_FILE = 'data/bgg-backfill-skipped.json'
const DEFAULT_REVIEW_FILE = 'data/bgg-review.csv'

function usage(): never {
  console.error('usage: tsx scripts/bgg-backfill.ts certain [maxOnlineRequests] | review [outfile] | apply [infile]')
  process.exit(2)
}

const loadCaptured = (): CapturedCandidates => {
  try {
    const parsed = JSON.parse(readFileSync(CAPTURED_FILE, 'utf8'))
    return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
  } catch {
    return {}
  }
}

const saveCaptured = (data: CapturedCandidates): void => {
  mkdirSync('data', { recursive: true })
  writeFileSync(CAPTURED_FILE, JSON.stringify(data, null, 2), 'utf8')
}

const command = process.argv[2]
if (command !== 'certain' && command !== 'review' && command !== 'apply') usage()
mkdirSync('data', { recursive: true })

const { supabaseUrl, supabaseServiceKey } = loadConfig(process.env)
const store = createSupabaseClient(supabaseUrl, supabaseServiceKey)
const ranks = loadRanksIndex(RANKS_CANDIDATES)

const searchIds = async (query: string): Promise<number[] | null> => {
  const html = await createCurlFetcher()(bggSearchUrl(query))
  return html === null ? null : parseSearchIds(html)
}
const fetchGeekItem = async (id: number) => {
  const res = await defaultFetch(
    `https://api.geekdo.com/api/geekitems?objectid=${id}&objecttype=thing`,
    { 'User-Agent': USER_AGENT, Accept: 'application/json' },
  )
  return res.status === 200 ? parseGeekItem(await res.text(), id) : null
}

if (command === 'certain') {
  const maxOnlineRequests = Number(process.argv[3] ?? '200')
  const report = await runBggBackfill({
    store,
    ranks,
    searchIds,
    fetchGeekItem,
    skipped: createSkipStore(SKIPPED_FILE),
    maxOnlineRequests: Number.isFinite(maxOnlineRequests) && maxOnlineRequests > 0 ? maxOnlineRequests : 200,
    loadCaptured,
    saveCaptured,
  })
  console.log(JSON.stringify(report, null, 2))
} else if (command === 'review') {
  if (ranks === null) {
    console.error('boardgames_ranks.csv non trovato')
    process.exit(1)
  }
  const products = await store.findUnlinkedProducts()
  const rows = buildReviewRows(products, ranks, loadCaptured())
  const out = process.argv[3] ?? DEFAULT_REVIEW_FILE
  mkdirSync('data', { recursive: true })
  writeFileSync(out, reviewToCsv(rows), 'utf8')
  console.log(JSON.stringify({ products: rows.length, out }, null, 2))
} else {
  const file = process.argv[3] ?? DEFAULT_REVIEW_FILE
  const rows = parseReviewCsv(readFileSync(file, 'utf8'))
  const report = await applyReview({ store, ranks, fetchGeekItem }, rows)
  console.log(JSON.stringify(report, null, 2))
}
