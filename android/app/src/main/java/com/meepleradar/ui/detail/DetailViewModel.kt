package com.meepleradar.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.Offer
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

    data class Content(val item: IncomingItem, val offers: List<Offer>) : DetailUiState
}

class DetailViewModel(
    private val repo: ListingRepository,
    private val key: String,
    private val today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val _state = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = try {
                val item = repo.incoming(today).firstOrNull { it.key == key }
                if (item == null) {
                    DetailUiState.NotFound
                } else {
                    DetailUiState.Content(item = item, offers = orderOffers(item.offers))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DetailUiState.NotFound
            }
        }
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
