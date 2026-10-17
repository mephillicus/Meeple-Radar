package com.meepleradar.data.repo

import com.meepleradar.data.model.Action
import com.meepleradar.data.model.Game
import com.meepleradar.data.model.Listing
import com.meepleradar.data.model.Source
import com.meepleradar.data.remote.SupabaseApi
import com.meepleradar.data.remote.UserActionRow
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultListingRepositoryTest {

    private val today = LocalDate.of(2026, 10, 17)

    private class FakeSupabaseApi(
        private val listings: List<Listing> = emptyList(),
        private val games: List<Game> = emptyList(),
        private val sources: List<Source> = emptyList(),
        private val actions: List<UserActionRow> = emptyList(),
    ) : SupabaseApi {
        val inserted = mutableListOf<UserActionRow>()

        override suspend fun listings(): List<Listing> = listings

        override suspend fun games(): List<Game> = games

        override suspend fun sources(): List<Source> = sources

        override suspend fun userActions(): List<UserActionRow> = actions

        override suspend fun insertAction(row: UserActionRow) {
            inserted += row
        }
    }

    private fun listing(
        id: Long,
        sourceId: String = "s1",
        title: String = "Gioco $id",
        releaseDate: LocalDate? = null,
        gameId: Long? = null,
    ) = Listing(
        id = id,
        sourceId = sourceId,
        title = title,
        url = "https://example.com/$id",
        price = null,
        currency = null,
        availability = null,
        releaseDate = releaseDate,
        imageUrl = null,
        gameId = gameId,
        matchConfidence = null,
    )

    private fun game(id: Long, name: String) =
        Game(id = id, name = name, kind = "base", bggId = null, bggRating = null, bggRank = null, imageUrl = null)

    @Test
    fun `incoming combina listing game e source ed esclude gli ignorati`() = runTest {
        val api = FakeSupabaseApi(
            listings = listOf(
                listing(id = 1, gameId = 5, releaseDate = today.plusDays(1)),
                listing(id = 2, gameId = 6, releaseDate = today.plusDays(2)),
            ),
            games = listOf(game(5, "Catan"), game(6, "Carcassonne")),
            sources = listOf(Source("s1", "Store Uno")),
            actions = listOf(UserActionRow(gameId = 6, listingId = null, action = Action.IGNORED)),
        )

        val items = DefaultListingRepository(api).incoming(today)

        assertEquals(listOf("game:5"), items.map { it.key })
        assertEquals("Catan", items[0].title)
        assertEquals("Store Uno", items[0].offers[0].sourceName)
    }

    @Test
    fun `favorites ritorna solo i game preferiti anche con game_id presente`() = runTest {
        val api = FakeSupabaseApi(
            listings = listOf(
                listing(id = 1, gameId = 5, releaseDate = today.plusDays(1)),
                listing(id = 2, gameId = 6, releaseDate = today.plusDays(2)),
            ),
            games = listOf(game(5, "Catan"), game(6, "Carcassonne")),
            sources = listOf(Source("s1", "Store Uno")),
            actions = listOf(UserActionRow(gameId = 5, listingId = null, action = Action.FAVORITE)),
        )

        val items = DefaultListingRepository(api).favorites(today)

        assertEquals(listOf("game:5"), items.map { it.key })
    }

    @Test
    fun `setAction con chiave game invia gameId con listingId null`() = runTest {
        val api = FakeSupabaseApi()

        DefaultListingRepository(api).setAction("game:5", Action.FAVORITE)

        assertEquals(listOf(UserActionRow(gameId = 5, listingId = null, action = Action.FAVORITE)), api.inserted)
    }

    @Test
    fun `setAction con chiave listing invia listingId con gameId null`() = runTest {
        val api = FakeSupabaseApi()

        DefaultListingRepository(api).setAction("listing:9", Action.IGNORED)

        assertEquals(listOf(UserActionRow(gameId = null, listingId = 9, action = Action.IGNORED)), api.inserted)
    }

    @Test
    fun `favorites con azione su listing non lancia`() = runTest {
        val api = FakeSupabaseApi(
            listings = listOf(listing(id = 9, gameId = null, releaseDate = null)),
            sources = listOf(Source("s1", "Store Uno")),
            actions = listOf(UserActionRow(gameId = null, listingId = 9, action = Action.FAVORITE)),
        )

        val items = DefaultListingRepository(api).favorites(today)

        assertEquals(listOf("listing:9"), items.map { it.key })
    }
}
