package com.meepleradar.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meepleradar.data.local.BggSelectionStore
import com.meepleradar.data.model.Action
import com.meepleradar.data.model.Game
import com.meepleradar.data.model.GameSavePreview
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.Offer
import com.meepleradar.data.remote.BoardGameDetails
import com.meepleradar.data.remote.BoardGameImageLookup
import com.meepleradar.data.remote.BggSeries
import com.meepleradar.data.repo.ListingRepository
import com.meepleradar.ui.incoming.orderedOffersForDisplay
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DetailUiState {
    object Loading : DetailUiState

    object NotFound : DetailUiState

    data class Error(val message: String) : DetailUiState

    data class Content(
        val item: IncomingItem,
        val offers: List<Offer>,
        val isFavorite: Boolean = false,
        val actionError: String? = null,
        val boardGameDetails: BoardGameDetails? = null,
        val isLoadingDetails: Boolean = false,
        val detailsError: String? = null,
        val series: BggSeries? = null,
        val isLoadingSeries: Boolean = false,
        val seriesError: String? = null,
        val bggCandidates: List<BoardGameDetails> = emptyList(),
        val isLoadingBggCandidates: Boolean = false,
        val manualBggId: Int? = null,
        val isSearchingBgg: Boolean = false,
        val bggSearchError: String? = null,
        val isPendingDbSave: Boolean = false,
        val isSavingToDb: Boolean = false,
        val saveToDbSuccess: Boolean = false,
        val saveToDbError: String? = null,
        val isUpdatingBggStats: Boolean = false,
        val updateBggStatsSuccess: Boolean = false,
        val updateBggStatsError: String? = null,
        val isFromDatabase: Boolean = false,

        /** Form inserimento manuale prodotto (Task 7/8). */
        val manualProductTitle: String? = null,
        val manualProductUrl: String? = null,
        val manualProductPrice: Double? = null,
        val manualProductCurrency: String? = null,
        val manualProductError: String? = null,
        val isEditingManualProduct: Boolean = false,
        val savePreview: GameSavePreview? = null,
    ) : DetailUiState
}

class DetailViewModel(
    private val repo: ListingRepository,
    private val key: String,
    private val today: LocalDate = LocalDate.now(),
    private val imageLookup: BoardGameImageLookup? = null,
    private val bggSelectionStore: BggSelectionStore = NoOpBggSelectionStore,
    private val fallbackItem: IncomingItem? = null,
) : ViewModel() {

    private var pendingSaveItem: IncomingItem? = null

    private val _state = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = DetailUiState.Loading
        viewModelScope.launch {
            try {
                var item = repo.findItem(key, today)
                val isFromDatabase = item?.game?.id != null && item.game.id > 0L
                if (item == null && fallbackItem != null) {
                    item = fallbackItem
                } else if (item == null) {
                    _state.value = DetailUiState.NotFound
                    return@launch
                }

                val isFavorite = repo.favorites(today).any { it.key == key }
                _state.value = DetailUiState.Content(
                    item = item,
                    offers = orderedOffersForDisplay(item.offers),
                    isFavorite = isFavorite,
                    isFromDatabase = isFromDatabase,
                    isPendingDbSave = !isFromDatabase,
                    isLoadingDetails = imageLookup != null,
                )

                runCatching {
                    repo.setAction(key, Action.SEEN)
                    item.bggSelectionKeys.forEach { selKey ->
                        repo.setAction(selKey, Action.SEEN)
                    }
                }
                val lookup = imageLookup ?: return@launch
                var details: BoardGameDetails? = null
                try {
                    val customDesc = item.customDescription?.takeIf(String::isNotBlank)
                        ?: item.game?.description?.takeIf(String::isNotBlank)
                    val title = item.lookupTitle ?: item.title
                    val selectionKey = selectionKeyFor(title)
                    val bggSelectionKeys = (item.bggSelectionKeys + key).distinct()
                    val savedBggId = bggSelectionKeys.firstNotNullOfOrNull(bggSelectionStore::getBggId)
                        ?: bggSelectionStore.getBggId(selectionKey)
                    val bggId = savedBggId ?: item.game?.bggId
                    details = bggId
                        ?.takeIf { it > 0 }
                        ?.let { lookup.findDetailsById(it) }
                        ?: lookup.findDetails(title, null)
                    if (!customDesc.isNullOrBlank() && details != null) {
                        details = details.copy(description = customDesc)
                    }
                    updateContent {
                        it.copy(
                            boardGameDetails = details,
                            isLoadingDetails = false,
                            isLoadingBggCandidates = details == null,
                            manualBggId = savedBggId,
                            isPendingDbSave = !isFromDatabase || savedBggId != null,
                        )
                    }
                    if (details == null) {
                        val candidates = lookup.findCandidates(title, bggId)
                        updateContent {
                            it.copy(bggCandidates = candidates, isLoadingBggCandidates = false)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    updateContent {
                        it.copy(
                            isLoadingDetails = false,
                            isLoadingBggCandidates = false,
                            detailsError = e.message ?: DETAILS_ERROR,
                        )
                    }
                }

                val bggIdToLoadSeries = details?.bggId ?: item.game?.bggId
                if (bggIdToLoadSeries != null && bggIdToLoadSeries > 0) {
                    updateContent { it.copy(isLoadingSeries = true) }
                    try {
                        val series = lookup.findSeries(bggIdToLoadSeries)
                        updateContent { it.copy(series = series, isLoadingSeries = false) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        updateContent {
                            it.copy(
                                isLoadingSeries = false,
                                seriesError = e.message,
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = DetailUiState.Error(e.message ?: DEFAULT_ERROR)
            }
        }
    }

    fun selectBggCandidate(candidate: BoardGameDetails) {
        val content = _state.value as? DetailUiState.Content ?: return
        val title = content.item.lookupTitle ?: content.item.title
        val selectionKeys = (content.item.bggSelectionKeys + key).distinct()
        selectionKeys.forEach { bggSelectionStore.saveBggId(it, candidate.bggId) }
        bggSelectionStore.saveBggId(selectionKeyFor(title), candidate.bggId)
        val customDesc = content.item.customDescription?.takeIf(String::isNotBlank)
            ?: content.item.game?.description?.takeIf(String::isNotBlank)
        val selected = if (customDesc.isNullOrBlank()) candidate else candidate.copy(description = customDesc)
        updateContent {
            it.copy(
                boardGameDetails = selected,
                bggCandidates = emptyList(),
                isLoadingBggCandidates = false,
                isLoadingSeries = imageLookup != null,
                series = null,
                seriesError = null,
                manualBggId = candidate.bggId,
                isPendingDbSave = true,
                saveToDbSuccess = false,
                saveToDbError = null,
            )
        }
        val lookup = imageLookup ?: return
        viewModelScope.launch {
            try {
                val series = lookup.findSeries(candidate.bggId)
                updateContent { it.copy(series = series, isLoadingSeries = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateContent {
                    it.copy(isLoadingSeries = false, seriesError = e.message)
                }
            }
        }
    }

    fun saveToDatabase() {
        val content = _state.value as? DetailUiState.Content ?: return
        val details = content.boardGameDetails
        val listingId = content.item.key.removePrefix("listing:").toLongOrNull()
            ?: content.item.bggSelectionKeys.firstNotNullOfOrNull { it.removePrefix("listing:").toLongOrNull() }
            ?: 0L
        val currentGameId = content.item.game?.id

        updateContent {
            it.copy(
                isSavingToDb = true,
                saveToDbError = null,
            )
        }

        viewModelScope.launch {
            try {
                if (details != null) {
                    val lookup = imageLookup
                    val latestDetails = lookup?.findDetailsById(details.bggId) ?: details
                    val customDescription = content.item.customDescription?.takeIf(String::isNotBlank)
                        ?: content.item.game?.description?.takeIf(String::isNotBlank)
                    val (savedGameId, savedProductId) = repo.linkListingToBgg(
                        listingId = listingId,
                        currentGameId = currentGameId,
                        details = latestDetails,
                        fallbackTitle = content.item.title,
                    )
                    if (savedProductId == null) {
                        updateContent {
                            it.copy(
                                isSavingToDb = false,
                                saveToDbError = "Il prodotto non è stato creato. Verifica i permessi Supabase.",
                            )
                        }
                        return@launch
                    }
                    updateContent {
                        val savedGame = savedGameId?.let { id ->
                            val existing = content.item.game
                            Game(
                                id = id,
                                name = latestDetails.primaryName ?: latestDetails.englishName ?: content.item.title,
                                kind = if (latestDetails.expands.isNotEmpty()) "expansion" else "base",
                                bggId = latestDetails.bggId,
                                bggRating = latestDetails.bggRating,
                                bggRank = latestDetails.bggRank,
                                imageUrl = latestDetails.imageUrl,
                                description = existing?.description,
                            )
                        }
                        it.copy(
                            item = if (savedGame == null) it.item else it.item.copy(game = savedGame),
                            boardGameDetails = if (customDescription == null) {
                                latestDetails
                            } else {
                                latestDetails.copy(description = customDescription)
                            },
                            isSavingToDb = false,
                            isPendingDbSave = false,
                            saveToDbSuccess = true,
                        )
                    }
                } else {
                    repo.saveGameToDatabase(content.item, emptyList())
                    updateContent {
                        it.copy(
                            isSavingToDb = false,
                            isPendingDbSave = false,
                            saveToDbSuccess = true,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateContent {
                    it.copy(
                        isSavingToDb = false,
                        saveToDbError = e.message ?: "Impossibile salvare sul Database.",
                    )
                }
            }
        }
    }

    fun searchBggCandidates(query: String) {
        val lookup = imageLookup ?: return
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return

        updateContent {
            it.copy(isSearchingBgg = true, bggSearchError = null)
        }
        viewModelScope.launch {
            try {
                val candidates = lookup.searchCandidates(normalizedQuery)
                updateContent {
                    it.copy(
                        bggCandidates = candidates,
                        isSearchingBgg = false,
                        bggSearchError = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateContent {
                    it.copy(
                        isSearchingBgg = false,
                        bggSearchError = e.message ?: "Ricerca BGG fallita.",
                    )
                }
            }
        }
    }

    fun updateMissingBggStats() {
        val content = _state.value as? DetailUiState.Content ?: return
        val game = content.item.game
        if (game == null && !content.saveToDbSuccess) return
        val bggId = game?.bggId ?: content.boardGameDetails?.bggId ?: return
        val lookup = imageLookup ?: run {
            updateContent { it.copy(updateBggStatsError = "Servizio BoardGameGeek non disponibile.") }
            return
        }
        if (content.isUpdatingBggStats) return

        updateContent {
            it.copy(
                isUpdatingBggStats = true,
                updateBggStatsSuccess = false,
                updateBggStatsError = null,
            )
        }
        viewModelScope.launch {
            try {
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
                updateContent {
                    val latestGame = it.item.game
                    val updatedItem = if (latestGame == null) {
                        it.item
                    } else {
                        it.item.copy(
                            game = latestGame.copy(
                                bggRating = latestDetails.bggRating ?: latestGame.bggRating,
                                bggRank = latestDetails.bggRank ?: latestGame.bggRank,
                                description = latestDetails.description ?: latestGame.description,
                            ),
                        )
                    }
                    it.copy(
                        item = updatedItem,
                        boardGameDetails = latestDetails,
                        isUpdatingBggStats = false,
                        updateBggStatsSuccess = true,
                        updateBggStatsError = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateContent {
                    it.copy(
                        isUpdatingBggStats = false,
                        updateBggStatsSuccess = false,
                        updateBggStatsError = e.message ?: "Impossibile aggiornare voto e rank BGG.",
                    )
                }
            }
        }
    }

    /** Attiva/disattiva la modalità di inserimento manuale del prodotto (Task 7/8). */
    fun toggleManualProductEditing() {
        val content = _state.value as? DetailUiState.Content ?: return
        updateContent {
            it.copy(
                isEditingManualProduct = !it.isEditingManualProduct,
                manualProductTitle = null,
                manualProductUrl = null,
                manualProductPrice = null,
                manualProductCurrency = null,
                manualProductError = null,
            )
        }
    }

    /** Aggiorna un campo del form di inserimento manuale prodotto. */
    fun updateManualProductField(
        title: String? = null,
        url: String? = null,
        price: Double? = null,
        currency: String? = null,
    ) {
        val content = _state.value as? DetailUiState.Content ?: return
        updateContent {
            it.copy(
                manualProductTitle = title,
                manualProductUrl = url,
                manualProductPrice = price,
                manualProductCurrency = currency,
            )
        }
    }

    /** Salva il prodotto inserito manualmente (Task 7/8). Upsert idempotente di game/product/listing. */
    fun saveManualProduct() {
        val content = _state.value as? DetailUiState.Content ?: return
        val title = content.manualProductTitle?.trim()
        val url = content.manualProductUrl?.trim()
        val price = content.manualProductPrice
        val currency = content.manualProductCurrency?.takeIf { it.isNotBlank() }

        if (title.isNullOrBlank()) {
            updateContent { it.copy(manualProductError = "Inserisci un titolo valido.") }
            return
        }
        if (url.isNullOrBlank()) {
            updateContent { it.copy(manualProductError = "Inserisci un URL valido.") }
            return
        }

        updateContent {
            it.copy(
                isSavingToDb = true,
                saveToDbError = null,
                manualProductError = null,
            )
        }

        viewModelScope.launch {
            try {
                val details = content.boardGameDetails
                val bggId = details?.bggId ?: content.item.game?.bggId ?: 0
                if (bggId <= 0) {
                    throw IllegalStateException("ID BGG non disponibile per il gioco.")
                }

                val itemToSave = content.item.copy(
                    title = title,
                    imageUrl = details?.imageUrl ?: content.item.imageUrl,
                    game = Game(
                        id = content.item.game?.id ?: 0L,
                        name = title,
                        kind = if (details?.expands?.isNotEmpty() == true) "expansion" else "base",
                        bggId = bggId,
                        bggRating = details?.bggRating ?: content.item.game?.bggRating,
                        bggRank = details?.bggRank ?: content.item.game?.bggRank,
                        imageUrl = details?.imageUrl ?: content.item.imageUrl,
                        description = content.item.customDescription ?: content.item.game?.description,
                    ),
                    customDescription = null,
                )

                val preview = repo.previewGameSave(itemToSave)
                pendingSaveItem = itemToSave

                val selectedIds = preview.listings.filter { it.isSelected }.map { it.id }
                val savedGameId = repo.saveGameToDatabase(itemToSave, selectedIds)
                pendingSaveItem = null

                updateContent {
                    it.copy(
                        item = if (itemToSave.game != null && savedGameId != null) {
                            it.item.copy(game = itemToSave.game.copy(id = savedGameId))
                        } else {
                            it.item
                        },
                        isSavingToDb = false,
                        isPendingDbSave = false,
                        isFromDatabase = savedGameId != null,
                        saveToDbSuccess = true,
                        savePreview = null,
                        isEditingManualProduct = false,
                        manualProductTitle = null,
                        manualProductUrl = null,
                        manualProductPrice = null,
                        manualProductCurrency = null,
                        manualProductError = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pendingSaveItem = null
                updateContent {
                    it.copy(
                        isSavingToDb = false,
                        saveToDbError = e.message ?: "Impossibile salvare il prodotto manuale.",
                        manualProductError = e.message ?: "Impossibile salvare il prodotto manuale.",
                    )
                }
            }
        }
    }

    fun toggleFavorite() {
        val content = _state.value as? DetailUiState.Content ?: return
        val newFavorite = !content.isFavorite
        val action = if (newFavorite) Action.FAVORITE else Action.UNFAVORITED
        viewModelScope.launch {
            try {
                repo.setAction(key, action)
                updateContent { it.copy(isFavorite = newFavorite, actionError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateContent {
                    it.copy(actionError = e.message ?: ACTION_ERROR)
                }
            }
        }
    }

    fun ignore(onBack: () -> Unit) {
        viewModelScope.launch {
            try {
                repo.setAction(key, Action.IGNORED)
                onBack()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateContent {
                    it.copy(actionError = e.message ?: ACTION_ERROR)
                }
            }
        }
    }

    fun removeManualBggSelection() {
        val content = _state.value as? DetailUiState.Content ?: return
        val title = content.item.lookupTitle ?: content.item.title
        val selectionKeys = (content.item.bggSelectionKeys + key).distinct()
        selectionKeys.forEach { bggSelectionStore.removeBggId(it) }
        bggSelectionStore.removeBggId(selectionKeyFor(title))
        refresh()
    }

    private fun selectionKeyFor(title: String): String =
        "title:" + title.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), "")

    private inline fun updateContent(block: (DetailUiState.Content) -> DetailUiState.Content) {
        val current = _state.value as? DetailUiState.Content ?: return
        _state.value = block(current)
    }

    private companion object {
        const val DEFAULT_ERROR = "Impossibile caricare i dettagli."
        const val DETAILS_ERROR = "Impossibile caricare i dettagli da BoardGameGeek."
        const val ACTION_ERROR = "Impossibile completare l'operazione."
    }
}

private object NoOpBggSelectionStore : BggSelectionStore {
    override fun getBggId(itemKey: String): Int? = null
    override fun saveBggId(itemKey: String, bggId: Int) {}
    override fun removeBggId(itemKey: String) {}
}
