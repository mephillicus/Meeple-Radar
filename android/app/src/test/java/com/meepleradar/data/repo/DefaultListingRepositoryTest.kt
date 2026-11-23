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
import org.junit.Assert.assertTrue
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

    @Test
    fun `newsItems esclude gli elementi ignorati e aggiorna newlyAddedCountFlow`() = runTest {
        val cache = FakeLocalCacheStore(
            CachedDatabase(
                listings = listOf(
                    listing(id = 1, gameId = 5, title = "Catan", releaseDate = today.minusDays(5)),
                    listing(id = 2, gameId = 6, title = "Carcassonne", releaseDate = today.minusDays(3)),
                ),
                games = listOf(game(5, "Catan"), game(6, "Carcassonne")),
                sources = listOf(Source("s1", "Store Uno")),
                newlyAddedListingIds = setOf(1L, 2L),
            ),
        )
        val api = FakeSupabaseApi(
            actions = listOf(UserActionRow(gameId = 6, listingId = null, action = Action.IGNORED)),
        )
        val repository = DefaultListingRepository(api, FakeFavoriteStore(), cache)

        val items = repository.newsItems(today)

        assertEquals(listOf("game:5"), items.map { it.key })
        assertEquals(1, repository.newlyAddedCount())
    }

    @Test
    fun `newsItems idrata dalla rete quando la cache locale e vuota`() = runTest {
        val cache = FakeLocalCacheStore(CachedDatabase())
        val api = FakeSupabaseApi(
            listings = listOf(
                listing(id = 10, gameId = 5, title = "Nuovo Gioco", releaseDate = today.minusDays(2)),
            ),
            games = listOf(game(5, "Nuovo Gioco")),
            sources = listOf(Source("s1", "Store Uno")),
        )
        val repository = DefaultListingRepository(api, FakeFavoriteStore(), cache)

        val items = repository.newsItems(today)

        assertEquals(listOf("game:5"), items.map { it.key })
    }

    @Test
    fun `isSeen remoto resta attivo anche dopo un azione FAVORITE o UNFAVORITED piu recente`() = runTest {
        val listingNews = listing(id = 1, gameId = 5, title = "Catan", releaseDate = today.minusDays(2))
        val listingUpcoming = listing(id = 2, gameId = 6, title = "Carcassonne", releaseDate = today.plusDays(2))
        val games = listOf(game(5, "Catan"), game(6, "Carcassonne"))
        val sources = listOf(Source("s1", "Store Uno"))
        val cache = FakeLocalCacheStore(
            CachedDatabase(
                listings = listOf(listingNews, listingUpcoming),
                games = games,
                sources = sources,
            ),
        )
        val api = FakeSupabaseApi(
            listings = listOf(listingNews, listingUpcoming),
            games = games,
            sources = sources,
            actions = listOf(
                UserActionRow(gameId = 5, listingId = null, action = Action.FAVORITE),
                UserActionRow(gameId = 5, listingId = null, action = Action.SEEN),
                UserActionRow(gameId = 6, listingId = null, action = Action.UNFAVORITED),
                UserActionRow(gameId = 6, listingId = null, action = Action.SEEN),
            ),
        )
        val repository = DefaultListingRepository(api, FakeFavoriteStore(), cache)

        val news = repository.newsItems(today)
        assertEquals(listOf("game:5"), news.map { it.key })
        org.junit.Assert.assertTrue(news.single().isSeen)

        val incoming = repository.incoming(today)
        val incomingItem = incoming.single { it.key == "game:6" }
        org.junit.Assert.assertTrue(incomingItem.isSeen)
    }

    @Test
    fun `Action SEEN piu recente non maschera IGNORED ne FAVORITE`() = runTest {
        val l1 = listing(id = 1, gameId = 5, title = "Ignorato", releaseDate = today.plusDays(2))
        val l2 = listing(id = 2, gameId = 6, title = "Preferito", releaseDate = today.plusDays(3))
        val games = listOf(game(5, "Ignorato"), game(6, "Preferito"))
        val sources = listOf(Source("s1", "Store Uno"))
        val cache = FakeLocalCacheStore(CachedDatabase(listings = listOf(l1, l2), games = games, sources = sources))
        val api = FakeSupabaseApi(
            listings = listOf(l1, l2),
            games = games,
            sources = sources,
            actions = listOf(
                UserActionRow(gameId = 5, listingId = null, action = Action.SEEN),
                UserActionRow(gameId = 5, listingId = null, action = Action.IGNORED),
                UserActionRow(gameId = 6, listingId = null, action = Action.SEEN),
                UserActionRow(gameId = 6, listingId = null, action = Action.FAVORITE),
            ),
        )
        val repository = DefaultListingRepository(api, FakeFavoriteStore(), cache)

        assertEquals(listOf("game:6"), repository.incoming(today).map { it.key })
        assertEquals(setOf("game:6"), repository.favoriteKeys(today))
        assertEquals(listOf("game:5"), repository.ignoredItems(today).map { it.key })
    }

    @Test
    fun `ignoredItems e restoreIgnored ripristinano il gioco in lista`() = runTest {
        val l1 = listing(id = 1, gameId = 5, title = "Catan", releaseDate = today.plusDays(2))
        val games = listOf(game(5, "Catan"))
        val sources = listOf(Source("s1", "Store Uno"))
        val cache = FakeLocalCacheStore(CachedDatabase(listings = listOf(l1), games = games, sources = sources))
        val api = FakeSupabaseApi(
            listings = listOf(l1),
            games = games,
            sources = sources,
            actions = listOf(UserActionRow(gameId = 5, listingId = null, action = Action.IGNORED)),
        )
        val repository = DefaultListingRepository(api, FakeFavoriteStore(), cache)

        assertEquals(emptyList<String>(), repository.incoming(today).map { it.key })
        assertEquals(listOf("game:5"), repository.ignoredItems(today).map { it.key })

        repository.restoreIgnored("game:5")

        assertEquals(emptyList<String>(), repository.ignoredItems(today).map { it.key })
        assertEquals(listOf("game:5"), repository.incoming(today).map { it.key })
        assertEquals(Action.UNFAVORITED, api.inserted.last().action)
    }

    @Test
    fun `forceRefresh usa sync incrementale quando cache e watermark sono recenti`() = runTest {
        val baseTimeMs = 1_760_000_000_000L
        val existingListing = listing(id = 1, gameId = 5, title = "Catan", releaseDate = today.plusDays(2))
            .copy(updatedAt = "2026-10-16T10:00:00Z")
        val updatedListing = existingListing.copy(title = "Catan Nuova Edizione", updatedAt = "2026-10-17T09:00:00Z")
        val addedListing = listing(id = 2, gameId = 6, title = "Dune", releaseDate = today.plusDays(4))
            .copy(updatedAt = "2026-10-17T09:30:00Z")

        var fullListingsCalls = 0
        var deltaSinceRequested: String? = null
        val api = object : SupabaseApi {
            override suspend fun listings(): List<Listing> {
                fullListingsCalls++
                return listOf(existingListing)
            }
            override suspend fun listingsUpdatedSince(sinceIso: String): List<Listing> {
                deltaSinceRequested = sinceIso
                return listOf(updatedListing, addedListing)
            }
            override suspend fun games(): List<Game> = emptyList()
            override suspend fun gamesUpdatedSince(sinceIso: String): List<Game> = listOf(
                game(6, "Dune").copy(updatedAt = "2026-10-17T09:31:00Z"),
            )
            override suspend fun sources(): List<Source> = listOf(Source("s1", "Store Uno"))
            override suspend fun productsUpdatedSince(sinceIso: String): List<Product> = emptyList()
            override suspend fun userActions(): List<UserActionRow> = emptyList()
            override suspend fun insertAction(row: UserActionRow) = Unit
            override suspend fun saveGameToDatabase(item: IncomingItem, selectedListingIdsToLink: List<Long>): Long? = null
        }

        val cache = object : LocalCacheStore {
            var db = CachedDatabase(
                listings = listOf(existingListing),
                games = listOf(game(5, "Catan")),
                sources = listOf(Source("s1", "Store Uno")),
                lastIncrementalSyncAt = "2026-10-16T10:00:00Z",
                lastFullSyncAt = baseTimeMs,
            )
            override fun load(): CachedDatabase = db
            override fun saveIfChanged(
                newListings: List<Listing>,
                newGames: List<Game>,
                newSources: List<Source>,
                newProducts: List<Product>,
            ): Pair<CachedDatabase, Boolean> {
                db = db.copy(listings = newListings, games = newGames, sources = newSources, products = newProducts)
                return db to true
            }
            override fun mergeDelta(
                deltaListings: List<Listing>,
                deltaGames: List<Game>,
                newSources: List<Source>,
                deltaProducts: List<Product>,
                serverWatermarkIso: String?,
            ): Pair<CachedDatabase, Boolean> {
                val merged = super.mergeDelta(deltaListings, deltaGames, newSources, deltaProducts, serverWatermarkIso)
                db = merged.first
                return merged
            }
        }

        val repository = DefaultListingRepository(
            api = api,
            favoriteStore = FakeFavoriteStore(),
            localCacheStore = cache,
            clockMs = { baseTimeMs + 60_000L },
        )

        val items = repository.incoming(today, forceRefresh = true)

        assertEquals(0, fullListingsCalls)
        assertEquals("2026-10-16T10:00:00Z", deltaSinceRequested)
        assertEquals(setOf("game:5", "game:6"), items.map { it.key }.toSet())
        assertEquals("Catan Nuova Edizione", items.single { it.key == "game:5" }.title)
        assertEquals("2026-10-17T09:31:00Z", cache.db.lastIncrementalSyncAt)
    }

    @Test
    fun `forceRefresh esegue full sync se ultimo full sync e piu vecchio di 7 giorni o se il server ritorna HTTP 400`() = runTest {
        val baseTimeMs = 1_760_000_000_000L
        val eightDaysMs = 8L * 24L * 60L * 60L * 1000L
        val l1 = listing(id = 1, gameId = 5, title = "Catan", releaseDate = today.plusDays(2))

        var fullCalls = 0
        var deltaCalls = 0
        var failDeltaWith400 = false
        val api = object : SupabaseApi {
            override suspend fun listings(): List<Listing> {
                fullCalls++
                return listOf(l1)
            }
            override suspend fun listingsUpdatedSince(sinceIso: String): List<Listing> {
                deltaCalls++
                if (failDeltaWith400) throw java.io.IOException("HTTP 400: column listing.updated_at does not exist")
                return emptyList()
            }
            override suspend fun games(): List<Game> = listOf(game(5, "Catan"))
            override suspend fun gamesUpdatedSince(sinceIso: String): List<Game> = emptyList()
            override suspend fun sources(): List<Source> = listOf(Source("s1", "Store Uno"))
            override suspend fun productsUpdatedSince(sinceIso: String): List<Product> = emptyList()
            override suspend fun userActions(): List<UserActionRow> = emptyList()
            override suspend fun insertAction(row: UserActionRow) = Unit
            override suspend fun saveGameToDatabase(item: IncomingItem, selectedListingIdsToLink: List<Long>): Long? = null
        }

        // Caso 1: > 7 giorni -> full sync diretto senza tentare delta
        val staleCache = FakeLocalCacheStore(
            CachedDatabase(
                listings = listOf(l1),
                games = listOf(game(5, "Catan")),
                sources = listOf(Source("s1", "Store Uno")),
                lastIncrementalSyncAt = "2026-10-01T00:00:00Z",
                lastFullSyncAt = baseTimeMs,
            ),
        )
        DefaultListingRepository(
            api = api,
            favoriteStore = FakeFavoriteStore(),
            localCacheStore = staleCache,
            clockMs = { baseTimeMs + eightDaysMs },
        ).incoming(today, forceRefresh = true)

        assertEquals(1, fullCalls)
        assertEquals(0, deltaCalls)

        // Caso 2: < 7 giorni ma server risponde HTTP 400 -> fallback silente a full sync
        failDeltaWith400 = true
        val freshCache = FakeLocalCacheStore(
            CachedDatabase(
                listings = listOf(l1),
                games = listOf(game(5, "Catan")),
                sources = listOf(Source("s1", "Store Uno")),
                lastIncrementalSyncAt = "2026-10-16T00:00:00Z",
                lastFullSyncAt = baseTimeMs,
            ),
        )
        DefaultListingRepository(
            api = api,
            favoriteStore = FakeFavoriteStore(),
            localCacheStore = freshCache,
            clockMs = { baseTimeMs + 60_000L },
        ).incoming(today, forceRefresh = true)

        assertEquals(2, fullCalls)
        assertEquals(1, deltaCalls)
    }

    @Test
    fun `forceRefresh incrementa catalogRevisionFlow aggiorna preorderCountFlow e Action SEEN non notifica`() = runTest {
        val preorderByListing = listing(id = 1, gameId = 5, title = "Preordine Listing", releaseDate = today.plusDays(10))
        val preorderByProduct = listing(id = 2, gameId = 6, title = "Preordine Product", releaseDate = null).copy(productId = 20L)
        val ignoredPreorder = listing(id = 3, gameId = 7, title = "Preordine Ignorato", releaseDate = today.plusDays(15))
        val pastListing = listing(id = 4, gameId = 8, title = "Gia Uscito", releaseDate = today.minusDays(5))
        val productFuture = Product(
            id = 20L,
            title = "Preordine Product",
            imageUrl = null,
            releaseDate = today.plusDays(20),
            gameId = 6L,
        )

        val api = object : SupabaseApi {
            override suspend fun listings(): List<Listing> = listOf(preorderByListing, preorderByProduct, ignoredPreorder, pastListing)
            override suspend fun games(): List<Game> = listOf(game(5, "G5"), game(6, "G6"), game(7, "G7"), game(8, "G8"))
            override suspend fun sources(): List<Source> = listOf(Source("s1", "Store Uno"))
            override suspend fun products(): List<Product> = listOf(productFuture)
            override suspend fun userActions(): List<UserActionRow> = listOf(
                UserActionRow(gameId = 7, listingId = null, action = Action.IGNORED),
            )
            override suspend fun insertAction(row: UserActionRow) = Unit
            override suspend fun saveGameToDatabase(item: IncomingItem, selectedListingIdsToLink: List<Long>): Long? = null
        }

        val cache = FakeLocalCacheStore(CachedDatabase())
        val repository = DefaultListingRepository(api, FakeFavoriteStore(), cache)

        assertEquals(0L, repository.catalogRevisionFlow().value)

        // Un refresh fatto dalla lista aggiorna la revisione e anche il contatore preordini (escludendo ignorati e includendo product.releaseDate)
        repository.incoming(today, forceRefresh = true)
        assertEquals(1L, repository.catalogRevisionFlow().value)
        assertEquals(2, repository.preorderCount())
        assertEquals(2, repository.preorderCountFlow().value)

        // Anche un refresh da Novita incrementa la revisione per sincronizzare gli altri tab
        repository.refreshNewsItems(today)
        assertEquals(2L, repository.catalogRevisionFlow().value)
        assertEquals(2, repository.preorderCountFlow().value)

        // Action.SEEN non deve incrementare catalogRevisionFlow per evitare tempeste di reload durante markAllAsSeen
        repository.setAction("game:5", Action.SEEN)
        assertEquals(2L, repository.catalogRevisionFlow().value)

        // Azioni di stato (FAVORITE / IGNORED) notificano gli altri tab
        repository.setAction("game:5", Action.FAVORITE)
        assertEquals(3L, repository.catalogRevisionFlow().value)
    }

    @Test
    fun `findItem risolve chiavi secondarie game e listing raggruppate sotto product e alert espansioni senza listing`() = runTest {
        val groupedListing = listing(id = 101, gameId = 50, title = "One Card Dungeon").copy(productId = 300L)
        val product = Product(
            id = 300L,
            title = "One Card Dungeon",
            imageUrl = null,
            releaseDate = null,
            gameId = 50L,
        )
        val baseGame = game(50, "One Card Dungeon").copy(bggId = 319263)
        val expansionWithoutListings = game(51, "One Card Dungeon: M'Guf-yn Returns").copy(
            kind = "expansion",
            bggId = 370591,
        )

        val api = object : SupabaseApi {
            override suspend fun listings(): List<Listing> = listOf(groupedListing)
            override suspend fun games(): List<Game> = listOf(baseGame, expansionWithoutListings)
            override suspend fun sources(): List<Source> = listOf(Source("s1", "Store Uno"))
            override suspend fun products(): List<Product> = listOf(product)
            override suspend fun userActions(): List<UserActionRow> = emptyList()
            override suspend fun insertAction(row: UserActionRow) = Unit
            override suspend fun saveGameToDatabase(item: IncomingItem, selectedListingIdsToLink: List<Long>): Long? = null
        }

        val cache = FakeLocalCacheStore(
            CachedDatabase(
                listings = listOf(groupedListing),
                games = listOf(baseGame, expansionWithoutListings),
                sources = listOf(Source("s1", "Store Uno")),
                products = listOf(product),
            ),
        )
        val repository = DefaultListingRepository(api, FakeFavoriteStore(), cache)

        // L'item ha key primaria "product:300", ma un avviso può cercarlo con "game:50" o "listing:101"
        val byGameKey = repository.findItem("game:50", today)
        assertEquals("product:300", byGameKey?.key)
        assertEquals("One Card Dungeon", byGameKey?.title)

        val byListingKey = repository.findItem("listing:101", today)
        assertEquals("product:300", byListingKey?.key)

        // Espansione presente in tabella games ma senza listing nei negozi
        val expansionItem = repository.findItem("game:51", today)
        assertEquals("One Card Dungeon: M'Guf-yn Returns", expansionItem?.title)
        assertEquals(370591, expansionItem?.game?.bggId)
    }

    @Test
    fun `forceFullResync bypassa il delta incrementale e aggiorna localCacheStats`() = runTest {
        val baseTimeMs = 1_776_600_000_000L
        var fullListingCalls = 0
        var deltaListingCalls = 0

        val oldListing = listing(id = 1, gameId = 10, title = "Vecchio Gioco")
        val freshListing = listing(id = 2, gameId = 20, title = "Nuovo Gioco")

        val api = object : SupabaseApi {
            override suspend fun listings(): List<Listing> {
                fullListingCalls++
                return listOf(freshListing)
            }
            override suspend fun listingsUpdatedSince(sinceIso: String): List<Listing> {
                deltaListingCalls++
                return emptyList()
            }
            override suspend fun games(): List<Game> = listOf(game(20, "Nuovo Gioco"))
            override suspend fun sources(): List<Source> = listOf(Source("s1", "Store Uno"))
            override suspend fun products(): List<Product> = emptyList()
            override suspend fun userActions(): List<UserActionRow> = emptyList()
            override suspend fun insertAction(row: UserActionRow) = Unit
            override suspend fun saveGameToDatabase(item: IncomingItem, selectedListingIdsToLink: List<Long>): Long? = null
        }

        val cache = FakeLocalCacheStore(
            CachedDatabase(
                listings = listOf(oldListing),
                games = listOf(game(10, "Vecchio Gioco")),
                sources = listOf(Source("s1", "Store Uno")),
                lastIncrementalSyncAt = "2026-04-19T00:00:00Z",
                lastFullSyncAt = baseTimeMs,
            ),
        )
        val repository = DefaultListingRepository(
            api = api,
            favoriteStore = FakeFavoriteStore(),
            localCacheStore = cache,
            clockMs = { baseTimeMs + 60_000L },
        )

        val stats = repository.forceFullResync(today)
        assertEquals(1, fullListingCalls)
        assertEquals(0, deltaListingCalls)
        assertEquals(1, stats.listingsCount)
        assertEquals(1, stats.gamesCount)
        assertEquals(1, stats.sourcesCount)

        val shareText = com.meepleradar.ui.alerts.formatFavoritesShareText(
            listOf(
                IncomingItem(
                    key = "game:20",
                    title = "Brass: Birmingham",
                    imageUrl = null,
                    releaseDate = null,
                    kind = com.meepleradar.data.model.ItemKind.NEW_UNDATED,
                    game = game(20, "Brass: Birmingham"),
                    offers = listOf(
                        com.meepleradar.data.model.Offer(
                            sourceId = "s1",
                            sourceName = "DungeonDice",
                            price = com.meepleradar.data.model.Price(59.9, "EUR"),
                            url = "https://example.com/brass",
                            availability = "available",
                            releaseDate = null,
                        ),
                    ),
                ),
            ),
        )
        assertTrue(shareText.contains("Brass: Birmingham"))
        assertTrue(shareText.contains("DungeonDice"))
        assertTrue(shareText.contains("https://example.com/brass"))
    }
}
