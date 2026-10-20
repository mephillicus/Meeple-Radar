package com.meepleradar.ui.favorites

import com.meepleradar.data.model.Action
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.ItemKind
import com.meepleradar.data.model.Source
import com.meepleradar.data.repo.ListingRepository
import com.meepleradar.ui.incoming.IncomingUiState
import java.io.IOException
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
        private val favoriteItems: List<IncomingItem> = emptyList(),
        private val failure: Throwable? = null,
    ) : ListingRepository {
        val actions = mutableListOf<Pair<String, Action>>()
        var requestedSourceIds: Set<String>? = emptySet()

        override suspend fun incoming(today: LocalDate, sourceIds: Set<String>?, includeUndated: Boolean, includePriceless: Boolean, onProgress: ((Int, String) -> Unit)?): List<IncomingItem> = emptyList()

        override suspend fun favorites(today: LocalDate, sourceIds: Set<String>?, includeUndated: Boolean, includePriceless: Boolean, onProgress: ((Int, String) -> Unit)?): List<IncomingItem> {
            requestedSourceIds = sourceIds
            failure?.let { throw it }
            return favoriteItems
        }

        override suspend fun sources(): List<Source> = emptyList()

        override suspend fun setAction(key: String, action: Action) {
            actions += key to action
        }

        override fun lastDbModifiedTimestamp(): Long = 0L
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
    }
}
