package com.meepleradar.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meepleradar.data.model.Action
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.Offer
import com.meepleradar.data.remote.BoardGameDetails
import com.meepleradar.data.remote.BoardGameImageLookup
import com.meepleradar.data.repo.ListingRepository
import java.time.LocalDate
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
    ) : DetailUiState
}

class DetailViewModel(
    private val repo: ListingRepository,
    private val key: String,
    private val today: LocalDate = LocalDate.now(),
    private val imageLookup: BoardGameImageLookup? = null,
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
                val item = repo.incoming(today).firstOrNull { it.key == key }
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
                try {
                    val customDesc = item.customDescription ?: item.game?.description
                    var details = lookup.findDetails(item.title, item.game?.bggId)
                    if (details == null && item.game != null) {
                        details = BoardGameDetails(
                            bggId = item.game.bggId ?: 0,
                            imageUrl = item.game.imageUrl,
                            description = customDesc,
                            yearPublished = null,
                            minPlayers = null,
                            maxPlayers = null,
                            playingTime = null,
                            minAge = null,
                            designers = emptyList(),
                            categories = emptyList(),
                            mechanics = emptyList(),
                        )
                    } else if (!customDesc.isNullOrBlank() && details != null) {
                        details = details.copy(description = customDesc)
                    }
                    updateContent { it.copy(boardGameDetails = details, isLoadingDetails = false) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    updateContent {
                        it.copy(
                            isLoadingDetails = false,
                            detailsError = e.message ?: DETAILS_ERROR,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = DetailUiState.Error(e.message ?: DEFAULT_ERROR)
            }
        }
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
