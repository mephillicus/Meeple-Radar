package com.meepleradar.ui.incoming

import com.meepleradar.data.model.Action
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.ItemKind
import com.meepleradar.data.model.Source
import com.meepleradar.data.repo.ListingRepository
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
class IncomingViewModelTest {

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
        private val incomingItems: List<IncomingItem> = emptyList(),
        private val favoriteItems: List<IncomingItem> = emptyList(),
        private val failure: Throwable? = null,
        private val actionFailure: Throwable? = null,
    ) : ListingRepository {
        val actions = mutableListOf<Pair<String, Action>>()

        override suspend fun incoming(today: LocalDate, sourceIds: Set<String>?, includeUndated: Boolean, includePriceless: Boolean): List<IncomingItem> {
            failure?.let { throw it }
            return incomingItems
        }

        override suspend fun favorites(today: LocalDate, sourceIds: Set<String>?, includeUndated: Boolean, includePriceless: Boolean): List<IncomingItem> {
            failure?.let { throw it }
            return favoriteItems
        }

        override suspend fun sources(): List<Source> = emptyList()

        override suspend fun setAction(key: String, action: Action) {
            actionFailure?.let { throw it }
            actions += key to action
        }
    }

    private fun item(key: String, title: String) = IncomingItem(
        key = key,
        title = title,
        imageUrl = null,
        releaseDate = null,
        kind = ItemKind.NEW_UNDATED,
        game = null,
        offers = emptyList(),
    )

    @Test
    fun `refresh passa da Loading a Content con gli item`() = runTest(dispatcher) {
        val item = item("game:5", "Catan")
        val viewModel = IncomingViewModel(
            repo = FakeRepo(incomingItems = listOf(item), favoriteItems = listOf(item)),
            today = today,
        )

        viewModel.refresh()
        assertEquals(IncomingUiState.Loading, viewModel.state.value)

        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is IncomingUiState.Content)
        state as IncomingUiState.Content
        assertEquals(listOf("game:5"), state.items.map { it.key })
        assertEquals(setOf("game:5"), state.favorites)

        viewModel.refresh()
        assertEquals(IncomingUiState.Loading, viewModel.state.value)

        advanceUntilIdle()
        assertTrue(viewModel.state.value is IncomingUiState.Content)
    }

    @Test
    fun `errore della repository produce Error con messaggio leggibile`() = runTest(dispatcher) {
        val viewModel = IncomingViewModel(
            repo = FakeRepo(failure = IOException("connessione assente")),
            today = today,
        )

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is IncomingUiState.Error)
        assertEquals("connessione assente", (state as IncomingUiState.Error).message)
    }

    @Test
    fun `errore senza messaggio produce Error con messaggio di default`() = runTest(dispatcher) {
        val viewModel = IncomingViewModel(repo = FakeRepo(failure = RuntimeException()), today = today)

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals("Errore di rete", (viewModel.state.value as IncomingUiState.Error).message)
    }

    @Test
    fun `toggleFavorite chiama setAction FAVORITE e aggiorna favorites`() = runTest(dispatcher) {
        val item = item("game:5", "Catan")
        val repo = FakeRepo(incomingItems = listOf(item), favoriteItems = emptyList())
        val viewModel = IncomingViewModel(repo, today)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.toggleFavorite(item)
        advanceUntilIdle()

        assertEquals(listOf("game:5" to Action.FAVORITE), repo.actions)
        assertEquals(setOf("game:5"), (viewModel.state.value as IncomingUiState.Content).favorites)

        viewModel.toggleFavorite(item)
        advanceUntilIdle()

        assertEquals(
            listOf("game:5" to Action.FAVORITE, "game:5" to Action.UNFAVORITED),
            repo.actions,
        )
        assertTrue((viewModel.state.value as IncomingUiState.Content).favorites.isEmpty())
    }

    @Test
    fun `errore nel salvataggio preferito viene mostrato`() = runTest(dispatcher) {
        val item = item("game:5", "Catan")
        val viewModel = IncomingViewModel(
            repo = FakeRepo(incomingItems = listOf(item), actionFailure = IOException("salvataggio fallito")),
            today = today,
        )
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.toggleFavorite(item)
        advanceUntilIdle()

        assertEquals(IncomingUiState.Error("salvataggio fallito"), viewModel.state.value)
    }

    @Test
    fun `ignore chiama setAction IGNORED e rimuove l item`() = runTest(dispatcher) {
        val item = item("game:5", "Catan")
        val repo = FakeRepo(incomingItems = listOf(item))
        val viewModel = IncomingViewModel(repo, today)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.ignore(item)
        advanceUntilIdle()

        assertEquals(listOf("game:5" to Action.IGNORED), repo.actions)
        assertTrue((viewModel.state.value as IncomingUiState.Content).items.isEmpty())
    }

    @Test
    fun `applyFilters e resetFilters aggiornano lo stato dei filtri e fanno il refresh`() = runTest(dispatcher) {
        val viewModel = IncomingViewModel(repo = FakeRepo(), today = today)
        assertEquals(false, viewModel.hideUndated.value)
        assertEquals(null, viewModel.selectedSources.value)

        viewModel.applyFilters(sourceIds = setOf("s1"), hideUndated = true)
        assertEquals(true, viewModel.hideUndated.value)
        assertEquals(setOf("s1"), viewModel.selectedSources.value)

        viewModel.resetFilters()
        assertEquals(false, viewModel.hideUndated.value)
        assertEquals(null, viewModel.selectedSources.value)
    }
}
