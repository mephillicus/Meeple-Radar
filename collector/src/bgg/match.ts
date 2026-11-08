import { normalizeTitle } from '../normalize.js'
import type { RanksIndex, RankRow } from './csv-ranks.js'
import { matchCsv } from './csv-ranks.js'
import type { GeekItem } from './online.js'

// Soglie del "certo": similarità minima del miglior candidato e distacco
// minimo dal secondo. Un refuso di una lettera su dieci caratteri (0.9)
// passa; due candidati alla pari no. Titoli localizzati ("I Lord di
// Waterdeep" ~0.7) restano dubbi e finiscono in review.
export const CERTAIN_SIMILARITY = 0.9
export const CERTAIN_MARGIN = 0.04

// Il prefilter a token scarta i troppo corti: articoli e preposizioni
// collegherebbero mezzo catalogo. Oltre al token intero indicizza anche il
// suo prefisso di 4 caratteri: senza quello un refuso su una parola intera
// ("carcasonne") non condividerebbe nessun token con "carcassonne".
const MIN_TOKEN_LENGTH = 4
const PREFIX_LENGTH = 4

function tokensOf(normalizedTitle: string): string[] {
  return normalizedTitle.split(' ').filter((t) => t.length >= MIN_TOKEN_LENGTH)
}

export function similarity(a: string, b: string): number {
  if (a === b) return 1
  if (a.length === 0 || b.length === 0) return 0
  let prev = new Array<number>(b.length + 1)
  let cur = new Array<number>(b.length + 1)
  for (let j = 0; j <= b.length; j++) prev[j] = j
  for (let i = 1; i <= a.length; i++) {
    cur[0] = i
    for (let j = 1; j <= b.length; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1
      cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
    }
    ;[prev, cur] = [cur, prev]
  }
  return 1 - prev[b.length] / Math.max(a.length, b.length)
}

// token (normalizzato) e prefissi -> normalized-title dei nomi BGG che li contengono.
export function buildCsvTokenIndex(index: RanksIndex): Map<string, Set<string>> {
  const tokens = new Map<string, Set<string>>()
  const add = (token: string, key: string): void => {
    const bucket = tokens.get(token)
    if (bucket) bucket.add(key)
    else tokens.set(token, new Set([key]))
  }
  for (const key of index.byName.keys()) {
    for (const token of tokensOf(key)) {
      add(token, key)
      add(token.slice(0, PREFIX_LENGTH), key)
    }
  }
  return tokens
}

export type CsvCandidate = { row: RankRow; score: number }

export function findCsvCandidates(
  index: RanksIndex,
  tokenIndex: Map<string, Set<string>>,
  normalizedTitle: string,
  limit: number,
): CsvCandidate[] {
  const keys = new Set<string>()
  for (const token of tokensOf(normalizedTitle)) {
    for (const key of tokenIndex.get(token) ?? []) keys.add(key)
    for (const key of tokenIndex.get(token.slice(0, PREFIX_LENGTH)) ?? []) keys.add(key)
  }
  const scored: CsvCandidate[] = []
  for (const key of keys) {
    const rows = index.byName.get(key)
    if (!rows) continue
    const score = similarity(normalizedTitle, key)
    for (const row of rows) scored.push({ row, score })
  }
  scored.sort((a, b) => b.score - a.score || (b.row.usersrated ?? 0) - (a.row.usersrated ?? 0) || a.row.bggId - b.row.bggId)
  return scored.slice(0, limit)
}

// Il certo dal CSV: match esatto unico (come il percorso CSV del daily
// enrich, espansioni comprese), oppure similarità oltre soglia con distacco
// dal secondo — per quello solo giochi base: un'espansione assegnata per
// refuso sarebbe peggiore di una review da compilare.
export function pickCertainCsv(
  index: RanksIndex,
  tokenIndex: Map<string, Set<string>>,
  normalizedTitle: string,
): RankRow | null {
  const exact = matchCsv(index, normalizedTitle)
  if (exact !== null) return exact
  const candidates = findCsvCandidates(index, tokenIndex, normalizedTitle, 2)
  const top = candidates[0]
  if (!top || top.row.isExpansion) return null
  if (top.score < CERTAIN_SIMILARITY) return null
  if ((candidates[1]?.score ?? 0) > top.score - CERTAIN_MARGIN) return null
  return top.row
}

export function itemScore(item: GeekItem, normalizedTitle: string): number {
  let best = similarity(normalizedTitle, normalizeTitle(item.name))
  for (const alt of item.alternateNames) {
    best = Math.max(best, similarity(normalizedTitle, normalizeTitle(alt)))
  }
  return best
}

// Il certo online, stessa soglia del CSV: solo subtype boardgame, il
// miglior nome (primario o alternativo) sopra soglia e staccato.
export function pickCertainItems(items: GeekItem[], normalizedTitle: string): GeekItem | null {
  const scored = items
    .map((item) => ({ item, score: itemScore(item, normalizedTitle) }))
    .sort((a, b) => b.score - a.score)
  const top = scored[0]
  if (!top || top.item.subtype !== 'boardgame') return null
  if (top.score < CERTAIN_SIMILARITY) return null
  if ((scored[1]?.score ?? 0) > top.score - CERTAIN_MARGIN) return null
  return top.item
}
