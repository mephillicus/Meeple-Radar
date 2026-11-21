import fs from 'node:fs'
import path from 'node:path'

export const MAX_ONLINE_ATTEMPTS = 3
export const DEFAULT_SKIP_TTL_DAYS = 30

export type SkipEntry = {
  attempts: number
  skippedAt: string
}

export type SkipStoreOptions = {
  ttlDays?: number
  now?: () => Date
}

export type SkipStore = {
  shouldSkip: (normalizedTitle: string) => boolean
  record: (normalizedTitle: string) => number
  save: () => void
}

const MS_PER_DAY = 24 * 60 * 60 * 1000

function isExpired(entry: SkipEntry, nowMs: number, ttlMs: number): boolean {
  const ts = Date.parse(entry.skippedAt)
  if (Number.isNaN(ts)) return true
  return nowMs - ts >= ttlMs
}

export function createSkipStore(filePath: string, opts: SkipStoreOptions = {}): SkipStore {
  const ttlDays = opts.ttlDays ?? DEFAULT_SKIP_TTL_DAYS
  const ttlMs = ttlDays * MS_PER_DAY
  const nowFn = opts.now ?? (() => new Date())
  const map: Record<string, SkipEntry> = {}

  try {
    const raw = fs.readFileSync(filePath, 'utf8')
    const parsed = JSON.parse(raw)
    if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
      const nowIso = nowFn().toISOString()
      const nowMs = nowFn().getTime()
      for (const [key, val] of Object.entries(parsed as Record<string, unknown>)) {
        if (typeof val === 'number' && Number.isFinite(val)) {
          // Retrocompatibilità con il vecchio formato { [title]: number }
          map[key] = { attempts: val, skippedAt: nowIso }
        } else if (
          val !== null &&
          typeof val === 'object' &&
          typeof (val as Record<string, unknown>).attempts === 'number' &&
          Number.isFinite((val as Record<string, unknown>).attempts) &&
          typeof (val as Record<string, unknown>).skippedAt === 'string'
        ) {
          const entry: SkipEntry = {
            attempts: Number((val as Record<string, unknown>).attempts),
            skippedAt: String((val as Record<string, unknown>).skippedAt),
          }
          if (!isExpired(entry, nowMs, ttlMs)) {
            map[key] = entry
          }
        }
      }
    }
  } catch {
    // file assente o corrotto -> mappa vuota
  }

  return {
    shouldSkip: (normalizedTitle: string): boolean => {
      const entry = map[normalizedTitle]
      if (!entry) return false
      if (isExpired(entry, nowFn().getTime(), ttlMs)) {
        delete map[normalizedTitle]
        return false
      }
      return entry.attempts >= MAX_ONLINE_ATTEMPTS
    },
    record: (normalizedTitle: string): number => {
      const nowDate = nowFn()
      const current = map[normalizedTitle]
      const baseAttempts =
        current && !isExpired(current, nowDate.getTime(), ttlMs) ? current.attempts : 0
      const next = baseAttempts + 1
      map[normalizedTitle] = { attempts: next, skippedAt: nowDate.toISOString() }
      return next
    },
    save: (): void => {
      const nowMs = nowFn().getTime()
      for (const key of Object.keys(map)) {
        if (isExpired(map[key], nowMs, ttlMs)) {
          delete map[key]
        }
      }
      fs.mkdirSync(path.dirname(filePath), { recursive: true })
      fs.writeFileSync(filePath, JSON.stringify(map), 'utf8')
    },
  }
}
