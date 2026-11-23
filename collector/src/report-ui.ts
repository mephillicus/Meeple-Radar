import { appendFileSync } from 'node:fs'
import type { Source, SourceKind } from './types.js'
import type { RunReport } from './run.js'
import type { CollectionSyncReport } from './run-collection.js'

const SOURCE_KIND_LABELS: Record<SourceKind, string> = {
  roundups: 'articoli mensili',
  headless: 'headless',
  html: 'html',
  shopify: 'shopify',
  woocommerce: 'woocommerce',
  wordpress: 'wordpress',
  news: 'news',
  gemini: 'gemini',
}

export function formatDuration(ms: number): string {
  const clamped = Math.max(0, Math.round(ms))
  if (clamped < 1000) return `${clamped}ms`
  const totalSeconds = Math.floor(clamped / 1000)
  if (totalSeconds < 60) return `${totalSeconds}s`
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${minutes}m ${String(seconds).padStart(2, '0')}s`
}

export function sourceKindLabel(kind: SourceKind): string {
  return SOURCE_KIND_LABELS[kind] ?? kind
}

export function formatHttpErrors(errors?: Record<string, number>): string {
  if (!errors) return ''
  const entries = Object.entries(errors)
    .filter(([, count]) => count > 0)
    .sort(([a], [b]) => a.localeCompare(b))
  if (entries.length === 0) return ''
  return entries
    .map(([code, count]) => (/^\d+$/.test(code) ? `HTTP ${code}×${count}` : `${code}×${count}`))
    .join(', ')
}

export type SourceProgressLineArgs = {
  index: number
  total: number
  source: Source
  discovered: number
  extracted: number
  durationMs: number
  failed?: string
  httpErrors?: Record<string, number>
  inProgress: string[]
}

export function formatSourceProgressLine(args: SourceProgressLineArgs): string {
  const { index, total, source, discovered, extracted, durationMs, failed, httpErrors, inProgress } = args
  const httpErrStr = formatHttpErrors(httpErrors)
  const icon = failed ? '❌' : discovered === 0 || httpErrStr !== '' ? '⚠️' : '✅'
  const counter = `[${String(index).padStart(2, '0')}/${String(total).padStart(2, '0')}]`
  const kind = sourceKindLabel(source.kind)
  const duration = formatDuration(durationMs)
  const tail = inProgress.length > 0 ? `in corso: ${inProgress.join(', ')}` : 'tutte completate'
  const httpErrPart = httpErrStr ? ` [${httpErrStr}]` : ''

  if (failed) {
    return `${icon} ${counter} ${source.id} (${kind}) — errore: ${failed}${httpErrPart} (${duration}) | ${tail}`
  }

  const filtered = Math.max(0, discovered - extracted)
  const countLabel = source.kind === 'roundups' ? 'uscite' : 'salvati'
  const filteredPart = filtered > 0 ? ` (-${filtered} filtrati)` : ''

  return `${icon} ${counter} ${source.id} (${kind}) — ${extracted}/${discovered} ${countLabel}${filteredPart}${httpErrPart} (${duration}) | ${tail}`
}

function classifySources(report: RunReport, sources: Source[]): { ok: number; warn: number; fail: number } {
  const enabled = sources.filter((s) => s.enabled)
  let ok = 0
  let warn = 0
  let fail = 0
  for (const source of enabled) {
    const failed = Boolean(report.failures[source.id])
    if (failed) {
      fail++
      continue
    }
    const m = report.metrics?.[source.id]
    const discovered = m?.discoveredCount ?? report.perSource[source.id] ?? 0
    const hasHttpErrors = formatHttpErrors(m?.httpErrorsByStatus) !== ''
    if (discovered === 0 || hasHttpErrors) {
      warn++
    } else {
      ok++
    }
  }
  return { ok, warn, fail }
}

export function renderConsoleSummary(report: RunReport, sources: Source[], totalDurationMs: number): string {
  const { ok, warn, fail } = classifySources(report, sources)
  const status = report.ok ? '✅ Completato' : '❌ Con errori'
  const lines: string[] = [
    '────────────────────────────────────────────────────────────',
    `🎲 RIEPILOGO COLLECT — ${status} in ${formatDuration(totalDurationMs)}`,
    `   Fonti: ${ok} OK, ${warn} warning, ${fail} fallite`,
  ]

  if (report.bgg) {
    if (report.bgg.error) {
      lines.push(`   BGG: errore (${report.bgg.error})`)
    } else {
      lines.push(
        `   BGG: viaCsv=${report.bgg.viaCsv}, viaSearch=${report.bgg.viaSearch}, hydratedGames=${report.bgg.hydratedGames ?? 0}, unresolved=${report.bgg.unresolved}`,
      )
    }
  }

  if (report.alerts) {
    if (report.alerts.error) {
      lines.push(`   Alert: errore (${report.alerts.error})`)
    } else {
      const { back_in_stock, price_drop, new_expansion } = report.alerts.byType
      lines.push(
        `   Alert: ${report.alerts.inserted} inseriti (back_in_stock=${back_in_stock}, price_drop=${price_drop}, new_expansion=${new_expansion})`,
      )
    }
  }

  if (report.health) {
    if (report.health.error) {
      lines.push(`   Salute fonti: errore (${report.health.error})`)
    } else if (report.health.warnings.length === 0) {
      lines.push(`   Salute fonti: nessun avviso (${report.health.checkedRuns} run controllate)`)
    } else {
      lines.push(`   Salute fonti: ${report.health.warnings.length} avvisi`)
      for (const w of report.health.warnings) {
        lines.push(`     • ${w.sourceId}: ${w.message}`)
      }
    }
  }

  const nonSourceFailures = Object.entries(report.failures).filter(
    ([id]) => !sources.some((s) => s.enabled && s.id === id),
  )
  for (const [id, msg] of nonSourceFailures) {
    lines.push(`   ❌ ${id}: ${msg}`)
  }

  lines.push('────────────────────────────────────────────────────────────')
  return lines.join('\n')
}

export function renderCollectSummaryMarkdown(
  report: RunReport,
  sources: Source[],
  totalDurationMs: number,
): string {
  const statusBadge = report.ok ? '✅ Completato' : '❌ Con errori'
  const enabled = sources.filter((s) => s.enabled)
  const lines: string[] = [
    '## 🎲 Meeple Radar — Riepilogo Collect',
    '',
    `**Stato:** ${statusBadge} · **Durata totale:** ${formatDuration(totalDurationMs)}`,
    '',
    '| Stato | Fonte | Tipo | Trovati | Salvati | Filtrati | Errori HTTP | Durata |',
    '| :---: | :--- | :--- | ---: | ---: | ---: | :--- | ---: |',
  ]

  for (const source of enabled) {
    const m = report.metrics?.[source.id]
    const failed = report.failures[source.id]
    const discovered = m?.discoveredCount ?? report.perSource[source.id] ?? 0
    const extracted = m?.extractedCount ?? 0
    const filtered = Math.max(0, discovered - extracted)
    const httpErrStr = formatHttpErrors(m?.httpErrorsByStatus)
    const status = failed ? '❌' : discovered === 0 || httpErrStr !== '' ? '⚠️' : '✅'
    const duration = m ? formatDuration(m.durationMs) : '—'
    const httpCol = failed ? `Errore: ${failed}` : httpErrStr || '—'
    lines.push(
      `| ${status} | ${source.name} (\`${source.id}\`) | ${sourceKindLabel(source.kind)} | ${discovered} | ${extracted} | ${filtered} | ${httpCol} | ${duration} |`,
    )
  }

  const nonSourceFailures = Object.entries(report.failures).filter(
    ([id]) => !enabled.some((s) => s.id === id),
  )
  if (nonSourceFailures.length > 0) {
    lines.push('', '### ❌ Errori globali')
    for (const [id, msg] of nonSourceFailures) {
      lines.push(`- **${id}**: ${msg}`)
    }
  }

  lines.push('', '### BGG Enrichment')
  if (!report.bgg) {
    lines.push('_Non eseguito_')
  } else if (report.bgg.error) {
    lines.push(`❌ Errore: ${report.bgg.error}`)
  } else {
    lines.push(
      `- **viaCsv:** ${report.bgg.viaCsv}`,
      `- **viaSearch:** ${report.bgg.viaSearch}`,
      `- **hydratedGames:** ${report.bgg.hydratedGames ?? 0}`,
      `- **unresolved:** ${report.bgg.unresolved}`,
    )
  }

  lines.push('', '### Alert')
  if (!report.alerts) {
    lines.push('_Non eseguito_')
  } else if (report.alerts.error) {
    lines.push(`❌ Errore: ${report.alerts.error}`)
  } else {
    lines.push(
      `- **back_in_stock:** ${report.alerts.byType.back_in_stock}`,
      `- **price_drop:** ${report.alerts.byType.price_drop}`,
      `- **new_expansion:** ${report.alerts.byType.new_expansion}`,
    )
  }

  lines.push('', '### Salute Fonti')
  if (!report.health) {
    lines.push('_Non eseguito_')
  } else if (report.health.error) {
    lines.push(`❌ Errore: ${report.health.error}`)
  } else if (report.health.warnings.length === 0) {
    lines.push(`✅ Nessun avviso (${report.health.checkedRuns} run controllate)`)
  } else {
    for (const w of report.health.warnings) {
      lines.push(`- ⚠️ **${w.sourceId}** (${w.reason}, ×${w.consecutiveCount}): ${w.message}`)
    }
  }

  return lines.join('\n')
}

export function renderCollectionSyncSummaryMarkdown(report: CollectionSyncReport): string {
  const statusBadge = report.ok ? '✅ Completato' : '❌ Con errori'
  const lines: string[] = [
    '## 🎲 Meeple Radar — Riepilogo Collection Sync',
    '',
    `**Stato:** ${statusBadge}`,
    '',
  ]

  if (report.error) {
    lines.push(`❌ **Errore:** ${report.error}`)
  } else {
    lines.push(
      `- **Sincronizzati (synced):** ${report.synced}`,
      `- **Posseduti (owned):** ${report.owned}`,
      `- **Wishlist:** ${report.wishlist}`,
      `- **Preferiti automatici aggiunti (autofavorite.inserted):** ${report.autofavorite.inserted}`,
    )
  }

  return lines.join('\n')
}

export function writeGithubStepSummary(
  markdown: string,
  summaryPath: string | undefined = process.env.GITHUB_STEP_SUMMARY,
): void {
  const target = summaryPath?.trim()
  if (!target) return
  try {
    appendFileSync(target, `${markdown}\n`, 'utf8')
  } catch {
    // best-effort: never throw when writing GitHub step summary
  }
}
