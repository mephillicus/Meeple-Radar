package com.meepleradar.ui.components

import com.meepleradar.data.model.AvailabilityStatus
import com.meepleradar.data.model.Game
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.ItemKind
import com.meepleradar.data.model.Offer
import com.meepleradar.data.model.Price
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListingFilterEngineTest {

    private val today = LocalDate.of(2026, 11, 20)

    private fun item(
        key: String,
        title: String,
        releaseDate: LocalDate? = null,
        priceEur: Double? = null,
        sourceId: String = "s1",
        availability: String? = "InStock",
        bggRating: Double? = null,
        isNewlyAdded: Boolean = false,
        isSeen: Boolean = false,
    ) = IncomingItem(
        key = key,
        title = title,
        imageUrl = null,
        releaseDate = releaseDate,
        kind = if (releaseDate != null) ItemKind.UPCOMING else ItemKind.NEW_UNDATED,
        game = bggRating?.let { Game(1L, title, "base", 10, it, null, null) },
        offers = listOf(
            Offer(
                sourceId = sourceId,
                sourceName = sourceId,
                price = priceEur?.let { Price(it, "EUR") },
                url = "https://example.com/$key",
                availability = availability,
                releaseDate = releaseDate,
            ),
        ),
        isNewlyAdded = isNewlyAdded,
        isSeen = isSeen,
    )

    @Test
    fun `ordinamento per prezzo mette i prezzi crescenti prima e senza prezzo in fondo`() {
        val items = listOf(
            item("a", "Zeta", priceEur = null),
            item("b", "Beta", priceEur = 49.90),
            item("c", "Alfa", priceEur = 29.90),
            item("d", "Gamma", priceEur = 29.90),
        )

        val sorted = sortListings(items, ListingSortOption.PRICE_ASC)
        assertEquals(listOf("c", "d", "b", "a"), sorted.map { it.key })
    }

    @Test
    fun `ordinamento per data crescente e decrescente mantiene i senza data in fondo`() {
        val items = listOf(
            item("u", "Senza data", releaseDate = null),
            item("m", "Medio", releaseDate = today.plusDays(5)),
            item("e", "Presto", releaseDate = today.plusDays(1)),
            item("l", "Tardi", releaseDate = today.plusDays(20)),
        )

        val asc = sortListings(items, ListingSortOption.RELEASE_DATE_ASC)
        assertEquals(listOf("e", "m", "l", "u"), asc.map { it.key })

        val desc = sortListings(items, ListingSortOption.RELEASE_DATE_DESC)
        assertEquals(listOf("l", "m", "e", "u"), desc.map { it.key })
    }

    @Test
    fun `ordinamento per voto BGG decrescente mette i senza voto o zero in fondo`() {
        val items = listOf(
            item("none", "Nessuno", bggRating = null),
            item("zero", "Zero", bggRating = 0.0),
            item("mid", "Medio", bggRating = 7.4),
            item("top", "Top", bggRating = 8.6),
        )

        val sorted = sortListings(items, ListingSortOption.BGG_RATING_DESC)
        assertEquals(listOf("top", "mid", "none", "zero"), sorted.map { it.key })
    }

    @Test
    fun `isAlphabetical e vero solo per DEFAULT e TITLE_ASC`() {
        assertTrue(ListingSortOption.DEFAULT.isAlphabetical)
        assertTrue(ListingSortOption.TITLE_ASC.isAlphabetical)
        assertFalse(ListingSortOption.PRICE_ASC.isAlphabetical)
        assertFalse(ListingSortOption.RELEASE_DATE_ASC.isAlphabetical)
        assertFalse(ListingSortOption.RELEASE_DATE_DESC.isAlphabetical)
        assertFalse(ListingSortOption.BGG_RATING_DESC.isAlphabetical)
    }

    @Test
    fun `filtra correttamente per fonte, data, prezzo, visti, novita e ricerca`() {
        val items = listOf(
            item("1", "Ark Nova", releaseDate = today.plusDays(3), priceEur = 60.0, sourceId = "s1", isNewlyAdded = true, isSeen = false),
            item("2", "Arkham Horror", releaseDate = null, priceEur = 40.0, sourceId = "s1", isNewlyAdded = true, isSeen = false),
            item("3", "Ark Nova Promo", releaseDate = today.plusDays(1), priceEur = null, sourceId = "s1", isNewlyAdded = true, isSeen = false),
            item("4", "Ark Nova Seen", releaseDate = today.plusDays(2), priceEur = 55.0, sourceId = "s1", isNewlyAdded = true, isSeen = true),
            item("5", "Ark Nova Altro Store", releaseDate = today.plusDays(2), priceEur = 55.0, sourceId = "s2", isNewlyAdded = true, isSeen = false),
        )

        val filtered = filterAndSortListings(
            items = items,
            state = ListingFilterState(
                selectedSources = setOf("s1"),
                hideUndated = true,
                hidePriceless = true,
                hideSeen = true,
                searchQuery = "ark nova",
                showOnlyNewlyAdded = true,
                sortOption = ListingSortOption.PRICE_ASC,
            ),
            today = today,
        )

        assertEquals(listOf("1"), filtered.map { it.key })
    }

    @Test
    fun `ricerca sotto i 3 caratteri non filtra i risultati`() {
        val items = listOf(
            item("1", "Catan"),
            item("2", "Brass"),
        )

        val filtered = filterAndSortListings(
            items = items,
            state = ListingFilterState(searchQuery = "ca"),
            today = today,
        )

        assertEquals(listOf("1", "2"), filtered.map { it.key })
    }

    @Test
    fun `disponibilita PREORDER riconosce data futura quando abilitato`() {
        val futureItem = item("1", "Futuro", releaseDate = today.plusDays(10), availability = null)
        val availableItem = item("2", "Pronto", releaseDate = today.minusDays(5), availability = "InStock")

        val filtered = filterAndSortListings(
            items = listOf(futureItem, availableItem),
            state = ListingFilterState(selectedAvailability = setOf(AvailabilityStatus.PREORDER)),
            today = today,
            matchItemReleaseDateAsPreorder = true,
        )

        assertEquals(listOf("1"), filtered.map { it.key })
    }

    @Test
    fun `ordinamento PRICE_DESC e DISCOUNT_DESC e filtro onlyDiscounted funzionano correttamente`() {
        val noDiscount = item("no", "No Sconto", priceEur = 60.0)
        val smallDrop = item("small", "Piccolo", priceEur = 40.0).copy(
            offers = listOf(
                Offer(
                    sourceId = "s1",
                    sourceName = "s1",
                    price = Price(40.0, "EUR"),
                    url = "https://example.com/small",
                    availability = "InStock",
                    priceDropPct = 10.0,
                ),
            ),
        )
        val bigDrop = item("big", "Grande", priceEur = 50.0).copy(
            offers = listOf(
                Offer(
                    sourceId = "s1",
                    sourceName = "s1",
                    price = Price(50.0, "EUR"),
                    url = "https://example.com/big",
                    availability = "InStock",
                    priceDropPct = 25.0,
                ),
            ),
        )
        val priceless = item("null", "Senza Prezzo", priceEur = null)

        val byPriceDesc = sortListings(listOf(smallDrop, priceless, noDiscount, bigDrop), ListingSortOption.PRICE_DESC)
        assertEquals(listOf("no", "big", "small", "null"), byPriceDesc.map { it.key })

        val byDiscountDesc = sortListings(listOf(noDiscount, smallDrop, bigDrop), ListingSortOption.DISCOUNT_DESC)
        assertEquals(listOf("big", "small", "no"), byDiscountDesc.map { it.key })

        val discountedOnly = filterAndSortListings(
            items = listOf(noDiscount, smallDrop, bigDrop),
            state = ListingFilterState(onlyDiscounted = true, sortOption = ListingSortOption.DISCOUNT_DESC),
            today = today,
        )
        assertEquals(listOf("big", "small"), discountedOnly.map { it.key })
    }
}
