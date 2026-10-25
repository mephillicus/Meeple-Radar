import fs from 'node:fs'

export const MAX_ONLINE_ATTEMPTS = 3

export type SkipStore = {
  shouldSkip: (normalizedTitle: string) => boolean
  record: (normalizedTitle: string) => number
  save: () => void
}

export function createSkipStore(path: string): SkipStore {
  let map: Record<string, number> = {}
  try {
    const raw = fs.readFileSync(path, 'utf8')
    const parsed = JSON.parse(raw)
    if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
      map = parsed as Record<string, number>
    }
  } catch {
    map = {}
  }

  return {
    shouldSkip: (normalizedTitle: string): boolean => (map[normalizedTitle] ?? 0) >= MAX_ONLINE_ATTEMPTS,
    record: (normalizedTitle: string): number => {
      const next = (map[normalizedTitle] ?? 0) + 1
      map[normalizedTitle] = next
      return next
    },
    save: (): void => {
      fs.writeFileSync(path, JSON.stringify(map), 'utf8')
    },
  }
}
