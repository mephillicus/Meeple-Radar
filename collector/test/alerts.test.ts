import { expect, test, vi } from 'vitest'
import {
  detectExpansionAlerts,
  detectFavoriteAlerts,
  runAlerts,
} from '../src/alerts.js'
import type { FavoriteListingSnapshot } from '../src/db.js'

const snap = (overrides: Partial<FavoriteListingSnapshot>): FavoriteListingSnapshot => ({
  listingId: 10,
  gameId: 5,
  productId: 20,
  title: 'Dune: Imperium',
  url: 'https://shop.it/dune',
  sourceId: 'dungeondice',
  price: 45,
  currency: 'EUR',
  availability: 'available',
  history: [],
  ...overrides,
})

test('detectFavoriteAlerts detects back_in_stock from preorder/out_of_stock to available', () => {
  const alerts = detectFavoriteAlerts([
    snap({
      listingId: 10,
      history: [
        { listingId: 10, price: 50, currency: 'EUR', availability: 'available', recordedAt: '2026-11-21T06:00:00Z' },
        { listingId: 10, price: 50, currency: 'EUR', availability: 'preorder', recordedAt: '2026-11-14T06:00:00Z' },
      ],
    }),
    snap({
      listingId: 11,
      history: [
        { listingId: 11, price: 50, currency: 'EUR', availability: 'available', recordedAt: '2026-11-21T06:00:00Z' },
        { listingId: 11, price: 50, currency: 'EUR', availability: 'available', recordedAt: '2026-11-14T06:00:00Z' },
      ],
    }),
  ])
  expect(alerts).toHaveLength(1)
  expect(alerts[0]).toMatchObject({
    alertType: 'back_in_stock',
    listingId: 10,
    dedupeKey: 'back_in_stock:10:2026-11-21',
  })
})

test('detectFavoriteAlerts detects price_drop >= 10% and ignores smaller drops', () => {
  const alerts = detectFavoriteAlerts([
    snap({
      listingId: 20,
      history: [
        { listingId: 20, price: 40, currency: 'EUR', availability: 'available', recordedAt: '2026-11-21T06:00:00Z' },
        { listingId: 20, price: 50, currency: 'EUR', availability: 'available', recordedAt: '2026-11-14T06:00:00Z' },
      ],
    }),
    snap({
      listingId: 21,
      history: [
        { listingId: 21, price: 46, currency: 'EUR', availability: 'available', recordedAt: '2026-11-21T06:00:00Z' },
        { listingId: 21, price: 50, currency: 'EUR', availability: 'available', recordedAt: '2026-11-14T06:00:00Z' },
      ],
    }),
  ])
  expect(alerts).toHaveLength(1)
  expect(alerts[0]).toMatchObject({
    alertType: 'price_drop',
    listingId: 20,
    dedupeKey: 'price_drop:20:40:2026-11-21',
  })
  expect(alerts[0].payload).toMatchObject({ previousPrice: 50, currentPrice: 40, dropPct: 20 })
})

test('detectExpansionAlerts detects expansions for owned base games only', () => {
  const alerts = detectExpansionAlerts(new Set([316554]), [
    { id: 99, name: 'Dune: Imperium – Rise of Ix', bggId: 342031, expandsBggId: 316554 },
    { id: 100, name: 'Other Expansion', bggId: 999, expandsBggId: 12345 },
  ])
  expect(alerts).toHaveLength(1)
  expect(alerts[0]).toMatchObject({
    alertType: 'new_expansion',
    gameId: 99,
    title: 'Dune: Imperium – Rise of Ix',
    dedupeKey: 'new_expansion:99',
  })
})

test('runAlerts writes idempotently and sends optional webhook, never throwing on error', async () => {
  const store = {
    findFavoriteListings: vi.fn().mockResolvedValue([
      snap({
        listingId: 30,
        history: [
          { listingId: 30, price: 35, currency: 'EUR', availability: 'available', recordedAt: '2026-11-21T06:00:00Z' },
          { listingId: 30, price: 50, currency: 'EUR', availability: 'out_of_stock', recordedAt: '2026-11-14T06:00:00Z' },
        ],
      }),
    ]),
    collectionOwnedBggIds: vi.fn().mockResolvedValue(new Set([316554])),
    findExpansionGamesWithBaseDetails: vi.fn().mockResolvedValue([
      { id: 99, name: 'Rise of Ix', bggId: 342031, expandsBggId: 316554 },
    ]),
    insertUserAlerts: vi.fn().mockResolvedValue(3),
  } as any
  const fetchFn = vi.fn().mockResolvedValue({ ok: true, status: 200 })

  const report = await runAlerts({
    store,
    webhookUrl: 'https://hooks.example.com/alerts',
    fcmServerKey: '',
    fetchFn: fetchFn as any,
  })

  expect(report.detected).toBe(3)
  expect(report.inserted).toBe(3)
  expect(report.byType).toEqual({ back_in_stock: 1, price_drop: 1, new_expansion: 1 })
  expect(report.webhookSent).toBe(true)
  expect(store.insertUserAlerts).toHaveBeenCalledTimes(1)

  const failingStore = {
    findFavoriteListings: vi.fn().mockRejectedValue(new Error('alerts boom')),
  } as any
  const errReport = await runAlerts({ store: failingStore, webhookUrl: '', fcmServerKey: '' })
  expect(errReport.error).toBe('alerts boom')
  expect(errReport.inserted).toBe(0)
})

test('detectFavoriteAlerts handles canonical backorder and legacy/schema.org availability transitions', () => {
  const alerts = detectFavoriteAlerts([
    snap({
      listingId: 40,
      history: [
        { listingId: 40, price: 50, currency: 'EUR', availability: 'available', recordedAt: '2026-11-21T06:00:00Z' },
        { listingId: 40, price: 50, currency: 'EUR', availability: 'backorder', recordedAt: '2026-11-14T06:00:00Z' },
      ],
    }),
    snap({
      listingId: 41,
      history: [
        { listingId: 41, price: 50, currency: 'EUR', availability: 'true', recordedAt: '2026-11-21T06:00:00Z' },
        { listingId: 41, price: 50, currency: 'EUR', availability: 'preordine', recordedAt: '2026-11-14T06:00:00Z' },
      ],
    }),
    snap({
      listingId: 42,
      history: [
        { listingId: 42, price: 50, currency: 'EUR', availability: 'https://schema.org/InStock', recordedAt: '2026-11-21T06:00:00Z' },
        { listingId: 42, price: 50, currency: 'EUR', availability: 'https://schema.org/OutOfStock', recordedAt: '2026-11-14T06:00:00Z' },
      ],
    }),
    snap({
      listingId: 43,
      history: [
        { listingId: 43, price: 50, currency: 'EUR', availability: 'true', recordedAt: '2026-11-21T06:00:00Z' },
        { listingId: 43, price: 50, currency: 'EUR', availability: 'false', recordedAt: '2026-11-14T06:00:00Z' },
      ],
    }),
  ])
  expect(alerts).toHaveLength(4)
  expect(alerts.map((a) => [a.alertType, a.listingId])).toEqual([
    ['back_in_stock', 40],
    ['back_in_stock', 41],
    ['back_in_stock', 42],
    ['back_in_stock', 43],
  ])
})
