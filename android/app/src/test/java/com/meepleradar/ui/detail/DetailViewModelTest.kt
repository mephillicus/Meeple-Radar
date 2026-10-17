package com.meepleradar.ui.detail

import com.meepleradar.data.model.Action
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.ItemKind
import com.meepleradar.data.model.Offer
import com.meepleradar.data.model.Price
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

    private class FakeRepo(private val items: List<IncomingItem>) : ListingRepository {
        override suspend fun incoming(today: LocalDate): List<IncomingItem> = items

        override suspend fun favorites(today: LocalDate): List<IncomingItem> = emptyList()

        override suspend fun setAction(key: String, action: Action) = Unit
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

        val viewModel = DetailViewModel(FakeRepo(listOf(item)), key = "game:5", today = today)
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
}
