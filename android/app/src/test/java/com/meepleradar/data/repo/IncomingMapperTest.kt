package com.meepleradar.data.repo

import com.meepleradar.data.model.Game
import com.meepleradar.data.model.ItemKind
import com.meepleradar.data.model.Listing
import com.meepleradar.data.model.Offer
import com.meepleradar.data.model.Price
import com.meepleradar.data.model.Product
import com.meepleradar.data.model.Source
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingMapperTest {

    private val today = LocalDate.of(2026, 10, 17)

    private fun listing(
        id: Long,
        sourceId: String = "s1",
        title: String = "Gioco $id",
        url: String = "https://example.com/$id",
        price: Double? = null,
        currency: String? = null,
        availability: String? = null,
        releaseDate: LocalDate? = null,
        imageUrl: String? = null,
        gameId: Long? = null,
        matchConfidence: Double? = null,
    ) = Listing(
        id = id,
        sourceId = sourceId,
        title = title,
        url = url,
        price = price,
        currency = currency,
        availability = availability,
        releaseDate = releaseDate,
        imageUrl = imageUrl,
        gameId = gameId,
        matchConfidence = matchConfidence,
    )

    private fun game(id: Long, name: String, imageUrl: String? = null) =
        Game(id = id, name = name, kind = "base", bggId = null, bggRating = null, bggRank = null, imageUrl = imageUrl)

    private fun sources(vararg ids: String) = ids.associateWith { Source(it, "Store $it") }

    @Test
    fun `listing senza data diventa NEW_UNDATED e non crasha`() {
        val items = buildIncomingItems(
            listings = listOf(listing(id = 1)),
            games = emptyMap(),
            sources = sources("s1"),
            ignoredKeys = emptySet(),
            today = today,
        )

        assertEquals(1, items.size)
        assertEquals("listing:1", items[0].key)
        assertEquals(ItemKind.NEW_UNDATED, items[0].kind)
        assertNull(items[0].releaseDate)
        assertEquals(1, items[0].offers.size)
        assertNull(items[0].offers[0].price)
    }

    @Test
    fun `listing senza gameId resta una card singola con chiave listing id`() {
        val items = buildIncomingItems(
            listings = listOf(listing(id = 7, title = "Solo Listing", gameId = null)),
            games = mapOf(42L to game(42, "Gioco Collegato")),
            sources = sources("s1"),
            ignoredKeys = emptySet(),
            today = today,
        )

        assertEquals(1, items.size)
        assertEquals("listing:7", items[0].key)
        assertEquals("Solo Listing", items[0].title)
        assertNull(items[0].game)
    }

    @Test
    fun `prezzo migliore solo tra offerte EUR e null se assente`() {
        val eur = Offer("s1", "Store s1", Price(49.9, "EUR"), "u1", null)
        val eurCheap = Offer("s4", "Store s4", Price(39.0, "EUR"), "u4", null)
        val usd = Offer("s2", "Store s2", Price(10.0, "USD"), "u2", null)
        val noPrice = Offer("s3", "Store s3", null, "u3", null)

        assertEquals(Price(39.0, "EUR"), bestEurPrice(listOf(eur, eurCheap, usd, noPrice)))
        assertNull(bestEurPrice(listOf(usd, noPrice)))
    }

    @Test
    fun `listing dello stesso game confluiscono in una card con piu offerte`() {
        val a = listing(id = 1, sourceId = "s1", price = 40.0, currency = "EUR", gameId = 5)
        val b = listing(id = 2, sourceId = "s2", price = 35.0, currency = "EUR", gameId = 5)

        val items = buildIncomingItems(
            listings = listOf(a, b),
            games = mapOf(5L to game(5, "Catan")),
            sources = sources("s1", "s2"),
            ignoredKeys = emptySet(),
            today = today,
        )

        assertEquals(1, items.size)
        assertEquals("game:5", items[0].key)
        assertEquals("Catan", items[0].title)
        assertEquals(2, items[0].offers.size)
        assertEquals(listOf("Store s1", "Store s2"), items[0].offers.map { it.sourceName })
        assertEquals(Price(35.0, "EUR"), bestEurPrice(items[0].offers))
    }

    @Test
    fun `items ignorati sono esclusi`() {
        val items = buildIncomingItems(
            listings = listOf(listing(id = 1, gameId = 5)),
            games = mapOf(5L to game(5, "Catan")),
            sources = sources("s1"),
            ignoredKeys = setOf("game:5"),
            today = today,
        )

        assertTrue(items.isEmpty())
    }

    @Test
    fun `tutti i giochi inclusi indipendentemente dalla data passata o futura`() {
        val past = listing(id = 1, gameId = 1, releaseDate = today.minusDays(1), title = "Alpha")
        val future = listing(id = 2, gameId = 2, releaseDate = today.plusDays(3), title = "Beta")
        val undated = listing(id = 3, gameId = 3, releaseDate = null, title = "Zeta")

        val items = buildIncomingItems(
            listings = listOf(past, future, undated),
            games = mapOf(1L to game(1, "Alpha"), 2L to game(2, "Beta"), 3L to game(3, "Zeta")),
            sources = sources("s1"),
            ignoredKeys = emptySet(),
            today = today,
        )

        assertEquals(listOf("game:1", "game:2", "game:3"), items.map { it.key })
    }

    @Test
    fun `includeUndated falso esclude gli item senza data`() {
        val future = listing(id = 2, gameId = 2, releaseDate = today.plusDays(3))
        val undated = listing(id = 3, gameId = 3, releaseDate = null)

        val items = buildIncomingItems(
            listings = listOf(future, undated),
            games = mapOf(2L to game(2, "Futuro"), 3L to game(3, "SenzaData")),
            sources = sources("s1"),
            ignoredKeys = emptySet(),
            today = today,
            includeUndated = false,
        )

        assertEquals(listOf("game:2"), items.map { it.key })
    }

    @Test
    fun `includePriceless falso esclude gli item senza prezzo`() {
        val priced = listing(id = 2, gameId = 2, price = 39.9, currency = "EUR")
        val priceless = listing(id = 3, gameId = 3, price = null)

        val items = buildIncomingItems(
            listings = listOf(priced, priceless),
            games = mapOf(2L to game(2, "PrezzoOk"), 3L to game(3, "SenzaPrezzo")),
            sources = sources("s1"),
            ignoredKeys = emptySet(),
            today = today,
            includePriceless = false,
        )

        assertEquals(listOf("game:2"), items.map { it.key })
    }

    @Test
    fun `ordinamento alfabetico per titolo`() {
        val later = listing(id = 1, gameId = 1, releaseDate = today.plusDays(10))
        val sooner = listing(id = 2, gameId = 2, releaseDate = today.plusDays(2))
        val zeta = listing(id = 3, gameId = 3, releaseDate = null)
        val alpha = listing(id = 4, gameId = 4, releaseDate = null)

        val items = buildIncomingItems(
            listings = listOf(later, sooner, zeta, alpha),
            games = mapOf(
                1L to game(1, "Later"),
                2L to game(2, "Sooner"),
                3L to game(3, "Zeta"),
                4L to game(4, "Alpha"),
            ),
            sources = sources("s1"),
            ignoredKeys = emptySet(),
            today = today,
        )

        assertEquals(listOf("Alpha", "Later", "Sooner", "Zeta"), items.map { it.title })
    }

    @Test
    fun `listing con productId confluiscono in una card con chiave product id e piu offerte`() {
        val a = listing(id = 1, sourceId = "s1", price = 40.0, currency = "EUR").copy(productId = 10L)
        val b = listing(id = 2, sourceId = "s2", price = 35.0, currency = "EUR").copy(productId = 10L)

        val items = buildIncomingItems(
            listings = listOf(a, b),
            games = emptyMap(),
            sources = sources("s1", "s2"),
            ignoredKeys = emptySet(),
            today = today,
            products = mapOf(10L to com.meepleradar.data.model.Product(10L, "Citadels", null, null)),
        )

        assertEquals(1, items.size)
        assertEquals("product:10", items[0].key)
        assertEquals("Citadels", items[0].title)
        assertEquals(2, items[0].offers.size)
        assertEquals(Price(35.0, "EUR"), bestEurPrice(items[0].offers))
        assertEquals(listOf("product:10", "listing:1", "listing:2"), items[0].bggSelectionKeys)
    }

    @Test
    fun `mantiene il titolo del product per la ricerca BGG anche se il game collegato ha un nome diverso`() {
        val productTitle = "Dune: La Guerra per Arrakis – La Guerra del Deserto"
        val listing = listing(id = 1, title = productTitle, gameId = 5).copy(productId = 10L)
        val items = buildIncomingItems(
            listings = listOf(listing),
            games = mapOf(5L to game(5, "Dune")),
            sources = sources("s1"),
            ignoredKeys = emptySet(),
            today = today,
            products = mapOf(10L to Product(10L, productTitle, null, null)),
        )

        assertEquals("Dune", items.single().title)
        assertEquals(productTitle, items.single().lookupTitle)
    }

    @Test
    fun `mostra il prezzo product solo se il link punta al publisher dichiarato`() {
        val product = Product(
            id = 10,
            title = "Dune",
            imageUrl = null,
            releaseDate = null,
            url = "https://www.asmodee.it/prodotto/dune",
            price = 49.9,
            currency = "EUR",
            publisher = "Asmodee",
        )
        val listing = listing(id = 1).copy(productId = product.id)
        val items = buildIncomingItems(
            listings = listOf(listing),
            games = emptyMap(),
            sources = sources("s1"),
            ignoredKeys = emptySet(),
            today = today,
            products = mapOf(product.id to product),
        )

        assertEquals(2, items.single().offers.size)
        assertEquals("Asmodee", items.single().offers.last().sourceName)
        assertEquals(Price(49.9, "EUR"), items.single().offers.last().price)
        assertEquals("https://www.asmodee.it/prodotto/dune", items.single().offers.last().url)

        val unrelatedLink = product.copy(url = "https://news.example.com/dune")
        val withoutPublisherLink = buildIncomingItems(
            listings = listOf(listing),
            games = emptyMap(),
            sources = sources("s1"),
            ignoredKeys = emptySet(),
            today = today,
            products = mapOf(product.id to unrelatedLink),
        )
        assertEquals(1, withoutPublisherLink.single().offers.size)
    }

    @Test
    fun `listing di un product e escluso se la chiave product o listing e in ignoredKeys`() {
        val a = listing(id = 1, sourceId = "s1").copy(productId = 10L)
        val b = listing(id = 2, sourceId = "s2").copy(productId = 10L)

        val itemsIgnoredByProduct = buildIncomingItems(
            listings = listOf(a, b),
            games = emptyMap(),
            sources = sources("s1", "s2"),
            ignoredKeys = setOf("product:10"),
            today = today,
        )
        assertTrue(itemsIgnoredByProduct.isEmpty())

        val itemsIgnoredByListing = buildIncomingItems(
            listings = listOf(a, b),
            games = emptyMap(),
            sources = sources("s1", "s2"),
            ignoredKeys = setOf("listing:1"),
            today = today,
        )
        assertTrue(itemsIgnoredByListing.isEmpty())
    }
}
