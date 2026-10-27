package com.meepleradar.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meepleradar.data.local.BggSelectionStore
import com.meepleradar.data.model.Action
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.Offer
import com.meepleradar.data.remote.BggSeries
import com.meepleradar.data.remote.BoardGameDetails
import com.meepleradar.data.remote.BoardGameImageLookup
import com.meepleradar.data.repo.ListingRepository
import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
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
    ) : DetailUiState
}

class DetailViewModel(
    private val repo: ListingRepository,
    private val key: String,
    private val today: LocalDate = LocalDate.now(),
    private val imageLookup: BoardGameImageLookup? = null,
    private val bggSelectionStore: BggSelectionStore = NoOpBggSelectionStore,
) : ViewModel() {

    private val _state = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = DetailUiState.Loading
        viewModelScope.launch {
            try {
                val item = repo.findItem(key, today)
                if (item == null) {
                    _state.value = DetailUiState.NotFound
                    return@launch
                }

                val isFavorite = repo.favorites(today).any { it.key == key }
                _state.value = DetailUiState.Content(
                    item = item,
                    offers = orderOffers(item.offers),
                    isFavorite = isFavorite,
                    isLoadingDetails = imageLookup != null,
                )
                val lookup = imageLookup ?: return@launch
                var details: BoardGameDetails? = null
                try {
                    val customDesc = item.customDescription ?: item.game?.description
                    val title = item.lookupTitle ?: item.title
                    val selectionKey = selectionKeyFor(title)
                    val bggSelectionKeys = (item.bggSelectionKeys + key).distinct()
                    val savedBggId = bggSelectionKeys.firstNotNullOfOrNull(bggSelectionStore::getBggId)
                        ?: bggSelectionStore.getBggId(selectionKey)
                    details = if (savedBggId != null) {
                        lookup.findDetailsById(savedBggId)
                    } else {
                        lookup.findDetails(title, item.game?.bggId)
                    }
                    if (!customDesc.isNullOrBlank() && details != null) {
                        details = details.copy(description = customDesc)
                    }
                    updateContent {
                        it.copy(
                            boardGameDetails = details,
                            isLoadingDetails = false,
                            isLoadingBggCandidates = details == null && savedBggId == null,
                            manualBggId = savedBggId,
                        )
                    }
                    if (details == null && savedBggId == null) {
                        val candidates = lookup.findCandidates(title, item.game?.bggId)
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

                val validBggId = details?.bggId?.takeIf { it > 0 }
                if (validBggId != null) {
                    updateContent { it.copy(isLoadingSeries = true) }
                    try {
                        val seriesData = lookup.findSeries(validBggId)
                        updateContent { it.copy(series = seriesData, isLoadingSeries = false) }
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
        val customDesc = content.item.customDescription ?: content.item.game?.description
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
                        bggSearchError = e.message ?: DETAILS_ERROR,
                    )
                }
            }
        }
    }

    fun removeManualBggSelection() {
        val content = _state.value as? DetailUiState.Content ?: return
        val title = content.item.lookupTitle ?: content.item.title
        bggSelectionStore.removeBggId(selectionKeyFor(title))
        (content.item.bggSelectionKeys + key).distinct().forEach(bggSelectionStore::removeBggId)
        refresh()
    }

    private fun selectionKeyFor(title: String): String {
        val normalized = Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase(Locale.ROOT)
            .filter(Char::isLetterOrDigit)
        return "title:$normalized"
    }

    fun toggleFavorite() {
        val content = _state.value as? DetailUiState.Content ?: return
        val action = if (content.isFavorite) Action.UNFAVORITED else Action.FAVORITE
        viewModelScope.launch {
            try {
                repo.setAction(key, action)
                updateContent { it.copy(isFavorite = !content.isFavorite) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = DetailUiState.Error(e.message ?: DEFAULT_ERROR)
            }
        }
    }

    private object NoOpBggSelectionStore : BggSelectionStore {
        override fun getBggId(itemKey: String): Int? = null
        override fun saveBggId(itemKey: String, bggId: Int) = Unit
        override fun removeBggId(itemKey: String) = Unit
    }

    fun ignore(onIgnored: () -> Unit) {
        if (_state.value !is DetailUiState.Content) return
        viewModelScope.launch {
            try {
                repo.setAction(key, Action.IGNORED)
                onIgnored()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = DetailUiState.Error(e.message ?: DEFAULT_ERROR)
            }
        }
    }

    private fun updateContent(transform: (DetailUiState.Content) -> DetailUiState.Content) {
        val content = _state.value as? DetailUiState.Content ?: return
        _state.value = transform(content)
    }

    private companion object {
        const val DEFAULT_ERROR = "Impossibile caricare il gioco"
        const val DETAILS_ERROR = "Specifiche BGG non disponibili"
    }
}

internal fun orderOffers(offers: List<Offer>): List<Offer> = offers.sortedWith(
    compareBy<Offer> { offer ->
        when {
            offer.price?.currency == "EUR" -> EUR_GROUP
            offer.price != null -> OTHER_CURRENCY_GROUP
            else -> NO_PRICE_GROUP
        }
    }
        .thenBy { it.price?.amount ?: Double.MAX_VALUE }
        .thenBy { it.sourceName },
)

private const val EUR_GROUP = 0
private const val OTHER_CURRENCY_GROUP = 1
private const val NO_PRICE_GROUP = 2
