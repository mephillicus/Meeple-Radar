package com.meepleradar.ui.incoming

import com.meepleradar.data.model.AvailabilityStatus
import com.meepleradar.data.model.Offer
import com.meepleradar.data.model.Price
import com.meepleradar.data.model.availabilityStatus
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferPresentationTest {

    private fun offer(amount: Double, availability: String?, source: String = "Store") = Offer(
        sourceId = source,
        sourceName = source,
        price = Price(amount, "EUR"),
        url = "https://example.com",
        availability = availability,
    )

    @Test
    fun `normalizza disponibilita booleane e schema org`() {
        assertEquals(AvailabilityStatus.AVAILABLE, availabilityStatus("true"))
        assertEquals(AvailabilityStatus.AVAILABLE, availabilityStatus("InStock"))
        assertEquals(AvailabilityStatus.UNAVAILABLE, availabilityStatus("false"))
        assertEquals(AvailabilityStatus.UNAVAILABLE, availabilityStatus("http://schema.org/OutOfStock"))
        assertEquals(AvailabilityStatus.UNAVAILABLE, availabilityStatus("non disponibile"))
    }

    @Test
    fun `riconosce preordine backorder fuori produzione e stati sconosciuti`() {
        assertEquals(AvailabilityStatus.PREORDER, availabilityStatus("PreOrder"))
        assertEquals(AvailabilityStatus.BACKORDER, availabilityStatus("BackOrder"))
        assertEquals(AvailabilityStatus.DISCONTINUED, availabilityStatus("Discontinued"))
        assertEquals(AvailabilityStatus.UNKNOWN, availabilityStatus("custom-status"))
        assertEquals(AvailabilityStatus.UNKNOWN, availabilityStatus(null))
    }

    @Test
    fun `riconosce ristampa da testo e da reprintDate futura`() {
        val today = LocalDate.of(2026, 11, 24)
        val futureReprint = today.plusDays(30)
        val pastReprint = today.minusDays(5)
        val futureRelease = today.plusDays(15)

        assertEquals(AvailabilityStatus.REPRINT, availabilityStatus("reprint", today = today))
        assertEquals(AvailabilityStatus.REPRINT, availabilityStatus("Ristampa", today = today))
        assertEquals(AvailabilityStatus.REPRINT, availabilityStatus("riassortimento", today = today))
        assertEquals(AvailabilityStatus.REPRINT, availabilityStatus("http://schema.org/Reprint", today = today))

        assertEquals(AvailabilityStatus.REPRINT, availabilityStatus(null, today = today, reprintDate = futureReprint))
        assertEquals(AvailabilityStatus.REPRINT, availabilityStatus("OutOfStock", today = today, reprintDate = futureReprint))
        assertEquals(AvailabilityStatus.REPRINT, availabilityStatus("BackOrder", today = today, reprintDate = futureReprint))
        assertEquals(AvailabilityStatus.REPRINT, availabilityStatus("custom-status", today = today, reprintDate = futureReprint))
        assertEquals(AvailabilityStatus.REPRINT, availabilityStatus("OutOfStock", today = today, reprintDate = today))

        assertEquals(AvailabilityStatus.AVAILABLE, availabilityStatus("InStock", today = today, reprintDate = futureReprint))
        assertEquals(AvailabilityStatus.PREORDER, availabilityStatus("OutOfStock", releaseDate = futureRelease, today = today, reprintDate = futureReprint))
        assertEquals(AvailabilityStatus.UNAVAILABLE, availabilityStatus("OutOfStock", today = today, reprintDate = pastReprint))
    }

    @Test
    fun `availabilityLabel formatta correttamente ristampa con e senza data`() {
        val reprintWithDate = offer(39.9, "reprint").copy(reprintDate = LocalDate.of(2027, 3, 20))
        val reprintWithoutDate = offer(39.9, "ristampa")

        assertEquals("Ristampa 20/03/2027", availabilityLabel(reprintWithDate))
        assertEquals("Ristampa", availabilityLabel(reprintWithoutDate))
    }

    @Test
    fun `prezzo migliore esclude non disponibili e include tutti i pari merito`() {
        val offers = listOf(
            offer(20.0, "OutOfStock", "Esaurito"),
            offer(35.0, "InStock", "A"),
            offer(35.0, "true", "B"),
            offer(40.0, "false", "C"),
        )

        assertEquals(setOf("A", "B"), bestComparableEuroOffers(offers).map { it.sourceId }.toSet())
    }

    @Test
    fun `prezzo migliore resta vuoto se sono tutti fuori stock`() {
        val offers = listOf(offer(20.0, "false"), offer(30.0, "OutOfStock", "Store 2"))

        assertTrue(bestComparableEuroOffers(offers).isEmpty())
        assertEquals(null, lowestComparableEuroPrice(offers))
    }

    @Test
    fun `miglior prezzo considera solo importi numerici validi e preferisce EUR`() {
        val invalid = offer(Double.NaN, "InStock", "NaN")
        val infinite = offer(Double.POSITIVE_INFINITY, "InStock", "Infinite")
        val negative = offer(-1.0, "InStock", "Negative")
        val cheaperUsd = offer(1.0, "InStock", "USD").copy(price = Price(1.0, "USD"))
        val eur = offer(25.0, "InStock", "EUR")

        assertEquals("EUR", bestPricedOffer(listOf(invalid, infinite, negative, cheaperUsd, eur))?.sourceId)
        assertEquals(null, bestPricedOffer(listOf(invalid, infinite, negative)))
    }
}
