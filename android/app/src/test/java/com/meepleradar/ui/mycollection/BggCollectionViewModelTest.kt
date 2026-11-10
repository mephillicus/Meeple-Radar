package com.meepleradar.ui.mycollection

import com.meepleradar.data.local.BggCollectionStore
import com.meepleradar.data.local.CachedBggCollection
import com.meepleradar.data.model.Action
import com.meepleradar.data.model.BggCollectionGame
import com.meepleradar.data.model.BggExpansion
import com.meepleradar.data.model.Game
import com.meepleradar.data.model.GameSavePreview
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.ItemKind
import com.meepleradar.data.model.Source
import com.meepleradar.data.remote.BggCollectionApi
import com.meepleradar.data.repo.ListingRepository
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BggCollectionViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeStore(var cached: CachedBggCollection? = null) : BggCollectionStore {
        var cleared = false

        override fun load(): CachedBggCollection? = cached
        override fun save(collection: CachedBggCollection) {
            cached = collection
        }
        override fun clear() {
            cached = null
            cleared = true
        }
    }

    private class FakeApi(var result: Result<List<BggCollectionGame>>) : BggCollectionApi {
        val usernames = mutableListOf<String>()
        var expansionsResult: Result<List<BggExpansion>> = Result.success(emptyList())

        override suspend fun ownedGames(username: String, apiToken: String): List<BggCollectionGame> {
            usernames += username
            return result.getOrThrow()
        }

        override suspend fun expansions(bggIds: List<Int>, apiToken: String): List<BggExpansion> =
            expansionsResult.getOrThrow()
    }

    private class FakeListingRepository : ListingRepository {
        var news = emptyList<IncomingItem>()
        var preorders = emptyList<IncomingItem>()

        override suspend fun incoming(
            today: LocalDate,
            sourceIds: Set<String>?,
            includeUndated: Boolean,
            includePriceless: Boolean,
            forceRefresh: Boolean,
            onProgress: ((Int, String) -> Unit)?,
        ): List<IncomingItem> = emptyList()

        override suspend fun favorites(
            today: LocalDate,
            sourceIds: Set<String>?,
            includeUndated: Boolean,
            includePriceless: Boolean,
            forceRefresh: Boolean,
            onProgress: ((Int, String) -> Unit)?,
        ): List<IncomingItem> = emptyList()

        override suspend fun newsItems(today: LocalDate): List<IncomingItem> = news
        override suspend fun preorderItems(today: LocalDate): List<IncomingItem> = preorders
        override suspend fun findItem(key: String, today: LocalDate): IncomingItem? = null
        override suspend fun sources(): List<Source> = emptyList()
        override suspend fun setAction(key: String, action: Action) = Unit
        override suspend fun previewGameSave(item: IncomingItem): GameSavePreview = GameSavePreview()

        override suspend fun saveGameToDatabase(item: IncomingItem, selectedListingIdsToLink: List<Long>): Long? {
            // No-op per test
            return null
        }
        override fun lastDbModifiedTimestamp(): Long = 0L
        override fun newlyAddedCount(): Int = 0
        override fun newlyAddedCountFlow() = kotlinx.coroutines.flow.MutableStateFlow(0)
        override fun preorderCount(): Int = 0
    }

    private val game = BggCollectionGame(42, "The Old King's Crown", "boardgame", 2024, null, 7.8)

    @Test
    fun `connessione salva utente e collezione`() = runTest(dispatcher) {
        val store = FakeStore()
        val api = FakeApi(Result.success(listOf(game)))
        val viewModel = BggCollectionViewModel(api, store, FakeListingRepository())

        viewModel.connect("  username  ", "token")
        advanceUntilIdle()

        assertEquals(listOf("username"), api.usernames)
        assertEquals("username", store.cached?.username)
        assertEquals(listOf(game), store.cached?.games)
        assertTrue(viewModel.state.value is BggCollectionUiState.Content)
    }

    @Test
    fun `errore aggiornamento mantiene cache e logout la rimuove`() = runTest(dispatcher) {
        val cached = CachedBggCollection("username", listOf(game), 123L, "token")
        val store = FakeStore(cached)
        val api = FakeApi(Result.failure(IllegalStateException("BGG non disponibile")))
        val viewModel = BggCollectionViewModel(api, store, FakeListingRepository())

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.state.value as BggCollectionUiState.Content
        assertEquals(listOf(game), state.games)
        assertEquals("BGG non disponibile", state.error)

        viewModel.logout()

        assertTrue(store.cleared)
        assertTrue(viewModel.state.value is BggCollectionUiState.Setup)
    }

    @Test
    fun `carica espansioni solo quando si apre una sotto-pillola`() = runTest(dispatcher) {
        val ownedExpansion = game.copy(
            bggId = 501,
            name = "Prima espansione",
            kind = "boardgameexpansion",
            expandsBggId = 42,
        )
        val cachedExpansions = listOf(
            BggExpansion(501, "Prima espansione", 42, "https://boardgamegeek.com/boardgameexpansion/501"),
            BggExpansion(502, "Seconda espansione", 42, "https://boardgamegeek.com/boardgameexpansion/502"),
        )
        val store = FakeStore(
            CachedBggCollection("username", listOf(game, ownedExpansion), 123L, "token", cachedExpansions, true),
        )
        val api = FakeApi(Result.success(listOf(game)))
        val viewModel = BggCollectionViewModel(api, store, FakeListingRepository())

        advanceUntilIdle()
        viewModel.toggleExpansionSection(game.bggId, ExpansionOwnership.OWNED)
        advanceUntilIdle()

        val state = viewModel.state.value as BggCollectionUiState.Content
        val loadedExpansions = state.expansionsByGame[game.bggId] as ExpansionLoadState.Loaded
        assertEquals(listOf(501, 502), loadedExpansions.items.map { it.bggId })
        assertEquals(setOf(501), state.games.filter { it.expandsBggId != null }.map { it.bggId }.toSet())
        assertEquals(setOf(ExpansionOwnership.OWNED), state.expandedSections[game.bggId])
    }

    @Test
    fun `classifica Agora come espansione tramite relazione BGG`() = runTest(dispatcher) {
        val baseGame = game.copy(bggId = 173346, name = "7 Wonders Duel")
        val agora = game.copy(bggId = 309116, name = "7 Wonders Duel: Agora", kind = "boardgame")
        val store = FakeStore()
        val api = FakeApi(Result.success(listOf(baseGame, agora)))
        api.expansionsResult = Result.success(
            listOf(
                BggExpansion(
                    bggId = 309116,
                    name = "7 Wonders Duel: Agora",
                    expandsBggId = 173346,
                    url = "https://boardgamegeek.com/boardgameexpansion/309116",
                ),
            ),
        )
        val viewModel = BggCollectionViewModel(api, store, FakeListingRepository())

        viewModel.connect("username", "token")
        advanceUntilIdle()

        val state = viewModel.state.value as BggCollectionUiState.Content
        assertEquals("boardgameexpansion", state.games.single { it.bggId == 309116 }.kind)
        assertEquals(173346, state.games.single { it.bggId == 309116 }.expandsBggId)
        assertEquals(api.expansionsResult.getOrThrow(), store.cached?.expansions)
        assertTrue(store.cached?.expansionsLoaded == true)
    }

    @Test
    fun `associa espansione ai preordini e alle novita per il pallino di apertura`() = runTest(dispatcher) {
        val base = game.copy(bggId = 173346, name = "7 Wonders Duel")
        val agora = game.copy(bggId = 309116, name = "7 Wonders Duel: Agora", kind = "boardgame")
        val api = FakeApi(Result.success(listOf(base, agora))).apply {
            expansionsResult = Result.success(
                listOf(BggExpansion(309116, agora.name, base.bggId, "https://boardgamegeek.com/boardgameexpansion/309116")),
            )
        }
        val repository = FakeListingRepository().apply {
            preorders = listOf(
                IncomingItem(
                    key = "game:309116",
                    title = agora.name,
                    imageUrl = null,
                    releaseDate = LocalDate.now().plusDays(10),
                    kind = ItemKind.UPCOMING,
                    game = Game(309116, agora.name, "expansion", 309116, null, null, null),
                    offers = emptyList(),
                ),
            )
            news = listOf(
                IncomingItem(
                    key = "game:501",
                    title = "Novità",
                    imageUrl = null,
                    releaseDate = null,
                    kind = ItemKind.NEW_UNDATED,
                    game = Game(501, "Novità", "expansion", 501, null, null, null),
                    offers = emptyList(),
                ),
            )
        }
        val viewModel = BggCollectionViewModel(api, FakeStore(), repository)

        viewModel.connect("username", "token")
        advanceUntilIdle()

        val state = viewModel.state.value as BggCollectionUiState.Content
        assertEquals(ExpansionAvailabilityType.PREORDER, state.expansionAvailabilityById[309116]?.type)
        assertEquals(ExpansionAvailabilityType.NEWS, state.expansionAvailabilityById[501]?.type)
    }
}
