import { expect, test, vi } from 'vitest'
import { evaluateSourceHealth, runHealthCheck } from '../src/health.js'
import type { Source } from '../src/types.js'

const src = (id: string, enabled = true): Source => ({
  id,
  name: id,
  kind: 'shopify',
  baseUrl: 'https://e.com',
  enabled,
})

test('evaluateSourceHealth flags enabled source with 0 items for 2+ consecutive runs', () => {
  const warnings = evaluateSourceHealth({
    enabledSourceIds: ['s1', 's2', 's3'],
    runs: [
      { perSource: { s1: 0, s2: 0, s3: 5 }, zeroed: ['s1', 's2'], failures: {} },
      { perSource: { s1: 0, s2: 3, s3: 4 }, zeroed: ['s1'], failures: {} },
      { perSource: { s1: 0, s2: 0, s3: 0 }, zeroed: ['s1', 's2', 's3'], failures: {} },
    ],
  })
  expect(warnings).toHaveLength(1)
  expect(warnings[0]).toMatchObject({
    sourceId: 's1',
    reason: 'consecutive_zero',
    consecutiveCount: 3,
  })
})

test('evaluateSourceHealth flags repeated failures for 2+ consecutive runs', () => {
  const warnings = evaluateSourceHealth({
    enabledSourceIds: ['s1', 's2'],
    runs: [
      { perSource: { s2: 10 }, failures: { s1: 'timeout' }, zeroed: [] },
      { perSource: { s2: 10 }, failures: { s1: '502' }, zeroed: [] },
    ],
  })
  expect(warnings).toHaveLength(1)
  expect(warnings[0]).toMatchObject({
    sourceId: 's1',
    reason: 'repeated_failure',
    consecutiveCount: 2,
  })
})

test('runHealthCheck ignores disabled sources, logs structured warnings, and posts webhook when configured', async () => {
  const store = {
    getRecentRunLogs: vi.fn().mockResolvedValue([
      { id: 2, ok: true, details: { perSource: { s1: 0, disabled: 0 }, zeroed: ['s1', 'disabled'], failures: {} }, startedAt: '2026-11-14T06:00:00Z', finishedAt: null },
      { id: 1, ok: true, details: { perSource: { s1: 5 }, zeroed: [], failures: {} }, startedAt: '2026-11-07T06:00:00Z', finishedAt: null },
    ]),
  }
  const logger = vi.fn()
  const fetchFn = vi.fn().mockResolvedValue({ ok: true, status: 200 })

  const report = await runHealthCheck({
    store,
    sources: [src('s1', true), src('disabled', false)],
    currentReport: { perSource: { s1: 0 }, zeroed: ['s1'], failures: {} },
    webhookUrl: 'https://hooks.example.com/health',
    fetchFn: fetchFn as any,
    logger,
  })

  expect(report.checkedRuns).toBe(3)
  expect(report.warnings).toHaveLength(1)
  expect(report.warnings[0].sourceId).toBe('s1')
  expect(report.webhookSent).toBe(true)
  expect(logger).toHaveBeenCalledTimes(1)
  expect(JSON.parse(logger.mock.calls[0][0])).toMatchObject({
    level: 'warn',
    event: 'collector_health_warning',
    sourceId: 's1',
  })
  expect(fetchFn).toHaveBeenCalledWith(
    'https://hooks.example.com/health',
    expect.objectContaining({ method: 'POST' }),
  )
})

test('runHealthCheck never throws even when store or webhook fails', async () => {
  const store = {
    getRecentRunLogs: vi.fn().mockRejectedValue(new Error('db down')),
  }
  const report = await runHealthCheck({
    store,
    sources: [src('s1', true)],
    currentReport: { perSource: { s1: 0 }, zeroed: ['s1'], failures: {} },
    webhookUrl: '',
    logger: vi.fn(),
  })
  expect(report.error).toBe('db down')
  expect(report.warnings).toEqual([])
})
