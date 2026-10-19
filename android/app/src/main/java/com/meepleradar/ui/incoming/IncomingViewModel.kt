package com.meepleradar.ui.incoming

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meepleradar.data.model.Action
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.Source
import com.meepleradar.data.repo.ListingRepository
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface IncomingUiState {
    object Loading : IncomingUiState

    data class Error(val message: String) : IncomingUiState

    data class Content(val items: List<IncomingItem>, val favorites: Set<String>) : IncomingUiState
}

class IncomingViewModel(
    private val repo: ListingRepository,
    private val today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val _state = MutableStateFlow<IncomingUiState>(IncomingUiState.Loading)
    val state: StateFlow<IncomingUiState> = _state.asStateFlow()

    private val _sources = MutableStateFlow<List<Source>>(emptyList())
    val sources: StateFlow<List<Source>> = _sources.asStateFlow()

    private val _selectedSources = MutableStateFlow<Set<String>?>(null)
    val selectedSources: StateFlow<Set<String>?> = _selectedSources.asStateFlow()

    private val _hideUndated = MutableStateFlow<Boolean>(false)
    val hideUndated: StateFlow<Boolean> = _hideUndated.asStateFlow()

    private val _hidePriceless = MutableStateFlow<Boolean>(false)
    val hidePriceless: StateFlow<Boolean> = _hidePriceless.asStateFlow()

    init {
        loadSources()
    }

    fun applyFilters(sourceIds: Set<String>?, hideUndated: Boolean, hidePriceless: Boolean = false) {
        _selectedSources.value = sourceIds
        _hideUndated.value = hideUndated
        _hidePriceless.value = hidePriceless
        refresh()
    }

    fun resetFilters() {
        _selectedSources.value = null
        _hideUndated.value = false
        _hidePriceless.value = false
        refresh()
    }

    fun refresh() {
        _state.value = IncomingUiState.Loading
        viewModelScope.launch {
            _state.value = try {
                IncomingUiState.Content(
                    items = repo.incoming(
                        today = today,
                        sourceIds = _selectedSources.value,
                        includeUndated = !_hideUndated.value,
                        includePriceless = !_hidePriceless.value,
                    ),
                    favorites = repo.favorites(
                        today = today,
                        sourceIds = _selectedSources.value,
                        includeUndated = !_hideUndated.value,
                        includePriceless = !_hidePriceless.value,
                    ).map { it.key }.toSet(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                IncomingUiState.Error(e.message ?: DEFAULT_ERROR)
            }
        }
    }

    fun loadSources() {
        viewModelScope.launch {
            try {
                _sources.value = repo.sources()
            } catch (e: Exception) {
                _sources.value = emptyList()
            }
        }
    }



    fun toggleFavorite(item: IncomingItem) {
        val content = _state.value as? IncomingUiState.Content ?: return
        val wasFavorite = item.key in content.favorites
        val action = if (wasFavorite) Action.UNFAVORITED else Action.FAVORITE

        viewModelScope.launch {
            try {
                repo.setAction(item.key, action)
                val current = _state.value as? IncomingUiState.Content ?: return@launch
                val favorites = if (wasFavorite) {
                    current.favorites - item.key
                } else {
                    current.favorites + item.key
                }
                _state.value = current.copy(favorites = favorites)
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
                val content = _state.value as? IncomingUiState.Content ?: return@launch
                _state.value = content.copy(items = content.items.filterNot { it.key == item.key })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = IncomingUiState.Error(e.message ?: DEFAULT_ERROR)
            }
        }
    }

    private companion object {
        const val DEFAULT_ERROR = "Errore di rete"
    }
}
