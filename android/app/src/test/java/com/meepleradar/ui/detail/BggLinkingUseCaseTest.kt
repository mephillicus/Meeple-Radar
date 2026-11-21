package com.meepleradar.ui.detail

import com.meepleradar.data.local.BggSelectionStore
import com.meepleradar.data.model.Action
import com.meepleradar.data.model.CandidateListingLink
import com.meepleradar.data.model.Game
import com.meepleradar.data.model.GameSavePreview
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.ItemKind
import com.meepleradar.data.model.Source
import com.meepleradar.data.remote.BggLink
import com.meepleradar.data.remote.BggSeries
import com.meepleradar.data.remote.BoardGameDetails
import com.meepleradar.data.remote.BoardGameImageLookup
import com.meepleradar.data.repo.ListingRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BggLinkingUseCaseTest {

    private class FakeSelectionStore : BggSelectionStore {
        val map = mutableMapOf<String, Int>()
        override fun getBggId(itemKey: String): Int? = map[itemKey]
        override fun saveBggId(itemKey: String, bggId: Int) {
            map[itemKey] = bggId
        }
        override fun removeBggId(itemKey: String) {
            map.remove(itemKey)
        }
    }

    private class FakeLookup(
        private val byId: Map<Int, BoardGameDetails> = emptyMap(),
        private val byTitle: BoardGameDetails? = null,
    ) : BoardGameImageLookup {
        override suspend fun findImage(title: String, bggId: Int?): String? = null
        override suspend fun findDetails(title: String, bggId: Int?): BoardGameDetails? = byTitle
        override suspend fun findDetailsById(bggId: Int): BoardGameDetails? = byId[bggId]
        override suspend fun refreshDetailsById(bggId: Int): BoardGameDetails? = byId[bggId]
        override suspend fun findSeries(bggId: Int): BggSeries? = null
    }

    private class FakeRepo(
        private val linkResult: Pair<Long?, Long?> = 50L to 100L,
        private val previewListings: List<CandidateListingLink> = emptyList(),
        private val saveGameResult: Long? = 77L,
    ) : ListingRepository {
        var lastLinkedListingId: Long? = null
        var lastSavedIds: List<Long>? = null
        var lastStatsCall: Triple<Int, Double?, Int?>? = null

        override suspend fun incoming(today: LocalDate, sourceIds: Set<String>?, includeUndated: Boolean, includePriceless: Boolean, forceRefresh: Boolean, onProgress: ((Int, String) -> Unit)?): List<IncomingItem> = emptyList()
        override suspend fun favorites(today: LocalDate, sourceIds: Set<String>?, includeUndated: Boolean, includePriceless: Boolean, forceRefresh: Boolean, onProgress: ((Int, String) -> Unit)?): List<IncomingItem> = emptyList()
        override suspend fun newsItems(today: LocalDate): List<IncomingItem> = emptyList()
        override suspend fun preorderItems(today: LocalDate): List<IncomingItem> = emptyList()
        override suspend fun findItem(key: String, today: LocalDate): IncomingItem? = null
        override suspend fun sources(): List<Source> = emptyList()
        override suspend fun setAction(key: String, action: Action) = Unit
        override fun lastDbModifiedTimestamp(): Long = 0L
        override fun newlyAddedCount(): Int = 0
        override fun newlyAddedCountFlow(): StateFlow<Int> = MutableStateFlow(0)
        override fun preorderCount(): Int = 0
        override suspend fun previewGameSave(item: IncomingItem): GameSavePreview = GameSavePreview(previewListings)
        override suspend fun saveGameToDatabase(item: IncomingItem, selectedListingIdsToLink: List<Long>): Long? {
            lastSavedIds = selectedListingIdsToLink
            return saveGameResult
        }
        override suspend fun linkListingToBgg(listingId: Long, currentGameId: Long?, details: BoardGameDetails, fallbackTitle: String): Pair<Long?, Long?> {
            lastLinkedListingId = listingId
            return linkResult
        }
        override suspend fun updateGameBggStats(bggId: Int, bggRating: Double?, bggRank: Int?, description: String?) {
            lastStatsCall = Triple(bggId, bggRating, bggRank)
        }
    }

    private fun details(
        bggId: Int,
        name: String = "Dune",
        rating: Double? = 8.1,
        rank: Int? = 12,
        expands: List<BggLink> = emptyList(),
    ) = BoardGameDetails(
        bggId = bggId,
        imageUrl = "https://img/$bggId.jpg",
        description = "BGG desc",
        yearPublished = 2024,
        minPlayers = 2,
        maxPlayers = 4,
        playingTime = 90,
        minAge = 12,
        designers = emptyList(),
        categories = emptyList(),
        mechanics = emptyList(),
        primaryName = name,
        bggRating = rating,
        bggRank = rank,
        expands = expands,
    )

    private fun sampleItem(
        key: String = "listing:42",
        title: String = "Dune Imperium",
        game: Game? = null,
        customDescription: String? = null,
    ) = IncomingItem(
        key = key,
        title = title,
        imageUrl = null,
        releaseDate = null,
        kind = ItemKind.NEW_UNDATED,
        game = game,
        offers = emptyList(),
        customDescription = customDescription,
        bggSelectionKeys = listOf(key, "product:9"),
    )

    @Test
    fun `resolveDetails preferisce selezione manuale salvata e mantiene la descrizione personalizzata`() = runTest {
        val store = FakeSelectionStore()
        val item = sampleItem(customDescription = "Descrizione italiana del negozio")
        val useCase = BggLinkingUseCase(
            repo = FakeRepo(),
            imageLookup = FakeLookup(byId = mapOf(333 to details(333, "Dune Manual"))),
            bggSelectionStore = store,
        )

        useCase.persistManualSelection(item, item.key, 333)
        val result = useCase.resolveDetails(item, item.key)

        assertEquals(333, result.manualBggId)
        assertEquals(333, result.details?.bggId)
        assertEquals("Descrizione italiana del negozio", result.details?.description)

        useCase.clearManualSelection(item, item.key)
        assertTrue(store.map.isEmpty())
    }

    @Test
    fun `linkAndSave solleva errore se il prodotto non viene creato e classifica le espansioni`() = runTest {
        val failingUseCase = BggLinkingUseCase(
            repo = FakeRepo(linkResult = 50L to null),
            imageLookup = FakeLookup(),
            bggSelectionStore = FakeSelectionStore(),
        )
        try {
            failingUseCase.linkAndSave(sampleItem(), details(10))
            fail("Attesa eccezione quando productId è null")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("prodotto"))
        }

        val repo = FakeRepo(linkResult = 50L to 100L)
        val expansionDetails = details(11, "Rise of Ix", expands = listOf(BggLink(10, "Dune", null)))
        val okUseCase = BggLinkingUseCase(
            repo = repo,
            imageLookup = FakeLookup(byId = mapOf(11 to expansionDetails)),
            bggSelectionStore = FakeSelectionStore(),
        )
        val saved = okUseCase.linkAndSave(sampleItem(), expansionDetails)

        assertEquals(42L, repo.lastLinkedListingId)
        assertEquals(50L, saved.updatedGame?.id)
        assertEquals("expansion", saved.updatedGame?.kind)
    }

    @Test
    fun `updateMissingBggStats valida voto e rank e aggiorna il gioco`() = runTest {
        val item = sampleItem(
            key = "game:5",
            game = Game(5L, "Dune", "base", 99, null, null, null, null),
        )
        val emptyStatsUseCase = BggLinkingUseCase(
            repo = FakeRepo(),
            imageLookup = FakeLookup(byId = mapOf(99 to details(99, rating = null, rank = null))),
            bggSelectionStore = FakeSelectionStore(),
        )
        try {
            emptyStatsUseCase.updateMissingBggStats(item, 99)
            fail("Attesa eccezione se rating e rank sono entrambi null")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("voto"))
        }

        val repo = FakeRepo()
        val okUseCase = BggLinkingUseCase(
            repo = repo,
            imageLookup = FakeLookup(byId = mapOf(99 to details(99, rating = 8.4, rank = 7))),
            bggSelectionStore = FakeSelectionStore(),
        )
        val updated = okUseCase.updateMissingBggStats(item, 99)

        assertEquals(Triple(99, 8.4, 7), repo.lastStatsCall)
        assertEquals(8.4, updated.updatedGame?.bggRating!!, 1e-9)
        assertEquals(7, updated.updatedGame.bggRank)
    }

    @Test
    fun `saveManualProduct collega solo i listing selezionati dalla preview`() = runTest {
        val repo = FakeRepo(
            previewListings = listOf(
                CandidateListingLink(1L, "s1", "Store 1", "Dune", 40.0, "EUR", null, null, true, isSelected = true),
                CandidateListingLink(2L, "s2", "Store 2", "Dune 2", 45.0, "EUR", 9L, "Altro", false, isSelected = false),
            ),
            saveGameResult = 77L,
        )
        val useCase = BggLinkingUseCase(repo, FakeLookup(), FakeSelectionStore())
        val result = useCase.saveManualProduct(sampleItem(), details(321), "Dune Edizione IT")

        assertEquals(listOf(1L), repo.lastSavedIds)
        assertEquals(77L, result.savedGameId)
        assertEquals(77L, result.savedGame?.id)
        assertNull(useCase.customDescriptionFor(sampleItem()))
    }
}
