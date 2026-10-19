package com.meepleradar.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meepleradar.data.model.Action
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.repo.ListingRepository
import com.meepleradar.ui.incoming.IncomingUiState
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FavoritesViewModel(
    private val repo: ListingRepository,
    private val today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val _state = MutableStateFlow<IncomingUiState>(IncomingUiState.Loading)
    val state: StateFlow<IncomingUiState> = _state.asStateFlow()

    fun refresh() {
        _state.value = IncomingUiState.Loading
        viewModelScope.launch {
            _state.value = try {
                val items = repo.favorites(today)
                IncomingUiState.Content(items = items, favorites = items.map { it.key }.toSet())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                IncomingUiState.Error(e.message ?: DEFAULT_ERROR)
            }
        }
    }

    fun toggleFavorite(item: IncomingItem) {
        viewModelScope.launch {
            try {
                repo.setAction(item.key, Action.UNFAVORITED)
                remove(item.key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = IncomingUiState.Error(e.message ?: DEFAULT_ERROR)
            }
        }
    }

    fun ignore(item: IncomingItem) {
        viewModelScope.launch {
            try {
                repo.setAction(item.key, Action.IGNORED)
                remove(item.key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = IncomingUiState.Error(e.message ?: DEFAULT_ERROR)
            }
        }
    }

    private fun remove(key: String) {
        val content = _state.value as? IncomingUiState.Content ?: return
        val items = content.items.filterNot { it.key == key }
        _state.value = content.copy(items = items, favorites = items.map { it.key }.toSet())
    }

    private companion object {
        const val DEFAULT_ERROR = "Errore di rete"
    }
}
