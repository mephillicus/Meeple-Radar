import fs from 'node:fs'
import { normalizeTitle } from '../normalize.js'

export type RankRow = {
  bggId: number
  name: string
  year: number
  rank: number | null
  average: number | null
  usersrated: number | null
  isExpansion: boolean
}

export type RanksIndex = {
  byName: Map<string, RankRow[]>
  byId: Map<number, RankRow>
}

function splitFields(line: string): string[] {
  const fields: string[] = []
  let cur = ''
  let inQuotes = false
  for (let i = 0; i < line.length; i++) {
    const ch = line[i]
    if (inQuotes) {
      if (ch === '"') {
        if (line[i + 1] === '"') {
          cur += '"'
          i++
        } else {
          inQuotes = false
        }
      } else {
        cur += ch
      }
    } else if (ch === '"') {
      inQuotes = true
    } else if (ch === ',') {
      fields.push(cur)
      cur = ''
    } else {
      cur += ch
    }
  }
  fields.push(cur)
  return fields
}

function numOrNull(raw: string | undefined): number | null {
  if (raw === undefined || raw.trim() === '') return null
  const n = Number(raw)
  return Number.isFinite(n) ? n : null
}

function numOrZero(raw: string | undefined): number {
  return numOrNull(raw) ?? 0
}

export function parseRanksCsv(text: string): RankRow[] {
  const lines = text.split(/\r?\n/)
  const headerIndex = lines.findIndex((line) => line.trim() !== '')
  if (headerIndex === -1) return []
  const header = splitFields(lines[headerIndex]).map((h) => h.trim())
  const idCol = header.indexOf('id')
  const nameCol = header.indexOf('name')
  if (idCol === -1 || nameCol === -1) return []
  const yearCol = header.indexOf('yearpublished')
  const rankCol = header.indexOf('rank')
  const averageCol = header.indexOf('average')
  const usersratedCol = header.indexOf('usersrated')
  const isExpansionCol = header.indexOf('is_expansion')

  const rows: RankRow[] = []
  for (let i = headerIndex + 1; i < lines.length; i++) {
    const line = lines[i]
    if (line.trim() === '') continue
    const fields = splitFields(line)
    const name = fields[nameCol]
    if (name === undefined || name.trim() === '') continue
    const bggId = numOrNull(fields[idCol])
    if (bggId === null) continue
    rows.push({
      bggId,
      name,
      year: numOrZero(fields[yearCol]),
      rank: numOrNull(fields[rankCol]),
      average: numOrNull(fields[averageCol]),
      usersrated: numOrNull(fields[usersratedCol]),
      isExpansion: fields[isExpansionCol]?.trim() === '1',
    })
  }
  return rows
}

export function buildRanksIndex(rows: RankRow[]): RanksIndex {
  const byName = new Map<string, RankRow[]>()
  const byId = new Map<number, RankRow>()
  for (const row of rows) {
    const key = normalizeTitle(row.name)
    const bucket = byName.get(key)
    if (bucket) {
      bucket.push(row)
    } else {
      byName.set(key, [row])
    }
    byId.set(row.bggId, row)
  }
  return { byName, byId }
}

export function loadRanksIndex(candidates: string[]): RanksIndex | null {
  for (const candidate of candidates) {
    if (fs.existsSync(candidate)) {
      const text = fs.readFileSync(candidate, 'utf8')
      return buildRanksIndex(parseRanksCsv(text))
    }
  }
  return null
}

export function matchCsv(index: RanksIndex, normalizedTitle: string): RankRow | null {
  const bucket = index.byName.get(normalizedTitle)
  if (!bucket || bucket.length !== 1) return null
  return bucket[0]
}
