package com.meepleradar.ui.favorites

import com.meepleradar.data.model.Action
import com.meepleradar.data.model.GameSavePreview
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.ItemKind
import com.meepleradar.data.model.Offer
import com.meepleradar.data.model.Price
import com.meepleradar.data.model.Source
import com.meepleradar.data.repo.ListingRepository
import com.meepleradar.ui.incoming.IncomingUiState
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
class FavoritesViewModelTest {

    private val today = LocalDate.of(2026, 10, 17)
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeRepo(
        var favoriteItems: List<IncomingItem> = emptyList(),
        private val ignoredList: MutableList<IncomingItem> = mutableListOf(),
        private val failure: Throwable? = null,
    ) : ListingRepository {
        val actions = mutableListOf<Pair<String, Action>>()
        val restoredKeys = mutableListOf<String>()
        var requestedSourceIds: Set<String>? = emptySet()
        val catalogRevision = MutableStateFlow(0L)

        override suspend fun incoming(today: LocalDate, sourceIds: Set<String>?, includeUndated: Boolean, includePriceless: Boolean, forceRefresh: Boolean, onProgress: ((Int, String) -> Unit)?): List<IncomingItem> = emptyList()

        override suspend fun favorites(today: LocalDate, sourceIds: Set<String>?, includeUndated: Boolean, includePriceless: Boolean, forceRefresh: Boolean, onProgress: ((Int, String) -> Unit)?): List<IncomingItem> {
            requestedSourceIds = sourceIds
            failure?.let { throw it }
            return favoriteItems
        }

        override suspend fun ignoredItems(today: LocalDate): List<IncomingItem> = ignoredList.toList()

        override suspend fun restoreIgnored(key: String) {
            restoredKeys += key
            ignoredList.removeAll { it.key == key }
        }

        override suspend fun sources(): List<Source> = emptyList()

        override suspend fun setAction(key: String, action: Action) {
            actions += key to action
        }

        override suspend fun previewGameSave(item: IncomingItem): GameSavePreview = GameSavePreview()

        override suspend fun saveGameToDatabase(item: IncomingItem, selectedListingIdsToLink: List<Long>): Long? {
            // No-op per test
            return null
        }

        override fun lastDbModifiedTimestamp(): Long = 0L

        override fun catalogRevisionFlow(): StateFlow<Long> = catalogRevision

        override fun newlyAddedCount(): Int = 0

        override fun newlyAddedCountFlow(): StateFlow<Int> = MutableStateFlow(0)

        override fun preorderCount(): Int = 0

        override suspend fun newsItems(today: LocalDate): List<IncomingItem> = emptyList()

        override suspend fun preorderItems(today: LocalDate): List<IncomingItem> = emptyList()

        override suspend fun findItem(key: String, today: LocalDate): IncomingItem? = favoriteItems.firstOrNull { it.key == key }
    }

    private fun item(key: String) = IncomingItem(
        key = key,
        title = "Gioco $key",
        imageUrl = null,
        releaseDate = null,
        kind = ItemKind.NEW_UNDATED,
        game = null,
        offers = emptyList(),
    )

    @Test
    fun `favorites mostra solo gli item preferiti`() = runTest(dispatcher) {
        val repo = FakeRepo(listOf(item("game:5")))
        val viewModel = FavoritesViewModel(repo, today)

        viewModel.refresh()
        assertEquals(IncomingUiState.Loading, viewModel.state.value)

        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is IncomingUiState.Content)
        state as IncomingUiState.Content
        assertEquals(listOf("game:5"), state.items.map { it.key })
        assertEquals(setOf("game:5"), state.favorites)
        assertEquals(null, repo.requestedSourceIds)
    }

    @Test
    fun `lista vuota produce Content con lista vuota`() = runTest(dispatcher) {
        val viewModel = FavoritesViewModel(FakeRepo(), today)

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is IncomingUiState.Content)
        state as IncomingUiState.Content
        assertTrue(state.items.isEmpty())
        assertTrue(state.favorites.isEmpty())
    }

    @Test
    fun `errore rete produce Error`() = runTest(dispatcher) {
        val viewModel = FavoritesViewModel(FakeRepo(failure = IOException("offline")), today)

        viewModel.refresh()
        advanceUntilIdle()

        assertTrue(viewModel.state.value is IncomingUiState.Error)
        assertEquals("offline", (viewModel.state.value as IncomingUiState.Error).message)
    }

    @Test
    fun `toggleFavorite registra l azione e rimuove l item dalla lista`() = runTest(dispatcher) {
        val repo = FakeRepo(listOf(item("game:5")))
        val viewModel = FavoritesViewModel(repo, today)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.toggleFavorite(item("game:5"))
        advanceUntilIdle()

        assertEquals(listOf("game:5" to Action.UNFAVORITED), repo.actions)
        assertTrue((viewModel.state.value as IncomingUiState.Content).items.isEmpty())
    }

    @Test
    fun `ignore registra l azione e rimuove l item dalla lista`() = runTest(dispatcher) {
        val repo = FakeRepo(listOf(item("game:5")))
        val viewModel = FavoritesViewModel(repo, today)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.ignore(item("game:5"))
        advanceUntilIdle()

        assertEquals(listOf("game:5" to Action.IGNORED), repo.actions)
        assertTrue((viewModel.state.value as IncomingUiState.Content).items.isEmpty())
        assertEquals(listOf("game:5"), viewModel.ignoredItems.value.map { it.key })
    }

    @Test
    fun `ricerca e chip disponibilita filtrano i preferiti`() = runTest(dispatcher) {
        val available = item("game:1").copy(
            title = "Catan",
            offers = listOf(Offer("s1", "Store", Price(35.0, "EUR"), "url1", "InStock")),
        )
        val preorder = item("game:2").copy(
            title = "Brass",
            releaseDate = today.plusDays(15),
            offers = listOf(Offer("s1", "Store", Price(60.0, "EUR"), "url2", "PreOrder", today.plusDays(15))),
        )
        val soldOut = item("game:3").copy(
            title = "Dune",
            offers = listOf(Offer("s1", "Store", Price(50.0, "EUR"), "url3", "OutOfStock")),
        )
        val viewModel = FavoritesViewModel(FakeRepo(listOf(available, preorder, soldOut)), today)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.setAvailabilityFilter(FavoriteAvailabilityFilter.AVAILABLE)
        assertEquals(listOf("game:1"), (viewModel.state.value as IncomingUiState.Content).items.map { it.key })

        viewModel.setAvailabilityFilter(FavoriteAvailabilityFilter.PREORDER)
        assertEquals(listOf("game:2"), (viewModel.state.value as IncomingUiState.Content).items.map { it.key })

        viewModel.setAvailabilityFilter(FavoriteAvailabilityFilter.UNAVAILABLE)
        assertEquals(listOf("game:3"), (viewModel.state.value as IncomingUiState.Content).items.map { it.key })

        viewModel.setAvailabilityFilter(FavoriteAvailabilityFilter.ALL)
        viewModel.setSearchQuery("cat")
        assertEquals(listOf("game:1"), (viewModel.state.value as IncomingUiState.Content).items.map { it.key })
    }

    @Test
    fun `restoreIgnored rimuove il gioco dagli ignorati e chiama il repository`() = runTest(dispatcher) {
        val ignoredItem = item("game:9")
        val repo = FakeRepo(ignoredList = mutableListOf(ignoredItem))
        val viewModel = FavoritesViewModel(repo, today)
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(listOf("game:9"), viewModel.ignoredItems.value.map { it.key })

        viewModel.restoreIgnored(ignoredItem)
        advanceUntilIdle()

        assertEquals(listOf("game:9"), repo.restoredKeys)
        assertTrue(viewModel.ignoredItems.value.isEmpty())
    }

    @Test
    fun `si ricarica automaticamente dalla cache quando cambia catalogRevisionFlow`() = runTest(dispatcher) {
        val repo = FakeRepo(listOf(item("game:5")))
        val viewModel = FavoritesViewModel(repo, today)
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(listOf("game:5"), (viewModel.state.value as IncomingUiState.Content).items.map { it.key })

        // Simula aggiornamento catalogo da un altro tab
        repo.favoriteItems = listOf(item("game:5"), item("game:8"))
        repo.catalogRevision.value = 1L
        advanceUntilIdle()

        val state = viewModel.state.value as IncomingUiState.Content
        assertEquals(listOf("game:5", "game:8"), state.items.map { it.key })
        assertEquals(setOf("game:5", "game:8"), state.favorites)
    }
}
