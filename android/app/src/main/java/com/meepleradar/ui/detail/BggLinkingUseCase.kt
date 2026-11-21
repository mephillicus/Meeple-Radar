package com.meepleradar.ui.detail

import com.meepleradar.data.local.BggSelectionStore
import com.meepleradar.data.model.Game
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.remote.BggSeries
import com.meepleradar.data.remote.BoardGameDetails
import com.meepleradar.data.remote.BoardGameImageLookup
import com.meepleradar.data.repo.ListingRepository
import java.util.Locale

data class BggDetailsLookupResult(
    val details: BoardGameDetails?,
    val manualBggId: Int?,
    val effectiveBggId: Int?,
)

data class BggLinkSaveResult(
    val updatedGame: Game?,
    val resolvedDetails: BoardGameDetails,
)

data class BggStatsUpdateResult(
    val updatedGame: Game?,
    val latestDetails: BoardGameDetails,
)

data class ManualProductSaveResult(
    val savedGameId: Long?,
    val savedGame: Game?,
)

/**
 * Incapsula la logica di risoluzione, associazione, salvataggio e aggiornamento
 * dei metadati BoardGameGeek per una scheda dettaglio.
 */
class BggLinkingUseCase(
    private val repo: ListingRepository,
    private val imageLookup: BoardGameImageLookup?,
    private val bggSelectionStore: BggSelectionStore,
) {

    fun selectionKeyFor(title: String): String =
        "title:" + title.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), "")

    fun customDescriptionFor(item: IncomingItem): String? =
        item.customDescription?.takeIf(String::isNotBlank)
            ?: item.game?.description?.takeIf(String::isNotBlank)

    fun applyCustomDescription(item: IncomingItem, details: BoardGameDetails): BoardGameDetails {
        val customDesc = customDescriptionFor(item)
        return if (customDesc.isNullOrBlank()) details else details.copy(description = customDesc)
    }

    fun persistManualSelection(item: IncomingItem, routeKey: String, bggId: Int) {
        val title = item.lookupTitle ?: item.title
        val selectionKeys = (item.bggSelectionKeys + routeKey).distinct()
        selectionKeys.forEach { bggSelectionStore.saveBggId(it, bggId) }
        bggSelectionStore.saveBggId(selectionKeyFor(title), bggId)
    }

    fun clearManualSelection(item: IncomingItem, routeKey: String) {
        val title = item.lookupTitle ?: item.title
        val selectionKeys = (item.bggSelectionKeys + routeKey).distinct()
        selectionKeys.forEach { bggSelectionStore.removeBggId(it) }
        bggSelectionStore.removeBggId(selectionKeyFor(title))
    }

    suspend fun resolveDetails(item: IncomingItem, routeKey: String): BggDetailsLookupResult {
        val lookup = imageLookup ?: return BggDetailsLookupResult(null, null, item.game?.bggId)
        val title = item.lookupTitle ?: item.title
        val selectionKey = selectionKeyFor(title)
        val bggSelectionKeys = (item.bggSelectionKeys + routeKey).distinct()
        val savedBggId = bggSelectionKeys.firstNotNullOfOrNull(bggSelectionStore::getBggId)
            ?: bggSelectionStore.getBggId(selectionKey)
        val effectiveBggId = savedBggId ?: item.game?.bggId
        val rawDetails = effectiveBggId
            ?.takeIf { it > 0 }
            ?.let { lookup.findDetailsById(it) }
            ?: lookup.findDetails(title, null)
        val details = rawDetails?.let { applyCustomDescription(item, it) }
        return BggDetailsLookupResult(
            details = details,
            manualBggId = savedBggId,
            effectiveBggId = effectiveBggId,
        )
    }

    suspend fun findCandidates(item: IncomingItem, bggId: Int?): List<BoardGameDetails> {
        val lookup = imageLookup ?: return emptyList()
        val title = item.lookupTitle ?: item.title
        return lookup.findCandidates(title, bggId)
    }

    suspend fun searchCandidates(query: String): List<BoardGameDetails> {
        val lookup = imageLookup ?: return emptyList()
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return emptyList()
        return lookup.searchCandidates(normalizedQuery)
    }

    suspend fun loadSeries(bggId: Int): BggSeries? =
        imageLookup?.findSeries(bggId)

    suspend fun linkAndSave(item: IncomingItem, details: BoardGameDetails): BggLinkSaveResult {
        val listingId = item.key.removePrefix("listing:").toLongOrNull()
            ?: item.bggSelectionKeys.firstNotNullOfOrNull { it.removePrefix("listing:").toLongOrNull() }
            ?: 0L
        val currentGameId = item.game?.id
        val latestDetails = imageLookup?.findDetailsById(details.bggId) ?: details
        val (savedGameId, savedProductId) = repo.linkListingToBgg(
            listingId = listingId,
            currentGameId = currentGameId,
            details = latestDetails,
            fallbackTitle = item.title,
        )
        if (savedProductId == null) {
            throw IllegalStateException("Il prodotto non è stato creato. Verifica i permessi Supabase.")
        }
        val savedGame = savedGameId?.let { id ->
            Game(
                id = id,
                name = latestDetails.primaryName ?: latestDetails.englishName ?: item.title,
                kind = if (latestDetails.expands.isNotEmpty()) "expansion" else "base",
                bggId = latestDetails.bggId,
                bggRating = latestDetails.bggRating,
                bggRank = latestDetails.bggRank,
                imageUrl = latestDetails.imageUrl,
                description = item.game?.description,
            )
        }
        return BggLinkSaveResult(
            updatedGame = savedGame,
            resolvedDetails = applyCustomDescription(item, latestDetails),
        )
    }

    suspend fun saveWithoutDetails(item: IncomingItem): Long? =
        repo.saveGameToDatabase(item, emptyList())

    suspend fun updateMissingBggStats(item: IncomingItem, bggId: Int): BggStatsUpdateResult {
        val lookup = imageLookup
            ?: throw IllegalStateException("Servizio BoardGameGeek non disponibile.")
        val latestDetails = lookup.refreshDetailsById(bggId)
            ?: throw IllegalStateException("Impossibile recuperare i dati da BoardGameGeek.")
        if (latestDetails.bggRating == null && latestDetails.bggRank == null) {
            throw IllegalStateException("BoardGameGeek non ha restituito né il voto né il rank.")
        }
        repo.updateGameBggStats(
            bggId = bggId,
            bggRating = latestDetails.bggRating,
            bggRank = latestDetails.bggRank,
            description = latestDetails.description,
        )
        val updatedGame = item.game?.copy(
            bggRating = latestDetails.bggRating ?: item.game.bggRating,
            bggRank = latestDetails.bggRank ?: item.game.bggRank,
            description = latestDetails.description ?: item.game.description,
        )
        return BggStatsUpdateResult(
            updatedGame = updatedGame,
            latestDetails = latestDetails,
        )
    }

    suspend fun saveManualProduct(
        item: IncomingItem,
        details: BoardGameDetails?,
        title: String,
    ): ManualProductSaveResult {
        val bggId = details?.bggId ?: item.game?.bggId ?: 0
        if (bggId <= 0) {
            throw IllegalStateException("ID BGG non disponibile per il gioco.")
        }

        val candidateGame = Game(
            id = item.game?.id ?: 0L,
            name = title,
            kind = if (details?.expands?.isNotEmpty() == true) "expansion" else "base",
            bggId = bggId,
            bggRating = details?.bggRating ?: item.game?.bggRating,
            bggRank = details?.bggRank ?: item.game?.bggRank,
            imageUrl = details?.imageUrl ?: item.imageUrl,
            description = item.customDescription ?: item.game?.description,
        )
        val itemToSave = item.copy(
            title = title,
            imageUrl = details?.imageUrl ?: item.imageUrl,
            game = candidateGame,
            customDescription = null,
        )

        val preview = repo.previewGameSave(itemToSave)
        val selectedIds = preview.listings.filter { it.isSelected }.map { it.id }
        val savedGameId = repo.saveGameToDatabase(itemToSave, selectedIds)
        return ManualProductSaveResult(
            savedGameId = savedGameId,
            savedGame = savedGameId?.let { candidateGame.copy(id = it) },
        )
    }
}
