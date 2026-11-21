package com.meepleradar.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meepleradar.data.local.BggSelectionStore
import com.meepleradar.data.model.Action
import com.meepleradar.data.model.GameSavePreview
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.Offer
import com.meepleradar.data.remote.BggSeries
import com.meepleradar.data.remote.BoardGameDetails
import com.meepleradar.data.remote.BoardGameImageLookup
import com.meepleradar.data.repo.ListingRepository
import com.meepleradar.ui.incoming.orderedOffersForDisplay
import java.time.LocalDate
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
    bggSelectionStore: BggSelectionStore = NoOpBggSelectionStore,
    private val fallbackItem: IncomingItem? = null,
) : ViewModel() {

    private val bggLinking = BggLinkingUseCase(
        repo = repo,
        imageLookup = imageLookup,
        bggSelectionStore = bggSelectionStore,
    )

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

                val isFavorite = key in repo.favoriteKeys(today)
                _state.value = DetailUiState.Content(
                    item = item,
                    offers = orderedOffersForDisplay(item.offers),
                    isFavorite = isFavorite,
                    isFromDatabase = isFromDatabase,
                    isPendingDbSave = !isFromDatabase,
                    isLoadingDetails = imageLookup != null,
                )

                markItemSeen(item)

                if (imageLookup == null) return@launch

                var resolvedDetails: BoardGameDetails? = null
                try {
                    val resolution = bggLinking.resolveDetails(item, key)
                    resolvedDetails = resolution.details
                    updateContent {
                        it.copy(
                            boardGameDetails = resolution.details,
                            isLoadingDetails = false,
                            isLoadingBggCandidates = resolution.details == null,
                            manualBggId = resolution.manualBggId,
                            isPendingDbSave = !isFromDatabase || resolution.manualBggId != null,
                        )
                    }
                    if (resolution.details == null) {
                        val candidates = bggLinking.findCandidates(item, resolution.effectiveBggId)
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

                val bggIdToLoadSeries = resolvedDetails?.bggId ?: item.game?.bggId
                if (bggIdToLoadSeries != null && bggIdToLoadSeries > 0) {
                    loadSeriesFor(bggIdToLoadSeries)
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
        bggLinking.persistManualSelection(content.item, key, candidate.bggId)
        val selected = bggLinking.applyCustomDescription(content.item, candidate)
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
        if (imageLookup == null) return
        viewModelScope.launch {
            loadSeriesFor(candidate.bggId)
        }
    }

    fun saveToDatabase() {
        val content = _state.value as? DetailUiState.Content ?: return
        val details = content.boardGameDetails

        updateContent {
            it.copy(
                isSavingToDb = true,
                saveToDbError = null,
            )
        }

        viewModelScope.launch {
            try {
                if (details != null) {
                    val result = bggLinking.linkAndSave(content.item, details)
                    updateContent {
                        it.copy(
                            item = if (result.updatedGame == null) it.item else it.item.copy(game = result.updatedGame),
                            boardGameDetails = result.resolvedDetails,
                            isSavingToDb = false,
                            isPendingDbSave = false,
                            saveToDbSuccess = true,
                        )
                    }
                } else {
                    bggLinking.saveWithoutDetails(content.item)
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
        if (imageLookup == null) return
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return

        updateContent {
            it.copy(isSearchingBgg = true, bggSearchError = null)
        }
        viewModelScope.launch {
            try {
                val candidates = bggLinking.searchCandidates(normalizedQuery)
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
        if (imageLookup == null) {
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
                val result = bggLinking.updateMissingBggStats(content.item, bggId)
                updateContent {
                    it.copy(
                        item = if (result.updatedGame == null) it.item else it.item.copy(game = result.updatedGame),
                        boardGameDetails = result.latestDetails,
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
                val result = bggLinking.saveManualProduct(
                    item = content.item,
                    details = content.boardGameDetails,
                    title = title,
                )
                updateContent {
                    it.copy(
                        item = if (result.savedGame != null) it.item.copy(game = result.savedGame) else it.item,
                        isSavingToDb = false,
                        isPendingDbSave = false,
                        isFromDatabase = result.savedGameId != null,
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
        bggLinking.clearManualSelection(content.item, key)
        refresh()
    }

    private suspend fun markItemSeen(item: IncomingItem) {
        runCatching {
            repo.setAction(key, Action.SEEN)
            if (item.key != key) {
                repo.setAction(item.key, Action.SEEN)
            }
            item.bggSelectionKeys.forEach { selKey ->
                if (selKey != key && selKey != item.key) {
                    repo.markLocalSeen(selKey, true)
                    runCatching { repo.setAction(selKey, Action.SEEN) }
                }
            }
            item.game?.bggId?.let { bggId ->
                repo.markLocalSeen("bgg:$bggId", true)
            }
        }
    }

    private suspend fun loadSeriesFor(bggId: Int) {
        updateContent { it.copy(isLoadingSeries = true) }
        try {
            val series = bggLinking.loadSeries(bggId)
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
