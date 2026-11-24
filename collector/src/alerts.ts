import type {
  ExpansionGameDetail,
  FavoriteListingSnapshot,
  ListingStore,
  UserAlertInput,
} from './db.js'
import { normalizeAvailability } from './normalize.js'

export type AlertsReport = {
  detected: number
  inserted: number
  byType: {
    back_in_stock: number
    price_drop: number
    new_expansion: number
  }
  webhookSent?: boolean
  error?: string
}

export const DEFAULT_PRICE_DROP_THRESHOLD = 0.1
const WEBHOOK_TIMEOUT_MS = 10_000
const UNAVAILABLE_STATES = new Set(['preorder', 'out_of_stock', 'backorder', 'reprint'])

export function detectFavoriteAlerts(
  snapshots: FavoriteListingSnapshot[],
  priceDropThreshold = DEFAULT_PRICE_DROP_THRESHOLD,
): UserAlertInput[] {
  const alerts: UserAlertInput[] = []

  for (const snap of snapshots) {
    if (snap.history.length < 2) continue
    const [latest, previous] = snap.history
    const dateKey = (latest.recordedAt || new Date().toISOString()).slice(0, 10)

    const prevAvail = normalizeAvailability(previous.availability)
    const currAvail = normalizeAvailability(latest.availability)
    if (prevAvail !== null && UNAVAILABLE_STATES.has(prevAvail) && currAvail === 'available') {
      alerts.push({
        alertType: 'back_in_stock',
        gameId: snap.gameId,
        productId: snap.productId,
        listingId: snap.listingId,
        title: snap.title,
        dedupeKey: `back_in_stock:${snap.listingId}:${dateKey}`,
        payload: {
          url: snap.url,
          sourceId: snap.sourceId,
          previousAvailability: previous.availability,
          currentAvailability: latest.availability,
          price: latest.price ?? snap.price,
          currency: latest.currency ?? snap.currency,
        },
      })
    }

    if (
      previous.price !== null &&
      previous.price > 0 &&
      latest.price !== null &&
      latest.price < previous.price
    ) {
      const dropRatio = (previous.price - latest.price) / previous.price
      if (dropRatio >= priceDropThreshold) {
        const dropPct = Math.round(dropRatio * 1000) / 10
        alerts.push({
          alertType: 'price_drop',
          gameId: snap.gameId,
          productId: snap.productId,
          listingId: snap.listingId,
          title: snap.title,
          dedupeKey: `price_drop:${snap.listingId}:${latest.price}:${dateKey}`,
          payload: {
            url: snap.url,
            sourceId: snap.sourceId,
            previousPrice: previous.price,
            currentPrice: latest.price,
            currency: latest.currency ?? snap.currency,
            dropPct,
          },
        })
      }
    }
  }

  return alerts
}

export function detectExpansionAlerts(
  ownedBggIds: Set<number>,
  expansions: ExpansionGameDetail[],
): UserAlertInput[] {
  const alerts: UserAlertInput[] = []
  for (const exp of expansions) {
    if (!ownedBggIds.has(exp.expandsBggId)) continue
    alerts.push({
      alertType: 'new_expansion',
      gameId: exp.id,
      productId: null,
      listingId: null,
      title: exp.name || `Expansion #${exp.id}`,
      dedupeKey: `new_expansion:${exp.id}`,
      payload: {
        bggId: exp.bggId,
        expandsBggId: exp.expandsBggId,
      },
    })
  }
  return alerts
}

/**
 * Motore alert post-raccolta (P3.2).
 * Rileva:
 * 1. Giochi/prodotti preferiti passati da preorder/out_of_stock ad available
 *    oppure con calo di prezzo >= 10%.
 * 2. Nuove espansioni (game.expands_bgg_id) per giochi posseduti in collection_item.
 * Scrive idempotentemente su user_alert (dedupe_key) e invia opzionalmente a webhook/FCM.
 * Best-effort: non lancia mai e non fa mai fallire la run.
 */
export async function runAlerts(args: {
  store: ListingStore
  priceDropThreshold?: number
  webhookUrl?: string
  fcmServerKey?: string
  fcmTopic?: string
  fetchFn?: typeof fetch
}): Promise<AlertsReport> {
  const {
    store,
    priceDropThreshold = DEFAULT_PRICE_DROP_THRESHOLD,
    webhookUrl = process.env.ALERTS_WEBHOOK_URL,
    fcmServerKey = process.env.FCM_SERVER_KEY,
    fcmTopic = process.env.FCM_TOPIC ?? 'meeple-radar-alerts',
    fetchFn = fetch,
  } = args

  try {
    const alerts: UserAlertInput[] = []

    if (store.findFavoriteListings) {
      const snapshots = await store.findFavoriteListings()
      alerts.push(...detectFavoriteAlerts(snapshots, priceDropThreshold))
    }

    if (store.collectionOwnedBggIds) {
      const ownedBggIds = await store.collectionOwnedBggIds()
      if (ownedBggIds.size > 0) {
        const expansions: ExpansionGameDetail[] = store.findExpansionGamesWithBaseDetails
          ? await store.findExpansionGamesWithBaseDetails()
          : (await store.findExpansionGamesWithBase()).map((e) => ({
              id: e.id,
              name: `Expansion #${e.id}`,
              bggId: null,
              expandsBggId: e.expands_bgg_id,
            }))
        alerts.push(...detectExpansionAlerts(ownedBggIds, expansions))
      }
    }

    const byType = { back_in_stock: 0, price_drop: 0, new_expansion: 0 }
    for (const a of alerts) {
      byType[a.alertType]++
    }

    const inserted =
      alerts.length > 0 && store.insertUserAlerts ? await store.insertUserAlerts(alerts) : 0

    let webhookSent: boolean | undefined
    if (inserted > 0 && webhookUrl) {
      try {
        const res = await fetchFn(webhookUrl, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ event: 'user_alerts', inserted, byType, alerts }),
          signal: AbortSignal.timeout(WEBHOOK_TIMEOUT_MS),
        })
        webhookSent = res.ok
      } catch {
        webhookSent = false
      }
    }

    if (inserted > 0 && fcmServerKey) {
      try {
        await fetchFn('https://fcm.googleapis.com/fcm/send', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Authorization: `key=${fcmServerKey}`,
          },
          body: JSON.stringify({
            to: `/topics/${fcmTopic}`,
            notification: {
              title: 'Meeple Radar',
              body: `${inserted} nuovi avvisi disponibili`,
            },
            data: { inserted: String(inserted), ...byType },
          }),
          signal: AbortSignal.timeout(WEBHOOK_TIMEOUT_MS),
        })
      } catch {
        // Best-effort FCM dispatch.
      }
    }

    return {
      detected: alerts.length,
      inserted,
      byType,
      ...(webhookSent !== undefined && { webhookSent }),
    }
  } catch (err) {
    return {
      detected: 0,
      inserted: 0,
      byType: { back_in_stock: 0, price_drop: 0, new_expansion: 0 },
      error: err instanceof Error ? err.message : String(err),
    }
  }
}
