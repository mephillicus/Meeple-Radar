package com.meepleradar.data.repo

import com.meepleradar.data.local.CachedDatabase
import com.meepleradar.data.local.FavoriteStore
import com.meepleradar.data.local.LocalCacheStore
import com.meepleradar.data.model.Action
import com.meepleradar.data.model.Game
import com.meepleradar.data.model.GameSavePreview
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.ItemKind
import com.meepleradar.data.model.Listing
import com.meepleradar.data.model.Product
import com.meepleradar.data.model.Source
import com.meepleradar.data.remote.BoardGameDetails
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
        private val linkedProductId: Long? = null,
        private val savedGameId: Long? = null,
    ) : SupabaseApi {
        val inserted = mutableListOf<UserActionRow>()

        override suspend fun listings(): List<Listing> = listings

        override suspend fun games(): List<Game> = games

        override suspend fun sources(): List<Source> = sources

        override suspend fun userActions(): List<UserActionRow> = actions

        override suspend fun insertAction(row: UserActionRow) {
            inserted += row
        }

        override suspend fun linkListingToBgg(
            listingId: Long,
            currentGameId: Long?,
            details: BoardGameDetails,
            fallbackTitle: String,
        ): Pair<Long?, Long?> = currentGameId to linkedProductId

        override suspend fun saveGameToDatabase(item: IncomingItem, selectedListingIdsToLink: List<Long>): Long? {
            return savedGameId
        }

    }

    private class FakeLocalCacheStore(private var database: CachedDatabase) : LocalCacheStore {
        override fun load(): CachedDatabase = database

        override fun saveIfChanged(
            newListings: List<Listing>,
            newGames: List<Game>,
            newSources: List<Source>,
            newProducts: List<Product>,
        ): Pair<CachedDatabase, Boolean> {
            database = database.copy(
                listings = newListings,
                games = newGames,
                sources = newSources,
                products = newProducts,
            )
            return database to true
        }
    }

    private fun listing(
        id: Long,
        sourceId: String = "s1",
        title: String = "Gioco $id",
        releaseDate: LocalDate? = null,
        gameId: Long? = null,
        availability: String? = null,
    ) = Listing(
        id = id,
        sourceId = sourceId,
        title = title,
        url = "https://example.com/$id",
        price = null,
        currency = null,
        availability = availability,
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
        assertEquals("Gioco 1", items[0].title)
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
    fun `newsItems include solo novità recenti non preordine`() = runTest {
        val cache = FakeLocalCacheStore(
            CachedDatabase(
                listings = listOf(
                    listing(
                        id = 1,
                        gameId = 5,
                        title = "Catan",
                        releaseDate = today.minusDays(20),
                    ),
                    listing(
                        id = 2,
                        gameId = 6,
                        title = "Carcassonne",
                        releaseDate = today.plusDays(10),
                    ),
                ),
                games = listOf(game(5, "Catan"), game(6, "Carcassonne")),
                sources = listOf(Source("s1", "Store Uno")),
                newlyAddedListingIds = setOf(1L, 2L),
            ),
        )
        val repository = DefaultListingRepository(FakeSupabaseApi(), FakeFavoriteStore(), cache)

        val items = repository.newsItems(today)

        assertEquals(listOf("game:5"), items.map { it.key })
    }

    @Test
    fun `preorderItems include solo i giochi con data successiva a oggi`() = runTest {
        val cache = FakeLocalCacheStore(
            CachedDatabase(
                listings = listOf(
                    listing(id = 1, gameId = 5, title = "Catan", releaseDate = today.minusDays(20)),
                    listing(id = 2, gameId = 6, title = "Carcassonne", releaseDate = today.plusDays(10)),
                ),
                games = listOf(game(5, "Catan"), game(6, "Carcassonne")),
                sources = listOf(Source("s1", "Store Uno")),
            ),
        )
        val repository = DefaultListingRepository(FakeSupabaseApi(), FakeFavoriteStore(), cache)

        val items = repository.preorderItems(today)

        assertEquals(listOf("game:6"), items.map { it.key })
    }

    @Test
    fun `newsItems imposta isNewlyAdded a true per gli id presenti in newlyAddedListingIds`() = runTest {
        val cache = FakeLocalCacheStore(
            CachedDatabase(
                listings = listOf(
                    listing(id = 230235, gameId = 5, title = "Nuovo Gioco", releaseDate = today.minusDays(5)),
                ),
                games = listOf(game(5, "Nuovo Gioco")),
                sources = listOf(Source("s1", "Store Uno")),
                newlyAddedListingIds = setOf(230235L),
            ),
        )
        val repository = DefaultListingRepository(FakeSupabaseApi(), FakeFavoriteStore(), cache)

        val items = repository.newsItems(today)

        assertEquals(1, items.size)
        org.junit.Assert.assertTrue(items[0].isNewlyAdded)
    }

    @Test
    fun `setAction con chiave product invia productId`() = runTest {
        val api = FakeSupabaseApi()

        DefaultListingRepository(api, FakeFavoriteStore()).setAction("product:12", Action.FAVORITE)

        assertEquals(listOf(UserActionRow(gameId = null, listingId = null, action = Action.FAVORITE, productId = 12)), api.inserted)
    }

    @Test
    fun `findItem con chiave bgg restituisce il gioco salvato dal database locale se gia presente`() = runTest {
        val savedGame = Game(
            id = 1044L,
            name = "1977 Fuga da New York",
            kind = "base",
            bggId = 362723,
            bggRating = 7.3,
            bggRank = 5685,
            imageUrl = "https://example.com/image.jpg",
            description = "Descrizione salvata",
        )
        val cache = FakeLocalCacheStore(
            CachedDatabase(
                games = listOf(savedGame),
            ),
        )
        val repository = DefaultListingRepository(FakeSupabaseApi(), FakeFavoriteStore(), cache)

        val item = repository.findItem("bgg:362723", today)

        org.junit.Assert.assertNotNull(item)
        assertEquals("bgg:362723", item?.key)
        assertEquals(1044L, item?.game?.id)
        assertEquals(7.3, item?.game?.bggRating)
        assertEquals(5685, item?.game?.bggRank)
        assertEquals("Descrizione salvata", item?.game?.description)
    }

    @Test
    fun `associazione BGG aggiorna anche la cache locale del game e del listing`() = runTest {
        val cachedGame = game(5, "Titolo precedente")
        val cachedListing = listing(id = 10, gameId = 5)
        val cache = FakeLocalCacheStore(
            CachedDatabase(
                listings = listOf(cachedListing),
                games = listOf(cachedGame),
            ),
        )
        val repository = DefaultListingRepository(
            api = FakeSupabaseApi(),
            favoriteStore = FakeFavoriteStore(),
            localCacheStore = cache,
        )
        val details = BoardGameDetails(
            bggId = 321,
            imageUrl = "https://bgg.example/cover.jpg",
            description = null,
            yearPublished = 2024,
            minPlayers = 2,
            maxPlayers = 4,
            playingTime = 60,
            minAge = 10,
            designers = emptyList(),
            categories = emptyList(),
            mechanics = emptyList(),
            primaryName = "Titolo BGG",
            bggRating = 7.6,
            bggRank = 28,
        )

        repository.linkListingToBgg(10, 5, details, "Fallback")

        val updated = cache.load()
        assertEquals(321, updated.games.single().bggId)
        assertEquals(7.6, updated.games.single().bggRating!!, 0.0001)
        assertEquals(28, updated.games.single().bggRank)
        assertEquals("https://bgg.example/cover.jpg", updated.games.single().imageUrl)
        assertEquals(5L, updated.listings.single().gameId)

        repository.updateGameBggStats(321, 8.1, 21)

        assertEquals(8.1, cache.load().games.single().bggRating!!, 0.0001)
        assertEquals(21, cache.load().games.single().bggRank)
    }

    @Test
    fun `associazione BGG senza listing aggiorna i listing locali per mostrare subito la card`() = runTest {
        val existingListing = listing(id = 10, title = "Offerta", gameId = null)
        val meepleListing = listing(
            id = 12,
            sourceId = "meeple_app",
            title = "Titolo BGG",
            gameId = 5,
        ).copy(
            url = "https://boardgamegeek.com/boardgame/321",
            imageUrl = "https://bgg.example/cover.jpg",
            productId = 6,
        )
        val cache = FakeLocalCacheStore(
            CachedDatabase(
                listings = listOf(existingListing),
                games = listOf(game(5, "Titolo precedente")),
            ),
        )
        val repository = DefaultListingRepository(
            api = FakeSupabaseApi(listings = listOf(existingListing.copy(gameId = 5), meepleListing), linkedProductId = 6),
            favoriteStore = FakeFavoriteStore(),
            localCacheStore = cache,
        )
        val details = BoardGameDetails(
            bggId = 321,
            imageUrl = "https://bgg.example/cover.jpg",
            description = "BGG description",
            yearPublished = 2024,
            minPlayers = 2,
            maxPlayers = 4,
            playingTime = 60,
            minAge = 10,
            designers = emptyList(),
            categories = emptyList(),
            mechanics = emptyList(),
            primaryName = "Titolo BGG",
            bggRating = 7.6,
            bggRank = 28,
        )

        repository.linkListingToBgg(0, 5, details, "Fallback")

        assertEquals(setOf(10L, 12L), cache.load().listings.map { it.id }.toSet())
        assertEquals(5L, cache.load().listings.single { it.id == 12L }.gameId)
        assertEquals(6L, cache.load().listings.single { it.id == 12L }.productId)
        assertEquals(6L, cache.load().products.single().id)
    }

    @Test
    fun `salvataggio BGG aggiorna i listing locali per mostrare subito la card`() = runTest {
        val meepleListing = listing(
            id = 12,
            sourceId = "meeple_app",
            title = "Titolo BGG",
            gameId = 5,
        ).copy(productId = 6)
        val cache = FakeLocalCacheStore(CachedDatabase())
        val repository = DefaultListingRepository(
            api = FakeSupabaseApi(listings = listOf(meepleListing), savedGameId = 5),
            favoriteStore = FakeFavoriteStore(),
            localCacheStore = cache,
        )
        val item = IncomingItem(
            key = "bgg:321",
            title = "Titolo BGG",
            imageUrl = null,
            releaseDate = null,
            kind = ItemKind.NEW_UNDATED,
            game = Game(
                id = 0,
                name = "Titolo BGG",
                kind = "base",
                bggId = 321,
                bggRating = 7.6,
                bggRank = 28,
                imageUrl = null,
            ),
            offers = emptyList(),
        )

        repository.saveGameToDatabase(item, emptyList())

        assertEquals(12L, cache.load().listings.single().id)
        assertEquals(5L, cache.load().listings.single().gameId)
        assertEquals(5L, cache.load().games.single().id)
    }
}
