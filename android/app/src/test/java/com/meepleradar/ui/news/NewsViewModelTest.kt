package com.meepleradar.ui.news

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NewsViewModelTest {

    private val today = LocalDate.of(2026, 11, 20)
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeNewsRepo(
        var news: List<IncomingItem> = emptyList(),
        var refreshedNews: List<IncomingItem> = news,
        var favorites: Set<String> = emptySet(),
        var timestamp: Long = 100L,
    ) : ListingRepository {
        var refreshNewsCalled = 0
        var batchSeenCalls = 0
        private val localSeen = mutableSetOf<String>()

        override suspend fun incoming(
            today: LocalDate,
            sourceIds: Set<String>?,
            includeUndated: Boolean,
            includePriceless: Boolean,
            forceRefresh: Boolean,
            onProgress: ((Int, String) -> Unit)?,
        ): List<IncomingItem> = news

        override suspend fun favorites(
            today: LocalDate,
            sourceIds: Set<String>?,
            includeUndated: Boolean,
            includePriceless: Boolean,
            forceRefresh: Boolean,
            onProgress: ((Int, String) -> Unit)?,
        ): List<IncomingItem> = news.filter { it.key in favorites }

        override suspend fun favoriteKeys(today: LocalDate): Set<String> = favorites

        override suspend fun newsItems(today: LocalDate): List<IncomingItem> = news

        override suspend fun refreshNewsItems(today: LocalDate): List<IncomingItem> {
            refreshNewsCalled++
            news = refreshedNews
            timestamp += 100L
            return refreshedNews
        }

        override suspend fun preorderItems(today: LocalDate): List<IncomingItem> = emptyList()

        override suspend fun findItem(key: String, today: LocalDate): IncomingItem? =
            news.firstOrNull { it.key == key }

        override suspend fun sources(): List<Source> = emptyList()

        override suspend fun setAction(key: String, action: Action) {
            if (action == Action.SEEN) localSeen.add(key)
        }

        override fun seenKeys(): Set<String> = localSeen.toSet()

        override fun markLocalSeen(key: String, seen: Boolean) {
            if (seen) localSeen.add(key) else localSeen.remove(key)
        }

        override fun markAllLocalSeen(keys: Collection<String>) {
            batchSeenCalls++
            localSeen.addAll(keys)
        }

        override fun lastDbModifiedTimestamp(): Long = timestamp

        override fun newlyAddedCount(): Int = news.count { it.isNewlyAdded }

        override fun newlyAddedCountFlow(): StateFlow<Int> = MutableStateFlow(newlyAddedCount())

        override fun preorderCount(): Int = 0

        override suspend fun previewGameSave(item: IncomingItem): GameSavePreview = GameSavePreview()

        override suspend fun saveGameToDatabase(item: IncomingItem, selectedListingIdsToLink: List<Long>): Long? = null
    }

    private fun item(
        key: String,
        title: String,
        isNewlyAdded: Boolean = false,
        isSeen: Boolean = false,
    ) = IncomingItem(
        key = key,
        title = title,
        imageUrl = null,
        releaseDate = today.minusDays(2),
        kind = ItemKind.UPCOMING,
        game = null,
        offers = emptyList(),
        isNewlyAdded = isNewlyAdded,
        isSeen = isSeen,
    )

    @Test
    fun `newlyAddedCount e stato reattivo e si aggiorna su load refresh e ignore`() = runTest(dispatcher) {
        val item1 = item("game:1", "Uno", isNewlyAdded = true)
        val item2 = item("game:2", "Due", isNewlyAdded = false)
        val item3 = item("game:3", "Tre", isNewlyAdded = true)
        val repo = FakeNewsRepo(
            news = listOf(item1, item2),
            refreshedNews = listOf(item1, item2, item3),
        )

        val vm = NewsViewModel(repo, today)
        // Chiamata immediata prima del completamento iniziale non sovrascrive Loading
        vm.refreshSeenStatus()
        assertEquals(NewsUiState.Loading, vm.state.value)

        advanceUntilIdle()

        assertEquals(1, vm.newlyAddedCount.value)
        val loaded = vm.state.value as NewsUiState.Content
        assertEquals(listOf("game:1", "game:2"), loaded.items.map { it.key })

        vm.refresh()
        advanceUntilIdle()

        assertEquals(1, repo.refreshNewsCalled)
        assertEquals(2, vm.newlyAddedCount.value)

        vm.ignore(item1)
        advanceUntilIdle()

        assertEquals(1, vm.newlyAddedCount.value)
    }

    @Test
    fun `markAllAsSeen aggiorna subito la UI in batch e resetFilters azzera anche showOnlyNewlyAdded`() = runTest(dispatcher) {
        val item1 = item("game:1", "Uno", isNewlyAdded = true, isSeen = false)
        val item2 = item("game:2", "Due", isNewlyAdded = false, isSeen = false)
        val repo = FakeNewsRepo(news = listOf(item1, item2))
        val vm = NewsViewModel(repo, today)
        advanceUntilIdle()

        vm.markAllAsSeen()

        val content = vm.state.value as NewsUiState.Content
        assertTrue(content.items.all { it.isSeen })
        assertEquals(1, repo.batchSeenCalls)

        vm.toggleNewlyAddedFilter()
        assertTrue(vm.showOnlyNewlyAdded.value)
        assertEquals(listOf("game:1"), (vm.state.value as NewsUiState.Content).items.map { it.key })

        vm.resetFilters()
        assertFalse(vm.showOnlyNewlyAdded.value)
        assertEquals(listOf("game:1", "game:2"), (vm.state.value as NewsUiState.Content).items.map { it.key })
    }

    @Test
    fun `unseenCount e toggleHideSeen filtrano correttamente i giochi gia visti`() = runTest(dispatcher) {
        val item1 = item("game:1", "Uno", isSeen = false)
        val item2 = item("game:2", "Due", isSeen = true)
        val item3 = item("game:3", "Tre", isSeen = false)
        val repo = FakeNewsRepo(news = listOf(item1, item2, item3))
        val vm = NewsViewModel(repo, today)
        advanceUntilIdle()

        assertEquals(2, vm.unseenCount.value)
        assertFalse(vm.hideSeen.value)
        assertEquals(listOf("game:1", "game:2", "game:3"), (vm.state.value as NewsUiState.Content).items.map { it.key })

        vm.toggleHideSeen()
        assertTrue(vm.hideSeen.value)
        assertEquals(2, vm.unseenCount.value)
        assertEquals(listOf("game:1", "game:3"), (vm.state.value as NewsUiState.Content).items.map { it.key })

        vm.markAllAsSeen()
        assertEquals(0, vm.unseenCount.value)
        assertEquals(emptyList<String>(), (vm.state.value as NewsUiState.Content).items.map { it.key })

        vm.toggleHideSeen()
        assertFalse(vm.hideSeen.value)
        val allItems = (vm.state.value as NewsUiState.Content).items
        assertEquals(listOf("game:1", "game:2", "game:3"), allItems.map { it.key })
        assertTrue(allItems.all { it.isSeen })
    }
}
