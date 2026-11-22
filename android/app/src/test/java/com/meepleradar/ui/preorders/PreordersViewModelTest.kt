package com.meepleradar.ui.preorders

import com.meepleradar.data.model.Action
import com.meepleradar.data.model.GameSavePreview
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.ItemKind
import com.meepleradar.data.model.Source
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
class PreordersViewModelTest {

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

    private class FakePreordersRepo(
        var preorders: List<IncomingItem> = emptyList(),
        var favoriteKeySet: Set<String> = emptySet(),
        var sourceList: List<Source> = emptyList(),
    ) : ListingRepository {
        val catalogRevision = MutableStateFlow(0L)
        var refreshPreordersCalls = 0

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

        override suspend fun favoriteKeys(today: LocalDate): Set<String> = favoriteKeySet

        override suspend fun newsItems(today: LocalDate): List<IncomingItem> = emptyList()

        override suspend fun preorderItems(today: LocalDate): List<IncomingItem> = preorders

        override suspend fun refreshPreorderItems(today: LocalDate): List<IncomingItem> {
            refreshPreordersCalls++
            catalogRevision.value += 1L
            return preorders
        }

        override suspend fun findItem(key: String, today: LocalDate): IncomingItem? =
            preorders.firstOrNull { it.key == key }

        override suspend fun sources(): List<Source> = sourceList

        override suspend fun setAction(key: String, action: Action) {
            if (action != Action.SEEN) {
                catalogRevision.value += 1L
            }
        }

        override suspend fun previewGameSave(item: IncomingItem): GameSavePreview = GameSavePreview()

        override suspend fun saveGameToDatabase(item: IncomingItem, selectedListingIdsToLink: List<Long>): Long? = null

        override fun lastDbModifiedTimestamp(): Long = 100L

        override fun catalogRevisionFlow(): StateFlow<Long> = catalogRevision

        override fun newlyAddedCount(): Int = 0

        override fun newlyAddedCountFlow(): StateFlow<Int> = MutableStateFlow(0)

        override fun preorderCount(): Int = preorders.size
    }

    private fun preorderItem(key: String, title: String) = IncomingItem(
        key = key,
        title = title,
        imageUrl = null,
        releaseDate = today.plusDays(15),
        kind = ItemKind.UPCOMING,
        game = null,
        offers = emptyList(),
    )

    @Test
    fun `si aggiorna automaticamente quando un altro tab aggiorna Supabase e incrementa catalogRevisionFlow`() = runTest(dispatcher) {
        val initialItem = preorderItem("game:1", "Catan")
        val repo = FakePreordersRepo(
            preorders = listOf(initialItem),
            favoriteKeySet = emptySet(),
            sourceList = listOf(Source("s1", "Store Uno")),
        )
        val viewModel = PreordersViewModel(repo, today)
        advanceUntilIdle()

        val initialState = viewModel.state.value as PreordersUiState.Content
        assertEquals(listOf("game:1"), initialState.items.map { it.key })
        assertEquals(0, repo.refreshPreordersCalls)

        // Simula aggiornamento Supabase lanciato da un altro tab (es. Lista o Novita)
        val newItem = preorderItem("game:2", "Brass")
        repo.preorders = listOf(initialItem, newItem)
        repo.favoriteKeySet = setOf("game:2")
        repo.sourceList = listOf(Source("s1", "Store Uno"), Source("s2", "Store Due"))
        repo.catalogRevision.value = 1L
        advanceUntilIdle()

        val updatedState = viewModel.state.value as PreordersUiState.Content
        assertEquals(listOf("game:1", "game:2"), updatedState.items.map { it.key })
        assertEquals(setOf("game:2"), updatedState.favorites)
        assertEquals(listOf("s1", "s2"), viewModel.sources.value.map { it.id })
        assertEquals(0, repo.refreshPreordersCalls)
    }

    @Test
    fun `refresh diretto richiama refreshPreorderItems e aggiorna lo stato`() = runTest(dispatcher) {
        val repo = FakePreordersRepo(preorders = listOf(preorderItem("game:1", "Catan")))
        val viewModel = PreordersViewModel(repo, today)
        advanceUntilIdle()

        repo.preorders = listOf(preorderItem("game:1", "Catan"), preorderItem("game:3", "Dune"))
        viewModel.refresh(showLoadingState = false)
        advanceUntilIdle()

        assertEquals(1, repo.refreshPreordersCalls)
        val state = viewModel.state.value as PreordersUiState.Content
        assertEquals(listOf("game:1", "game:3"), state.items.map { it.key })
        assertTrue(!viewModel.isRefreshing.value)
    }
}
