package com.meepleradar.notifications

import com.meepleradar.data.local.NotificationPreferences
import com.meepleradar.data.model.UserAlert
import com.meepleradar.data.model.UserAlertType
import java.time.LocalTime

data class PendingAlertNotification(
    val alertId: Long,
    val title: String,
    val body: String,
    val itemKey: String?,
    val alertType: UserAlertType,
)

data class PendingNewsDigestNotification(
    val newlyAddedCount: Int,
    val lastModifiedTimestamp: Long,
    val title: String,
    val body: String,
)

data class NotificationEvaluationResult(
    val alertNotifications: List<PendingAlertNotification>,
    val newsDigest: PendingNewsDigestNotification?,
)

/**
 * Logica pura di valutazione delle notifiche locali da emettere durante il sync in background.
 */
object AlertNotificationEvaluator {

    const val QUIET_HOURS_START = 22
    const val QUIET_HOURS_END = 8

    private val DETAIL_DROP_PCT_REGEX = Regex("\\(-(\\d+(?:[.,]\\d+)?)%\\)")

    fun isQuietHour(hour: Int): Boolean = hour >= QUIET_HOURS_START || hour < QUIET_HOURS_END

    fun evaluate(
        alerts: List<UserAlert>,
        prefs: NotificationPreferences,
        alreadyNotifiedIds: Set<Long>,
        newlyAddedCount: Int = 0,
        lastModifiedTimestamp: Long = 0L,
        lastNotifiedNewsTimestamp: Long = 0L,
        currentHour: Int = LocalTime.now().hour,
    ): NotificationEvaluationResult {
        if (!prefs.backgroundSyncEnabled) {
            return NotificationEvaluationResult(emptyList(), null)
        }
        if (prefs.quietHoursEnabled && isQuietHour(currentHour)) {
            return NotificationEvaluationResult(emptyList(), null)
        }

        val pendingAlerts = alerts
            .filter { it.isUnread && it.id !in alreadyNotifiedIds }
            .filter { alert ->
                when (alert.alertType) {
                    UserAlertType.BACK_IN_STOCK -> prefs.notifyBackInStock
                    UserAlertType.PRICE_DROP -> {
                        if (!prefs.notifyPriceDrop) {
                            false
                        } else if (prefs.minPriceDropPct <= 0) {
                            true
                        } else {
                            val pct = alert.priceDropPct ?: extractDropPctFromDetail(alert.detailText)
                            pct == null || pct >= prefs.minPriceDropPct
                        }
                    }
                    UserAlertType.NEW_EXPANSION -> prefs.notifyNewExpansion
                }
            }
            .map { alert ->
                val prefix = when (alert.alertType) {
                    UserAlertType.BACK_IN_STOCK -> "Disponibile: "
                    UserAlertType.PRICE_DROP -> "Calo prezzo: "
                    UserAlertType.NEW_EXPANSION -> "Nuova espansione: "
                }
                val defaultBody = when (alert.alertType) {
                    UserAlertType.BACK_IN_STOCK -> "Un tuo gioco preferito è tornato disponibile."
                    UserAlertType.PRICE_DROP -> "Rilevato un ribasso di prezzo su un tuo preferito."
                    UserAlertType.NEW_EXPANSION -> "Nuova espansione disponibile per un gioco della tua collezione."
                }
                PendingAlertNotification(
                    alertId = alert.id,
                    title = "$prefix${alert.title}",
                    body = alert.detailText?.takeIf(String::isNotBlank) ?: defaultBody,
                    itemKey = alert.itemKeyOrNull(),
                    alertType = alert.alertType,
                )
            }

        val digest = if (
            prefs.notifyNewsDigest &&
            newlyAddedCount > 0 &&
            lastModifiedTimestamp > lastNotifiedNewsTimestamp
        ) {
            PendingNewsDigestNotification(
                newlyAddedCount = newlyAddedCount,
                lastModifiedTimestamp = lastModifiedTimestamp,
                title = "Nuove uscite su Meeple Radar",
                body = if (newlyAddedCount == 1) {
                    "È stata rilevata 1 nuova uscita negli store."
                } else {
                    "Sono state rilevate $newlyAddedCount nuove uscite negli store."
                },
            )
        } else {
            null
        }

        return NotificationEvaluationResult(
            alertNotifications = pendingAlerts,
            newsDigest = digest,
        )
    }

    internal fun extractDropPctFromDetail(detailText: String?): Double? {
        if (detailText.isNullOrBlank()) return null
        val match = DETAIL_DROP_PCT_REGEX.find(detailText) ?: return null
        return match.groupValues[1].replace(',', '.').toDoubleOrNull()
    }
}
