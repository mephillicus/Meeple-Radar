package com.meepleradar.ui.detail

import com.meepleradar.data.model.Action
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.ItemKind
import com.meepleradar.data.model.Offer
import com.meepleradar.data.model.Price
import com.meepleradar.data.model.Source
import com.meepleradar.data.remote.BoardGameDetails
import com.meepleradar.data.remote.BoardGameImageLookup
import com.meepleradar.data.repo.ListingRepository
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
class DetailViewModelTest {

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
        private val items: List<IncomingItem>,
        private var favoriteItems: List<IncomingItem> = emptyList(),
        private val failure: Throwable? = null,
    ) : ListingRepository {
        var requestedSourceIds: Set<String>? = emptySet()
        val actions = mutableListOf<Pair<String, Action>>()

        override suspend fun incoming(today: LocalDate, sourceIds: Set<String>?, includeUndated: Boolean, includePriceless: Boolean, forceRefresh: Boolean, onProgress: ((Int, String) -> Unit)?): List<IncomingItem> {
            requestedSourceIds = sourceIds
            failure?.let { throw it }
            return items
        }

        override suspend fun favorites(today: LocalDate, sourceIds: Set<String>?, includeUndated: Boolean, includePriceless: Boolean, forceRefresh: Boolean, onProgress: ((Int, String) -> Unit)?): List<IncomingItem> = favoriteItems

        override suspend fun sources(): List<Source> = emptyList()

        override suspend fun setAction(key: String, action: Action) {
            actions += key to action
            favoriteItems = when (action) {
                Action.FAVORITE -> items.filter { it.key == key }
                Action.UNFAVORITED, Action.IGNORED -> emptyList()
            }
        }

        override fun lastDbModifiedTimestamp(): Long = 0L

        override fun newlyAddedCount(): Int = 0

        override fun newlyAddedCountFlow(): StateFlow<Int> = MutableStateFlow(0)

        override suspend fun newsItems(today: LocalDate): List<IncomingItem> = emptyList()
    }

    private class FakeImageLookup(
        private val details: BoardGameDetails? = null,
        private val failure: Throwable? = null,
    ) : BoardGameImageLookup {
        override suspend fun findImage(title: String, bggId: Int?): String? = null

        override suspend fun findDetails(title: String, bggId: Int?): BoardGameDetails? {
            failure?.let { throw it }
            return details
        }
    }

    private fun offer(sourceId: String, amount: Double?, currency: String?) = Offer(
        sourceId = sourceId,
        sourceName = sourceId,
        price = if (amount != null && currency != null) Price(amount, currency) else null,
        url = "https://example.com/$sourceId",
        availability = null,
    )

    private fun item(key: String, offers: List<Offer>) = IncomingItem(
        key = key,
        title = "Gioco $key",
        imageUrl = null,
        releaseDate = null,
        kind = ItemKind.NEW_UNDATED,
        game = null,
        offers = offers,
    )

    @Test
    fun `content espone le offerte ordinate per prezzo crescente con gli n d in fondo`() = runTest(dispatcher) {
        val item = item(
            key = "game:5",
            offers = listOf(
                offer("no-price", null, null),
                offer("usd", 30.0, "USD"),
                offer("eur-high", 49.9, "EUR"),
                offer("eur-low", 39.9, "EUR"),
            ),
        )

        val repo = FakeRepo(listOf(item))
        val viewModel = DetailViewModel(repo, key = "game:5", today = today)
        assertEquals(DetailUiState.Loading, viewModel.state.value)

        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is DetailUiState.Content)
        state as DetailUiState.Content
        assertEquals("game:5", state.item.key)
        assertEquals(
            listOf("eur-low", "eur-high", "usd", "no-price"),
            state.offers.map { it.sourceId },
        )
        assertEquals(null, repo.requestedSourceIds)
    }

    @Test
    fun `carica specifiche del gioco e cerca tra tutti i negozi`() = runTest(dispatcher) {
        val details = BoardGameDetails(
            bggId = 13,
            imageUrl = null,
            description = "Descrizione",
            yearPublished = 1995,
            minPlayers = 3,
            maxPlayers = 4,
            playingTime = 90,
            minAge = 10,
            designers = listOf("Autore"),
            categories = emptyList(),
            mechanics = emptyList(),
        )
        val repo = FakeRepo(listOf(item("game:5", emptyList())))
        val viewModel = DetailViewModel(
            repo = repo,
            key = "game:5",
            today = today,
            imageLookup = FakeImageLookup(details),
        )

        advanceUntilIdle()

        val state = viewModel.state.value as DetailUiState.Content
        assertEquals(null, repo.requestedSourceIds)
        assertEquals(details, state.boardGameDetails)
        assertEquals(false, state.isLoadingDetails)
    }

    @Test
    fun `errore nel caricamento del gioco e visibile invece di una pagina vuota`() = runTest(dispatcher) {
        val viewModel = DetailViewModel(
            repo = FakeRepo(emptyList(), failure = IllegalStateException("Connessione assente")),
            key = "game:5",
            today = today,
        )

        advanceUntilIdle()

        assertEquals(DetailUiState.Error("Connessione assente"), viewModel.state.value)
    }

    @Test
    fun `chiave inesistente produce NotFound`() = runTest(dispatcher) {
        val viewModel = DetailViewModel(
            repo = FakeRepo(listOf(item("game:5", emptyList()))),
            key = "game:99",
            today = today,
        )

        advanceUntilIdle()

        assertEquals(DetailUiState.NotFound, viewModel.state.value)
    }

    @Test
    fun `dettaglio legge e aggiorna lo stato preferito`() = runTest(dispatcher) {
        val game = item("game:5", emptyList())
        val repo = FakeRepo(items = listOf(game), favoriteItems = listOf(game))
        val viewModel = DetailViewModel(repo, key = "game:5", today = today)

        advanceUntilIdle()
        assertTrue((viewModel.state.value as DetailUiState.Content).isFavorite)

        viewModel.toggleFavorite()
        advanceUntilIdle()

        assertEquals(listOf("game:5" to Action.UNFAVORITED), repo.actions)
        assertEquals(false, (viewModel.state.value as DetailUiState.Content).isFavorite)
    }

    @Test
    fun `ignora un gioco e invoca il callback dopo il salvataggio`() = runTest(dispatcher) {
        val repo = FakeRepo(items = listOf(item("game:5", emptyList())))
        val viewModel = DetailViewModel(repo, key = "game:5", today = today)
        var ignored = false
        advanceUntilIdle()

        viewModel.ignore { ignored = true }
        advanceUntilIdle()

        assertEquals(listOf("game:5" to Action.IGNORED), repo.actions)
        assertTrue(ignored)
    }
}
