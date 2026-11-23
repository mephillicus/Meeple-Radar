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

    @Test
    fun `rispetta la fascia silenziosa 22-08 sospendendo avvisi e riepilogo`() {
        val prefs = NotificationPreferences(
            backgroundSyncEnabled = true,
            notifyPriceDrop = true,
            notifyNewsDigest = true,
            quietHoursEnabled = true,
        )

        val lateNight = AlertNotificationEvaluator.evaluate(
            alerts = listOf(alert(1L, UserAlertType.PRICE_DROP)),
            prefs = prefs,
            alreadyNotifiedIds = emptySet(),
            newlyAddedCount = 2,
            lastModifiedTimestamp = 2_000L,
            lastNotifiedNewsTimestamp = 0L,
            currentHour = 23,
        )
        assertTrue(lateNight.alertNotifications.isEmpty())
        assertNull(lateNight.newsDigest)

        val earlyMorning = AlertNotificationEvaluator.evaluate(
            alerts = listOf(alert(1L, UserAlertType.PRICE_DROP)),
            prefs = prefs,
            alreadyNotifiedIds = emptySet(),
            newlyAddedCount = 2,
            lastModifiedTimestamp = 2_000L,
            lastNotifiedNewsTimestamp = 0L,
            currentHour = 7,
        )
        assertTrue(earlyMorning.alertNotifications.isEmpty())
        assertNull(earlyMorning.newsDigest)

        val daytime = AlertNotificationEvaluator.evaluate(
            alerts = listOf(alert(1L, UserAlertType.PRICE_DROP)),
            prefs = prefs,
            alreadyNotifiedIds = emptySet(),
            newlyAddedCount = 2,
            lastModifiedTimestamp = 2_000L,
            lastNotifiedNewsTimestamp = 0L,
            currentHour = 8,
        )
        assertEquals(1, daytime.alertNotifications.size)
        assertEquals(2, daytime.newsDigest?.newlyAddedCount)
    }

    @Test
    fun `filtra i cali di prezzo sotto la soglia minima configurata`() {
        val alerts = listOf(
            alert(1L, UserAlertType.PRICE_DROP, title = "Sconto Piccolo", detail = "Store: €50 → €45 (-10%)")
                .copy(priceDropPct = 10.0),
            alert(2L, UserAlertType.PRICE_DROP, title = "Sconto Grande", detail = "Store: €50 → €37 (-26%)")
                .copy(priceDropPct = 26.0),
            alert(3L, UserAlertType.PRICE_DROP, title = "Solo testo 22%", detail = "Store: €50 → €39 (-22%)"),
            alert(4L, UserAlertType.BACK_IN_STOCK, title = "Tornato disponibile"),
        )

        val result = AlertNotificationEvaluator.evaluate(
            alerts = alerts,
            prefs = NotificationPreferences(
                backgroundSyncEnabled = true,
                notifyPriceDrop = true,
                notifyBackInStock = true,
                minPriceDropPct = 20,
            ),
            alreadyNotifiedIds = emptySet(),
            currentHour = 14,
        )

        assertEquals(listOf(2L, 3L, 4L), result.alertNotifications.map { it.alertId })
    }
}
