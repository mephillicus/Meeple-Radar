package com.meepleradar.data.repo

import com.meepleradar.data.model.Action
import com.meepleradar.data.model.Game
import com.meepleradar.data.model.Listing
import com.meepleradar.data.model.Source
import com.meepleradar.data.local.FavoriteStore
import com.meepleradar.data.remote.SupabaseApi
import com.meepleradar.data.remote.UserActionRow
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultListingRepositoryTest {

    private val today = LocalDate.of(2026, 10, 17)

    private class FakeFavoriteStore(initialKeys: Set<String> = emptySet()) : FavoriteStore {
        private val favoriteKeys = initialKeys.toMutableSet()

        override fun keys(): Set<String> = favoriteKeys.toSet()

        override fun setFavorite(key: String, favorite: Boolean) {
            if (favorite) favoriteKeys.add(key) else favoriteKeys.remove(key)
        }
    }

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

        val items = DefaultListingRepository(api, FakeFavoriteStore()).incoming(today)

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
        )

        val items = DefaultListingRepository(api, FakeFavoriteStore(setOf("game:5"))).favorites(today)

        assertEquals(listOf("game:5"), items.map { it.key })
    }

    @Test
    fun `l azione piu recente rimuove il preferito senza ignorare il gioco`() = runTest {
        val api = FakeSupabaseApi(
            listings = listOf(listing(id = 1, gameId = 5, releaseDate = today.plusDays(1))),
            games = listOf(game(5, "Catan")),
            sources = listOf(Source("s1", "Store Uno")),
            actions = listOf(
                UserActionRow(gameId = 5, listingId = null, action = Action.UNFAVORITED),
                UserActionRow(gameId = 5, listingId = null, action = Action.FAVORITE),
            ),
        )
        val repo = DefaultListingRepository(api, FakeFavoriteStore())

        assertEquals(emptyList<com.meepleradar.data.model.IncomingItem>(), repo.favorites(today))
        assertEquals(listOf("game:5"), repo.incoming(today).map { it.key })
    }

    @Test
    fun `setAction favorite e unfavorite sono persistiti localmente`() = runTest {
        val api = FakeSupabaseApi(
            listings = listOf(listing(id = 5, gameId = 5, releaseDate = today.plusDays(1))),
            games = listOf(game(5, "Catan")),
            sources = listOf(Source("s1", "Store Uno")),
        )
        val store = FakeFavoriteStore()
        val repo = DefaultListingRepository(api, store)

        repo.setAction("game:5", Action.FAVORITE)
        assertEquals(setOf("game:5"), store.keys())
        assertEquals(listOf("game:5"), repo.favorites(today).map { it.key })

        repo.setAction("game:5", Action.UNFAVORITED)
        assertEquals(emptySet<String>(), store.keys())
        assertEquals(emptyList<com.meepleradar.data.model.IncomingItem>(), repo.favorites(today))
        assertEquals(
            listOf(
                UserActionRow(gameId = 5, listingId = null, action = Action.FAVORITE),
                UserActionRow(gameId = 5, listingId = null, action = Action.UNFAVORITED),
            ),
            api.inserted,
        )
    }

    @Test
    fun `setAction con chiave listing invia listingId con gameId null`() = runTest {
        val api = FakeSupabaseApi()

        DefaultListingRepository(api, FakeFavoriteStore()).setAction("listing:9", Action.IGNORED)

        assertEquals(listOf(UserActionRow(gameId = null, listingId = 9, action = Action.IGNORED)), api.inserted)
    }

    @Test
    fun `favorites con azione su listing non lancia`() = runTest {
        val api = FakeSupabaseApi(
            listings = listOf(listing(id = 9, gameId = null, releaseDate = null)),
            sources = listOf(Source("s1", "Store Uno")),
        )

        val items = DefaultListingRepository(api, FakeFavoriteStore(setOf("listing:9"))).favorites(today)

        assertEquals(listOf("listing:9"), items.map { it.key })
    }

    @Test
    fun `setAction con chiave product invia productId`() = runTest {
        val api = FakeSupabaseApi()

        DefaultListingRepository(api, FakeFavoriteStore()).setAction("product:12", Action.FAVORITE)

        assertEquals(listOf(UserActionRow(gameId = null, listingId = null, action = Action.FAVORITE, productId = 12)), api.inserted)
    }
}
