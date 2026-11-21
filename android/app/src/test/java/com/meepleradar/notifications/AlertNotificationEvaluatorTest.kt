package com.meepleradar.notifications

import com.meepleradar.data.local.NotificationPreferences
import com.meepleradar.data.model.UserAlert
import com.meepleradar.data.model.UserAlertType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertNotificationEvaluatorTest {

    private fun alert(
        id: Long,
        type: UserAlertType,
        title: String = "Gioco $id",
        detail: String? = "Dettaglio $id",
        readAt: String? = null,
    ) = UserAlert(
        id = id,
        alertType = type,
        gameId = id,
        productId = null,
        listingId = id * 10,
        title = title,
        detailText = detail,
        dedupeKey = "key:$id",
        readAt = readAt,
        createdAt = "2026-04-19T08:00:00Z",
    )

    @Test
    fun `non emette nulla quando backgroundSyncEnabled e disattivo`() {
        val result = AlertNotificationEvaluator.evaluate(
            alerts = listOf(alert(1L, UserAlertType.PRICE_DROP)),
            prefs = NotificationPreferences(
                backgroundSyncEnabled = false,
                notifyPriceDrop = true,
                notifyNewsDigest = true,
            ),
            alreadyNotifiedIds = emptySet(),
            newlyAddedCount = 5,
            lastModifiedTimestamp = 1_000L,
            lastNotifiedNewsTimestamp = 0L,
        )

        assertTrue(result.alertNotifications.isEmpty())
        assertNull(result.newsDigest)
    }

    @Test
    fun `filtra per tipo abilitato, esclude letti e deduplica gia notificati`() {
        val alerts = listOf(
            alert(1L, UserAlertType.PRICE_DROP, title = "Ark Nova"),
            alert(2L, UserAlertType.BACK_IN_STOCK, title = "Brass"),
            alert(3L, UserAlertType.NEW_EXPANSION, title = "Dune: Bloodlines"),
            alert(4L, UserAlertType.PRICE_DROP, readAt = "2026-04-19T09:00:00Z"),
            alert(5L, UserAlertType.PRICE_DROP),
        )

        val result = AlertNotificationEvaluator.evaluate(
            alerts = alerts,
            prefs = NotificationPreferences(
                backgroundSyncEnabled = true,
                notifyPriceDrop = true,
                notifyBackInStock = false,
                notifyNewExpansion = true,
            ),
            alreadyNotifiedIds = setOf(5L),
        )

        assertEquals(listOf(1L, 3L), result.alertNotifications.map { it.alertId })
        assertEquals("Calo prezzo: Ark Nova", result.alertNotifications[0].title)
        assertEquals("game:1", result.alertNotifications[0].itemKey)
        assertEquals("Nuova espansione: Dune: Bloodlines", result.alertNotifications[1].title)
    }

    @Test
    fun `emette riepilogo novita solo se abilitato e con timestamp piu recente`() {
        val prefs = NotificationPreferences(
            backgroundSyncEnabled = true,
            notifyNewsDigest = true,
        )

        val first = AlertNotificationEvaluator.evaluate(
            alerts = emptyList(),
            prefs = prefs,
            alreadyNotifiedIds = emptySet(),
            newlyAddedCount = 3,
            lastModifiedTimestamp = 2_000L,
            lastNotifiedNewsTimestamp = 1_000L,
        )
        assertEquals(3, first.newsDigest?.newlyAddedCount)
        assertTrue(first.newsDigest!!.body.contains("3 nuove uscite"))

        val duplicate = AlertNotificationEvaluator.evaluate(
            alerts = emptyList(),
            prefs = prefs,
            alreadyNotifiedIds = emptySet(),
            newlyAddedCount = 3,
            lastModifiedTimestamp = 2_000L,
            lastNotifiedNewsTimestamp = 2_000L,
        )
        assertNull(duplicate.newsDigest)
    }
}
