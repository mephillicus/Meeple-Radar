import type { RanksIndex } from './csv-ranks.js'
import { splitDelimited } from './csv-ranks.js'
import type { CapturedCandidates } from './backfill.js'
import { buildCsvTokenIndex, findCsvCandidates } from './match.js'

export type ReviewCandidate = { name: string; bggId: number; year: number | null }

export type ReviewRow = {
  productId: number
  title: string
  normalizedTitle: string
  releaseDate: string | null
  candidates: ReviewCandidate[]
}

const CANDIDATE_LIMIT = 3
const BGGS_ID_COLUMN = 13

// I candidati catturati online (informazione costosa, gia in mano) hanno la
// precedenza; il CSV fuzzy riempie i buchi. Dedup per bgg_id, massimo tre.
export function buildReviewRows(
  products: Array<{ id: number; title: string; normalizedTitle: string; releaseDate: string | null }>,
  ranks: RanksIndex,
  captured: CapturedCandidates,
): ReviewRow[] {
  const tokenIndex = buildCsvTokenIndex(ranks)
  return products.map((product) => {
    const candidates: ReviewCandidate[] = []
    const seen = new Set<number>()
    const add = (candidate: ReviewCandidate): void => {
      if (seen.has(candidate.bggId) || candidates.length >= CANDIDATE_LIMIT) return
      seen.add(candidate.bggId)
      candidates.push(candidate)
    }
    for (const c of captured[product.normalizedTitle] ?? []) {
      add({ name: c.name, bggId: c.bggId, year: c.yearpublished })
    }
    for (const c of findCsvCandidates(ranks, tokenIndex, product.normalizedTitle, CANDIDATE_LIMIT)) {
      add({ name: c.row.name, bggId: c.row.bggId, year: c.row.year || null })
    }
    return {
      productId: product.id,
      title: product.title,
      normalizedTitle: product.normalizedTitle,
      releaseDate: product.releaseDate,
      candidates,
    }
  })
}

const HEADER = [
  'product_id', 'titolo', 'nome_normalizzato', 'data_uscita',
  'candidato_1', 'candidato_1_id', 'candidato_1_anno',
  'candidato_2', 'candidato_2_id', 'candidato_2_anno',
  'candidato_3', 'candidato_3_id', 'candidato_3_anno',
  'bgg_id', 'note',
]

// Excel italiano: separatore ';', BOM UTF-8, quoting classico.
function field(value: string | number | null): string {
  const s = value === null ? '' : String(value)
  return /[";\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}

export function reviewToCsv(rows: ReviewRow[]): string {
  const lines = [HEADER.join(';')]
  for (const row of rows) {
    const cells = [
      field(row.productId), field(row.title), field(row.normalizedTitle), field(row.releaseDate),
    ]
    for (let i = 0; i < CANDIDATE_LIMIT; i++) {
      const c = row.candidates[i]
      cells.push(field(c?.name ?? ''), field(c?.bggId ?? ''), field(c?.year ?? ''))
    }
    // bgg_id e note restano vuoti: sono le colonne che compila l'utente.
    cells.push('', '')
    lines.push(cells.join(';'))
  }
  return '\ufeff' + lines.join('\r\n') + '\r\n'
}

export function parseReviewCsv(text: string): Array<{ productId: number; bggId: number | null }> {
  const lines = text.replace(/^\ufeff/, '').split(/\r?\n/).filter((line) => line.trim() !== '')
  const out: Array<{ productId: number; bggId: number | null }> = []
  for (const line of lines.slice(1)) {
    const fields = splitDelimited(line, ';')
    const productId = Number(fields[0])
    if (!Number.isFinite(productId)) continue
    const bggRaw = (fields[BGGS_ID_COLUMN] ?? '').trim()
    const bggId = bggRaw === '' ? null : Number(bggRaw)
    out.push({ productId, bggId: bggId !== null && Number.isFinite(bggId) ? bggId : null })
  }
  return out
}
