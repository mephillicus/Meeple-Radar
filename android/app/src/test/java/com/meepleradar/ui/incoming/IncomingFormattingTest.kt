package com.meepleradar.ui.incoming

import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.ItemKind
import com.meepleradar.data.model.Offer
import com.meepleradar.data.model.Price
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingFormattingTest {

    private fun offer(amount: Double?, currency: String?) = Offer(
        sourceId = "s1",
        sourceName = "Store Uno",
        price = if (amount != null && currency != null) Price(amount, currency) else null,
        url = "https://example.com/1",
        availability = null,
    )

    private fun item(kind: ItemKind, releaseDate: LocalDate?) = IncomingItem(
        key = "game:1",
        title = "Catan",
        imageUrl = null,
        releaseDate = releaseDate,
        kind = kind,
        game = null,
        offers = emptyList(),
    )

    @Test
    fun `priceLabel mostra EUR con due decimali e n d se assente`() {
        assertEquals("45,90 €", priceLabel(offer(45.9, "EUR")))
        assertEquals("n/d", priceLabel(offer(null, null)))
    }

    @Test
    fun `priceLabel mostra valuta originale per non EUR senza convertire`() {
        assertEquals("39.90 USD", priceLabel(offer(39.9, "USD")))
        assertEquals("35.00 USD", priceLabel(offer(35.0, "USD")))
    }

    @Test
    fun `dateLabel per item UPCOMING mostra la data, per NEW_UNDATED mostra data da confermare`() {
        assertEquals("In uscita il 05/11/2026", dateLabel(item(ItemKind.UPCOMING, LocalDate.of(2026, 11, 5))))
        assertEquals("Novità — data da confermare", dateLabel(item(ItemKind.NEW_UNDATED, null)))
    }

    @Test
    fun `bestPriceLabel sceglie il prezzo EUR piu basso e ricade su valuta originale`() {
        val offers = listOf(offer(49.9, "EUR"), offer(39.9, "EUR"), offer(35.0, "USD"))

        assertEquals("39,90 €", bestPriceLabel(offers))
        assertEquals("35.00 USD", bestPriceLabel(listOf(offer(35.0, "USD"))))
        assertEquals("n/d", bestPriceLabel(listOf(offer(null, null))))
        assertEquals("n/d", bestPriceLabel(emptyList()))
    }
}
