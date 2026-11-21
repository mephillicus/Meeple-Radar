import type { ListingStore, RunLogEntry } from './db.js'
import type { Source } from './types.js'

export type HealthWarning = {
  sourceId: string
  reason: 'consecutive_zero' | 'repeated_failure'
  consecutiveCount: number
  message: string
}

export type HealthReport = {
  checkedRuns: number
  warnings: HealthWarning[]
  webhookSent?: boolean
  error?: string
}

export type RunHealthSnapshot = {
  perSource?: Record<string, number>
  failures?: Record<string, string>
  zeroed?: string[]
}

const DEFAULT_THRESHOLD = 2
const WEBHOOK_TIMEOUT_MS = 10_000

function isZeroInRun(run: RunHealthSnapshot, sourceId: string): boolean {
  if (run.zeroed && run.zeroed.includes(sourceId)) return true
  if (run.perSource && sourceId in run.perSource && run.perSource[sourceId] === 0) return true
  return false
}

function isFailedInRun(run: RunHealthSnapshot, sourceId: string): boolean {
  return Boolean(run.failures && sourceId in run.failures && run.failures[sourceId])
}

export function evaluateSourceHealth(args: {
  enabledSourceIds: string[]
  runs: RunHealthSnapshot[]
  consecutiveThreshold?: number
}): HealthWarning[] {
  const { enabledSourceIds, runs, consecutiveThreshold = DEFAULT_THRESHOLD } = args
  const warnings: HealthWarning[] = []

  for (const sourceId of enabledSourceIds) {
    let consecutiveZero = 0
    for (const run of runs) {
      if (isZeroInRun(run, sourceId)) consecutiveZero++
      else break
    }
    if (consecutiveZero >= consecutiveThreshold) {
      warnings.push({
        sourceId,
        reason: 'consecutive_zero',
        consecutiveCount: consecutiveZero,
        message: `Source "${sourceId}" returned 0 items for ${consecutiveZero} consecutive runs`,
      })
    }

    let consecutiveFailure = 0
    for (const run of runs) {
      if (isFailedInRun(run, sourceId)) consecutiveFailure++
      else break
    }
    if (consecutiveFailure >= consecutiveThreshold) {
      warnings.push({
        sourceId,
        reason: 'repeated_failure',
        consecutiveCount: consecutiveFailure,
        message: `Source "${sourceId}" failed for ${consecutiveFailure} consecutive runs`,
      })
    }
  }

  return warnings
}

function toSnapshot(entry: RunLogEntry): RunHealthSnapshot {
  const d = entry.details
  if (!d || typeof d !== 'object') return {}
  return {
    perSource: (d.perSource && typeof d.perSource === 'object' ? d.perSource : undefined) as
      | Record<string, number>
      | undefined,
    failures: (d.failures && typeof d.failures === 'object' ? d.failures : undefined) as
      | Record<string, string>
      | undefined,
    zeroed: Array.isArray(d.zeroed) ? d.zeroed.map(String) : undefined,
  }
}

/**
 * Controllo salute post-raccolta (P2.1).
 * Controlla le ultime 3 run (corrente + ultime 2 in run_log) per ogni fonte
 * abilitata ed emette warning strutturati + webhook opzionale.
 * Non lancia mai eccezioni e non fa mai fallire la run.
 */
export async function runHealthCheck(args: {
  store: Pick<ListingStore, 'getRecentRunLogs'>
  sources: Source[]
  currentReport: RunHealthSnapshot
  webhookUrl?: string
  fetchFn?: typeof fetch
  logger?: (msg: string) => void
}): Promise<HealthReport> {
  const {
    store,
    sources,
    currentReport,
    webhookUrl = process.env.HEALTH_WEBHOOK_URL,
    fetchFn = fetch,
    logger = (msg) => console.warn(msg),
  } = args

  try {
    const enabledSourceIds = sources.filter((s) => s.enabled).map((s) => s.id)
    const previousLogs = store.getRecentRunLogs ? await store.getRecentRunLogs(2) : []
    const runs: RunHealthSnapshot[] = [currentReport, ...previousLogs.map(toSnapshot)]
    const warnings = evaluateSourceHealth({ enabledSourceIds, runs })

    for (const w of warnings) {
      logger(JSON.stringify({ level: 'warn', event: 'collector_health_warning', ...w }))
    }

    let webhookSent: boolean | undefined
    if (warnings.length > 0 && webhookUrl) {
      try {
        const res = await fetchFn(webhookUrl, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            event: 'collector_health_warning',
            checkedRuns: runs.length,
            warnings,
          }),
          signal: AbortSignal.timeout(WEBHOOK_TIMEOUT_MS),
        })
        webhookSent = res.ok
      } catch {
        webhookSent = false
      }
    }

    return {
      checkedRuns: runs.length,
      warnings,
      ...(webhookSent !== undefined && { webhookSent }),
    }
  } catch (err) {
    return {
      checkedRuns: 0,
      warnings: [],
      error: err instanceof Error ? err.message : String(err),
    }
  }
}
