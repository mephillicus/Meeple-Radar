import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  formatDuration,
  formatHttpErrors,
  formatSourceProgressLine,
  renderCollectSummaryMarkdown,
  renderCollectionSyncSummaryMarkdown,
  renderConsoleSummary,
  sourceKindLabel,
  writeGithubStepSummary,
} from '../src/report-ui.js'
import type { RunReport } from '../src/run.js'
import type { Source } from '../src/types.js'

const makeSource = (overrides: Partial<Source> & Pick<Source, 'id' | 'kind'>): Source => ({
  name: overrides.id,
  baseUrl: 'https://example.com',
  enabled: true,
  ...overrides,
})

describe('formatDuration', () => {
  it('formats milliseconds under 1s', () => {
    expect(formatDuration(0)).toBe('0ms')
    expect(formatDuration(450)).toBe('450ms')
    expect(formatDuration(999)).toBe('999ms')
  })

  it('formats seconds under 1m', () => {
    expect(formatDuration(1000)).toBe('1s')
    expect(formatDuration(12400)).toBe('12s')
    expect(formatDuration(59999)).toBe('59s')
  })

  it('formats minutes and zero-padded seconds at or above 1m', () => {
    expect(formatDuration(60000)).toBe('1m 00s')
    expect(formatDuration(65000)).toBe('1m 05s')
    expect(formatDuration(135000)).toBe('2m 15s')
  })
})

describe('sourceKindLabel & formatHttpErrors', () => {
  it('maps roundups to articoli mensili and preserves other kinds', () => {
    expect(sourceKindLabel('roundups')).toBe('articoli mensili')
    expect(sourceKindLabel('shopify')).toBe('shopify')
    expect(sourceKindLabel('headless')).toBe('headless')
    expect(sourceKindLabel('html')).toBe('html')
    expect(sourceKindLabel('woocommerce')).toBe('woocommerce')
    expect(sourceKindLabel('wordpress')).toBe('wordpress')
    expect(sourceKindLabel('news')).toBe('news')
    expect(sourceKindLabel('gemini')).toBe('gemini')
  })

  it('formats HTTP error maps cleanly', () => {
    expect(formatHttpErrors(undefined)).toBe('')
    expect(formatHttpErrors({})).toBe('')
    expect(formatHttpErrors({ '429': 4 })).toBe('HTTP 429×4')
    expect(formatHttpErrors({ '503': 2, '429': 4 })).toBe('HTTP 429×4, HTTP 503×2')
  })
})

describe('formatSourceProgressLine', () => {
  it('formats a shop success line with filtered count and in-progress sources', () => {
    const line = formatSourceProgressLine({
      index: 3,
      total: 15,
      source: makeSource({ id: 'dungeondice', kind: 'html' }),
      discovered: 120,
      extracted: 105,
      durationMs: 4200,
      inProgress: ['cranio', 'uplay'],
    })
    expect(line).toContain('✅')
    expect(line).toContain('[03/15]')
    expect(line).toContain('dungeondice (html)')
    expect(line).toContain('105/120 salvati')
    expect(line).toContain('(-15 filtrati)')
    expect(line).toContain('(4s)')
    expect(line).toContain('| in corso: cranio, uplay')
  })

  it('formats roundups with uscite label and tutte completate when idle', () => {
    const line = formatSourceProgressLine({
      index: 15,
      total: 15,
      source: makeSource({ id: 'houseofgames', kind: 'roundups' }),
      discovered: 64,
      extracted: 64,
      durationMs: 65000,
      inProgress: [],
    })
    expect(line).toContain('✅')
    expect(line).toContain('[15/15]')
    expect(line).toContain('houseofgames (articoli mensili)')
    expect(line).toContain('64/64 uscite')
    expect(line).not.toContain('filtrati')
    expect(line).toContain('(1m 05s)')
    expect(line).toContain('| tutte completate')
  })

  it('shows warning icon on zero discovered or HTTP errors', () => {
    const zeroLine = formatSourceProgressLine({
      index: 1,
      total: 4,
      source: makeSource({ id: 'empty', kind: 'shopify' }),
      discovered: 0,
      extracted: 0,
      durationMs: 500,
      inProgress: [],
    })
    expect(zeroLine.startsWith('⚠️')).toBe(true)

    const httpErrLine = formatSourceProgressLine({
      index: 2,
      total: 4,
      source: makeSource({ id: 'throttled', kind: 'woocommerce' }),
      discovered: 10,
      extracted: 10,
      durationMs: 2500,
      httpErrors: { '429': 4 },
      inProgress: [],
    })
    expect(httpErrLine.startsWith('⚠️')).toBe(true)
    expect(httpErrLine).toContain('HTTP 429×4')
  })

  it('shows failure icon and error message on failed source', () => {
    const line = formatSourceProgressLine({
      index: 2,
      total: 5,
      source: makeSource({ id: 'broken', kind: 'headless' }),
      discovered: 0,
      extracted: 0,
      durationMs: 900,
      failed: 'timeout',
      inProgress: ['s3'],
    })
    expect(line.startsWith('❌')).toBe(true)
    expect(line).toContain('[02/05]')
    expect(line).toContain('errore: timeout')
    expect(line).toContain('| in corso: s3')
  })
})

describe('renderCollectSummaryMarkdown & renderConsoleSummary', () => {
  const sources: Source[] = [
    makeSource({ id: 'cranio', name: 'Cranio Creations', kind: 'shopify' }),
    makeSource({ id: 'houseofgames', name: 'House of Games', kind: 'roundups' }),
    makeSource({ id: 'broken', name: 'Broken Shop', kind: 'html' }),
    makeSource({ id: 'disabled-src', name: 'Disabled', kind: 'html', enabled: false }),
  ]

  const report: RunReport = {
    ok: false,
    perSource: { cranio: 20, houseofgames: 12 },
    failures: { broken: 'HTTP 500' },
    zeroed: [],
    metrics: {
      cranio: { discoveredCount: 20, extractedCount: 18, durationMs: 3200, httpErrorsByStatus: {} },
      houseofgames: {
        discoveredCount: 12,
        extractedCount: 12,
        durationMs: 7500,
        httpErrorsByStatus: { '429': 1 },
      },
      broken: { discoveredCount: 0, extractedCount: 0, durationMs: 400, httpErrorsByStatus: { '500': 1 } },
    },
    bgg: {
      candidates: 30,
      linked: 15,
      viaCsv: 10,
      viaSearch: 5,
      listingsLinked: 15,
      unresolved: 15,
      onlineRequests: 5,
      hydratedGames: 8,
      csvMissing: false,
    },
    alerts: {
      detected: 4,
      inserted: 3,
      byType: { back_in_stock: 1, price_drop: 2, new_expansion: 0 },
    },
    health: {
      checkedRuns: 5,
      warnings: [
        {
          sourceId: 'broken',
          reason: 'repeated_failure',
          consecutiveCount: 2,
          message: 'Source "broken" failed for 2 consecutive runs',
        },
      ],
    },
  }

  it('renders Markdown summary with table and sections', () => {
    const md = renderCollectSummaryMarkdown(report, sources, 72000)
    expect(md).toContain('## 🎲 Meeple Radar — Riepilogo Collect')
    expect(md).toContain('❌ Con errori')
    expect(md).toContain('1m 12s')
    expect(md).toContain('| Stato | Fonte | Tipo | Trovati | Salvati | Filtrati | Errori HTTP | Durata |')
    expect(md).toContain('| ✅ | Cranio Creations (`cranio`) | shopify | 20 | 18 | 2 | — | 3s |')
    expect(md).toContain('| ⚠️ | House of Games (`houseofgames`) | articoli mensili | 12 | 12 | 0 | HTTP 429×1 | 7s |')
    expect(md).toContain('| ❌ | Broken Shop (`broken`) | html | 0 | 0 | 0 | Errore: HTTP 500 | 400ms |')
    expect(md).not.toContain('disabled-src')
    expect(md).toContain('### BGG Enrichment')
    expect(md).toContain('viaCsv:** 10')
    expect(md).toContain('viaSearch:** 5')
    expect(md).toContain('hydratedGames:** 8')
    expect(md).toContain('unresolved:** 15')
    expect(md).toContain('### Alert')
    expect(md).toContain('back_in_stock:** 1')
    expect(md).toContain('price_drop:** 2')
    expect(md).toContain('new_expansion:** 0')
    expect(md).toContain('### Salute Fonti')
    expect(md).toContain('broken')
  })

  it('renders compact console summary', () => {
    const text = renderConsoleSummary(report, sources, 72000)
    expect(text).toContain('1m 12s')
    expect(text).toContain('1 OK, 1 warning, 1 fallite')
    expect(text).toContain('viaCsv=10')
    expect(text).toContain('viaSearch=5')
    expect(text).toContain('hydratedGames=8')
    expect(text).toContain('back_in_stock=1')
    expect(text).toContain('1 avvisi')
  })
})

describe('renderCollectionSyncSummaryMarkdown', () => {
  it('renders successful sync summary', () => {
    const md = renderCollectionSyncSummaryMarkdown({
      ok: true,
      synced: 42,
      owned: 30,
      wishlist: 12,
      autofavorite: { inserted: 5, skipped: 25 },
    })
    expect(md).toContain('✅ Completato')
    expect(md).toContain('42')
    expect(md).toContain('30')
    expect(md).toContain('12')
    expect(md).toContain('5')
  })

  it('renders error sync summary', () => {
    const md = renderCollectionSyncSummaryMarkdown({
      ok: false,
      synced: 0,
      owned: 0,
      wishlist: 0,
      autofavorite: { inserted: 0, skipped: 0 },
      error: 'BGG 503',
    })
    expect(md).toContain('❌ Con errori')
    expect(md).toContain('BGG 503')
  })
})

describe('writeGithubStepSummary', () => {
  it('appends markdown to the target path and ignores empty/unwritable paths', () => {
    const dir = mkdtempSync(join(tmpdir(), 'meeple-summary-'))
    try {
      const file = join(dir, 'summary.md')
      writeGithubStepSummary('## Part 1', file)
      writeGithubStepSummary('## Part 2', file)
      expect(readFileSync(file, 'utf8')).toBe('## Part 1\n## Part 2\n')

      // Empty/undefined path is a no-op
      expect(() => writeGithubStepSummary('ignored', '')).not.toThrow()
      expect(() => writeGithubStepSummary('ignored', undefined)).not.toThrow()

      // Non-existent directory never throws (best-effort)
      expect(() => writeGithubStepSummary('ignored', join(dir, 'missing', 'nested', 'summary.md'))).not.toThrow()
    } finally {
      rmSync(dir, { recursive: true, force: true })
    }
  })
})
